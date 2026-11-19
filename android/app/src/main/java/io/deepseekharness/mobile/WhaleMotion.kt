package io.deepseekharness.mobile

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import android.webkit.WebView

/** Transient surface motion. Geometry, user opacity and background work keep their owners. */
internal object WhaleMotion {
    fun enabled()=ValueAnimator.areAnimatorsEnabled()
    fun enter(view:View) {
        view.animate().cancel()
        view.alpha=1f;view.translationY=0f
        if(!enabled()) return
        val gentle=view.context.getSharedPreferences("whale-interface",0).getString("style","cute")=="cute"
        view.alpha=0f
        view.scaleX=.975f; view.scaleY=.975f
        view.translationY=(if(gentle) 10 else 6)*view.resources.displayMetrics.density
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(if(gentle) 280 else 220)
            .setInterpolator(PathInterpolator(.22f,1f,.36f,1f))
            .withEndAction {view.alpha=1f;view.scaleX=1f;view.scaleY=1f;view.translationY=0f}.start()
    }
    fun overlay(params:android.view.WindowManager.LayoutParams, critical:Boolean=false) {
        params.windowAnimations=if(!enabled()) 0 else if(critical) R.style.WhaleControlMotion else R.style.WhaleOverlayMotion
    }
    fun feedback(view:View) {
        view.animate().cancel()
        view.scaleX=1f;view.scaleY=1f
        if(!enabled()) return
        view.animate().scaleX(.95f).scaleY(.95f).setDuration(55).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(PathInterpolator(.22f,1f,.36f,1f)).start()
        }.start()
    }
    fun webCSS() = """
        @keyframes whale-surface-in {from{opacity:0;translate:0 5px}to{opacity:1;translate:0 0}}
        button,input,textarea {transition:background-color 140ms ease-out,border-color 140ms ease-out,box-shadow 140ms ease-out}
        button:active {scale:.975}
        [role=dialog],[role=menu] {animation:whale-surface-in 220ms cubic-bezier(.2,.7,.2,1) backwards}
        @media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}button:active{scale:1}}
        :root[data-motion-reduced=true] *,:root[data-motion-reduced=true] *::before,:root[data-motion-reduced=true] *::after{animation:none!important;transition:none!important}
        :root[data-motion-reduced=true] button:active{scale:1}
    """.trimIndent()
    fun sync(web:WebView) {
        val reduced=!enabled()
        web.evaluateJavascript("if(document.documentElement)document.documentElement.dataset.motionReduced='$reduced'",null)
    }
}
