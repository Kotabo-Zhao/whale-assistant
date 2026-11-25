package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 从庞大的 WhaleNativeToolRegistry 拆分出的多模态视觉与点击自检处理模块：
 * - `whale_phone_vision`：截屏 + 精简控件树融合识屏；
 * - `whale_phone_vision_click`：视觉定位点击 + Post-Action 450ms 页面变化自检；
 * - 副屏截屏叠加 0–1000 归一化坐标网格，使副屏与主屏视觉精度完全对齐。
 */
internal object WhaleVisionToolHandlers {

    fun handlePhoneVision(
        app: Context,
        name: String,
        question: String,
        withNotice: (String, () -> DeepSeekAccessibilityService.ScreenCaptureResult) -> DeepSeekAccessibilityService.ScreenCaptureResult,
    ): WhaleNativeToolRegistry.ToolOutcome {
        val service = DeepSeekAccessibilityService.current()
            ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
        val cap = withNotice("正在看图识屏…") { service.captureScreenJpeg(annotateRefs = true) }
        if (!cap.ok || cap.jpeg == null) {
            return WhaleNativeToolRegistry.ToolOutcome(
                name,
                "看图识屏受限",
                false,
                JSONObject().put("error", cap.error ?: "截屏失败").toString(),
            )
        }
        val uiTree = runCatching { service.fastControl("fastTree", "").takeIf { it.ok }?.text.orEmpty() }.getOrDefault("")
        val analysis = WhaleVisionScreenAnalyzer.analyzeScreen(
            context = app,
            jpeg = cap.jpeg,
            screenWidth = cap.width,
            screenHeight = cap.height,
            packageName = cap.packageName,
            question = question,
            locateTarget = false,
            uiTreeSummary = uiTree,
        )
        return WhaleNativeToolRegistry.ToolOutcome(
            name,
            if (analysis.ok) "多模态看图识屏完成" else "视觉识屏失败",
            analysis.ok,
            JSONObject()
                .put("packageName", cap.packageName)
                .put("screenWidth", cap.width)
                .put("screenHeight", cap.height)
                .put("provider", analysis.provider)
                .put("model", analysis.model)
                .put("analysis", analysis.text)
                .toString(),
        )
    }

    fun handlePhoneVisionClick(
        app: Context,
        name: String,
        target: String,
        withCaptureNotice: (String, () -> DeepSeekAccessibilityService.ScreenCaptureResult) -> DeepSeekAccessibilityService.ScreenCaptureResult,
        withTapNotice: (String, () -> io.deepseekharness.mobile.accessibility.DeviceCommandResult) -> io.deepseekharness.mobile.accessibility.DeviceCommandResult,
    ): WhaleNativeToolRegistry.ToolOutcome {
        val service = DeepSeekAccessibilityService.current()
            ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
        require(target.isNotEmpty()) { "缺少要视觉定位点击的目标 target" }
        val cap = withCaptureNotice("视觉定位「${target.take(10)}」…") { service.captureScreenJpeg(annotateRefs = true) }
        if (!cap.ok || cap.jpeg == null) {
            return WhaleNativeToolRegistry.ToolOutcome(
                name,
                "视觉截屏受限",
                false,
                JSONObject().put("error", cap.error ?: "截屏失败").toString(),
                mutating = true,
            )
        }
        val beforeTree = runCatching { service.fastControl("fastTree", "").takeIf { it.ok }?.text.orEmpty() }.getOrDefault("")
        val loc = WhaleVisionScreenAnalyzer.analyzeScreen(
            context = app,
            jpeg = cap.jpeg,
            screenWidth = cap.width,
            screenHeight = cap.height,
            packageName = cap.packageName,
            question = target,
            locateTarget = true,
            uiTreeSummary = beforeTree,
        )
        if (!loc.ok || (loc.ref == null && (loc.x == null || loc.y == null))) {
            return WhaleNativeToolRegistry.ToolOutcome(
                name,
                "未能在画面中找到目标",
                false,
                JSONObject().put("error", loc.text).put("provider", loc.provider).put("model", loc.model).toString(),
                mutating = true,
            )
        }
        val tapJson = JSONObject().put("action", "click")
        if (loc.ref != null) tapJson.put("ref", loc.ref)
        if (loc.x != null && loc.y != null) tapJson.put("x", loc.x).put("y", loc.y)
        val tapRes = withTapNotice("视觉点击「${loc.text.take(10)}」") { service.fastControl("fastAct", tapJson.toString()) }

        // Post-Action Verification：等待 450ms 后对比控件树变化，识别点击未生效或被弹窗遮挡
        var screenChanged = false
        var verifyHint = ""
        if (tapRes.ok) {
            runCatching { Thread.sleep(450L) }
            val afterTree = runCatching { service.fastControl("fastTree", "").takeIf { it.ok }?.text.orEmpty() }.getOrDefault("")
            screenChanged = beforeTree.isBlank() || afterTree.isBlank() || afterTree != beforeTree
            if (!screenChanged) {
                verifyHint = "点击手势已派发，但 450ms 后页面控件树未检测到变化（可能被遮挡、目标不可点或坐标略有偏移），建议重新看屏确认。"
            }
        }
        val posDesc = if (loc.ref != null) "#${loc.ref}" else "(${loc.x},${loc.y})"
        return WhaleNativeToolRegistry.ToolOutcome(
            name,
            "视觉点击「${loc.text}」$posDesc",
            tapRes.ok,
            JSONObject()
                .put("clicked", tapRes.ok)
                .put("verifiedScreenChanged", screenChanged)
                .put("label", loc.text)
                .put("ref", loc.ref ?: JSONObject.NULL)
                .put("x", loc.x ?: JSONObject.NULL)
                .put("y", loc.y ?: JSONObject.NULL)
                .put("detail", tapRes.text)
                .apply { if (verifyHint.isNotBlank()) put("verifyHint", verifyHint) }
                .toString(),
            mutating = true,
        )
    }

    /** 给副屏 JPEG 叠加与主屏一致的极淡 10×10 归一化（0–1000）坐标网格与 200/500/800 刻度。 */
    fun overlayNormalizedGrid(jpeg: ByteArray): ByteArray {
        return runCatching {
            val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            src.recycle()
            val canvas = Canvas(bmp)
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(46, 255, 255, 255)
                strokeWidth = 1f
            }
            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(145, 255, 240, 140)
                textSize = (w * 0.022f).coerceIn(14f, 24f)
            }
            for (step in 1..9) {
                val ratio = step / 10f
                val gx = w * ratio
                val gy = h * ratio
                canvas.drawLine(gx, 0f, gx, h, gridPaint)
                canvas.drawLine(0f, gy, w, gy, gridPaint)
                if (step in setOf(2, 5, 8)) {
                    val label = (step * 100).toString()
                    canvas.drawText(label, gx + 3f, tickPaint.textSize + 4f, tickPaint)
                    canvas.drawText(label, 4f, gy - 3f, tickPaint)
                }
            }
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
            bmp.recycle()
            out.toByteArray()
        }.getOrDefault(jpeg)
    }
}
