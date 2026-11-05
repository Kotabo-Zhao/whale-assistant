package io.deepseekharness.mobile.companion

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit opt-in: this is sustained local ASR load, not meeting accuracy acceptance. */
class WhaleContinuousAudioTest {
    @Test fun recognizesVoicedAudioPastTenMinutes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("continuousAudio") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        assertEquals("有运行中的用户任务",0,WorkerDashboardStore(app).page(limit=0).getInt("running"))
        assertFalse("有用户资料待解析",CompanionKnowledgeStore(app).withParseDatabase { it.hasPendingParse() })
        val clip = inst.context.assets.open("whale-knowledge-mandarin.wav").use { it.readBytes() }
        val source = ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (String(clip,offset,4,Charsets.US_ASCII) != "data") {
            val length = source.getInt(offset+4)
            offset += 8 + length + (length and 1)
        }
        val speechBytes = source.getInt(offset+4)
        val repetitions = 116
        val size = speechBytes * repetitions
        val durationSeconds = size / 32000.0
        assertTrue(durationSeconds > 650)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(size+36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size).array()
        val wav = File.createTempFile("continuous-acceptance-",".wav",app.cacheDir)
        try {
            wav.outputStream().buffered().use { out ->
                out.write(header)
                repeat(repetitions) { out.write(clip,offset+8,speechBytes) }
            }
            val start = SystemClock.elapsedRealtime()
            val transcript = WhaleAudioTranscript.recognize(app,wav)
            val elapsed = SystemClock.elapsedRealtime()-start
            val lines = transcript.lines().filter { it.isNotBlank() }
            val timestamps = lines.map { line ->
                val match = requireNotNull(Regex("^\\[(\\d+):(\\d{2})] ").find(line))
                match.groupValues[1].toInt()*60 + match.groupValues[2].toInt()
            }
            inst.sendStatus(0,Bundle().apply {
                putString("fixtureOpening",lines.take(3).joinToString("\n"))
                putString("fixtureEnding",lines.takeLast(3).joinToString("\n"))
            })
            assertTrue("持续语音没有分段",lines.size > 50)
            assertTrue("末段未识别",timestamps.last() >= 650)
            assertTrue("时间戳发生倒退",timestamps.zipWithNext().all { (a,b) -> b >= a })
            // ASR sentence boundaries need not match the fixture's file boundary.
            val opening = lines.filterIndexed { index, _ -> timestamps[index] < 6 }.joinToString("\n")
            assertTrue("开头未识别",opening.contains("开会") && opening.contains("笔记本"))
            assertTrue("末段内容未识别",lines.last().contains("笔记本"))
            assertFalse("意外截断",transcript.contains("[内容过长，已截取]"))
            inst.sendStatus(0,Bundle().apply {
                putDouble("audioDurationSeconds",durationSeconds)
                putLong("recognitionMs",elapsed)
                putInt("segments",lines.size)
                putInt("lastSegmentSeconds",timestamps.last())
                putString("continuousAudio","repeated voiced fixture; no meeting accuracy claim")
            })
        } finally { assertTrue("测试音频清理失败",wav.delete()) }
    }
}
