package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单应用任务台账的边界用例。
 *
 * 这里锁的是**分类口径**而不是实现细节：什么情况算「可重试」、什么情况必须记成「不确定」、
 * 目标变了之后还能不能发。这些边界在真机上要靠「制造超时 / 换应用 / 断服务」才能碰一次，
 * 所以它们必须在 JVM 上逐个钉住。
 */
class AutomationTaskLedgerTest {

    private fun spec(
        id: String,
        action: String = AutomationTaskActions.CLICK,
        target: String = "com.example.app:id/ok",
        verify: String = AutomationTaskActions.defaultVerify(action),
        timeoutMs: Long = 5_000L,
        maxAttempts: Int = 1,
    ) = AutomationTaskStepSpec(id, action, target, verify, timeoutMs, maxAttempts)

    private fun facts(
        packageName: String = "com.example.app",
        digest: String = "d1",
        locked: Boolean = false,
        sensitive: Boolean = false,
        allowed: Boolean = true,
        connected: Boolean = true,
        manualPreempted: Boolean = false,
    ) = AutomationTaskFacts(packageName, digest, locked, sensitive, allowed, connected, manualPreempted)

    private fun ledger(vararg steps: AutomationTaskStepSpec, digest: String? = "d1"): AutomationTaskLedger {
        val ledger = AutomationTaskLedger()
        val gate = ledger.plan("task-1", "com.example.app", digest, steps.toList())
        assertTrue("计划应当被接受：$gate", gate is AutomationTaskGate.Proceed)
        return ledger
    }

    private fun AutomationTaskGate.refusedCode(): String {
        assertTrue("这一步本应被拒：$this", this is AutomationTaskGate.Refused)
        return (this as AutomationTaskGate.Refused).code
    }

    private fun AutomationTaskGate.attempt(): Int {
        assertTrue("这一步本应放行：$this", this is AutomationTaskGate.Proceed)
        return (this as AutomationTaskGate.Proceed).attempt
    }

    @Test
    fun `计划校验把不合法的步骤逐条挡住`() {
        val seen = mutableSetOf<String>()
        assertNull(AutomationTaskPlan.validate(spec("s1"), seen))
        assertTrue(AutomationTaskPlan.validate(spec("S1"), seen)!!.contains("标识不合法"))
        assertTrue(AutomationTaskPlan.validate(spec("s2", action = "swipe"), seen)!!.contains("动作不受支持"))
        assertTrue(AutomationTaskPlan.validate(spec("s3", verify = "targetGone", action = AutomationTaskActions.SCROLL), seen)!!.contains("校验模式"))
        assertTrue(AutomationTaskPlan.validate(spec("s4", target = ""), seen)!!.contains("缺少目标"))
        assertTrue(AutomationTaskPlan.validate(spec("s5", timeoutMs = 50L), seen)!!.contains("超时"))
        assertTrue(AutomationTaskPlan.validate(spec("s6", maxAttempts = 4), seen)!!.contains("重试上限"))
        assertTrue(AutomationTaskPlan.validate(spec("s7", action = AutomationTaskActions.WAIT, target = "x"), seen)!!.contains("不接受目标"))
        assertTrue(AutomationTaskPlan.validate(spec("s1"), mutableSetOf("s1"))!!.contains("重复"))
    }

    @Test
    fun `两步都通过校验时任务收口为完成`() {
        val ledger = ledger(spec("s1"), spec("s2", action = AutomationTaskActions.WAIT, target = ""))
        assertEquals(1, ledger.gate("s1", facts(), 1_000L).attempt())
        ledger.record("s1", AutomationTaskStepOutcome(ok = true, verified = true), 1_200L)
        assertTrue(ledger.gate("s2", facts(), 1_300L) is AutomationTaskGate.Proceed)
        val snapshot = ledger.record("s2", AutomationTaskStepOutcome(ok = true), 1_500L)
        assertEquals(AutomationTaskStatus.DONE, snapshot.status)
        assertEquals(listOf(AutomationTaskStepStatus.OK, AutomationTaskStepStatus.OK), snapshot.steps.map { it.status })
        assertNull(snapshot.nextStepId)
        assertEquals(2, snapshot.settledSteps)
        assertEquals(0, snapshot.pendingSteps)
    }

    @Test
    fun `同一步在途时重复 gate 被 busy 拦下且不增加尝试次数`() {
        val ledger = ledger(spec("s1", maxAttempts = 2))
        assertEquals(1, ledger.gate("s1", facts(), 1_000L).attempt())
        assertEquals(AutomationTaskCodes.BUSY, ledger.gate("s1", facts(), 1_100L).refusedCode())
        assertEquals(1, ledger.snapshot().steps.single().attemptsUsed)
        assertEquals(1_000L, ledger.snapshot().steps.single().startedAtMs)
    }

    @Test
    fun `跳过前一步直接做后一步会被顺序闸门挡住`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        val gate = ledger.gate("s2", facts(), 1_000L)
        assertEquals(AutomationTaskCodes.STEP_OUT_OF_ORDER, gate.refusedCode())
        assertTrue((gate as AutomationTaskGate.Refused).message.contains("s1"))
    }

    @Test
    fun `前一步未验证不能跳到下一步`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = true, verified = false), 1100L)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s2", facts(), 1200L).refusedCode())
        assertEquals(0, ledger.snapshot().steps[1].attemptsUsed)
    }

    @Test
    fun `缺少验证字段的成功回执不能证明点击完成`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        val parsed = AutomationTaskWire.parse("""{"op":"record","step":"s1","attempt":1,"status":"ok"}""") as AutomationTaskRequest.Record
        val snapshot = ledger.record(parsed.stepId, parsed.attempt, parsed.outcome, 1100L)
        assertEquals(AutomationTaskStepStatus.UNKNOWN, snapshot.steps[0].status)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s2", facts(), 1200L).refusedCode())
    }

    @Test
    fun `窗口变化验证要求同任务同目标的真实动作回执`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        val snapshot = ledger.snapshot()
        val step = snapshot.steps[0]
        val claimed = AutomationTaskStepOutcome(ok = true, verified = true)
        val receipt = AutomationTaskEvidence.receipt(snapshot, "com.example.app", "click", "com.example.app:id/ok", 1100L)
        assertTrue(receipt != null)
        assertNull(AutomationTaskEvidence.receipt(snapshot, "com.other.app", "click", "com.example.app:id/ok", 1100L))
        assertNull(AutomationTaskEvidence.receipt(snapshot, "com.example.app", "setText", "com.example.app:id/ok", 1100L))
        assertNull(AutomationTaskEvidence.receipt(snapshot, "com.example.app", "click", "com.example.app:id/other", 1100L))
        assertFalse(AutomationTaskEvidence.constrain(snapshot.taskId, step, claimed, "d1", receipt, 1200L).verified)
        assertFalse(AutomationTaskEvidence.constrain(snapshot.taskId, step, claimed, "d2", null, 1200L).verified)
        assertFalse(AutomationTaskEvidence.constrain("other-task", step, claimed, "d2", receipt, 1200L).verified)
        assertFalse(AutomationTaskEvidence.constrain(snapshot.taskId, step.copy(attemptsUsed = 2), claimed, "d2", receipt, 1200L).verified)
        assertTrue(AutomationTaskEvidence.constrain(snapshot.taskId, step, claimed, "d2", receipt, 1200L).verified)
        assertFalse(AutomationTaskEvidence.constrain(snapshot.taskId, step, claimed.copy(verified = false), "d2", receipt, 1200L).verified)
        val constrained = AutomationTaskEvidence.constrain(snapshot.taskId, step, claimed, "d1", receipt, 1200L)
        ledger.record("s1", 1, constrained, 1200L)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s2", facts(), 1300L).refusedCode())
    }

    @Test
    fun `设备侧限频回执绑定尝试且可以安全重试`() {
        val ledger = ledger(spec("s1", maxAttempts = 2), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        val state = ledger.snapshot()
        val receipt = AutomationTaskEvidence.rejected(state, "com.example.app", "click", "com.example.app:id/ok", 1100L, "ACCESSIBILITY_RATE_LIMITED")!!
        assertEquals(AutomationTaskCodes.BUSY, AutomationTaskActionPolicy.refusal(state, "com.example.app", "click", "com.example.app:id/ok", 1150L, receipt))
        assertNull(AutomationTaskEvidence.rejected(state, "com.other.app", "click", "com.example.app:id/ok", 1100L, "ACCESSIBILITY_RATE_LIMITED"))
        assertNull(AutomationTaskEvidence.rejected(state, "com.example.app", "click", "wrong", 1100L, "ACCESSIBILITY_RATE_LIMITED"))
        val outcome = AutomationTaskEvidence.constrain(state.taskId, state.steps[0], AutomationTaskStepOutcome(ok = true, verified = true), "d2", receipt, 1200L)
        assertFalse(outcome.ok); assertEquals("ACCESSIBILITY_RATE_LIMITED", outcome.code)
        assertEquals(AutomationTaskStepStatus.FAILED, ledger.record("s1", 1, outcome, 1200L).steps[0].status)
        assertEquals(2, ledger.gate("s1", facts(), 1300L).attempt())
        val next = ledger.snapshot()
        val stale = AutomationTaskEvidence.constrain(next.taskId, next.steps[0], AutomationTaskStepOutcome(ok = false, code = "ACCESSIBILITY_RATE_LIMITED"), "d1", receipt, 1400L)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, stale.code)
        assertEquals(AutomationTaskStepStatus.UNKNOWN, ledger.record("s1", 2, stale, 1400L).steps[0].status)
    }

    @Test
    fun `目标未找到的设备回执可重试但不接受任意拒绝代码`() {
        val ledger = ledger(spec("s1", maxAttempts = 2), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        val state = ledger.snapshot()
        assertNull(AutomationTaskEvidence.rejected(state, "com.example.app", "click", "com.example.app:id/ok", 1100L, "AUTOMATION_CLICK_FAILED"))
        val receipt = AutomationTaskEvidence.rejected(state, "com.example.app", "click", "com.example.app:id/ok", 1100L, "ACCESSIBILITY_NODE_NOT_FOUND")!!
        val result = AutomationTaskEvidence.constrain(state.taskId, state.steps[0], AutomationTaskStepOutcome(ok = false), "d1", receipt, 1200L)
        assertEquals("ACCESSIBILITY_NODE_NOT_FOUND", result.code)
        assertEquals(AutomationTaskStepStatus.FAILED, ledger.record("s1", 1, result, 1200L).steps[0].status)
        assertEquals(2, ledger.gate("s1", facts(), 1300L).attempt())
    }

    @Test
    fun `调用方声称可重试失败不能覆盖动作回执或证明未派发`() {
        for (hasReceipt in listOf(false, true)) {
            val ledger = ledger(spec("s1", action = AutomationTaskActions.CLICK, verify = AutomationTaskActions.VERIFY_NONE, maxAttempts = 2), spec("s2"))
            ledger.gate("s1", facts(), 1000L)
            val state = ledger.snapshot()
            val receipt = if (hasReceipt) AutomationTaskEvidence.receipt(state, "com.example.app", "click", "com.example.app:id/ok", 1100L) else null
            val claim = AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_CLICK_FAILED", verified = true)
            val outcome = AutomationTaskEvidence.constrain(state.taskId, state.steps[0], claim, "d1", receipt, 1200L)
            val recorded = ledger.record("s1", 1, outcome, 1200L)
            assertEquals(AutomationTaskStepStatus.UNKNOWN, recorded.steps[0].status)
            assertEquals(AutomationTaskCodes.NEEDS_CHECK, recorded.steps[0].code)
            assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s1", facts(), 1300L).refusedCode())
            assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s2", facts(), 1300L).refusedCode())
        }
    }

    @Test
    fun `未要求画面验证的动作仍需实际回执且不宣称已验证`() {
        val ledger = ledger(spec("s1", action = AutomationTaskActions.SCROLL, verify = AutomationTaskActions.VERIFY_NONE), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        val state = ledger.snapshot(); val step = state.steps[0]
        val claim = AutomationTaskStepOutcome(ok = true, verified = true)
        val absent = AutomationTaskEvidence.constrain(state.taskId, step, claim, "d1", null, 1200L)
        assertFalse(absent.ok)
        ledger.record("s1", 1, absent, 1200L)
        assertEquals(AutomationTaskStepStatus.UNKNOWN, ledger.snapshot().steps[0].status)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s2", facts(), 1300L).refusedCode())

        val good = ledger(spec("s1", action = AutomationTaskActions.SCROLL, verify = AutomationTaskActions.VERIFY_NONE))
        good.gate("s1", facts(), 1000L)
        val current = good.snapshot()
        val receipt = AutomationTaskEvidence.receipt(current, "com.example.app", "scroll", "com.example.app:id/ok", 1100L)
        val outcome = AutomationTaskEvidence.constrain(current.taskId, current.steps[0], claim, "d1", receipt, 1200L)
        assertTrue(outcome.ok)
        assertEquals(AutomationTaskCodes.ACTION_UNVERIFIED, good.record("s1", 1, outcome, 1200L).steps[0].code)
    }

    @Test
    fun `包变化仅靠调用者声称不能算验证`() {
        val ledger = ledger(spec("back", action = AutomationTaskActions.BACK, target = AutomationTaskActions.BACK_TARGET, verify = AutomationTaskActions.VERIFY_PACKAGE_CHANGED))
        ledger.gate("back", facts(), 1000L)
        val state = ledger.snapshot()
        val outcome = AutomationTaskEvidence.constrain(state.taskId, state.steps[0], AutomationTaskStepOutcome(ok = true, verified = true), "other", null, 1200L)
        assertFalse(outcome.verified)
        assertEquals(AutomationTaskStepStatus.UNKNOWN, ledger.record("back", 1, outcome, 1200L).steps[0].status)
        val noVerification = ledger(spec("back", action = AutomationTaskActions.BACK, target = AutomationTaskActions.BACK_TARGET, verify = AutomationTaskActions.VERIFY_NONE))
        noVerification.gate("back", facts(), 1000L)
        val state2 = noVerification.snapshot()
        val unchecked = AutomationTaskEvidence.constrain(state2.taskId, state2.steps[0], AutomationTaskStepOutcome(ok = true), "d1", null, 1200L)
        assertFalse(unchecked.ok)
        assertEquals(AutomationTaskStepStatus.UNKNOWN, noVerification.record("back", 1, unchecked, 1200L).steps[0].status)
        assertTrue(AutomationTaskPlan.validate(spec("bad-back", action = AutomationTaskActions.BACK, target = "wrong"), mutableSetOf())!!.contains("system:back"))
        val successful = ledger(spec("back", action = AutomationTaskActions.BACK, target = AutomationTaskActions.BACK_TARGET, verify = AutomationTaskActions.VERIFY_PACKAGE_CHANGED))
        successful.gate("back", facts(), 1000L)
        val current = successful.snapshot()
        val receipt = AutomationTaskEvidence.receipt(current, "com.example.app", "back", AutomationTaskActions.BACK_TARGET, 1100L)
        val claimed = AutomationTaskStepOutcome(ok = true, verified = true)
        assertEquals(AutomationTaskCodes.BUSY, AutomationTaskActionPolicy.refusal(current, "com.example.app", "back", AutomationTaskActions.BACK_TARGET, 1200L, receipt))
        assertEquals(AutomationTaskCodes.STEP_OUT_OF_ORDER, AutomationTaskActionPolicy.refusal(current, "com.example.app", "back", "wrong", 1200L, null))
        assertFalse(AutomationTaskEvidence.constrain(current.taskId, current.steps[0], claimed, "d2", receipt, 1200L, expectedPackage = "com.example.app", observedPackage = null).verified)
        assertFalse(AutomationTaskEvidence.constrain(current.taskId, current.steps[0], claimed, "d2", receipt, 1200L, expectedPackage = "com.example.app", observedPackage = "com.example.app").verified)
        val confirmed = AutomationTaskEvidence.constrain(current.taskId, current.steps[0], claimed, "d2", receipt, 1200L, expectedPackage = "com.example.app", observedPackage = "com.other.app")
        assertTrue(confirmed.verified)
        assertEquals(AutomationTaskStepStatus.OK, successful.record("back", 1, confirmed, 1200L).steps[0].status)
    }

    @Test
    fun `中断标记不含步骤内容且不把未验证动作当作安全收口`() {
        val ledger = ledger(spec("s1", action = AutomationTaskActions.SCROLL, verify = AutomationTaskActions.VERIFY_NONE))
        assertTrue(AutomationTaskInterruptionStore.active(ledger.snapshot()))
        assertFalse(AutomationTaskInterruptionStore.orphaned(true, ledger.snapshot()))
        assertTrue(AutomationTaskInterruptionStore.orphaned(true, null))
        assertFalse(AutomationTaskInterruptionStore.orphaned(false, null))
        ledger.gate("s1", facts(), 1000L)
        assertTrue(AutomationTaskInterruptionStore.requiresReview(ledger.snapshot()))
        val finished = ledger.record("s1", AutomationTaskStepOutcome(ok = true), 1200L)
        assertTrue(AutomationTaskInterruptionStore.requiresReview(finished))
        assertTrue(AutomationTaskInterruptionStore.orphaned(true, finished))
    }

    @Test
    fun `目标消失验证需要匹配动作且真实查询目标缺失`() {
        val ledger = ledger(spec("s1", verify = AutomationTaskActions.VERIFY_TARGET_GONE), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        val state = ledger.snapshot()
        val step = state.steps[0]
        val receipt = AutomationTaskEvidence.receipt(state, "com.example.app", "click", "com.example.app:id/ok", 1100L)
        val claimed = AutomationTaskStepOutcome(ok = true, verified = true)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, true).verified)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, null).verified)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", null, 1200L, false).verified)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, false).verified)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, false, expectedPackage = "com.example.app", observedPackage = "com.other.app").verified)
        assertTrue(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, false, expectedPackage = "com.example.app", observedPackage = "com.example.app").verified)
        val failed = AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, true)
        ledger.record("s1", 1, failed, 1200L)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s2", facts(), 1300L).refusedCode())
    }

    @Test
    fun `文字写入验证必须匹配同一步动作和目标节点实际内容`() {
        val ledger = ledger(spec("s1", action = AutomationTaskActions.SET_TEXT), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        val state = ledger.snapshot()
        val step = state.steps[0]
        val receipt = AutomationTaskEvidence.receipt(state, "com.example.app", "setText", "com.example.app:id/ok", 1100L, "你好 🐋")
        val claimed = AutomationTaskStepOutcome(ok = true, verified = true)
        val correct = AutomationTaskEvidence.textDigest("你好 🐋")
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, observedTextDigest = correct).verified)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, observedTextDigest = correct, expectedPackage = "com.example.app", observedPackage = "com.other.app").verified)
        assertTrue(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, observedTextDigest = correct, expectedPackage = "com.example.app", observedPackage = "com.example.app").verified)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, observedTextDigest = null).verified)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", receipt, 1200L, observedTextDigest = AutomationTaskEvidence.textDigest("不同" )).verified)
        assertFalse(AutomationTaskEvidence.constrain(state.taskId, step, claimed, "d1", null, 1200L, observedTextDigest = correct).verified)
        assertFalse(receipt.toString().contains("你好"))
        assertNull(AutomationTaskEvidence.textDigest("a".repeat(513)))
    }

    @Test
    fun `任务实际动作必须通过闸门并匹配当前步骤且不能重复执行`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        fun check(packageName: String = "com.example.app", action: String = "click", target: String = "com.example.app:id/ok", at: Long = 1100L, receipt: AutomationTaskActionReceipt? = null) =
            AutomationTaskActionPolicy.refusal(ledger.snapshot(), packageName, action, target, at, receipt)
        assertEquals(AutomationTaskCodes.STEP_OUT_OF_ORDER, check())
        ledger.gate("s1", facts(), 1000L)
        assertNull(check())
        assertEquals(AutomationTaskCodes.STEP_OUT_OF_ORDER, check(target = "com.example.app:id/other"))
        assertEquals(AutomationTaskCodes.STEP_OUT_OF_ORDER, check(action = "setText"))
        assertEquals(AutomationTaskCodes.STEP_OUT_OF_ORDER, check(packageName = "com.other.app"))
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, check(at = 6000L))
        val receipt = AutomationTaskEvidence.receipt(ledger.snapshot(), "com.example.app", "click", "com.example.app:id/ok", 1100L)
        assertEquals(AutomationTaskCodes.BUSY, check(receipt = receipt))
        ledger.cancel()
        assertEquals(AutomationTaskCodes.CANCELLED, check())
        assertNull(AutomationTaskActionPolicy.refusal(AutomationTaskLedger().snapshot(), "com.example.app", "click", "x", 1100L, null))
    }

    @Test
    fun `任务结果不确定后不能绕开台账直接重发`() {
        val ledger = ledger(spec("s1"))
        ledger.gate("s1", facts(), 1000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = true, verified = false), 1100L)
        assertEquals(AutomationTaskStatus.FAILED, ledger.snapshot().status)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, AutomationTaskActionPolicy.refusal(ledger.snapshot(), "com.example.app", "click", "com.example.app:id/ok", 1200L, null))
    }

    @Test
    fun `用户确认等待不消耗执行超时且不得跨任务或取消`() {
        val ledger = ledger(spec("s1"))
        ledger.gate("s1", facts(), 1000L)
        assertFalse(ledger.allowConfirmationWait("other", "s1", 1, 1100L, 9000L))
        assertTrue(ledger.allowConfirmationWait("task-1", "s1", 1, 1100L, 9000L))
        assertNull(AutomationTaskActionPolicy.refusal(ledger.snapshot(), "com.example.app", "click", "com.example.app:id/ok", 9100L, null))
        ledger.cancel()
        assertFalse(ledger.allowConfirmationWait("task-1", "s1", 1, 9100L, 9200L))
    }

    @Test
    fun `前一步可重试失败时不能跳过直接执行下一步`() {
        val ledger = ledger(spec("s1", maxAttempts = 2), spec("s2"))
        ledger.gate("s1", facts(), 1000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_NODE_UNRESOLVED"), 1100L)
        assertEquals(AutomationTaskCodes.STEP_OUT_OF_ORDER, ledger.gate("s2", facts(), 1200L).refusedCode())
        assertEquals(2, ledger.gate("s1", facts(), 1300L).attempt())
    }

    @Test
    fun `已经成功的步骤再提交不会执行第二次`() {
        val ledger = ledger(spec("s1", maxAttempts = 3))
        ledger.gate("s1", facts(), 1_000L)
        ledger.record("s1", 1, AutomationTaskStepOutcome(ok = true), 1_100L)
        assertEquals(AutomationTaskCodes.STEP_ALREADY_SETTLED, ledger.gate("s1", facts(), 1_200L).refusedCode())
        // 幂等：重复提交不消耗重试次数，也不把状态改回去。
        val snapshot = ledger.snapshot()
        assertEquals(1, snapshot.steps.single().attemptsUsed)
        assertEquals(AutomationTaskStepStatus.OK, snapshot.steps.single().status)
    }

    @Test
    fun `前台换了应用时任务被阻塞并给出恢复路径`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        val gate = ledger.gate("s1", facts(packageName = "com.other.app"), 1_000L)
        assertEquals(AutomationTaskCodes.TARGET_LEFT, gate.refusedCode())
        assertEquals(AutomationTaskStatus.BLOCKED, ledger.snapshot().status)
        // 回到目标应用之后，必须用新的指纹走一次 resume 才能继续（不是默默放行）。
        assertTrue(ledger.resume(listOf("s1", "s2"), facts(digest = "d2")) is AutomationTaskGate.Proceed)
        assertEquals(AutomationTaskStatus.RUNNING, ledger.snapshot().status)
        assertTrue(ledger.gate("s1", facts(digest = "d2"), 2_000L) is AutomationTaskGate.Proceed)
    }

    @Test
    fun `计划之后界面就变了时第一步不发出`() {
        val ledger = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.TARGET_STALE, ledger.gate("s1", facts(digest = "d9"), 1_000L).refusedCode())
        assertTrue(ledger.resume(listOf("s1"), facts(digest = "d9")) is AutomationTaskGate.Proceed)
        assertTrue(ledger.gate("s1", facts(digest = "d9"), 2_000L) is AutomationTaskGate.Proceed)
    }

    @Test
    fun `恢复时的步骤列表必须与原计划一致`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(packageName = "com.other.app"), 1_000L)
        val gate = ledger.resume(listOf("s2", "s1"), facts(digest = "d2"))
        assertEquals(AutomationTaskCodes.PLAN_INVALID, gate.refusedCode())
    }

    @Test
    fun `旧尝试的回执不能覆盖新尝试`() {
        val ledger = ledger(spec("s1", maxAttempts = 2))
        ledger.gate("s1", facts(), 1_000L)
        ledger.record("s1", 1, AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_CLICK_FAILED"), 1_100L)
        ledger.gate("s1", facts(), 1_200L)
        val stale = ledger.record("s1", 1, AutomationTaskStepOutcome(ok = true), 1_300L)
        assertEquals(AutomationTaskCodes.RECORD_INVALID, stale.lastCode)
        assertEquals(AutomationTaskStepStatus.PENDING, stale.steps.single().status)
        val current = ledger.record("s1", 2, AutomationTaskStepOutcome(ok = true), 1_400L)
        assertEquals(AutomationTaskStepStatus.OK, current.steps.single().status)
    }

    @Test
    fun `用户抢占后恢复必须重新确认现场`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(packageName = "com.other.app"), 1_000L)
        val blocked = ledger.resume(listOf("s1", "s2"), facts(digest = "d2", manualPreempted = true))
        assertEquals(AutomationTaskCodes.MANUAL_PREEMPTED, blocked.refusedCode())
        assertEquals(AutomationTaskStatus.BLOCKED, ledger.snapshot().status)
        assertTrue(ledger.resume(listOf("s1", "s2"), facts(digest = "d2")) is AutomationTaskGate.Proceed)
    }

    @Test
    fun `执行中外部界面变化将步骤标为不确定并拒绝恢复重发`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(), 1_000L)
        val blocked = ledger.markExternalChange()
        assertEquals(AutomationTaskStatus.BLOCKED, blocked.status)
        assertEquals(AutomationTaskCodes.MANUAL_PREEMPTED, blocked.lastCode)
        assertEquals(AutomationTaskStepStatus.UNKNOWN, blocked.steps[0].status)
        assertNull(blocked.steps[0].startedAtMs)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.resume(listOf("s1", "s2"), facts(digest = "d1")).refusedCode())
        assertEquals(AutomationTaskCodes.RECORD_INVALID, ledger.record("s1", 1, AutomationTaskStepOutcome(ok = true), 1_100L).lastCode)
        assertEquals(AutomationTaskStatus.BLOCKED, ledger.snapshot().status)
        assertEquals(AutomationTaskStepStatus.UNKNOWN, ledger.cancel().steps[0].status)
    }

    @Test
    fun `步骤之间的外部变化重新核对后可以恢复`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(), 1_000L)
        ledger.record("s1", 1, AutomationTaskStepOutcome(ok = true, verified = true), 1_100L)
        assertEquals(AutomationTaskStatus.BLOCKED, ledger.markExternalChange().status)
        assertTrue(ledger.resume(listOf("s1", "s2"), facts(digest = "d2")) is AutomationTaskGate.Proceed)
        assertTrue(ledger.gate("s2", facts(digest = "d2"), 1_200L) is AutomationTaskGate.Proceed)
    }

    @Test
    fun `未阻塞的任务不接受重复恢复`() {
        val ledger = ledger(spec("s1"))
        assertEquals(
            AutomationTaskCodes.RESUME_REQUIRED,
            ledger.resume(listOf("s1"), facts()).refusedCode(),
        )
    }

    @Test
    fun `明确没生效的失败可以重试一次`() {
        val ledger = ledger(spec("s1", maxAttempts = 2))
        assertEquals(1, ledger.gate("s1", facts(), 1_000L).attempt())
        ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_NODE_UNRESOLVED"), 1_100L)
        val snapshot = ledger.snapshot().steps.single()
        assertEquals(AutomationTaskStepStatus.FAILED, snapshot.status)
        assertEquals("AUTOMATION_NODE_UNRESOLVED", snapshot.code)
        // 现场没变：允许再试，次数记到 2。
        assertEquals(2, ledger.gate("s1", facts(), 1_200L).attempt())
        ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_CLICK_FAILED"), 1_300L)
        // 次数用尽：再提交就是「次数用完」，不是继续试。
        assertEquals(AutomationTaskCodes.ATTEMPTS_EXHAUSTED, ledger.gate("s1", facts(), 1_400L).refusedCode())
    }

    @Test
    fun `失败之后界面又变了时不允许直接重发`() {
        val ledger = ledger(spec("s1", maxAttempts = 3))
        ledger.gate("s1", facts(digest = "d1"), 1_000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_NODE_UNRESOLVED"), 1_100L)
        val gate = ledger.gate("s1", facts(digest = "d2"), 1_200L)
        assertEquals(AutomationTaskCodes.TARGET_STALE, gate.refusedCode())
        assertEquals(AutomationTaskStatus.BLOCKED, ledger.snapshot().status)
    }

    @Test
    fun `认不出的失败码按不安全处理且不许重试`() {
        val ledger = ledger(spec("s1", maxAttempts = 3))
        ledger.gate("s1", facts(), 1_000L)
        val snapshot = ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_SOMETHING_NEW"), 1_100L)
        val step = snapshot.steps.single()
        assertEquals(AutomationTaskStepStatus.UNKNOWN, step.status)
        assertEquals("AUTOMATION_SOMETHING_NEW", step.code)
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s1", facts(), 1_200L).refusedCode())
    }

    @Test
    fun `超时记成不确定并且永不自动重试`() {
        val ledger = ledger(spec("s1", maxAttempts = 3))
        ledger.gate("s1", facts(), 1_000L)
        val snapshot = ledger.record("s1", AutomationTaskStepOutcome(ok = false, timedOut = true), 6_500L)
        val step = snapshot.steps.single()
        assertEquals(AutomationTaskStepStatus.UNKNOWN, step.status)
        assertEquals(AutomationTaskCodes.TIMEOUT, step.code)
        assertEquals(5_500L, step.elapsedMs)
        assertFalse(AutomationTaskRetry.mayRetry(step.code, step.attemptsUsed, step.maxAttempts))
        assertEquals(AutomationTaskCodes.NEEDS_CHECK, ledger.gate("s1", facts(), 7_000L).refusedCode())
    }

    @Test
    fun `发出去了但没有观察到变化时记成不确定`() {
        val ledger = ledger(spec("s1"))
        ledger.gate("s1", facts(), 1_000L)
        val snapshot = ledger.record("s1", AutomationTaskStepOutcome(ok = true, verified = false), 1_400L)
        val step = snapshot.steps.single()
        assertEquals(AutomationTaskStepStatus.UNKNOWN, step.status)
        assertEquals(AutomationTaskCodes.NOT_VERIFIED, step.code)
        assertEquals(AutomationTaskStatus.FAILED, snapshot.status)
    }

    @Test
    fun `不需要校验的等待步骤成功即算完成`() {
        val ledger = ledger(spec("s1", action = AutomationTaskActions.WAIT, target = "", maxAttempts = 1))
        ledger.gate("s1", facts(), 1_000L)
        val snapshot = ledger.record("s1", AutomationTaskStepOutcome(ok = true, verified = false), 1_100L)
        assertEquals(AutomationTaskStepStatus.OK, snapshot.steps.single().status)
        assertEquals(AutomationTaskStatus.DONE, snapshot.status)
    }

    @Test
    fun `取消之后未收口的步骤记为跳过且不再放行`() {
        val ledger = ledger(spec("s1"), spec("s2"))
        ledger.gate("s1", facts(), 1_000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = true), 1_100L)
        val snapshot = ledger.cancel()
        assertEquals(AutomationTaskStatus.CANCELLED, snapshot.status)
        assertEquals(listOf(AutomationTaskStepStatus.OK, AutomationTaskStepStatus.SKIPPED), snapshot.steps.map { it.status })
        assertEquals(AutomationTaskCodes.CANCELLED, snapshot.steps.last().code)
        assertEquals(AutomationTaskCodes.CANCELLED, ledger.gate("s2", facts(), 1_200L).refusedCode())
    }

    @Test
    fun `迟到或重复 record 不得改写已收口状态`() {
        val ledger = ledger(spec("s1"))
        ledger.gate("s1", facts(), 1_000L)
        val original = ledger.record("s1", AutomationTaskStepOutcome(ok = true), 1_100L)
        val late = ledger.record("s1", AutomationTaskStepOutcome(ok = false, code = "AUTOMATION_CLICK_FAILED"), 1_200L)
        assertEquals(AutomationTaskCodes.RECORD_INVALID, late.lastCode)
        assertEquals(original, ledger.snapshot())
        assertEquals(AutomationTaskStatus.DONE, ledger.snapshot().status)
        assertEquals(AutomationTaskStepStatus.OK, ledger.snapshot().steps.single().status)
    }

    @Test
    fun `取消后的 record 被拒绝且保持 cancelled`() {
        val ledger = ledger(spec("s1"))
        ledger.gate("s1", facts(), 1_000L)
        ledger.cancel()
        val result = ledger.record("s1", AutomationTaskStepOutcome(ok = true), 1_100L)
        assertEquals(AutomationTaskCodes.RECORD_INVALID, result.lastCode)
        assertEquals(AutomationTaskCodes.CANCELLED, ledger.snapshot().lastCode)
        assertEquals(AutomationTaskStatus.CANCELLED, ledger.snapshot().status)
        assertEquals(AutomationTaskStepStatus.SKIPPED, ledger.snapshot().steps.single().status)
    }

    @Test
    fun `终态任务不能被 resume 重新打开`() {
        val done = ledger(spec("s1"))
        done.gate("s1", facts(), 1_000L)
        done.record("s1", AutomationTaskStepOutcome(ok = true), 1_100L)
        assertEquals(AutomationTaskCodes.RESUME_REQUIRED, done.resume(listOf("s1"), "d2").refusedCode())
        assertEquals(AutomationTaskStatus.DONE, done.snapshot().status)

        val cancelled = ledger(spec("s1"))
        cancelled.cancel()
        assertEquals(AutomationTaskCodes.RESUME_REQUIRED, cancelled.resume(listOf("s1"), "d2").refusedCode())
        assertEquals(AutomationTaskStatus.CANCELLED, cancelled.snapshot().status)
    }

    @Test
    fun `同一时刻只允许一个未收口的任务`() {
        val ledger = ledger(spec("s1"))
        val gate = ledger.plan("task-2", "com.example.app", "d1", listOf(spec("t1")))
        assertEquals(AutomationTaskCodes.BUSY, gate.refusedCode())
        // 收口之后同一个 id 可以被新计划覆盖。
        ledger.gate("s1", facts(), 1_000L)
        ledger.record("s1", AutomationTaskStepOutcome(ok = true), 1_100L)
        assertTrue(ledger.plan("task-1", "com.example.app", "d1", listOf(spec("s9"))) is AutomationTaskGate.Proceed)
    }

    @Test
    fun `锁屏 敏感窗口与白名单拒绝都算场景类并阻塞任务`() {
        val locked = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.DEVICE_LOCKED, locked.gate("s1", facts(locked = true), 1_000L).refusedCode())
        assertEquals(AutomationTaskStatus.BLOCKED, locked.snapshot().status)

        val sensitive = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.SENSITIVE_WINDOW, sensitive.gate("s1", facts(sensitive = true), 1_000L).refusedCode())

        val denied = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.PACKAGE_NOT_ALLOWED, denied.gate("s1", facts(allowed = false), 1_000L).refusedCode())

        val offline = ledger(spec("s1"))
        assertEquals(AutomationTaskCodes.SERVICE_DISCONNECTED, offline.gate("s1", facts(connected = false), 1_000L).refusedCode())
    }

    @Test
    fun `步骤过多与计划非法都在计划期被拒`() {
        val ledger = AutomationTaskLedger()
        val many = (1..AutomationTaskPlan.MAX_STEPS + 1).map { spec("s$it") }
        assertEquals(
            AutomationTaskCodes.TOO_MANY_STEPS,
            ledger.plan("task-1", "com.example.app", "d1", many).refusedCode(),
        )
        assertEquals(
            AutomationTaskCodes.PLAN_INVALID,
            ledger.plan("bad id", "com.example.app", "d1", listOf(spec("s1"))).refusedCode(),
        )
        assertEquals(
            AutomationTaskCodes.PLAN_INVALID,
            ledger.plan("task-1", "com.example.app", "d1", emptyList()).refusedCode(),
        )
        // 包名形状跟着白名单那把尺子走（`AccessibilityAutomationPolicy.validPackage`）：至少两段、
        // 每段以字母开头，所以单段名与空段都算「写错了」。
        assertEquals(
            AutomationTaskCodes.PLAN_INVALID,
            ledger.plan("task-1", "settings", "d1", listOf(spec("s1"))).refusedCode(),
        )
        assertEquals(
            AutomationTaskCodes.PLAN_INVALID,
            ledger.plan("task-1", "com..example", "d1", listOf(spec("s1"))).refusedCode(),
        )
    }

    @Test
    fun `大写段与保留包名都不在计划期拦：能不能动由白名单回答`() {
        // 旧正则只认小写，于是「白名单里存得下的包名，计划里却报不合法」。
        val upper = AutomationTaskLedger().plan("task-1", "Com.Example.App", "d1", listOf(spec("s1")))
        assertTrue("大写段应当被接受：$upper", upper is AutomationTaskGate.Proceed)
        // 保留包名（`com.android.settings` 这类）也放过去：能不能动是服务侧 `packageAllowed` 的结论，
        // 在计划期回一句「包名不合法」会把「不允许」说成「你写错了」。
        val reserved = AutomationTaskLedger().plan("task-1", "com.android.settings", "d1", listOf(spec("s1")))
        assertTrue("保留包名在计划期不拦：$reserved", reserved is AutomationTaskGate.Proceed)
    }
}
