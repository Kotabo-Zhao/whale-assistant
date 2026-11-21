package io.deepseekharness.mobile.companion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 仿荣耀 YOYO 记忆的「自动识别收藏与截屏入记忆」引擎（0 空闲 Token 消耗）。
 *
 * 两条精准自动采集链路（均严格受 [CompanionStore.backgroundAutoMemory] 总开关控制）：
 * 1. 应用内收藏感知：无障碍仅在检测到「收藏成功 / 已收藏 / 加入书签 / Saved」等信号时，
 *    跳过密码/银行/支付敏感页（[AccessibilityPasswordPolicy]），采集当前页面有效标题与正文摘要，
 *    自动写入本地资料库并触发后台语义提炼入长期记忆；
 * 2. 系统截屏感知：通过 [MediaStore] 监听新增截屏图片（路径含 Screenshots / 截屏），
 *    防抖去重后自动交给 [WhaleOriginalImport] 入库并进行后台视觉/文字理解。
 */
internal object WhaleAutoMemoryObserver {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-auto-memory").apply { isDaemon = true }
    }
    private val lastFavoriteCaptureMs = AtomicLong(0L)
    private val lastScreenshotCaptureMs = AtomicLong(0L)
    @Volatile private var lastScreenshotUri: String = ""
    @Volatile private var screenshotObserver: ContentObserver? = null

    private val FAVORITE_SIGNAL_REGEX = Regex(
        "收藏成功|已收藏|已加入收藏|已保存至收藏|加入收藏夹|加入书签|已添加书签|已加入稍后看|saved to|bookmarked|added to favorites",
        RegexOption.IGNORE_CASE,
    )

    private val SENSITIVE_PACKAGE_KEYWORDS = listOf(
        "bank", "alipay", "eg.android.AlipayGphone", "wallet", "pay", "password", "keepass", "bitwarden", "1password",
    )

    internal fun isFavoriteSignal(text: String?): Boolean {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty() || clean.length > 120) return false
        return FAVORITE_SIGNAL_REGEX.containsMatchIn(clean)
    }

    internal fun isScreenshotPath(pathOrName: String?): Boolean {
        val lower = pathOrName?.lowercase().orEmpty()
        return lower.contains("screenshot") || lower.contains("截屏") || lower.contains("screen_shot")
    }

    internal fun extractMeaningfulSummary(fragments: List<String>, maxChars: Int = 1400): String {
        val seen = LinkedHashSet<String>()
        val out = StringBuilder()
        for (raw in fragments) {
            val clean = raw.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ").trim()
            if (clean.length < 4) continue
            if (clean.length <= 6 && clean in setOf("返回", "分享", "评论", "点赞", "收藏", "关注", "更多", "首页", "我的", "取消", "确定")) continue
            if (!seen.add(clean)) continue
            if (out.isNotEmpty()) out.append("\n")
            out.append(clean)
            if (out.length >= maxChars) break
        }
        return out.toString().take(maxChars).trim()
    }

    /** 无障碍轻量钩子：仅在命中收藏信号时触发，绝不扫描普通浏览页面。 */
    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && type != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == service.packageName) return
        if (SENSITIVE_PACKAGE_KEYWORDS.any { pkg.contains(it, ignoreCase = true) }) return

        val joinedEventText = buildString {
            event.text?.forEach { if (!it.isNullOrBlank()) append(it).append(' ') }
            event.contentDescription?.takeIf { it.isNotBlank() }?.let { append(it) }
        }
        if (!isFavoriteSignal(joinedEventText)) return

        val app = service.applicationContext
        if (!CompanionStore(app).backgroundAutoMemory) return

        val now = System.currentTimeMillis()
        val prev = lastFavoriteCaptureMs.get()
        if (now - prev < 8_000L || !lastFavoriteCaptureMs.compareAndSet(prev, now)) return

        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return
        val fragments = ArrayList<String>(40)
        var hasSensitiveField = false
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 14 || fragments.size >= 48 || hasSensitiveField) return
            val vid = node.viewIdResourceName?.lowercase().orEmpty()
            val variation = node.inputType and android.text.InputType.TYPE_MASK_VARIATION
            val isPwdInput = variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            if (node.isPassword || isPwdInput || vid.contains("password") || vid.contains("passwd") || vid.contains("pin_code")) {
                hasSensitiveField = true
                return
            }
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { fragments.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { fragments.add(it) }
            for (i in 0 until node.childCount) {
                walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            }
        }
        runCatching { walk(root, 0) }
        if (hasSensitiveField) return

        val summary = extractMeaningfulSummary(fragments)
        if (summary.length < 18) return

        executor.execute {
            runCatching {
                val origin = WhaleShareSource.from(pkg, summary, false)
                val saved = CompanionKnowledgeStore(app).saveText(summary, "auto_favorite", origin)
                if (saved.added && saved.recordIds.size == 1 && summary.length <= CompanionKnowledgeData.MAX_RECORD_TEXT) {
                    SharedMemoryJobs.enqueueWithoutSchedule(app, saved.recordIds.single(), summary)
                    SharedMemoryJobs.schedule(app, 0)
                    Handler(Looper.getMainLooper()).post {
                        runCatching { Toast.makeText(app, "已自动收入鲸鱼记忆", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }

    /** 启动系统截屏监听（无存储权限时优雅静默不崩溃）。 */
    @Synchronized
    fun startScreenshotObserver(context: Context) {
        if (screenshotObserver != null) return
        val app = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!CompanionStore(app).backgroundAutoMemory) return
                val now = System.currentTimeMillis()
                val prev = lastScreenshotCaptureMs.get()
                if (now - prev < 5_000L || !lastScreenshotCaptureMs.compareAndSet(prev, now)) return
                executor.execute { inspectLatestScreenshot(app) }
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
            screenshotObserver = observer
        }
    }

    @Synchronized
    fun stopScreenshotObserver(context: Context) {
        val obs = screenshotObserver ?: return
        screenshotObserver = null
        runCatching { context.applicationContext.contentResolver.unregisterContentObserver(obs) }
    }

    private fun inspectLatestScreenshot(app: Context) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
        )
        runCatching {
            app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val id = cursor.getLong(0)
                val name = cursor.getString(1).orEmpty()
                val relPath = cursor.getString(2).orEmpty()
                val dateAddedSec = cursor.getLong(3)
                val nowSec = System.currentTimeMillis() / 1000L
                if (nowSec - dateAddedSec > 15L) return
                if (!isScreenshotPath("$relPath/$name")) return
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                val uriStr = uri.toString()
                if (uriStr == lastScreenshotUri) return
                lastScreenshotUri = uriStr
                val origin = WhaleShareSource("系统截屏自动收藏", null)
                val imported = WhaleOriginalImport.save(app, uri, "image/png", origin, emptyList())
                if (imported) {
                    Handler(Looper.getMainLooper()).post {
                        runCatching { Toast.makeText(app, "截屏已自动收入鲸鱼记忆", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }
}
