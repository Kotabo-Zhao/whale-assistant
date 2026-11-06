package io.deepseekharness.mobile.shizuku

import android.graphics.Bitmap
import android.graphics.Rect
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Raw screencap framing on Android 10+: native-endian width, height, format, dataspace. */
internal class MainScreenPixels(
    val bitmap:Bitmap,val width:Int,val height:Int,val region:Rect,val frameId:String,val capturedAtMs:Long,
):java.io.Closeable {
    override fun close()=bitmap.recycle()
    companion object {
        fun region(request:JSONObject,width:Int,height:Int):Rect {
            if(!request.has("region")) return Rect(0,0,width,height)
            val r=request.optJSONArray("region") ?: throw RuntimeFailure("SCREEN_REGION_INVALID","区域格式无效")
            if(r.length()!=4 || (0..3).any{r.opt(it) !is Int}) throw RuntimeFailure("SCREEN_REGION_INVALID","区域需要四个整数")
            val x=r.getInt(0);val y=r.getInt(1);val w=r.getInt(2);val h=r.getInt(3)
            if(x<0 || y<0 || w<=0 || h<=0 || x.toLong()+w>width || y.toLong()+h>height)
                throw RuntimeFailure("SCREEN_REGION_INVALID","区域超出当前屏幕")
            return Rect(x,y,x+w,y+h)
        }
        fun read(input:InputStream,request:JSONObject,capturedAt:Long):MainScreenPixels {
            val digest=MessageDigest.getInstance("SHA-256")
            fun readFully(bytes:ByteArray) {
                var offset=0
                while(offset<bytes.size) {
                    val count=input.read(bytes,offset,bytes.size-offset)
                    if(count<0) throw java.io.EOFException("SCREEN_RAW_INCOMPLETE")
                    if(count>0){digest.update(bytes,offset,count);offset+=count}
                }
            }
            val header=ByteArray(16);readFully(header)
            val h=ByteBuffer.wrap(header).order(ByteOrder.nativeOrder())
            val width=h.int;val height=h.int;val format=h.int;h.int
            if(width !in 1..8192 || height !in 1..8192 || width.toLong()*height>20_000_000 || format !in setOf(1,2))
                throw RuntimeFailure("SCREEN_RAW_UNSUPPORTED","系统原始截图格式暂不支持")
            val region=region(request,width,height)
            val ratio=minOf(1f,1600f/maxOf(region.width(),region.height()))
            val targetWidth=(region.width()*ratio).roundToInt().coerceAtLeast(1)
            val targetHeight=(region.height()*ratio).roundToInt().coerceAtLeast(1)
            val pixels=ByteArray(targetWidth*targetHeight*4)
            val row=ByteArray(width*4)
            var targetY=0
            for(y in 0 until height) {
                readFully(row)
                while(targetY<targetHeight && y==region.top+targetY*region.height()/targetHeight) {
                    if(targetWidth==region.width() && format==1) {
                        System.arraycopy(row,region.left*4,pixels,targetY*targetWidth*4,targetWidth*4)
                    } else for(x in 0 until targetWidth) {
                        val source=(region.left+x*region.width()/targetWidth)*4
                        val dest=(targetY*targetWidth+x)*4
                        pixels[dest]=row[source];pixels[dest+1]=row[source+1];pixels[dest+2]=row[source+2]
                        pixels[dest+3]=if(format==2) 0xff.toByte() else row[source+3]
                    }
                    targetY++
                }
            }
            if(targetY!=targetHeight) throw RuntimeFailure("SCREEN_RAW_INCOMPLETE","截图数据不完整")
            val bitmap=Bitmap.createBitmap(targetWidth,targetHeight,Bitmap.Config.ARGB_8888)
            try {bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))}
            catch(error:Throwable){bitmap.recycle();throw error}
            return MainScreenPixels(bitmap,width,height,region,digest.digest().joinToString(""){"%02x".format(it)},capturedAt)
        }
    }
}
