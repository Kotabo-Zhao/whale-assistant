package io.deepseekharness.mobile.companion

import android.content.Context
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

/** Both background pipelines resolve the same explicit provider and encrypted credential. */
internal fun configuredUnderstandingModel(context: Context): UnderstandingModel {
    val settings = RuntimeStore(context)
    val provider = CompanionStore(context).understandingProvider
    val endpoint = when (provider) {
        "deepseek" -> "https://api.deepseek.com/chat/completions"
        "openai" -> "https://api.openai.com/v1/chat/completions"
        "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
        "groq" -> "https://api.groq.com/openai/v1/chat/completions"
        "xai" -> "https://api.x.ai/v1/chat/completions"
        "mistral" -> "https://api.mistral.ai/v1/chat/completions"
        else -> error("后台模型供应商不可用")
    }
    val modelProvider = ModelProvider.parse(provider)
    val key = settings.readModelCredential(provider) ?: throw UnderstandingRequestException("credentials", "后台模型凭据不可用")
    val model = settings.settings().defaultModels[modelProvider]
        ?: if (provider == "deepseek") "deepseek-flash" else throw UnderstandingRequestException("configuration", "请先设置后台供应商的默认模型")
    return DeepSeekUnderstandingModel(key, model, endpoint)
}
