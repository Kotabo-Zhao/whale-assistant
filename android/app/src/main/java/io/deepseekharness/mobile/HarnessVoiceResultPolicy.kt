package io.deepseekharness.mobile

/** Voice recognition is never sent automatically; a changed page must not consume the text. */
internal object HarnessVoiceResultPolicy {
    enum class Decision { CANCELLED, INVALID, REVIEW, COPY }
    fun canCopy(text: String): Boolean = text.isNotBlank() && text.length <= 10_000
    fun canInsert(text: String): Boolean = text.isNotBlank() && text.length <= 2_000
    fun decide(resultOk: Boolean, text: String, sameAuthorizedVisiblePage: Boolean): Decision = when {
        !resultOk -> Decision.CANCELLED
        !canCopy(text) -> Decision.INVALID
        !sameAuthorizedVisiblePage -> Decision.COPY
        else -> Decision.REVIEW
    }
}
