package io.deepseekharness.mobile.companion

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.OverlayBallService

internal object PetControlDeviceUi {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    fun field(value:Any,key:String):Any? = value.javaClass.getDeclaredField(key).apply{isAccessible=true}.get(value)
    fun service():Any?=OverlayBallService::class.java.getDeclaredField("activeService").apply{isAccessible=true}.get(null)
    fun await(label:String,check:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+15000
        while(!check()){check(SystemClock.elapsedRealtime()<until){label};Thread.sleep(30)}
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    fun tap(view:View) {
        val xy=IntArray(2)
        inst.runOnMainSync {
            check(view.isShown && view.width>0 && view.height>0)
            view.getLocationOnScreen(xy)
            val size=android.graphics.Point().also {view.context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealSize(it)}
            val visible=android.graphics.Rect(xy[0],xy[1],xy[0]+view.width,xy[1]+view.height)
            check(visible.intersect(0,0,size.x,size.y)){"control outside display: $visible"}
            xy[0]=visible.centerX();xy[1]=visible.centerY()
        }
        val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,xy[0].toFloat(),xy[1].toFloat(),0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {check(inst.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
            if(action==MotionEvent.ACTION_DOWN) SystemClock.sleep(32)
        }
    }
    fun click(label:String) {
        await("pet attached") {service()?.let{field(it,"ballView") as? View}?.isAttachedToWindow==true}
        val service=checkNotNull(service())
        if(field(service,"actionMenu")==null) tap(field(service,"ballView") as View)
        var button:TextView?=null
        await("pet menu button: $label") {
            inst.runOnMainSync { button=(field(service,"actionMenu") as? View)?.let{views(it).filterIsInstance<TextView>().firstOrNull{v->v.text.toString()==label && v.isShown}} }
            button!=null
        }
        tap(checkNotNull(button))
        await("control action applied: $label") {
            val phase=io.deepseekharness.mobile.DeviceControlState.current.phase
            when(label) {
                "暂停操作" -> phase=="paused"
                "停止操作" -> phase=="stopped"
                "恢复控制" -> phase!="paused" && phase!="stopped"
                else -> field(service,"actionMenu")==null
            }
        }
    }
}
