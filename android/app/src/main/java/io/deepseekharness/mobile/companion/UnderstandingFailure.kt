package io.deepseekharness.mobile.companion

/**
 * Shared failure vocabulary for notification understanding and saved-material jobs.
 * The code is persisted, while the text is deliberately local and contains no model response.
 */
internal object UnderstandingFailure {
    fun code(error: Throwable): String = when (error) {
        is UnderstandingRequestException -> error.code
        is java.net.SocketTimeoutException -> "timeout"
        is java.io.IOException -> "network"
        is org.json.JSONException, is IllegalArgumentException -> "invalid_model_output"
        else -> "processing_failed"
    }

    fun retryDelayMs(code: String): Long = if (code in setOf("credentials", "configuration", "request_rejected")) {
        6 * 60 * 60_000L
    } else {
        30 * 60_000L
    }

    fun summary(code: String): String = when (code) {
        "credentials" -> "模型凭据不可用或被拒绝"
        "configuration" -> "后台模型尚未配置"
        "rate_limited" -> "模型服务限流"
        "service_unavailable" -> "模型服务暂不可用"
        "request_rejected" -> "模型服务拒绝请求，请检查模型兼容性"
        "timeout" -> "请求超时"
        "network" -> "网络不可用"
        "invalid_model_output" -> "模型返回的数据不符合要求"
        else -> "处理未完成"
    }
}
