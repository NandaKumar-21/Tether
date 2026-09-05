package com.tether.app

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan

/**
 * Line diff between two generated versions, so the repair loop reads as history
 * rather than a spinner.
 */
object StudioDiff {

    private const val ADDED = 0xFF4ADE80.toInt()
    private const val REMOVED = 0xFFF87171.toInt()
    private const val CONTEXT = 0xFF5A636C.toInt()

    private enum class Kind { SAME, ADD, DEL }
    private class Row(val kind: Kind, val text: String)

    fun render(oldText: String, newText: String): CharSequence {
        val a = oldText.lines()
        val b = newText.lines()
        val rows = diff(a, b)

        val out = SpannableStringBuilder()
        var unchangedRun = 0
        for (r in rows) {
            // Collapse long stretches of identical lines; the change is the point.
            if (r.kind == Kind.SAME) {
                unchangedRun++
                if (unchangedRun > 3) continue
            } else {
                unchangedRun = 0
            }
            val prefix = when (r.kind) {
                Kind.ADD -> "+ "
                Kind.DEL -> "- "
                Kind.SAME -> "  "
            }
            val color = when (r.kind) {
                Kind.ADD -> ADDED
                Kind.DEL -> REMOVED
                Kind.SAME -> CONTEXT
            }
            val start = out.length
            out.append(prefix).append(r.text).append('\n')
            out.setSpan(
                ForegroundColorSpan(color),
                start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (out.isEmpty()) out.append("no differences")
        return out
    }

    /** Standard LCS table. Version files are a few hundred lines, so this is cheap. */
    private fun diff(a: List<String>, b: List<String>): List<Row> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val rows = ArrayList<Row>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    rows.add(Row(Kind.SAME, a[i])); i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    rows.add(Row(Kind.DEL, a[i])); i++
                }
                else -> {
                    rows.add(Row(Kind.ADD, b[j])); j++
                }
            }
        }
        while (i < n) rows.add(Row(Kind.DEL, a[i++]))
        while (j < m) rows.add(Row(Kind.ADD, b[j++]))
        return rows
    }
}
