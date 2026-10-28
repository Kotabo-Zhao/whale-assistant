package io.deepseekharness.mobile.accessibility

/** Shares the task marker lock with plan/cancel so rules cannot begin during a task transition. */
internal object AutomationRuleTaskArbiter {
    fun <T : Any> runIfIdle(pending: () -> Boolean, action: () -> T): T? =
        synchronized(AutomationTaskInterruptionStore.lock) { if (pending()) null else action() }
}

/** Callbacks may arrive after a later gesture was started; only the matching token can release it. */
internal class AutomationRuleGestureTracker {
    private var next = 0L
    private var active: Long? = null
    fun begin(): Long? = synchronized(AutomationTaskInterruptionStore.lock) {
        if (active != null) return@synchronized null
        val token = ++next
        active = token
        token
    }
    fun finish(token: Long) = synchronized(AutomationTaskInterruptionStore.lock) {
        if (active == token) active = null
    }
    fun inFlight(): Boolean = synchronized(AutomationTaskInterruptionStore.lock) { active != null }
}
