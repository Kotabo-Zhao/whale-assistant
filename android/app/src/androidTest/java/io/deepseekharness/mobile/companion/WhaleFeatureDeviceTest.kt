package io.deepseekharness.mobile.companion

import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.features.WhaleFeatureActivity
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WhaleFeatureDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val base get() = inst.targetContext
    @Test fun rejectsDoubleEncodedMarkupWithoutRewritingScriptEscapes() {
        val dir = File(base.cacheDir,"escaped-tool-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val store = WhaleFeatureStore(object : ContextWrapper(base) { override fun getNoBackupFilesDir() = dir })
        try {
            fun input(html: String, revision: String = "") = JSONObject().put("id","sample").put("title","转义检查").put("html",html).put("baseRevision",revision)
            val first = store.draft(input("<p>原版</p>")).getString("latest")
            val bad = "<div>\\n<p>甲</p>\\n<p>乙</p>\\n<p>丙</p>\\n</div>"
            val failure = assertThrows(io.deepseekharness.mobile.runtime.RuntimeFailure::class.java) { store.draft(input(bad,first)) }
            assertEquals("FEATURE_HTML_DOUBLE_ESCAPED",failure.code)
            assertTrue(failure.message!!.contains("重复转义"))
            assertEquals(first,store.read("sample")!!.getString("latest"))
            val source = "<div>\n<p>正常换行</p>\n<script>const lines='a\\nb'.split('\\n');</script><pre><code>\\n</code><code>\\n</code><code>\\n</code></pre></div>"
            val next = store.draft(input(source,first)).getString("latest")
            assertEquals(source,store.read("sample")!!.getJSONObject("versions").getJSONObject(next).getString("html"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun draftEnableRollbackAndStaleEdits() {
        val dir = File(base.cacheDir, "feature-test-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val store = WhaleFeatureStore(object : ContextWrapper(base) { override fun getNoBackupFilesDir() = dir })
        try {
            fun draft(code: String, revision: String = "") = store.draft(JSONObject().put("id","example").put("title","试用")
                .put("html",code).put("baseRevision",revision)).getString("latest")
            val first = draft("<p>一</p>")
            assertEquals(first, draft("<p>一</p>"))
            store.activate("example", first, "")
            val next = draft("<p>二</p>", first)
            assertThrows(IllegalArgumentException::class.java) { draft("<p>旧编辑</p>", first) }
            assertEquals(first, store.list().single().getString("active"))
            store.activate("example", next, first)
            assertEquals(first, store.list().single().getString("previous"))
            store.activate("example", first, next)
            store.activate("example", "", first)
            assertEquals(first, store.list().single().getString("previous"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun activeCatalogReadsMetadataOnceAndInvalidatesOnPublication() {
        val dir = File(base.cacheDir, "tool-catalog-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val store = WhaleFeatureStore(object : ContextWrapper(base) { override fun getNoBackupFilesDir() = dir })
        try {
            val source = "<p>" + "鲸鱼".repeat(5200) + "</p>"
            var revision = ""
            repeat(36) { index ->
                val id = "tool-$index"
                revision = store.draft(JSONObject().put("id",id).put("title","测试工具 $index").put("html",source)).getString("latest")
                store.activate(id, revision, "")
            }
            val start = SystemClock.elapsedRealtimeNanos()
            val first = store.activeTools()
            val warm = SystemClock.elapsedRealtimeNanos()
            repeat(200) { assertSame(first, store.activeTools()) }
            val end = SystemClock.elapsedRealtimeNanos()
            assertEquals(36, first.size)
            store.activate("tool-35", "", revision)
            assertEquals(35, store.activeTools().size)
            inst.sendStatus(0, android.os.Bundle().apply {
                putInt("tools", first.size); putLong("coldCatalogUs", (warm-start)/1000); putLong("warmCatalogMeanUs", (end-warm)/200000)
            })
        } finally { dir.deleteRecursively() }
    }
    @Test fun enabledToolOpensInPetWindow() {
        val tool = WhaleFeatureStore(base).activeTools().firstOrNull { it.id == "whale-temperature" }
            ?: WhaleFeatureStore(base).activeTools().first { it.title == "工时换算" || it.title == "阅读摘录卡" }
        val monitor = inst.addMonitor(io.deepseekharness.mobile.features.WhalePetFeatureActivity::class.java.name, null, false)
        try {
            WhaleFeatureActivity.openTool(base, tool.id, tool.revision, pet = true)
            val activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 8000))
            try {
                val end = SystemClock.elapsedRealtime() + 12000
                var ready = false
                fun web(view: View): WebView? = when (view) {
                    is WebView -> view
                    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { web(view.getChildAt(it)) }
                    else -> null
                }
                while (!ready && SystemClock.elapsedRealtime() < end) {
                    inst.runOnMainSync {
                        val page = web(activity.window.decorView)
                        page?.evaluateJavascript("document.readyState + ' ' + location.hostname") { result ->
                            ready = result == "\"" + "complete " + tool.id + ".features.whale.invalid\""
                        }
                    }
                    if (!ready) Thread.sleep(100)
                }
                assertTrue("已启用工具需在隔离的工具域内完成加载", ready)
                inst.runOnMainSync {
                    assertTrue(activity.window.isFloating)
                    assertTrue(activity.window.decorView.width < activity.resources.displayMetrics.widthPixels)
                }
            } finally { inst.runOnMainSync { activity.finish() } }
        } finally { inst.removeMonitor(monitor) }
    }

    @Test fun openPetToolReleasesReplacedRolledBackAndDisabledRevisions() {
        val store = WhaleFeatureStore(base)
        val id = "proof-" + UUID.randomUUID().toString().take(12)
        val other = "$id-b"
        val monitor = inst.addMonitor(io.deepseekharness.mobile.features.WhalePetFeatureActivity::class.java.name,null,false)
        var window: android.app.Activity? = null
        fun draft(key: String, title: String, previous: String = "") = store.draft(JSONObject()
            .put("id",key).put("title",title).put("html","<output data-whale-result>$title</output>")
            .put("baseRevision",previous)).getString("latest")
        fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        fun await(check: (List<View>) -> Boolean) {
            val until = SystemClock.elapsedRealtime()+8000
            do {
                var ok = false
                inst.runOnMainSync { ok = check(views(requireNotNull(window).window.decorView)) }
                if (ok) return
                Thread.sleep(60)
            } while (SystemClock.elapsedRealtime() < until)
            fail("工具窗口状态未更新")
        }
        fun click(label: String) = await { it.filterIsInstance<Button>().singleOrNull { b -> b.text == label }?.performClick() == true }
        fun closed(label: String) = await { it.none { v -> v is WebView } && it.filterIsInstance<android.widget.TextView>().any { v -> v.text == label } }
        try {
            val first = draft(id,"工具一")
            store.activate(id,first,"")
            WhaleFeatureActivity.openTool(base,id,first,pet=true)
            window = requireNotNull(inst.waitForMonitorWithTimeout(monitor,8000))
            lateinit var old: WebView
            await { nodes -> nodes.filterIsInstance<WebView>().singleOrNull()?.let { old = it; true } == true }
            val second = draft(id,"工具二",first)
            store.activate(other,draft(other,"其他工具"),"")
            await { nodes -> nodes.any { it === old } }
            store.activate(id,second,first)
            closed("工具版本已变更"); click("打开当前版本")
            await { nodes -> nodes.any { it is WebView && it !== old } && nodes.filterIsInstance<android.widget.TextView>().any { it.text == "工具二" } }
            store.activate(id,first,second)
            closed("工具版本已变更"); click("打开当前版本")
            await { nodes -> nodes.any { it is WebView } && nodes.filterIsInstance<android.widget.TextView>().any { it.text == "工具一" } }
            store.activate(id,"",first)
            closed("此工具已停用")
        } finally {
            inst.runOnMainSync { window?.finish() }
            inst.removeMonitor(monitor)
            for (key in listOf(id,other)) {
                store.read(key)?.optString("active")?.takeIf(String::isNotBlank)?.let { store.activate(key,"",it) }
                android.util.AtomicFile(File(base.noBackupFilesDir,"whale-features-v1/$key.json")).delete()
            }
        }
    }

    @Test fun generatedToolRunsInApp() {
        val store = WhaleFeatureStore(base)
        val row = requireNotNull(store.read("whale-temperature"))
        val version = row.getString("latest")
        val title = row.getJSONObject("versions").getJSONObject(version).getString("title")
        val activity = inst.startActivitySync(Intent(base, WhaleFeatureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WhaleFeatureActivity
        fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        fun waitFor(action: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 12_000
            var success = false
            while (SystemClock.elapsedRealtime() < end && !success) { inst.runOnMainSync { success = action() }; if (!success) Thread.sleep(80) }
            assertTrue("工坊界面操作超时", success)
        }
        fun click(label: String) = waitFor {
            val card = children(activity.window.decorView).filterIsInstance<android.widget.TextView>().firstOrNull { it.text == title }?.parent as? View
            val nodes = card?.let(::children).orEmpty()
            val target = nodes.filterIsInstance<Button>().firstOrNull { it.text == label }
            if (target != null && !target.isShown) nodes.filterIsInstance<Button>().firstOrNull { it.text == "管理" }?.performClick()
            target?.takeIf { it.isShown }?.performClick() == true
        }
        fun evaluate(script: String): String {
            lateinit var web: WebView
            waitFor { children(activity.window.decorView).filterIsInstance<WebView>().firstOrNull()?.let { web = it; true } == true }
            val latch = CountDownLatch(1)
            var result = ""
            inst.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
            assertTrue(latch.await(5, TimeUnit.SECONDS)); return result
        }
        try {
            val isNew = row.optString("active") != version
            click(if (isNew) "预览新版本" else "打开功能")
            val until = SystemClock.elapsedRealtime() + 6_000
            while (evaluate("!!document.getElementById('celsius')") != "true" && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            for ((input, expected) in listOf("25" to "77", "-40" to "-40", "" to "")) {
                val value = evaluate("""(()=>{const input=document.getElementById('celsius');input.value='$input';input.dispatchEvent(new Event('input',{bubbles:true}));return document.getElementById('fahrenheit').textContent.trim()})()""")
                if (expected.isNotEmpty()) assertTrue(value, expected in value) else assertFalse(value, value.contains("-40"))
                if (input == "25") {
                    val ready = CountDownLatch(1)
                    inst.runOnMainSync {
                        children(activity.window.decorView).filterIsInstance<WebView>().single().postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) { ready.countDown() }
                        })
                    }
                    assertTrue(ready.await(4, TimeUnit.SECONDS)); Thread.sleep(220)
                    val decor = activity.window.decorView
                    val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                    val latch = CountDownLatch(1)
                    var copied = -1
                    android.view.PixelCopy.request(activity.window, bitmap, { copied = it; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                    assertTrue(latch.await(4, TimeUnit.SECONDS)); assertEquals(android.view.PixelCopy.SUCCESS, copied)
                    File(base.getExternalFilesDir(null), "whale-feature-temperature.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            assertEquals("true", evaluate("typeof window.Capacitor === 'undefined' && typeof window.Android === 'undefined'"))
            assertEquals("true", evaluate("(()=>{localStorage.setItem('whaleTestEntry','saved');const saved=localStorage.getItem('whaleTestEntry');localStorage.removeItem('whaleTestEntry');return saved==='saved' && location.hostname.endsWith('.whale.invalid')})()"))
            if (isNew) {
                inst.runOnMainSync { activity.onBackPressed() }
                click("启用此版本")
                waitFor { store.read("whale-temperature")?.optString("active") == version }
            }
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
