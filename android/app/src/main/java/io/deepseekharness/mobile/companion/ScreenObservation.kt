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
        val controlBounds=DeviceControlOverlay.observationBounds()
        val recognized=WhaleImageText.recognize(image)
        val lines=JSONArray()
        val sx=region.width().toDouble()/image.width;val sy=region.height().toDouble()/image.height
        var remaining=8000
        fun physical(bounds:Rect)=JSONArray(listOf(
            region.left+floor(bounds.left*sx).toInt(),region.top+floor(bounds.top*sy).toInt(),
            minOf(region.right,region.left+ceil(bounds.right*sx).toInt()),
            minOf(region.bottom,region.top+ceil(bounds.bottom*sy).toInt())))
        fun visualGap(previous:Rect,next:Rect,box:Rect):Boolean {
            val start=maxOf(box.left,previous.centerX());val end=minOf(box.right,next.centerX())
            val needed=maxOf(4,box.height()/4)
            if(end-start<needed || box.height()>180 || box.width()>1200) return false
            val colors=listOf(image.getPixel(box.left,box.top),image.getPixel(box.right-1,box.top),
                image.getPixel(box.left,box.bottom-1),image.getPixel(box.right-1,box.bottom-1))
            val bg=colors.groupingBy{it}.eachCount().maxByOrNull{it.value}!!.key
            var clear=0
            for(x in start until end) {
                var ink=0
                for(y in box.top until box.bottom) {
                    val pixel=image.getPixel(x,y)
                    val delta=kotlin.math.abs(android.graphics.Color.red(pixel)-android.graphics.Color.red(bg))+
                        kotlin.math.abs(android.graphics.Color.green(pixel)-android.graphics.Color.green(bg))+
                        kotlin.math.abs(android.graphics.Color.blue(pixel)-android.graphics.Color.blue(bg))
                    if(delta>100 && ++ink>1) break
                }
                clear=if(ink<=1) clear+1 else 0
                if(clear>=needed) return true
            }
            return false
        }
        for(block in recognized.textBlocks) for(line in block.lines) {
            val bounds=line.boundingBox ?: continue
            if(lines.length()>=128 || remaining<=0 || !bounds.intersect(0,0,image.width,image.height)) continue
            val text=line.text.take(minOf(512,remaining));remaining-=text.length
            val row=JSONObject().put("text",text).put("bounds",physical(bounds))
            val physicalBounds=row.getJSONArray("bounds")
            if(controlBounds?.contains((physicalBounds.getInt(0)+physicalBounds.getInt(2))/2,
                (physicalBounds.getInt(1)+physicalBounds.getInt(3))/2)==true) continue
            if(text.length<=20) ScreenVisualHints.underline(image.width,image.height,
                ScreenVisualHints.Box(bounds.left,bounds.top,bounds.right,bounds.bottom),image::getPixel)?.let {
                row.put("visualSelectionHint","underline").put("indicatorBounds",physical(Rect(it.left,it.top,it.right,it.bottom)))
            }
            val elements=JSONArray()
            for(element in line.elements.take(48)) {
                val box=element.boundingBox ?: continue
                if(!box.intersect(0,0,image.width,image.height)) continue
                // Icons are sometimes read as a leading Chinese character. Split only at a
                // real visual gap, never by deleting a guessed prefix or matching arbitrary substrings.
                val symbols=element.symbols.mapNotNull{s->s.boundingBox?.let{b->s.text to b}}
                val groups=ArrayList<Pair<String,Rect>>()
                var previous:Rect?=null
                for((label,b) in symbols) {
                    if(!b.intersect(0,0,image.width,image.height)) continue
                    val prior=groups.lastOrNull()
                    val separated=prior==null || b.left-prior.second.right>maxOf(3,minOf(b.height(),prior.second.height())/4) ||
                        previous?.let{visualGap(it,b,box)}==true
                    if(separated) groups.add(label to Rect(b))
                    else groups[groups.lastIndex]=(prior!!.first+label) to Rect(prior.second).apply{union(b)}
                    previous=b
                }
                if(groups.size>1) for((label,b) in groups.take(48))
                    elements.put(JSONObject().put("text",label.take(80)).put("bounds",physical(b)))
                else elements.put(JSONObject().put("text",element.text.take(80)).put("bounds",physical(box)))
            }
            if(elements.length()>1) row.put("elements",elements)
            lines.put(row)
        }
        return JSONObject().put("width",width).put("height",height).put("frameId",frameId)
            .put("region",JSONArray(listOf(region.left,region.top,region.width(),region.height())))
            .put("ocrText",(0 until lines.length()).joinToString("\n"){lines.getJSONObject(it).getString("text")}.take(8000)).put("lines",lines)
            .apply { if(controlBounds!=null) put("excludedControlOverlay",JSONObject()
                .put("owner","io.deepseekharness.mobile").put("role","screen_control_pause_stop")
                .put("bounds",JSONArray(listOf(controlBounds.left,controlBounds.top,controlBounds.right,controlBounds.bottom)))) }
            .put("ocrMs",android.os.SystemClock.elapsedRealtime()-started)
            .put("observedAtMs",System.currentTimeMillis()).put("coordinateSpace","current_screenshot_pixels")
    }
}
