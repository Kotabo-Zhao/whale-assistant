package io.deepseekharness.mobile.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** Two aligned frames; only the eye regions change during a blink. */
internal class CompanionPetDrawable(private val open: Bitmap, private val closed: Bitmap, private val body: Bitmap) : Drawable(), AutoCloseable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val composite = Bitmap.createBitmap(open.width, open.height, Bitmap.Config.ARGB_8888)
    private val compositeCanvas = Canvas(composite)
    private val mesh = FloatArray((MESH_WIDTH + 1) * (MESH_HEIGHT + 1) * 2)
    var bend = 0f
        set(value) { field = value.coerceIn(-1f, 1f); invalidateSelf() }
    var leftArm = 0f
        set(value) { field = value.coerceIn(-25f, 25f); invalidateSelf() }
    var rightArm = 0f
        set(value) { field = value.coerceIn(-25f, 25f); invalidateSelf() }
    var blinkAmount = 0f
        set(value) { val next = value.coerceIn(0f, 1f); if (field != next) { field = next; invalidateSelf() } }
    init { require(open.width == closed.width && open.height == closed.height && open.width == body.width && open.height == body.height) }
    override fun getIntrinsicWidth() = open.width
    override fun getIntrinsicHeight() = open.height
    override fun draw(canvas: Canvas) {
        compositeCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        layerPaint.alpha = 255
        compositeCanvas.drawBitmap(open, 0f, 0f, layerPaint)
        val w = open.width.toFloat(); val h = open.height.toFloat()
        // The inpainted apron supplies pixels behind two independently rotating forearms.
        compositeCanvas.save()
        compositeCanvas.clipRect(w * 0.374f, h * 0.583f, w * 0.616f, h * 0.745f)
        compositeCanvas.drawBitmap(body, 0f, 0f, layerPaint)
        compositeCanvas.restore()
        fun arm(points: Array<Pair<Float, Float>>, pivotX: Float, angle: Float) {
            val path = android.graphics.Path()
            points.forEachIndexed { index, (x, y) -> if (index == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
            path.close()
            compositeCanvas.save()
            compositeCanvas.rotate(angle, pivotX * w, 0.59f * h)
            compositeCanvas.clipPath(path)
            compositeCanvas.drawBitmap(open, 0f, 0f, layerPaint)
            compositeCanvas.restore()
        }
        arm(arrayOf(0.395f to 0.584f, 0.441f to 0.584f, 0.474f to 0.649f, 0.489f to 0.661f,
            0.499f to 0.714f, 0.490f to 0.732f, 0.452f to 0.732f, 0.432f to 0.697f, 0.414f to 0.657f), 0.414f, leftArm)
        arm(arrayOf(0.536f to 0.584f, 0.578f to 0.584f, 0.560f to 0.657f, 0.544f to 0.697f,
            0.528f to 0.732f, 0.493f to 0.732f, 0.486f to 0.714f, 0.497f to 0.661f, 0.516f to 0.649f), 0.558f, rightArm)
        if (blinkAmount > 0f) {
            layerPaint.alpha = (255 * blinkAmount).toInt()
            for ((left, right) in listOf(0.29f to 0.46f, 0.50f to 0.66f)) {
                val region = Rect((open.width * left).toInt(), (open.height * 0.31f).toInt(), (open.width * right).toInt(), (open.height * 0.44f).toInt())
                compositeCanvas.drawBitmap(closed, region, region, layerPaint)
            }
        }
        var index = 0
        for (y in 0..MESH_HEIGHT) {
            val fy = y.toFloat() / MESH_HEIGHT
            for (x in 0..MESH_WIDTH) {
                val fx = x.toFloat() / MESH_WIDTH
                // The upper body bends relative to the feet, while the lower silhouette lags behind.
                val head = kotlin.math.sin(fy * Math.PI).toFloat() * (1f - fy)
                val tail = kotlin.math.sin(fy * Math.PI).toFloat() * fy
                val horizontal = bend * (head * 0.028f - tail * 0.018f)
                mesh[index++] = bounds.left + bounds.width() * (fx + horizontal)
                mesh[index++] = bounds.top + bounds.height() * fy
            }
        }
        canvas.drawBitmapMesh(composite, MESH_WIDTH, MESH_HEIGHT, mesh, 0, null, 0, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun close() { if (!composite.isRecycled) composite.recycle(); if (!open.isRecycled) open.recycle(); if (!closed.isRecycled) closed.recycle(); if (!body.isRecycled) body.recycle() }
    companion object { private const val MESH_WIDTH = 8; private const val MESH_HEIGHT = 12 }
}

internal object CompanionPetMotion {
    fun sway(progress: Float): Float = kotlin.math.sin(progress.coerceIn(0f, 1f) * 2f * Math.PI).toFloat()
    data class Pose(val rotation: Float, val lift: Float, val scaleX: Float, val scaleY: Float)
    data class Arms(val left: Float, val right: Float)
    fun arms(elapsedMs: Long, thinking: Float, replyElapsedMs: Long): Arms {
        val wave = kotlin.math.sin(elapsedMs.coerceAtLeast(0) * 2.0 * Math.PI / 2800.0).toFloat()
        val t = thinking.coerceIn(0f, 1f)
        val reply = if (replyElapsedMs in 0..1799) kotlin.math.sin(replyElapsedMs / 1800.0 * Math.PI).toFloat() else 0f
        return Arms(-1.5f * wave - t * (9f + 3f * wave) - reply * 13f,
            1.5f * wave + t * 2f + reply * 13f)
    }
    fun blink(elapsedMs: Long): Float {
        if (elapsedMs !in 0..279) return 0f
        return kotlin.math.sin(elapsedMs / 280.0 * Math.PI).toFloat().let { it * it }
    }
    fun pose(elapsedMs: Long, thinking: Float, replyElapsedMs: Long): Pose {
        val time = elapsedMs.coerceAtLeast(0).toDouble()
        val t = thinking.coerceIn(0f, 1f)
        val sway = kotlin.math.sin(time * 2.0 * Math.PI / 4400.0).toFloat()
        val breathe = kotlin.math.sin(time * 2.0 * Math.PI / 3100.0).toFloat()
        val ponder = kotlin.math.sin(time * 2.0 * Math.PI / 2300.0).toFloat()
        val cheer = if (replyElapsedMs in 0..1799) {
            val p = replyElapsedMs / 1800.0
            (kotlin.math.sin(Math.PI * p) * kotlin.math.abs(kotlin.math.sin(2.0 * Math.PI * p))).toFloat()
        } else 0f
        return Pose(sway * (2.2f + t * 1.3f) + ponder * t * 2.5f, 1.8f + breathe * 1.1f + t * (1f + ponder * 1.2f) + cheer * 7f,
            0.98f + breathe * 0.008f + cheer * 0.035f, 0.98f - breathe * 0.008f + cheer * 0.045f)
    }
    fun tapScale(elapsedMs: Long): Float = if (elapsedMs in 0..299) 1f - 0.10f * kotlin.math.sin(elapsedMs / 300f * Math.PI).toFloat() else 1f
}
