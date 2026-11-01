package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class PetIntentRouterTest {
    @Test fun acceptsOnlyKnownModelIntents() {
        assertEquals(PetIntentRouter.Intent.CHAT, PetIntentRouter.parse("{\"intent\":\"chat\"}"))
        assertEquals(PetIntentRouter.Intent.KNOWLEDGE, PetIntentRouter.parse("{\"intent\":\"knowledge\"}"))
        assertEquals(PetIntentRouter.Intent.AGENT, PetIntentRouter.parse("{\"intent\":\"agent\"}"))
        assertThrows(Exception::class.java) { PetIntentRouter.parse("{\"intent\":\"unknown\"}") }
    }
}
