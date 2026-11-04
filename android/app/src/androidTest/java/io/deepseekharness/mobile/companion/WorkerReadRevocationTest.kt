package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.DeviceBridgeServer
import io.deepseekharness.mobile.shizuku.DeviceCommandRunner
import io.deepseekharness.mobile.shizuku.ShizukuRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class WorkerReadRevocationTest {
    @Test fun withdrawingAccessDuringUnifiedSearchWithholdsThePendingResponse() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = UUID.randomUUID().toString()
        val root = File(app.cacheDir,"read-revoke-$tag").apply { check(mkdirs()) }
        val names = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val revoke = AtomicBoolean(false)
        val ctx = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val prefs = app.getSharedPreferences((tag + name).also(names::add),mode)
                return object : SharedPreferences by prefs {
                    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                        if (key == "notification_model_access" && revoke.compareAndSet(true,false)) {
                            check(prefs.edit().putBoolean("knowledge_model_access",false).commit())
                        }
                        return prefs.getBoolean(key,defValue)
                    }
                }
            }
        }
        val service = ShizukuRuntime(ctx,{ _,_,_ -> },{ _,_ -> })
        val bridge = DeviceBridgeServer(service,DeviceCommandRunner { _,_ -> error("设备执行不在本测试内") },tag,appContext=ctx)
        val owner = "session-whale-task-desk"
        fun search(vararg kinds: String): JSONObject {
            val param = JSONObject().put("callerSession",owner).put("query","隔离测试")
                .put("kinds",JSONArray(kinds.toList()))
            val bytes = JSONObject().put("command","companionUnifiedSearch").put("param",param.toString()).toString().toByteArray()
            return Socket("127.0.0.1",bridge.localPort).use { socket ->
                socket.soTimeout = 10000
                val output = socket.getOutputStream()
                output.write(("POST /device-command HTTP/1.0\r\nAuthorization: Bearer $tag\r\nContent-Length: " + bytes.size + "\r\n\r\n").toByteArray())
                output.write(bytes); output.flush()
                JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
            }
        }
        try {
            BubbleSessionRegistry(ctx).record(owner)
            val prefs = CompanionStore(ctx)
            prefs.notificationModelAccess = true
            prefs.knowledgeModelAccess = true
            val marker = "隔离测试 $tag"
            CompanionKnowledgeStore(ctx).saveText(marker,"shared_text",WhaleShareSource("测试资料",null))
            bridge.start()
            assertTrue(search("knowledge","tasks").getString("text").contains(marker))
            revoke.set(true)
            val denied = search("knowledge","tasks")
            assertFalse(revoke.get())
            assertFalse("撤权后响应仍携带测试资料",denied.getString("text").contains(marker))
            assertEquals("KNOWLEDGE_MODEL_DISABLED",denied.optString("errorCode"))
            assertEquals("",denied.getString("text"))
            assertEquals("KNOWLEDGE_MODEL_DISABLED",search("knowledge").optString("errorCode"))
            assertTrue(search("tasks").getBoolean("ok"))
            prefs.knowledgeModelAccess = true
            assertTrue(search("knowledge","tasks").getString("text").contains(marker))
        } finally {
            bridge.stop()
            service.shutdown()
            names.forEach(app::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }
}
