package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleCaptureFeedTest {
    private fun withLibrary(body: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "capture-test-" + UUID.randomUUID()
        val root = File(app.cacheDir, key).apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(key + name, mode)
        }
        try {
            body(isolated)
        } finally { root.deleteRecursively() }
    }

    @Test fun modelSearchRecoversPreviousOriginsAndFilenamesWithLiteralExcerpts() = withLibrary { isolated ->
        val store = CompanionKnowledgeStore(isolated)
        val body = "评审安排在周六上午"
        val wx = WhaleShareSource.from("com.tencent.mm",null,false)
        val xhs = WhaleShareSource.from("com.xingin.xhs",null,false)
        val id = store.saveText(body,"shared_text",wx).recordIds.single()
        store.saveText(body,"shared_text",xhs)
        store.saveText("其他平台的旅行清单","shared_text",xhs)
        val hash = "b".repeat(64)
        store.registerOriginal(hash,"蓝港简报.txt","text/plain",WhaleShareSource("本地文件",null))
        val part = store.addDocument("正文只有潮汐记录",hash,"蓝港简报.txt",true).getJSONObject(0)
        fun search(request: JSONObject) = JSONObject(CompanionKnowledgeModel.execute(true,request.toString(),store::findById,
            exact=store::exactPage) { error("不应扫描全库") })
        val query = JSONObject().put("query","微信").put("limit",1)
        repeat(2) {
            val result = search(query)
            assertEquals("exact",result.getString("matchMode"))
            val hit = result.getJSONArray("items").getJSONObject(0)
            assertEquals(id,hit.getString("id"))
            assertEquals(body,hit.getString("excerpt"))
            assertEquals("小红书分享",hit.getString("shareOrigin"))
            assertEquals(listOf("微信分享","小红书分享"),hit.getJSONArray("shareOriginLabels").let { a -> (0 until a.length()).map(a::getString) })
            assertEquals(id,store.lexicalCandidates("微信",10).single().getString("id"))
        }
        assertEquals(0,search(JSONObject(query.toString()).put("source","original")).getJSONArray("items").length())
        val related = search(JSONObject().put("query","微信 评审"))
        assertEquals("related",related.getString("matchMode"))
        assertEquals(id,related.getJSONArray("items").getJSONObject(0).getString("id"))
        assertTrue(store.lexicalCandidates("蓝港简报",10).any { it.getString("id") == part.getString("id") })
        val reread = search(JSONObject().put("id",id).put("query",""))
        assertEquals(body,reread.getString("text"))
        assertEquals(2,reread.getJSONArray("shareOriginLabels").length())
        assertTrue(store.remove(id,body))
        assertEquals(0,search(query).getJSONArray("items").length())
    }

    @Test fun repeatIntakePaginationDeletionAndRestoreUseCurrentRows() = withLibrary { isolated ->
        val store = CompanionKnowledgeStore(isolated)
        val dashboard = CompanionDashboard(isolated)
        val wx = WhaleShareSource.from("com.tencent.mm", null, false)
        val xhs = WhaleShareSource.from("com.xingin.xhs", null, false)
        fun cards() = dashboard.execute(JSONObject().put("operation", "knowledgeRecentCaptures")).getJSONArray("items")
        fun read(card: JSONObject, offset: Int = 0) = dashboard.execute(JSONObject().put("operation", "knowledgeCaptureRead")
            .put("id", card.getString("id")).put("createdAt", card.getLong("createdAt")).put("generation", card.optString("generation")).put("offset", offset))
        for (i in 0..10) store.saveText("投放样例 $i", "shared_text", wx)
        assertEquals(8, cards().length())
        val second = store.saveText("投放样例 2", "shared_text", xhs)
        assertFalse(second.added)
        val top = cards().getJSONObject(0)
        assertEquals(second.recordIds.single(), top.getString("id"))
        assertEquals("小红书分享", top.getString("shareOrigin"))
        assertEquals("投放样例 2", read(top).getJSONObject("row").getString("text"))
        assertFalse(store.saveText("投放样例 2", "shared_text", wx).added)
        val history = read(top).getJSONObject("row").getJSONArray("shareOrigins")
        assertEquals(listOf("小红书分享","微信分享"), (0 until history.length()).map { history.getJSONObject(it).getString("label") })
        val body = (1..7).joinToString("") { ("段落" + it).repeat(2700) }
        val long = store.saveText(body, "shared_text", wx)
        val capture = cards().getJSONObject(0)
        assertEquals(long.recordIds.first(), capture.getString("id"))
        val combined = StringBuilder()
        var page = read(capture)
        while (true) {
            val parts = page.getJSONArray("items")
            assertTrue(parts.length() in 1..3)
            for (i in 0 until parts.length()) combined.append(parts.getJSONObject(i).getString("text"))
            if (page.isNull("nextOffset")) break
            page = read(capture, page.getInt("nextOffset"))
        }
        assertEquals(body, combined.toString())
        val hash = "b".repeat(64)
        val anchor = store.registerOriginal(hash, "分段文本.txt", "text/plain", wx)
        store.addDocument(body, hash, "分段文本.txt", true, wx)
        store.updateOriginalCoverage(hash, "extracted_text")
        val pointer = cards().getJSONObject(0)
        val first = read(pointer).getJSONArray("items").getJSONObject(0)
        assertTrue(store.remove(first.getString("id"), first.getString("text")))
        assertEquals(1, read(pointer).getJSONArray("items").getJSONObject(0).getInt("chunkIndex"))
        val backup = UnifiedMemoryBackup.export(isolated)
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(backup.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT 1 FROM knowledge_meta WHERE key=?", arrayOf(KnowledgeCaptureFeed.KEY)).use { assertFalse(it.moveToFirst()) }
            }
        } finally { backup.delete() }
        assertTrue(store.removeOriginal(hash, anchor.getString("id"), anchor.getString("text")).records > 0)
        assertTrue(read(pointer).getBoolean("missing"))
        store.registerOriginal(hash, "重新投放.txt", "text/plain", xhs)
        assertTrue(read(pointer).getBoolean("missing"))
        assertEquals("重新投放.txt", read(cards().getJSONObject(0)).getJSONObject("row").getString("displayName"))
        assertTrue(store.remove(top.getString("id"), top.getString("text")))
        assertTrue(read(top).getBoolean("missing"))
    }

    @Test fun measureEightCardsAndThreeChunksInLargeLibrary() = withLibrary { isolated ->
        val store = CompanionKnowledgeStore(isolated)
        val unrelated = JSONArray()
        repeat(3000) { i ->
            unrelated.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", "历史资料 $i " + "目录文字".repeat(140))
                .put("source","text_file").put("createdAt",1000L+i))
        }
        store.withParseDatabase { it.write(unrelated) }
        repeat(7) { store.saveText("文件样例 $it", "shared_text", WhaleShareSource("本地文件", null)) }
        store.saveText("正文样例".repeat(6300), "shared_text", WhaleShareSource("系统分享", null))
        val dashboard = CompanionDashboard(isolated)
        val list = JSONObject().put("operation","knowledgeRecentCaptures")
        val cards = dashboard.execute(list).getJSONArray("items")
        assertEquals(8,cards.length())
        val first = cards.getJSONObject(0)
        val detail = JSONObject().put("operation","knowledgeCaptureRead").put("id",first.getString("id"))
            .put("createdAt",first.getLong("createdAt")).put("offset",0)
        assertEquals(3,dashboard.execute(detail).getJSONArray("items").length())
        fun measure(request: JSONObject, name: String) {
            val times = mutableListOf<Long>()
            var bytes = 0
            repeat(20) {
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                bytes = dashboard.execute(request).toString().toByteArray().size
                times.add((android.os.SystemClock.elapsedRealtimeNanos()-start)/1000)
            }
            times.sort()
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                putLong(name+"MedianUs", (times[9]+times[10])/2)
                putLong(name+"P95Us",times[18]); putInt(name+"Bytes",bytes)
                putLong("actualRecords",store.count())
            })
        }
        measure(list,"recent")
        measure(detail,"content")
    }
}
