package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import io.deepseekharness.mobile.R
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A static sticker from the same artwork as the animated pet. No frame scheduling. */
internal class PetPortrait(context: Context, private val ui: PetUi, icon: Int) : View(context) {
    private val memory = icon == R.drawable.ic_whale_nav_memory
    private val task = icon == R.drawable.ic_pet_assign || icon == R.drawable.ic_whale_nav_tasks
    private val frame = if (memory) R.drawable.whale_maid_blink_v2 else R.drawable.whale_maid_open_v2
    private val seal = ui.palette.getDrawable(if (memory) R.drawable.ic_pet_heart else R.drawable.ic_pet_star)?.mutate()
    private val fallback = ui.palette.getDrawable(R.drawable.ic_pet_whale)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val target = RectF()
    private var face: Bitmap? = null
    private val mask = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        mask.reset(); mask.addRoundRect(0f, 0f, w*.95f, h*.96f, w*.25f, w*.25f, Path.Direction.CW)
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val cached = frames.get(frame)
        if (cached != null) { setFace(cached); return }
        val owner = WeakReference(this)
        val resource = frame
        val appResources = context.applicationContext.resources
        reader.execute {
            val image = frames.get(resource) ?: BitmapFactory.decodeResource(appResources, resource,
                BitmapFactory.Options().apply { inSampleSize = 4; inScaled = false })?.also { frames.put(resource, it) }
            if (image != null) main.post { owner.get()?.takeIf { it.isAttachedToWindow }?.setFace(image) }
        }
    }

    private fun setFace(bitmap: Bitmap) {
        face = bitmap
        source.set((bitmap.width * .10f).toInt(), 0, (bitmap.width * .85f).toInt(), (bitmap.height * .70f).toInt())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = if (memory) ui.rose else if (task) ui.honey else ui.soft
        canvas.drawRoundRect(0f, h*.08f, w*.94f, h*.97f, w*.32f, w*.32f, paint)
        paint.color = ui.line; canvas.drawCircle(w*.90f, h*.15f, w*.035f, paint)
        paint.color = ui.surface; canvas.drawCircle(w*.10f, h*.22f, w*.045f, paint)
        target.set(w*.03f, h*.035f, w*.94f, h*.95f)
        val bitmap = face
        if (bitmap != null) {
            paint.color = android.graphics.Color.WHITE
            val checkpoint = canvas.save(); canvas.clipPath(mask)
            canvas.drawBitmap(bitmap, source, target, paint); canvas.restoreToCount(checkpoint)
        } else fallback?.let { it.setBounds(width/6, height/6, width*5/6, height*5/6); it.draw(canvas) }
        if (task || memory) {
            paint.color = ui.surface
            canvas.drawCircle(w*.84f, h*.82f, w*.16f, paint)
            seal?.let {
                it.setBounds((w*.715f).toInt(),(h*.69f).toInt(),(w*.97f).toInt(),(h*.95f).toInt())
                it.draw(canvas)
            }
        }
    }

    private companion object {
        val frames = LruCache<Int, Bitmap>(2)
        val main = Handler(Looper.getMainLooper())
        val reader = ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue()) { work ->
            Thread(work, "pet-stickers").apply { isDaemon = true }
        }
    }
}
