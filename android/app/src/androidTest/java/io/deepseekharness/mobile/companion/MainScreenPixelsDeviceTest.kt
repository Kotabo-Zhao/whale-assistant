package io.deepseekharness.mobile.companion

import android.graphics.Color
import io.deepseekharness.mobile.shizuku.MainScreenPixels
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainScreenPixelsDeviceTest {
    private fun raw(width:Int,height:Int,format:Int,pixels:ByteArray)=
        ByteBuffer.allocate(16+pixels.size).order(ByteOrder.nativeOrder()).putInt(width).putInt(height).putInt(format).putInt(0).put(pixels).array()
    @Test fun nativeRgbaColorCropAndGeometryArePreserved() {
        val colors=byteArrayOf(-1,0,0,-1,0,-1,0,-1,0,0,-1,-1,-1,-1,-1,-1)
        MainScreenPixels.read(ByteArrayInputStream(raw(2,2,1,colors)),JSONObject(),1).use{
            assertEquals(Color.RED,it.bitmap.getPixel(0,0));assertEquals(Color.GREEN,it.bitmap.getPixel(1,0))
            assertEquals(Color.BLUE,it.bitmap.getPixel(0,1));assertEquals(Color.WHITE,it.bitmap.getPixel(1,1))
            assertEquals(64,it.frameId.length)
        }
        val rgbx=colors.copyOf().apply{for(i in 3 until size step 4) this[i]=0}
        MainScreenPixels.read(ByteArrayInputStream(raw(2,2,2,rgbx)),JSONObject(),1).use {
            assertEquals(Color.RED,it.bitmap.getPixel(0,0));assertEquals(Color.WHITE,it.bitmap.getPixel(1,1))
        }
        MainScreenPixels.read(ByteArrayInputStream(raw(2,2,1,colors)),JSONObject().put("region",JSONArray(listOf(1,0,1,2))),1).use{
            assertEquals(1,it.region.left);assertEquals(1,it.bitmap.width)
            assertEquals(Color.GREEN,it.bitmap.getPixel(0,0));assertEquals(Color.WHITE,it.bitmap.getPixel(0,1))
        }
    }
    @Test fun oversizedUnsupportedAndIncompleteFramesAreRejectedBeforePublication() {
        for(bytes in listOf(raw(8192,8192,1,byteArrayOf()),raw(2,2,22,byteArrayOf()),raw(2,2,1,byteArrayOf(0)))) {
            assertTrue(runCatching{MainScreenPixels.read(ByteArrayInputStream(bytes),JSONObject(),1).close()}.isFailure)
        }
        MainScreenPixels.read(ByteArrayInputStream(raw(3200,1,1,ByteArray(12800){-1})),JSONObject(),1).use{
            assertEquals(3200,it.width);assertEquals(1600,it.bitmap.width);assertEquals(1,it.bitmap.height)
        }
    }
}
