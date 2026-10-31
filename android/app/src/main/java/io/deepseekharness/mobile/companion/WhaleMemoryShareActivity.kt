package io.deepseekharness.mobile.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.util.concurrent.Executors

/** Explicit share target: keep the original selected text in the user's local knowledge store. */
class WhaleMemoryShareActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // A configuration recreation must not save the same incoming share twice.
        if (savedInstanceState != null) { finish(); return }
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            Toast.makeText(this, "不支持的分享内容", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val single = runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        @Suppress("DEPRECATION")
        val multiple = runCatching { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull().orEmpty()
        val clip = intent.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
        val uris = (if (intent.action == Intent.ACTION_SEND_MULTIPLE) multiple.ifEmpty { clip } else listOfNotNull(single ?: clip.firstOrNull())).distinct()
        val sharedText = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull()
        val app = applicationContext
        executor.execute {
            val result = runCatching {
                require(uris.size <= 10) { "too many files" }
                if (uris.isNotEmpty()) {
                    val results = uris.map { uri ->
                        runCatching {
                            val type = runCatching { app.contentResolver.getType(uri) }.getOrNull() ?: intent.type
                            if (type?.startsWith("image/") == true) WhaleImageImport.import(app, uri)
                            else WhaleDocumentImport.import(app, uri, type)
                        }.getOrElse { "保存失败" }
                    }
                    return@runCatching results.firstOrNull { "失败" in it || "未完成" in it || "需要" in it } ?: "已保存"
                }
                val text = WhaleSharedText.accept(Intent.ACTION_SEND, "text/plain", sharedText)
                requireNotNull(text)
                CompanionKnowledgeStore(app).add(text, "shared_text")
                check(SharedMemoryJobs.enqueue(app, text))
                if (runCatching { CompanionSharedMemory.understandText(app, text) }.getOrDefault(false)) SharedMemoryJobs.complete(app, text)
                "已保存"
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    // Successful saves are silent; report failures and incomplete analysis.
                    val notice = result.fold({ message -> message.takeIf { "失败" in it || "未完成" in it || "需要" in it } },
                        { "保存失败，请检查鲸鱼资料库" })
                    if (notice != null) Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

internal object WhaleSharedText {
    fun accept(action: String?, type: String?, value: CharSequence?): String? {
        if (action != Intent.ACTION_SEND || type != "text/plain" || value == null ||
            value.isBlank() || value.length > CompanionKnowledgeData.MAX_TEXT || '\u0000' in value) return null
        return value.toString()
    }

    /** Bounded strict UTF-8 decoding; binary or truncated input is never stored as text. */
    fun readFile(input: java.io.InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(bytes.size() + n <= CompanionKnowledgeData.MAX_TEXT * 4) { "file too large" }
            bytes.write(buffer, 0, n)
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        return accept(Intent.ACTION_SEND, "text/plain", text)
    }
}
