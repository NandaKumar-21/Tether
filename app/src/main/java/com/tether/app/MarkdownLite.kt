package com.tether.app

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Display-only. Turns the model's markdown into spans so the phone screen does not
 * show literal asterisks. Never touches what the HTTP endpoint returns.
 */
object MarkdownLite {

    private const val CODE_COLOR = 0xFF9FE8B4.toInt()
    private const val HEADING_COLOR = 0xFFE6EAEE.toInt()
    private const val LABEL_COLOR = 0xFF6B7680.toInt()

    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val CODE = Regex("""`([^`\n]+)`""")
    private val ITALIC = Regex("""(?<![*\w])\*([^*\n]+?)\*(?!\*)""")

    fun render(markdown: String): CharSequence {
        val out = SpannableStringBuilder()
        var inFence = false

        val lines = markdown.lines()
        var i = -1
        while (++i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trimStart()

            if (trimmed.startsWith("```")) {
                inFence = !inFence
                continue
            }

            // A pipe table is unreadable at 18sp on a phone, so reflow each row
            // into a stanza: the first cell as a heading, the rest labelled by column.
            if (!inFence && isTableRow(trimmed)) {
                val block = ArrayList<String>()
                while (i < lines.size && isTableRow(lines[i].trimStart())) {
                    block.add(lines[i].trim())
                    i++
                }
                i--
                appendTable(out, block)
                continue
            }

            if (inFence) {
                val start = out.length
                out.append(raw).append('\n')
                out.setSpan(
                    TypefaceSpan("monospace"),
                    start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                out.setSpan(
                    ForegroundColorSpan(CODE_COLOR),
                    start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                continue
            }

            val heading = Regex("""^(#{1,6})\s+(.*)$""").find(trimmed)
            if (heading != null) {
                val text = inline(heading.groupValues[2])
                val start = out.length
                out.append(text).append('\n')
                out.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                out.setSpan(
                    RelativeSizeSpan(1.15f),
                    start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                out.setSpan(
                    ForegroundColorSpan(HEADING_COLOR),
                    start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                continue
            }

            val bullet = Regex("""^[-*+]\s+(.*)$""").find(trimmed)
            if (bullet != null) {
                out.append("  •  ").append(inline(bullet.groupValues[1])).append('\n')
                continue
            }

            val numbered = Regex("""^(\d+)[.)]\s+(.*)$""").find(trimmed)
            if (numbered != null) {
                out.append("  ${numbered.groupValues[1]}.  ")
                    .append(inline(numbered.groupValues[2])).append('\n')
                continue
            }

            out.append(inline(raw)).append('\n')
        }

        // Collapse the run of blank lines markdown tends to leave behind.
        while (out.isNotEmpty() && out.last() == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun isTableRow(line: String): Boolean =
        line.startsWith("|") && line.count { it == '|' } >= 2

    /** A separator row like |---|:--:|---| carries no content. */
    private fun isSeparator(cells: List<String>): Boolean =
        cells.isNotEmpty() && cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' || it == ' ' } }

    private fun cells(row: String): List<String> =
        row.trim().trim('|').split('|').map { it.trim() }

    private fun appendTable(out: SpannableStringBuilder, rows: List<String>) {
        val parsed = rows.map { cells(it) }.filter { !isSeparator(it) }
        if (parsed.isEmpty()) return

        val header = parsed.first()
        val body = parsed.drop(1)

        // Only one row: nothing to label against, so print it plainly.
        if (body.isEmpty()) {
            out.append(inline(header.joinToString("  ·  "))).append('\n')
            return
        }

        for (row in body) {
            val key = row.firstOrNull().orEmpty()
            if (key.isNotEmpty()) {
                val start = out.length
                out.append(key).append('\n')
                out.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                out.setSpan(
                    ForegroundColorSpan(HEADING_COLOR),
                    start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            for (c in 1 until row.size) {
                val value = row[c]
                if (value.isEmpty()) continue
                val label = header.getOrNull(c).orEmpty()
                val start = out.length
                out.append("    ").append(label).append("  ")
                out.setSpan(
                    ForegroundColorSpan(LABEL_COLOR),
                    start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                out.append(inline(value)).append('\n')
            }
            out.append('\n')
        }
    }

    private fun inline(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(text)
        // Bold first: it consumes the double markers so italic cannot mis-fire on them.
        applyAll(sb, BOLD) { listOf(StyleSpan(Typeface.BOLD)) }
        applyAll(sb, CODE) { listOf(TypefaceSpan("monospace"), ForegroundColorSpan(CODE_COLOR)) }
        applyAll(sb, ITALIC) { listOf(StyleSpan(Typeface.ITALIC)) }
        return sb
    }

    private fun applyAll(
        sb: SpannableStringBuilder,
        regex: Regex,
        spans: () -> List<Any>
    ) {
        var guard = 0
        while (guard++ < 200) {
            val m = regex.find(sb) ?: return
            val start = m.range.first
            val inner = m.groupValues[1]
            sb.replace(start, m.range.last + 1, inner)
            for (s in spans()) {
                sb.setSpan(s, start, start + inner.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
