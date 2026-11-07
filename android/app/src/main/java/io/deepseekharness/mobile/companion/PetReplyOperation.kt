package io.deepseekharness.mobile.companion

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Cancels only the foreground wait; a submitted Harness task keeps its original request ID. */
internal class PetReplyOperation(private val limitMillis: Long = 180_000) {
    private val callbacks = ConcurrentHashMap.newKeySet<() -> Unit>()
    @Volatile private var reason: String? = null
    @Volatile private var runner: Thread? = null
    private var finishing = false
    fun cancel(message: String = "回复等待已结束，原消息已保留。") {
        synchronized(this) { if (reason != null || finishing) return; reason = message; runner?.interrupt() }
        callbacks.forEach { callback -> cancellation.execute { runCatching(callback) } }
    }
    fun check() { reason?.let { throw java.io.InterruptedIOException(it) } }
    fun onCancel(callback: () -> Unit): AutoCloseable {
        callbacks.add(callback)
        if (reason != null) cancellation.execute { runCatching(callback) }
        return AutoCloseable { callbacks.remove(callback) }
    }
    /** Deliver cancellation even when a provider is still unwinding a blocking native call. */
    fun <T> submit(executor: java.util.concurrent.Executor, work: () -> T, finished: (Result<T>) -> Unit) {
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(result: Result<T>) { if (delivered.compareAndSet(false,true)) finished(result) }
        val subscription = onCancel { finish(Result.failure(java.io.InterruptedIOException(reason))) }
        try {
            executor.execute {
                try { finish(runCatching { run(work) }) } finally { subscription.close() }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            subscription.close()
            finish(Result.failure(IllegalStateException("回复线程繁忙，请稍后重试。",error)))
        }
    }
    fun <T> run(block: () -> T): T {
        synchronized(this) { runner = Thread.currentThread() }
        current.set(this)
        val deadline = timer.schedule({ cancel("回复等待超时，原消息已保留；后台任务可到主对话查看。") }, limitMillis, TimeUnit.MILLISECONDS)
        try { check(); return block().also { check() } }
        catch (failure: Exception) { check(); throw failure }
        finally {
            deadline.cancel(false); current.remove()
            synchronized(this) { runner = null; finishing = true }
            callbacks.clear()
            // This executor is reused for later messages; cancellation must not poison the next turn.
            Thread.interrupted()
        }
    }
    companion object {
        private val current = ThreadLocal<PetReplyOperation>()
        private val timer = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable,"pet-reply-deadline").apply { isDaemon=true } }.apply { removeOnCancelPolicy=true }
        private val cancellation = java.util.concurrent.Executors.newCachedThreadPool { runnable -> Thread(runnable,"pet-reply-cancel").apply { isDaemon=true } }
        fun checkpoint() { current.get()?.check() }
        fun <T> whileActive(block: () -> T): T {
            val operation = current.get() ?: return block()
            return synchronized(operation) { operation.check(); block() }
        }
        fun bind(callback: () -> Unit): AutoCloseable = current.get()?.onCancel(callback) ?: AutoCloseable { }
        /** Network work is over. Once persistence starts, a late cancel must not turn a saved
         * reply into a retryable failure and cause the same user action to be submitted again. */
        fun <T> commitReply(block: () -> T): T {
            current.get()?.let { operation ->
                synchronized(operation) { operation.check(); operation.finishing = true }
            }
            return block()
        }
    }
}
