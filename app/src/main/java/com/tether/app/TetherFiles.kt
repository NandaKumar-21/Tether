package com.tether.app

import android.content.Context
import java.io.File

/** App-private storage layout shared by Workspace and the file browser. */
object TetherFiles {

    fun root(context: Context): File = File(context.filesDir, "Tether").apply { mkdirs() }

    fun projects(context: Context): File = File(root(context), "Projects").apply { mkdirs() }
    fun images(context: Context): File = File(root(context), "Images").apply { mkdirs() }
    fun sessions(context: Context): File = File(root(context), "Sessions").apply { mkdirs() }

    fun languageOf(file: File): String = when (file.extension.lowercase()) {
        "py" -> "PYTHON"
        "c" -> "C"
        else -> file.extension.uppercase().ifEmpty { "?" }
    }
}
