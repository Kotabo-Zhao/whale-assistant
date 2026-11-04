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
    private const val MAX_US = 10L * 60 * 1_000_000
    fun supports(ext: String, mime: String?) = mime?.startsWith("audio/") == true ||
        ext in setOf("mp3", "wav", "ogg", "opus", "m4a", "aac", "amr", "flac", "3gp", "silk")

    @Synchronized fun recognize(context: Context, file: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return Model(modelPath(context).absolutePath).use { model ->
                    Recognizer(model, RATE.toFloat()).use { recognizer ->
                        recognizer.setWords(true)
                        decode(extractor, codec, recognizer)
                    }
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

    private fun decode(extractor: MediaExtractor, codec: MediaCodec, recognizer: Recognizer): String {
        val info = MediaCodec.BufferInfo()
        val result = StringBuilder()
        var inputEnded = false
        var partial = false
        var resampler: MonoPcm? = null
        val start = SystemClock.elapsedRealtime()
        fun append(json: String) {
            val segment = JSONObject(json)
            val text = segment.optString("text").trim()
            if (text.isBlank()) return
            val seconds = segment.optJSONArray("result")?.optJSONObject(0)?.optDouble("start", 0.0)?.toInt() ?: 0
            result.append(String.format(Locale.ROOT, "[%02d:%02d] ", seconds / 60, seconds % 60))
                .append(text.replace(Regex("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])"), "")).append('\n')
        }
        while (true) {
            check(SystemClock.elapsedRealtime() - start < 12 * 60_000) { "音频解码超时" }
            if (!inputEnded) {
                val slot = codec.dequeueInputBuffer(10_000)
                if (slot >= 0) {
                    val time = extractor.sampleTime
                    val bytes = if (time < 0 || time >= MAX_US) -1 else extractor.readSampleData(checkNotNull(codec.getInputBuffer(slot)), 0)
                    if (bytes < 0) {
                        partial = time >= MAX_US
                        codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                    } else {
                        codec.queueInputBuffer(slot, 0, bytes, time, 0); extractor.advance()
                    }
                }
            }
            val slot = codec.dequeueOutputBuffer(info, 10_000)
            if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) resampler = MonoPcm(codec.outputFormat)
            if (slot < 0) continue
            try {
                if (info.size > 0) {
                    val pcm = resampler ?: MonoPcm(codec.outputFormat).also { resampler = it }
                    val bytes = checkNotNull(codec.getOutputBuffer(slot)).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.position(info.offset); bytes.limit(info.offset + info.size)
                    pcm.accept(bytes.asShortBuffer(), recognizer) { append(recognizer.result) }
                }
            } finally { codec.releaseOutputBuffer(slot, false) }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        append(recognizer.finalResult)
        check(result.isNotBlank()) { "没有识别到语音" }
        if (partial) result.append("[仅提取前 10 分钟语音]")
        return result.toString().trim()
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
        fun accept(input: java.nio.ShortBuffer, recognizer: Recognizer, onSegment: () -> Unit) {
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
                        if (recognizer.acceptWaveForm(buffer, count)) onSegment()
                        count = 0
                    }
                }
            }
            if (count > 0 && recognizer.acceptWaveForm(buffer, count)) onSegment()
        }
    }
}
