package io.deepseekharness.mobile.features

/** Runs the enabled revision in the same isolated host, in a compact window above the source app. */
class WhalePetFeatureActivity : WhaleFeatureActivity() {
    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        window.setLayout(minOf((540 * metrics.density).toInt(), (metrics.widthPixels * .92).toInt()),
            minOf((600 * metrics.density).toInt(), (metrics.heightPixels * .83).toInt()))
    }
}
