package io.deepseekharness.mobile

/** Handoff is valid only for the same visible, authorized page and a short-lived request. */
internal object HarnessDraftHandoffPolicy {
    fun canDeliver(visible: Boolean, authorized: Boolean, sameGate: Boolean, samePage: Boolean, ageMs: Long): Boolean =
        visible && authorized && sameGate && samePage && ageMs in 0..120_000
}
