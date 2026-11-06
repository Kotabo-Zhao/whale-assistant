package io.deepseekharness.mobile.shizuku

import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A requested native frame uses a pipe; no terminal, PNG codec or base64 is involved. */
internal class MainScreenCapture {
    private class Job(val write:ParcelFileDescriptor,val owner:IBinder) {
        @Volatile var process:Process?=null
        @Volatile var closed=false
        lateinit var death:IBinder.DeathRecipient
        @Synchronized fun close() {
            closed=true
            runCatching{write.close()}
            process?.destroyForcibly()
        }
    }
    private val jobs=ConcurrentHashMap<String,Job>()
    private val writer=Executors.newSingleThreadExecutor()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    @Synchronized fun open(id:String,owner:IBinder):ParcelFileDescriptor {
        require(UUID.fromString(id).toString()==id)
        check(jobs.isEmpty()){ "SCREEN_CAPTURE_BUSY" }
        check(android.os.Build.VERSION.SDK_INT>=29){"SCREEN_RAW_UNSUPPORTED"}
        val pipe=ParcelFileDescriptor.createReliablePipe()
        val job=Job(pipe[1],owner)
        job.death=IBinder.DeathRecipient{cancel(id)}
        try {owner.linkToDeath(job.death,0)} catch(error:Exception) {pipe.forEach{it.close()};throw error}
        jobs[id]=job
        val timeout=timer.schedule({cancel(id)},5,TimeUnit.SECONDS)
        try {
            writer.execute {
                try {
                    val process=synchronized(job) {
                        check(!job.closed)
                        ProcessBuilder("/system/bin/screencap").redirectError(java.io.File("/dev/null")).start().also{job.process=it}
                    }
                    ParcelFileDescriptor.AutoCloseOutputStream(job.write).use { output->
                        process.inputStream.use{input->input.copyTo(output,64*1024)}
                        check(process.waitFor()==0){"SCREEN_RAW_FAILED"}
                    }
                } catch(_:Exception) {
                    runCatching{job.write.closeWithError("SCREEN_RAW_FAILED")}
                } finally {
                    timeout.cancel(false);job.close()
                    runCatching{owner.unlinkToDeath(job.death,0)}
                    jobs.remove(id,job)
                }
            }
        } catch(error:Exception) {
            timeout.cancel(false);job.close();jobs.remove(id,job);runCatching{owner.unlinkToDeath(job.death,0)};pipe[0].close();throw error
        }
        return pipe[0]
    }
    fun cancel(id:String) {jobs[id]?.close()}
    fun closeAll() {jobs.keys.toList().forEach(::cancel)}
}
