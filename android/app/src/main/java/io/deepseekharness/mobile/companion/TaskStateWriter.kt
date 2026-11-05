package io.deepseekharness.mobile.companion

import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

/** Caller holds the store lock. Publish only after write, sync, close and rename succeed. */
internal class TaskStateWriter(
    private val writePayload: (FileOutputStream, ByteArray) -> Unit = { output, bytes -> output.write(bytes) }
) {
    fun replace(target: File, bytes: ByteArray) {
        // Older releases used AtomicFile. Recover any legacy backup before publication.
        if (File(target.path + ".bak").exists()) AtomicFile(target).openRead().close()
        val partial = File(target.path + ".partial")
        try {
            FileOutputStream(partial).use { output ->
                writePayload(output, bytes)
                output.fd.sync()
            }
            Os.rename(partial.path, target.path)
        } finally {
            partial.delete()
        }
    }
}
