package io.deepseekharness.mobile.runtime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SeparateRuntimeDeviceTest {
    @Test fun selectedStreamIsVerifiedBeforePromotion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySeparateRuntime") == "yes")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(app.cacheDir, "runtime-test-" + UUID.randomUUID()).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(dir, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(dir, "private").apply { mkdirs() }
            override fun getCacheDir(): File = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences(dir.name + name, mode).also { names.add(dir.name + name) }
        }
        try {
            val store = RuntimeStore(context)
            val installer = RuntimeInstaller(store, RuntimeStatus(store))
            val invalid = runCatching {
                installer.install(RuntimeSource(null, null, openSelectedRootfs = { byteArrayOf(1, 2).inputStream() }))
            }.exceptionOrNull()
            assertTrue(invalid is RuntimeFailure)
            assertNull(store.installedManifest())
            val file = InstrumentationRegistry.getArguments().getString("archiveName")
            require(file == null || file == "whale-p57-test.bundle")
            val source: () -> java.io.InputStream = if (file == null) store::openBundledRootfs
                else { { File(app.cacheDir, file).inputStream() } }
            installer.install(RuntimeSource(null, null, openSelectedRootfs = source))
            assertEquals(store.openBundledManifest().use { RuntimeManifest.parse(it.readBytes()).version }, store.installedManifest()?.version)
            assertTrue(File(store.currentRoot, "opt/dsh/plugins/dsh-mobile-shizuku/lib/workers.js").readText().contains("function allowsDraft("))
        } finally {
            names.forEach(app::deleteSharedPreferences)
            RuntimeFiles.deleteTreeNoFollowFallback(dir, app.cacheDir)
        }
    }
}
