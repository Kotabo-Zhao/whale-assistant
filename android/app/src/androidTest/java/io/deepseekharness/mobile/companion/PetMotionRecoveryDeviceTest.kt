package io.deepseekharness.mobile.companion

import android.os.Handler
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.overlay.CompanionPetDrawable
import io.deepseekharness.mobile.overlay.PetAction
import org.junit.Assert.*
import org.junit.Test

/** Exercise the live overlay's callback ownership, not just an offline animation timeline. */
class PetMotionRecoveryDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext

    private fun withPet(body: (OverlayBallService, View, CompanionPetDrawable) -> Unit) {
        val prefs = CompanionStore(app)
        val reduced = prefs.reduceMotion
        val store = io.deepseekharness.mobile.runtime.RuntimeStore(app)
        val enabled = store.overlayBallEnabled()
        val wasRunning = OverlayBallService.isRunning
        try {
            prefs.reduceMotion = false
            store.setOverlayBallEnabled(true)
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            OverlayBallService.start(app)
            PetControlDeviceUi.await("decoded visible overlay") {
                var ready = false
                inst.runOnMainSync {
                    OverlayBallService.updateForHostAppForeground(false)
                    PetControlDeviceUi.service()?.let {
                        ready = (PetControlDeviceUi.field(it, "ballView") as? View)?.isShown == true &&
                            PetControlDeviceUi.field(it, "petDrawable") != null
                    }
                }
                ready
            }
            val service = PetControlDeviceUi.service() as OverlayBallService
            val view = PetControlDeviceUi.field(service, "ballView") as View
            val pet = PetControlDeviceUi.field(service, "petDrawable") as CompanionPetDrawable
            // Establish the starting loop even on the old release. Subsequent recovery gets no nudge.
            inst.runOnMainSync { OverlayBallService.applyCompanionMotionPreference() }
            body(service, view, pet)
        } finally {
            prefs.reduceMotion = reduced
            store.setOverlayBallEnabled(enabled)
            inst.runOnMainSync {
                (PetControlDeviceUi.service() as? OverlayBallService)?.let {
                    (PetControlDeviceUi.field(it, "ballView") as? View)?.visibility = View.VISIBLE
                    it.updatePetConversationState("idle")
                    OverlayBallService.applyCompanionMotionPreference()
                }
            }
            if (!wasRunning) OverlayBallService.stop(app)
        }
    }

    private fun running(service: OverlayBallService): Boolean {
        var running = false
        inst.runOnMainSync { running = PetControlDeviceUi.field(service, "companionFrame") != null }
        return running
    }

    private fun assertAdvances(pet: CompanionPetDrawable) {
        val seen = mutableSetOf<Pair<String, Int>>()
        val until = SystemClock.uptimeMillis() + 4500
        while (SystemClock.uptimeMillis() < until && seen.size < 3) {
            inst.runOnMainSync { seen.add(pet.currentCel.let { it.clip to it.frame }) }
            Thread.sleep(40)
        }
        assertTrue("Visible cels must advance after recovery: $seen", seen.size >= 3)
    }

    @Test fun visibilityRestoresAnimationWithoutAnotherBusinessEvent() = withPet { service, view, pet ->
        repeat(4) {
            inst.runOnMainSync { view.visibility = View.INVISIBLE }
            PetControlDeviceUi.await("hidden view must cancel frames") { !running(service) }
            Thread.sleep(200)
            assertFalse("Hidden view must not poll", running(service))
            inst.runOnMainSync { view.visibility = View.VISIBLE }
            PetControlDeviceUi.await("visible view must restart without a message or screen cycle") { running(service) }
            assertAdvances(pet)
        }
    }

    @Test fun newReplyRepairsMissingCallbackWithoutDiscardingTheReply() = withPet { service, view, pet ->
        inst.runOnMainSync {
            view.visibility = View.INVISIBLE
            val callback = PetControlDeviceUi.field(service, "companionFrame") as? Runnable
            val handler = PetControlDeviceUi.field(service, "hintHandler") as Handler
            callback?.let { handler.removeCallbacks(it); it.run() }
            assertNull(PetControlDeviceUi.field(service, "companionFrame"))
            view.visibility = View.VISIBLE
            service.updatePetConversationState("reply")
        }
        PetControlDeviceUi.await("the new reply must survive recovery") {
            var replying = false
            inst.runOnMainSync { replying = pet.currentPose.action == PetAction.REPLY && pet.displayedClipName == "reply" }
            replying
        }
        assertAdvances(pet)
    }

    @Test fun reattachingTheSameWindowRestoresFrames() = withPet { service, view, pet ->
        val windows = PetControlDeviceUi.field(service, "windowManager") as android.view.WindowManager
        val params = PetControlDeviceUi.field(service, "layoutParams") as android.view.WindowManager.LayoutParams
        repeat(4) {
            inst.runOnMainSync { windows.removeViewImmediate(view) }
            assertFalse("Detached overlay must stop", running(service))
            Thread.sleep(150)
            inst.runOnMainSync { windows.addView(view, params) }
            PetControlDeviceUi.await("reattached overlay should resume") { running(service) }
            assertAdvances(pet)
        }
    }

    @Test fun returningHomeRepeatedlyRestoresTheRealSurface() = withPet { service, view, pet ->
        repeat(6) {
            app.startActivity(android.content.Intent(app, io.deepseekharness.mobile.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            PetControlDeviceUi.await("host activity must suspend the overlay") { !running(service) }
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            PetControlDeviceUi.await("returning home must restore the loop") { running(service) }
            assertAdvances(pet)
        }
        val pixels = mutableSetOf<Int>()
        val crop = android.graphics.Rect()
        inst.runOnMainSync {
            val xy = IntArray(2); view.getLocationOnScreen(xy)
            crop.set(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
            service.updatePetConversationState("thinking")
        }
        PetControlDeviceUi.await("reading atlas for actual surface capture") {
            var reading = false
            inst.runOnMainSync { reading = pet.displayedClipName == "reading" }
            reading
        }
        val out = java.io.File(app.getExternalFilesDir(null), "pet-recovery-review").apply { mkdirs() }
        repeat(18) { index ->
            val screen = requireNotNull(inst.uiAutomation.takeScreenshot())
            val avatar = android.graphics.Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height())
            val values = IntArray(avatar.width * avatar.height)
            avatar.getPixels(values, 0, avatar.width, 0, 0, avatar.width, avatar.height)
            pixels.add(values.contentHashCode())
            java.io.File(out, "frame-$index.png").outputStream().use {
                avatar.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            avatar.recycle(); screen.recycle()
            Thread.sleep(120)
        }
        assertTrue("The actual overlay surface must change, not just its logical frame: ${pixels.size}", pixels.size >= 4)
        inst.sendStatus(0, android.os.Bundle().apply {
            putInt("homeCycles", 6); putInt("uniqueSurfaceFrames", pixels.size)
        })
    }
}
