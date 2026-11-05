package io.deepseekharness.mobile.runtime

import android.content.Context
import org.json.JSONObject

/** Bounded stage timings contain no screenshot, text, package name or command arguments. */
internal object DeviceObservationTiming {
    @Synchronized fun record(context: Context, stage: String, elapsed: Long) {
        val prefs = context.getSharedPreferences("device-observation-timing", Context.MODE_PRIVATE)
        val rows = org.json.JSONArray(prefs.getString("samples","[]"))
        val tail = (0 until rows.length()).toList().takeLast(49).map(rows::getJSONObject) +
            JSONObject().put("stage",stage.take(40)).put("elapsedMs",elapsed).put("at",System.currentTimeMillis())
        prefs.edit().putString("samples",org.json.JSONArray(tail).toString()).apply()
    }
}
