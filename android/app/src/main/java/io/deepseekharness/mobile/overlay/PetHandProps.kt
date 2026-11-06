package io.deepseekharness.mobile.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

/** Reusable, outlined hand props. Grip anchors move with the same wrists as the artwork. */
internal class PetHandProps {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val path=Path()
    var accent=0xff3e9da6.toInt()
    var surface=0xffd6f2ed.toInt()
    private val outline=0xff344b67.toInt()
    private val paper=0xffffeed8.toInt()
    private val gold=0xffedbd72.toInt()
    private var opacity=1f
    private fun pen(color:Int,stroke:Float=0f,alpha:Float=1f) {
        ink.color=color;ink.alpha=(255*opacity*alpha).toInt().coerceIn(0,255)
        ink.style=if(stroke>0) Paint.Style.STROKE else Paint.Style.FILL;ink.strokeWidth=stroke
    }
    private fun panel(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int,round:Float=1.1f) {
        pen(color);c.drawRoundRect(l,t,r,b,round,round,ink)
        pen(outline,.55f);c.drawRoundRect(l,t,r,b,round,round,ink)
    }
    private fun line(c:Canvas,x:Float,y:Float,x2:Float,y2:Float,color:Int=outline,width:Float=.55f) {
        pen(color,width);c.drawLine(x,y,x2,y2,ink)
    }
    fun draw(c:Canvas,skeleton:PetSkeleton,p:PetPose,w:Float,h:Float,alpha:Int) {
        if(p.prop==PetProp.NONE || p.propAmount<=.01f) return
        opacity=p.propAmount*alpha/255f
        val right=when(p.prop) {
            PetProp.CLIPBOARD,PetProp.MAGNIFIER,PetProp.STYLUS,PetProp.HOURGLASS,PetProp.STOP_PADDLE,PetProp.WRENCH -> true
            else -> false
        }
        fun grip(rightHand:Boolean,draw:()->Unit) {
            val b=skeleton.bones[if(rightHand) PetJoint.WRIST_R.ordinal else PetJoint.WRIST_L.ordinal]
            val x=if(rightHand) .513f*w else .478f*w;val y=if(rightHand) .702f*h else .705f*h
            c.save();c.translate(b.x(x,y),b.y(x,y))
            c.rotate(Math.toDegrees(kotlin.math.atan2(b.sine.toDouble(),b.cosine.toDouble())).toFloat())
            c.scale(w/100f,h/100f)
            draw();c.restore()
        }
        fun held(draw:()->Unit) {
            if(!PetInteraction.constrained(p)) { grip(right,draw);return }
            val chest=skeleton.bones[PetJoint.CHEST.ordinal]
            c.save();c.translate(chest.x(p.holdX*w,p.holdY*h),chest.y(p.holdX*w,p.holdY*h))
            c.rotate(p.chest+p.holdAngle);c.scale(w/100f,h/100f)
            draw();c.restore()
        }
        held {
            when(p.prop) {
                PetProp.CUP -> {
                    pen(outline,1.25f);c.drawOval(-10f,-8f,-4f,-1.5f,ink)
                    panel(c,-6f,-10f,6f,2f,surface,2f)
                    pen(paper);c.drawOval(-5.2f,-11.2f,5.2f,-8f,ink)
                    pen(0xffa97554.toInt());c.drawOval(-4.5f,-10.6f,4.5f,-8.7f,ink)
                    pen(accent);c.drawOval(-3f,-5f,2.5f,-1.8f,ink);path.rewind();path.moveTo(2f,-3f);path.lineTo(4f,-5f);path.lineTo(4f,-1.5f);path.close();c.drawPath(path,ink)
                    pen(paper,.45f,.7f);val drift=sin(p.propPhase*2*PI).toFloat()
                    for(i in -1..1) {path.rewind();path.moveTo(i*2.4f,-13f);path.cubicTo(i*2.4f+1f+drift,-14f,i*2.4f-1f,-15f,i*2.4f+drift,-16f);c.drawPath(path,ink)}
                }
                PetProp.CLIPBOARD -> {
                    panel(c,-5f,-16f,9f,2.5f,accent)
                    panel(c,-3.6f,-14f,7.6f,1f,paper,.4f);panel(c,-1.5f,-17f,5.5f,-13.5f,gold,.6f)
                    for(i in 0..2) {line(c,0f,-9f+i*3.6f,5.5f,-9f+i*3.6f,accent);line(c,-2f,-9f+i*3.6f,-1f,-8.4f+i*3.6f,accent)}
                }
                PetProp.BOOK,PetProp.NOTEBOOK -> {
                    panel(c,-4f,-13f,13f,3f,accent)
                    pen(paper);path.rewind();path.moveTo(-3f,-12f);path.quadTo(1f,-14f,4.5f,-11.5f)
                    path.quadTo(8f,-14f,12f,-12f);path.lineTo(12f,1.8f);path.quadTo(8f,0f,4.5f,2f)
                    path.quadTo(1f,0f,-3f,1.8f);path.close();c.drawPath(path,ink)
                    line(c,4.5f,-11.5f,4.5f,2f,0xffbe9c7b.toInt(),.45f)
                    for(i in 0..2) {line(c,-1.5f,-8f+i*2.7f,2.8f,-8.5f+i*2.7f,accent,.4f);line(c,6.3f,-8.5f+i*2.7f,10.5f,-8f+i*2.7f,accent,.4f)}
                    pen(gold);path.rewind();path.moveTo(8f,-13f);path.lineTo(10f,-13f);path.lineTo(10f,-8.5f);path.lineTo(9f,-9.5f);path.lineTo(8f,-8.5f);path.close();c.drawPath(path,ink)
                    if(p.prop==PetProp.BOOK && p.pageTurn>.001f) {
                        val turn=p.pageTurn
                        val edge=12f-15f*turn;val curl=3f*sin(turn*PI).toFloat()
                        path.rewind();path.moveTo(4.5f,-11.5f);path.quadTo(edge+curl,-15f-curl,edge,-12f)
                        path.lineTo(edge,1.8f);path.quadTo(edge+curl,-curl,4.5f,2f);path.close()
                        pen(0xfffff6e8.toInt());c.drawPath(path,ink);pen(0xffbe9c7b.toInt(),.35f);c.drawPath(path,ink)
                    }
                }
                PetProp.QUESTION_CARD,PetProp.REPLY_CARD -> {
                    panel(c,-5f,-13.5f,10f,1.5f,paper)
                    panel(c,-5f,-13.5f,10f,-10.5f,accent,.8f)
                    if(p.prop==PetProp.QUESTION_CARD) {
                        pen(accent,.9f);path.rewind();path.moveTo(.1f,-7.3f);path.cubicTo(.1f,-10.2f,6f,-10.2f,5.3f,-7.3f)
                        path.quadTo(4.8f,-5.9f,2.8f,-4.8f);path.lineTo(2.8f,-4f);c.drawPath(path,ink)
                        pen(accent);c.drawCircle(2.8f,-1.8f,.6f,ink)
                    } else {
                        for(i in 0..2) line(c,-2f,-7.2f+i*2.8f,if(i==2) 4f else 7f,-7.2f+i*2.8f,accent,.6f)
                    }
                }
                PetProp.STAR_WAND -> {
                    line(c,0f,1f,0f,-15f,gold,1.4f)
                    path.rewind();for(i in 0..9) {val angle=-PI/2+i*PI/5;val radius=if(i%2==0) 5.3f else 2.5f
                        val x=kotlin.math.cos(angle).toFloat()*radius;val y=-17f+sin(angle).toFloat()*radius
                        if(i==0) path.moveTo(x,y) else path.lineTo(x,y)};path.close()
                    pen(gold);c.drawPath(path,ink);pen(outline,.45f);c.drawPath(path,ink)
                    pen(paper);c.drawCircle(-1.4f,-18.5f,.85f,ink)
                    pen(accent,.8f);path.rewind();path.moveTo(0f,-10f);path.cubicTo(7f,-7f,-6f,-4f,2f,0f);c.drawPath(path,ink)
                }
                PetProp.TISSUE -> {
                    pen(paper);path.rewind();path.moveTo(-5f,-8f);path.lineTo(-1.5f,-10f);path.lineTo(3.8f,-7f)
                    path.lineTo(5f,-1f);path.lineTo(.2f,2f);path.lineTo(-5f,-1f);path.close();c.drawPath(path,ink)
                    pen(0xff90bbc8.toInt(),.5f);c.drawPath(path,ink);line(c,-1.5f,-8f,.5f,.5f,0xffaec7cc.toInt(),.4f)
                }
                PetProp.HEART_CUSHION -> {
                    path.rewind();path.moveTo(0f,2f);path.cubicTo(-13f,-5f,-6f,-14f,0f,-8f)
                    path.cubicTo(6f,-14f,13f,-5f,0f,2f);path.close();pen(0xffe59ba8.toInt());c.drawPath(path,ink)
                    pen(0xff945774.toInt(),.6f);c.drawPath(path,ink);pen(0xffffd8d7.toInt(),.8f);c.drawArc(-5.5f,-9f,-1f,-4.5f,200f,105f,false,ink)
                }
                PetProp.ENVELOPE -> {
                    panel(c,-7f,-9f,9f,1.5f,paper)
                    line(c,-6.5f,1f,1f,-4.5f,0xffb58e72.toInt(),.5f);line(c,8.5f,1f,1f,-4.5f,0xffb58e72.toInt(),.5f)
                    pen(0xfff6dcba.toInt());path.rewind();path.moveTo(-6.5f,-8.5f);path.lineTo(8.5f,-8.5f);path.lineTo(1f,-2.3f);path.close();c.drawPath(path,ink)
                    pen(accent);c.drawCircle(1f,-3.2f,1.7f,ink);pen(paper,.5f);c.drawLine(.3f,-3.2f,1f,-2.5f,ink);c.drawLine(1f,-2.5f,2f,-3.8f,ink)
                }
                PetProp.MAGNIFIER -> {
                    line(c,0f,1.5f,0f,-7.3f,outline,2.2f);line(c,0f,-1f,0f,-6f,gold,1.1f)
                    pen(0xff9bdef1.toInt(),alpha=.55f);c.drawCircle(0f,-12.3f,5.1f,ink)
                    pen(outline,1.1f);c.drawCircle(0f,-12.3f,5.3f,ink);pen(gold,.45f);c.drawCircle(0f,-12.3f,4.4f,ink)
                    pen(0xffeffcff.toInt(),.9f);c.drawArc(-3.2f,-15.6f,3.2f,-9.2f,205f,80f,false,ink)
                }
                PetProp.STYLUS -> {
                    c.save();c.rotate(-20f)
                    panel(c,-.7f,-10.5f,.7f,1f,gold,.45f)
                    pen(paper);path.rewind();path.moveTo(-.7f,-10.5f);path.lineTo(.7f,-10.5f);path.lineTo(0f,-13f);path.close();c.drawPath(path,ink)
                    pen(outline);c.drawCircle(0f,-12.7f,.3f,ink);c.restore()
                }
                PetProp.HOURGLASS -> {
                    panel(c,-4.6f,-13.7f,4.6f,-11.9f,gold,.4f);panel(c,-4.6f,.2f,4.6f,2f,gold,.4f)
                    pen(0xffb8e8ef.toInt(),alpha=.7f);path.rewind();path.moveTo(-3.7f,-11.7f);path.lineTo(3.7f,-11.7f)
                    path.cubicTo(3.7f,-8f,.4f,-7f,.4f,-6f);path.cubicTo(.4f,-4.5f,3.7f,-3f,3.7f,0f)
                    path.lineTo(-3.7f,0f);path.cubicTo(-3.7f,-3f,-.4f,-4.5f,-.4f,-6f);path.cubicTo(-.4f,-7f,-3.7f,-8f,-3.7f,-11.7f);path.close();c.drawPath(path,ink)
                    pen(outline,.4f);c.drawPath(path,ink)
                    pen(gold);path.rewind();path.moveTo(-2.7f,-9.8f);path.lineTo(2.7f,-9.8f);path.lineTo(0f,-6.7f);path.close();c.drawPath(path,ink)
                    path.rewind();path.moveTo(-3f,-.5f);path.lineTo(3f,-.5f);path.lineTo(0f,-3f);path.close();c.drawPath(path,ink)
                    line(c,0f,-6.7f,0f,-3.5f,gold,.35f)
                }
                PetProp.STOP_PADDLE -> {
                    line(c,0f,2f,0f,-10f,outline,1.6f)
                    panel(c,-5f,-17f,5f,-7f,0xffc88785.toInt(),2f)
                    panel(c,-2.3f,-14.3f,2.3f,-9.7f,paper,.5f)
                }
                PetProp.WRENCH -> {
                    line(c,0f,1f,0f,-9f,outline,2.7f);line(c,0f,1f,0f,-9f,0xffc2d4df.toInt(),1.7f)
                    pen(outline,2.2f);c.drawArc(-3.7f,-15.3f,3.7f,-7.9f,-5f,255f,false,ink)
                    pen(0xffd4e3ea.toInt(),1.2f);c.drawArc(-3.7f,-15.3f,3.7f,-7.9f,-5f,255f,false,ink)
                    pen(accent);c.drawCircle(0f,1f,.7f,ink)
                }
                PetProp.NONE -> Unit
            }
        }
        if(p.prop==PetProp.NOTEBOOK) grip(true) {pencil(c)}
    }
    private fun pencil(c:Canvas) {
        panel(c,-.7f,-11f,.7f,.5f,gold,.45f)
        pen(paper);path.rewind();path.moveTo(-.7f,.5f);path.lineTo(.7f,.5f);path.lineTo(0f,3f);path.close();c.drawPath(path,ink)
        pen(outline);c.drawCircle(0f,2.7f,.3f,ink)
        panel(c,-.8f,-12.2f,.8f,-10.3f,accent,.3f)
    }
}
