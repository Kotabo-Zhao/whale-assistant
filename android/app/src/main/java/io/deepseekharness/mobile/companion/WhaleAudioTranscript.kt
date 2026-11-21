package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 云端音频文件转写接口（已移除 42MB 离线 Vosk 语音模型与 JNA 原生库）。
 *
 * 复用「我的 → 桌宠拟人语音」配置的云端转写接口（默认硅基流动 FunAudioLLM/SenseVoiceSmall）。
 */
internal object WhaleAudioTranscript {
    private const val DEFAULT_STT_MODEL = "FunAudioLLM/SenseVoiceSmall"

    fun supports(ext: String, mime: String?) = mime?.startsWith("audio/") == true ||
        ext in setOf("mp3", "wav", "ogg", "opus", "m4a", "aac", "amr", "flac", "3gp", "silk")

    @Synchronized
    fun recognize(
        context: Context,
        file: File,
        @Suppress("UNUSED_PARAMETER") fromSeconds: Int = 0,
        stopped: () -> Boolean = { false },
    ): String {
        check(!stopped()) { "音频解析已中断" }
        require(file.isFile && file.length() > 0L) { "音频文件不存在或为空" }
        val cfg = WhaleVoiceEngine.readConfig(context)
        val apiKey = cfg.apiKey.trim()
        require(apiKey.isNotEmpty()) { "请先在「我的 → 桌宠拟人语音」配置云端 API Key 后再转写音频文件" }

        val boundary = "----WhaleAudio${UUID.randomUUID().toString().replace("-", "")}"
        val endpoint = "${cfg.baseUrl.trimEnd('/')}/audio/transcriptions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            BufferedOutputStream(conn.outputStream).use { out ->
                fun writeStr(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                writeStr("--$boundary\r\n")
                writeStr("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                writeStr("$DEFAULT_STT_MODEL\r\n")
                writeStr("--$boundary\r\n")
                writeStr("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                writeStr("Content-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                writeStr("\r\n--$boundary--\r\n")
                out.flush()
            }
            check(!stopped()) { "音频解析已中断" }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("云端音频转写失败 ($code)：${body.take(200)}")
            val text = runCatching { JSONObject(body).optString("text", "") }.getOrDefault("").trim()
            return text.ifEmpty { throw IllegalStateException("云端音频转写返回为空") }
        } finally {
            conn.disconnect()
        }
    }
}
