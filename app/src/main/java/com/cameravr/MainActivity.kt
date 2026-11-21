package com.cameravr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var handOverlay: HandOverlayView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var handLandmarker: HandLandmarker? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Permissão da câmera necessária."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        handOverlay = HandOverlayView(this)
        val heading = TextView(this).apply {
            text = "CameraVR  •  Hand tracking"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(20, 22, 20, 12)
            setShadowLayer(5f, 1f, 1f, Color.BLACK)
        }
        status = TextView(this).apply {
            text = "Preparando câmera traseira…"
            textSize = 14f
            setTextColor(Color.rgb(100, 216, 203))
            setPadding(20, 16, 20, 20)
            setBackgroundColor(Color.argb(170, 16, 24, 32))
        }

        root.addView(previewView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(handOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(heading, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initializeHandLandmarker(): Boolean {
        return try {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("hand_landmarker.task")
                        .build()
                )
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(this, options)
            true
        } catch (error: Exception) {
            runOnUiThread {
                status.text = "Falha ao carregar o modelo de mãos: ${error.localizedMessage}"
            }
            false
        }
    }

    private fun startCamera() {
        if (handLandmarker == null && !initializeHandLandmarker()) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setTargetRotation(previewView.display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
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
                status.text = "Câmera ativa • procurando até duas mãos…"
            } catch (error: Exception) {
                status.text = "Não foi possível iniciar a câmera: ${error.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            val plane = image.planes.firstOrNull() ?: return
            val source = plane.buffer.duplicate()
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val packed = ByteArray(width * height * 4)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val sourceIndex = y * rowStride + x * pixelStride
                    val targetIndex = (y * width + x) * 4
                    if (sourceIndex + 3 < source.limit()) {
                        packed[targetIndex] = source.get(sourceIndex)
                        packed[targetIndex + 1] = source.get(sourceIndex + 1)
                        packed[targetIndex + 2] = source.get(sourceIndex + 2)
                        packed[targetIndex + 3] = source.get(sourceIndex + 3)
                    }
                }
            }

            val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
            val rotation = image.imageInfo.rotationDegrees
            val processedBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(rawBitmap, 0, 0, width, height, matrix, true)
                    .also { if (it !== rawBitmap) rawBitmap.recycle() }
            } else {
                rawBitmap
            }
            bitmap = processedBitmap

            val mpImage = BitmapImageBuilder(processedBitmap).build()
            val result = handLandmarker?.detectForVideo(mpImage, SystemClock.uptimeMillis())
            val hands = result?.landmarks()?.mapIndexed { index, hand ->
                val side = result.handedness()
                    .getOrNull(index)
                    ?.firstOrNull()
                    ?.categoryName()
                    ?.lowercase()
                    ?: "unknown"
                TrackedHand(
                    label = when (side) {
                        "left" -> "Esquerda"
                        "right" -> "Direita"
                        else -> "Mão ${index + 1}"
                    },
                    landmarks = hand.map { landmark ->
                        PointF(landmark.x(), landmark.y())
                    }
                )
            }.orEmpty()
            val processedWidth = processedBitmap.width
            val processedHeight = processedBitmap.height

            runOnUiThread {
                handOverlay.updateHands(hands, processedWidth, processedHeight)
                val detectedSides = hands.map { it.label }
                status.text = when (hands.size) {
                    0 -> "MediaPipe ativo • mostre as duas mãos"
                    1 -> "${detectedSides.first()} detectada • 21 pontos"
                    else -> "Duas mãos detectadas • ${detectedSides.distinct().joinToString(" + ")} • 42 pontos"
                }
            }
        } catch (error: Exception) {
            runOnUiThread {
                status.text = "Erro no rastreamento: ${error.localizedMessage}"
            }
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    override fun onDestroy() {
        handLandmarker?.close()
        handLandmarker = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

private data class TrackedHand(
    val label: String,
    val landmarks: List<PointF>
)

private class HandOverlayView(context: android.content.Context) : View(context) {
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
        setShadowLayer(5f, 1f, 1f, Color.BLACK)
    }
    private var hands: List<TrackedHand> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1

    fun updateHands(newHands: List<TrackedHand>, width: Int, height: Int) {
        hands = newHands
        sourceWidth = width.coerceAtLeast(1)
        sourceHeight = height.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f

        hands.forEach { hand ->
            val handColor = when (hand.label) {
                "Esquerda" -> Color.rgb(70, 170, 255)
                "Direita" -> Color.rgb(0, 235, 170)
                else -> Color.rgb(255, 210, 80)
            }
            linePaint.color = handColor
            pointPaint.color = handColor

            fun px(point: PointF) = offsetX + point.x * sourceWidth * scale
            fun py(point: PointF) = offsetY + point.y * sourceHeight * scale

            CONNECTIONS.forEach { (start, end) ->
                if (start < hand.landmarks.size && end < hand.landmarks.size) {
                    canvas.drawLine(
                        px(hand.landmarks[start]), py(hand.landmarks[start]),
                        px(hand.landmarks[end]), py(hand.landmarks[end]), linePaint
                    )
                }
            }
            hand.landmarks.forEach { point ->
                canvas.drawCircle(px(point), py(point), 6f, pointPaint)
            }
            hand.landmarks.firstOrNull()?.let { wrist ->
                canvas.drawText(hand.label, px(wrist) + 10f, py(wrist) - 14f, labelPaint)
            }
        }
    }

    private companion object {
        val CONNECTIONS = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17
        )
    }
}
