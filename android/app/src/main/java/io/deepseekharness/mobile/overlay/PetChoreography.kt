package io.deepseekharness.mobile.overlay

import kotlin.math.PI
import kotlin.math.sin

/** Secondary acting shares the character clock: deterministic, bounded and frozen by reduced motion. */
internal object PetChoreography {
    private fun wave(ms:Long,period:Long)=sin(ms*2.0*PI/period).toFloat()
    private fun swell(ms:Long,start:Long,duration:Long):Float =
        if(ms<=start || ms>=start+duration) 0f else sin((ms-start)*PI/duration).toFloat().let{it*it}
    private fun fade(ms:Long,start:Long,duration:Long):Float {
        val t=((ms-start).toFloat()/duration).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }
    fun finish(action:PetAction,time:Long,p:PetPose):PetPose {
        val ms=time.coerceAtLeast(0)
        val period=when(action) {
            PetAction.IDLE -> 12000L; PetAction.THINK -> 5200L; PetAction.WORK -> 3600L
            PetAction.ASK -> 5400L; PetAction.OBSERVE -> 3400L; PetAction.CONTROL -> 1900L
            PetAction.WAIT -> 4600L; PetAction.PAUSED,PetAction.STOPPED,PetAction.ERROR -> 6000L
            PetAction.DRAG -> 900L; else -> 2400L
        }
        val phase=(ms%period).toFloat()/period
        fun accent(kind:PetAccent,alpha:Float,body:PetPose=p)=body.copy(accent=kind,accentPhase=phase,accentAlpha=alpha.coerceIn(0f,1f))
        return when(action) {
            PetAction.IDLE -> {
                val stretch=swell(ms%12000,7200,3100)
                accent(PetAccent.PEARL,.45f*stretch,p.copy(chest=p.chest*.48f,head=p.head*.55f-4f*stretch,
                    leftFin=p.leftFin*.65f+8f*stretch,rightFin=p.rightFin*.65f-8f*stretch,
                    lift=p.lift*.65f,eyeClose=.7f*swell(ms%12000,8000,600)))
            }
            PetAction.ACCEPT -> accent(PetAccent.CHECK,.9f*swell(ms,300,1250))
            PetAction.THINK -> accent(PetAccent.THOUGHT,.76f,p.copy(eyeClose=.5f*swell(ms%5200,3600,480)))
            PetAction.WORK -> {
                val reread=swell(ms%7200,5400,1400)
                accent(PetAccent.INK,.72f,p.copy(head=p.head-8f*reread,rightWrist=p.rightWrist*(1f-reread),
                    eyeClose=.45f*swell(ms%7200,5600,370)))
            }
            PetAction.ASK -> accent(PetAccent.QUESTION,.8f,p.copy(head=p.head*.8f,leftWrist=12f+7f*wave(ms,3600)))
            PetAction.REPLY -> accent(PetAccent.REPLY,.86f*swell(ms,120,2100))
            PetAction.CELEBRATE -> accent(PetAccent.STARS,.95f*swell(ms,180,1820),p.copy(eyeClose=.75f*swell(ms,250,420)))
            PetAction.TOUCH -> accent(PetAccent.HEART,.85f*swell(ms,80,900),p.copy(wink=swell(ms,180,450)))
            PetAction.SETBACK -> {
                val recover=fade(ms,1100,1300)
                accent(PetAccent.CLOUD,.65f*(1f-fade(ms,1800,800)),p.copy(head=p.head*(1f-.8f*recover)+5f*wave(ms,480)*swell(ms,80,900),
                    chest=p.chest*(1f-.7f*recover),lift=p.lift*(1f-recover),mouth=-.65f*(1f-recover),eyeClose=0f))
            }
            PetAction.NOTICE -> accent(PetAccent.LETTER,.82f*swell(ms,140,3100))
            PetAction.DRAG -> accent(PetAccent.TRAIL,.55f*kotlin.math.abs(p.gaze))
            PetAction.OBSERVE -> accent(PetAccent.SCAN,.5f,p.copy(head=8f+3f*wave(ms,3400),leftWrist=5f+3f*wave(ms,3400)))
            PetAction.CONTROL -> {
                val beat=swell(ms%1900,160,390)+swell(ms%1900,810,390)
                accent(PetAccent.TAP,.5f*beat,p.copy(rightElbow=43f+13f*beat,rightWrist=-12f*beat,phoneTap=beat))
            }
            PetAction.WAIT -> accent(PetAccent.ORBIT,.66f,p.copy(leftElbow=-39f,head=1f+2f*wave(ms,4600),
                rightElbow=25f,tailRoot=p.tailRoot*.65f,tailTip=p.tailTip*.65f))
            PetAction.PAUSED -> accent(PetAccent.PAUSE,.78f,p.copy(head=-10f,chest=-3f,tailRoot=p.tailRoot*.3f,tailTip=p.tailTip*.3f))
            PetAction.STOPPED -> accent(PetAccent.STOP,.74f,p.copy(eyeClose=.25f*swell(ms%6000,4200,450)))
            PetAction.ERROR -> accent(PetAccent.ALERT,.82f,p.copy(head=-12f+4f*wave(ms,520)*(1f-fade(ms,600,900)),
                rightShoulder=-12f,rightElbow=-45f,mouth=-.4f,eyeClose=.15f*swell(ms%6000,4000,450)))
        }
    }
}
