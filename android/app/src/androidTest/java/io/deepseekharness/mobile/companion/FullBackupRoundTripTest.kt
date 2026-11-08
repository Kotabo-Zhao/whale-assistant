package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.SessionWorkspaceStore
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FullBackupRoundTripTest {
    @Test fun largeTranscriptAndWorkspaceSurviveRestoreAndCorruptionFailsClosed() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val root = File(app.cacheDir,"backup-test-$token").apply { check(mkdirs()) }
        val preferences = mutableSetOf<String>()
        fun isolated(name: String): Context = object : ContextWrapper(app) {
            val space = File(root,name).apply { check(mkdirs()) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = space
            override fun getCacheDir() = space
            override fun getFilesDir() = space
            override fun getDatabasePath(db: String) = File(space,db)
            override fun getSharedPreferences(key: String, mode: Int): android.content.SharedPreferences {
                val isolatedKey = "audit-$token-$name-$key"
                preferences.add(isolatedKey)
                return app.getSharedPreferences(isolatedKey,mode)
            }
        }
        try {
            val from = isolated("source")
            val to = isolated("destination")
            val reply = "鲸".repeat(60_000)
            val history = CompanionBubbleHistory(from)
            history.save(CompanionBubbleHistory.Snapshot("pet-audit","a".repeat(64),listOf(
                CompanionBubbleChat.Message("user","测试"), CompanionBubbleChat.Message("assistant",reply)
            )),requireWritable=true)
            val groups = org.json.JSONArray()
            repeat(300) { groups.put(org.json.JSONObject().put("id","session-$it").put("title","鲸".repeat(200))) }
            val bytes = org.json.JSONObject().put("version",1).put("entries",groups).toString().toByteArray()
            check(bytes.size > 150_000)
            SessionWorkspaceStore(from).mergeArchive(bytes)
            repeat(201) { BubbleSessionRegistry(from).record("pet-$it") }
            for ((ctx, marker) in listOf(from to "source", to to "destination")) {
                UnifiedMemoryBackup.export(ctx).delete()
                SQLiteDatabase.openDatabase(UnifiedUnderstandingStore.path(ctx).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("INSERT INTO understanding_items(id,job_id,event_id,title,state) VALUES (?,?,?,?,?)",arrayOf("same","job","event","title","new"))
                    db.execSQL("INSERT INTO secretary_items VALUES (?,?,NULL)",arrayOf("same",marker))
                }
            }
            val altered = UnifiedMemoryBackup.export(from)
            SQLiteDatabase.openDatabase(altered.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TRIGGER secretary_item_cleanup")
                db.execSQL("CREATE TRIGGER secretary_item_cleanup AFTER DELETE ON understanding_items BEGIN DELETE FROM secretary_items; END")
            }
            assertThrows(IllegalArgumentException::class.java) { UnifiedMemoryBackup.restore(to, altered) }
            val archive = UnifiedFullBackup.export(from)
            val destinationPrefs = to.getSharedPreferences("session_workspace_v1",Context.MODE_PRIVATE)
            check(destinationPrefs.edit().putString("entries","invalid").commit())
            assertThrows(Exception::class.java) { UnifiedFullBackup.restore(to, archive) }
            SQLiteDatabase.openDatabase(UnifiedUnderstandingStore.path(to).path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT body FROM secretary_items WHERE item_id='same'",null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("destination",it.getString(0))
                }
            }
            check(destinationPrefs.edit().remove("entries").commit())
            UnifiedFullBackup.restore(to, archive)
            SQLiteDatabase.openDatabase(UnifiedUnderstandingStore.path(to).path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT body FROM secretary_items WHERE item_id='same'",null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("source",it.getString(0))
                }
            }
            assertEquals(reply,CompanionBubbleHistory(to).load()?.messages?.last()?.text)
            assertEquals(300,SessionWorkspaceStore(to).entries().size)
            assertEquals(201,BubbleSessionRegistry(to).sessionIds().size)
            val prefs = from.getSharedPreferences("session_workspace_v1",Context.MODE_PRIVATE)
            check(prefs.edit().putString("entries","invalid").commit())
            assertThrows(Exception::class.java) { UnifiedFullBackup.export(from) }
            assertThrows(Exception::class.java) { SessionWorkspaceStore(from).mergeArchive(bytes) }
            assertEquals("invalid",prefs.getString("entries",null))
        } finally {
            preferences.forEach { app.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}
