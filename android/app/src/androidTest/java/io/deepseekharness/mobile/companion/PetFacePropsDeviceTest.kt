package io.deepseekharness.mobile.companion

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.overlay.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/** Complete illustrated actions are inspected at display size; pixel checks do not accept aesthetics. */
class PetFacePropsDeviceTest {
    @Test fun distinctWholeCelsExportAtRealOverlaySizeOnLightAndDark() {
        val inst = InstrumentationRegistry.getInstrumentation(); val app = inst.targetContext
        val out = File(app.getExternalFilesDir(null), "pet-cel-size").apply { mkdirs() }
        val side = (88 * app.resources.displayMetrics.density).toInt()
        val hashes = mutableSetOf<Int>()
        CompanionPetDrawable(app.resources, side, Executor { it.run() }).use { pet ->
            for (dark in listOf(false, true)) {
                val cell = side + 24
                val frame = Bitmap.createBitmap(cell * 5, (side + 44) * 4, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(frame)
                canvas.drawColor(if (dark) 0xff142536.toInt() else 0xffeef3f7.toInt())
                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.WHITE else 0xff163049.toInt(); textSize = 16f }
                PetAction.entries.forEachIndexed { i, action ->
                    val x = i % 5 * cell; val y = i / 5 * (side + 44)
                    pet.prepareForExport(action, 900)
                    assertEquals(PetCels.actionClips.getValue(action), pet.displayedClipName)
                    assertTrue(pet.cachedAtlasCount <= CompanionPetDrawable.MAX_ATLASES)
                    canvas.drawText(action.name, x + 12f, y + 23f, label)
                    pet.setBounds(x + 12, y + 31, x + 12 + side, y + 31 + side)
                    pet.draw(canvas)
                    if (!dark) {
                        val pixels = IntArray(side * side)
                        frame.getPixels(pixels, 0, side, x + 12, y + 31, side, side)
                        hashes.add(pixels.contentHashCode())
                    }
                }
                File(out, if (dark) "actual-88dp-dark.png" else "actual-88dp-light.png").outputStream().use {
                    frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                frame.recycle()
            }
            assertEquals("Every state must use its own whole-character drawing", 17, hashes.size)
            inst.sendStatus(0, android.os.Bundle().apply {
                putInt("actual88dpPixels", side); putInt("distinctStateCels", hashes.size)
                putLong("cachedBitmapBytes", pet.cachedBitmapBytes)
            })
        }
    }
}
