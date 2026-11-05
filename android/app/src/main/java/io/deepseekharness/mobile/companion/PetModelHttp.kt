package io.deepseekharness.mobile.companion

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/** Call deadline includes headers and SSE heartbeats, unlike an individual socket read timeout. */
internal object PetModelHttp {
    private val client = okhttp3.OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun <T> post(key: String, body: String, timeoutMillis: Long, read: (BufferedReader) -> T): T = execute(
        okhttp3.Request.Builder().url("https://api.deepseek.com/chat/completions")
            .header("Authorization","Bearer $key").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build(), timeoutMillis,read)
    internal fun <T> execute(request: okhttp3.Request, timeoutMillis: Long, read: (BufferedReader) -> T): T {
        PetReplyOperation.checkpoint()
        val call=client.newCall(request)
        call.timeout().timeout(timeoutMillis,TimeUnit.MILLISECONDS)
        val cancellation=PetReplyOperation.bind(call::cancel)
        try {
            return call.execute().use { response ->
                check(response.isSuccessful) { "模型请求失败（HTTP ${response.code}），请检查配置或稍后重试。" }
                requireNotNull(response.body).charStream().buffered().use(read)
            }.also { PetReplyOperation.checkpoint() }
        } finally { cancellation.close() }
    }
}
