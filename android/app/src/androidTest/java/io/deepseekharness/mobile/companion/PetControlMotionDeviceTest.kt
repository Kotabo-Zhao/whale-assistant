package io.deepseekharness.mobile.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.overlay.CompanionPetDrawable
import io.deepseekharness.mobile.overlay.PetActing
import io.deepseekharness.mobile.overlay.PetAction
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class PetControlMotionDeviceTest {
    @Test fun temporaryPetPreservesSwitchAndShowsStaticStatusInAllThemes() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val store=io.deepseekharness.mobile.runtime.RuntimeStore(app);val enabled=store.overlayBallEnabled()
        val prefs=CompanionStore(app);val reduced=prefs.reduceMotion
        val style=app.getSharedPreferences("whale-interface",0);val oldStyle=style.getString("style",null)
        val oldMode=io.deepseekharness.mobile.AppThemePreference.current(app)
        try {
            inst.runOnMainSync {io.deepseekharness.mobile.DeviceControlState.resume();io.deepseekharness.mobile.OverlayBallService.stop(app)}
            PetControlDeviceUi.await("old pet stopped") {PetControlDeviceUi.service()==null}
            store.setOverlayBallEnabled(false)
            io.deepseekharness.mobile.DeviceControlState.pause()
            io.deepseekharness.mobile.DeviceControlState.refreshIndicator(app)
            PetControlDeviceUi.await("temporary pet decoded") {PetControlDeviceUi.service()?.let{PetControlDeviceUi.field(it,"petDrawable")}!=null}
            val service=checkNotNull(PetControlDeviceUi.service())
            prefs.reduceMotion=true
            for(look in listOf("simple","cute")) for(mode in listOf("light","dark")) {
                style.edit().putString("style",look).commit();io.deepseekharness.mobile.AppThemePreference.save(app,mode)
                inst.runOnMainSync {
                    io.deepseekharness.mobile.OverlayBallService.applyCompanionMotionPreference()
                    io.deepseekharness.mobile.OverlayBallService.refreshAppearance()
                }
                inst.waitForIdleSync()
                inst.runOnMainSync {
                    val drawable=checkNotNull(PetControlDeviceUi.field(service,"petDrawable"))
                    val pose=PetControlDeviceUi.field(drawable,"pose") as io.deepseekharness.mobile.overlay.PetPose
                    assertEquals(PetAction.PAUSED,pose.action);assertTrue(pose.reduced)
                    assertNull("reduced motion must not keep scheduling frames",PetControlDeviceUi.field(service,"companionFrame"))
                    assertTrue((PetControlDeviceUi.field(service,"ballView") as android.view.View).contentDescription.contains("暂停"))
                    assertFalse(store.overlayBallEnabled())
                }
            }
            PetControlDeviceUi.click("恢复控制")
            PetControlDeviceUi.await("temporary pet removed after control") {PetControlDeviceUi.service()==null}
            assertTrue(PetControlProtection.bounds().isEmpty())
            assertFalse(store.overlayBallEnabled())
        } catch(error:Throwable) {
            inst.uiAutomation.takeScreenshot()?.let { bitmap -> File(app.getExternalFilesDir(null),"p77-temporary-pet-failure.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            throw error
        } finally {
            io.deepseekharness.mobile.DeviceControlState.resume()
            prefs.reduceMotion=reduced
            style.edit().apply{if(oldStyle==null) remove("style") else putString("style",oldStyle)}.commit()
            io.deepseekharness.mobile.AppThemePreference.save(app,oldMode)
            store.setOverlayBallEnabled(enabled)
            if(enabled) io.deepseekharness.mobile.OverlayBallService.start(app) else io.deepseekharness.mobile.OverlayBallService.stop(app)
        }
    }
    @Test fun renderPhoneStatesAndLiftFramesOnDevice() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val frame=Bitmap.createBitmap(1200,1320,Bitmap.Config.ARGB_8888);val canvas=Canvas(frame)
        canvas.drawColor(Color.rgb(15,28,44))
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(210,232,241);textSize=23f}
        CompanionPetDrawable(app.resources,320,java.util.concurrent.Executor { it.run() }).use { pet ->
            val actions=listOf(PetAction.OBSERVE,PetAction.CONTROL,PetAction.WAIT,PetAction.PAUSED,PetAction.STOPPED,PetAction.ERROR)
            actions.forEachIndexed { i,action ->
                val x=i%3*400;val y=i/3*330
                canvas.drawText(action.name,x+20f,y+30f,label)
                pet.prepareForExport(action,900);pet.setBounds(x+45,y+38,x+355,y+328);pet.draw(canvas)
            }
            val actor=PetActing();actor.sample(0);actor.control("operating",100);actor.sample(100)
            listOf(0L,100L,220L,420L,700L,1050L).forEachIndexed { i,ms ->
                val x=i%3*400;val y=660+i/3*330
                canvas.drawText("TAKE PHONE ${ms}ms",x+20f,y+30f,label)
                val pose=actor.sample(100+ms);pet.prepareForExport(pose.action,pose.elapsedMs)
                pet.setBounds(x+45,y+38,x+355,y+328);pet.draw(canvas)
            }
            assertEquals(PetAction.CONTROL,actor.action)
        }
        File(app.getExternalFilesDir(null),"p77-pet-phone-states.png").outputStream().use{frame.compress(Bitmap.CompressFormat.PNG,100,it)}
        frame.recycle()
    }
}
