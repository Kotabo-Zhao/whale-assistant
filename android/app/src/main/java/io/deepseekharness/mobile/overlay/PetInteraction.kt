package io.deepseekharness.mobile.overlay

import kotlin.math.*

/** A prop and its two hands share one frame, in chest-local artwork coordinates.
 * IK is evaluated AFTER state blending. A wrist is an oriented joint, not a sticker anchor. */
internal object PetInteraction {
    const val ASPECT = 1222f / 1287f
    fun ease(time:Long,start:Long,duration:Long):Float {
        val t=((time-start).toFloat()/duration).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }
    fun sip(time:Long):Float {
        val t=time.coerceAtLeast(0)%24000
        return ease(t,6500,1500)*(1f-ease(t,9800,1700))
    }
    fun page(time:Long):Float {
        val t=time.coerceAtLeast(0)%9000
        return if(t>=6200) 0f else ease(t,4600,1600)
    }
    fun pageHandX(turn:Float)=if(turn<.6f) .11f-.15f*turn else .02f+.09f*(turn-.6f)/.4f
    fun pageHandY(turn:Float)=if(turn<.6f) .01f-.10f*turn else -.05f+.06f*(turn-.6f)/.4f
    fun animate(action:PetAction,time:Long,p:PetPose):PetPose {
        val ms=time.coerceAtLeast(0)
        return when(action) {
            PetAction.IDLE -> {
                val drink=sip(ms)
                val head=1.5f*sin(ms*2.0*PI/9000).toFloat()-3f*drink
                // The cup lip reaches the head-local mouth; no independent bobbing of the cup.
                val a=Math.toRadians(head.toDouble());val dx=(.484f-PetJoint.HEAD.x)*ASPECT
                val dy=.443f-PetJoint.HEAD.y
                val mouthX=PetJoint.HEAD.x+(cos(a)*dx-sin(a)*dy).toFloat()/ASPECT
                val mouthY=PetJoint.HEAD.y+(sin(a)*dx+cos(a)*dy).toFloat()
                val tilt=-9f*drink;val r=Math.toRadians(tilt.toDouble())
                val targetX=mouthX-(sin(r)*.106f).toFloat()/ASPECT
                val targetY=mouthY+(cos(r)*.106f).toFloat()
                p.copy(chest=p.chest*.35f,head=head,hair=p.hair*.25f,
                    leftFin=p.leftFin*.3f,rightFin=p.rightFin*.3f,
                    tailRoot=3f*sin(ms*2.0*PI/8200).toFloat(),tailTip=4f*sin(ms*2.0*PI/8200-.7).toFloat(),
                    feet=0f,lift=.0015f*sin(ms*2.0*PI/5800).toFloat(),gaze=-.18f*drink,
                    holdX=.49f+(targetX-.49f)*drink,holdY=.695f+(targetY-.695f)*drink,holdAngle=tilt,
                    face=p.face.copy(opening=.03f,smile=.5f-.4f*drink,squint=.12f*drink),
                    eyeClose=max(p.eyeClose,((drink-.6f)/.4f).coerceIn(0f,1f)),accentAlpha=0f)
            }
            PetAction.THINK -> p.copy(chest=1.5f,head=5f+1.2f*sin(ms*2.0*PI/5500).toFloat(),
                holdX=.458f,holdY=.665f,holdAngle=-3f,pageTurn=page(ms),
                gaze=.2f*sin(ms*2.0*PI/4200).toFloat(),lift=p.lift*.35f)
            PetAction.WORK -> p.copy(chest=2f,head=5f+1.2f*sin(ms*2.0*PI/6000).toFloat(),
                holdX=.458f,holdY=.68f,holdAngle=-3f,propPhase=(ms%3600)/3600f,
                tailRoot=p.tailRoot*.25f,tailTip=p.tailTip*.25f,lift=p.lift*.25f)
            PetAction.OBSERVE,PetAction.CONTROL,PetAction.WAIT,PetAction.PAUSED,PetAction.ERROR -> {
                val lower=if(action==PetAction.WAIT || action==PetAction.PAUSED) .035f else 0f
                p.copy(holdX=.49f,holdY=.675f+lower,holdAngle=-6f,
                    head=if(action==PetAction.PAUSED) -5f else 4f,
                    prop=PetProp.NONE,propAmount=0f,
                    chest=1f,lift=p.lift*.3f,tailRoot=p.tailRoot*.4f,tailTip=p.tailTip*.4f)
            }
            PetAction.ACCEPT -> p.copy(holdX=.60f,holdY=.68f,holdAngle=8f)
            PetAction.ASK -> p.copy(holdX=.37f,holdY=.65f,holdAngle=-10f)
            PetAction.REPLY -> p.copy(holdX=.37f,holdY=.65f,holdAngle=-6f)
            PetAction.CELEBRATE -> {
                val lift=sin((ms.coerceAtMost(2200))*PI/2200).toFloat()
                p.copy(holdX=.41f-.08f*lift,holdY=.68f-.16f*lift,holdAngle=-12f*lift)
            }
            PetAction.TOUCH -> p.copy(holdX=.49f,holdY=.65f,holdAngle=0f)
            PetAction.NOTICE -> p.copy(holdX=.37f,holdY=.64f,holdAngle=-8f)
            PetAction.SETBACK -> {
                val recover=ease(ms,1300,1100)
                p.copy(holdX=.37f+.07f*recover,holdY=.53f+.15f*recover,holdAngle=12f)
            }
            PetAction.STOPPED -> p.copy(holdX=.65f,holdY=.60f,holdAngle=0f)
            else -> p
        }
    }
    fun constrained(p:PetPose)=p.phone>.001f || p.prop!=PetProp.NONE
    fun pointX(p:PetPose,x:Float,y:Float):Float {
        val r=Math.toRadians(p.holdAngle.toDouble())
        return p.holdX+(cos(r)*x*ASPECT-sin(r)*y).toFloat()/ASPECT
    }
    fun pointY(p:PetPose,x:Float,y:Float):Float {
        val r=Math.toRadians(p.holdAngle.toDouble())
        return p.holdY+(sin(r)*x*ASPECT+cos(r)*y).toFloat()
    }
    private fun contact(p:PetPose,right:Boolean,x:Float,y:Float,angle:Float=p.holdAngle):PetPose =
        reach(p,right,pointX(p,x,y),pointY(p,x,y),angle)

    /** Blend hand destinations, not Euler angles (which can wrap by 360 degrees). */
    fun blendHands(start:PetPose,end:PetPose,body:PetPose,t:Float):PetPose {
        if(t<=0f || t>=1f) return body
        fun side(p:PetPose,right:Boolean):PetPose {
            val a=handPosition(start,right);val b=handPosition(end,right)
            val angleA=if(right) start.rightShoulder+start.rightElbow+start.rightWrist else start.leftShoulder+start.leftElbow+start.leftWrist
            val angleB=if(right) end.rightShoulder+end.rightElbow+end.rightWrist else end.leftShoulder+end.leftElbow+end.leftWrist
            return reach(p,right,a.first+(b.first-a.first)*t,a.second+(b.second-a.second)*t,angleA+(angleB-angleA)*t)
        }
        return side(side(body,false),true)
    }

    fun constrain(p:PetPose):PetPose {
        val target=solve(p)
        val amount=(if(p.phone>.001f) p.phone else p.propAmount).coerceIn(0f,1f)
        if(amount>=.999f || !constrained(p)) return target
        // Release/regrip is gradual. Switching the prop enum at zero opacity must not
        // instantly replace six joint angles. Keep the primary object glued to its hand
        // while the other hand releases it or reaches to support it.
        fun approach(body:PetPose,right:Boolean):PetPose {
            val start=handPosition(p,right);val end=handPosition(target,right)
            val a=if(right) p.rightShoulder+p.rightElbow+p.rightWrist else p.leftShoulder+p.leftElbow+p.leftWrist
            val b=if(right) target.rightShoulder+target.rightElbow+target.rightWrist else target.leftShoulder+target.leftElbow+target.leftWrist
            return reach(body,right,start.first+(end.first-start.first)*amount,
                start.second+(end.second-start.second)*amount,a+(b-a)*amount)
        }
        val blended=if(amount<.001f) p else approach(approach(p,false),true)
        val right=p.phone<=.001f && (p.prop==PetProp.CLIPBOARD || p.prop==PetProp.STOP_PADDLE)
        val grip=handPosition(blended,right)
        val x=when {p.phone>.001f -> -.035f;p.prop==PetProp.CUP -> -.075f
            p.prop==PetProp.BOOK || p.prop==PetProp.NOTEBOOK -> -.025f;p.prop==PetProp.HEART_CUSHION -> -.045f;else -> 0f}
        val y=when {p.phone>.001f -> .003f;p.prop==PetProp.CUP -> -.04f
            p.prop==PetProp.BOOK || p.prop==PetProp.NOTEBOOK -> .01f;else -> 0f}
        return blended.copy(holdX=grip.first-(pointX(p,x,y)-p.holdX),holdY=grip.second-(pointY(p,x,y)-p.holdY))
    }
    private fun handPosition(p:PetPose,right:Boolean):Pair<Float,Float> {
        val s=if(right) PetJoint.SHOULDER_R else PetJoint.SHOULDER_L
        val e=if(right) PetJoint.ELBOW_R else PetJoint.ELBOW_L
        val w=if(right) PetJoint.WRIST_R else PetJoint.WRIST_L
        var x=s.x*ASPECT;var y=s.y
        fun segment(dx:Float,dy:Float,degrees:Float) {
            val a=Math.toRadians(degrees.toDouble())
            x+=(cos(a)*dx*ASPECT-sin(a)*dy).toFloat();y+=(sin(a)*dx*ASPECT+cos(a)*dy).toFloat()
        }
        val a=if(right)p.rightShoulder else p.leftShoulder
        val b=if(right)p.rightElbow else p.leftElbow
        val c=if(right)p.rightWrist else p.leftWrist
        segment(e.x-s.x,e.y-s.y,a);segment(w.x-e.x,w.y-e.y,a+b)
        segment((if(right).513f else .478f)-w.x,(if(right).702f else .705f)-w.y,a+b+c)
        return x/ASPECT to y
    }
    private fun solve(p:PetPose):PetPose = when {
        p.phone>.001f -> {
            val held=contact(p,false,-.035f,.003f,p.holdAngle-18f)
            when(p.phoneMode) {
                2 -> contact(held,true,.015f*.88f,-.058f*.88f-.028f*(1f-p.phoneTap),p.holdAngle+20f)
                4 -> reach(held,true,.66f,.57f,-125f)
                else -> contact(held,true,.045f,.008f,p.holdAngle+12f)
            }
        }
        p.prop==PetProp.CUP -> contact(contact(p,false,-.075f,-.04f,
            p.holdAngle+20f+70f*((.695f-p.holdY)/.15f).coerceIn(0f,1f)),true,.035f,.01f,p.holdAngle-12f)
        p.prop==PetProp.BOOK -> {
            val turn=sin(p.pageTurn*PI).toFloat()
            contact(contact(p,false,-.025f,.01f,p.holdAngle+12f),true,pageHandX(p.pageTurn),
                pageHandY(p.pageTurn),p.holdAngle-15f-25f*turn)
        }
        p.prop==PetProp.NOTEBOOK -> {
            val write=sin(p.propPhase*PI*8).toFloat()
            val tick=(p.propPhase*3600).toLong()
            val rest=ease(tick,2450,350)*(1f-ease(tick,3200,400))
            // Pencil tip is 0.027 below the grip in the same frame as its renderer.
            contact(contact(p,false,-.025f,.01f,p.holdAngle+12f),true,.074f+.009f*write*(1f-rest),
                -.045f-.027f-.025f*rest,p.holdAngle)
        }
        p.prop==PetProp.HEART_CUSHION -> contact(contact(p,false,-.045f,0f,15f),true,.045f,0f,-15f)
        p.prop==PetProp.TISSUE -> contact(p,false,0f,0f,65f)
        p.prop==PetProp.CLIPBOARD || p.prop==PetProp.STOP_PADDLE -> contact(p,true,0f,0f)
        p.prop!=PetProp.NONE -> contact(p,false,0f,0f)
        else -> p
    }

    /** Solve shoulder/elbow to an oriented wrist, then close the wrist onto the actual grip.
     * Targets beyond reach are clamped; no bone length changes or elastic forearm stretching. */
    fun reach(p:PetPose,right:Boolean,x:Float,y:Float,angle:Float):PetPose {
        val shoulder=if(right) PetJoint.SHOULDER_R else PetJoint.SHOULDER_L
        val elbow=if(right) PetJoint.ELBOW_R else PetJoint.ELBOW_L
        val wrist=if(right) PetJoint.WRIST_R else PetJoint.WRIST_L
        val hx=if(right) .513f else .478f;val hy=if(right) .702f else .705f
        val radians=Math.toRadians(angle.toDouble())
        val gx=(hx-wrist.x)*ASPECT;val gy=hy-wrist.y
        val dx=(x-shoulder.x)*ASPECT-(cos(radians)*gx-sin(radians)*gy)
        val dy=y-shoulder.y-(sin(radians)*gx+cos(radians)*gy)
        val ax=(elbow.x-shoulder.x)*ASPECT;val ay=elbow.y-shoulder.y
        val bx=(wrist.x-elbow.x)*ASPECT;val by=wrist.y-elbow.y
        val l1=hypot(ax,ay).toDouble();val l2=hypot(bx,by).toDouble()
        val distance=hypot(dx,dy).coerceIn(abs(l1-l2)+.0001,l1+l2-.0001)
        val bend=acos(((distance*distance-l1*l1-l2*l2)/(2*l1*l2)).coerceIn(-1.0,1.0))*(if(right) -1 else 1)
        val start=atan2(dy,dx)-atan2(l2*sin(bend),l1+l2*cos(bend))
        val a=Math.toDegrees(start-atan2(ay.toDouble(),ax.toDouble())).toFloat()
        val b=Math.toDegrees(bend-(atan2(by.toDouble(),bx.toDouble())-atan2(ay.toDouble(),ax.toDouble()))).toFloat()
        val c=angle-a-b
        return if(right) p.copy(rightShoulder=a,rightElbow=b,rightWrist=c)
            else p.copy(leftShoulder=a,leftElbow=b,leftWrist=c)
    }
}
