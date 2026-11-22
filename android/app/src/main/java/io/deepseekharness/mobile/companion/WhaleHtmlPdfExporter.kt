package io.deepseekharness.mobile.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 原生 Android WebView 通用 HTML/CSS/SVG → 多页矢量 PDF 渲染引擎（零外部体积增量）。
 *
 * 使用系统内置 Chromium WebView 排版渲染 HTML，再通过标准 [PdfDocument] 按 A4 比例（595×842pt，最多 20 页）
 * 逐页绘制并输出到项目工作区，配合 [WhaleCodeRunner.exportProjectFile] 一键导出至手机下载目录或系统分享。
 */
internal object WhaleHtmlPdfExporter {
    private const val PAGE_WIDTH_PT = 595
    private const val PAGE_HEIGHT_PT = 842
    private const val MAX_PAGES = 20

    @SuppressLint("SetJavaScriptEnabled")
    fun renderToPdf(
        context: Context,
        html: String,
        project: String = "scratch",
        fileName: String = "document.pdf",
        exportToDownloads: Boolean = true,
        shareNow: Boolean = false,
    ): JSONObject {
        val app = context.applicationContext
        require(html.isNotBlank() && html.length <= 512 * 1024) { "HTML 内容不能为空且不超过 512KB" }

        val safeProj = project.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(48).ifBlank { "scratch" }
        val rawName = fileName.trim().replace(Regex("[^a-zA-Z0-9._\\-\u4e00-\u9fa5]"), "_").ifBlank { "document.pdf" }
        val finalName = if (rawName.lowercase().endsWith(".pdf")) rawName else "$rawName.pdf"
        val projectDir = File(File(app.filesDir, "whale-projects"), safeProj).apply { mkdirs() }
        val pdfFile = File(projectDir, finalName)

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)
        val pageCountRef = AtomicReference(0)
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            var wv: WebView? = null
            try {
                wv = WebView(app).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            mainHandler.postDelayed({
                                try {
                                    val density = app.resources.displayMetrics.density.coerceAtLeast(1f)
                                    val renderWidthPx = (PAGE_WIDTH_PT * density).toInt().coerceAtLeast(PAGE_WIDTH_PT)
                                    val pageHeightPx = (PAGE_HEIGHT_PT * density).toInt().coerceAtLeast(PAGE_HEIGHT_PT)

                                    view.measure(
                                        View.MeasureSpec.makeMeasureSpec(renderWidthPx, View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                    )
                                    val totalHeightPx = view.measuredHeight.coerceAtLeast(pageHeightPx)
                                    view.layout(0, 0, renderWidthPx, totalHeightPx)

                                    val totalPages = ((totalHeightPx + pageHeightPx - 1) / pageHeightPx).coerceIn(1, MAX_PAGES)
                                    val doc = PdfDocument()
                                    val scale = PAGE_WIDTH_PT.toFloat() / renderWidthPx.toFloat()
                                    try {
                                        for (p in 0 until totalPages) {
                                            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, p + 1).create()
                                            val page = doc.startPage(pageInfo)
                                            val canvas = page.canvas
                                            canvas.save()
                                            canvas.scale(scale, scale)
                                            canvas.translate(0f, -(p * pageHeightPx).toFloat())
                                            view.draw(canvas)
                                            canvas.restore()
                                            doc.finishPage(page)
                                        }
                                        FileOutputStream(pdfFile).use { out -> doc.writeTo(out) }
                                        pageCountRef.set(totalPages)
                                    } finally {
                                        doc.close()
                                    }
                                } catch (t: Throwable) {
                                    errorRef.set(t)
                                } finally {
                                    runCatching { view.destroy() }
                                    latch.countDown()
                                }
                            }, 280L)
                        }
                    }
                }
                wv.loadDataWithBaseURL("https://whale.local/", html, "text/html", "UTF-8", null)
            } catch (t: Throwable) {
                errorRef.set(t)
                runCatching { wv?.destroy() }
                latch.countDown()
            }
        }

        val completed = latch.await(25L, TimeUnit.SECONDS)
        if (!completed) {
            throw IllegalStateException("HTML 转 PDF 渲染超时（>25s）")
        }
        errorRef.get()?.let { throw IllegalStateException("HTML 转 PDF 失败：${it.message ?: it.javaClass.simpleName}", it) }

        val result = JSONObject()
            .put("ok", true)
            .put("project", safeProj)
            .put("path", finalName)
            .put("pages", pageCountRef.get())
            .put("bytes", pdfFile.length())

        if (exportToDownloads || shareNow) {
            val exported = WhaleCodeRunner.exportProjectFile(app, safeProj, finalName, shareNow)
            result.put("savedTo", exported.optString("savedTo"))
            result.put("shared", exported.optBoolean("shared", false))
        }
        return result
    }
}
