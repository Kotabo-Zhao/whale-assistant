package io.deepseekharness.mobile.companion

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/** Call deadline includes headers and SSE heartbeats, unlike an individual socket read timeout. */
internal object PetModelHttp {
    internal class HttpFailure(val code: Int, val retryAfter: String?, message: String) : IllegalStateException(message) {
        val temporary get() = code in setOf(500,502,503,504)
    }
    private val client = okhttp3.OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp otherwise replays a 503 with Retry-After: 0 inside a single Call.
            if (response.code == 503 && response.header("Retry-After")?.toLongOrNull() == 0L)
                response.newBuilder().header("Retry-After","1").build() else response
        }
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun <T> post(key: String, body: String, timeoutMillis: Long, retryRejected: Boolean = true, read: (BufferedReader) -> T): T {
        val request = okhttp3.Request.Builder().url("https://api.deepseek.com/chat/completions")
            .header("Authorization","Bearer $key").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return if (retryRejected) inference(request,timeoutMillis,read) else execute(request,timeoutMillis,read)
    }
    /** Only an inference rejected at the HTTP boundary is eligible. No stream or tool has started. */
    internal fun <T> inference(request: okhttp3.Request, timeoutMillis: Long, read: (BufferedReader) -> T): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try { return execute(request,timeoutMillis,read) } catch (error: HttpFailure) {
            if (!error.temporary) throw error
            val delay = error.retryAfter?.let { value ->
                val seconds = value.trim().toLongOrNull() ?: throw error
                if (seconds !in 0L..3L) throw error
                seconds * 1000L
            } ?: 750L
            if (TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()) <= delay + 250) throw error
            PetReplyOperation.checkpoint()
            Thread.sleep(delay)
            PetReplyOperation.checkpoint()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
            if (remaining <= 0) throw error
            return execute(request,remaining,read)
        }
    }
    internal fun <T> execute(request: okhttp3.Request, timeoutMillis: Long, read: (BufferedReader) -> T): T {
        PetReplyOperation.checkpoint()
        val call=client.newCall(request)
        call.timeout().timeout(timeoutMillis,TimeUnit.MILLISECONDS)
        val cancellation=PetReplyOperation.bind(call::cancel)
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) throw HttpFailure(response.code,response.header("Retry-After"),when (response.code) {
                    401, 403 -> "模型凭据无效或访问被拒绝（HTTP ${response.code}），请检查模型设置。"
                    429 -> "模型请求过于频繁（HTTP 429），请稍后重试。"
                    in 500..599 -> "模型服务暂时不可用（HTTP ${response.code}），请稍后重试。"
                    in 300..399 -> "模型接口发生重定向（HTTP ${response.code}），请检查接口配置。"
                    else -> "模型请求失败（HTTP ${response.code}），请检查配置或稍后重试。"
                })
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
