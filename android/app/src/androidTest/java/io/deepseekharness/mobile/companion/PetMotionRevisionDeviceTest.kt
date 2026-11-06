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
    /** Optional live-surface review, not a claim that a real worker performed these activities. */
    @Test fun replayAuthoredClipsInActualOverlayForVisualReview() {
        if(InstrumentationRegistry.getArguments().getString("liveReview")!="true") return
        val prefs=CompanionStore(app);val reduced=prefs.reduceMotion
        val store=io.deepseekharness.mobile.runtime.RuntimeStore(app);val enabled=store.overlayBallEnabled()
        val wasRunning=OverlayBallService.isRunning
        var restoreDirector:(()->Unit)?=null
        val out=File(app.getExternalFilesDir(null),"pet-live-review").apply{mkdirs()}
        try {
            prefs.reduceMotion=false;store.setOverlayBallEnabled(true)
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            OverlayBallService.start(app)
            PetControlDeviceUi.await("live review pet") {
                OverlayBallService.updateForHostAppForeground(false)
                PetControlDeviceUi.service()?.let{PetControlDeviceUi.field(it,"petDrawable")}!=null
            }
            val service=PetControlDeviceUi.service() as OverlayBallService
            val actor=PetControlDeviceUi.field(service,"petActing") as PetActing
            val crop=Rect()
            inst.runOnMainSync {
                val running=actor.runningTasks;val waiting=actor.waitingTasks;val thinking=actor.chatThinking
                val dragging=actor.dragging
                restoreDirector={actor.runningTasks=running;actor.waitingTasks=waiting;actor.chatThinking=thinking;actor.dragging=dragging}
                actor.runningTasks=0;actor.waitingTasks=0;actor.chatThinking=false;actor.dragging=false
                val view=PetControlDeviceUi.field(service,"ballView") as android.view.View
                val xy=IntArray(2);view.getLocationOnScreen(xy)
                crop.set(xy[0],xy[1],xy[0]+view.width,xy[1]+view.height)
            }
            inst.sendStatus(0,android.os.Bundle().apply{putString("liveReviewCrop","${crop.width()}:${crop.height()}:${crop.left}:${crop.top}")})
            for(action in listOf(PetAction.IDLE,PetAction.THINK,PetAction.WORK,PetAction.CONTROL)) {
                inst.runOnMainSync {
                    actor.runningTasks=if(action==PetAction.WORK)1 else 0
                    actor.chatThinking=action==PetAction.THINK
                    actor.control(if(action==PetAction.CONTROL)"operating" else "idle",SystemClock.uptimeMillis())
                    actor.resume(SystemClock.uptimeMillis(),PetPose(action,if(action==PetAction.IDLE)16000 else 0))
                    service.javaClass.getDeclaredMethod("wakePetMotion").apply{isAccessible=true}.invoke(service)
                }
                inst.sendStatus(0,android.os.Bundle().apply{putString("liveReviewAction",action.name)})
                Thread.sleep(1800)
                val screen=requireNotNull(inst.uiAutomation.takeScreenshot())
                val avatar=Bitmap.createBitmap(screen,crop.left,crop.top,crop.width(),crop.height())
                File(out,"${action.name.lowercase()}.png").outputStream().use{avatar.compress(Bitmap.CompressFormat.PNG,100,it)}
                avatar.recycle();screen.recycle()
                Thread.sleep(3200)
                inst.runOnMainSync {
                    actor.runningTasks=0;actor.waitingTasks=0;actor.chatThinking=false
                    actor.control("idle",SystemClock.uptimeMillis())
                    actor.resume(SystemClock.uptimeMillis(),PetPose())
                    service.javaClass.getDeclaredMethod("wakePetMotion").apply{isAccessible=true}.invoke(service)
                }
                val expected=if(action==PetAction.IDLE)"drink_return" else PetCels.exits.getValue(PetCels.actionClips.getValue(action))
                val seen=mutableSetOf<String>();val until=SystemClock.uptimeMillis()+3500
                while(SystemClock.uptimeMillis()<until) {
                    inst.runOnMainSync {seen.add((PetControlDeviceUi.field(service,"petDrawable") as CompanionPetDrawable).displayedClipName)}
                    Thread.sleep(20)
                }
                assertTrue("$action must display its independent return: $seen",expected in seen)
                assertTrue("$action must reach idle: $seen","idle" in seen)
                inst.sendStatus(0,android.os.Bundle().apply{putString("liveReturnSequence",seen.joinToString(","))})
            }
        } finally {
            prefs.reduceMotion=reduced;store.setOverlayBallEnabled(enabled)
            inst.runOnMainSync {
                (PetControlDeviceUi.service() as? OverlayBallService)?.let{service ->
                    service.updatePetConversationState("idle")
                    (PetControlDeviceUi.field(service,"petActing") as PetActing).control(io.deepseekharness.mobile.DeviceControlState.current.phase,SystemClock.uptimeMillis())
                    restoreDirector?.invoke()
                    OverlayBallService.applyCompanionMotionPreference()
                }
            }
            if(!wasRunning) OverlayBallService.stop(app)
        }
    }
    @Test fun powerSaverRestsIdleWithoutReducingGestureCadence() {
        val prefs=CompanionStore(app);val reduced=prefs.reduceMotion
        val store=io.deepseekharness.mobile.runtime.RuntimeStore(app);val enabled=store.overlayBallEnabled()
        val wasRunning=OverlayBallService.isRunning
        var restoreDirector:(()->Unit)?=null
        try {
            prefs.reduceMotion=false;store.setOverlayBallEnabled(true)
            io.deepseekharness.mobile.DeviceControlState.resume()
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            OverlayBallService.start(app)
            PetControlDeviceUi.await("visible decoded pet") {
                OverlayBallService.updateForHostAppForeground(false)
                PetControlDeviceUi.service()?.let{PetControlDeviceUi.field(it,"petDrawable")}!=null
            }
            val service=PetControlDeviceUi.service() as OverlayBallService
            val pet=PetControlDeviceUi.field(service,"petDrawable") as CompanionPetDrawable
            val actor=PetControlDeviceUi.field(service,"petActing") as PetActing
            inst.runOnMainSync {
                val running=actor.runningTasks;val waiting=actor.waitingTasks
                val dragging=actor.dragging;val thinking=actor.chatThinking
                restoreDirector={actor.runningTasks=running;actor.waitingTasks=waiting;actor.dragging=dragging;actor.chatThinking=thinking}
                actor.runningTasks=0;actor.waitingTasks=0;actor.dragging=false
                service.javaClass.getDeclaredField("petPowerSave").apply{isAccessible=true}.setBoolean(service,true)
                service.updatePetConversationState("idle")
            }
            PetControlDeviceUi.await("power-saving ambient still") {
                var resting=false
                inst.runOnMainSync {resting=pet.currentPose.action==PetAction.IDLE && pet.currentCel.clip=="idle" && pet.nextFrameDelayMs==Long.MAX_VALUE}
                resting
            }
            inst.runOnMainSync {
                val callback=PetControlDeviceUi.field(service,"companionFrame") as Runnable
                val handler=PetControlDeviceUi.field(service,"hintHandler") as android.os.Handler
                if(android.os.Build.VERSION.SDK_INT>=29) assertFalse("Ambient still must not poll",handler.hasCallbacks(callback))
                service.updatePetConversationState("thinking")
            }
            PetControlDeviceUi.await("reading gesture begins") {
                var moving=false
                inst.runOnMainSync {moving=pet.displayedClipName=="reading" && pet.currentCel.frame in 1..4 && !pet.currentPose.reduced}
                moving
            }
            val seen=mutableSetOf<Int>();val until=SystemClock.uptimeMillis()+1100
            while(SystemClock.uptimeMillis()<until) {
                inst.runOnMainSync {seen.add(pet.currentCel.frame)}
                Thread.sleep(20)
            }
            assertTrue("Gesture should not be clamped to 5 FPS: $seen",seen.size>=8)
            inst.sendStatus(0,android.os.Bundle().apply{putInt("gestureCellsIn1100ms",seen.size);putBoolean("powerSaverIdleNoPolling",true)})
        } finally {
            prefs.reduceMotion=reduced;store.setOverlayBallEnabled(enabled)
            inst.runOnMainSync {
                (PetControlDeviceUi.service() as? OverlayBallService)?.updatePetConversationState("idle")
                restoreDirector?.invoke()
                OverlayBallService.applyCompanionMotionPreference()
            }
            if(!wasRunning) OverlayBallService.stop(app)
        }
    }
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
            val service=PetControlDeviceUi.service() as OverlayBallService
            val pet=PetControlDeviceUi.field(service,"petDrawable") as CompanionPetDrawable
            var held=PetPose()
            inst.runOnMainSync {
                held=pet.currentPose
                service.updatePetConversationState("thinking")
            }
            Thread.sleep(300)
            inst.runOnMainSync { assertEquals("Hidden updates must not load another atlas",held,pet.currentPose) }
            OverlayBallService.updateForHostAppForeground(false)
            PetControlDeviceUi.await("visible pet resumes") { scheduled() }
            PetControlDeviceUi.await("latest hidden state appears on resume") {
                var reading=false
                inst.runOnMainSync { reading=pet.displayedClipName=="reading" }
                reading
            }
            inst.runOnMainSync { service.updatePetConversationState("idle") }
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
            val times=arrayOf(longArrayOf(16000,16760,17080,17480,17800,18080),longArrayOf(0,1280,1520,1760,2000,2240),longArrayOf(0,400,520,640,800,1200))
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
