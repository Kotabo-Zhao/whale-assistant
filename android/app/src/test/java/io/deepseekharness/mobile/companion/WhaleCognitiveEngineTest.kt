package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleCognitiveEngineTest {

    @Test
    fun modelRouterDownshiftsExpensiveReasonerForShortChatOrHighBudget() {
        val fastShort = WhaleModelRouter.route("deepseek-reasoner", "在吗？", hasImages = false, dailyBudgetProgress = 0.1f)
        assertEquals("deepseek-chat", fastShort.model)
        assertEquals(WhaleModelRouter.Tier.FAST, fastShort.tier)

        val deepPlan = WhaleModelRouter.route("deepseek-reasoner", "帮我深度分析一下这个架构重构方案的利弊", hasImages = false, dailyBudgetProgress = 0.2f)
        assertEquals("deepseek-reasoner", deepPlan.model)
        assertEquals(WhaleModelRouter.Tier.DEEP, deepPlan.tier)

        val vision = WhaleModelRouter.route("deepseek-v4-pro", "看图", hasImages = true, dailyBudgetProgress = 0.9f)
        assertEquals("deepseek-v4-pro", vision.model)
        assertEquals(WhaleModelRouter.Tier.DEEP, vision.tier)
    }

    @Test
    fun extractSuggestionsStripsInlineMarkerAndParsesUpToThreeChips() {
        val raw = "好的，我已经帮你把明天早上的日程整理好了。\n<!--whale_next:[\"顺手设个8点闹钟\",\"查看本周其他日程\",\"导出成待办清单\",\"忽略第四条\"]-->"
        val (clean, chips) = WhaleNativeAgentEngine.extractSuggestions(raw)
        assertEquals("好的，我已经帮你把明天早上的日程整理好了。", clean)
        assertEquals(listOf("顺手设个8点闹钟", "查看本周其他日程", "导出成待办清单"), chips)
        assertTrue(!clean.contains("whale_next"))
    }

    @Test
    fun inferFallbackSuggestionsGeneratesChoiceAndYesNoQuickReplies() {
        val ab = WhaleNativeAgentEngine.inferFallbackSuggestions("美团那单吃饭的事，你还没说今晚吃还是改明天？")
        assertEquals(listOf("今晚吃", "改明天", "先不急"), ab)

        val yn = WhaleNativeAgentEngine.inferFallbackSuggestions("刚给你建的习惯打卡小工具，要不要现在先塞两条习惯进去试试？")
        assertEquals(listOf("好呀，直接做", "先不用了", "我有补充"), yn)

        val plain = WhaleNativeAgentEngine.inferFallbackSuggestions("已经帮你把桌宠尺寸调到 160dp 了。")
        assertTrue(plain.isEmpty())
    }

    @Test
    fun skillMarkdownAndGithubUrlResolution() {
        val urls = WhaleSkillStore.resolveCandidateUrls("https://github.com/acme/weather-skill")
        assertTrue(urls.contains("https://raw.githubusercontent.com/acme/weather-skill/main/SKILL.md"))
        val parsed = WhaleSkillStore.parseSkillMarkdown(
            "---\nname: weather-brief\ndescription: 查询天气并给出穿衣建议\n---\n# 步骤\n1. 查询天气",
        )
        assertEquals("weather-brief", parsed.slug)
        assertEquals("查询天气并给出穿衣建议", parsed.description)
    }

    @Test
    fun mcpRpcBodyParsesJsonAndSseFrames() {
        val direct = WhaleMcpManager.parseRpcBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}")
        assertEquals(1, direct.getInt("id"))
        val sse = WhaleMcpManager.parseRpcBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}\n\n")
        assertEquals(2, sse.getInt("id"))
        assertEquals("https://mcp.example.com/sse", WhaleMcpManager.validateUrl("https://mcp.example.com/sse"))
    }

    @Test
    fun longTaskCheckpointRoundTripAndStagePrompt() {
        val cp = WhaleLongTaskStore.Checkpoint(
            id = "task-1",
            title = "整理周报",
            task = "汇总本周工作并生成周报",
            mode = "create",
            ownerSession = "session-whale-task-desk",
            stage = 2,
            maxStages = 3,
            progressDigest = "已完成前 3 个项目的指标收集",
            resumeCount = 1,
            state = "running",
            updatedAt = 123456L,
        )
        val restored = WhaleLongTaskStore.Checkpoint.fromJson(cp.json())
        assertEquals(cp, restored)
        val prompt = WhaleLongTaskStore.buildStagePrompt(restored)
        assertTrue(prompt.contains("第 2/3 阶段"))
        assertTrue(prompt.contains("已完成前 3 个项目的指标收集"))
    }

    @Test
    fun workerSlotsReserveThreeRegularPlusOneHumanPriority() {
        val active = mutableSetOf<String>()
        val slots = WhaleSuperagentCore.WorkerSlots(runningProvider = { active }, maxWorkers = { 3 })
        slots.reserve("t1", "launch")
        slots.reserve("t2", "launch")
        slots.reserve("t3", "launch")
        assertEquals(0, slots.availableLaunches())
        var launchBlocked = false
        try {
            slots.reserve("t4", "launch")
        } catch (_: WhaleSuperagentCore.LaterException) {
            launchBlocked = true
        }
        assertTrue(launchBlocked)
        // Human follow-up gets +1 priority slot
        slots.reserve("human-followup", "person")
        assertEquals(4, slots.used())
        slots.free("human-followup")
        assertEquals(3, slots.used())
    }

    @Test
    fun transientFailureDetectionAndTruncatorAndTicketStage() {
        assertTrue(WhaleSuperagentCore.isTransientFailure(statusCode = 429))
        assertTrue(WhaleSuperagentCore.isTransientFailure(statusCode = 408))
        assertTrue(WhaleSuperagentCore.isTransientFailure(statusCode = 503))
        assertTrue(WhaleSuperagentCore.isTransientFailure(errorText = "java.net.SocketTimeoutException: timed out"))
        assertTrue(!WhaleSuperagentCore.isTransientFailure(statusCode = 401))

        val longText = "a".repeat(900)
        val trunc = WhaleSuperagentCore.ResourceTruncator.truncate(longText, 600, "worker/w1")
        assertEquals("worker/w1", trunc.getString("read"))
        assertEquals(900, trunc.getInt("chars"))
        val page1 = WhaleSuperagentCore.ResourceTruncator.readPage(longText, offset = 0, limit = 500)
        assertEquals(500, page1.getInt("nextOffset"))

        assertEquals(
            WhaleSuperagentCore.TicketStage.PERSON_DELIVERY,
            WhaleSuperagentCore.computeStage(deliveryFailed = true, hasAttention = true, reportState = "ready"),
        )
        assertEquals(
            WhaleSuperagentCore.TicketStage.PERSON_ANSWER,
            WhaleSuperagentCore.computeStage(hasAttention = true, reportState = "blocked"),
        )
        assertTrue(WhaleSuperagentCore.waitsForPerson("blocked", hasHumanMessageSinceReport = false))
        assertTrue(!WhaleSuperagentCore.waitsForPerson("blocked", hasHumanMessageSinceReport = true))
    }

    @Test
    fun noticeCoalescerMandateGuardAndUnreadFollowupReplay() {
        val before = org.json.JSONObject().put("eval", org.json.JSONObject().put("mode", "observe").put("limit", 3))
        val after = org.json.JSONObject().put("eval", org.json.JSONObject().put("mode", "auto").put("limit", 3))
        assertEquals(listOf("eval.mode"), WhaleSuperagentCore.NoticeCoalescer.changedPaths(before, after))
        assertTrue(WhaleSuperagentCore.MandateGuard.validateBriefText("x".repeat(300), "note", 240) != null)
        assertEquals(null, WhaleSuperagentCore.MandateGuard.validateBriefText("简短备注", "note", 240))

        val cp = WhaleLongTaskStore.Checkpoint(
            id = "task-2",
            title = "重构模块",
            task = "重构设置页",
            mode = "create",
            ownerSession = "session-whale-task-desk",
            stage = 2,
            maxStages = 3,
            progressDigest = "阶段1已完成",
            resumeCount = 1,
            state = "running",
            updatedAt = 999L,
            strength = "inferred",
            ruleName = "自动整理",
            deliveredInputs = listOf("in-1" to "已读过的补充", "in-2" to "新增未读补充"),
            readInputIds = setOf("in-1"),
        )
        val prompt = WhaleLongTaskStore.buildStagePrompt(cp)
        assertTrue(prompt.contains("主动规则受限授权"))
        assertTrue(prompt.contains("新增未读补充"))
        assertTrue(!prompt.contains("已读过的补充"))
        assertTrue(prompt.contains("严禁重复已完成的外部动作"))
    }
}
