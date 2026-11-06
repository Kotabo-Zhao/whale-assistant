package io.deepseekharness.mobile.companion

import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.DeviceControlState
import io.deepseekharness.mobile.overlay.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class PetAllStatesDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    private val labels=mapOf(PetAction.IDLE to "待机 · 眨眼与小憩",PetAction.ACCEPT to "接单 · 点头与确认",PetAction.THINK to "思考 · 阅读与翻页",
        PetAction.WORK to "工作 · 书写与回看",PetAction.ASK to "询问 · 摊手与侧首",PetAction.REPLY to "回复 · 展手与表达",
        PetAction.CELEBRATE to "完成 · 轻跃与落地",PetAction.SETBACK to "受挫 · 低首再振作",PetAction.TOUCH to "触摸 · 眨眼与亲近",
        PetAction.NOTICE to "主动提醒 · 挥手递信",PetAction.DRAG to "拖动 · 收脚与平衡",PetAction.OBSERVE to "观察 · 举机与扫描",
        PetAction.CONTROL to "操控 · 双拍与停顿",PetAction.WAIT to "等待 · 低持与轮候",PetAction.PAUSED to "暂停 · 举手与持守",
        PetAction.STOPPED to "停止 · 收机与示意",PetAction.ERROR to "异常 · 看屏与求助")
    private fun drawable():CompanionPetDrawable {
        return CompanionPetDrawable(app.resources,320,java.util.concurrent.Executor { it.run() })
    }
    @Test fun renderAllStatesAcrossThemesAndExportNativeFrames() {
        val outputName=InstrumentationRegistry.getArguments().getString("outputDir") ?: "pet-animation"
        require(outputName.matches(Regex("[a-zA-Z0-9-]+")))
        val out=File(app.getExternalFilesDir(null),outputName).apply{mkdirs()}
        val style=app.getSharedPreferences("whale-interface",0);val oldStyle=style.getString("style",null)
        val oldMode=io.deepseekharness.mobile.AppThemePreference.current(app)
        val frame=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);val canvas=Canvas(frame)
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=25f}
        val movie=InstrumentationRegistry.getArguments().getString("movie")=="true"
        val onlyState=InstrumentationRegistry.getArguments().getString("onlyState")
        require(onlyState==null || labels.keys.any{it.name==onlyState})
        var frameNumber=0
        var writtenFrames=0
        try {
            drawable().use { pet ->
                for(look in listOf("simple","cute")) for(mode in listOf("dark","light")) {
                    style.edit().putString("style",look).commit();io.deepseekharness.mobile.AppThemePreference.save(app,mode)
                    val ui=PetUi(app)
                    val sheet=Bitmap.createBitmap(1280,1800,Bitmap.Config.ARGB_8888);val board=Canvas(sheet);board.drawColor(ui.canvas)
                    labels.keys.forEachIndexed {i,action ->
                        val x=i%4*320;val y=i/4*360
                        label.color=ui.ink;label.textSize=17f;board.drawText(labels.getValue(action),x+15f,y+27f,label)
                        val instant=when(action){PetAction.IDLE->8500L;PetAction.TOUCH->410L;else->900L}
                        pet.prepareForExport(action,instant)
                        pet.setBounds(x+8,y+30,x+312,y+334);pet.draw(board)
                    }
                    File(out,"states-$look-$mode.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)};sheet.recycle()
                    if(look=="simple" && mode=="dark" && movie) {
                        val frames=File(out,"frames").apply{mkdirs()}
                        for(action in labels.keys) {
                            val actor=PetActing();actor.sample(0)
                            when(action) {
                                PetAction.IDLE -> Unit
                                PetAction.THINK -> actor.chatThinking=true
                                PetAction.WORK -> actor.runningTasks=1
                                PetAction.ASK -> actor.waitingTasks=1
                                PetAction.DRAG -> actor.dragging=true
                                PetAction.OBSERVE -> actor.control("observing",100)
                                PetAction.CONTROL -> actor.control("operating",100)
                                PetAction.WAIT -> actor.control("waiting",100)
                                PetAction.PAUSED -> actor.control("paused",100)
                                PetAction.STOPPED -> actor.control("stopped",100)
                                PetAction.ERROR -> actor.control("failed",100)
                                else -> actor.react(action,100)
                            }
                            val seconds=when(action){PetAction.IDLE->12;PetAction.THINK->6;PetAction.WORK->8;PetAction.ASK->6;else->4}
                            if(onlyState!=null && action.name!=onlyState) {frameNumber+=seconds*20;continue}
                            repeat(seconds*20) { index ->
                                canvas.drawColor(ui.canvas);label.color=ui.ink;label.textSize=28f
                                canvas.drawText(labels.getValue(action),32f,54f,label)
                                label.color=ui.muted;label.textSize=17f
                                canvas.drawText("鲸鱼助手 · Pocket S 原生动画预览",32f,86f,label)
                                val pose=actor.sample(100+index*50L)
                                pet.prepareForExport(pose.action,pose.elapsedMs)
                                pet.setBounds(48,114,592,658);pet.draw(canvas)
                                label.textSize=15f;canvas.drawText("深海·专注    ${action.name}",32f,694f,label)
                                File(frames,"frame-${(frameNumber++).toString().padStart(5,'0')}.png").outputStream().use{frame.compress(Bitmap.CompressFormat.PNG,100,it)}
                                writtenFrames++
                            }
                            inst.sendStatus(0,android.os.Bundle().apply{putString("renderedState",action.name);putInt("framesWritten",frameNumber)})
                        }
                    }
                }
            }
            assertEquals(17,labels.size)
            inst.sendStatus(0,android.os.Bundle().apply{putInt("stateCount",labels.size);putInt("themeCount",4);putInt("nativeFrames",frameNumber);putInt("writtenFrames",writtenFrames)})
        } finally {
            frame.recycle();style.edit().apply{if(oldStyle==null) remove("style") else putString("style",oldStyle)}.commit()
            io.deepseekharness.mobile.AppThemePreference.save(app,oldMode)
        }
    }
    @Test fun realOverlayReceivesConversationAndTouchStatesAndControlAlwaysWins() {
        val store=io.deepseekharness.mobile.runtime.RuntimeStore(app);val enabled=store.overlayBallEnabled()
        val prefs=CompanionStore(app);val reduced=prefs.reduceMotion
        val position=OverlayBallPreferences.from(app);val original=position.readPosition()
        try {
            DeviceControlState.resume();prefs.reduceMotion=false;store.setOverlayBallEnabled(true)
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            OverlayBallService.start(app)
            PetControlDeviceUi.await("animated pet attached") {
                OverlayBallService.updateForHostAppForeground(false)
                PetControlDeviceUi.service()?.let{PetControlDeviceUi.field(it,"petDrawable")}!=null
            }
            val service=PetControlDeviceUi.service() as OverlayBallService
            val actor=PetControlDeviceUi.field(service,"petActing") as PetActing
            fun state(expected:PetAction)=PetControlDeviceUi.await(expected.name) {var same=false;inst.runOnMainSync{same=actor.action==expected};same}
            inst.runOnMainSync{service.updatePetConversationState("thinking")};state(PetAction.THINK)
            inst.runOnMainSync{service.updatePetConversationState("reply")};state(PetAction.REPLY)
            state(PetAction.IDLE)
            val view=PetControlDeviceUi.field(service,"ballView") as View;val xy=IntArray(2)
            inst.runOnMainSync{view.getLocationOnScreen(xy);xy[0]+=view.width/2;xy[1]+=view.height/2}
            val now=SystemClock.uptimeMillis()
            fun touch(action:Int,x:Float,y:Float) {
                val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0).apply{source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                try{assertTrue(inst.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
            }
            val dx=if(xy[0]>app.resources.displayMetrics.widthPixels/2) -100f else 100f
            try {
                touch(MotionEvent.ACTION_DOWN,xy[0].toFloat(),xy[1].toFloat());Thread.sleep(60)
                touch(MotionEvent.ACTION_MOVE,xy[0]+dx,xy[1].toFloat());state(PetAction.DRAG)
                DeviceControlState.pause();state(PetAction.PAUSED)
            } finally {touch(MotionEvent.ACTION_CANCEL,xy[0]+dx,xy[1].toFloat())}
            inst.runOnMainSync{assertFalse(actor.dragging)}
            prefs.reduceMotion=true;inst.runOnMainSync{OverlayBallService.applyCompanionMotionPreference()}
            inst.runOnMainSync{
                assertNull(PetControlDeviceUi.field(service,"companionFrame"))
                val pet=PetControlDeviceUi.field(service,"petDrawable") as CompanionPetDrawable
                val pose=PetControlDeviceUi.field(pet,"pose") as PetPose
                assertEquals(PetAction.PAUSED,pose.action);assertTrue(pose.reduced)
            }
            inst.sendStatus(0,android.os.Bundle().apply{putBoolean("conversationStates",true);putBoolean("realDragAndCancel",true);putBoolean("controlPriority",true);putBoolean("reducedMotionStatic",true)})
        } finally {
            DeviceControlState.resume();prefs.reduceMotion=reduced
            original?.let{position.writePosition(it.first,it.second)} ?: position.clearPosition()
            store.setOverlayBallEnabled(enabled);OverlayBallService.stop(app)
            if(enabled) OverlayBallService.start(app)
        }
    }
}
