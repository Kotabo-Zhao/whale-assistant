package io.deepseekharness.mobile

import android.webkit.WebView
import org.json.JSONObject

/** Local, fixed styling for the isolated Harness page; no script comes from the guest. */
internal object HarnessVisualStyle {
    private val css = """
        :root { --whale-radius: WHALE_RADIUSpx; }
        /* App conversations expose the outer assistant, never injected events or tool payloads. */
        [data-chat-flow-kind="context"], [data-chat-flow-kind="turn-trigger"],
        [data-chat-flow-kind]:has([data-tool="mobile_workers"], [data-tool="mobile_worker_report"],
          [data-tool="mobile_coordinator"], [data-tool="mobile_goals"], [data-tool="mobile_owner_profile"],
          [data-tool="mobile_secretary"]) { display: none !important; }
        :root, body { WHALE_TOKENS }
        body { font-family: "Avenir Next", "Segoe UI", "Microsoft YaHei", sans-serif; }
        [data-phase] :is(textarea, [contenteditable="true"]) {
            border-radius: var(--whale-radius) !important;
            line-height: 1.6;
            box-shadow: 0 4px 16px color-mix(in srgb,var(--dsh-theme-accent) 8%,transparent);
        }
        [data-phase] :is(button, [role="button"]) {
            border-radius: var(--whale-radius);
            transition: background-color 150ms ease, box-shadow 150ms ease;
        }
        [data-phase] :is(textarea, [contenteditable="true"]):focus-visible {
            outline: 2px solid var(--dsh-theme-accent, #315fcf);
            outline-offset: 2px;
        }
        pre, .md-code-block { border-radius: var(--whale-radius); }
        [role="dialog"] { box-shadow: 0 20px 55px rgba(20, 35, 70, .18); }
        [role="dialog"] { border-radius: var(--whale-radius); }
        [data-rightbar-col] { border-radius: var(--whale-radius) 0 0 var(--whale-radius); }
        [data-shell-overlay] { border-radius: 0 var(--whale-radius) var(--whale-radius) 0; }
        :focus-visible { outline-color: var(--dsh-theme-accent, #315fcf); }
    """.trimIndent()

    fun apply(web: WebView) {
        val design=WhaleDesign.of(web.context)
        val ink=design.hex(design.ink); val muted=design.hex(design.muted)
        val line=design.hex(design.line); val soft=design.hex(design.soft); val raised=design.hex(design.raised)
        val accent="var(--dsh-theme-accent)"; val surface="color-mix(in srgb,var(--dsh-theme-surface) calc(var(--dsh-theme-control-opacity,1)*100%),transparent)"
        val tokens=linkedMapOf(
            "--dsw-specific-input-major" to surface, "--dsw-specific-login-input" to raised,
            "--dsw-specific-bubble" to soft, "--dsw-specific-bubble-highlight" to soft,
            "--dsw-menu-surface-fill" to surface,
            "--dsw-alias-label-primary" to ink, "--dsw-alias-label-primary-bluish" to ink,
            "--dsw-alias-label-secondary" to muted, "--dsw-alias-label-tertiary" to muted,
            "--dsw-alias-label-caption" to muted, "--dsw-alias-label-dimmed" to muted,
            "--dsw-alias-label-primary-dimmed" to muted,
            "--dsw-alias-label-primary-foreground" to design.hex(design.onAccent),
            "--dsw-alias-brand-primary" to accent, "--dsw-alias-brand-text" to accent,
            "--dsw-alias-link" to accent, "--dsw-alias-state-business-primary" to accent,
            "--dsw-alias-state-business-tertiary" to soft,
            "--dsw-alias-border-l1" to line, "--dsw-alias-border-l2" to line,
            "--dsw-alias-border-l3" to line, "--dsw-alias-border-l4" to line,
            "--dsw-alias-border-l2-darkmode-thin" to line,
            "--dsw-alias-button-primary-dimmed" to soft,
            "--dsw-alias-button-info-fill" to soft, "--dsw-alias-button-info-hover" to raised,
            "--dsw-alias-button-elevated-fill" to surface, "--dsw-alias-button-floating-fill" to surface,
            "--dsw-alias-button-floating-hover" to raised, "--dsw-alias-button-tool-bar-fill" to surface,
            "--dsw-alias-button-tool-bar-hover" to raised, "--dsw-alias-menu-icon" to muted,
            "--dsw-alias-markdown-code-block" to raised, "--dsw-alias-markdown-code-block-banner" to raised,
            "--dsw-alias-markdown-inline-code" to soft, "--dsw-alias-menu-group-header-fill" to raised,
            "--dsw-alias-scrollbar-bg-l2" to line, "--dsw-alias-scrollbar-hover-l2" to muted,
            "--dsw-radius-panel" to (design.radius.toString()+"px"),
            "--dsw-radius-xl" to (design.radius.toString()+"px"),
            "--dsw-radius-lg" to (if(design.sea) "12px" else "18px"),
            "--dsw-radius-md" to (if(design.sea) "10px" else "16px"),
        ).entries.joinToString("") { (name,value) -> name+":"+value+"!important;" }
        val quoted = JSONObject.quote(css.replace("WHALE_RADIUS",design.radius.toString()).replace("WHALE_TOKENS",tokens))
        web.evaluateJavascript("(function(){var s=document.getElementById('whale-harness-style');" +
            "if(!s){s=document.createElement('style');s.id='whale-harness-style';document.head.appendChild(s);}" +
            "s.textContent=$quoted;})();", null)
    }
}
