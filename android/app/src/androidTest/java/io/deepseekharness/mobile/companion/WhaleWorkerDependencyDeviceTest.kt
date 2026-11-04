package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.ProcessProbe
import io.deepseekharness.mobile.runtime.RuntimeLaunchResolver
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class WhaleWorkerDependencyDeviceTest {
    @Test fun bundledSchedulerRecoversDependencyWithoutReleasingItEarly() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RuntimeStore(app)
        val tmp = File(store.currentRoot,"tmp")
        val stage = Files.createTempDirectory(tmp.toPath(),"whale-dep-").toFile()
        val modules = File(stage,"node_modules").toPath()
        try {
            // Read only the plugin code from the APK under test. Use an independent ledger.
            app.assets.open("runtime/rootfs.bundle").use { stream ->
                TarArchiveInputStream(GZIPInputStream(stream.buffered())).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val relative = entry.name.removePrefix("opt/dsh/plugins/dsh-mobile-shizuku/")
                        if (relative == entry.name || !entry.isFile ||
                            !(relative == "package.json" || Regex("lib/[a-zA-Z0-9_-]+\\.js").matches(relative))) continue
                        val target = File(stage,relative)
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        target.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
            Files.createSymbolicLink(modules,java.nio.file.Paths.get("/opt/dsh/plugins/dsh-mobile-shizuku/node_modules"))
            File(stage,"check.mjs").writeText("""
                import assert from 'node:assert/strict'
                import {WorkerQueue,activeRowCount} from './lib/workers.js'
                const path = new URL('ledger', import.meta.url).pathname
                const queue = new WorkerQueue(path)
                for (const [id,state,deps] of [['source','failed',[]],['report','queued',['source']],['other','queued',[]]]) {
                    queue.save({id,state,dependsOn:deps,title:id,owner:'qa',sessionId:id,mode:'read',createdAt:1})
                }
                assert.deepEqual(queue.runnable().map(row=>row.id), ['other'])
                const restored = new WorkerQueue(path)
                assert.match(restored.rows.get('report').waitReason,/source.*失败/)
                assert.equal(activeRowCount([restored.rows.get('source'),restored.rows.get('report')]),0)
                restored.save({...restored.rows.get('source'),state:'queued'})
                assert.ok(!restored.runnable().some(row=>row.id==='report'))
                restored.save({...restored.rows.get('source'),state:'completed'})
                assert.ok(restored.runnable().some(row=>row.id==='report'))
                assert.equal(restored.rows.get('report').waitReason,undefined)
                console.log('DEPENDENCY_RECOVERY_OK')
            """.trimIndent())
            val resolver = RuntimeLaunchResolver(app,store,includeCredentials=false,selectHarness=false)
            val spec = resolver.launch(listOf("/bin/sh","-c","exec node \"\$1\"","whale","/tmp/${stage.name}/check.mjs"))
            val result = ProcessProbe.run(spec,store.currentRoot,60,outputLimit=8192)
            assertTrue(result.output.takeLast(1200),result.succeeded && result.output.contains("DEPENDENCY_RECOVERY_OK"))
        } finally {
            Files.deleteIfExists(modules)
            check(stage.parentFile == tmp)
            stage.walkBottomUp().forEach { it.delete() }
        }
    }
}
