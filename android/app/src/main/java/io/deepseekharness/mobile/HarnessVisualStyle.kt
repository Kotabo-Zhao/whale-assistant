package io.deepseekharness.mobile

import android.webkit.WebView
import org.json.JSONObject

/** Local, fixed styling for the isolated Harness page; no script comes from the guest. */
internal object HarnessVisualStyle {
    private val css = """
        :root { --whale-radius: 18px; }
        body { font-family: "Avenir Next", "Segoe UI", "Microsoft YaHei", sans-serif; }
        [data-phase] :is(textarea, [contenteditable="true"]) {
            border-radius: 16px !important;
            line-height: 1.6;
            box-shadow: 0 8px 24px rgba(35, 75, 150, .08);
        }
        [data-phase] :is(button, [role="button"]) {
            border-radius: 12px;
            transition: background-color 150ms ease, box-shadow 150ms ease;
        }
        [data-phase] :is(textarea, [contenteditable="true"]):focus-visible {
            outline: 2px solid var(--dsh-theme-accent, #315fcf);
            outline-offset: 2px;
        }
        pre, .md-code-block { border-radius: 14px; }
        [role="dialog"] { box-shadow: 0 20px 55px rgba(20, 35, 70, .18); }
        [role="dialog"] { border-radius: var(--whale-radius); }
        [data-rightbar-col] { border-radius: 18px 0 0 18px; }
        [data-shell-overlay] { border-radius: 0 18px 18px 0; }
        :focus-visible { outline-color: var(--dsh-theme-accent, #315fcf); }
    """.trimIndent()

    fun apply(web: WebView) {
        val quoted = JSONObject.quote(css)
        web.evaluateJavascript("(function(){var s=document.getElementById('whale-harness-style');" +
            "if(!s){s=document.createElement('style');s.id='whale-harness-style';document.head.appendChild(s);}" +
            "s.textContent=$quoted;})();", null)
    }
}
