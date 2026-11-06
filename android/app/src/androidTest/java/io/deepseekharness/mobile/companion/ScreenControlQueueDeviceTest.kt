package io.deepseekharness.mobile.companion

import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.DeviceControlState
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenControlQueueDeviceTest {
    @Test fun resumeDoesNotReplayAnActionQueuedBeforePause() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val called=AtomicBoolean(false);val queuedEntered=CountDownLatch(1)
        DeviceControlState.resume()
        fun result()=DeviceCommandResult(true,0,"{}",false,null)
        val first=Thread {
            runCatching{DeviceControlState.execute(app,false,"隔离阻塞测试") {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));result()
            }}
        }
        val second=Thread {
            queuedEntered.countDown()
            runCatching{DeviceControlState.execute(app,false,"隔离排队测试") {called.set(true);result()}}
        }
        try {
            first.start();assertTrue(entered.await(3,TimeUnit.SECONDS))
            second.start();assertTrue(queuedEntered.await(3,TimeUnit.SECONDS))
            val deadline=android.os.SystemClock.elapsedRealtime()+3000
            while(second.state!=Thread.State.WAITING && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(5)
            assertEquals(Thread.State.WAITING,second.state)
            DeviceControlState.pause();DeviceControlState.resume();release.countDown()
            first.join(3000);second.join(3000)
            assertFalse(first.isAlive);assertFalse(second.isAlive)
            assertFalse("恢复后重放了暂停前的排队动作",called.get())
        } finally {release.countDown();first.join(3000);second.join(3000);DeviceControlState.resume()}
    }
}
