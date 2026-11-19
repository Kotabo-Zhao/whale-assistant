package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectChatEscalationPolicyTest {
    @Test
    fun routesEverydayChatAndMemoryToDirectNativeWithoutProot() {
        assertEquals(
            DirectChatEscalationPolicy.Route.DIRECT_NATIVE,
            DirectChatEscalationPolicy.decide("帮我总结一下刚才聊的三个要点"),
        )
        assertEquals(
            DirectChatEscalationPolicy.Route.DIRECT_NATIVE,
            DirectChatEscalationPolicy.decide("提醒我明天上午十点开会"),
        )
    }

    @Test
    fun escalatesShellScreenAutomationAndWorkshopCreationToProotWorker() {
        assertEquals(
            DirectChatEscalationPolicy.Route.ESCALATE_PROOT_WORKER,
            DirectChatEscalationPolicy.decide("帮我跑一段 python 脚本分析这份 CSV"),
        )
        assertEquals(
            DirectChatEscalationPolicy.Route.ESCALATE_PROOT_WORKER,
            DirectChatEscalationPolicy.decide("帮我创建一个每日习惯打卡小应用"),
        )
        assertEquals(
            DirectChatEscalationPolicy.Route.ESCALATE_PROOT_WORKER,
            DirectChatEscalationPolicy.decide("帮我点开微信发一条消息"),
        )
    }
}
