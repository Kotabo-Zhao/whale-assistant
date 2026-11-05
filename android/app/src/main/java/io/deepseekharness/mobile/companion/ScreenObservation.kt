package io.deepseekharness.mobile.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject

/** Decode only on the bridge worker. Regions use the current screenshot's pixel coordinates. */
internal object ScreenObservation {
    fun text(base64: String, request: JSONObject): JSONObject {
        val bytes = try { android.util.Base64.decode(base64.trim(), android.util.Base64.DEFAULT) }
            catch (_: IllegalArgumentException) { throw RuntimeFailure("SCREEN_IMAGE_INVALID","截图不可用") }
        val source = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            ?: throw RuntimeFailure("SCREEN_IMAGE_INVALID","截图不可用")
        var crop: Bitmap? = null
        try {
            val region = request.optJSONArray("region")
            if(request.has("region") && region == null) throw RuntimeFailure("SCREEN_REGION_INVALID","区域格式无效")
            val image = if (region == null) source else {
                if (region.length() != 4 || (0..3).any { region.opt(it) !is Int })
                    throw RuntimeFailure("SCREEN_REGION_INVALID","区域应为截图像素坐标 x、y、宽、高")
                val x=region.getInt(0); val y=region.getInt(1); val width=region.getInt(2); val height=region.getInt(3)
                if(x<0 || y<0 || width<=0 || height<=0 || x.toLong()+width>source.width || y.toLong()+height>source.height)
                    throw RuntimeFailure("SCREEN_REGION_INVALID","区域超出当前截图")
                Bitmap.createBitmap(source,x,y,width,height).also { crop=it }
            }
            val recognized=WhaleImageText.recognize(image)
            val lines=org.json.JSONArray()
            val dx=region?.getInt(0) ?: 0; val dy=region?.getInt(1) ?: 0
            var remaining=8000
            for(block in recognized.textBlocks) for(line in block.lines) {
                val bounds=line.boundingBox ?: continue
                if(lines.length()>=128 || remaining<=0 || !bounds.intersect(0,0,image.width,image.height)) continue
                val text=line.text.take(minOf(512,remaining)); remaining-=text.length
                lines.put(JSONObject().put("text",text).put("bounds",org.json.JSONArray(listOf(
                    bounds.left+dx,bounds.top+dy,bounds.right+dx,bounds.bottom+dy))))
            }
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return JSONObject().put("width",source.width).put("height",source.height).put("frameId",digest)
                .put("region",region ?: org.json.JSONArray(listOf(0,0,source.width,source.height)))
                .put("ocrText",recognized.text.trim().take(8000)).put("lines",lines)
                .put("observedAtMs",System.currentTimeMillis()).put("coordinateSpace","current_screenshot_pixels")
        } finally { if(crop !== source) crop?.recycle(); source.recycle() }
    }
}
