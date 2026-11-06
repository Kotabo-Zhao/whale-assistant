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
    @Test fun profileColdAtlasDecoding() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val times = mutableListOf<Long>()
        // This runs on the instrumentation thread. The live view decodes on its avatar executor.
        CompanionPetDrawable(inst.targetContext.resources,264,java.util.concurrent.Executor { it.run() }).use { pet ->
            for (action in io.deepseekharness.mobile.overlay.PetAction.entries) {
                if (action == io.deepseekharness.mobile.overlay.PetAction.IDLE) continue
                val start = SystemClock.elapsedRealtimeNanos()
                pet.prepareForExport(action)
                val elapsed = (SystemClock.elapsedRealtimeNanos()-start)/1000
                times.add(elapsed)
                inst.sendStatus(0,Bundle().apply { putString("decodedClip",action.name);putLong("coldAtlasDecodeUs",elapsed) })
            }
            val start = SystemClock.elapsedRealtimeNanos()
            pet.prepareForExport(io.deepseekharness.mobile.overlay.PetAction.IDLE,16000)
            times.add((SystemClock.elapsedRealtimeNanos()-start)/1000)
            times.sort()
            inst.sendStatus(0,Bundle().apply {
                putLong("decodeP50Us",times[times.size/2]);putLong("decodeP95Us",times[times.size*95/100])
                putLong("decodeMaxUs",times.last());putLong("cachedBitmapBytes",pet.cachedBitmapBytes)
            })
        }
    }
    @Test fun profileVisibleAndHiddenOverlay() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val serviceType = io.deepseekharness.mobile.OverlayBallService::class.java
        val store = io.deepseekharness.mobile.runtime.RuntimeStore(context)
        val wasEnabled = store.overlayBallEnabled()
        val wasRunning = io.deepseekharness.mobile.OverlayBallService.isRunning
        val prefs = CompanionStore(context)
        val reduced = prefs.reduceMotion
        val args = InstrumentationRegistry.getArguments()
        val discharge = args.getString("powerMode") == "discharge"
        val seconds = args.getString("sampleSeconds")?.toLongOrNull() ?: if (discharge) 1800L else 60L
        require(seconds in (if (discharge) 1800L..3600L else 30L..600L))
        val powerFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        fun battery() = requireNotNull(context.registerReceiver(null,powerFilter))
        fun plugged(intent: android.content.Intent) = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,-1)
        val gauge = context.getSystemService(android.os.BatteryManager::class.java)
        fun charge() = gauge.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val screen = context.getSystemService(android.os.PowerManager::class.java)
        val connects = java.util.concurrent.atomic.AtomicInteger()
        val screenChanges = java.util.concurrent.atomic.AtomicInteger()
        val edges = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                if (intent.action == android.content.Intent.ACTION_POWER_CONNECTED) connects.incrementAndGet()
                else screenChanges.incrementAndGet()
            }
        }
        val activeField = serviceType.getDeclaredField("activeService").apply { isAccessible = true }
        fun frameRunning(): Boolean {
            var running = false
            inst.runOnMainSync { activeField.get(null)?.let { running = serviceType.getDeclaredField("companionFrame").apply { isAccessible = true }.get(it) != null } }
            return running
        }
        fun measure(name: String) {
            val startBattery = battery()
            val startCharge = charge()
            val startConnects = connects.get()
            val startScreenChanges = screenChanges.get()
            val startScreen = screen.isInteractive
            val expectedFrames = name == "visible"
            val expectedScreen = name != "screen-off"
            check(frameRunning() == expectedFrames)
            if (discharge) check(plugged(startBattery) == 0) { "离电采样开始前仍有外部电源" }
            val start = SystemClock.elapsedRealtime()
            val cpuStart = android.os.Process.getElapsedCpuTime()
            Thread.sleep(seconds * 1000)
            val duration = SystemClock.elapsedRealtime() - start
            val cpu = android.os.Process.getElapsedCpuTime() - cpuStart
            inst.waitForIdleSync()
            val endBattery = battery()
            val endCharge = charge()
            val powerConnections = connects.get() - startConnects
            val displayTransitions = screenChanges.get() - startScreenChanges
            inst.sendStatus(0, Bundle().apply {
                putString("state", name); putLong("sampleMs", duration); putLong("processCpuMs", cpu)
                putBoolean("systemPowerSave",screen.isPowerSaveMode)
                putBoolean("frameScheduled", frameRunning()); putLong("processPssKiB", android.os.Debug.getPss())
                putInt("temperatureDeciC",endBattery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,-1))
                putInt("startTemperatureDeciC",startBattery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,-1))
                putInt("startPlugged",plugged(startBattery)); putInt("endPlugged",plugged(endBattery))
                putInt("powerConnections",powerConnections); putString("requestedMode",if (discharge) "discharge" else "baseline")
                putBoolean("startInteractive",startScreen); putBoolean("endInteractive",screen.isInteractive)
                putInt("screenTransitions",displayTransitions)
                if (startCharge > 0 && endCharge > 0) {
                    putLong("startChargeUAh",startCharge); putLong("endChargeUAh",endCharge)
                }
            })
            check(startScreen == expectedScreen && screen.isInteractive == expectedScreen && displayTransitions == 0 && frameRunning() == expectedFrames) { "显示状态在采样中改变" }
            if (discharge) check(plugged(endBattery) == 0 && powerConnections == 0 && startCharge > 0 && endCharge > 0) {
                "离电采样期间接入过外部电源或电量计不可用"
            }
        }
        context.registerReceiver(edges,android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED).apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON); addAction(android.content.Intent.ACTION_SCREEN_OFF)
        })
        try {
            if (discharge) {
                inst.sendStatus(0,Bundle().apply { putString("powerSampler","ready to unplug") })
                val until = SystemClock.elapsedRealtime() + 300000
                while (plugged(battery()) != 0 && SystemClock.elapsedRealtime() < until) Thread.sleep(1000)
                check(plugged(battery()) == 0) { "五分钟内未离电，未开始验收" }
            }
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
            if (args.getString("screenCycle") == "true") {
                fun key(code: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    inst.uiAutomation.executeShellCommand("input keyevent $code")).use { it.readBytes() }
                fun awaitState(expected: Boolean, frames: Boolean) {
                    val limit = SystemClock.elapsedRealtime()+8000
                    while (screen.isInteractive != expected || frameRunning() != frames) {
                        check(SystemClock.elapsedRealtime() < limit) { "桌宠动画没有跟随屏幕状态" }
                        Thread.sleep(100)
                    }
                }
                io.deepseekharness.mobile.OverlayBallService.updateForHostAppForeground(false)
                awaitState(true,true)
                try {
                    key("KEYCODE_SLEEP")
                    awaitState(false,false)
                    Thread.sleep(500)
                    measure("screen-off")
                } finally { key("KEYCODE_WAKEUP") }
                val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
                awaitState(true,!keyguard.isKeyguardLocked)
                inst.sendStatus(0,Bundle().apply { putBoolean("wakeKeyguardLocked",keyguard.isKeyguardLocked); putBoolean("wakeFrames",frameRunning()) })
            }
        } finally {
            context.unregisterReceiver(edges)
            prefs.reduceMotion = reduced
            store.setOverlayBallEnabled(wasEnabled)
            if (!wasRunning) io.deepseekharness.mobile.OverlayBallService.stop(context)
            else io.deepseekharness.mobile.OverlayBallService.updateForHostAppForeground(io.deepseekharness.mobile.AppForeground.isForeground())
        }
    }
    @Test fun drawAvatarCycle() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val resources = inst.targetContext.resources
        val frame = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        CompanionPetDrawable(resources, 192, java.util.concurrent.Executor { it.run() }).use { pet ->
            pet.setBounds(0, 0, 192, 192)
            val times = mutableListOf<Long>()
            repeat(io.deepseekharness.mobile.overlay.PetAction.entries.size * 40) { i ->
                pet.prepareForExport(io.deepseekharness.mobile.overlay.PetAction.entries[i / 40], (i % 40) * 50L)
                val start = SystemClock.elapsedRealtimeNanos(); pet.draw(canvas)
                if (i >= 40) times.add(SystemClock.elapsedRealtimeNanos() - start)
            }
            times.sort()
            inst.sendStatus(0, Bundle().apply {
                putInt("sourceWidth", io.deepseekharness.mobile.overlay.PetCels.CELL)
                putLong("cachedBitmapBytes", pet.cachedBitmapBytes)
                putLong("drawP50Us", times[times.size / 2] / 1000)
                putLong("drawP95Us", times[times.size * 95 / 100] / 1000)
            })
        }
        frame.recycle()
    }
}
