package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleBackgroundAuthorTest {
    @Test fun onlyExplicitLocalCreationCanWriteItsOwnUnpublishedDraft() = verifyAuthor(false)
    @Test fun boundCreationPinsItsOriginalOwnerAndRequiresTheNewProtocol() = verifyAuthor(true)
    private fun verifyAuthor(bound: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "author-test-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val names = mutableSetOf<String>()
        val ctx = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences((root.name+name).also { names.add(it) },mode)
        }
        try {
            val store = WorkerDashboardStore(ctx)
            val features = WhaleFeatureStore(ctx)
            val worker = UUID.randomUUID().toString()
            val owner = if (bound) "session-"+UUID.randomUUID() else "session-whale-task-desk"
            if (bound) BubbleSessionRegistry(ctx).record(owner)
            val command = store.submit("生成一项工具", "create",sourceSession=owner.takeIf { bound }).getString("commandId")
            if (bound) {
                assertEquals(0,store.batch(0,true).length())
                assertEquals(command,store.batch(0,true,true).getJSONObject(0).getString("commandId"))
            }
            val id = "task-$command"
            val row = JSONObject().put("id",worker).put("sessionId","session-worker-$worker").put("owner",owner)
                .put("mode","create").put("state","running").put("revision",1).put("title","后台生成")
                .put("creationCommandId",command).put("domainAssignmentId","assignment-one").put("writeSeq",1)
            val req = JSONObject().put("workerId",worker).put("id",id).put("commandId",command).put("revision",1)
                .put("assignmentId","assignment-one").put("action","draft").put("title","草稿工具").put("html","<p>第一版</p>")
            row.put("localCreation",command).put("authoredDraft",JSONObject().put("id","other"))
            store.put(row)
            assertThrows(IllegalStateException::class.java) { store.author(req) }
            assertThrows(IllegalArgumentException::class.java) { features.draft(req) }
            if (bound) {
                store.put(JSONObject(row.toString()).put("owner","session-other").put("writeSeq",1))
                assertThrows(IllegalStateException::class.java) { store.acknowledge(command,worker) }
                store.put(row)
            }
            store.acknowledge(command, worker)
            store.acknowledge(command, worker)
            assertThrows(IllegalStateException::class.java) { store.author(JSONObject(req.toString()).put("id","other")) }
            assertThrows(IllegalStateException::class.java) { store.author(JSONObject(req.toString()).put("revision",2)) }
            assertThrows(IllegalStateException::class.java) { store.author(JSONObject(req.toString()).put("assignmentId","expired")) }
            // A pre-upgrade main-session feature could already use the now-reserved name.
            val legacyDir = File(root, "whale-features-v1").apply { mkdirs() }
            val collision = File(legacyDir, "$id.json")
            collision.writeText(JSONObject().put("id",id).put("latest","legacy").put("versions",JSONObject()).toString())
            assertThrows(IllegalStateException::class.java) { store.author(JSONObject(req.toString()).put("action","list")) }
            assertThrows(IllegalStateException::class.java) { store.author(req) }
            assertTrue(collision.delete())
            val first = store.author(req).getString("latest")
            assertEquals(first, store.author(req).getString("latest"))
            assertTrue(features.activeTools().isEmpty())
            assertEquals(first, store.author(JSONObject(req.toString()).put("action","read")).getString("revision"))
            if (bound) {
                store.put(JSONObject(row.toString()).put("owner","session-other").put("localCreationOwner","session-other").put("writeSeq",2))
                assertThrows(IllegalStateException::class.java) { store.author(req) }
                store.put(row.put("writeSeq",2))
                assertEquals(first,store.author(req).getString("latest"))
            }
            assertThrows(IllegalStateException::class.java) { store.author(JSONObject(req.toString()).put("action","activate")) }
            store.put(row.put("writeSeq",2))
            assertEquals(first, store.list().getJSONArray("items").getJSONObject(0).getJSONObject("authoredDraft").getString("revision"))
            val cancel = store.command(JSONObject().put("id",worker).put("action","cancel"))
            assertTrue(cancel.getBoolean("ok"))
            val updated = JSONObject(req.toString()).put("baseRevision",first).put("html","<p>撤销后拒绝</p>")
            assertThrows(IllegalStateException::class.java) { store.author(updated) }
            val cancelId = store.pending().getJSONObject(0).getString("commandId")
            store.acknowledge(cancelId)
            store.put(row.put("writeSeq",3))
            assertThrows(IllegalStateException::class.java) { WorkerDashboardStore(ctx).author(updated) }
            assertEquals(first, features.read(id)!!.getString("latest"))
            assertTrue(features.read(id)!!.optString("active").isEmpty())
            // Neither a device nor a read submission can be converted into a creation receipt.
            for (mode in listOf("read","device")) {
                val plain = store.submit("普通任务",mode).getString("commandId")
                val fake = UUID.randomUUID().toString()
                store.put(JSONObject(row.toString()).put("id",fake).put("sessionId","session-worker-$fake").put("creationCommandId",plain))
                store.acknowledge(plain, fake)
                assertThrows(IllegalStateException::class.java) {
                    store.author(JSONObject(req.toString()).put("id","task-$plain").put("commandId",plain).put("workerId",fake))
                }
            }
        } finally { names.forEach { app.deleteSharedPreferences(it) }; root.deleteRecursively() }
    }
}
