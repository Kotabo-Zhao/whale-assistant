package io.deepseekharness.mobile.companion

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Optional models live outside the APK and are installed only after the user requests them. */
internal class PetSpeechModels(private val context: Context) {
    enum class Model(val directory: String, val url: String, val sha: String, val sizeLabel: String) {
        ASR("sherpa-onnx-paraformer-zh-small-2024-03-09", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2", "da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450", "约 78 MB"),
        TTS("kokoro-int8-multi-lang-v1_0", "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2", "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653", "约 132 MB"),
    }
    private val root = File(context.applicationContext.noBackupFilesDir, "pet-speech-models")
    fun directory(model: Model): File = File(root, model.directory)
    fun installed(model: Model): Boolean {
        val dir = directory(model)
        val files = if (model == Model.ASR) listOf("model.int8.onnx", "tokens.txt") else listOf("model.int8.onnx", "tokens.txt", "voices.bin", "lexicon-zh.txt")
        return files.all { File(dir, it).isFile }
    }
    fun install(model: Model, progress: (String) -> Unit = {}) {
        if (installed(model)) return
        root.mkdirs()
        val archive = File(context.cacheDir, "pet-${model.name.lowercase()}.tar.bz2")
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if (!archive.isFile || hash(archive) != model.sha) {
            progress("正在下载本地语音模型（${model.sizeLabel}）…")
            val temp = File(context.cacheDir, archive.name + ".part")
            val connection = URL(model.url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000; connection.readTimeout = 120_000
                if (connection.responseCode !in 200..299) error("模型下载失败（HTTP ${connection.responseCode}）")
                connection.inputStream.use { input -> temp.outputStream().buffered().use { output -> input.copyTo(output) } }
                check(hash(temp) == model.sha) { "模型校验失败，请重试" }
                check(temp.renameTo(archive)) { "无法保存模型下载文件" }
            } finally { connection.disconnect(); if (temp.exists()) temp.delete() }
        }
        progress("正在安装本地语音模型…")
        val stage = File(root, "${model.directory}.new")
        if (stage.exists()) stage.deleteRecursively()
        stage.mkdirs()
        try {
            val prefix = model.directory + "/"
            TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.removePrefix("./")
                    if (!name.startsWith(prefix) || entry.isDirectory) continue
                    require(entry.isFile && entry.size in 0..300_000_000L)
                    val output = File(stage, name.removePrefix(prefix))
                    require(output.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                    output.parentFile?.mkdirs(); output.outputStream().buffered().use { tar.copyTo(it) }
                }
            }
            val destination = directory(model)
            check(!destination.exists() || destination.deleteRecursively()) { "无法替换旧模型" }
            check(stage.renameTo(destination) && installed(model)) { "模型安装未完成" }
            archive.delete()
        } finally { if (stage.exists()) stage.deleteRecursively() }
    }
}
