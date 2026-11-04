package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File

internal object WhaleDocumentImport {
    fun parse(app: Context, file: File, ext: String, mime: String, fromSeconds: Int = 0, stopped: () -> Boolean = { false }): WhaleParsedContent? {
        val audio = WhaleAudioTranscript.supports(ext, mime)
        if (audio) return WhaleParsedContent.document(WhaleAudioTranscript.recognize(app, file, fromSeconds, stopped), "audio_transcript")
        require(fromSeconds == 0)
        val text = WhaleDocumentText.extract(file, ext, mime)
        if (!text.isNullOrBlank()) return WhaleParsedContent.document(text, null)
        if (ext != "pdf" && mime != "application/pdf") return null
        val key = RuntimeStore(app).readModelCredential("deepseek")?.takeIf { it.isNotBlank() } ?: return null
        val (pages, total) = WhaleDocumentText.renderPdfPages(file)
        if (pages.isEmpty()) return null
        val descriptions = pages.mapIndexed { index, jpeg -> "第 ${index + 1} 页：\n" + DeepSeekVisionModel(key).analyze(jpeg) }
        val heading = if (total > pages.size) "扫描 PDF：仅分析前 ${pages.size} / $total 页\n" else "扫描 PDF 分析：\n"
        val summary = heading + descriptions.joinToString("\n\n")
        val partial = total > pages.size || summary.length > CompanionKnowledgeData.MAX_RECORD_TEXT
        return WhaleParsedContent(summary.take(CompanionKnowledgeData.MAX_RECORD_TEXT), if (partial) "partial" else "model_description", description = true)
    }
}

internal object WhaleDocumentText {
    private const val MAX_EXTRACT = 2_000_000
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
    private fun bounded(value: String): String {
        if(value.length <= MAX_EXTRACT) return value
        var end=MAX_EXTRACT-12
        if(Character.isHighSurrogate(value[end-1]) && Character.isLowSurrogate(value[end])) end--
        return value.take(end)+"\n[内容过长，已截取]"
    }
    private fun pdf(file: File): String? {
        if (android.os.Build.VERSION.SDK_INT < 35) return null
        val out = StringBuilder()
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            for (i in 0 until minOf(pdf.pageCount, 500)) {
                pdf.openPage(i).use { page -> page.textContents.forEach { out.append(it.text).append('\n') } }
                if (out.length > MAX_EXTRACT) break
            }
            if (pdf.pageCount > 500) out.append("\n[仅提取前500页]")
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
