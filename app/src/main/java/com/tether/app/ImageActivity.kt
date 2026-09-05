package com.tether.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Gallery photo -> on-device OCR -> the extracted text goes to the local model.
 * The pipeline label on screen is not decoration - it is what actually happens,
 * in order, entirely on the device.
 */
class ImageActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TetherImage"
        const val REQ_PICK = 600
    }

    private lateinit var pipeline: TextView
    private lateinit var ocrText: TextView
    private lateinit var response: TextView
    private var extractedText: String = ""

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)

        pipeline = findViewById(R.id.imgPipeline)
        ocrText = findViewById(R.id.imgOcrText)
        response = findViewById(R.id.imgResponse)

        findViewById<AppCompatButton>(R.id.imgPickBtn).setOnClickListener { pickImage() }
        findViewById<AppCompatButton>(R.id.imgAnalyseBtn).setOnClickListener { ask("analyse") }
        findViewById<AppCompatButton>(R.id.imgExplainBtn).setOnClickListener { ask("explain") }
        findViewById<AppCompatButton>(R.id.imgFixBtn).setOnClickListener { ask("fix") }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        runPipeline(uri)
    }

    private fun runPipeline(uri: Uri) {
        setStage(1)
        val bitmap = try {
            loadBitmap(uri)
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed", t)
            ocrText.text = "could not read that image: ${t.message}"
            return
        }

        setStage(2)
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                extractedText = result.text.trim()
                ocrText.text = extractedText.ifEmpty { "no text found in that image" }
                setStage(0)
            }
            .addOnFailureListener { e ->
                ocrText.text = "OCR failed: ${e.message}"
                setStage(0)
            }
    }

    private fun loadBitmap(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }

    private fun setStage(stage: Int) {
        val stages = listOf("IMAGE", "OCR", "LOCAL GEMMA", "RESPONSE")
        val sb = StringBuilder()
        stages.forEachIndexed { i, s ->
            if (i > 0) sb.append("  ->  ")
            sb.append(s)
        }
        pipeline.text = when (stage) {
            1 -> "[IMAGE]  ->  OCR  ->  LOCAL GEMMA  ->  RESPONSE"
            2 -> "IMAGE  ->  [OCR]  ->  LOCAL GEMMA  ->  RESPONSE"
            3 -> "IMAGE  ->  OCR  ->  [LOCAL GEMMA]  ->  RESPONSE"
            4 -> "IMAGE  ->  OCR  ->  LOCAL GEMMA  ->  [RESPONSE]"
            else -> sb.toString()
        }
    }

    private fun ask(mode: String) {
        if (extractedText.isBlank()) {
            response.text = "pick an image with text first"
            return
        }
        if (!ServerState.modelLoaded) {
            response.text = "model not loaded yet"
            return
        }

        setStage(3)
        response.text = "thinking..."

        val task = when (mode) {
            "explain" -> "Explain what this text means, in plain language."
            "fix" -> "This text was extracted from a photo by OCR and may contain " +
                "recognition errors. Correct it and return the corrected text."
            else -> "Analyse this text and summarise what it is and what matters in it."
        }
        val prompt = "$task\n\n$extractedText\n"

        Thread({
            val reply = try {
                LlmEngine.generate(prompt)
            } catch (t: Throwable) {
                Log.w(TAG, "generate failed", t)
                "[error] ${t.message}"
            }
            runOnUiThread {
                setStage(4)
                response.text = MarkdownLite.render(reply.ifBlank { "(empty reply)" })
            }
        }, "image-ai").start()
    }

    override fun onDestroy() {
        try { recognizer.close() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
