package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WhaleDocumentTextTest {
    @Test fun extractsDocxTextAndRejectsExternalEntities() {
        fun document(xml: String) = kotlin.io.path.createTempFile(suffix = ".docx").toFile().apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { zip -> zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(xml.toByteArray()); zip.closeEntry() }
        }
        val normal = document("<w:document xmlns:w='urn:word'><w:p><w:t>鲸鱼测试</w:t></w:p></w:document>")
        assertTrue(WhaleDocumentText.extract(normal, "docx", null)!!.contains("鲸鱼测试"))
        val evil = document("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///not-a-document'>]><x><t>&e;</t></x>")
        assertThrows(Exception::class.java) { WhaleDocumentText.extract(evil, "docx", null) }
    }
    @Test fun textIsBoundedAndInvalidUtf8Rejected() {
        val file = kotlin.io.path.createTempFile().toFile().apply { deleteOnExit(); writeText("a".repeat(9000)) }
        assertEquals("a".repeat(9000), WhaleDocumentText.extract(file, "txt", null))
        file.writeText("a".repeat(2_000_001))
        assertTrue(WhaleDocumentText.extract(file, "txt", null)!!.contains("已截取"))
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(Exception::class.java) { WhaleDocumentText.extract(file, "txt", null) }
    }
    @Test fun extractsSharedAndNumericSpreadsheetCells() {
        val file = kotlin.io.path.createTempFile(suffix = ".xlsx").toFile().apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("xl/sharedStrings.xml")); zip.write("<sst xmlns='urn:x'><si><t>鲸鱼</t></si></sst>".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); zip.write("<worksheet xmlns='urn:x'><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='B1'><v>42</v></c></row></sheetData></worksheet>".toByteArray()); zip.closeEntry()
            }
        }
        val output = WhaleDocumentText.extract(file, "xlsx", null)!!
        assertTrue(output.contains("A1: 鲸鱼")); assertTrue(output.contains("B1: 42"))
    }
    @Test fun extractsOpenDocumentParagraphs() {
        val file = kotlin.io.path.createTempFile(suffix = ".odt").toFile().apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("content.xml"))
                zip.write("<office:document xmlns:office='urn:o' xmlns:text='urn:t'><text:p>你好<text:span>鲸鱼</text:span></text:p></office:document>".toByteArray())
                zip.closeEntry()
            }
        }
        assertTrue(WhaleDocumentText.extract(file, "odt", null)!!.contains("你好鲸鱼"))
    }
}
