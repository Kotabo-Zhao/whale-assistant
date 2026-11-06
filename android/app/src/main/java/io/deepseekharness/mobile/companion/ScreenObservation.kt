package io.deepseekharness.mobile.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.shizuku.MainScreenPixels
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.ceil

/** The image may be cropped or sampled; every returned bound stays in physical screen pixels. */
internal object ScreenObservation {
    fun text(base64:String,request:JSONObject):JSONObject {
        val bytes=try{android.util.Base64.decode(base64.trim(),android.util.Base64.DEFAULT)}
            catch(_:IllegalArgumentException){throw RuntimeFailure("SCREEN_IMAGE_INVALID","截图不可用")}
        val source=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            ?: throw RuntimeFailure("SCREEN_IMAGE_INVALID","截图不可用")
        var crop:Bitmap?=null
        try {
            val region=MainScreenPixels.region(request,source.width,source.height)
            val image=if(region==Rect(0,0,source.width,source.height)) source else
                Bitmap.createBitmap(source,region.left,region.top,region.width(),region.height()).also{crop=it}
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            return recognize(image,source.width,source.height,region,digest)
        } finally {crop?.recycle();source.recycle()}
    }
    fun text(frame:MainScreenPixels):JSONObject =
        recognize(frame.bitmap,frame.width,frame.height,frame.region,frame.frameId)
            .put("capturedAtMs",frame.capturedAtMs).put("captureRequestedAtMs",frame.capturedAtMs)
            .put("captureSource","shizuku_raw_pipe").put("decodedWidth",frame.bitmap.width).put("decodedHeight",frame.bitmap.height)

    private fun recognize(image:Bitmap,width:Int,height:Int,region:Rect,frameId:String):JSONObject {
        val started=android.os.SystemClock.elapsedRealtime()
        val recognized=WhaleImageText.recognize(image)
        val lines=JSONArray()
        val sx=region.width().toDouble()/image.width;val sy=region.height().toDouble()/image.height
        var remaining=8000
        for(block in recognized.textBlocks) for(line in block.lines) {
            val bounds=line.boundingBox ?: continue
            if(lines.length()>=128 || remaining<=0 || !bounds.intersect(0,0,image.width,image.height)) continue
            val text=line.text.take(minOf(512,remaining));remaining-=text.length
            lines.put(JSONObject().put("text",text).put("bounds",JSONArray(listOf(
                region.left+floor(bounds.left*sx).toInt(),region.top+floor(bounds.top*sy).toInt(),
                minOf(region.right,region.left+ceil(bounds.right*sx).toInt()),
                minOf(region.bottom,region.top+ceil(bounds.bottom*sy).toInt())))))
        }
        return JSONObject().put("width",width).put("height",height).put("frameId",frameId)
            .put("region",JSONArray(listOf(region.left,region.top,region.width(),region.height())))
            .put("ocrText",recognized.text.trim().take(8000)).put("lines",lines)
            .put("ocrMs",android.os.SystemClock.elapsedRealtime()-started)
            .put("observedAtMs",System.currentTimeMillis()).put("coordinateSpace","current_screenshot_pixels")
    }
}
