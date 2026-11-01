package io.deepseekharness.mobile.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast

/** Uses an installed Chinese on-device voice; never silently selects a network voice. */
internal class PetVoiceSpeaker(private val context: Context) {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val kokoro by lazy { PetKokoroVoice(context) }
    fun speak(value: String) {
        val text = value.replace(Regex("```[\\s\\S]*?```"), "代码内容").replace(Regex("!?(?:\\[([^]]+)\\])\\([^)]+\\)"), "$1")
            .replace(Regex("[*_#`~]"), "").trim().take(1900)
        if (text.isBlank()) return
        val preferredVoice = CompanionStore(context).petVoiceName(CompanionPersonaStore(context).active().optString("id", "default")) ?: "kokoro:45"
        if (preferredVoice.startsWith("kokoro:")) {
            if (!PetSpeechModels(context).installed(PetSpeechModels.Model.TTS)) {
                Toast.makeText(context, "请点击“选音色”下载角色语音模型。", Toast.LENGTH_LONG).show(); return
            }
            engine?.stop()
            kokoro.speak(text, preferredVoice.substringAfter(':').toIntOrNull()?.coerceIn(45, 48) ?: 45)
            return
        }
        if (ready) {
            val tts = engine ?: return
            val preferred = CompanionStore(context).petVoiceName(CompanionPersonaStore(context).active().optString("id", "default"))
            if (preferred != null) tts.voices?.firstOrNull { it.name == preferred && !it.isNetworkConnectionRequired }?.let { tts.voice = it }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pet-reply"); return
        }
        pending = text
        if (engine != null) return
        engine = TextToSpeech(context.applicationContext) { status -> handler.post {
            val tts = engine ?: return@post
            val preferred = CompanionStore(context).petVoiceName(CompanionPersonaStore(context).active().optString("id", "default"))
            val voices = if (status == TextToSpeech.SUCCESS) tts.voices?.filter {
                it.locale.language == "zh" && !it.isNetworkConnectionRequired && "notInstalled" !in it.features
            }.orEmpty() else emptyList()
            val voice = voices.firstOrNull { it.name == preferred } ?: voices.firstOrNull()
            if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                pending = null; tts.shutdown(); engine = null
                Toast.makeText(context, "没有可用的中文离线朗读音色，请安装系统语音数据。", Toast.LENGTH_LONG).show()
            } else {
                ready = true
                pending?.let { tts.speak(it, TextToSpeech.QUEUE_FLUSH, null, "pet-reply") }
                pending = null
            }
        } }
    }
    fun stop() { pending = null; engine?.stop(); kokoro.stop() }
    fun close() { stop(); engine?.shutdown(); engine = null; ready = false; kokoro.close() }
}
