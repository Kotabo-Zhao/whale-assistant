package io.deepseekharness.mobile.companion

import android.content.Context
import android.net.Uri
import io.deepseekharness.mobile.runtime.RuntimeSharedInbox
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Preserve user-selected originals even if a parser cannot understand them. */
internal object WhaleDocumentImport {
    private const val MAX_BYTES = 64L * 1024 * 1024
    fun import(context: Context, uri: Uri, mime: String?): String {
        require(uri.scheme == "content")
        val app = context.applicationContext
        val name = runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: when (mime) { "application/pdf" -> "pdf"; "text/plain" -> "txt"; else -> "bin" }
        val inbox = RuntimeSharedInbox(RuntimeStore(app))
        val target = File(inbox.stagingDirectory(), "${UUID.randomUUID()}-shared-document.$ext")
        var extracted: String? = null
        val sourceDigest = java.security.MessageDigest.getInstance("SHA-256")
        inbox.withPendingFile { pending ->
            requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                Files.newOutputStream(pending.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break; if (n == 0) continue
                        total += n; require(total <= MAX_BYTES) { "document too large" }; sourceDigest.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                    require(total > 0)
                }
            }
            extracted = runCatching { WhaleDocumentText.extract(pending, ext, mime) }.getOrNull()
            RuntimeSharedInbox.withLock { Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        }
        val text = extracted?.takeIf { it.isNotBlank() }
        val knowledge = CompanionKnowledgeStore(app)
        val added = text?.let { runCatching { knowledge.add(it, "text_file") }.getOrNull() }
        val key = runCatching { RuntimeStore(app).readModelCredential("deepseek") }.getOrNull()
        val scan = if (text == null && ext == "pdf" && !key.isNullOrBlank())
            runCatching { RuntimeSharedInbox.withLock { WhaleDocumentText.renderPdfPages(target) } }.getOrNull() else null
        val delivered = runCatching { inbox.deliver() }.getOrDefault(false)
        val where = if (delivered) "文件已保存到收件箱" else "文件已暂存，运行环境就绪后可用"
        if (scan != null && !key.isNullOrBlank()) {
            val analyzed = runCatching {
                val descriptions = scan.first.mapIndexed { index, jpeg ->
                    "第 ${index + 1} 页：\n" + DeepSeekVisionModel(key).analyze(jpeg)
                }
                val heading = if (scan.second > descriptions.size) "扫描 PDF：仅分析前 ${descriptions.size} / ${scan.second} 页\n" else "扫描 PDF 分析：\n"
                val contents = (heading + descriptions.joinToString("\n\n")).take(CompanionKnowledgeData.MAX_TEXT)
                knowledge.add(contents, "text_file")
                val stableId = "share:" + sourceDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                check(SharedMemoryJobs.enqueue(app, contents, stableId))
                val rows = knowledge.search("")
                val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("text") == contents } ?: error("source unavailable")
                val eventId = UUID.randomUUID().toString()
                val model = org.json.JSONObject(DeepSeekUnderstandingModel(key, "deepseek-flash").analyze(eventId, contents))
                check(knowledge.withUnchangedRecord(row.getString("id"), contents) {
                    CompanionMemoryStore(app).saveSharedFacts(stableId, model.getJSONArray("claims"))
                })
                SharedMemoryJobs.complete(app, contents, stableId)
            }.isSuccess
            return if (!analyzed) "$where；扫描文件分析未完成" else if (scan.second > scan.first.size)
                "$where；扫描文件仅分析前 ${scan.first.size} 页，其余未完成" else where
        }
        if (added == null) return "$where；文件内容未完成解析或资料未保存，请检查格式、密码及容量"
        if (!added) return where
        val memoryEvent = "share:" + CompanionMemorySuppression.digest(text!!)
        check(SharedMemoryJobs.enqueue(app, text, memoryEvent))
        if (key.isNullOrBlank()) return "$where；文字已保存，模型分析需要 DeepSeek API 密钥"
        val row = runCatching {
            val rows = knowledge.search("")
            (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("text") == text }
        }.getOrNull()
        if (row == null) return "$where；文字已保存，分析未完成"
        val understood = runCatching {
            val eventId = UUID.randomUUID().toString()
            val result = org.json.JSONObject(DeepSeekUnderstandingModel(key, "deepseek-flash").analyze(eventId, text!!))
            val claims = result.getJSONArray("claims")
            val items = result.optJSONArray("items") ?: org.json.JSONArray()
            val summary = buildString {
                for (i in 0 until claims.length()) append("• ").append(claims.getJSONObject(i).getString("text")).append('\n')
                for (i in 0 until items.length()) append("• 待办建议：").append(items.getJSONObject(i).getString("title")).append('\n')
            }.trim()
            check(knowledge.withUnchangedRecord(row.getString("id"), text) {
                CompanionMemoryStore(app).saveSharedFacts(memoryEvent, claims)
            })
            if (summary.isNotEmpty()) {
                val prefix = "DeepSeek 分析（待核对）：\n" + summary.take(1800) + "\n\n文档提取内容：\n"
                val available = CompanionKnowledgeData.MAX_TEXT - prefix.length
                val raw = if (text.length > available) text.take((available - 12).coerceAtLeast(0)) + "\n[内容过长，已截取]" else text
                // Stale edits/deletions are never recreated by a late model response.
                check(knowledge.edit(row.getString("id"), text, prefix + raw))
                SharedMemoryJobs.completeVersion(app, row.getString("id"), prefix + raw)
            }
            SharedMemoryJobs.complete(app, text, memoryEvent)
        }.isSuccess
        return if (understood) where else "$where；文字已保存，DeepSeek 分析未完成"
    }
}

internal object WhaleDocumentText {
    private const val MAX_EXTRACT = 8000
    fun extract(file: File, ext: String, mime: String?): String? {
        if (ext in setOf("txt", "md", "csv", "tsv", "json", "xml", "log", "yaml", "yml", "html", "htm") || mime?.startsWith("text/") == true) {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val decoded = java.io.InputStreamReader(file.inputStream(), decoder).use { reader ->
                val result = StringBuilder(); val buffer = CharArray(4096)
                while (result.length <= MAX_EXTRACT) {
                    val n = reader.read(buffer, 0, minOf(buffer.size, MAX_EXTRACT + 1 - result.length))
                    if (n < 0) break
                    result.append(buffer, 0, n)
                }
                result.toString()
            }
            require('\u0000' !in decoded)
            return bounded(decoded)
        }
        if (ext == "pdf" || mime == "application/pdf") return pdf(file)
        if (ext in setOf("docx", "pptx", "odt")) return office(file, ext)
        if (ext == "xlsx") return spreadsheet(file)
        return null
    }
    private fun bounded(value: String): String = if (value.length > MAX_EXTRACT) value.take(MAX_EXTRACT - 12) + "\n[内容过长，已截取]" else value
    private fun pdf(file: File): String? {
        if (android.os.Build.VERSION.SDK_INT < 35) return null
        val out = StringBuilder()
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            for (i in 0 until minOf(pdf.pageCount, 50)) {
                pdf.openPage(i).use { page -> page.textContents.forEach { out.append(it.text).append('\n') } }
                if (out.length > MAX_EXTRACT) break
            }
            if (pdf.pageCount > 50) out.append("\n[仅提取前50页]")
        }
        return bounded(out.toString()).takeIf { it.isNotBlank() }
    }
    fun renderPdfPages(file: File): Pair<List<ByteArray>, Int> {
        val result = mutableListOf<ByteArray>()
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            require(pdf.pageCount > 0)
            for (i in 0 until minOf(pdf.pageCount, 3)) pdf.openPage(i).use { page ->
                require(page.width > 0 && page.height > 0)
                val scale = minOf(2f, 1600f / maxOf(page.width, page.height))
                val width = (page.width * scale).toInt().coerceAtLeast(1); val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bytes = java.io.ByteArrayOutputStream()
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bytes))
                    result.add(bytes.toByteArray())
                } finally { bitmap.recycle() }
            }
            return result to pdf.pageCount
        }
    }
    private fun office(file: File, ext: String): String? {
        val result = StringBuilder()
        java.util.zip.ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().take(501).toList()
            require(entries.size <= 500)
            val wanted = entries.filter { when (ext) { "docx" -> it.name == "word/document.xml"; "odt" -> it.name == "content.xml"; else -> it.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) } }
                .sortedBy { it.name }
            var total = 0
            for (entry in wanted) {
                val bytes = zip.getInputStream(entry).use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val n = input.read(buffer); if (n < 0) break; if (n == 0) continue; total += n; require(total <= 2_000_000); out.write(buffer, 0, n) }
                    out.toByteArray()
                }
                val xml = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
                val factory = javax.xml.parsers.SAXParserFactory.newInstance().apply {
                    isNamespaceAware = true
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                }
                val reader = factory.newSAXParser().xmlReader
                reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw org.xml.sax.SAXException("external entity denied") }
                reader.contentHandler = object : org.xml.sax.helpers.DefaultHandler() {
                    var inText = false
                    var paragraphs = 0
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) { if (ext == "odt" && localName in setOf("p", "h")) paragraphs++; if (ext != "odt" && localName == "t") inText = true }
                    override fun endElement(uri: String?, localName: String?, qName: String?) { if (ext == "odt" && localName in setOf("p", "h")) { paragraphs--; result.append('\n') } else if (ext != "odt" && localName == "t") { inText = false; result.append(' ') } else if (localName == "p") result.append('\n') }
                    override fun characters(ch: CharArray, start: Int, length: Int) { if ((inText || paragraphs > 0) && result.length < MAX_EXTRACT + 100) result.append(ch, start, minOf(length, MAX_EXTRACT + 100 - result.length)) }
                }
                reader.parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
                result.append('\n')
            }
        }
        return bounded(result.toString()).takeIf { it.isNotBlank() }
    }

    /** Values are extracted from the workbook; formulas use their cached result. */
    private fun spreadsheet(file: File): String? {
        val result = StringBuilder()
        val shared = mutableListOf<String>()
        java.util.zip.ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().take(501).toList(); require(entries.size <= 500)
            var total = 0
            fun parse(entry: java.util.zip.ZipEntry, handler: org.xml.sax.helpers.DefaultHandler) {
                val bytes = zip.getInputStream(entry).use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val n = input.read(buffer); if (n < 0) break; if (n == 0) continue; total += n; require(total <= 2_000_000); out.write(buffer, 0, n) }
                    out.toByteArray()
                }
                val xml = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
                val factory = javax.xml.parsers.SAXParserFactory.newInstance().apply { isNamespaceAware = true }
                val reader = factory.newSAXParser().xmlReader
                reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw org.xml.sax.SAXException("external entity denied") }
                reader.contentHandler = handler; reader.parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
            }
            entries.firstOrNull { it.name == "xl/sharedStrings.xml" }?.let { entry ->
                parse(entry, object : org.xml.sax.helpers.DefaultHandler() {
                    var inText = false; var inItem = false; val value = StringBuilder()
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
                        if (localName == "si") { inItem = true; value.setLength(0) }; if (inItem && localName == "t") inText = true
                    }
                    override fun characters(ch: CharArray, start: Int, length: Int) { if (inText) value.append(ch, start, length) }
                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        if (localName == "t") inText = false
                        if (localName == "si") { require(shared.size < 100_000); shared.add(value.toString()); inItem = false }
                    }
                })
            }
            val sheets = entries.filter { it.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }.sortedBy { it.name }
            sheets.forEach { entry ->
                result.append("工作表 ").append(entry.name.substringAfterLast('/').substringBefore('.')).append("（公式显示缓存值）：\n")
                parse(entry, object : org.xml.sax.helpers.DefaultHandler() {
                    var type = ""; var address = ""; var inValue = false; val value = StringBuilder()
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
                        if (localName == "c") { type = attributes?.getValue("t").orEmpty(); address = attributes?.getValue("r").orEmpty(); value.setLength(0) }
                        if (localName == "v" || localName == "t") inValue = true
                    }
                    override fun characters(ch: CharArray, start: Int, length: Int) { if (inValue) value.append(ch, start, length) }
                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        if (localName == "v" || localName == "t") inValue = false
                        if (localName == "c" && value.isNotEmpty() && result.length < MAX_EXTRACT + 100) {
                            val text = if (type == "s") value.toString().toIntOrNull()?.let { shared.getOrNull(it) } ?: "[共享文本不可用]" else value.toString()
                            result.append(address).append(": ").append(text.take(1000)).append('\n')
                        }
                    }
                })
            }
        }
        return bounded(result.toString()).takeIf { it.isNotBlank() }
    }
}
