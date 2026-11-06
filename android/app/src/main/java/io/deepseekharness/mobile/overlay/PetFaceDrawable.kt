package io.deepseekharness.mobile.overlay

import android.graphics.*

/** Vector facial acting follows the head bone. The source artwork and its blue irises stay intact. */
internal class PetFaceDrawable(private val source:Bitmap) {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val path=Path()
    private val matrix=Matrix()
    private val transform=FloatArray(9)
    private fun sample(x:Float,y:Float)=source.getPixel((source.width*x).toInt(),(source.height*y).toInt())
    private val skinTop=sample(.485f,.416f)
    private val skinMiddle=sample(.445f,.44f)
    private val skinBottom=sample(.485f,.469f)
    private val mouthSkin=LinearGradient(48.5f,41.8f,48.5f,46.8f,
        intArrayOf(skinTop,skinMiddle,skinBottom),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
    private val lidSkin=sample(.38f,.337f)
    private val lip=0xff713e42.toInt()
    private val lash=0xff392b3b.toInt()
    private fun pen(color:Int,alpha:Float=1f,stroke:Float=0f) {
        ink.shader=null;ink.color=color;ink.alpha=(255*alpha.coerceIn(0f,1f)).toInt()
        ink.style=if(stroke>0) Paint.Style.STROKE else Paint.Style.FILL;ink.strokeWidth=stroke
    }
    fun draw(c:Canvas,skeleton:PetSkeleton,p:PetPose,w:Float,h:Float,alpha:Int,leftClose:Float=0f,rightClose:Float=0f) {
        val b=skeleton.bones[PetJoint.HEAD.ordinal];val f=p.face;val opacity=alpha/255f
        transform[0]=b.cosine;transform[1]=-b.sine;transform[2]=b.dx
        transform[3]=b.sine;transform[4]=b.cosine;transform[5]=b.dy;transform[8]=1f
        matrix.setValues(transform)
        c.save();c.concat(matrix);c.scale(w/100f,h/100f)
        // A local skin material covers the baked smile before drawing the new mouth.
        // It is evaluated by Canvas, not a new frame bitmap or a changed source asset.
        pen(skinMiddle,opacity);ink.shader=mouthSkin
        c.drawOval(45.15f,41.8f,52.15f,46.65f,ink);ink.shader=null
        val x=48.45f;val y=43.45f;val half=2.45f*f.width
        val opening=f.opening.coerceIn(0f,1f)
        if(opening<.07f) {
            pen(lip,opacity,.34f)
            path.rewind();path.moveTo(x-half,y)
            path.quadTo(x,y+1.85f*f.smile,x+half,y);c.drawPath(path,ink)
        } else {
            val height=.55f+2.65f*opening
            path.rewind();path.moveTo(x-half,y-.2f)
            path.quadTo(x,y+.65f*f.smile,x+half,y-.2f)
            path.cubicTo(x+half+.15f,y+height,x-half-.15f,y+height,x-half,y-.2f);path.close()
            pen(0xff8d4751.toInt(),opacity);c.drawPath(path,ink)
            c.save();c.clipPath(path)
            pen(0xfff4a5a4.toInt(),opacity);c.drawOval(x-half,y+height*.53f,x+half,y+height*1.3f,ink)
            if(opening>.6f && f.smile>.4f) {pen(0xffffeee6.toInt(),opacity);c.drawRoundRect(x-half,y-.2f,x+half,y+.48f,.2f,.2f,ink)}
            c.restore();pen(lip,opacity,.25f);c.drawPath(path,ink)
        }
        val l=maxOf(f.squint*.28f,leftClose);val r=maxOf(f.squint*.28f,rightClose)
        if(l>.02f && leftClose<.98f) lid(c,32.9f,36.9f,43.1f,36.8f,l,opacity)
        if(r>.02f && rightClose<.98f) lid(c,52.4f,34.8f,62.3f,34.1f,r,opacity)
        // Inner brow ends rise for concern and lower for concentration.
        pen(0xff805e69.toInt(),opacity*.85f,.36f)
        val raise=f.browRaise*.7f;val tilt=f.brow*.95f
        path.rewind();path.moveTo(35.3f,33.8f-raise)
        path.quadTo(38.2f,32.8f-raise,40.8f,33.2f-raise-tilt);c.drawPath(path,ink)
        path.rewind();path.moveTo(53.4f,31.4f-raise-tilt)
        path.quadTo(56.1f,30.8f-raise,58.7f,31.7f-raise);c.drawPath(path,ink)
        if(f.blush>.01f) {
            pen(0xffef818d.toInt(),opacity*f.blush*.30f)
            c.drawOval(34.6f,43.2f,39.9f,44.6f,ink);c.drawOval(55.0f,41.5f,59.4f,42.9f,ink)
            pen(0xffda6d82.toInt(),opacity*f.blush*.6f,.22f)
            for(i in 0..2) {c.drawLine(35.8f+i,43.3f,35.5f+i,44.1f,ink);c.drawLine(56f+i,41.6f,55.7f+i,42.4f,ink)}
        }
        if(f.tear>.01f) {
            drop(c,34.3f,42.5f,1.05f,opacity*f.tear)
            drop(c,60.7f,40.4f,.85f,opacity*f.tear)
        }
        if(f.sweat>.01f) drop(c,64.6f,33.1f,1.35f,opacity*f.sweat)
        c.restore()
    }
    private fun lid(c:Canvas,left:Float,y:Float,right:Float,innerY:Float,amount:Float,opacity:Float) {
        val mid=(left+right)*.5f;val down=10.5f*amount
        path.rewind();path.moveTo(left,y)
        path.cubicTo(left+2f,y-2.9f,right-2f,innerY-2.2f,right,innerY)
        path.quadTo(mid,y-2f+down,left,y);path.close()
        pen(lidSkin,opacity);c.drawPath(path,ink)
        pen(lash,opacity,.55f);path.rewind();path.moveTo(left,y)
        path.quadTo(mid,y-2f+down,right,innerY);c.drawPath(path,ink)
    }
    private fun drop(c:Canvas,x:Float,y:Float,r:Float,opacity:Float) {
        path.rewind();path.moveTo(x,y-r*1.4f)
        path.cubicTo(x+r*1.5f,y+r*.3f,x+r,y+r*1.6f,x,y+r*1.6f)
        path.cubicTo(x-r,y+r*1.6f,x-r*1.5f,y+r*.3f,x,y-r*1.4f);path.close()
        pen(0xff6dbdde.toInt(),opacity);c.drawPath(path,ink)
        pen(0xffecfaff.toInt(),opacity*.9f,.25f);c.drawLine(x-.3f*r,y,x-.45f*r,y+.6f*r,ink)
    }
}
