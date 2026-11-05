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
                check(response.isSuccessful) { when (response.code) {
                    401, 403 -> "模型凭据无效或访问被拒绝（HTTP ${response.code}），请检查模型设置。"
                    429 -> "模型请求过于频繁（HTTP 429），请稍后重试。"
                    in 500..599 -> "模型服务暂时不可用（HTTP ${response.code}），请稍后重试。"
                    in 300..399 -> "模型接口发生重定向（HTTP ${response.code}），请检查接口配置。"
                    else -> "模型请求失败（HTTP ${response.code}），请检查配置或稍后重试。"
                } }
                // Bound total decoded HTTP bytes, including a single enormous SSE line or JSON body.
                val bounded = object : java.io.FilterInputStream(requireNotNull(response.body).byteStream()) {
                    private var count = 0
                    private fun account(size: Int) { if (size > 0) count += size; check(count <= 512 * 1024) { "模型响应超过大小上限，未保存为完整回复。" } }
                    override fun read(): Int = `in`.read().also { account(if (it < 0) 0 else 1) }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        `in`.read(buffer, offset, minOf(length, 512 * 1024 - count + 1)).also(::account)
                }
                bounded.bufferedReader(Charsets.UTF_8).use(read)
            }.also { PetReplyOperation.checkpoint() }
        } catch (error: java.io.IOException) {
            PetReplyOperation.checkpoint()
            val message = when (error) {
                is java.io.InterruptedIOException -> "模型回复等待超时，原消息已保留，请稍后重试。"
                is java.net.UnknownHostException, is java.net.ConnectException -> "模型服务连接失败，请检查网络后重试。"
                else -> "模型连接中断，回复未完成，原消息已保留。"
            }
            throw IllegalStateException(message, error)
        } finally { cancellation.close() }
    }
}
