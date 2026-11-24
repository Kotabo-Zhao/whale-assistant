package io.deepseekharness.mobile.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 拖宠即问主题原生轮盘菜单（Radial Action Wheel）。
 *
 * - 跟随 [PetUi] 自动适配「极简·类Grok / 鲸鱼娘·可爱 / 乌萨奇·兔兔」及明暗主题；
 * - 中心轮毂展示提取到的完整图片圆形实时预览 / 链接域名 / 应用徽章；
 * - 外圈 6 个带独立手绘矢量图标的快捷操作按键，支持弹性绽放动效与触觉反馈。
 */
internal class RadialDragAskWheelView(
    context: Context,
    private val ui: PetUi,
    private val anchorX: Float,
    private val anchorY: Float,
    private val centerTitle: String,
    private val centerSubtitle: String,
    private val previewBitmap: Bitmap?,
    private val actions: List<WheelAction>,
    private val onSelect: (WheelAction) -> Unit,
    private val onDismiss: () -> Unit,
) : View(context) {

    enum class IconKind {
        SPARKLE,
        IMAGE_SEARCH,
        OCR,
        SAVE_IMAGE,
        LINK_SUMMARY,
        COPY_LINK,
        VERIFY,
        BOOKMARK,
        TRANSLATE,
        ASK_FREE,
        CLOSE,
    }

    data class WheelAction(
        val id: String,
        val label: String,
        val icon: IconKind,
        val primary: Boolean = false,
        val muted: Boolean = false,
    )

    private val dp = resources.displayMetrics.density
    private val orbitRadius = 110f * dp
    private val centerRadius = 46f * dp
    private val itemRadius = 27f * dp
    private val safeMargin = orbitRadius + itemRadius + 28f * dp

    private var cx = anchorX
    private var cy = anchorY
    private var bloomProgress = 0f
    private var pressedIndex = -1

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * dp
        pathEffect = DashPathEffect(floatArrayOf(6f * dp, 6f * dp), 0f)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * dp
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.0f * dp
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11.5f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11.5f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * dp
    }

    private var thumbShader: BitmapShader? = null
    private val thumbMatrix = Matrix()
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 340L
        interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        addUpdateListener {
            bloomProgress = it.animatedValue as Float
            invalidate()
        }
    }

    private fun staggeredProgress(index: Int): Float {
        val delay = index * 0.08f
        val local = ((bloomProgress - delay) / (1f - delay).coerceAtLeast(0.25f)).coerceIn(0f, 1f)
        // 轻弹性过冲曲线（Overshoot）
        val t = local - 1f
        return (t * t * (2.2f * t + 1.2f) + 1f).coerceIn(0f, 1.16f)
    }

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        if (previewBitmap != null && !previewBitmap.isRecycled) {
            thumbShader = BitmapShader(previewBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            thumbPaint.shader = thumbShader
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = anchorX.coerceIn(safeMargin, (w - safeMargin).coerceAtLeast(safeMargin))
        cy = anchorY.coerceIn(safeMargin + 16f * dp, (h - safeMargin - 24f * dp).coerceAtLeast(safeMargin))
    }

    private fun itemCenter(index: Int, progress: Float = 1f): Pair<Float, Float> {
        val count = actions.size.coerceAtLeast(1)
        val angleDeg = -90.0 + index * (360.0 / count)
        val rad = angleDeg * PI / 180.0
        val r = orbitRadius * progress
        return (cx + (cos(rad) * r).toFloat()) to (cy + (sin(rad) * r).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = bloomProgress.coerceIn(0f, 1.3f)
        val alphaFactor = bloomProgress.coerceIn(0f, 1f)

        // 1. 柔和暗色遮罩层
        scrimPaint.color = Color.argb((118 * alphaFactor).toInt(), 10, 12, 18)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // 2. 三主题专属轨道环：Simple 罗盘双虚线刻度 / Cute 海洋光晕环 / Usagi 手绘星芒环
        orbitPaint.color = withAlpha(if (ui.usagi) ui.line else ui.primary, (118 * alphaFactor).toInt())
        canvas.drawCircle(cx, cy, orbitRadius * p, orbitPaint)
        if (ui.simple) {
            // Simple: 精密罗盘内圈与 30° 刻度线
            orbitPaint.color = withAlpha(ui.primary, (58 * alphaFactor).toInt())
            canvas.drawCircle(cx, cy, orbitRadius * 0.72f * p, orbitPaint)
            for (deg in 0 until 360 step 30) {
                val rad = deg * PI / 180.0
                val r1 = (orbitRadius - 4f * dp) * p
                val r2 = (orbitRadius + 4f * dp) * p
                canvas.drawLine(
                    cx + (cos(rad) * r1).toFloat(),
                    cy + (sin(rad) * r1).toFloat(),
                    cx + (cos(rad) * r2).toFloat(),
                    cy + (sin(rad) * r2).toFloat(),
                    orbitPaint,
                )
            }
        } else if (ui.usagi && alphaFactor > 0.4f) {
            // Usagi: 手账四角星芒装饰
            val starAlpha = (210 * alphaFactor).toInt()
            val starR = orbitRadius * p + 38f * dp
            val starAngles = floatArrayOf(-42f, 38f, 142f, 218f)
            for (deg in starAngles) {
                val rad = deg * PI / 180.0
                val sx = cx + (cos(rad) * starR).toFloat()
                val sy = cy + (sin(rad) * starR).toFloat()
                drawSparkleStar(canvas, sx, sy, 6.5f * dp, withAlpha(ui.primary, starAlpha))
            }
        }

        // 3. 外圈 6 个带独立矢量图标的轮盘按键（28ms 顺时针交错绽放 + 磁吸 1.18× 主题光晕聚焦）
        actions.forEachIndexed { idx, action ->
            val sp = staggeredProgress(idx)
            val itemAlpha = sp.coerceIn(0f, 1f)
            val (ix, iy) = itemCenter(idx, sp)
            val isPressed = idx == pressedIndex
            val scale = (if (isPressed) 1.18f else 1f) * itemAlpha.coerceAtLeast(0.01f)
            val r = itemRadius * scale
            if (isPressed) {
                val glowColor = if (!ui.simple && !ui.usagi) ui.rose else ui.primary
                shadowPaint.color = withAlpha(glowColor, (108 * itemAlpha).toInt())
                canvas.drawCircle(ix, iy, r + 8f * dp, shadowPaint)
            }

            // 主题阴影：Usagi 硬偏移贴纸底影 / Cute 海蓝柔影 / Simple 克制哑光影
            if (ui.usagi) {
                shadowPaint.color = withAlpha(ui.line, (if (isPressed) 90 else 190 * alphaFactor.toInt()).coerceIn(0, 255))
                canvas.drawCircle(ix, iy + (if (isPressed) 1f else 3.2f) * dp, r, shadowPaint)
            } else {
                shadowPaint.color = if (!ui.simple) withAlpha(ui.primary, (46 * alphaFactor).toInt()) else Color.argb((54 * alphaFactor).toInt(), 0, 0, 0)
                canvas.drawCircle(ix, iy + 3f * dp, r + 1.5f * dp, shadowPaint)
            }

            // 圆形按钮填充与边框
            val bgColor = when {
                isPressed -> ui.primary
                action.primary -> ui.primary
                action.muted -> ui.canvas
                else -> ui.surface
            }
            val fgColor = when {
                isPressed || action.primary -> ui.onPrimary
                action.muted -> ui.muted
                else -> ui.ink
            }
            val strokeColor = when {
                isPressed || action.primary -> ui.primary
                else -> ui.line
            }
            circleFillPaint.color = withAlpha(bgColor, (250 * alphaFactor).toInt())
            canvas.drawCircle(ix, iy, r, circleFillPaint)
            circleStrokePaint.strokeWidth = if (ui.usagi) 2.1f * dp else 1.4f * dp
            circleStrokePaint.color = withAlpha(if (ui.usagi) ui.line else strokeColor, (230 * alphaFactor).toInt())
            canvas.drawCircle(ix, iy, r, circleStrokePaint)
            if (!ui.simple && !ui.usagi) {
                // Cute: 顶部瓷釉琉璃高光边
                circleStrokePaint.strokeWidth = 1.2f * dp
                circleStrokePaint.color = Color.argb((135 * alphaFactor).toInt(), 255, 255, 255)
                canvas.drawCircle(ix, iy - 0.6f * dp, r - 1.5f * dp, circleStrokePaint)
            }
            circleStrokePaint.strokeWidth = 1.4f * dp

            // 矢量图标
            drawVectorIcon(canvas, action.icon, ix, iy, 10.5f * dp * scale, withAlpha(fgColor, (255 * alphaFactor).toInt()))

            // 下方精致标签胶囊
            if (alphaFactor > 0.35f) {
                val labelAlpha = ((alphaFactor - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val labelY = iy + r + 14f * dp
                val textW = labelPaint.measureText(action.label)
                val pillRect = RectF(
                    ix - textW / 2f - 7f * dp,
                    labelY - 10.5f * dp,
                    ix + textW / 2f + 7f * dp,
                    labelY + 4f * dp,
                )
                badgeBgPaint.color = withAlpha(ui.surface, (232 * labelAlpha).toInt())
                canvas.drawRoundRect(pillRect, 99f * dp, 99f * dp, badgeBgPaint)
                circleStrokePaint.color = withAlpha(ui.line, (160 * labelAlpha).toInt())
                canvas.drawRoundRect(pillRect, 99f * dp, 99f * dp, circleStrokePaint)

                labelPaint.color = withAlpha(if (action.primary) ui.primary else ui.ink, (255 * labelAlpha).toInt())
                canvas.drawText(action.label, ix, labelY, labelPaint)
            }
        }

        // 4. 中心轮毂：图片缩略图 / 链接或内容预览
        val cr = centerRadius * (0.65f + 0.35f * p)
        shadowPaint.color = Color.argb((72 * alphaFactor).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy + 4f * dp, cr + 2f * dp, shadowPaint)

        circleFillPaint.color = withAlpha(ui.surface, (252 * alphaFactor).toInt())
        canvas.drawCircle(cx, cy, cr, circleFillPaint)

        val bmp = previewBitmap
        if (bmp != null && !bmp.isRecycled && thumbShader != null) {
            val bw = bmp.width.toFloat().coerceAtLeast(1f)
            val bh = bmp.height.toFloat().coerceAtLeast(1f)
            val scale = maxOf((cr * 2f) / bw, (cr * 2f) / bh)
            thumbMatrix.reset()
            thumbMatrix.setScale(scale, scale)
            thumbMatrix.postTranslate(cx - bw * scale / 2f, cy - bh * scale / 2f)
            thumbShader?.setLocalMatrix(thumbMatrix)
            thumbPaint.alpha = (255 * alphaFactor).toInt()
            canvas.drawCircle(cx, cy, cr - 2.5f * dp, thumbPaint)
        } else {
            // 无缩略图时在中心绘精致标题/副标题
            centerTitlePaint.color = withAlpha(ui.primary, (255 * alphaFactor).toInt())
            canvas.drawText(centerTitle.take(6), cx, cy - 2f * dp, centerTitlePaint)
            centerSubPaint.color = withAlpha(ui.muted, (235 * alphaFactor).toInt())
            canvas.drawText(centerSubtitle.take(9), cx, cy + 13f * dp, centerSubPaint)
        }

        // 中心外圈主题高光环
        circleStrokePaint.strokeWidth = 2.4f * dp
        circleStrokePaint.color = withAlpha(ui.primary, (240 * alphaFactor).toInt())
        canvas.drawCircle(cx, cy, cr, circleStrokePaint)
        circleStrokePaint.strokeWidth = 1.4f * dp

        // 中心下方内容类型小胶囊（例如「完整图片 1080×1440」或「mp.weixin.qq.com」）
        if (previewBitmap != null && !previewBitmap.isRecycled && centerSubtitle.isNotBlank() && alphaFactor > 0.5f) {
            val subText = centerSubtitle.take(16)
            val sw = centerSubPaint.measureText(subText)
            val subY = cy + cr + 13f * dp
            val subRect = RectF(cx - sw / 2f - 7f * dp, subY - 9.5f * dp, cx + sw / 2f + 7f * dp, subY + 3.5f * dp)
            badgeBgPaint.color = withAlpha(ui.primary, (235 * alphaFactor).toInt())
            canvas.drawRoundRect(subRect, 99f * dp, 99f * dp, badgeBgPaint)
            centerSubPaint.color = withAlpha(ui.onPrimary, (255 * alphaFactor).toInt())
            canvas.drawText(subText, cx, subY, centerSubPaint)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
            onDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val hit = hitTestAction(event.x, event.y)
                if (hit != pressedIndex) {
                    pressedIndex = hit
                    if (hit >= 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = hitTestAction(event.x, event.y)
                pressedIndex = -1
                invalidate()
                if (hit in actions.indices) {
                    onSelect(actions[hit])
                } else {
                    val distCenter = hypot((event.x - cx).toDouble(), (event.y - cy).toDouble()).toFloat()
                    if (distCenter <= centerRadius) {
                        // 点击中心轮毂默认触发第 0 项主操作
                        actions.firstOrNull()?.let(onSelect)
                    } else {
                        onDismiss()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTestAction(x: Float, y: Float): Int {
        val hitR = itemRadius + 14f * dp
        actions.forEachIndexed { idx, _ ->
            val (ix, iy) = itemCenter(idx, 1f)
            if (hypot((x - ix).toDouble(), (y - iy).toDouble()) <= hitR) return idx
        }
        return -1
    }

    private fun drawVectorIcon(canvas: Canvas, kind: IconKind, x: Float, y: Float, s: Float, color: Int) {
        iconPaint.color = color
        iconFillPaint.color = color
        when (kind) {
            IconKind.SPARKLE -> {
                val path = Path().apply {
                    moveTo(x, y - s)
                    quadTo(x, y, x + s, y)
                    quadTo(x, y, x, y + s)
                    quadTo(x, y, x - s, y)
                    quadTo(x, y, x, y - s)
                    close()
                }
                canvas.drawPath(path, iconFillPaint)
            }
            IconKind.IMAGE_SEARCH -> {
                // 放大镜 + 中心点
                val r = s * 0.65f
                canvas.drawCircle(x - s * 0.18f, y - s * 0.18f, r, iconPaint)
                canvas.drawLine(x + s * 0.28f, y + s * 0.28f, x + s * 0.9f, y + s * 0.9f, iconPaint)
                canvas.drawCircle(x - s * 0.18f, y - s * 0.18f, s * 0.18f, iconFillPaint)
            }
            IconKind.OCR -> {
                // 四角扫描框 + 中间文字线
                val b = s * 0.85f
                val c = s * 0.35f
                canvas.drawLine(x - b, y - b, x - b + c, y - b, iconPaint)
                canvas.drawLine(x - b, y - b, x - b, y - b + c, iconPaint)
                canvas.drawLine(x + b, y - b, x + b - c, y - b, iconPaint)
                canvas.drawLine(x + b, y - b, x + b, y - b + c, iconPaint)
                canvas.drawLine(x - b, y + b, x - b + c, y + b, iconPaint)
                canvas.drawLine(x - b, y + b, x - b, y + b - c, iconPaint)
                canvas.drawLine(x + b, y + b, x + b - c, y + b, iconPaint)
                canvas.drawLine(x + b, y + b, x + b, y + b - c, iconPaint)
                canvas.drawLine(x - s * 0.45f, y - s * 0.15f, x + s * 0.45f, y - s * 0.15f, iconPaint)
                canvas.drawLine(x - s * 0.35f, y + s * 0.22f, x + s * 0.35f, y + s * 0.22f, iconPaint)
            }
            IconKind.SAVE_IMAGE -> {
                // 下载箭头 + 底部托盘
                canvas.drawLine(x, y - s * 0.85f, x, y + s * 0.35f, iconPaint)
                canvas.drawLine(x - s * 0.42f, y - s * 0.05f, x, y + s * 0.38f, iconPaint)
                canvas.drawLine(x + s * 0.42f, y - s * 0.05f, x, y + s * 0.38f, iconPaint)
                val tray = Path().apply {
                    moveTo(x - s * 0.82f, y + s * 0.38f)
                    lineTo(x - s * 0.82f, y + s * 0.82f)
                    lineTo(x + s * 0.82f, y + s * 0.82f)
                    lineTo(x + s * 0.82f, y + s * 0.38f)
                }
                canvas.drawPath(tray, iconPaint)
            }
            IconKind.LINK_SUMMARY -> {
                // 文档 + 摘要横线
                val rect = RectF(x - s * 0.72f, y - s * 0.85f, x + s * 0.72f, y + s * 0.85f)
                canvas.drawRoundRect(rect, 2.5f * dp, 2.5f * dp, iconPaint)
                canvas.drawLine(x - s * 0.38f, y - s * 0.32f, x + s * 0.38f, y - s * 0.32f, iconPaint)
                canvas.drawLine(x - s * 0.38f, y, x + s * 0.38f, y, iconPaint)
                canvas.drawLine(x - s * 0.38f, y + s * 0.32f, x + s * 0.18f, y + s * 0.32f, iconPaint)
            }
            IconKind.COPY_LINK -> {
                // 链条双环
                canvas.save()
                canvas.rotate(-45f, x, y)
                canvas.drawRoundRect(RectF(x - s * 0.92f, y - s * 0.32f, x - s * 0.06f, y + s * 0.32f), s * 0.32f, s * 0.32f, iconPaint)
                canvas.drawRoundRect(RectF(x + s * 0.06f, y - s * 0.32f, x + s * 0.92f, y + s * 0.32f), s * 0.32f, s * 0.32f, iconPaint)
                canvas.drawLine(x - s * 0.32f, y, x + s * 0.32f, y, iconPaint)
                canvas.restore()
            }
            IconKind.VERIFY -> {
                // 盾牌 + 对勾
                val shield = Path().apply {
                    moveTo(x, y - s * 0.92f)
                    lineTo(x + s * 0.78f, y - s * 0.52f)
                    lineTo(x + s * 0.72f, y + s * 0.20f)
                    quadTo(x + s * 0.55f, y + s * 0.72f, x, y + s * 0.95f)
                    quadTo(x - s * 0.55f, y + s * 0.72f, x - s * 0.72f, y + s * 0.20f)
                    lineTo(x - s * 0.78f, y - s * 0.52f)
                    close()
                }
                canvas.drawPath(shield, iconPaint)
                canvas.drawLine(x - s * 0.28f, y + s * 0.04f, x - s * 0.04f, y + s * 0.28f, iconPaint)
                canvas.drawLine(x - s * 0.04f, y + s * 0.28f, x + s * 0.34f, y - s * 0.20f, iconPaint)
            }
            IconKind.BOOKMARK -> {
                val bm = Path().apply {
                    moveTo(x - s * 0.58f, y - s * 0.85f)
                    lineTo(x + s * 0.58f, y - s * 0.85f)
                    lineTo(x + s * 0.58f, y + s * 0.85f)
                    lineTo(x, y + s * 0.42f)
                    lineTo(x - s * 0.58f, y + s * 0.85f)
                    close()
                }
                canvas.drawPath(bm, iconPaint)
            }
            IconKind.TRANSLATE -> {
                // 地球仪经纬线
                canvas.drawCircle(x, y, s * 0.82f, iconPaint)
                canvas.drawLine(x - s * 0.82f, y, x + s * 0.82f, y, iconPaint)
                canvas.drawOval(RectF(x - s * 0.40f, y - s * 0.82f, x + s * 0.40f, y + s * 0.82f), iconPaint)
            }
            IconKind.ASK_FREE -> {
                // 对话气泡 + 三点
                val rect = RectF(x - s * 0.82f, y - s * 0.70f, x + s * 0.82f, y + s * 0.48f)
                canvas.drawRoundRect(rect, s * 0.36f, s * 0.36f, iconPaint)
                canvas.drawLine(x - s * 0.24f, y + s * 0.48f, x - s * 0.52f, y + s * 0.84f, iconPaint)
                canvas.drawCircle(x - s * 0.32f, y - s * 0.11f, 1.3f * dp, iconFillPaint)
                canvas.drawCircle(x, y - s * 0.11f, 1.3f * dp, iconFillPaint)
                canvas.drawCircle(x + s * 0.32f, y - s * 0.11f, 1.3f * dp, iconFillPaint)
            }
            IconKind.CLOSE -> {
                val d = s * 0.62f
                canvas.drawLine(x - d, y - d, x + d, y + d, iconPaint)
                canvas.drawLine(x + d, y - d, x - d, y + d, iconPaint)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
