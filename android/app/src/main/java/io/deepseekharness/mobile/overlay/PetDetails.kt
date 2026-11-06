package io.deepseekharness.mobile.overlay

import kotlin.math.PI
import kotlin.math.sin

/** Facial controls are continuous so expressions follow the same interrupted blend as the rig. */
internal data class PetFacePose(
    val smile:Float=.45f, val opening:Float=.05f, val width:Float=1f,
    val brow:Float=0f, val browRaise:Float=0f, val squint:Float=0f,
    val happyEyes:Float=0f, val blush:Float=.12f, val sweat:Float=0f, val tear:Float=0f,
) {
    fun mix(b:PetFacePose,t:Float):PetFacePose {
        fun m(a:Float,z:Float)=a+(z-a)*t
        return PetFacePose(m(smile,b.smile),m(opening,b.opening),m(width,b.width),m(brow,b.brow),
            m(browRaise,b.browRaise),m(squint,b.squint),m(happyEyes,b.happyEyes),m(blush,b.blush),m(sweat,b.sweat),m(tear,b.tear))
    }
}

internal enum class PetProp { NONE, CUP, CLIPBOARD, BOOK, NOTEBOOK, QUESTION_CARD, REPLY_CARD, STAR_WAND,
    TISSUE, HEART_CUSHION, ENVELOPE, MAGNIFIER, STYLUS, HOURGLASS, STOP_PADDLE, WRENCH }

/** Props are hand-held objects; bubbles remain secondary cues, never substitutes for facial acting. */
internal object PetDetails {
    fun enrich(action:PetAction,time:Long,p:PetPose):PetPose {
        val ms=time.coerceAtLeast(0)
        val beat=sin(ms*2.0*PI/1100).toFloat()
        val face=when(action) {
            PetAction.IDLE -> PetFacePose(smile=.5f,opening=.03f,blush=.16f)
            PetAction.ACCEPT -> PetFacePose(smile=.85f,opening=.45f,browRaise=.4f,blush=.3f)
            PetAction.THINK -> PetFacePose(smile=-.2f,opening=0f,width=.62f,brow=.55f,browRaise=.5f,squint=.16f)
            PetAction.WORK -> PetFacePose(smile=.05f,opening=0f,width=.8f,brow=-.65f,squint=.38f)
            PetAction.ASK -> PetFacePose(smile=0f,opening=.62f,width=.52f,brow=.65f,browRaise=.8f)
            PetAction.REPLY -> PetFacePose(smile=.65f,opening=.35f+.15f*beat,browRaise=.25f,blush=.22f)
            PetAction.CELEBRATE -> PetFacePose(smile=1f,opening=.9f,width=1.15f,happyEyes=1f,blush=.7f,browRaise=.5f)
            PetAction.SETBACK -> {
                val recovery=((ms-1300)/1300f).coerceIn(0f,1f)
                PetFacePose(smile=-.95f+.9f*recovery,opening=0f,brow=.95f*(1f-recovery),
                    browRaise=.3f,squint=.12f,tear=.55f*(1f-recovery),blush=.05f)
            }
            PetAction.TOUCH -> PetFacePose(smile=.95f,opening=.24f,blush=.95f)
            PetAction.NOTICE -> PetFacePose(smile=.85f,opening=.4f,browRaise=.55f,blush=.45f)
            PetAction.DRAG -> PetFacePose(smile=0f,opening=.7f,width=.6f,browRaise=.9f,sweat=.25f)
            PetAction.OBSERVE -> PetFacePose(smile=.05f,opening=0f,width=.75f,brow=-.3f,browRaise=.25f,squint=.2f)
            PetAction.CONTROL -> PetFacePose(smile=.1f,opening=0f,width=.85f,brow=-.85f,squint=.45f)
            PetAction.WAIT -> PetFacePose(smile=.2f,opening=.02f,browRaise=.05f,squint=.12f)
            PetAction.PAUSED -> PetFacePose(smile=0f,opening=0f,brow=-.45f,squint=.2f)
            PetAction.STOPPED -> PetFacePose(smile=.05f,opening=0f,brow=-.65f,squint=.28f)
            PetAction.ERROR -> PetFacePose(smile=-.7f,opening=0f,width=.8f,brow=.85f,browRaise=.5f,sweat=.85f)
        }
        val prop=when(action) {
            PetAction.IDLE -> PetProp.CUP
            PetAction.ACCEPT -> PetProp.CLIPBOARD
            PetAction.THINK -> PetProp.BOOK
            PetAction.WORK -> PetProp.NOTEBOOK
            PetAction.ASK -> PetProp.QUESTION_CARD
            PetAction.REPLY -> PetProp.REPLY_CARD
            PetAction.CELEBRATE -> PetProp.STAR_WAND
            PetAction.SETBACK -> PetProp.TISSUE
            PetAction.TOUCH -> PetProp.HEART_CUSHION
            PetAction.NOTICE -> PetProp.ENVELOPE
            PetAction.OBSERVE -> PetProp.MAGNIFIER
            PetAction.CONTROL -> PetProp.STYLUS
            PetAction.WAIT -> PetProp.HOURGLASS
            PetAction.STOPPED -> PetProp.STOP_PADDLE
            PetAction.ERROR -> PetProp.WRENCH
            PetAction.DRAG,PetAction.PAUSED -> PetProp.NONE
        }
        var result=p.copy(face=face,prop=prop,propAmount=if(prop==PetProp.NONE) 0f else 1f,
            propPhase=(ms%3600)/3600f,
            leftElbow=if(action==PetAction.IDLE) 28f+2f*beat else p.leftElbow,
            // The notebook is attached to the supporting wrist, replacing the old torso plaque.
            sheet=0f,mouth=0f,eyeClose=0f)
        result=when(action) {
            PetAction.WORK -> hand(hand(result,false,.47f,.68f),true,.54f+.007f*beat,.625f+.003f*beat)
            PetAction.OBSERVE -> hand(result,true,.68f,.68f)
            PetAction.CONTROL -> hand(result,true,.60f-.006f*p.phoneTap,.71f-.006f*p.phoneTap)
            PetAction.WAIT -> hand(result,true,.65f,.71f)
            PetAction.ERROR -> hand(result,true,.65f,.72f)
            else -> result
        }
        return result
    }
    /** Two-bone reach keeps a pen over its page and other objects clear of the face and phone. */
    private fun hand(p:PetPose,right:Boolean,x:Float,y:Float):PetPose {
        val aspect=1222.0/1287.0
        val shoulder=if(right) PetJoint.SHOULDER_R else PetJoint.SHOULDER_L
        val elbow=if(right) PetJoint.ELBOW_R else PetJoint.ELBOW_L
        val hx=if(right) .513 else .478;val hy=if(right) .702 else .705
        val ax=(elbow.x-shoulder.x)*aspect;val ay=(elbow.y-shoulder.y).toDouble()
        val bx=(hx-elbow.x)*aspect;val by=hy-elbow.y
        val dx=(x-shoulder.x)*aspect;val dy=(y-shoulder.y).toDouble()
        val l1=kotlin.math.hypot(ax,ay);val l2=kotlin.math.hypot(bx,by)
        val bend=kotlin.math.acos(((dx*dx+dy*dy-l1*l1-l2*l2)/(2*l1*l2)).coerceIn(-1.0,1.0))*(if(right) 1 else -1)
        val start=kotlin.math.atan2(dy,dx)-kotlin.math.atan2(l2*sin(bend),l1+l2*kotlin.math.cos(bend))
        val shoulderAngle=Math.toDegrees(start-kotlin.math.atan2(ay,ax)).toFloat()
        val elbowAngle=Math.toDegrees(bend-(kotlin.math.atan2(by,bx)-kotlin.math.atan2(ay,ax))).toFloat()
        return if(right) p.copy(rightShoulder=shoulderAngle,rightElbow=elbowAngle,rightWrist=0f)
            else p.copy(leftShoulder=shoulderAngle,leftElbow=elbowAngle,leftWrist=0f)
    }
}
