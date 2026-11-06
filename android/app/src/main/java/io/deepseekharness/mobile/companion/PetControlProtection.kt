package io.deepseekharness.mobile.companion

import android.graphics.Rect
import android.view.View

/** Published on layout/visibility changes, read without touching Views from the device executor. */
internal object PetControlProtection {
    private val regions = LinkedHashMap<String, Rect>()
    @Synchronized fun update(key: String, view: View?) {
        val rect=Rect()
        if (view != null && view.isAttachedToWindow && view.visibility == View.VISIBLE && view.getGlobalVisibleRect(rect)) {
            val xy=IntArray(2); view.getLocationOnScreen(xy)
            regions[key]=Rect(xy[0],xy[1],xy[0]+view.width,xy[1]+view.height)
        } else regions.remove(key)
    }
    @Synchronized fun bounds(): List<Rect> = regions.values.map(::Rect)
    @Synchronized fun intersects(x: Int, y: Int, endX: Int=x, endY: Int=y): Boolean =
        regions.values.any { it.intersects(minOf(x,endX),minOf(y,endY),maxOf(x,endX)+1,maxOf(y,endY)+1) }
}
