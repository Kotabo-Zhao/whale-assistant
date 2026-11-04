package io.deepseekharness.mobile.companion

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.view.DragEvent
import android.view.WindowManager
import android.widget.Toast

/** Private, transient UI owns the system grant until the selected content has been read. */
class WhaleDirectCaptureActivity : WhaleMemoryShareActivity() {
    private var waitingForClipboard = false

    override fun receiveRequest(savedInstanceState: android.os.Bundle?) {
        if (savedInstanceState != null) { finish(); return }
        when (intent.action) {
            CLIPBOARD -> {
                // Android grants clipboard reads to the focused window. The tile itself never reads it.
                waitingForClipboard = true
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                if (hasWindowFocus()) readClipboard()
            }
            DROP -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                @Suppress("DEPRECATION")
                val event = intent.getParcelableExtra<DragEvent>(EXTRA_DROP)
                if (event?.action != DragEvent.ACTION_DROP || event.clipData == null) { finish(); return }
                val grant = runCatching { requestDragAndDropPermissions(event) }.getOrNull()
                receiveClip(event.clipData, "桌宠投放") { grant?.release() }
            }
            else -> finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && waitingForClipboard) readClipboard()
    }

    private fun readClipboard() {
        waitingForClipboard = false
        val clip = runCatching { getSystemService(ClipboardManager::class.java).primaryClip }.getOrNull()
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        receiveClip(clip, "剪贴板投放") {}
    }

    private fun receiveClip(clip: ClipData?, source: String, release: () -> Unit) {
        if (clip == null || clip.itemCount !in 1..10) {
            release(); Toast.makeText(this, "没有可投放的内容，或一次超过 10 项。", Toast.LENGTH_SHORT).show(); finish(); return
        }
        val items = (0 until clip.itemCount).map(clip::getItemAt)
        val files = items.mapNotNull { it.uri?.takeIf { uri -> uri.scheme == "content" } }.distinct()
        importPayload(files,clip.description.getMimeType(0),release,source = { text ->
            val inferred = WhaleShareSource.from(null,text,false)
            if (inferred.label == "系统分享") inferred.copy(label = source) else inferred
        }) { WhaleSharedText.fromShare(null,items,emptyList()) }
    }

    companion object {
        const val CLIPBOARD = "io.deepseekharness.mobile.COLLECT_CLIPBOARD"
        private const val DROP = "io.deepseekharness.mobile.COLLECT_DROP"
        private const val EXTRA_DROP = "whale_drag_event"

        fun accepts(description: ClipDescription?): Boolean = description != null &&
            listOf("text/*", "image/*", "audio/*", "application/*").any(description::hasMimeType)

        fun drop(context: Context, event: DragEvent): Boolean {
            val clip = event.clipData ?: return false
            // The event is Parcelable. Forward it only to this same-package private activity.
            if (clip.itemCount !in 1..10 || (0 until clip.itemCount).sumOf { i ->
                    val item = clip.getItemAt(i)
                    (item.text?.length ?: 0).toLong() + (item.htmlText?.length ?: 0)
                } > 100_000L) {
                Toast.makeText(context, "拖入内容过多，请改用系统分享。", Toast.LENGTH_SHORT).show()
                return false
            }
            return runCatching {
                context.startActivity(Intent(context, WhaleDirectCaptureActivity::class.java)
                    .setAction(DROP).putExtra(EXTRA_DROP, event).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            }.isSuccess
        }
    }
}

open class WhaleMemoryTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { collect() } else collect()
    }
    protected open fun destination() = Intent(this, WhaleDirectCaptureActivity::class.java).setAction(WhaleDirectCaptureActivity.CLIPBOARD)
    private fun collect() {
        val request = destination().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, request, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(request)
        }
    }
}

class WhaleScreenTileService : WhaleMemoryTileService() {
    override fun destination() = Intent(this, WhaleScreenConsentActivity::class.java)
}
