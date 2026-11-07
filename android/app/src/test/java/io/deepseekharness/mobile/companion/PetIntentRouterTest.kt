package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class PetIntentRouterTest {
    @Test fun temporaryOrMalformedForegroundClassificationCanUseNormalReplyWithoutDispatchingWork() {
        for (failure in listOf(PetModelHttp.HttpFailure(500,null,"temporary"),PetIntentRouter.InvalidIntent(),org.json.JSONException("invalid"))) {
            val decision = PetIntentRouter.foreground { throw failure }
            assertEquals(PetIntentRouter.Intent.CHAT,decision.intent)
            assertFalse(decision.independent)
        }
        for (failure in listOf(PetModelHttp.HttpFailure(401,null,"credential"),PetModelHttp.HttpFailure(503,"120","busy"))) {
            assertSame(failure,assertThrows(PetModelHttp.HttpFailure::class.java) { PetIntentRouter.foreground { throw failure } })
        }
    }
    @Test fun immediateHandoffRequiresAnExplicitBooleanAndACompletePlan() {
        fun decision(extra: String) = PetIntentRouter.parse("""{"intent":"background_read","needs":"none","reference":""$extra}""")
        assertFalse(decision("").independent)
        assertFalse(decision(",\"independent\":\"true\"").independent)
        assertTrue(decision(""","independent":true""").independent)
        assertFalse(PetIntentRouter.parse("""{"intent":"agent","independent":true}""").independent)
    }

    @Test fun acceptsOnlyKnownModelIntents() {
        assertEquals(PetIntentRouter.Intent.CHAT, PetIntentRouter.parse("{\"intent\":\"chat\"}").intent)
        assertEquals(PetIntentRouter.Intent.KNOWLEDGE, PetIntentRouter.parse("{\"intent\":\"knowledge\"}").intent)
        assertEquals(PetIntentRouter.Intent.AGENT, PetIntentRouter.parse("{\"intent\":\"agent\"}").intent)
        assertThrows(Exception::class.java) { PetIntentRouter.parse("{\"intent\":\"unknown\"}") }
    }
    @org.junit.Test fun backgroundRequiresACompleteBoundedPlan() {
        val plan = PetIntentRouter.parse("""{"intent":"background_create","needs":"none","reference":"按城市整理旅行卡片"}""")
        assertEquals(PetIntentRouter.Intent.BACKGROUND_CREATE, plan.intent)
        val task = plan.workerTask("给我做成这个工具")
        org.junit.Assert.assertTrue(task.startsWith("给我做成这个工具"))
        org.junit.Assert.assertTrue(task.contains("按城市整理旅行卡片"))
        assertEquals("x".repeat(2000), plan.workerTask("x".repeat(2000)))
        assertEquals(PetIntentRouter.Intent.AGENT, PetIntentRouter.parse("""{"intent":"background_read","reference":""}""").intent)
        assertEquals(PetIntentRouter.Intent.AGENT, PetIntentRouter.parse("""{"intent":"background_create","needs":"device","reference":""}""").intent)
    }

}
