package io.deepseekharness.mobile.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.overlay.CompanionPetDrawable
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhalePetRenderDeviceTest {
    @Test fun profileVisibleAndHiddenOverlay() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val serviceType = io.deepseekharness.mobile.OverlayBallService::class.java
        val store = io.deepseekharness.mobile.runtime.RuntimeStore(context)
        val wasEnabled = store.overlayBallEnabled()
        val wasRunning = io.deepseekharness.mobile.OverlayBallService.isRunning
        val prefs = CompanionStore(context)
        val reduced = prefs.reduceMotion
        val seconds = InstrumentationRegistry.getArguments().getString("sampleSeconds")?.toLongOrNull()?.coerceIn(30, 600) ?: 60
        val activeField = serviceType.getDeclaredField("activeService").apply { isAccessible = true }
        fun frameRunning(): Boolean {
            var running = false
            inst.runOnMainSync { activeField.get(null)?.let { running = serviceType.getDeclaredField("companionFrame").apply { isAccessible = true }.get(it) != null } }
            return running
        }
        fun measure(name: String) {
            val start = SystemClock.elapsedRealtime()
            val cpuStart = android.os.Process.getElapsedCpuTime()
            Thread.sleep(seconds * 1000)
            val duration = SystemClock.elapsedRealtime() - start
            val cpu = android.os.Process.getElapsedCpuTime() - cpuStart
            val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            inst.sendStatus(0, Bundle().apply {
                putString("state", name); putLong("sampleMs", duration); putLong("processCpuMs", cpu)
                putBoolean("frameScheduled", frameRunning()); putLong("processPssKiB", android.os.Debug.getPss())
                putInt("temperatureDeciC", battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            })
        }
        try {
            prefs.reduceMotion = false
            store.setOverlayBallEnabled(true)
            io.deepseekharness.mobile.OverlayBallService.start(context)
            val until = SystemClock.elapsedRealtime() + 10_000
            while (!frameRunning() && SystemClock.elapsedRealtime() < until) {
                io.deepseekharness.mobile.OverlayBallService.updateForHostAppForeground(false); Thread.sleep(200)
            }
            check(frameRunning()) { "未观察到可见桌宠动画" }
            Thread.sleep(3000)
            val crop = android.graphics.Rect()
            inst.runOnMainSync {
                val view = serviceType.getDeclaredField("ballView").apply { isAccessible = true }.get(activeField.get(null)) as android.view.View
                val location = IntArray(2); view.getLocationOnScreen(location)
                crop.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
            inst.sendStatus(0, Bundle().apply {
                putString("avatarCrop", "${crop.width()}:${crop.height()}:${crop.left}:${crop.top}")
            })
            repeat(3) { i ->
                val screen = requireNotNull(inst.uiAutomation.takeScreenshot())
                if (i == 0) inst.sendStatus(0, Bundle().apply { putString("screenExtent", "${screen.width}:${screen.height}") })
                check(crop.intersect(0, 0, screen.width, screen.height))
                val avatar = Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height())
                java.io.File(context.getExternalFilesDir(null), "whale-pet-frame-$i.png").outputStream().use { avatar.compress(Bitmap.CompressFormat.PNG, 100, it) }
                avatar.recycle(); if (!screen.isRecycled) screen.recycle(); Thread.sleep(400)
            }
            if (InstrumentationRegistry.getArguments().getString("previewOnly") == "true") {
                Thread.sleep(12_000)
                return
            }
            measure("visible")
            io.deepseekharness.mobile.OverlayBallService.updateForHostAppForeground(true)
            Thread.sleep(1500); check(!frameRunning()) { "桌宠隐藏后仍在更新" }
            measure("hidden")
        } finally {
            prefs.reduceMotion = reduced
            store.setOverlayBallEnabled(wasEnabled)
            if (!wasRunning) io.deepseekharness.mobile.OverlayBallService.stop(context)
            else io.deepseekharness.mobile.OverlayBallService.updateForHostAppForeground(io.deepseekharness.mobile.AppForeground.isForeground())
        }
    }
    @Test fun drawAvatarCycle() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val resources = inst.targetContext.resources
        fun load(id: Int) = BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inSampleSize = 4; inScaled = false })
        val open = load(R.drawable.whale_maid_open_v2)
        val frame = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        CompanionPetDrawable(open, load(R.drawable.whale_maid_blink_v2), load(R.drawable.whale_maid_body_v3)).use { pet ->
            pet.setBounds(0, 0, 192, 192)
            val times = mutableListOf<Long>()
            repeat(260) { i ->
                pet.setPose(io.deepseekharness.mobile.overlay.PetActing.pose(io.deepseekharness.mobile.overlay.PetAction.entries[i / 40 % 7], (i % 40) * 50L))
                pet.blinkAmount = if (i % 70 in 0..4) 0.6f else 0f
                val start = SystemClock.elapsedRealtimeNanos(); pet.draw(canvas)
                if (i >= 40) times.add(SystemClock.elapsedRealtimeNanos() - start)
            }
            times.sort()
            inst.sendStatus(0, Bundle().apply {
                putInt("sourceWidth", open.width); putLong("drawP50Us", times[times.size / 2] / 1000)
                putLong("drawP95Us", times[times.size * 95 / 100] / 1000)
            })
        }
        frame.recycle()
    }
}
