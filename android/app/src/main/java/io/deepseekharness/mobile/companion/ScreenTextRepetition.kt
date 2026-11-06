package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure

/** A different call id must not turn the same ineffective input into an unlimited retry loop. */
internal class ScreenTextRepetition {
    private data class Attempt(val screen:String,val count:Int,val at:Long)
    private val attempts=LinkedHashMap<String,Attempt>()
    fun check(key:String,screen:String,now:Long) {
        val previous=attempts[key] ?: return
        if(screen.isNotEmpty() && screen==previous.screen && now-previous.at<120000 && previous.count>=2)
            throw RuntimeFailure("SCREEN_INPUT_REPEATED","相同文字已输入两次且页面无进展，已停止重复输入；请检查焦点、弹窗和实际文本，换一种有证据的方法")
    }
    fun observed(key:String,screen:String,now:Long) {
        val previous=attempts[key]
        val count=if(previous!=null && screen.isNotEmpty() && screen==previous.screen && now-previous.at<120000) previous.count+1 else 1
        attempts[key]=Attempt(screen,count,now)
        while(attempts.size>16) attempts.remove(attempts.keys.first())
    }
}
