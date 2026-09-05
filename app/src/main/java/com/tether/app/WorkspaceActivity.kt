package com.tether.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.io.File

/**
 * A code editor for C and Python. This is AI analysis only: there is no compiler or
 * interpreter on Android without Termux, so nothing here ever claims code ran.
 */
class WorkspaceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_PATH = "open_path"
        private const val TAG = "TetherWorkspace"
        const val DEFAULT_C = "#include <stdio.h>\n\nint main(void) {\n    printf(\"hello\\n\");\n    return 0;\n}\n"
        const val DEFAULT_PY = "def main():\n    print(\"hello\")\n\nif __name__ == \"__main__\":\n    main()\n"
    }

    private lateinit var fileListPane: View
    private lateinit var editorPane: View
    private lateinit var fileListRow: LinearLayout
    private lateinit var editor: EditText
    private lateinit var output: TextView
    private lateinit var outputScroll: View
    private lateinit var fileLabel: TextView
    private lateinit var langPill: TextView

    private var currentFile: File? = null
    /** Language of the buffer even before it has a file yet. */
    private var currentLang = "C"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workspace)

        fileListPane = findViewById(R.id.wsFileListPane)
        editorPane = findViewById(R.id.wsEditorPane)
        fileListRow = findViewById(R.id.wsFileListRow)
        editor = findViewById(R.id.wsEditor)
        output = findViewById(R.id.wsOutput)
        outputScroll = findViewById(R.id.wsOutputScroll)
        fileLabel = findViewById(R.id.wsFileLabel)
        langPill = findViewById(R.id.wsLangPill)

        findViewById<AppCompatButton>(R.id.wsNewCBtn).setOnClickListener { newFile("C") }
        findViewById<AppCompatButton>(R.id.wsNewPyBtn).setOnClickListener { newFile("PYTHON") }
        findViewById<AppCompatButton>(R.id.wsBackBtn).setOnClickListener { showFileList() }
        findViewById<AppCompatButton>(R.id.wsSaveBtn).setOnClickListener { save() }

        findViewById<AppCompatButton>(R.id.wsAskBtn).setOnClickListener { askAi("ask") }
        findViewById<AppCompatButton>(R.id.wsExplainBtn).setOnClickListener { askAi("explain") }
        findViewById<AppCompatButton>(R.id.wsBugBtn).setOnClickListener { askAi("bug") }
        findViewById<AppCompatButton>(R.id.wsImproveBtn).setOnClickListener { askAi("improve") }

        val openPath = intent.getStringExtra(EXTRA_OPEN_PATH)
        if (openPath != null) {
            openFile(File(openPath))
        } else {
            showFileList()
        }
    }

    override fun onResume() {
        super.onResume()
        if (fileListPane.visibility == View.VISIBLE) refreshFileList()
    }

    // -------------------------------------------------------------- files

    private fun refreshFileList() {
        fileListRow.removeAllViews()
        val dir = TetherFiles.projects(this)
        val files = dir.listFiles { f -> f.isFile && (f.extension == "c" || f.extension == "py") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            fileListRow.addView(TextView(this).apply {
                text = "no files yet"
                setTextColor(0xFF6B7680.toInt())
                textSize = 15f
                setPadding(0, dp(20), 0, dp(20))
            })
            return
        }

        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { openFile(f) }
                setOnLongClickListener {
                    f.delete()
                    refreshFileList()
                    true
                }
            }
            row.addView(TextView(this).apply {
                text = f.name
                setTextColor(0xFFF2F5F7.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = TetherFiles.languageOf(f)
                setTextColor(0xFF0B0D0F.toInt())
                textSize = 11f
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.pill_bg)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (f.extension == "py") 0xFFF5C451.toInt() else 0xFF4ADE80.toInt()
                )
            })
            fileListRow.addView(row)
        }
    }

    private fun newFile(lang: String) {
        currentFile = null
        currentLang = lang
        editor.setText(if (lang == "C") DEFAULT_C else DEFAULT_PY)
        showEditor()
    }

    private fun openFile(f: File) {
        currentFile = f
        currentLang = TetherFiles.languageOf(f)
        editor.setText(f.readText())
        showEditor()
    }

    private fun save() {
        val text = editor.text.toString()
        var f = currentFile
        if (f == null) {
            val ext = if (currentLang == "PYTHON") "py" else "c"
            var name = "sketch_${System.currentTimeMillis()}.$ext"
            f = File(TetherFiles.projects(this), name)
            currentFile = f
        }
        f.writeText(text)
        fileLabel.text = f.name
        showOutput("saved ${f.name}")
    }

    private fun showFileList() {
        editorPane.visibility = View.GONE
        fileListPane.visibility = View.VISIBLE
        refreshFileList()
    }

    private fun showEditor() {
        fileListPane.visibility = View.GONE
        editorPane.visibility = View.VISIBLE
        fileLabel.text = currentFile?.name ?: "(unsaved)"
        langPill.text = if (currentLang == "PYTHON") "PY" else "C"
        langPill.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (currentLang == "PYTHON") 0xFFF5C451.toInt() else 0xFF4ADE80.toInt()
        )
        outputScroll.visibility = View.GONE
    }

    // ----------------------------------------------------------------- ai

    private fun askAi(mode: String) {
        val code = editor.text.toString()
        if (code.isBlank()) {
            showOutput("nothing to analyse - the editor is empty")
            return
        }
        if (!ServerState.modelLoaded) {
            showOutput("model not loaded yet")
            return
        }

        showOutput("thinking...")
        val prompt = buildPrompt(mode, currentLang, code)

        Thread({
            val reply = try {
                LlmEngine.generate(prompt)
            } catch (t: Throwable) {
                Log.w(TAG, "generate failed", t)
                "[error] ${t.message}"
            }
            runOnUiThread { showOutput(reply.ifBlank { "(empty reply)" }) }
        }, "workspace-ai").start()
    }

    private fun buildPrompt(mode: String, lang: String, code: String): String {
        val language = if (lang == "PYTHON") "Python" else "C"
        val task = when (mode) {
            "explain" -> "Explain what this $language code does, in plain language."
            "bug" -> "Find bugs or likely runtime errors in this $language code. " +
                "List each one with the line if you can identify it."
            "improve" -> "Suggest concrete improvements to this $language code " +
                "(readability, correctness, efficiency). Do not just repeat the code back."
            else -> "Answer the user's question about this $language code."
        }
        return "You are a $language code assistant. You cannot compile or run code - " +
            "give analysis only, and never claim you executed anything.\n\n" +
            "$task\n\n```$lang\n$code\n```\n"
    }

    private fun showOutput(text: CharSequence) {
        outputScroll.visibility = View.VISIBLE
        output.text = if (text is String) MarkdownLite.render(text) else text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
