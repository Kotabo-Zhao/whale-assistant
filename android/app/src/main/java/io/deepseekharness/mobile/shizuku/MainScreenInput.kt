package io.deepseekharness.mobile.shizuku

import android.graphics.Point
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject

/** Main-display input in the already authorized shell process. Never retries a partial gesture. */
internal class MainScreenInput {
    private data class Access(val manager:Any,val inject:java.lang.reflect.Method,val display:java.lang.reflect.Method)
    private val access by lazy {
        val type=Class.forName("android.hardware.input.InputManager")
        Access(type.getMethod("getInstance").invoke(null)!!,
            type.getMethod("injectInputEvent",InputEvent::class.java,Int::class.javaPrimitiveType),
            InputEvent::class.java.getMethod("setDisplayId",Int::class.javaPrimitiveType))
    }
    private class Job {
        var cancelled=false
        var down=false
        var downTime=0L
        var x=0f;var y=0f
    }
    private val jobs=HashMap<String,Job>()
    private val cancelled=LinkedHashSet<String>()
    private val execution=java.util.concurrent.locks.ReentrantLock()
    private fun emit(event:InputEvent) {
        access.display.invoke(event,0)
        check(access.inject.invoke(access.manager,event,0)==true){"输入未被系统接受"}
    }
    private fun motion(job:Job,phase:Int) {
        val event=MotionEvent.obtain(job.downTime,SystemClock.uptimeMillis(),phase,job.x,job.y,0)
        try {event.source=InputDevice.SOURCE_TOUCHSCREEN;emit(event)} finally {event.recycle()}
    }
    fun cancel(id:String) {
        val job=synchronized(jobs) {
            cancelled.add(id)
            while(cancelled.size>64) cancelled.remove(cancelled.first())
            jobs[id]
        } ?: return
        synchronized(job) {
            job.cancelled=true
            if(job.down) {runCatching{motion(job,MotionEvent.ACTION_CANCEL)};job.down=false}
        }
    }
    fun closeAll() {synchronized(jobs){jobs.keys.toList()}.forEach(::cancel)}
    fun execute(id:String,parameters:String,owner:IBinder):String {
        require(id.length in 1..64 && parameters.length<=2048)
        val p=JSONObject(parameters)
        val action=p.getString("action")
        require(action in setOf("tap","swipe","back"))
        fun integer(key:String):Int {
            val v=p.get(key);require(v is Int || v is Long)
            val n=(v as Number).toLong();require(n in 0..65535);return n.toInt()
        }
        val width=integer("width");val height=integer("height")
        fun point(x:String,y:String):Pair<Int,Int> = (integer(x) to integer(y)).also {
            require(it.first<width && it.second<height){"坐标超出屏幕"}
        }
        val start=if(action=="back") 0 to 0 else point("x","y")
        val end=if(action=="swipe") point("endX","endY") else start
        val duration=if(action=="swipe") integer("durationMs").also{require(it in 80..1000)} else 0
        val job=Job()
        synchronized(jobs) {
            check(!jobs.containsKey(id))
            job.cancelled=cancelled.remove(id)
            jobs[id]=job
        }
        val death=IBinder.DeathRecipient {cancel(id)}
        var linked=false
        var locked=false
        val started=SystemClock.elapsedRealtime()
        var events=0
        try {
            owner.linkToDeath(death,0);linked=true
            execution.lock();locked=true
            // Query the physical display in the shell process, immediately before injecting.
            val type=Class.forName("android.hardware.display.DisplayManagerGlobal")
            val manager=type.getMethod("getInstance").invoke(null)
            val display=type.getMethod("getRealDisplay",Int::class.javaPrimitiveType).invoke(manager,0) as android.view.Display
            val actual=Point();display.getRealSize(actual)
            check(actual.x==width && actual.y==height){"SCREEN_GEOMETRY_CHANGED"}
            synchronized(job) {
                check(!job.cancelled && owner.isBinderAlive){"SCREEN_ACTION_CANCELLED"}
                if(action=="back") {
                    val now=SystemClock.uptimeMillis()
                    emit(KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));events++
                    // Finish this atomic key pair even if cancellation arrives concurrently.
                    emit(KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));events++
                } else {
                    job.downTime=SystemClock.uptimeMillis();job.x=start.first.toFloat();job.y=start.second.toFloat()
                    job.down=true;motion(job,MotionEvent.ACTION_DOWN);events++
                }
            }
            if(action=="swipe") {
                val steps=(duration+15)/16
                for(step in 1..steps) {
                    val target=job.downTime+duration*step/steps
                    val remaining=target-SystemClock.uptimeMillis()
                    if(remaining>0) SystemClock.sleep(remaining)
                    synchronized(job) {
                        check(!job.cancelled && owner.isBinderAlive){"SCREEN_ACTION_CANCELLED"}
                        job.x=start.first+(end.first-start.first)*step.toFloat()/steps
                        job.y=start.second+(end.second-start.second)*step.toFloat()/steps
                        motion(job,MotionEvent.ACTION_MOVE);events++
                    }
                }
            }
            if(action!="back") synchronized(job) {
                check(!job.cancelled && owner.isBinderAlive){"SCREEN_ACTION_CANCELLED"}
                motion(job,MotionEvent.ACTION_UP);job.down=false;events++
            }
            return JSONObject().put("injected",true).put("channel","shizuku_in_process")
                .put("action",action).put("events",events).put("injectionMs",SystemClock.elapsedRealtime()-started).toString()
        } finally {
            synchronized(job) {if(job.down){runCatching{motion(job,MotionEvent.ACTION_CANCEL)};job.down=false}}
            synchronized(jobs){jobs.remove(id);cancelled.remove(id)}
            if(linked) runCatching{owner.unlinkToDeath(death,0)}
            if(locked) execution.unlock()
        }
    }
}
