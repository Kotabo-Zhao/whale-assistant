package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.CustomModelProvider
import io.deepseekharness.mobile.runtime.CustomProviderApi
import io.deepseekharness.mobile.runtime.CustomProviderModel
import io.deepseekharness.mobile.runtime.RuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnderstandingProviderChoiceTest {
    private fun settings(providers: List<CustomModelProvider>) = RuntimeSettings(
        manifestUrl = "",
        manifestSha256 = "",
        keepScreenAwake = false,
        terminalFontSize = 14,
        customModelProviders = providers,
    )

    private fun provider(api: CustomProviderApi, baseUrl: String) = CustomModelProvider(
        id = "private-gateway",
        name = "私有网关",
        api = api,
        baseUrl = baseUrl,
        models = listOf(CustomProviderModel("chat-model", "Chat Model", 8192, 1024)),
    )

    @Test fun includesHttpsOpenAiCompletionsCustomProvider() {
        val choices = understandingProviderChoices(settings(listOf(provider(
            CustomProviderApi.OPENAI_COMPLETIONS,
            "https://gateway.example/v1",
        ))))

        assertEquals("私有网关", choices.first { it.id == "private-gateway" }.displayName)
    }

    @Test fun includesEverySupportedHttpsCustomProtocolButRejectsInsecureEndpoints() {
        val http = provider(CustomProviderApi.OPENAI_COMPLETIONS, "http://gateway.example/v1")
        val responses = provider(CustomProviderApi.OPENAI_RESPONSES, "https://gateway.example/v1")
        val anthropic = provider(CustomProviderApi.ANTHROPIC_MESSAGES, "https://gateway.example/v1")

        assertFalse(understandingProviderChoices(settings(listOf(http))).any { it.id == http.id })
        assertEquals(responses.id, understandingProviderChoices(settings(listOf(responses))).first { it.id == responses.id }.id)
        assertEquals(anthropic.id, understandingProviderChoices(settings(listOf(anthropic))).first { it.id == anthropic.id }.id)
    }
}
