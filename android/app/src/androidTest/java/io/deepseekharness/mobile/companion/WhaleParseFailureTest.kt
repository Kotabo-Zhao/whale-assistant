package io.deepseekharness.mobile.companion

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WhaleParseFailureTest {
    @Test fun failedOriginalKeepsSourceAndRetryClearsReason() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "parse-reason-"+java.util.UUID.randomUUID()
        val root = File(app.cacheDir,key).apply { check(mkdirs()) }
        val ctx = object : ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String,mode: Int) = app.getSharedPreferences(key+name,mode)
        }
        try {
            val file = File(root,"source.bin").apply { writeText("原始内容") }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            KnowledgeObjects(ctx).store(file,hash,1024)
            val store = CompanionKnowledgeStore(ctx)
            val original = store.registerOriginal(hash,"附件.unknown","application/octet-stream",WhaleShareSource("微信分享","https://example.com/source"))
            val id = original.getString("id")
            store.withParseDatabase { it.appendRecordIfAbsent(original,explicitReimport=true,reserveParse={}) }
            val anchor = store.withParseDatabase { it.claimParse() }!!
            WhaleParseJobs.process(ctx,anchor,java.util.concurrent.atomic.AtomicBoolean(false))
            val failed = store.findById(id)!!
            assertEquals("UNSUPPORTED_FORMAT",failed.getString("parseFailure"))
            assertEquals("needs_retry",failed.getString("parseState"))
            assertEquals("微信分享",failed.getString("shareOrigin"))
            assertEquals("https://example.com/source",failed.getString("sourceUrl"))
            assertEquals("原始内容",KnowledgeObjects(ctx).getVerified(hash).readText())
            assertTrue(store.withParseDatabase { it.retryParse(id,failed.toString()) {} })
            assertFalse(store.findById(id)!!.has("parseFailure"))
            val next = store.withParseDatabase { it.claimParse() }!!
            store.commitParsed(next.getString("parseTicket"),WhaleParsedContent.document("重试后的正文",null))
            assertEquals("complete",store.findById(id)!!.getString("parseState"))
            assertFalse(store.findById(id)!!.has("parseFailure"))
            assertEquals(1,store.searchPage("重试后的正文",0,5).first.length())
            assertEquals(WhaleParseFailure.NETWORK_TIMEOUT,WhaleParseFailure.from(java.net.SocketTimeoutException("private/path/sk-secret")))
            assertEquals(WhaleParseFailure.PARSE_FAILED,WhaleParseFailure.from(IllegalStateException("private source text")))
        } finally { root.walkBottomUp().forEach { it.delete() } }
    }

    @Test fun emptyTextAndUnsupportedDocumentsHaveDifferentReasons() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("parse-reason-",".bin",app.cacheDir)
        try {
            file.writeText(" ")
            val empty = assertThrows(WhaleParseFailure.Error::class.java) { WhaleDocumentImport.parse(app,file,"txt","text/plain") }
            val unsupported = assertThrows(WhaleParseFailure.Error::class.java) { WhaleDocumentImport.parse(app,file,"unknown","application/octet-stream") }
            assertEquals(WhaleParseFailure.EMPTY_CONTENT,empty.reason)
            assertEquals(WhaleParseFailure.UNSUPPORTED_FORMAT,unsupported.reason)
        } finally { file.delete() }
    }
}
