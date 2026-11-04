package io.deepseekharness.mobile.companion

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import io.deepseekharness.mobile.R
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WhaleScreenConsentActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state == null) {
            if (getSystemService(KeyguardManager::class.java).isDeviceLocked) { finish(); return }
            val consent = getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
            runCatching { startActivityForResult(consent, 1) }.onFailure {
                Toast.makeText(this, "此设备没有可用的系统截屏入口。", Toast.LENGTH_LONG).show(); finish()
            }
        }
    }
    @Deprecated("Receives the system's one-time screen capture grant")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 1 && result == RESULT_OK && data != null) {
            runCatching {
                startForegroundService(Intent(this, WhaleScreenCollectService::class.java).putExtra("consent", data))
            }.onFailure { Toast.makeText(this, "未能开始本次屏幕收藏。", Toast.LENGTH_SHORT).show() }
        }
        finish()
    }
}

/** A single user-initiated frame. Stop projection before compressing or recognizing the image. */
class WhaleScreenCollectService : Service() {
    private val thread = HandlerThread("whale-screen-collect")
    private lateinit var io: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var started = false
    private var finished = false
    private val timeout = Runnable { end("未收到可用画面，请回到来源页面再试。") }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { if (!finished) end("系统已结束本次屏幕收藏。") }
    }
    override fun onCreate() { super.onCreate(); thread.start(); io = Handler(thread.looper) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) {
            notice("上一张屏幕收藏正在处理，请稍后再试。")
            return START_NOT_STICKY
        }
        started = true
        val channel = "whale-single-screen"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "单次屏幕收藏", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_overlay_ball)
            .setContentTitle("正在收藏这一屏").setContentText("取得一帧后结束屏幕采集").build()
        @Suppress("DEPRECATION")
        val grant = intent?.getParcelableExtra<Intent>("consent")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(6731, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(6731, notification)
            requireNotNull(grant)
            io.postDelayed({ runCatching { startCapture(grant) }.onFailure { end("未能获得本次屏幕授权。") } }, 400)
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun startCapture(grant: Intent) {
        if (finished || getSystemService(KeyguardManager::class.java).isDeviceLocked) { end("请解锁后再收藏。"); return }
        val wm = getSystemService(WindowManager::class.java)
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels; val height = metrics.heightPixels
        val capture = requireNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, grant))
        projection = capture; capture.registerCallback(callback, io)
        val buffer = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = buffer
        buffer.setOnImageAvailableListener({ available ->
            if (finished) return@setOnImageAvailableListener
            val frame = available.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = runCatching { frame.use { copyFrame(it) } }.getOrNull()
            if (bitmap == null) { end("没有可读取的画面。"); return@setOnImageAvailableListener }
            finished = true
            releaseProjection()
            val message = try {
                if (getSystemService(KeyguardManager::class.java).isDeviceLocked || blank(bitmap)) "当前画面为空或受系统保护，没有保存。"
                else save(bitmap)
            } catch (_: Exception) { "保存未完成，请检查资料库。" } finally { bitmap.recycle() }
            notice(message); stopSelf()
        }, io)
        display = capture.createVirtualDisplay("Whale one-shot", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, buffer.surface, null, io)
        io.postDelayed(timeout, 6000)
    }
    private fun copyFrame(image: Image): Bitmap {
        val plane = image.planes[0]
        require(plane.pixelStride == 4)
        val stride = image.width * 4
        val pixels = if (plane.rowStride == stride) plane.buffer else {
            val compact = java.nio.ByteBuffer.allocateDirect(stride * image.height)
            val src = plane.buffer.duplicate()
            val base = src.position()
            for (row in 0 until image.height) {
                val start = base + row * plane.rowStride
                src.limit(start + stride); src.position(start)
                compact.put(src)
            }
            compact.rewind()
            compact
        }
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        return try { bitmap.copyPixelsFromBuffer(pixels); bitmap } catch (error: Exception) { bitmap.recycle(); throw error }
    }
    private fun blank(bitmap: Bitmap): Boolean {
        for (y in 1..16) for (x in 1..16) {
            val color = bitmap.getPixel(bitmap.width*x/17, bitmap.height*y/17)
            if ((color and 0x00ffffff) != 0) return false
        }
        return true
    }
    private fun save(bitmap: Bitmap): String {
        val scratch = File.createTempFile("screen-", ".png", cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            java.security.DigestOutputStream(scratch.outputStream(), digest).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val store = CompanionKnowledgeStore(this)
            val name = "屏幕收藏 " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date()) + ".png"
            val origin = WhaleShareSource("屏幕收藏", null)
            KnowledgeObjects(this).store(scratch, hash, 64L*1024*1024)
            store.registerOriginal(hash, name, "image/png", origin)
            val words = runCatching { WhaleImageText.read(bitmap) }.getOrDefault("")
            if (words.isBlank()) return "屏幕原图已保存，未识别出可检索的文字。"
            store.addDocument(words, hash, name, requireOriginal = true, origin = origin, mediaKind = "image_ocr")
            store.updateOriginalCoverage(hash, "extracted_text")
            return "屏幕已收藏，可按画面中的文字检索。"
        } finally { scratch.delete() }
    }
    private fun releaseProjection() {
        io.removeCallbacks(timeout)
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.unregisterCallback(callback); projection?.stop(); projection = null
    }
    private fun notice(message: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() } }
    private fun end(message: String) {
        if (finished) return
        finished = true; releaseProjection(); notice(message); stopSelf()
    }
    override fun onDestroy() {
        io.removeCallbacksAndMessages(null)
        io.post { finished = true; releaseProjection(); thread.quitSafely() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
