package io.deepseekharness.mobile.companion

/** Small color samples detect transitions without running OCR on intermediate animation frames. */
internal object ScreenFrameStability {
    fun sample(width:Int,height:Int,pixel:(Int,Int)->Int):IntArray = IntArray(32*56) { i ->
        pixel(((i%32+.5)*width/32).toInt().coerceIn(0,width-1),((i/32+.5)*height/56).toInt().coerceIn(0,height-1))
    }
    fun similar(first:IntArray,second:IntArray):Boolean {
        if(first.isEmpty() || first.size!=second.size) return false
        var changed=0
        for(i in first.indices) {
            val a=first[i];val b=second[i]
            val delta=kotlin.math.abs((a shr 16 and 255)-(b shr 16 and 255))+
                kotlin.math.abs((a shr 8 and 255)-(b shr 8 and 255))+kotlin.math.abs((a and 255)-(b and 255))
            if(delta>36 && ++changed*100>first.size*2) return false
        }
        return true
    }
}
