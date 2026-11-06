package io.deepseekharness.mobile.companion

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.overlay.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class PetCelAssetsDeviceTest {
    @Test fun loadingEveryClipOffMainKeepsAtMostThreeAtlases() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val decoder = Executor { task -> worker.execute {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            task.run()
        } }
        val pet = CompanionPetDrawable(inst.targetContext.resources, 264, decoder)
        try {
            val poses = PetAction.entries.map { PetPose(it) } + PetPose(PetAction.IDLE, 16_000)
            for (pose in poses) {
                val clip = PetCels.sample(pose).clip
                inst.runOnMainSync { pet.setPose(pose) }
                PetControlDeviceUi.await("decode $clip") {
                    var loaded = false
                    inst.runOnMainSync { loaded = pet.displayedClipName == clip }
                    loaded
                }
                inst.runOnMainSync {
                    assertTrue(pet.cachedAtlasCount <= CompanionPetDrawable.MAX_ATLASES)
                    assertTrue(pet.cachedBitmapBytes <= 3L * 1280 * 640 * 4)
                }
            }
        } finally {
            inst.runOnMainSync { pet.close() }
            worker.shutdownNow()
        }
    }
    @Test fun allAuthoredSheetsHaveEightTransparentBoundedCells() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val digests = mutableSetOf<String>()
        for (name in PetCels.clips.keys) {
            val bytes = app.assets.open("pet-cels/$name.webp").use { it.readBytes() }
            digests.add(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals(1280, image.width); assertEquals(640, image.height)
                for (frame in 0..7) {
                    val x = frame % 4 * 320; val y = frame / 4 * 320
                    val pixels = IntArray(320 * 320)
                    image.getPixels(pixels, 0, 320, x, y, 320, 320)
                    assertTrue("$name/$frame must contain a complete figure", pixels.count { Color.alpha(it) > 200 } > 10000)
                    for (p in 0 until 320) {
                        assertEquals("$name/$frame top margin", 0, Color.alpha(pixels[p]))
                        assertEquals("$name/$frame bottom margin", 0, Color.alpha(pixels[319 * 320 + p]))
                        assertEquals("$name/$frame left margin", 0, Color.alpha(pixels[p * 320]))
                        assertEquals("$name/$frame right margin", 0, Color.alpha(pixels[p * 320 + 319]))
                    }
                }
            } finally { image.recycle() }
        }
        assertEquals(18, digests.size)
    }
    @Test fun obsoleteDecodesCannotReplaceTheCurrentActionOrResurrectClosedDrawable() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val jobs = java.util.ArrayDeque<Runnable>()
        val executor = Executor { synchronized(jobs) { jobs.add(it) } }
        val pet = CompanionPetDrawable(inst.targetContext.resources, 264, executor)
        try {
            inst.runOnMainSync {
                pet.setPose(PetPose(PetAction.THINK))
                pet.setPose(PetPose(PetAction.WORK))
                pet.setPose(PetPose(PetAction.PAUSED))
            }
            assertEquals("Rapid state changes should queue only one decode", 1, jobs.size)
            repeat(2) {
                val job = synchronized(jobs) { jobs.removeFirst() }
                job.run()
                inst.waitForIdleSync()
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertEquals("paused", pet.displayedClipName)
                assertTrue(pet.cachedAtlasCount <= CompanionPetDrawable.MAX_ATLASES)
                pet.setPose(PetPose(PetAction.REPLY))
                pet.close()
            }
            while (true) {
                val job = synchronized(jobs) { if (jobs.isEmpty()) null else jobs.removeFirst() } ?: break
                job.run()
            }
            inst.waitForIdleSync()
            inst.runOnMainSync { assertEquals(0, pet.cachedAtlasCount) }
        } finally { inst.runOnMainSync { pet.close() } }
    }
}
