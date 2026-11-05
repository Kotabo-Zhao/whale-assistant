package io.deepseekharness.mobile.companion

/** Persist only a fixed category, never exception text, local paths or model responses. */
internal enum class WhaleParseFailure {
    MODEL_REQUIRED, UNSUPPORTED_FORMAT, EMPTY_CONTENT, NETWORK_TIMEOUT, INTERRUPTED, PARSE_FAILED;

    class Error(val reason: WhaleParseFailure) : IllegalStateException(reason.name)
    companion object {
        fun from(error: Throwable?): WhaleParseFailure = when (error) {
            null -> EMPTY_CONTENT
            is Error -> error.reason
            is java.net.SocketTimeoutException -> NETWORK_TIMEOUT
            else -> PARSE_FAILED
        }
    }
}
