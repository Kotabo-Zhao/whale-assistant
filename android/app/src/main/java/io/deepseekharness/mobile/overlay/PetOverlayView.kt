package io.deepseekharness.mobile.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Window visibility can change without detaching or changing the overlay's bounds. */
internal class PetOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    var onPresentationChanged: (() -> Unit)? = null
    private val notifyPresentation = Runnable { onPresentationChanged?.invoke() }

    private fun presentationChanged() {
        val listener = onPresentationChanged ?: return
        removeCallbacks(notifyPresentation)
        if (!isAttachedToWindow || !isShown || windowVisibility != View.VISIBLE) listener()
        // Attach/window callbacks can precede the first completed traversal. Recheck after it,
        // once per visibility edge; no periodic watchdog or hidden-window animation loop.
        else post(notifyPresentation)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        presentationChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        presentationChanged()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(notifyPresentation)
        super.onDetachedFromWindow()
    }
}
