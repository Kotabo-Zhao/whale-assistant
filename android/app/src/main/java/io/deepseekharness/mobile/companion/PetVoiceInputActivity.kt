package io.deepseekharness.mobile.companion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.widget.LinearLayout
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/** Voice input is explicitly started by the pet and returns only an editable draft. */
internal object PetVoiceInput {
    private var callback: ((String) -> Unit)? = null
    fun start(context: Context, result: (String) -> Unit) {
        callback = result
        try { context.startActivity(Intent(context, PetVoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { callback = null; throw e }
    }
    fun deliver(text: String) { val result = callback; callback = null; result?.invoke(text) }
    fun clear() { callback = null }
}

class PetVoiceInputActivity : Activity() {
    private var recognizer: SpeechRecognizer? = null
    private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    @Volatile private var cancelled = false
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "正在准备本地语音输入…"; textSize = 18f; setPadding(48, 48, 48, 48) })
        val models = PetSpeechModels(this)
        val systemReady = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (!models.installed(PetSpeechModels.Model.ASR) && !systemReady) {
            android.app.AlertDialog.Builder(this).setTitle("下载中文离线语音识别模型")
                .setMessage("这台手机没有可用的系统本地识别服务。下载 Paraformer 小模型约 78 MB，之后语音识别在本机进行。")
                .setNegativeButton("取消") { _, _ -> finish() }
                .setPositiveButton("下载") { _, _ ->
                    worker.execute {
                        val result = runCatching { models.install(PetSpeechModels.Model.ASR) { message -> runOnUiThread { showStatus(message) } } }
                        runOnUiThread { if (!isFinishing && !isDestroyed) result.onSuccess { requestMicrophone() }.onFailure { finishWithMessage("语音模型安装失败，请检查网络后重试。") } }
                    }
                }.setOnCancelListener { finish() }.show()
        } else requestMicrophone()
    }
    private fun showStatus(message: String) { setContentView(TextView(this).apply { text = message; textSize = 18f; setPadding(48, 48, 48, 48) }) }
    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        else startListening()
    }
    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else finishWithMessage("未授权麦克风，无法语音输入。")
    }
    private fun startListening() {
        if (PetSpeechModels(this).installed(PetSpeechModels.Model.ASR)) { startLocalRecording(); return }
        setContentView(TextView(this).apply { text = "正在聆听…说完后自动填写草稿"; textSize = 18f; setPadding(48, 48, 48, 48) })
        runCatching {
            val service = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) = finishWithMessage("本地语音识别未完成（$error），请检查离线语音模型或重试。")
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) finishWithMessage("没有识别到语音，请重试。")
                    else { PetVoiceInput.deliver(text); finish() }
                }
            })
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        }.onFailure { finishWithMessage("无法启动本地语音识别，请检查系统离线语音服务。") }
    }
    private fun startLocalRecording() {
        val button = Button(this).apply { text = "说完了，识别"; setOnClickListener { recording = false; text = "正在识别…"; isEnabled = false } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 32)
            addView(TextView(this@PetVoiceInputActivity).apply { text = "正在聆听，最多 30 秒。说完请点击下方按钮。"; textSize = 17f })
            addView(button)
        })
        recording = true
        worker.execute {
            val result = runCatching {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                @Suppress("MissingPermission")
                val input = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                recorder = input
                try {
                    check(input.state == AudioRecord.STATE_INITIALIZED)
                    val samples = ShortArray(16000 * 30); var count = 0
                    input.startRecording()
                    while (recording && !cancelled && count < samples.size) {
                        val n = input.read(samples, count, minOf(4096, samples.size - count))
                        if (n <= 0) break
                        count += n
                    }
                    input.stop()
                    check(!cancelled && count > 0)
                    runOnUiThread { if (!isFinishing) showStatus("正在本机识别…") }
                    val dir = PetSpeechModels(this).directory(PetSpeechModels.Model.ASR)
                    val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                        paraformer = OfflineParaformerModelConfig(model = java.io.File(dir, "model.int8.onnx").absolutePath),
                        tokens = java.io.File(dir, "tokens.txt").absolutePath, numThreads = 2))
                    val local = OfflineRecognizer(config = config)
                    try {
                        val stream = local.createStream()
                        try { stream.acceptWaveform(FloatArray(count) { samples[it] / 32768f }, 16000); local.decode(stream); local.getResult(stream).text }
                        finally { stream.release() }
                    } finally { local.release() }
                } finally { runCatching { input.stop() }; input.release(); recorder = null }
            }
            runOnUiThread {
                if (!cancelled && !isFinishing && !isDestroyed) result.onSuccess { text ->
                    if (text.isBlank()) finishWithMessage("没有识别到语音，请重试。") else { PetVoiceInput.deliver(text); finish() }
                }.onFailure { finishWithMessage("本地语音识别失败，请重试。") }
            }
        }
    }
    private fun finishWithMessage(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish() }
    override fun onDestroy() { cancelled = true; recording = false; recognizer?.destroy(); recognizer = null; PetVoiceInput.clear(); worker.shutdownNow(); super.onDestroy() }
}
