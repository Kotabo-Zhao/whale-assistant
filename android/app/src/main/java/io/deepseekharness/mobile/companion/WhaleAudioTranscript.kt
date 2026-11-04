package io.deepseekharness.mobile.companion

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream

/** Offline Mandarin file recognition. Model memory is released at the end of each import. */
internal object WhaleAudioTranscript {
    private const val MODEL = "vosk-model-small-cn-0.22"
    private const val RATE = 16_000
    private const val STALL_MS = 60_000L
    private val hanSpacing = Regex("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])")
    fun supports(ext: String, mime: String?) = mime?.startsWith("audio/") == true ||
        ext in setOf("mp3", "wav", "ogg", "opus", "m4a", "aac", "amr", "flac", "3gp", "silk")

    @Synchronized fun recognize(context: Context, file: File, fromSeconds: Int = 0, stopped: () -> Boolean = { false }): String {
        require(fromSeconds >= 0)
        check(!stopped()) { "音频解析已中断" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(index)
            val firstUs = extractor.sampleTime.coerceAtLeast(0)
            val cutoffUs = firstUs + fromSeconds * 1_000_000L
            if (fromSeconds > 0) extractor.seekTo(cutoffUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return Model(modelPath(context).absolutePath).use { model ->
                    Transcript(model).use { transcript -> decode(extractor, codec, transcript, firstUs, cutoffUs, stopped) }
                }
            } finally { codec.release() }
        } finally { extractor.release() }
    }

    private fun modelPath(context: Context): File {
        val root = File(context.noBackupFilesDir, "speech-models")
        val target = File(root, MODEL)
        if (target.isDirectory) return target
        check(root.isDirectory || root.mkdirs())
        val staging = java.nio.file.Files.createTempDirectory(root.toPath(), "unpack-").toFile()
        try {
            ZipInputStream(context.assets.open("models/$MODEL.zip").buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entry.name.startsWith("$MODEL/"))
                    val out = File(staging, entry.name)
                    require(out.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val unpacked = File(staging, MODEL)
            check(File(unpacked, "am/final.mdl").isFile && unpacked.renameTo(target))
            return target
        } finally { staging.deleteRecursively() }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec, transcript: Transcript, firstUs: Long, cutoffUs: Long, stopped: () -> Boolean): String {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var resampler: MonoPcm? = null
        var lastProgress = SystemClock.elapsedRealtime()
        while (!transcript.full) {
            check(!stopped() && !Thread.currentThread().isInterrupted) { "音频解析已中断" }
            check(SystemClock.elapsedRealtime() - lastProgress < STALL_MS) { "音频解码长时间没有进展" }
            if (!inputEnded) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val time = extractor.sampleTime
                    val size = if (time < 0) -1 else extractor.readSampleData(checkNotNull(codec.getInputBuffer(input)), 0)
                    if (size < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(input, 0, size, time, 0)
                        extractor.advance()
                    }
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
            val output = codec.dequeueOutputBuffer(info, 10_000)
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) resampler = MonoPcm(codec.outputFormat)
            if (output < 0) continue
            try {
                if (info.size > 0) {
                    val pcm = resampler ?: MonoPcm(codec.outputFormat).also { resampler = it }
                    val bytes = checkNotNull(codec.getOutputBuffer(output)).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.position(info.offset); bytes.limit(info.offset + info.size)
                    val input = bytes.asShortBuffer()
                    val skippedUs = pcm.skipBefore(input, cutoffUs - info.presentationTimeUs)
                    if (input.hasRemaining()) {
                        transcript.begin(info.presentationTimeUs + skippedUs - firstUs)
                        pcm.accept(input, transcript)
                    }
                }
                lastProgress = SystemClock.elapsedRealtime()
            } finally { codec.releaseOutputBuffer(output, false) }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        return transcript.finish(allowEmpty = cutoffUs > firstUs)
    }

    /** Offsets count decoded samples, including digital silence skipped by the recognizer. */
    private class Transcript(private val model: Model) : AutoCloseable {
        private var recognizer: Recognizer? = null
        private var position = -1L
        fun begin(timeUs: Long) {
            val sample = timeUs.coerceAtLeast(0) * RATE / 1_000_000
            if (position < 0) position = sample
            else if (sample > position + RATE / 4) {
                // Some containers represent a pause as a forward PTS jump rather than PCM samples.
                endUtterance()
                position = sample
            }
        }
        private var origin = 0L
        private var silence = 0
        private val text = StringBuilder()
        var full = false
            private set

        fun accept(pcm: ShortArray, size: Int) {
            if (full || size == 0) return
            val silent = (0 until size).all { pcm[it].toInt() == 0 }
            var rec = recognizer
            if (!silent && rec == null) {
                origin = position
                rec = Recognizer(model, RATE.toFloat()).also { recognizer = it; it.setWords(true) }
            }
            if (rec != null) {
                // Let ASR resolve trailing words before closing an utterance. Quiet nonzero signals are kept.
                val count = if (silent) minOf(size, 2 * RATE - silence) else size
                if (count > 0 && rec.acceptWaveForm(pcm, count)) append(rec.result)
                silence = if (silent) silence + count else 0
                if (silent && silence == 2 * RATE) endUtterance()
            }
            position += size
        }

        private fun append(json: String) {
            if (full) return
            val segment = JSONObject(json)
            val words = segment.optString("text").trim()
            if (words.isEmpty()) return
            val start = segment.optJSONArray("result")?.optJSONObject(0)?.optDouble("start", 0.0) ?: 0.0
            val seconds = (origin.toDouble() / RATE + start).toLong()
            val line = String.format(Locale.ROOT, "[%02d:%02d] ", seconds / 60, seconds % 60) + words.replace(hanSpacing,"") + "\n"
            val marker = "[内容过长，已截取]"
            val room = CompanionKnowledgeData.MAX_DOCUMENT_TEXT - marker.length - text.length
            if (line.length > room) {
                text.append(line.take(room.coerceAtLeast(0)).dropLastWhile { it.isHighSurrogate() }).append(marker)
                full = true
            } else text.append(line)
        }

        private fun endUtterance() {
            val rec = recognizer ?: return
            try { if (!full) append(rec.finalResult) } finally { close() }
        }

        fun finish(allowEmpty: Boolean): String {
            endUtterance()
            check(allowEmpty || text.isNotBlank()) { "没有识别到语音" }
            return text.toString().trim()
        }

        override fun close() { recognizer?.close(); recognizer = null; silence = 0 }
    }

    private class MonoPcm(format: MediaFormat) {
        private val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private val buffer = ShortArray(4096)
        private var phase = 0
        private var previous: Int? = null
        init {
            require(rate in 8000..192000 && channels in 1..16)
            require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
        }
        fun skipBefore(input: java.nio.ShortBuffer, deltaUs: Long): Long {
            if (deltaUs <= 0) return 0
            val frames = minOf((deltaUs * rate + 999_999) / 1_000_000, (input.remaining() / channels).toLong()).toInt()
            input.position(input.position() + frames * channels)
            return frames * 1_000_000L / rate
        }
        fun accept(input: java.nio.ShortBuffer, transcript: Transcript) {
            var count = 0
            while (input.remaining() >= channels) {
                var sum = 0
                repeat(channels) { sum += input.get().toInt() }
                val sample = sum / channels
                val last = previous ?: sample
                previous = sample
                phase += RATE
                while (phase >= rate) {
                    phase -= rate
                    buffer[count++] = (sample + (last - sample) * phase.toLong() / RATE).toShort()
                    if (count == buffer.size) {
                        transcript.accept(buffer, count)
                        count = 0
                    }
                }
            }
            if (count > 0) transcript.accept(buffer, count)
        }
    }
}
