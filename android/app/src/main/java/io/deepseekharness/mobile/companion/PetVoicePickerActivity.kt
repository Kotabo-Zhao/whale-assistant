package io.deepseekharness.mobile.companion

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.TextView
import android.widget.Toast

/** Audition local character voices and bind a voice to the selected persona card. */
class PetVoicePickerActivity : Activity() {
    private var engine: TextToSpeech? = null
    private val kokoro by lazy { PetKokoroVoice(this) }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var downloading = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "正在读取本地音色…"; textSize = 18f; setPadding(48, 48, 48, 48) })
        engine = TextToSpeech(applicationContext) { status -> Handler(Looper.getMainLooper()).post {
            if (isFinishing || isDestroyed) return@post
            val tts = engine ?: return@post
            val voices = if (status == TextToSpeech.SUCCESS) tts.voices?.filter {
                it.locale.language == "zh" && !it.isNetworkConnectionRequired && "notInstalled" !in it.features
            }?.sortedBy { it.name }.orEmpty() else emptyList()
            val names = listOf("角色女声 · 小北", "角色女声 · 小妮", "角色女声 · 晓晓", "角色女声 · 小伊") + voices.map { "系统离线 · ${it.name}" }
            val ids = (45..48).map { "kokoro:$it" } + voices.map { it.name }
            val card = CompanionPersonaStore(this).active(); val cardId = card.optString("id", "default")
            var selected = ids.indexOf(CompanionStore(this).petVoiceName(cardId)).coerceAtLeast(0)
            fun preview(index: Int) {
                if (index < 4) { tts.stop(); kokoro.speak("你好呀，今天也请多多关照。", 45 + index) }
                else { kokoro.stop(); tts.voice = voices[index - 4]; tts.speak("你好呀，今天也请多多关照。", TextToSpeech.QUEUE_FLUSH, null, "pet-preview") }
            }
            val dialog = AlertDialog.Builder(this).setTitle("为 ${card.optString("name", "角色")} 选择音色")
                .setSingleChoiceItems(names.toTypedArray(), selected) { _, index ->
                    selected = index
                    if (index >= 4 || PetSpeechModels(this).installed(PetSpeechModels.Model.TTS)) preview(index)
                    else if (!downloading) AlertDialog.Builder(this).setTitle("下载角色离线音色")
                        .setMessage("首次使用 Kokoro 量化中文女声音色需下载约 132 MB，安装后本机合成。四种女声可以分别试听并绑定角色。")
                        .setNegativeButton("取消", null).setPositiveButton("下载") { _, _ ->
                            downloading = true
                            Toast.makeText(this, "正在下载角色音色，请保持页面打开。", Toast.LENGTH_LONG).show()
                            worker.execute {
                                val result = runCatching { PetSpeechModels(this).install(PetSpeechModels.Model.TTS) }
                                runOnUiThread {
                                    downloading = false
                                    if (!isFinishing && !isDestroyed) result.onSuccess { preview(selected) }
                                        .onFailure { Toast.makeText(this, "音色下载失败，请检查网络或空间后重试。", Toast.LENGTH_LONG).show() }
                                }
                            }
                        }.show()
                }
                .setNegativeButton("取消") { _, _ -> finish() }
                .setPositiveButton("使用音色", null)
                .setOnCancelListener { finish() }.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (selected < 4 && !PetSpeechModels(this).installed(PetSpeechModels.Model.TTS))
                        Toast.makeText(this, "请先点选音色并完成模型下载。", Toast.LENGTH_SHORT).show()
                    else { CompanionStore(this).setPetVoiceName(cardId, ids[selected]); dialog.dismiss(); finish() }
                }
            }
            dialog.show()
        } }
    }
    override fun onDestroy() { engine?.stop(); engine?.shutdown(); engine = null; kokoro.close(); worker.shutdownNow(); super.onDestroy() }
}
