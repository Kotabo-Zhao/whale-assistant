package io.deepseekharness.mobile.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.util.concurrent.Executors

/** Explicit share target: keep the original selected text in the user's local knowledge store. */
open class WhaleMemoryShareActivity : Activity() {
    companion object {
        const val FROM_PET = "io.deepseekharness.mobile.PET_CAPTURE"
        const val PICK = "io.deepseekharness.mobile.PICK_KNOWLEDGE"
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveRequest(savedInstanceState)
    }

    protected open fun receiveRequest(savedInstanceState: Bundle?) {
        if (intent.action == PICK) {
            if (savedInstanceState == null) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 1)
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // A configuration recreation must not save the same incoming share twice.
        if (savedInstanceState != null) { finish(); return }
        if (intent.action == FROM_PET) {
            val note = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            importSelection(emptyList(), note, WhaleShareSource.forPet(note), null)
            return
        }
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val selection = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host
            importSelection(emptyList(), selection, WhaleShareSource.from(pkg, selection, false), null)
            return
        }
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            Toast.makeText(this, "不支持的分享内容", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val single = runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        @Suppress("DEPRECATION")
        val multiple = runCatching { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull().orEmpty()
        val data = intent.clipData
        if (multiple.size > 10 || (data?.itemCount ?: 0) > 10) {
            Toast.makeText(this, "一次最多投放 10 项", Toast.LENGTH_LONG).show(); finish(); return
        }
        val clipItems = if (data != null) (0 until data.itemCount).map(data::getItemAt) else emptyList()
        val uris = (multiple + listOfNotNull(single) + clipItems.mapNotNull { it.uri })
            .filterNot { it.scheme in setOf("http", "https") }.distinct()
        val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host
        val share = intent
        importPayload(uris, share.type, source = { WhaleShareSource.from(pkg,it,uris.isNotEmpty()) }) {
            WhaleSharedText.fromShare(share, clipItems, multiple + listOfNotNull(single))
        }
    }

    @Deprecated("Activity result is delivered by the system document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) { finish(); return }
        val clip = data.clipData
        val uris = (listOfNotNull(data.data) + (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }).distinct()
        val note = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        val origin = if (note.isNullOrBlank()) WhaleShareSource("本地文件", null) else WhaleShareSource.forPet(note)
        importSelection(uris, note, origin, null)
    }

    internal fun importSelection(uris: List<Uri>, sharedText: CharSequence?, origin: WhaleShareSource, mime: String?) =
        importPayload(uris,mime,source = { origin }) { sharedText }

    internal fun importPayload(uris: List<Uri>, mime: String?, release: () -> Unit = {},
        source: (CharSequence?) -> WhaleShareSource, readText: () -> CharSequence?) {
        val app = applicationContext
        executor.execute {
            val result = runCatching {
                require(uris.size <= 10) { "一次最多选择 10 个文件" }
                val results = mutableListOf<String>()
                val note = runCatching(readText)
                val sharedText = note.getOrNull()
                val origin = source(sharedText)
                if (note.isFailure) results.add("分享文字过长或格式无效，文字未保存")
                var captionIds = emptyList<String>()
                if (!sharedText.isNullOrBlank()) results += runCatching {
                    val text = requireNotNull(WhaleSharedText.accept(Intent.ACTION_SEND, "text/plain", sharedText))
                    val saved = CompanionKnowledgeStore(app).saveText(text, "shared_text", origin)
                    captionIds = saved.recordIds
                    val added = saved.added
                    if (added && text.length <= CompanionKnowledgeData.MAX_RECORD_TEXT) {
                        SharedMemoryJobs.enqueueWithoutSchedule(app, saved.recordIds.single(), text)
                        SharedMemoryJobs.schedule(app, 0)
                    }
                    if (!added) "资料库已有此文本" else if (origin.url != null) "已保存分享文本与链接" else "文本已存入鲸鱼资料"
                }.getOrElse { "文本未保存" }
                var queued = 0
                var known = 0
                var failed = 0
                for (uri in uris) runCatching {
                    val type = runCatching { app.contentResolver.getType(uri) }.getOrNull() ?: mime
                    WhaleOriginalImport.save(app, uri, type, origin, captionIds)
                }.onSuccess { if (it) queued++ else known++ }.onFailure { failed++ }
                if (queued > 0) results.add("已保存 $queued 份原件，后台解析中")
                if (known > 0) results.add("$known 份原件已在资料库")
                if (failed > 0) results.add("$failed 份文件未能保存，请重新投放")
                results.distinct().joinToString("；").ifEmpty { "没有可保存的内容" }
            }
            runOnUiThread {
                runCatching { release() }
                if (!isFinishing && !isDestroyed) {
                    // A share target is transient, so an explicit completion message is the only
                    // dependable on-screen acknowledgement after a file reaches the knowledge base.
                    val notice = result.fold({ it }, { "保存失败，请检查鲸鱼资料库" })
                    Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
                    finish()
                }
                if (uris.isNotEmpty()) WhaleParseJobs.deliverInbox(app)
            }
        }
    }

}

internal object WhaleSharedText {
    fun accept(action: String?, type: String?, value: CharSequence?): String? {
        if (action != Intent.ACTION_SEND || type != "text/plain" || value == null ||
            value.isBlank() || value.length > CompanionKnowledgeData.MAX_DOCUMENT_TEXT || '\u0000' in value) return null
        return value.toString()
    }

    fun fromShare(intent: Intent?, items: List<android.content.ClipData.Item>, streams: List<Uri>): String? {
        val parts = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        var length = 0L
        fun links(value: CharSequence?) {
            if (value !is android.text.Spanned) return
            value.getSpans(0,value.length,android.text.style.URLSpan::class.java).forEach { span ->
                span.url?.takeIf(WhaleShareSource::webUrl)?.let(targets::add)
            }
        }
        fun add(value: CharSequence?) {
            if (value.isNullOrBlank()) return
            require(value.length <= CompanionKnowledgeData.MAX_DOCUMENT_TEXT)
            val text = value.toString()
            if (parts.add(text)) {
                length += text.length + if (parts.size > 1) 1 else 0
                require(length <= CompanionKnowledgeData.MAX_DOCUMENT_TEXT && '\u0000' !in text)
            }
        }
        fun html(value: String?, includeBody: Boolean) {
            if (!value.isNullOrBlank()) {
                require(value.length <= CompanionKnowledgeData.MAX_DOCUMENT_TEXT)
                val rich = android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY)
                if (includeBody) add(rich.toString().trimEnd())
                links(rich)
            }
        }
        @Suppress("DEPRECATION")
        when (val extra = intent?.extras?.get(Intent.EXTRA_TEXT)) {
            is CharSequence -> { add(extra); links(extra) }
            is ArrayList<*> -> { require(extra.size <= 10); extra.filterIsInstance<CharSequence>().forEach { add(it); links(it) } }
        }
        html(intent?.getStringExtra(Intent.EXTRA_HTML_TEXT),parts.isEmpty())
        for (item in items) {
            add(item.text); links(item.text)
            html(item.htmlText,item.text.isNullOrBlank())
            if (item.uri?.scheme in setOf("http", "https")) add(item.uri.toString())
        }
        for (uri in streams) if (uri.scheme in setOf("http", "https")) add(uri.toString())
        if (targets.isNotEmpty()) {
            parts.forEach { WhaleShareSource.visibleUrls(it).forEach(targets::remove) }
            targets.forEach(::add)
        }
        val subject = intent?.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.takeIf(String::isNotBlank)
        if (subject != null && parts.none { it.lineSequence().firstOrNull() == subject }) {
            add(subject)
            return (listOf(subject) + parts.filterNot { it == subject }).joinToString("\n")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Bounded strict UTF-8 decoding; binary or truncated input is never stored as text. */
    fun readFile(input: java.io.InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(bytes.size() + n <= CompanionKnowledgeTextFile.MAX_FILE_BYTES) { "file too large" }
            bytes.write(buffer, 0, n)
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        return accept(Intent.ACTION_SEND, "text/plain", text)
    }
}
