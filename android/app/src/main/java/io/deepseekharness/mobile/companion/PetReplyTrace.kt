package io.deepseekharness.mobile.companion

/** Fixed stage names and coarse error categories; never records prompts, model output or exceptions. */
internal class PetReplyTrace(
    private val record: (Map<String,String>) -> Unit,
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    enum class Stage(val field: String) { ROUTE("route"), REPLY("reply"), HANDOFF("handoff"), SUBMIT("submit") }

    fun <T> measure(stage: Stage, action: () -> T): T {
        val started = now()
        fun emit(result: String, code: String? = null) {
            val fields = mutableMapOf("phase" to stage.field,"result" to result,"elapsed_ms" to (now()-started).coerceAtLeast(0).toString())
            code?.let { fields["code"] = it }
            runCatching { record(fields) } // Diagnostic storage must never interrupt a reply.
        }
        emit("started")
        return try { action().also { emit("ok") } }
        catch (error: Throwable) {
            val code = when (error) {
                is java.net.SocketTimeoutException -> "NETWORK_TIMEOUT"
                is java.net.UnknownHostException -> "NETWORK_DNS"
                is InterruptedException, is java.io.InterruptedIOException -> "INTERRUPTED"
                is java.io.IOException -> "NETWORK_IO"
                else -> "REPLY_FAILED"
            }
            emit(if (code == "INTERRUPTED") "cancelled" else "failed",code)
            throw error
        }
    }

    companion object {
        fun from(context: android.content.Context): PetReplyTrace {
            val log = io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLog(context)
            return PetReplyTrace({ fields -> log.record(
                io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel.INFO,
                io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent.PET_REPLY_STAGE,fields) })
        }
    }
}
