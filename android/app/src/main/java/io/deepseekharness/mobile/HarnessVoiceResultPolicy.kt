package io.deepseekharness.mobile

/** Voice recognition is never sent automatically; a changed page must not consume the text. */
internal object HarnessVoiceResultPolicy {
    enum class Decision { CANCELLED, INVALID, REVIEW, COPY }
    fun decide(resultOk: Boolean, text: String, sameAuthorizedVisiblePage: Boolean): Decision = when {
        !resultOk -> Decision.CANCELLED
        text.isBlank() || text.length > 10000 -> Decision.INVALID
        text.length > 2000 || !sameAuthorizedVisiblePage -> Decision.COPY
        else -> Decision.REVIEW
    }
}
