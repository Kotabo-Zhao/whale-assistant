package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject

/** A visible opt-in and short capture lease. Revocation is checked before and after each screenshot. */
internal class ScreenWatchAccess(context: Context) {
    private val prefs = context.getSharedPreferences("screen_watch", Context.MODE_PRIVATE)
    fun status(): JSONObject = JSONObject().put("enabled", prefs.getBoolean("enabled", false))
        .put("active", active()).put("owner", owner)
    fun enabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply(); if (!value) stop() }
    fun start(session: String) = synchronized(lock) {
        check(prefs.getBoolean("enabled", false)) { "请先在事项页开启屏幕陪看授权" }
        require(BubbleSessionRegistry.valid(session))
        check(!active() || owner == session) { "已有其他会话正在看屏幕" }
        owner = session; expires = SystemClock.elapsedRealtime() + 120_000
    }
    fun requireOwner(session: String) {
        check(prefs.getBoolean("enabled", false) && active() && owner == session) { "屏幕陪看已停止或未授权" }
    }
    fun stop() = synchronized(lock) { owner = ""; expires = 0 }
    fun frame(session: String, screenshot: String): String {
        requireOwner(session)
        val bytes = Base64.decode(screenshot, Base64.DEFAULT)
        val original = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val scale = minOf(1.0, 960.0 / maxOf(original.width, original.height))
            val width = (original.width * scale).toInt(); val height = (original.height * scale).toInt()
            val image = Bitmap.createScaledBitmap(original, width, height, true)
            try {
                val pixels = IntArray(width * height); image.getPixels(pixels, 0, width, 0, 0, width, height)
                val rgb = ByteArray(pixels.size * 3)
                pixels.forEachIndexed { index, color -> rgb[index * 3] = (color shr 16).toByte(); rgb[index * 3 + 1] = (color shr 8).toByte(); rgb[index * 3 + 2] = color.toByte() }
                requireOwner(session)
                return JSONObject().put("width", width).put("height", height).put("rgb", Base64.encodeToString(rgb, Base64.NO_WRAP)).toString()
            } finally { if (image !== original) image.recycle() }
        } finally { original.recycle() }
    }
    private fun active() = expires > SystemClock.elapsedRealtime()
    companion object { private val lock = Any(); @Volatile private var owner = ""; @Volatile private var expires = 0L }
}
