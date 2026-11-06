package io.deepseekharness.mobile.companion

import android.graphics.*
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.overlay.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PetMotionRevisionDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    @Test fun animatorSettingStopsAllFramesAndRestartsWithoutScreenCycle() {
        val prefs=CompanionStore(app);val reduced=prefs.reduceMotion
        val store=io.deepseekharness.mobile.runtime.RuntimeStore(app);val enabled=store.overlayBallEnabled()
        val wasRunning=OverlayBallService.isRunning
        val original=Settings.Global.getString(app.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE)
        fun scale(value:String?) {
            val command=if(value==null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $value"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
        }
        fun scheduled():Boolean {
            var result=false
            inst.runOnMainSync { PetControlDeviceUi.service()?.let { result=PetControlDeviceUi.field(it,"companionFrame")!=null } }
            return result
        }
        try {
            scale("1");prefs.reduceMotion=false;store.setOverlayBallEnabled(true)
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            OverlayBallService.start(app)
            PetControlDeviceUi.await("visible frames") { OverlayBallService.updateForHostAppForeground(false);scheduled() }
            scale("0")
            PetControlDeviceUi.await("system reduced motion must remove the callback, not poll") { !scheduled() }
            Thread.sleep(1200);assertFalse(scheduled())
            scale("1")
            PetControlDeviceUi.await("animation should resume without relaunch or screen cycling") { scheduled() }
            prefs.reduceMotion=true
            inst.runOnMainSync { OverlayBallService.applyCompanionMotionPreference() }
            assertFalse(scheduled())
            prefs.reduceMotion=false
            inst.runOnMainSync { OverlayBallService.applyCompanionMotionPreference() }
            PetControlDeviceUi.await("app reduced motion should be reversible") { scheduled() }
            OverlayBallService.updateForHostAppForeground(true)
            PetControlDeviceUi.await("hidden pet must have no callback") { !scheduled() }
            OverlayBallService.updateForHostAppForeground(false)
            PetControlDeviceUi.await("visible pet resumes") { scheduled() }
        } finally {
            scale(original);prefs.reduceMotion=reduced;store.setOverlayBallEnabled(enabled)
            if(!wasRunning) OverlayBallService.stop(app)
            else {
                OverlayBallService.updateForHostAppForeground(io.deepseekharness.mobile.AppForeground.isForeground())
                inst.runOnMainSync { OverlayBallService.applyCompanionMotionPreference() }
            }
        }
    }

    @Test fun exportActualRendererWithItsAdaptiveClock() {
        val out=File(app.getExternalFilesDir(null),"pet-motion-revision").apply{mkdirs()}
        val pets=Array(3){CompanionPetDrawable(app.resources,320,java.util.concurrent.Executor { it.run() })}
        val frame=Bitmap.createBitmap(960,420,Bitmap.Config.ARGB_8888);val canvas=Canvas(frame)
        val actions=arrayOf(PetAction.IDLE,PetAction.THINK,PetAction.CONTROL)
        val titles=arrayOf("举杯 · 喝水 · 放下","托书 · 翻页 · 收手","持机 · 触屏 · 停顿")
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xffdceef2.toInt();textSize=21f}
        val next=LongArray(3)
        val transitions=Array(3){PetActing()}
        val movie=InstrumentationRegistry.getArguments().getString("movie")=="true"
        val frames=File(out,"frames").apply{if(movie)mkdirs()}
        try {
            pets.forEachIndexed {i,p ->p.setBounds(i*320+22,65,i*320+298,341)}
            val count=if(movie) 600 else 1
            repeat(count) {n ->
                val now=if(movie) n*1000L/30 else 9000L
                if(n==480) {
                    for(i in 0..2) {transitions[i].resume(now,pets[i].currentPose);next[i]=now}
                    transitions[0].react(PetAction.ACCEPT,now);transitions[1].react(PetAction.REPLY,now)
                    transitions[2].control("paused",now)
                }
                canvas.drawColor(0xff142536.toInt())
                for(i in 0..2) {
                    val pet=pets[i]
                    if(now>=next[i]) {
                        val pose=if(n>=480) transitions[i].sample(now) else PetActing.pose(actions[i],now+if(i==0)16000 else 0)
                        pet.prepareForExport(pose.action,pose.elapsedMs)
                        next[i]=now+minOf(pet.nextFrameDelayMs,if(n>=480)transitions[i].untilStateChange(now) else Long.MAX_VALUE)
                    }
                    val title=if(n<480) titles[i] else arrayOf("持物中接单","阅读后回复","操控转暂停")[i]
                    pet.draw(canvas);canvas.drawText(title,i*320+25f,38f,paint)
                }
                paint.textSize=16f
                canvas.drawText("原生绘制回放 · 使用桌宠实际刷新策略 · ${now/1000f}s",25f,389f,paint)
                paint.textSize=21f
                if(movie) File(frames,"frame-%04d.png".format(n)).outputStream().use{frame.compress(Bitmap.CompressFormat.PNG,100,it)}
                if(n==0 || n==240 || n==270 || n==330 || n==490 || n==510) File(out,"pose-$n.png").outputStream().use{frame.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            val board=Bitmap.createBitmap(1440,900,Bitmap.Config.ARGB_8888);val c=Canvas(board);c.drawColor(0xffedf3f5.toInt())
            val times=arrayOf(longArrayOf(16000,17000,17500,18000,18800,19700),longArrayOf(0,3000,3200,3420,3660,4050),longArrayOf(0,400,520,640,800,1200))
            paint.color=0xff213d50.toInt();paint.textSize=17f
            for(row in 0..2) for(col in 0..5) {
                val p=pets[row];val t=times[row][col]
                p.setBounds(col*240,row*300+35,col*240+240,row*300+275)
                p.prepareForExport(actions[row],t);p.draw(c)
                c.drawText("${titles[row].substringBefore('·').trim()} ${t/1000f}s",col*240+12f,row*300+25f,paint)
            }
            File(out,"interaction-steps.png").outputStream().use{board.compress(Bitmap.CompressFormat.PNG,100,it)};board.recycle()
            inst.sendStatus(0,android.os.Bundle().apply{putInt("frames",if(movie)count else 0);putString("output",out.name)})
        } finally { frame.recycle();pets.forEach{it.close()} }
    }
}
