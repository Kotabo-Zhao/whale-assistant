package io.deepseekharness.mobile.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 拖宠即问「方向盘式一体精密轮盘」（Steering-Wheel Ring Dial）。
 *
 * - 紧凑一体环形结构（总直径仅 ~216dp，比离散圆球紧凑 30%）；
 * - 外环按操作数均分为无缝弧形扇区，图标与文字精致内嵌于每个扇区内，彻底消除杂乱外挂胶囊；
 * - 中心轮毂展示完整图片实时预览或内容徽章，与外环之间留有精密凹槽与微光倒角；
 * - 支持单拇指极坐标滑动选区与每扇区边界触觉刻度反馈。
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
    private val outerRadius = 108f * dp
    private val ringInnerRadius = 46f * dp
    private val centerRadius = 41f * dp
    private val safeMargin = outerRadius + 16f * dp

    private var cx = anchorX
    private var cy = anchorY
    private var bloomProgress = 0f
    private var pressedIndex = -1

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.85f * dp
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.2f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 8.8f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var thumbShader: BitmapShader? = null
    private val thumbMatrix = Matrix()
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorPath = Path()
    private val outerRect = RectF()
    private val innerRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 260L
        interpolator = OvershootInterpolator(1.08f)
        addUpdateListener {
            bloomProgress = (it.animatedValue as Float).coerceAtLeast(0f)
            invalidate()
        }
    }

    init {
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
        cy = anchorY.coerceIn(safeMargin + 12f * dp, (h - safeMargin - 16f * dp).coerceAtLeast(safeMargin))
    }

    private fun buildSectorPath(startDeg: Float, sweepDeg: Float, innerR: Float, outerR: Float) {
        sectorPath.reset()
        outerRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        innerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        sectorPath.arcTo(outerRect, startDeg, sweepDeg, true)
        sectorPath.arcTo(innerRect, startDeg + sweepDeg, -sweepDeg, false)
        sectorPath.close()
    }

    private fun blendColor(base: Int, tint: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(base) * inv + Color.red(tint) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(base) * inv + Color.green(tint) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(base) * inv + Color.blue(tint) * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scaleP = (0.72f + 0.28f * bloomProgress).coerceIn(0.1f, 1.15f)
        val alphaFactor = bloomProgress.coerceIn(0f, 1f)
        val count = actions.size.coerceAtLeast(1)
        val sweep = 360f / count
        val gapDeg = 1.6f

        val curOuterR = outerRadius * scaleP
        val curInnerR = ringInnerRadius * scaleP
        val curCenterR = centerRadius * scaleP

        // 1. 柔和暗色氛围遮罩
        scrimPaint.color = Color.argb((110 * alphaFactor).toInt(), 8, 11, 18)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // 2. 方向盘一体外环统一弥散阴影
        shadowPaint.color = Color.argb((68 * alphaFactor).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy + 4f * dp, curOuterR + 3f * dp, shadowPaint)

        // 3. 方向盘暗色底托环（让扇区之间的极细辐条呈现精密机械间隙感）
        sectorFillPaint.color = withAlpha(ui.canvas, (218 * alphaFactor).toInt())
        canvas.drawCircle(cx, cy, curOuterR + 1.2f * dp, sectorFillPaint)

        // 4. 6 等分无缝弧形扇区（图标 + 紧凑文字内嵌于每个扇区内）
        val midR = (curInnerR + curOuterR) * 0.505f
        actions.forEachIndexed { idx, action ->
            val centerDeg = -90f + idx * sweep
            val startDeg = centerDeg - sweep / 2f + gapDeg / 2f
            val arcSweep = (sweep - gapDeg).coerceAtLeast(6f)
            val isPressed = idx == pressedIndex
            val sectorOuter = if (isPressed) curOuterR + 4.5f * dp else curOuterR

            buildSectorPath(startDeg, arcSweep, curInnerR, sectorOuter)

            val fillBase = when {
                isPressed -> ui.primary
                action.primary -> blendColor(ui.surface, ui.primary, if (ui.dark) 0.26f else 0.14f)
                action.muted -> ui.soft
                else -> ui.surface
            }
            sectorFillPaint.color = withAlpha(fillBase, (246 * alphaFactor).toInt())
            canvas.drawPath(sectorPath, sectorFillPaint)

            // 选中态外缘高光弧线
            if (isPressed || action.primary) {
                rimStrokePaint.strokeWidth = (if (isPressed) 2.2f else 1.4f) * dp
                rimStrokePaint.color = withAlpha(ui.primary, ((if (isPressed) 255 else 155) * alphaFactor).toInt())
                outerRect.set(cx - sectorOuter, cy - sectorOuter, cx + sectorOuter, cy + sectorOuter)
                canvas.drawArc(outerRect, startDeg + 2f, (arcSweep - 4f).coerceAtLeast(2f), false, rimStrokePaint)
            }

            // 扇区中心点坐标：图标居上、紧凑文字紧贴下方，完全收入环内
            val rad = centerDeg * PI / 180.0
            val sx = cx + (cos(rad) * midR).toFloat()
            val sy = cy + (sin(rad) * midR).toFloat()

            val fgColor = when {
                isPressed -> ui.onPrimary
                action.primary -> ui.accent
                action.muted -> ui.muted
                else -> ui.ink
            }
            drawVectorIcon(canvas, action.icon, sx, sy - 6.5f * dp, 8.6f * dp * (if (isPressed) 1.08f else 1f), withAlpha(fgColor, (255 * alphaFactor).toInt()))

            labelPaint.color = withAlpha(fgColor, (245 * alphaFactor).toInt())
            canvas.drawText(action.label.take(4), sx, sy + 13.5f * dp, labelPaint)
        }

        // 5. 外圈与内圈精密微光倒角细环（方向盘质感核心）
        rimStrokePaint.strokeWidth = 1.0f * dp
        rimStrokePaint.color = Color.argb((if (ui.dark) 38 else 72 * alphaFactor.toInt()).coerceIn(0, 90), 255, 255, 255)
        canvas.drawCircle(cx, cy, curOuterR, rimStrokePaint)

        spokePaint.color = withAlpha(ui.line, (150 * alphaFactor).toInt())
        canvas.drawCircle(cx, cy, curInnerR, spokePaint)

        // 6. 中心轮毂（图片预览 / 标题 + 凹槽高光）
        shadowPaint.color = Color.argb((74 * alphaFactor).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy + 2f * dp, curCenterR + 1.5f * dp, shadowPaint)

        sectorFillPaint.color = withAlpha(ui.surface, (254 * alphaFactor).toInt())
        canvas.drawCircle(cx, cy, curCenterR, sectorFillPaint)

        val bmp = previewBitmap
        if (bmp != null && !bmp.isRecycled && thumbShader != null) {
            val bw = bmp.width.toFloat().coerceAtLeast(1f)
            val bh = bmp.height.toFloat().coerceAtLeast(1f)
            val s = maxOf((curCenterR * 2f) / bw, (curCenterR * 2f) / bh)
            thumbMatrix.reset()
            thumbMatrix.setScale(s, s)
            thumbMatrix.postTranslate(cx - bw * s / 2f, cy - bh * s / 2f)
            thumbShader?.setLocalMatrix(thumbMatrix)
            thumbPaint.alpha = (255 * alphaFactor).toInt()
            canvas.drawCircle(cx, cy, curCenterR - 1.8f * dp, thumbPaint)

            // 将尺寸小标精致内嵌在中心轮毂底缘内，不再和下方扇区打架
            if (centerSubtitle.isNotBlank() && alphaFactor > 0.5f) {
                val subText = centerSubtitle.take(12)
                val sw = centerSubPaint.measureText(subText)
                val pillY = cy + curCenterR - 7.5f * dp
                val subRect = RectF(cx - sw / 2f - 5.5f * dp, pillY - 7.5f * dp, cx + sw / 2f + 5.5f * dp, pillY + 2.8f * dp)
                badgeBgPaint.color = Color.argb((188 * alphaFactor).toInt(), 12, 16, 24)
                canvas.drawRoundRect(subRect, 99f * dp, 99f * dp, badgeBgPaint)
                centerSubPaint.color = Color.argb((245 * alphaFactor).toInt(), 255, 255, 255)
                canvas.drawText(subText, cx, pillY, centerSubPaint)
            }
        } else {
            centerTitlePaint.color = withAlpha(ui.accent, (255 * alphaFactor).toInt())
            canvas.drawText(centerTitle.take(6), cx, cy - 1.5f * dp, centerTitlePaint)
            centerSubPaint.color = withAlpha(ui.muted, (235 * alphaFactor).toInt())
            canvas.drawText(centerSubtitle.take(9), cx, cy + 12f * dp, centerSubPaint)
        }

        // 中心轮毂极细主题光环
        rimStrokePaint.strokeWidth = 1.6f * dp
        rimStrokePaint.color = withAlpha(ui.primary, (215 * alphaFactor).toInt())
        canvas.drawCircle(cx, cy, curCenterR, rimStrokePaint)
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

    /** 极坐标扇区命中：手指在环带内任意滑动即可像转方向盘一样顺滑切选扇区。 */
    private fun hitTestAction(x: Float, y: Float): Int {
        val count = actions.size
        if (count == 0) return -1
        val dist = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
        if (dist < centerRadius || dist > outerRadius + 24f * dp) return -1
        val sweep = 360.0 / count
        var deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        // 将 0 号扇区中心（-90°）左移半格作为 0° 起点
        deg = (deg + 90.0 + sweep / 2.0) % 360.0
        if (deg < 0.0) deg += 360.0
        return (deg / sweep).toInt().coerceIn(0, count - 1)
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

    private fun drawSparkleStar(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        iconFillPaint.color = color
        val inner = radius * 0.28f
        val path = Path().apply {
            moveTo(x, y - radius)
            lineTo(x + inner, y - inner)
            lineTo(x + radius, y)
            lineTo(x + inner, y + inner)
            lineTo(x, y + radius)
            lineTo(x - inner, y + inner)
            lineTo(x - radius, y)
            lineTo(x - inner, y - inner)
            close()
        }
        canvas.drawPath(path, iconFillPaint)
    }
}
