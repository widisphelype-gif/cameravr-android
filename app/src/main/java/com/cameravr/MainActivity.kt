package com.cameravr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var vrView: VrStereoView
    private lateinit var status: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var handLandmarker: HandLandmarker? = null
    private val transport = VrTransport()
    @Volatile private var streaming = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Permissão da câmera necessária."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vrView = VrStereoView(this)
        status = TextView(this).apply {
            text = "Preparando câmera traseira…"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(16, 12, 16, 12)
            setBackgroundColor(android.graphics.Color.argb(185, 12, 18, 28))
        }
        val title = TextView(this).apply {
            text = "CameraVR • VR Box"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(16, 14, 8, 14)
        }
        val display = Button(this).apply {
            text = "Tela"
            setOnClickListener { showDisplayDialog() }
        }
        val connect = Button(this).apply {
            text = "PC / conexão"
            setOnClickListener { showConnectionDialog() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.argb(175, 12, 18, 28))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(display)
            addView(connect)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(vrView, FrameLayout.LayoutParams(-1, -1))
            addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showDisplayDialog() {
        val options = arrayOf("VR Box • duas lentes (lado a lado)", "Tela única • juntar em uma imagem")
        AlertDialog.Builder(this)
            .setTitle("Modo de visualização")
            .setSingleChoiceItems(options, if (vrView.isSingleView()) 1 else 0) { dialog, which ->
                vrView.setSingleView(which == 1)
                status.text = if (which == 1) "Tela única ativada • imagem sem divisão" else "Modo VR Box ativado • duas lentes"
                dialog.dismiss()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showConnectionDialog() {
        val hostInput = EditText(this).apply {
            setText("127.0.0.1")
            hint = "IP do PC (USB: 127.0.0.1)"
        }
        val portInput = EditText(this).apply {
            setText("4242")
            hint = "Porta"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(TextView(this@MainActivity).apply {
                text = "USB: use 127.0.0.1 e execute adb reverse tcp:4242 tcp:4242 no PC.\nWi-Fi: informe o IPv4 do PC na mesma rede."
            })
            addView(hostInput)
            addView(portInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Conectar ao CameraVR PC")
            .setView(content)
            .setPositiveButton("Conectar") { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 4242
                if (host.isNotBlank() && port in 1..65535) {
                    transport.configure(host, port)
                    streaming = true
                    status.text = "Transmissão configurada • aguardando PC em $host:$port"
                } else status.text = "Endereço ou porta inválidos."
            }
            .setNeutralButton("Parar transmissão") { _, _ ->
                streaming = false
                transport.disconnect()
                status.text = "Transmissão para o PC desligada • modo VR Box local"
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun initializeHandLandmarker(): Boolean = try {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        handLandmarker = HandLandmarker.createFromOptions(this, options)
        true
    } catch (error: Exception) {
        runOnUiThread { status.text = "Falha ao carregar rastreador: ${error.localizedMessage}" }
        false
    }

    private fun startCamera() {
        if (handLandmarker == null && !initializeHandLandmarker()) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { analyzeFrame(it) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                status.text = "Câmera ativa • VR Box lado a lado • procurando duas mãos"
            } catch (error: Exception) {
                status.text = "Não foi possível iniciar a câmera: ${error.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            val plane = image.planes.first()
            val source = plane.buffer.duplicate()
            val width = image.width
            val height = image.height
            val packed = ByteArray(width * height * 4)
            for (y in 0 until height) for (x in 0 until width) {
                val si = y * plane.rowStride + x * plane.pixelStride
                val di = (y * width + x) * 4
                if (si + 3 < source.limit()) {
                    packed[di] = source.get(si)
                    packed[di + 1] = source.get(si + 1)
                    packed[di + 2] = source.get(si + 2)
                    packed[di + 3] = source.get(si + 3)
                }
            }
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
            val rotation = image.imageInfo.rotationDegrees
            val frame = if (rotation != 0) {
                Bitmap.createBitmap(raw, 0, 0, width, height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true).also { raw.recycle() }
            } else raw
            bitmap = frame

            val result = handLandmarker?.detectForVideo(
                BitmapImageBuilder(frame).build(), SystemClock.uptimeMillis()
            )
            val hands = result?.landmarks()?.mapIndexed { index, landmarks ->
                val side = result.handedness().getOrNull(index)?.firstOrNull()
                    ?.categoryName()?.lowercase() ?: "unknown"
                TrackedHand(
                    when (side) {
                        "left" -> "Esquerda"
                        "right" -> "Direita"
                        else -> "Mão ${index + 1}"
                    },
                    landmarks.map { PointF(it.x(), it.y()) }
                )
            }.orEmpty()

            if (streaming) transport.sendFrame(frame)
            val displayFrame = frame.copy(Bitmap.Config.ARGB_8888, false)
            runOnUiThread {
                vrView.update(displayFrame, hands)
                status.text = when (hands.size) {
                    0 -> "VR Box • MediaPipe ativo • mostre as mãos à câmera"
                    1 -> "VR Box • 1 mão detectada • estimativa de cabeça ainda experimental"
                    else -> "VR Box • 2 mãos detectadas • cabeça: estimativa visual em desenvolvimento"
                } + if (streaming) " • enviando ao PC" else " • modo local"
            }
        } catch (error: Exception) {
            runOnUiThread { status.text = "Erro no processamento: ${error.localizedMessage}" }
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    override fun onDestroy() {
        streaming = false
        transport.close()
        handLandmarker?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

internal data class TrackedHand(val label: String, val landmarks: List<PointF>)
