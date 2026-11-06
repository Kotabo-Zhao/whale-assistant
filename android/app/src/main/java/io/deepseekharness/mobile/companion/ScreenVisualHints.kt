package io.deepseekharness.mobile.companion

/** Read a visible underline, not an accessibility-selected state or a business outcome. */
internal object ScreenVisualHints {
    data class Box(val left:Int,val top:Int,val right:Int,val bottom:Int)
    fun underline(width:Int,height:Int,box:Box,pixel:(Int,Int)->Int):Box? {
        val h=box.bottom-box.top;val w=box.right-box.left
        if(h<8 || w<8 || box.bottom>height/3) return null
        val x=(box.left+box.right)/2
        fun distance(a:Int,b:Int)=kotlin.math.abs((a shr 16 and 255)-(b shr 16 and 255))+
            kotlin.math.abs((a shr 8 and 255)-(b shr 8 and 255))+kotlin.math.abs((a and 255)-(b and 255))
        val limit=minOf(height-12,box.bottom+h*2)
        for(y in box.bottom+2 until limit) {
            val color=pixel(x,y)
            // Thin, high-contrast flat stroke; broad cards and full-width dividers are not hints.
            if(distance(color,pixel(x,y+10))<240) continue
            var left=x;var right=x
            while(left>0 && distance(color,pixel(left-1,y))<55) left--
            while(right<width-1 && distance(color,pixel(right+1,y))<55) right++
            if(right-left<w*3/2 || right-left>width*3/5 || left>=box.left || right<=box.right) continue
            var top=y;var bottom=y
            while(top>box.bottom && distance(color,pixel(x,top-1))<55) top--
            while(bottom<height-1 && bottom-y<12 && distance(color,pixel(x,bottom+1))<55) bottom++
            if(bottom-top !in 1..8) continue
            return Box(left,top,right+1,bottom+1)
        }
        return null
    }
}
