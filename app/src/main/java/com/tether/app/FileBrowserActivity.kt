package com.tether.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import java.io.File

/** Lists the three app-private directories. Tapping a code file opens it in Workspace. */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        root = findViewById(R.id.filesRoot)

        findViewById<AppCompatButton>(R.id.filesNewCBtn).setOnClickListener { createAndOpen("c") }
        findViewById<AppCompatButton>(R.id.filesNewPyBtn).setOnClickListener { createAndOpen("py") }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun createAndOpen(ext: String) {
        val template = if (ext == "py") WorkspaceActivity.DEFAULT_PY else WorkspaceActivity.DEFAULT_C
        val f = File(TetherFiles.projects(this), "sketch_${System.currentTimeMillis()}.$ext")
        f.writeText(template)
        startActivity(
            Intent(this, WorkspaceActivity::class.java)
                .putExtra(WorkspaceActivity.EXTRA_OPEN_PATH, f.absolutePath)
        )
    }

    private fun refresh() {
        root.removeAllViews()
        section("PROJECTS", TetherFiles.projects(this), openable = true)
        section("IMAGES", TetherFiles.images(this), openable = false)
        section("SESSIONS", TetherFiles.sessions(this), openable = false)
    }

    private fun section(title: String, dir: File, openable: Boolean) {
        root.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF6B7680.toInt())
            textSize = 12f
            letterSpacing = 0.16f
            setPadding(0, dp(18), 0, dp(8))
        })

        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (files.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "empty"
                setTextColor(0xFF4A535C.toInt())
                textSize = 14f
                setPadding(0, 0, 0, dp(10))
            })
            return
        }

        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                val canOpen = openable && (f.extension == "c" || f.extension == "py")
                if (canOpen) {
                    setOnClickListener {
                        startActivity(
                            Intent(context, WorkspaceActivity::class.java)
                                .putExtra(WorkspaceActivity.EXTRA_OPEN_PATH, f.absolutePath)
                        )
                    }
                }
                setOnLongClickListener {
                    f.delete()
                    refresh()
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
                background = ContextCompat.getDrawable(context, R.drawable.pill_bg)
                backgroundTintList = ColorStateList.valueOf(badgeColor(f))
            })
            root.addView(row)
        }
    }

    private fun badgeColor(f: File): Int = when (f.extension.lowercase()) {
        "py" -> 0xFFF5C451.toInt()
        "c" -> 0xFF4ADE80.toInt()
        else -> 0xFF7A8590.toInt()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
