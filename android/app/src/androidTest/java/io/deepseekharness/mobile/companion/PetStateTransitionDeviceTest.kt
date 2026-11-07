package io.deepseekharness.mobile.companion

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.overlay.CompanionPetDrawable
import io.deepseekharness.mobile.overlay.PetActing
import io.deepseekharness.mobile.overlay.PetPose
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Samples the native drawable attached to the live overlay. Never captures the user's screen. */
class PetStateTransitionDeviceTest {
    @Test fun interruptedActionsKeepTheirDrawingsConnected() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val args = InstrumentationRegistry.getArguments()
        val export = args.getString("capturePetTransitions") == "true"
        val store = RuntimeStore(app)
        val prefs = CompanionStore(app)
        val enabled = store.overlayBallEnabled()
        val reduced = prefs.reduceMotion
        val wasRunning = OverlayBallService.isRunning
        val output = File(app.getExternalFilesDir(null), "pet-state-transitions")
        var restore: (() -> Unit)? = null
        try {
            prefs.reduceMotion = false
            store.setOverlayBallEnabled(true)
            inst.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            OverlayBallService.start(app)
            PetControlDeviceUi.await("live pet surface") {
                var ready = false
                inst.runOnMainSync {
                    OverlayBallService.updateForHostAppForeground(false)
                    PetControlDeviceUi.service()?.let { ready = PetControlDeviceUi.field(it, "petDrawable") != null &&
                        (PetControlDeviceUi.field(it, "ballView") as? View)?.isShown == true }
                }
                ready
            }
            val service = PetControlDeviceUi.service() as OverlayBallService
            val actor = PetControlDeviceUi.field(service, "petActing") as PetActing
            val pet = PetControlDeviceUi.field(service, "petDrawable") as CompanionPetDrawable
            inst.runOnMainSync {
                val running = actor.runningTasks; val waiting = actor.waitingTasks
                val thinking = actor.chatThinking; val dragging = actor.dragging
                restore = { actor.runningTasks = running; actor.waitingTasks = waiting
                    actor.chatThinking = thinking; actor.dragging = dragging
                    service.javaClass.getDeclaredMethod("refreshControlPose").apply { isAccessible = true }.invoke(service) }
                actor.control("idle"); actor.runningTasks = 0; actor.waitingTasks = 0
                actor.dragging = false; actor.chatThinking = false
                actor.resume(SystemClock.uptimeMillis(), PetPose())
                service.updatePetConversationState("idle")
            }
            val frames = mutableListOf<Triple<Long, String, Int>>()
            if (export) output.mkdirs()
            val start = SystemClock.uptimeMillis()
            val times = listOf(400L, 2100L, 3850L, 5200L, 5780L, 5860L, 6000L)
            var event = 0
            var deadline = start
            while (SystemClock.uptimeMillis() - start < 8600) {
                SystemClock.sleep((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
                var bitmap: Bitmap? = null
                inst.runOnMainSync {
                    val elapsed = SystemClock.uptimeMillis() - start
                    while (event < times.size && elapsed >= times[event]) {
                        when (event++) {
                            0 -> service.updatePetConversationState("thinking")
                            1 -> { actor.runningTasks = 1; service.updatePetConversationState("idle") }
                            2 -> { actor.control("operating",SystemClock.uptimeMillis()); service.updatePetConversationState("idle") }
                            3 -> { actor.control("idle"); service.updatePetConversationState("reply") }
                            4 -> service.updatePetConversationState("thinking")
                            5 -> { actor.dragging = true; service.updatePetConversationState("idle") }
                            6 -> { actor.dragging = false; actor.runningTasks = 0; service.updatePetConversationState("idle") }
                        }
                    }
                    frames += Triple(elapsed, pet.displayedClipName, pet.currentCel.frame)
                    if (export) {
                        val b = pet.bounds
                        bitmap = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
                        pet.draw(Canvas(bitmap!!).apply { translate(-b.left.toFloat(), -b.top.toFloat()) })
                    }
                }
                bitmap?.let { b -> File(output, "f%04d.png".format(java.util.Locale.ROOT, frames.lastIndex)).outputStream().use {
                    b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
                deadline = maxOf(deadline + 33, SystemClock.uptimeMillis())
            }
            if (export) File(output,"frames.csv").writeText(frames.joinToString("\n") { "${it.first},${it.second},${it.third}" })
            val seen = frames.map { it.second }.distinct()
            assertTrue("did not use the seated writing entry: $seen", "writing_enter" in seen)
            assertTrue("writing never became visible: $seen", "writing" in seen)
            assertTrue("late decode or superseded drag left the pet in $seen", frames.last().second == "idle")
        } finally {
            inst.runOnMainSync { restore?.invoke() }
            prefs.reduceMotion = reduced
            store.setOverlayBallEnabled(enabled)
            OverlayBallService.applyCompanionMotionPreference()
            if (!wasRunning) OverlayBallService.stop(app)
        }
    }
}
