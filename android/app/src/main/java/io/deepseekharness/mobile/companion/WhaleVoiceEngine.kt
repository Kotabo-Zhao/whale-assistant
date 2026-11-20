package io.deepseekharness.mobile.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 硅基流动（SiliconFlow）单 Key 原生语音引擎（零外部依赖）。
 *
 * - 听（ASR）：`FunAudioLLM/SenseVoiceSmall`（免费、高精度中文识别，`/v1/audio/transcriptions`）
 * - 说（TTS）：`FunAudioLLM/CosyVoice2-0.5B`（拟人情绪起伏，`/v1/audio/speech`，按句切分流水线播报）
 * - 支持自动复用自定义供应商里已填的 SiliconFlow Key，或使用专属语音 Key；支持随时一键打断播报。
 */
internal object WhaleVoiceEngine {
    private const val PREFS = "whale_voice_v1"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_VOICE = "voice"
    private const val KEY_SPEED = "speed"
    private const val KEY_AUTO_SPEAK = "auto_speak_pet"

    const val DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
    const val DEFAULT_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
    const val DEFAULT_TTS_MODEL = "FunAudioLLM/CosyVoice2-0.5B"
    const val DEFAULT_VOICE = "diana"

    val PRESET_VOICES = setOf("diana", "claire", "bella", "anna", "alex", "benjamin", "charles", "david")

    data class Config(
        val apiKey: String,
        val baseUrl: String,
        val voice: String,
        val speed: Float,
        val autoSpeakPet: Boolean,
    )

    private val ttsFetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-tts-fetch").apply { isDaemon = true }
    }
    private val ttsPlayExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-tts-play").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0L)

    @Volatile private var activePlayer: MediaPlayer? = null
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var recordFile: File? = null
    @Volatile var isRecording: Boolean = false
        private set

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun readConfig(context: Context): Config {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var key = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        var baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trim().orEmpty().ifBlank { DEFAULT_BASE_URL }

        // 若未单独填语音 Key，自动复用自定义供应商中已配置的 SiliconFlow Key
        if (key.isEmpty()) {
            runCatching {
                val store = RuntimeStore(app)
                for (custom in store.settings().customModelProviders) {
                    if (custom.baseUrl.contains("siliconflow", ignoreCase = true)) {
                        val reused = store.readModelCredential(custom.id)?.trim().orEmpty()
                        if (reused.isNotEmpty()) {
                            key = reused
                            baseUrl = custom.baseUrl.trim().trimEnd('/')
                            break
                        }
                    }
                }
            }
        }
        val voice = prefs.getString(KEY_VOICE, DEFAULT_VOICE)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_VOICE
        val speed = prefs.getFloat(KEY_SPEED, 1.05f).coerceIn(0.7f, 1.5f)
        val autoSpeak = prefs.getBoolean(KEY_AUTO_SPEAK, true)
        return Config(key, baseUrl.trimEnd('/'), voice, speed, autoSpeak)
    }

    fun saveConfig(
        context: Context,
        apiKey: String? = null,
        baseUrl: String? = null,
        voice: String? = null,
        speed: Float? = null,
        autoSpeakPet: Boolean? = null,
    ): Config {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        if (apiKey != null) edit.putString(KEY_API_KEY, apiKey.trim())
        if (!baseUrl.isNullOrBlank()) edit.putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
        if (!voice.isNullOrBlank()) edit.putString(KEY_VOICE, voice.trim())
        if (speed != null) edit.putFloat(KEY_SPEED, speed.coerceIn(0.7f, 1.5f))
        if (autoSpeakPet != null) edit.putBoolean(KEY_AUTO_SPEAK, autoSpeakPet)
        edit.apply()
        return readConfig(context)
    }

    fun snapshotJson(context: Context): JSONObject {
        val cfg = readConfig(context)
        return JSONObject()
            .put("configured", cfg.apiKey.isNotBlank())
            .put("baseUrl", cfg.baseUrl)
            .put("voice", cfg.voice)
            .put("speed", cfg.speed.toDouble())
            .put("autoSpeakPet", cfg.autoSpeakPet)
            .put("hasRecordPermission", hasRecordPermission(context))
            .put("asrModel", DEFAULT_ASR_MODEL)
            .put("ttsModel", DEFAULT_TTS_MODEL)
    }

    /** 清理 Markdown 符号与 URL，避免朗读时念出“星号/井号/链接”。 */
    internal fun cleanForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " 代码片段已省略。")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_~`#>|]"), " ")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** 按中英文句末标点切分自然短句，实现首句低延迟流水线播放。 */
    internal fun splitSentences(text: String, maxChunkChars: Int = 110): List<String> {
        val cleaned = cleanForSpeech(text)
        if (cleaned.isEmpty()) return emptyList()
        val rawParts = cleaned.split(Regex("(?<=[。！？!?；;\\n])"))
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (part in rawParts) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            if (buf.length + piece.length <= maxChunkChars) {
                buf.append(piece)
            } else {
                if (buf.isNotEmpty()) {
                    out.add(buf.toString())
                    buf.setLength(0)
                }
                if (piece.length <= maxChunkChars) {
                    buf.append(piece)
                } else {
                    var idx = 0
                    while (idx < piece.length) {
                        val end = (idx + maxChunkChars).coerceAtMost(piece.length)
                        out.add(piece.substring(idx, end))
                        idx = end
                    }
                }
            }
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out.take(12)
    }

    fun stopSpeaking() {
        generation.incrementAndGet()
        val p = activePlayer
        activePlayer = null
        runCatching {
            p?.stop()
            p?.release()
        }
    }

    /** 异步按句流式合成并播报（不阻塞主线程，支持随时打断）。 */
    fun speakText(context: Context, text: String, voiceOverride: String? = null) {
        val app = context.applicationContext
        val cfg = readConfig(app)
        if (cfg.apiKey.isBlank()) return
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return
        val targetVoice = voiceOverride?.trim()?.takeIf { it.isNotEmpty() } ?: cfg.voice
        val myGen = generation.incrementAndGet()
        runCatching { activePlayer?.stop(); activePlayer?.release(); activePlayer = null }

        ttsFetchExecutor.execute {
            for (sentence in sentences) {
                if (generation.get() != myGen) break
                val mp3File = runCatching { synthesizeToTempFile(app, cfg, sentence, targetVoice) }.getOrNull() ?: continue
                if (generation.get() != myGen) {
                    mp3File.delete()
                    break
                }
                ttsPlayExecutor.execute {
                    if (generation.get() != myGen) {
                        mp3File.delete()
                        return@execute
                    }
                    playAudioFileBlocking(mp3File, myGen)
                }
            }
        }
    }

    private fun synthesizeToTempFile(app: Context, cfg: Config, sentence: String, voiceName: String): File {
        val fullVoice = if (voiceName.contains(":")) voiceName else "$DEFAULT_TTS_MODEL:$voiceName"
        val body = JSONObject()
            .put("model", DEFAULT_TTS_MODEL)
            .put("input", sentence)
            .put("voice", fullVoice)
            .put("response_format", "mp3")
            .put("speed", cfg.speed.toDouble())

        val conn = (URL("${cfg.baseUrl}/audio/speech").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IllegalStateException("TTS HTTP $code: ${err.take(180)}")
            }
            val tmp = File(app.cacheDir, "whale-tts-${UUID.randomUUID()}.mp3")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            return tmp
        } finally {
            conn.disconnect()
        }
    }

    private fun playAudioFileBlocking(file: File, myGen: Long) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { latch.countDown() }
            player.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            player.prepare()
            if (generation.get() != myGen) return
            activePlayer = player
            player.start()
            latch.await(45, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // Ignore playback interruption
        } finally {
            if (activePlayer === player) activePlayer = null
            runCatching { player.release() }
            file.delete()
        }
    }

    @Synchronized
    fun startRecording(context: Context): String? {
        val app = context.applicationContext
        if (!hasRecordPermission(app)) return "请先在「设置 → 一站式权限与自治」中授予麦克风录音权限"
        if (readConfig(app).apiKey.isBlank()) return "请先在设置中填写硅基流动（SiliconFlow）API Key"
        stopSpeaking()
        cancelRecording()

        val outFile = File(app.cacheDir, "whale-asr-${System.currentTimeMillis()}.m4a")
        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(app)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(32_000)
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordFile = outFile
            isRecording = true
            null
        } catch (t: Throwable) {
            outFile.delete()
            isRecording = false
            "启动录音失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    @Synchronized
    fun cancelRecording() {
        val rec = recorder
        val file = recordFile
        recorder = null
        recordFile = null
        isRecording = false
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        file?.delete()
    }

    /** 停止录音并上传至硅基流动免费 SenseVoiceSmall 接口，返回识别文字。 */
    fun stopAndTranscribe(context: Context): Result<String> {
        val file: File
        synchronized(this) {
            val rec = recorder
            val f = recordFile
            recorder = null
            recordFile = null
            isRecording = false
            if (rec == null || f == null) return Result.failure(IllegalStateException("当前未在录音"))
            runCatching { rec.stop() }
            runCatching { rec.release() }
            file = f
        }
        return try {
            if (!file.exists() || file.length() < 512L) {
                return Result.failure(IllegalStateException("录音时间太短，请再试一次"))
            }
            val cfg = readConfig(context.applicationContext)
            if (cfg.apiKey.isBlank()) return Result.failure(IllegalStateException("未配置硅基流动 API Key"))
            val text = transcribeMultipart(cfg, file)
            if (text.isBlank()) Result.failure(IllegalStateException("没听清楚，请再说一遍"))
            else Result.success(text)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            file.delete()
        }
    }

    private fun transcribeMultipart(cfg: Config, audioFile: File): String {
        val boundary = "----WhaleVoiceBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val conn = (URL("${cfg.baseUrl}/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                out.writeBytes("$DEFAULT_ASR_MODEL\r\n")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"speech.m4a\"\r\n")
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                audioFile.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("语音识别失败（HTTP $code）：${raw.take(180)}")
            }
            val json = JSONObject(raw)
            return if (json.isNull("text")) "" else json.optString("text", "").trim()
        } finally {
            conn.disconnect()
        }
    }
}
