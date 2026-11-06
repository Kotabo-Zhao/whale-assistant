package io.deepseekharness.mobile.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** Immutable artwork layers. Animation updates transforms, never uploads a recomposited bitmap. */
internal class CompanionPetDrawable(private val open: Bitmap, private val closed: Bitmap, private val body: Bitmap) : Drawable(), AutoCloseable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val propPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val phoneProp = PetPhoneProp()
    private val accents = PetAccentDrawable()
    private val face = PetFaceDrawable(open)
    private val handProps = PetHandProps()
    fun phonePalette(accent: Int, surface: Int) {
        phoneProp.accent = accent; phoneProp.surface = surface
        accents.accent=accent;accents.surface=surface;invalidateSelf()
        handProps.accent=accent;handProps.surface=surface
    }
    private val w = open.width.toFloat()
    private val h = open.height.toFloat()
    private val base = open.copy(Bitmap.Config.ARGB_8888, true)
    private val skeleton = PetSkeleton(w, h)
    private val skin = PetSkin(20, 22, Rect(0, 0, open.width, open.height), w, h)
    private data class Layer(val image: Bitmap, val skin: PetSkin)
    private val leftEye = eye(.29f, .445f)
    private val rightEye = eye(.518f, .66f)
    private val left = cutArm(PetJoint.ELBOW_L, floatArrayOf(.395f,.584f, .441f,.584f, .474f,.649f, .489f,.661f, .499f,.714f, .490f,.732f, .452f,.732f, .432f,.697f, .414f,.657f))
    private val right = cutArm(PetJoint.ELBOW_R, floatArrayOf(.536f,.584f, .578f,.584f, .560f,.657f, .544f,.697f, .528f,.732f, .493f,.732f, .486f,.714f, .497f,.661f, .516f,.649f))
    private var pose = PetPose()
    private var prepared: PetPose? = null
    fun setPose(next: PetPose) {
        if (next == pose) return
        val sheetChanged=(pose.sheet>.01f)!=(next.sheet>.01f) || pose.phoneMode!=next.phoneMode ||
            kotlin.math.abs(pose.phone-next.phone)>.012f || kotlin.math.abs(pose.phoneTap-next.phoneTap)>.025f ||
            pose.accent!=next.accent || kotlin.math.abs(pose.accentAlpha-next.accentAlpha)>.01f ||
            (next.accentAlpha>.01f && kotlin.math.abs(pose.accentPhase-next.accentPhase)>.004f) ||
            kotlin.math.abs(pose.eyeClose-next.eyeClose)>.015f || kotlin.math.abs(pose.wink-next.wink)>.015f ||
            pose.face!=next.face || pose.prop!=next.prop || kotlin.math.abs(pose.propAmount-next.propAmount)>.01f ||
            (next.propAmount>.01f && kotlin.math.abs(pose.propPhase-next.propPhase)>.004f)
        pose = next
        prepareGeometry()
        // The 20/30 Hz motion clock continues unchanged. Avoid submitting another
        // mesh when all movement is below one fifth of a physical display pixel.
        val scale = if (bounds.width()>0) bounds.width()/w*.90f else 1f
        if (skin.movedSinceDraw(scale,.18f) || left.skin.movedSinceDraw(scale,.18f) ||
            right.skin.movedSinceDraw(scale,.18f) || sheetChanged || pose.sheet>.01f) invalidateSelf()
    }
    var blinkAmount = 0f
        set(value) { if (field != value) { field = value.coerceIn(0f, 1f); invalidateSelf() } }

    init {
        require(open.width == closed.width && open.height == closed.height && open.width == body.width && open.height == body.height)
        val canvas = Canvas(base)
        canvas.clipRect(w * .374f, h * .583f, w * .616f, h * .745f)
        canvas.drawBitmap(body, null, Rect(0, 0, open.width, open.height), paint)
    }
    private fun eye(left: Float, right: Float): Layer {
        val region = Rect((w * left).toInt(), (h * .31f).toInt(), (w * right).toInt(), (h * .44f).toInt())
        val image = Bitmap.createBitmap(closed, region.left, region.top, region.width(), region.height())
        return Layer(image, PetSkin(5, 5, region, w, h))
    }
    private fun cutArm(joint: PetJoint, vertices: FloatArray): Layer {
        val path = android.graphics.Path()
        var i = 0
        while (i < vertices.size) {
            if (i == 0) path.moveTo(vertices[i] * w, vertices[i + 1] * h) else path.lineTo(vertices[i] * w, vertices[i + 1] * h)
            i += 2
        }
        path.close()
        val region = Rect()
        android.graphics.RectF().also { path.computeBounds(it, true); it.roundOut(region) }
        val image = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.translate(-region.left.toFloat(), -region.top.toFloat())
        canvas.clipPath(path)
        canvas.drawBitmap(open, null, Rect(0, 0, open.width, open.height), paint)
        return Layer(image, PetSkin(5, 8, region, w, h, joint))
    }
    override fun getIntrinsicWidth() = open.width
    override fun getIntrinsicHeight() = open.height
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / w, bounds.height() / h)
        canvas.scale(.90f, .90f, w * .5f, h * .60f)
        prepareGeometry()
        drawSkin(canvas, base, skin, paint)
        face.draw(canvas,skeleton,pose,w,h,paint.alpha)
        val both=maxOf(blinkAmount,pose.eyeClose,pose.face.happyEyes).coerceIn(0f,1f)
        if (both > 0f) {
            drawEye(canvas,leftEye,both)
        }
        val rightClose=maxOf(both,pose.wink).coerceIn(0f,1f)
        if(rightClose>0f) {
            drawEye(canvas,rightEye,rightClose)
        }
        if (pose.sheet > .01f) drawWorkSheet(canvas)
        handProps.draw(canvas,skeleton,pose,w,h,paint.alpha)
        phoneProp.draw(canvas,skeleton,pose,w,h,paint.alpha)
        layerPaint.alpha = paint.alpha
        drawSkin(canvas, left.image, left.skin, layerPaint)
        drawSkin(canvas, right.image, right.skin, layerPaint)
        accents.draw(canvas,skeleton,pose,w,h,paint.alpha)
        canvas.restore()
    }
    private fun drawEye(canvas:Canvas,eye:Layer,amount:Float) {
        // Composite the eyelid layer explicitly so fractional blinks retain the iris underneath.
        val checkpoint=canvas.saveLayerAlpha(0f,0f,w,h,(paint.alpha*amount).toInt())
        layerPaint.alpha=255;drawSkin(canvas,eye.image,eye.skin,layerPaint)
        canvas.restoreToCount(checkpoint)
    }
    private fun drawSkin(canvas: Canvas, image: Bitmap, skin: PetSkin, color: Paint) {
        canvas.drawBitmapMesh(image, skin.cols, skin.rows, skin.vertices, 0, null, 0, color)
        skin.recordDraw()
    }
    private fun prepareGeometry() {
        if (prepared == pose) return
        skeleton.set(pose)
        skin.update(skeleton,pose); left.skin.update(skeleton,pose); right.skin.update(skeleton,pose)
        leftEye.skin.update(skeleton,pose); rightEye.skin.update(skeleton,pose)
        prepared=pose
    }
    /** The slate sits under the writing hand and follows the torso while the wrist taps. */
    private fun drawWorkSheet(canvas: Canvas) {
        val chest = skeleton.bones[PetJoint.CHEST.ordinal]
        val anchorX = chest.x(.605f*w, .69f*h)
        val anchorY = chest.y(.605f*w, .69f*h)
        canvas.save()
        canvas.translate(anchorX, anchorY)
        canvas.scale(w / 100f, h / 100f)
        canvas.rotate(-11f + pose.chest * .35f)
        fun ink(color: Int) { propPaint.color = color; propPaint.alpha = (pose.sheet * paint.alpha).toInt() }
        ink(0xff385467.toInt()); canvas.drawRoundRect(-8f,-5f,10f,9f,1.9f,1.9f,propPaint)
        ink(accents.surface); canvas.drawRoundRect(-7.1f,-4.1f,9.1f,8.1f,1.2f,1.2f,propPaint)
        ink(accents.accent); propPaint.strokeWidth = .8f
        canvas.drawLine(-3.8f,-.8f,1.5f+4.3f*pose.accentPhase,-.8f,propPaint)
        canvas.drawLine(-3.8f,2.3f,3.4f,2.3f,propPaint)
        propPaint.alpha=(pose.sheet*paint.alpha*.45f).toInt();canvas.drawLine(-3.8f,5.2f,4.8f,5.2f,propPaint)
        ink(0xffd39d5c.toInt()); propPaint.strokeWidth = 1.4f
        canvas.drawLine(-3.8f,-5.5f,-3.8f,-3.3f,propPaint)
        canvas.drawLine(4.4f,-5.5f,4.4f,-3.3f,propPaint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; layerPaint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun close() {
        listOf(left.image, right.image, leftEye.image, rightEye.image, base, open, closed, body).forEach { if (!it.isRecycled) it.recycle() }
    }
}

internal object CompanionPetMotion {
    fun blink(elapsedMs: Long): Float {
        if (elapsedMs !in 0..279) return 0f
        return kotlin.math.sin(elapsedMs / 280.0 * Math.PI).toFloat().let { it * it }
    }
}
