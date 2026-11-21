package io.deepseekharness.mobile.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 鲸鱼原生双模语音引擎（本地零配置离线优先 + 云端拟人可选）：
 *
 * - **默认零配置本地离线模式（0 API Key、0 费用、0 体积增量）**：
 *   - 说（TTS）：直接调用 Android 系统原生 [TextToSpeech]（自动优先中文女声，荣耀/小米自带高清系统音色离线秒播）；
 *   - 听（ASR）：直接调用 Android 系统原生 [SpeechRecognizer]（`EXTRA_PREFER_OFFLINE = true`，离线按住说话即出字）；
 * - **可选云端拟人模式（填硅基流动 Key 后自动升级）**：
 *   - 听：`FunAudioLLM/SenseVoiceSmall`；说：`FunAudioLLM/CosyVoice2-0.5B`，云端失败自动降级回本地离线 TTS，永不哑巴。
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

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val ttsFetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-tts-fetch").apply { isDaemon = true }
    }
    private val ttsPlayExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-tts-play").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0L)

    @Volatile private var activePlayer: MediaPlayer? = null
    @Volatile private var localTts: TextToSpeech? = null
    @Volatile private var localTtsReady: Boolean = false
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var recordFile: File? = null
    @Volatile private var localRecognizer: SpeechRecognizer? = null
    @Volatile private var localSttLatch: CountDownLatch? = null
    private val localSttResult = AtomicReference<Result<String>?>(null)
    @Volatile private var usingLocalStt: Boolean = false
    @Volatile var isRecording: Boolean = false
        private set

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun readConfig(context: Context): Config {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var key = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        var baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trim().orEmpty().ifBlank { DEFAULT_BASE_URL }

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
        val cloudConfigured = cfg.apiKey.isNotBlank()
        return JSONObject()
            .put("configured", true)
            .put("cloudConfigured", cloudConfigured)
            .put("engine", if (cloudConfigured) "siliconflow_cloud" else "android_local_offline")
            .put("baseUrl", cfg.baseUrl)
            .put("voice", cfg.voice)
            .put("speed", cfg.speed.toDouble())
            .put("autoSpeakPet", cfg.autoSpeakPet)
            .put("hasRecordPermission", hasRecordPermission(context))
            .put("asrModel", if (cloudConfigured) DEFAULT_ASR_MODEL else "Android 系统离线语音识别")
            .put("ttsModel", if (cloudConfigured) DEFAULT_TTS_MODEL else "Android 系统离线朗读")
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
        runCatching { localTts?.stop() }
    }

    /** 使用 Android 系统自带离线 TTS 朗读（0 API Key、0 费用、即刻发声）。 */
    private fun speakLocalOffline(app: Context, sentences: List<String>, speed: Float, myGen: Long) {
        val speakNow = { engine: TextToSpeech ->
            if (generation.get() == myGen) {
                runCatching {
                    engine.setSpeechRate(speed)
                    engine.setPitch(1.06f)
                    sentences.forEachIndexed { idx, line ->
                        val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        engine.speak(line, mode, null, "whale-$myGen-$idx")
                    }
                }
            }
        }
        val existing = localTts
        if (existing != null && localTtsReady) {
            speakNow(existing)
            return
        }
        mainHandler.post {
            if (generation.get() != myGen) return@post
            lateinit var created: TextToSpeech
            created = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching {
                        val zh = created.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        if (zh == TextToSpeech.LANG_MISSING_DATA || zh == TextToSpeech.LANG_NOT_SUPPORTED) {
                            created.setLanguage(Locale.getDefault())
                        }
                    }
                    localTts = created
                    localTtsReady = true
                    speakNow(created)
                }
            }
        }
    }

    /** 异步播报：未配置云端 Key 自动走系统离线 TTS；配置了硅基流动 Key 时走云端拟人语音并自带本地兜底。 */
    fun speakText(context: Context, text: String, voiceOverride: String? = null) {
        // TTS speaking disabled per user preference (STT-only streamlined voice mode)
        return
        @Suppress("UNREACHABLE_CODE")
        val app = context.applicationContext
        val cfg = readConfig(app)
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return
        val myGen = generation.incrementAndGet()
        runCatching { activePlayer?.stop(); activePlayer?.release(); activePlayer = null }
        runCatching { localTts?.stop() }

        if (cfg.apiKey.isBlank()) {
            speakLocalOffline(app, sentences, cfg.speed, myGen)
            return
        }

        val targetVoice = voiceOverride?.trim()?.takeIf { it.isNotEmpty() } ?: cfg.voice
        ttsFetchExecutor.execute {
            for ((index, sentence) in sentences.withIndex()) {
                if (generation.get() != myGen) break
                val mp3File: File = runCatching { synthesizeToTempFile(app, cfg, sentence, targetVoice) }.getOrNull()
                    ?: if (index == 0) {
                        speakLocalOffline(app, sentences, cfg.speed, myGen)
                        return@execute
                    } else {
                        return@execute
                    }
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
        val latch = CountDownLatch(1)
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
            latch.await(45, TimeUnit.SECONDS)
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
        if (!hasRecordPermission(app)) return "请先在「设置 → 桌宠尺寸与拟人语音」中授予麦克风录音权限"
        stopSpeaking()
        cancelRecording()

        val cfg = readConfig(app)
        if (cfg.apiKey.isBlank()) {
            // 零配置本地离线语音识别（Android 原生 SpeechRecognizer）
            if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                return "当前系统未提供内置语音识别服务，可在设置中填入硅基流动免费 Key 作为云端语音识别。"
            }
            val latch = CountDownLatch(1)
            localSttLatch = latch
            localSttResult.set(null)
            usingLocalStt = true
            isRecording = true
            mainHandler.post {
                runCatching {
                    val sr = SpeechRecognizer.createSpeechRecognizer(app)
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        private var retriedDefaultLocale = false
                        override fun onError(error: Int) {
                            if ((error == 12 || error == 13) && !retriedDefaultLocale) {
                                retriedDefaultLocale = true
                                val fallbackIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                }
                                runCatching { sr.startListening(fallbackIntent) }.onFailure {
                                    localSttResult.compareAndSet(null, Result.failure(IllegalStateException("系统语音识别暂不可用，可在设置填入免费硅基流动 Key 使用高精度语音识别")))
                                    latch.countDown()
                                }
                                return
                            }
                            val hint = when (error) {
                                6, 7 -> "没听到清晰语音，请靠近麦克风再试一次"
                                9 -> "请先授予麦克风权限"
                                12, 13 -> "当前系统语音包不支持该语种，可在设置填入免费硅基流动 Key 使用高精度识别"
                                else -> "语音识别失败（码 $error），请再试一次"
                            }
                            localSttResult.compareAndSet(null, Result.failure(IllegalStateException(hint)))
                            latch.countDown()
                        }
                        override fun onResults(results: Bundle?) {
                            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                            val text = list.firstOrNull()?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                localSttResult.compareAndSet(null, Result.success(text))
                            } else {
                                localSttResult.compareAndSet(null, Result.failure(IllegalStateException("没听清楚，请再说一遍")))
                            }
                            latch.countDown()
                        }
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    localRecognizer = sr
                    sr.startListening(intent)
                }.onFailure { err ->
                    localSttResult.compareAndSet(null, Result.failure(err))
                    latch.countDown()
                }
            }
            return null
        }

        usingLocalStt = false
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
        val sr = localRecognizer
        recorder = null
        recordFile = null
        localRecognizer = null
        usingLocalStt = false
        isRecording = false
        localSttLatch?.countDown()
        localSttLatch = null
        if (sr != null) {
            mainHandler.post { runCatching { sr.cancel(); sr.destroy() } }
        }
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        file?.delete()
    }

    /** 停止录音并返回识别文字（本地离线 SpeechRecognizer 或云端 SenseVoiceSmall）。 */
    fun stopAndTranscribe(context: Context): Result<String> {
        if (usingLocalStt) {
            val sr: SpeechRecognizer?
            val latch: CountDownLatch?
            synchronized(this) {
                sr = localRecognizer
                latch = localSttLatch
                usingLocalStt = false
                isRecording = false
            }
            if (sr != null) {
                mainHandler.post { runCatching { sr.stopListening() } }
            }
            runCatching { latch?.await(8, TimeUnit.SECONDS) }
            if (sr != null) {
                mainHandler.post { runCatching { sr.destroy() } }
            }
            synchronized(this) {
                if (localRecognizer === sr) localRecognizer = null
                localSttLatch = null
            }
            return localSttResult.get() ?: Result.failure(IllegalStateException("没听清楚，请再说一遍"))
        }

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
