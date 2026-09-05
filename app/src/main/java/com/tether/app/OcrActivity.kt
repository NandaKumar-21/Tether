package com.tether.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Camera capture -> on-device OCR. The recognised text is parked in ServerState
 * and consumed by the next /v1/chat/completions request.
 */
class OcrActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TetherOCR"
        const val REQ_CAMERA = 200
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureBtn: Button
    private lateinit var resultText: TextView
    private lateinit var hint: TextView

    private var imageCapture: ImageCapture? = null
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        previewView = findViewById(R.id.preview)
        captureBtn = findViewById(R.id.captureBtn)
        resultText = findViewById(R.id.ocrResult)
        hint = findViewById(R.id.hint)

        captureBtn.setOnClickListener { capture() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                Toast.makeText(this, "Camera failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val capture = imageCapture ?: return
        captureBtn.isEnabled = false
        hint.text = "Reading..."

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                @SuppressLint("UnsafeOptInUsageError")
                override fun onCaptureSuccess(image: ImageProxy) {
                    val media = image.image
                    if (media == null) {
                        image.close()
                        fail("no image")
                        return
                    }
                    val input = InputImage.fromMediaImage(
                        media,
                        image.imageInfo.rotationDegrees
                    )
                    recognizer.process(input)
                        .addOnSuccessListener { result ->
                            image.close()
                            onText(result.text)
                        }
                        .addOnFailureListener { e ->
                            image.close()
                            fail(e.message ?: "OCR failed")
                        }
                }

                override fun onError(exception: ImageCaptureException) {
                    fail(exception.message ?: "capture failed")
                }
            }
        )
    }

    private fun onText(text: String) {
        captureBtn.isEnabled = true
        val clean = text.trim()
        if (clean.isEmpty()) {
            hint.text = "No text found. Try again."
            return
        }
        ServerState.ocrContext = clean
        ServerState.log("OCR captured ${clean.length} chars")
        hint.text = "Captured. It will be context for the next request."
        resultText.visibility = TextView.VISIBLE
        resultText.text = clean.take(1200)
        captureBtn.text = "CAPTURE AGAIN"
        Log.i(TAG, "ocr: ${clean.take(200)}")
    }

    private fun fail(msg: String) {
        captureBtn.isEnabled = true
        hint.text = "Failed: $msg"
        Log.w(TAG, "ocr failed: $msg")
    }

    override fun onDestroy() {
        try {
            recognizer.close()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
