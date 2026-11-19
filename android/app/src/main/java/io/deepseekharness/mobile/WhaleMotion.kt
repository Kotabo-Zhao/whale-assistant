package io.deepseekharness.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.PathInterpolator
import android.webkit.WebView

/**
 * 原生双风格与低功耗自适应动效引擎。
 *
 * - 极简·类 Grok（simple）：140ms 干脆减速曲线 + 4dp 轻位移，零过冲；
 * - 鲸鱼娘·可爱（cute）：260ms 柔和微弹曲线 + 10dp 轻浮入场 + 触感按压回弹；
 * - 全程使用 `withLayer()` 硬件合成层，并在系统省电模式/低电量/减少动效时自动降级为零开销直切。
 */
internal object WhaleMotion {
    private val grokCurve = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val cuteCurve = PathInterpolator(0.22f, 1.12f, 0.36f, 1f)

    fun enabled(context: Context? = null): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        if (context == null) return true
        return WhalePowerGovernor.current(context) != WhalePowerGovernor.Profile.ECO
    }

    fun enter(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
        val profile = WhalePowerGovernor.current(view.context)
        if (!ValueAnimator.areAnimatorsEnabled() || profile == WhalePowerGovernor.Profile.ECO) return

        val cute = view.context.getSharedPreferences("whale-interface", 0).getString("style", "cute") != "simple"
        val scale = WhalePowerGovernor.durationScale(profile)
        val duration = ((if (cute) 260L else 145L) * scale).toLong().coerceAtLeast(80L)
        val liftDp = if (cute) 10f else 4f
        val startScale = if (cute) 0.965f else 0.988f

        view.alpha = 0f
        view.scaleX = startScale
        view.scaleY = startScale
        view.translationY = liftDp * view.resources.displayMetrics.density
        view.animate()
            .withLayer()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(if (cute) cuteCurve else grokCurve)
            .withEndAction {
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationY = 0f
            }
            .start()
    }

    fun overlay(params: android.view.WindowManager.LayoutParams, critical: Boolean = false) {
        params.windowAnimations = if (!enabled()) 0 else if (critical) R.style.WhaleControlMotion else R.style.WhaleOverlayMotion
    }

    fun feedback(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        val profile = WhalePowerGovernor.current(view.context)
        if (!ValueAnimator.areAnimatorsEnabled() || profile == WhalePowerGovernor.Profile.ECO) return
        val cute = view.context.getSharedPreferences("whale-interface", 0).getString("style", "cute") != "simple"
        val downScale = if (cute) 0.94f else 0.97f
        view.animate()
            .withLayer()
            .scaleX(downScale)
            .scaleY(downScale)
            .setDuration(48L)
            .withEndAction {
                view.animate()
                    .withLayer()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(if (cute) 170L else 110L)
                    .setInterpolator(if (cute) cuteCurve else grokCurve)
                    .start()
            }
            .start()
    }

    fun webCSS() = """
        @keyframes whale-surface-in {from{opacity:0;transform:translate3d(0,5px,0)}to{opacity:1;transform:translate3d(0,0,0)}}
        button,input,textarea {transition:background-color 120ms ease-out,border-color 120ms ease-out,opacity 120ms ease-out,transform 110ms ease-out}
        button:active {transform:scale(.975)}
        [role=dialog],[role=menu] {animation:whale-surface-in 180ms cubic-bezier(.16,1,.3,1) backwards}
        @media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}button:active{transform:none}}
        :root[data-motion-reduced=true] *,:root[data-motion-reduced=true] *::before,:root[data-motion-reduced=true] *::after,
        :root[data-power-profile=eco] *,:root[data-power-profile=eco] *::before,:root[data-power-profile=eco] *::after{animation:none!important;transition:none!important}
        :root[data-motion-reduced=true] button:active,:root[data-power-profile=eco] button:active{transform:none}
    """.trimIndent()

    fun sync(web: WebView) {
        val profile = WhalePowerGovernor.current(web.context)
        val reduced = !ValueAnimator.areAnimatorsEnabled() || profile == WhalePowerGovernor.Profile.ECO
        web.evaluateJavascript(
            "if(document.documentElement){document.documentElement.dataset.motionReduced='$reduced';document.documentElement.dataset.powerProfile='${profile.wireValue}';}",
            null,
        )
    }
}
