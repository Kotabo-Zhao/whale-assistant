package io.deepseekharness.mobile.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/** The quantized Kokoro model runs entirely on the device; speaker ids 45-48 are Chinese women. */
internal class PetKokoroVoice(private val context: Context) {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null
    private var tts: OfflineTts? = null
    fun speak(text: String, speaker: Int) {
        stop(); val current = generation
        executor.execute {
            runCatching {
                val dir = PetSpeechModels(context).directory(PetSpeechModels.Model.TTS)
                check(PetSpeechModels(context).installed(PetSpeechModels.Model.TTS)) { "请先下载角色音色模型" }
                fun path(name: String) = File(dir, name).absolutePath
                val synth = tts ?: OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(model = path("model.int8.onnx"), voices = path("voices.bin"), tokens = path("tokens.txt"),
                        lexicon = "${path("lexicon-us-en.txt")},${path("lexicon-zh.txt")}"), numThreads = 2),
                    ruleFsts = "${path("phone-zh.fst")},${path("date-zh.fst")},${path("number-zh.fst")}"))
                    .also { tts = it }
                val pieces = text.split(Regex("(?<=[。！？!?；;])")).flatMap { it.chunked(100) }.filter { it.isNotBlank() }
                for (piece in pieces) {
                    if (generation != current) break
                    val audio = synth.generate(piece, speaker, 1.0f)
                    if (generation != current || audio.samples.isEmpty()) break
                    val data = ShortArray(audio.samples.size) { (audio.samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                    val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(data.size * 2).build()
                    track = player
                    try {
                        player.write(data, 0, data.size); player.play()
                        val deadline = android.os.SystemClock.elapsedRealtime() + data.size * 1000L / audio.sampleRate + 2000
                        while (generation == current && player.playbackHeadPosition < data.size && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
                    } finally { runCatching { player.stop() }; player.release(); track = null }
                }
            }.onFailure { handler.post { Toast.makeText(context, "角色音色播放失败，请检查本地模型。", Toast.LENGTH_LONG).show() } }
        }
    }
    fun stop() { generation++; runCatching { track?.pause() } }
    fun close() { stop(); executor.execute { tts?.free(); tts = null }; executor.shutdown() }
}
