package com.cameravr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val framesAnalyzed = AtomicLong(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Permissão da câmera necessária."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(16, 24, 32))
        }
        val title = TextView(this).apply {
            text = "CameraVR  •  Android tracking prototype"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20, 22, 20, 12)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        status = TextView(this).apply {
            text = "Preparando câmera traseira…"
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(100, 216, 203))
            setPadding(20, 16, 20, 20)
        }
        root.addView(title)
        root.addView(previewView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(status)
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                analyzeFrame(image)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                status.text = "Câmera ativa • análise de frames preparada • rastreadores ainda não conectados"
            } catch (error: Exception) {
                status.text = "Não foi possível iniciar a câmera: ${error.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // First milestone: validate the live frame pipeline.
    // MediaPipe hand landmarks and visual head-pose estimation will be added in a later milestone.
    private fun analyzeFrame(image: ImageProxy) {
        framesAnalyzed.incrementAndGet()
        image.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
