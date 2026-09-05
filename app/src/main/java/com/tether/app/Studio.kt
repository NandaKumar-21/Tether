
package com.tether.app

/**
 * Pure logic for Tether Studio: prompt construction, model-output cleanup, and
 * the error hook injected into generated pages. No Android dependencies so the
 * behaviour is easy to reason about.
 */
object Studio {

    /** Kept blunt on purpose: a 1B model needs the constraints repeated. */
    private const val RULES =
        "You write a single self-contained HTML file.\n" +
            "Rules:\n" +
            "- Output ONLY HTML. No explanation, no commentary, no markdown fences.\n" +
            "- Start with <!DOCTYPE html> and end with </html>.\n" +
            "- Put all CSS in one <style> tag and all JavaScript in one <script> tag.\n" +
            "- No external files, no CDN links, no <img src> to the internet. The device is offline.\n" +
            "- Never call alert(), prompt() or confirm(). They freeze the page.\n" +
            "- All input and output must be visible DOM elements: buttons with onclick\n" +
            "  handlers, and a div or input that shows the result.\n" +
            "- Dark background, large readable text, big tap targets, works on a phone screen.\n"

    fun buildPrompt(request: String, cameraContext: String?): String {
        val sb = StringBuilder()
        sb.append(RULES).append('\n')
        if (!cameraContext.isNullOrBlank()) {
            sb.append("The user photographed this text. Treat it as part of the request:\n")
                .append(cameraContext.take(1200)).append("\n\n")
        }
        sb.append("Build this: ").append(request.trim()).append('\n')
        return sb.toString()
    }

    fun buildRepairPrompt(code: String, errors: List<String>): String {
        val sb = StringBuilder()
        sb.append(RULES).append('\n')
        sb.append("The HTML file below throws these JavaScript errors when it runs:\n")
        errors.take(6).forEach { sb.append("- ").append(it.take(300)).append('\n') }
        sb.append('\n')
        sb.append("Fix the errors. Return the COMPLETE corrected HTML file, nothing else.\n\n")
        sb.append(code.take(6000)).append('\n')
        return sb.toString()
    }

    /**
     * Models wrap output in fences and add commentary no matter how firmly they are
     * told not to, so strip it rather than trusting the instruction.
     */
    fun cleanHtml(raw: String): String {
        var s = raw.trim()

        // ```html ... ``` or ``` ... ```
        if (s.contains("```")) {
            val fence = Regex("```[a-zA-Z]*\\s*\\n?([\\s\\S]*?)```")
            val m = fence.find(s)
            s = if (m != null) m.groupValues[1].trim() else s.replace("```", "").trim()
        }

        // Drop anything before the document actually starts.
        val doctype = s.indexOf("<!DOCTYPE", ignoreCase = true)
        val htmlTag = s.indexOf("<html", ignoreCase = true)
        val start = when {
            doctype >= 0 -> doctype
            htmlTag >= 0 -> htmlTag
            else -> -1
        }
        if (start > 0) s = s.substring(start)

        // Drop trailing commentary after the document ends.
        val end = s.lastIndexOf("</html>", ignoreCase = true)
        if (end >= 0) s = s.substring(0, end + "</html>".length)

        s = s.trim()

        // Nothing document-shaped came back: wrap whatever fragment we got.
        if (!s.contains("<html", ignoreCase = true) && !s.contains("<!DOCTYPE", ignoreCase = true)) {
            if (s.isBlank()) return ""
            s = "<!DOCTYPE html>\n<html><head><meta name=\"viewport\" " +
                "content=\"width=device-width, initial-scale=1\"></head>\n<body>\n" +
                s + "\n</body></html>"
        }
        return s
    }

    /**
     * Routes window.onerror and unhandled rejections through console.error so a
     * single WebChromeClient callback sees everything.
     */
    private const val ERROR_HOOK = """
<script>
(function(){
  window.onerror = function(msg, src, line, col){
    console.error("[js] " + msg + "  (line " + line + ")");
    return false;
  };
  window.addEventListener("unhandledrejection", function(e){
    console.error("[promise] " + (e.reason && e.reason.message ? e.reason.message : e.reason));
  });
})();
</script>
"""

    fun injectErrorHook(html: String): String {
        if (html.isBlank()) return html
        val headIdx = html.indexOf("<head", ignoreCase = true)
        if (headIdx >= 0) {
            val close = html.indexOf('>', headIdx)
            if (close > 0) {
                return html.substring(0, close + 1) + ERROR_HOOK + html.substring(close + 1)
            }
        }
        val htmlIdx = html.indexOf("<html", ignoreCase = true)
        if (htmlIdx >= 0) {
            val close = html.indexOf('>', htmlIdx)
            if (close > 0) {
                return html.substring(0, close + 1) + ERROR_HOOK + html.substring(close + 1)
            }
        }
        return ERROR_HOOK + html
    }

    /** True when a console line looks like a real script failure rather than noise. */
    fun isRealError(message: String): Boolean {
        val m = message.lowercase()
        if (m.contains("favicon")) return false
        if (m.contains("net::err_file_not_found") && m.contains("favicon")) return false
        return true
    }
}
