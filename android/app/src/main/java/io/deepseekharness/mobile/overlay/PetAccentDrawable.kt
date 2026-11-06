package io.deepseekharness.mobile.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small hand-drawn motifs, contained in the existing avatar window. No particles or bitmap churn. */
internal class PetAccentDrawable {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val path=Path()
    var accent=0xff3e9da6.toInt()
    var surface=0xffd6f2ed.toInt()
    private fun pen(color:Int,opacity:Float,stroke:Boolean=false,width:Float=.8f) {
        ink.color=color;ink.alpha=(255*opacity.coerceIn(0f,1f)).toInt()
        ink.style=if(stroke) Paint.Style.STROKE else Paint.Style.FILL;ink.strokeWidth=width
    }
    private fun star(c:Canvas,x:Float,y:Float,r:Float) {
        path.rewind();path.moveTo(x,y-r);path.quadTo(x+.24f*r,y-.24f*r,x+r,y)
        path.quadTo(x+.24f*r,y+.24f*r,x,y+r);path.quadTo(x-.24f*r,y+.24f*r,x-r,y)
        path.quadTo(x-.24f*r,y-.24f*r,x,y-r);path.close();c.drawPath(path,ink)
    }
    fun draw(c:Canvas,skeleton:PetSkeleton,p:PetPose,w:Float,h:Float,alpha:Int) {
        if(p.accent==PetAccent.NONE || p.accentAlpha<=.01f) return
        val a=p.accentAlpha*alpha/255f;val t=p.accentPhase
        val breathe=sin(t*2*PI).toFloat()
        c.save();c.scale(w/100f,h/100f)
        // The upper-right pocket is clear of the face, wrists and tail throughout the rig's range.
        val x=75f;val y=15.5f-1.2f*breathe
        fun bubble() {
            pen(accent,a*.13f);c.drawOval(x-8.8f,y-7.3f,x+8.8f,y+8.7f,ink)
            pen(surface,a);c.drawRoundRect(x-7f,y-6f,x+7f,y+6f,4f,4f,ink)
            pen(accent,a*.8f,true,.65f);c.drawRoundRect(x-7f,y-6f,x+7f,y+6f,4f,4f,ink)
            c.drawLine(x-3.2f,y+5.8f,x-4.4f,y+8f,ink)
        }
        when(p.accent) {
            PetAccent.NONE -> Unit
            PetAccent.PEARL -> for(i in 0..2) {
                val q=(t+i*.27f)%1f
                pen(accent,a*(1f-q)*.65f,true,.45f)
                c.drawCircle(22f+i*5f,70f-q*19f,1.1f+i*.32f,ink)
            }
            PetAccent.CHECK -> {
                bubble();pen(accent,a,true,1.15f)
                path.rewind();path.moveTo(x-3.5f,y);path.lineTo(x-.8f,y+2.5f);path.lineTo(x+3.9f,y-2.7f);c.drawPath(path,ink)
                pen(accent,a*.65f);star(c,x+10f,y-7f,1.5f)
            }
            PetAccent.THOUGHT -> {
                bubble();pen(accent,a*.65f);c.drawCircle(x-8.4f,y+9f,1.1f,ink);c.drawCircle(x-11f,y+11.3f,.65f,ink)
                for(i in -1..1) {val wave=(.55f+.45f*sin(t*2*PI-i*.9).toFloat());pen(accent,a*wave);c.drawCircle(x+i*3.3f,y,.8f+.3f*wave,ink)}
            }
            PetAccent.INK -> {
                if(p.prop==PetProp.NOTEBOOK) {c.restore();return}
                // A small pencil makes the worksheet's purpose visible at phone-overlay size.
                val wrist=skeleton.bones[PetJoint.WRIST_R.ordinal]
                val px=wrist.x(.513f*w,.702f*h)/w*100f;val py=wrist.y(.513f*w,.702f*h)/h*100f
                c.save();c.translate(px,py);c.rotate(-18f+8f*breathe)
                pen(accent,a);c.drawRoundRect(-.7f,-7f,.7f,2f,.5f,.5f,ink)
                pen(0xffe9bc81.toInt(),a);path.rewind();path.moveTo(-.7f,2f);path.lineTo(.7f,2f);path.lineTo(0f,4f);path.close();c.drawPath(path,ink)
                c.restore()
            }
            PetAccent.QUESTION -> {
                bubble();pen(accent,a,true,1.1f);path.rewind();path.moveTo(x-2.4f,y-2.2f)
                path.cubicTo(x-2.4f,y-6f,x+5f,y-5f,x+2.2f,y-.8f);path.quadTo(x,y+.5f,x,y+1.3f);c.drawPath(path,ink)
                pen(accent,a);c.drawCircle(x,y+3.7f,.65f,ink)
            }
            PetAccent.REPLY -> {
                bubble();pen(accent,a,true,.85f);c.drawLine(x-3.7f,y-1.5f,x+3.7f,y-1.5f,ink);c.drawLine(x-3.7f,y+1.8f,x+1.5f,y+1.8f,ink)
            }
            PetAccent.STARS -> {
                for(i in 0..4) {
                    val angle=PI*(1.08+i*.21);val radius=30f+3f*sin(t*PI).toFloat()
                    pen(if(i%2==0) accent else 0xffe8b879.toInt(),a*(.7f+.3f*sin(t*2*PI+i).toFloat()))
                    star(c,49f+cos(angle).toFloat()*radius,38f+sin(angle).toFloat()*radius,1.7f+(i%2)*.65f)
                }
            }
            PetAccent.CLOUD -> {
                pen(accent,a*.18f);c.drawRoundRect(x-6f,y-2f,x+6f,y+3f,2.5f,2.5f,ink)
                c.drawCircle(x-2f,y-2f,3f,ink);c.drawCircle(x+2f,y-1f,3.6f,ink)
                pen(accent,a*.6f,true,.7f);c.drawLine(x-3f,y+6f,x-3.6f,y+8f,ink);c.drawLine(x+2.5f,y+5.5f,x+1.8f,y+7.5f,ink)
            }
            PetAccent.HEART -> {
                c.save();c.translate(x,y);c.scale(.75f+.15f*breathe,.75f+.15f*breathe)
                pen(accent,a*.15f);c.drawCircle(0f,0f,8f,ink);pen(accent,a)
                path.rewind();path.moveTo(0f,4.5f);path.cubicTo(-9f,-1f,-4f,-7f,0f,-2.8f)
                path.cubicTo(4f,-7f,9f,-1f,0f,4.5f);path.close();c.drawPath(path,ink);c.restore()
            }
            PetAccent.LETTER -> {
                bubble();pen(accent,a,true,.75f);c.drawRoundRect(x-4.4f,y-3f,x+4.4f,y+3f,1f,1f,ink)
                path.rewind();path.moveTo(x-4f,y-2.6f);path.lineTo(x,y+.6f);path.lineTo(x+4f,y-2.6f);c.drawPath(path,ink)
                pen(0xffe4ae68.toInt(),a);star(c,x+8.8f,y-7f,1.8f)
            }
            PetAccent.TRAIL -> {
                val left=p.gaze>=0;val start=if(left) 13f else 81f
                pen(accent,a*.55f,true,.6f)
                for(i in 0..2) c.drawLine(start,54f+i*5f,start+4f+2f*sin(t*2*PI+i).toFloat(),54f+i*5f,ink)
            }
            PetAccent.SCAN -> {
                pen(accent,a*.7f,true,.6f)
                c.drawArc(72f,12f,83f,23f,210f+40f*breathe,210f,false,ink)
                pen(accent,a*.6f);c.drawCircle(78f,18f,1.1f,ink)
            }
            PetAccent.TAP -> {pen(accent,a*.6f);star(c,71f,59f,1.2f+p.phoneTap)}
            PetAccent.ORBIT -> {
                for(i in 0..2) {val angle=t*2*PI+i*2*PI/3;pen(accent,a*(.45f+.15f*i));c.drawCircle(x+cos(angle).toFloat()*4.2f,y+sin(angle).toFloat()*2f,.8f,ink)}
            }
            PetAccent.PAUSE -> {
                bubble();pen(accent,a);c.drawRoundRect(x-3f,y-3.3f,x-1.2f,y+3.3f,.5f,.5f,ink);c.drawRoundRect(x+1.2f,y-3.3f,x+3f,y+3.3f,.5f,.5f,ink)
            }
            PetAccent.STOP -> {bubble();pen(accent,a);c.drawRoundRect(x-3f,y-3f,x+3f,y+3f,.8f,.8f,ink)}
            PetAccent.ALERT -> {
                bubble();pen(0xffc68c50.toInt(),a,true,.8f);path.rewind();path.moveTo(x,y-4f);path.lineTo(x+4.4f,y+3.6f);path.lineTo(x-4.4f,y+3.6f);path.close();c.drawPath(path,ink)
                c.drawLine(x,y-1.8f,x,y+.5f,ink);pen(0xffc68c50.toInt(),a);c.drawCircle(x,y+2f,.4f,ink)
            }
        }
        c.restore()
    }
}
