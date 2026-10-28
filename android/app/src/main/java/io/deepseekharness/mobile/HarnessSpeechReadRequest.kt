package io.deepseekharness.mobile

/** Main-thread ownership of one WebView read; timeout and response are mutually exclusive. */
internal class HarnessSpeechReadRequest(private val generation: Long) {
    private var pending = true
    fun claim(currentGeneration: Long): Boolean {
        if (!pending || generation != currentGeneration) return false
        pending = false
        return true
    }
}
