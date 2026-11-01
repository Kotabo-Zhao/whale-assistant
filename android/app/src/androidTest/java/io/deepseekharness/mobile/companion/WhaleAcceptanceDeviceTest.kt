package io.deepseekharness.mobile.companion

import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real share activity and on-device index; uses a named, synthetic user-visible fixture. */
@RunWith(AndroidJUnit4::class)
class WhaleAcceptanceDeviceTest {
    @Test fun offlineRichRendererTypesetsFractionsAndMarkdown() {
        // Android ICU rejects some regex syntax accepted by desktop JVM; exercise the
        // native streaming/fallback path before the finished answer enters the WebView.
        assertTrue(CompanionRichText.render("\$\$\\frac{1}{2}+x^{2}\$\$").toString().contains("1⁄2"))
        assertTrue(CompanionRichText.requiresRichRenderer("# 标题\n**重点**"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = java.util.concurrent.CountDownLatch(1)
        val output = java.util.concurrent.atomic.AtomicReference<String>()
        lateinit var view: android.webkit.WebView
        instrumentation.runOnMainSync {
            view = android.webkit.WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true; settings.blockNetworkLoads = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(v: android.webkit.WebView, url: String) {
                        val sample = org.json.JSONObject.quote("# 标题\n\n**重点**\n\n\\[\\frac{a}{b}+x^2\\]")
                        v.evaluateJavascript("renderMessage($sample); JSON.stringify({fraction:!!document.querySelector('.frac-line'),bold:!!document.querySelector('strong'),heading:!!document.querySelector('h1')})") {
                            output.set(it); ready.countDown()
                        }
                    }
                }
                loadUrl("file:///android_asset/rich-chat/index.html")
            }
        }
        assertTrue("offline renderer did not respond", ready.await(30, java.util.concurrent.TimeUnit.SECONDS))
        val result = output.get().replace("\\\"", "\"")
        assertTrue("fraction not typeset: $result", result.contains("\"fraction\":true"))
        assertTrue("Markdown not rendered: $result", result.contains("\"bold\":true") && result.contains("\"heading\":true"))
        instrumentation.runOnMainSync { view.destroy() }
    }
    @Test fun sharedFileShowsResultAndCanBeRetrievedSemantically() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "share/whale-acceptance-20261101.txt")
        file.parentFile!!.mkdirs()
        instrumentation.context.assets.open("whale-acceptance-20261101.txt").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val intent = Intent(context, WhaleMemoryShareActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val toast = instrumentation.uiAutomation.executeAndWaitForEvent(
            { context.startActivity(intent) },
            { event -> event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.packageName == context.packageName && event.text.isNotEmpty() },
            30_000,
        )
        val message = toast.text.joinToString(" ")
        assertTrue("import notice: $message", message.contains("已分段保存") || message.contains("后台整理中"))
        val knowledge = CompanionKnowledgeStore(context)
        val rows = knowledge.searchPage("青岚天文站", 0, 10).first
        assertTrue("imported document should be stored", rows.length() > 0)
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.optString("text").contains("除雾") }
        val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (SemanticIndex(context).use { it.isCurrent(row.getString("id"), CompanionMemorySuppression.digest(row.getString("text"))) }) break
            android.os.SystemClock.sleep(2_000)
        }
        assertTrue("fixture should be indexed", SemanticIndex(context).use { it.isCurrent(row.getString("id"), CompanionMemorySuppression.digest(row.getString("text"))) })
        val result = JSONObject(SemanticKnowledgeSearch.execute(context, JSONObject().put("query", "哪一天晚上进行天体观测？").toString()))
        assertEquals("hybrid", result.getString("matchMode"))
        assertTrue("paraphrased question should find the test document", result.getJSONArray("items").toString().contains("青岚天文站"))
    }
}
