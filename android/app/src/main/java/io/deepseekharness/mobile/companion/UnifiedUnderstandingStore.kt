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

/** Uses the existing encrypted credential store. Construct only after separate model-use consent. */
internal fun configuredDeepSeekUnderstandingModel(context: Context): UnderstandingModel {
    val settings = RuntimeStore(context)
    val key = settings.readModelCredential("deepseek") ?: error("DeepSeek 凭据不可用")
    val model = settings.settings().defaultModels[ModelProvider.DEEPSEEK] ?: error("尚未设置 DeepSeek 默认模型")
    return DeepSeekUnderstandingModel(key, model)
}
