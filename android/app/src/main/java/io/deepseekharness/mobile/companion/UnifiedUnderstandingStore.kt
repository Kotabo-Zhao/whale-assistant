package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.CustomModelProvider
import io.deepseekharness.mobile.runtime.CustomProviderApi
import io.deepseekharness.mobile.runtime.ModelProvider
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File

/** A staging database shared by memory migration, events, jobs and model proposals. */
internal class UnifiedUnderstandingStore(path: File) : AutoCloseable {
    val memories = MemoryMigrationDatabase(path)
    val queue: UnderstandingQueue
    init {
        try { queue = UnderstandingQueue(path) } catch (e: Exception) { memories.close(); throw e }
    }

    /** Caller supplies a consistent legacy snapshot; the original remains untouched. */
    fun stageLegacy(snapshot: ByteArray): Int = memories.importLegacy(snapshot)

    /** A compatible export, not an automatic downgrade or replacement of the legacy file. */
    fun exportLegacyRollback(): ByteArray {
        check(memories.isImported()) { "legacy data not imported" }
        return CompanionMemoryExport.encode(memories.readAll()).also { CompanionMemoryFormat.parse(it) }
    }

    override fun close() { try { queue.close() } finally { memories.close() } }

    companion object {
        fun path(context: Context): File = File(context.applicationContext.noBackupFilesDir, "understanding-staging-v1.db")
        fun open(context: Context): UnifiedUnderstandingStore = UnifiedUnderstandingStore(
            path(context)
        )
    }
}

/** A provider that may receive background notification or knowledge text. */
internal data class UnderstandingProviderChoice(val id: String, val displayName: String)

/**
 * Background understanding sends private text, so custom endpoints must be HTTPS. All configured
 * protocol variants pass through a bounded client and the same local proposal contract.
 */
internal fun understandingProviderChoices(settings: io.deepseekharness.mobile.runtime.RuntimeSettings): List<UnderstandingProviderChoice> {
    val builtIns = listOf("deepseek", "openai", "openrouter", "groq", "xai", "mistral")
        .map { UnderstandingProviderChoice(it, it) }
    val custom = settings.customModelProviders.mapNotNull { provider ->
        provider.takeIf(::isHttpsBaseUrl)
            ?.let { UnderstandingProviderChoice(it.id, it.name) }
    }
    return builtIns + custom
}

private fun isHttpsBaseUrl(provider: CustomModelProvider): Boolean =
    runCatching {
        java.net.URI(provider.baseUrl).let { uri ->
            uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.host != null &&
                uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)
        }
    }.getOrDefault(false)

/** Both background pipelines resolve the same explicit provider and encrypted credential. */
internal fun configuredUnderstandingModel(context: Context): UnderstandingModel {
    val settings = RuntimeStore(context)
    val provider = CompanionStore(context).understandingProvider
    val runtimeSettings = settings.settings()
    val customProvider = runtimeSettings.customModelProviders.firstOrNull { it.id == provider }
    val endpoint = when (provider) {
        "deepseek" -> "https://api.deepseek.com/chat/completions"
        "openai" -> "https://api.openai.com/v1/chat/completions"
        "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
        "groq" -> "https://api.groq.com/openai/v1/chat/completions"
        "xai" -> "https://api.x.ai/v1/chat/completions"
        "mistral" -> "https://api.mistral.ai/v1/chat/completions"
        else -> null
    }
    if (endpoint == null && customProvider == null) {
        throw UnderstandingRequestException("configuration", "已选择的后台模型供应商已删除，请在助手设置中重新选择")
    }
    val key = settings.readModelCredential(provider) ?: throw UnderstandingRequestException("credentials", "后台模型凭据不可用")
    val model = customProvider?.models?.firstOrNull()?.id
        ?: when (provider) {
            "deepseek" -> runtimeSettings.defaultModels[ModelProvider.DEEPSEEK] ?: "deepseek-flash"
            else -> runtimeSettings.defaultModels[ModelProvider.parse(provider)]
                ?: throw UnderstandingRequestException("configuration", "请先设置后台供应商的默认模型")
        }
    if (customProvider != null) {
        if (!isHttpsBaseUrl(customProvider)) throw UnderstandingRequestException("configuration", "后台自定义供应商必须使用 HTTPS")
        return if (customProvider.api == CustomProviderApi.OPENAI_COMPLETIONS) {
            DeepSeekUnderstandingModel(key, model, customProvider.baseUrl.trimEnd('/') + "/chat/completions", allowCustomEndpoint = true)
        } else {
            CustomUnderstandingModel(key, customProvider, model)
        }
    }
    return DeepSeekUnderstandingModel(key, model, requireNotNull(endpoint))
}
