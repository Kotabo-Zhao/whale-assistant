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
        val settle=PetInteraction.ease(ms,450,900)
        val face=when(action) {
            PetAction.IDLE -> PetFacePose(smile=.5f,opening=.03f,blush=.16f)
            PetAction.ACCEPT -> PetFacePose(smile=.85f-.2f*settle,opening=.35f*(1f-settle),browRaise=.4f-.3f*settle,blush=.3f)
            PetAction.THINK -> PetFacePose(smile=.05f,opening=0f,width=.78f,brow=.25f,browRaise=.2f,squint=.12f)
            PetAction.WORK -> PetFacePose(smile=.1f,opening=0f,width=.85f,brow=-.45f,squint=.25f)
            PetAction.ASK -> PetFacePose(smile=.1f*settle,opening=.42f*(1f-settle),width=.72f,brow=.55f,browRaise=.65f-.35f*settle)
            PetAction.REPLY -> PetFacePose(smile=.65f,opening=(.28f+.16f*beat)*(1f-PetInteraction.ease(ms,1400,650)),browRaise=.25f,blush=.22f)
            PetAction.CELEBRATE -> PetFacePose(smile=1f,opening=.65f-.3f*settle,width=1.08f,
                happyEyes=PetInteraction.ease(ms,100,150)*(1f-PetInteraction.ease(ms,1500,350)),blush=.6f,browRaise=.4f)
            PetAction.SETBACK -> {
                val recovery=((ms-1300)/1300f).coerceIn(0f,1f)
                PetFacePose(smile=-.95f+.9f*recovery,opening=0f,brow=.95f*(1f-recovery),
                    browRaise=.3f,squint=.12f,tear=.55f*(1f-recovery),blush=.05f)
            }
            PetAction.TOUCH -> PetFacePose(smile=.95f,opening=.24f,blush=.95f)
            PetAction.NOTICE -> PetFacePose(smile=.75f,opening=.32f*(1f-settle),browRaise=.5f-.3f*settle,blush=.3f)
            PetAction.DRAG -> PetFacePose(smile=0f,opening=.7f,width=.6f,browRaise=.9f,sweat=.25f)
            PetAction.OBSERVE -> PetFacePose(smile=.05f,opening=0f,width=.75f,brow=-.3f,browRaise=.25f,squint=.2f)
            PetAction.CONTROL -> PetFacePose(smile=.1f,opening=0f,width=.85f,brow=-.85f,squint=.45f)
            PetAction.WAIT -> PetFacePose(smile=.2f,opening=.02f,browRaise=.05f,squint=.12f)
            PetAction.PAUSED -> PetFacePose(smile=0f,opening=0f,brow=-.45f,squint=.2f)
            PetAction.STOPPED -> PetFacePose(smile=.05f,opening=0f,brow=-.65f,squint=.28f)
            PetAction.ERROR -> PetFacePose(smile=-.5f,opening=0f,width=.8f,brow=.65f,browRaise=.4f,sweat=.7f-.35f*settle)
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
        return p.copy(face=face,prop=prop,propAmount=if(prop==PetProp.NONE) 0f else 1f,
            propPhase=(ms%3600)/3600f,
            leftElbow=if(action==PetAction.IDLE) 28f+2f*beat else p.leftElbow,
            // The notebook is attached to the supporting wrist, replacing the old torso plaque.
            sheet=0f,mouth=0f)
    }
}
