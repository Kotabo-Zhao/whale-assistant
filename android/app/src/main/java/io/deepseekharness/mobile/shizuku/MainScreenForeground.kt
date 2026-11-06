package io.deepseekharness.mobile.shizuku

import android.content.ComponentName
import org.json.JSONObject

/** Read the focused main-display task through the existing privileged service; no shell sessions. */
internal object MainScreenForeground {
    fun read():String = try {
        val manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
        val api=Class.forName("android.app.IActivityTaskManager")
        val getter=api.methods.first{it.name in setOf("getFocusedRootTaskInfo","getFocusedStackInfo") && it.parameterCount==0}
        val info=getter.invoke(manager) ?: error("unavailable")
        val display=info.javaClass.getField("displayId").getInt(info)
        // If another display has focus, refuse rather than infer a target on display 0.
        check(display==0)
        val component=info.javaClass.getField("topActivity").get(info) as? ComponentName ?: error("unavailable")
        JSONObject().put("packageName",component.packageName).put("displayId",display).toString()
    } catch(_:Exception) { JSONObject().put("errorCode","SCREEN_APP_UNAVAILABLE").toString() }
}
