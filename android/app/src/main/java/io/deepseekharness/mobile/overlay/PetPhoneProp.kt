package io.deepseekharness.mobile.overlay

import android.graphics.Canvas
import android.graphics.Paint

/** A vector prop held by the skinned hand; no bitmap allocation or upload on a frame. */
internal class PetPhoneProp {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    var accent = 0xff3e9da6.toInt()
    var surface = 0xffd6f2ed.toInt()

    fun draw(canvas: Canvas, skeleton: PetSkeleton, p: PetPose, w: Float, h: Float, alpha: Int) {
        if (p.phone <= .01f && p.phoneMode != 5) return
        val hand = skeleton.bones[PetJoint.WRIST_L.ordinal]
        canvas.save()
        // The phone rises out of the apron as the elbow folds towards the chest.
        canvas.translate(hand.x(.478f*w,.705f*h), hand.y(.478f*w,.705f*h) + .012f*h + (1f-p.phone)*.045f*h)
        canvas.scale(w/100f*.88f,h/100f*.88f)
        canvas.rotate(-7f + p.chest*.25f)
        fun fill(color: Int) { ink.style=Paint.Style.FILL; ink.color=color; ink.alpha=(alpha*p.phone).toInt() }
        if (p.phone > .01f) {
            fill(0xff263f61.toInt()); canvas.drawRoundRect(-7.8f,-19f,7.8f,1.5f,2.3f,2.3f,ink)
            fill(0xffa9c9e4.toInt()); canvas.drawRoundRect(-7f,-18.3f,7f,.8f,1.9f,1.9f,ink)
            fill(surface); canvas.drawRoundRect(-6.1f,-17.4f,6.1f,-.2f,1.4f,1.4f,ink)
            fill(0xff263f61.toInt()); canvas.drawRoundRect(-2.3f,-17.5f,2.3f,-16.5f,.5f,.5f,ink)
            fill(accent); ink.strokeWidth=.9f
            when (p.phoneMode) {
                1 -> { // Scan: lens and handle travel with the watching pose.
                    val scan=kotlin.math.sin(p.accentPhase*2*Math.PI).toFloat()
                    ink.style=Paint.Style.STROKE; canvas.drawCircle(-.6f,-10f+scan,3f,ink)
                    canvas.drawLine(1.6f,-7.8f+scan,3.6f,-5.8f+scan,ink)
                    ink.alpha=(alpha*p.phone*.22f).toInt();canvas.drawLine(-4.6f,-13f+scan*2f,4.6f,-13f+scan*2f,ink)
                }
                2 -> {
                    canvas.drawRoundRect(-4.2f,-14f,4.2f,-11.5f,.7f,.7f,ink)
                    ink.alpha=(alpha*p.phone*.45f).toInt(); canvas.drawRoundRect(-4.2f,-10f,1.6f,-8.8f,.5f,.5f,ink)
                    ink.alpha=(alpha*p.phone*(1f-p.phoneTap*.65f)).toInt(); ink.style=Paint.Style.STROKE
                    canvas.drawCircle(1.5f,-5.8f,1.1f+p.phoneTap*2.1f,ink)
                }
                3 -> for (i in -1..1) {
                    val breath=(.55f+.45f*kotlin.math.sin(p.accentPhase*2*Math.PI-i*.9).toFloat())
                    ink.alpha=(alpha*p.phone*breath).toInt();canvas.drawCircle(i*2.8f,-9f,.75f+.15f*breath,ink)
                }
                4 -> { canvas.drawRoundRect(-3.5f,-12f,-1.3f,-5f,.5f,.5f,ink); canvas.drawRoundRect(1.3f,-12f,3.5f,-5f,.5f,.5f,ink) }
                6 -> {
                    fill(0xffb66939.toInt()); canvas.drawRoundRect(-.8f,-13f,.8f,-7.3f,.65f,.65f,ink)
                    canvas.drawCircle(0f,-4.9f,.9f,ink)
                }
            }
            fill(0xff688da7.toInt()); canvas.drawRoundRect(-2.8f,-1.5f,2.8f,-.9f,.3f,.3f,ink)
        }
        canvas.restore()
        if (p.phoneMode == 5) {
            val palm=skeleton.bones[PetJoint.WRIST_R.ordinal]
            canvas.save(); canvas.translate(palm.x(.516f*w,.70f*h),palm.y(.516f*w,.70f*h)-.05f*h)
            canvas.scale(w/100f,h/100f)
            ink.style=Paint.Style.FILL; ink.color=0xff263f61.toInt(); ink.alpha=alpha
            canvas.drawCircle(0f,0f,3.5f,ink)
            ink.color=0xfff0d5ba.toInt(); canvas.drawRoundRect(-1.5f,-1.5f,1.5f,1.5f,.3f,.3f,ink)
            canvas.restore()
        }
    }
}
