package com.cameravr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * CameraVR phone-viewer compositor.
 * Uses an OpenGL radial pre-warp and two lens viewports instead of merely clipping
 * a duplicated bitmap into curved Canvas shapes.
 */
internal class VrStereoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val glView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
    }
    private val overlay = HandOverlay(context)
    private val compositor = LensRenderer()

    @Volatile private var singleView = false

    init {
        glView.setRenderer(compositor)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        compositor.xdpi = resources.displayMetrics.xdpi.coerceAtLeast(160f)
        addView(glView, LayoutParams(-1, -1))
        addView(overlay, LayoutParams(-1, -1))
    }

    fun setSingleView(enabled: Boolean) {
        singleView = enabled
        compositor.singleView = enabled
        glView.requestRender()
        overlay.singleView = enabled
        overlay.invalidate()
    }

    fun isSingleView(): Boolean = singleView

    fun update(bitmap: Bitmap, trackedHands: List<TrackedHand>) {
        compositor.submit(bitmap)
        overlay.frameWidth = bitmap.width
        overlay.frameHeight = bitmap.height
        overlay.hands = trackedHands
        overlay.invalidate()
        glView.requestRender()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glView.onResume()
    }

    override fun onDetachedFromWindow() {
        glView.onPause()
        super.onDetachedFromWindow()
    }

    private class LensRenderer : GLSurfaceView.Renderer {
        @Volatile var singleView = false
        private val lock = Any()
        private var pending: Bitmap? = null
        private var texture = 0
        private var program = 0
        private var position = 0
        private var uv = 0
        private var sampler = 0
        private var center = 0
        private var coefficients = 0
        private var cropUniform = 0
        private var viewportW = 1
        private var viewportH = 1
        var xdpi = 400f
        private var frameWidth = 640
        private var frameHeight = 480
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(
                    -1f, -1f, 0f, 1f,
                     1f, -1f, 1f, 1f,
                    -1f,  1f, 0f, 0f,
                     1f,  1f, 1f, 0f
                ))
                position(0)
            }

        fun submit(bitmap: Bitmap) {
            synchronized(lock) {
                pending?.let { if (it !== bitmap && !it.isRecycled) it.recycle() }
                pending = bitmap
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            val vs = """
                attribute vec2 aPosition;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main() {
                    vUv = aUv;
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                }
            """.trimIndent()
            val fs = """
                precision highp float;
                varying vec2 vUv;
                uniform sampler2D uTexture;
                uniform vec2 uLensCenter;
                uniform vec2 uK;
                uniform vec2 uCrop;
                void main() {
                    vec2 t = vUv - uLensCenter;
                    float r2 = dot(t, t);
                    float radial = 1.0 + uK.x * r2 + uK.y * r2 * r2;
                    vec2 sourceUv = uLensCenter + t * radial;
                    sourceUv = vec2(0.5) + (sourceUv - vec2(0.5)) * uCrop;
                    if (sourceUv.x < 0.0 || sourceUv.x > 1.0 ||
                        sourceUv.y < 0.0 || sourceUv.y > 1.0) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    } else {
                        gl_FragColor = texture2D(uTexture, sourceUv);
                    }
                }
            """.trimIndent()
            program = linkProgram(vs, fs)
            position = GLES20.glGetAttribLocation(program, "aPosition")
            uv = GLES20.glGetAttribLocation(program, "aUv")
            sampler = GLES20.glGetUniformLocation(program, "uTexture")
            center = GLES20.glGetUniformLocation(program, "uLensCenter")
            coefficients = GLES20.glGetUniformLocation(program, "uK")
            cropUniform = GLES20.glGetUniformLocation(program, "uCrop")
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewportW = width.coerceAtLeast(1)
            viewportH = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, viewportW, viewportH)
        }

        override fun onDrawFrame(gl: GL10?) {
            val next = synchronized(lock) { pending.also { pending = null } }
            if (next != null && !next.isRecycled) {
                frameWidth = next.width
                frameHeight = next.height
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, next, 0)
                next.recycle()
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (texture == 0 || program == 0) return
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(sampler, 0)
            GLES20.glUniform2f(coefficients, 0.22f, 0.06f)
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)

            if (singleView) {
                GLES20.glViewport(0, 0, viewportW, viewportH)
                GLES20.glUniform2f(center, 0.5f, 0.5f)
                GLES20.glUniform2f(coefficients, 0f, 0f)
                GLES20.glUniform2f(cropUniform, 1f, 1f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } else {
                val half = viewportW / 2
                val aspect = half.toFloat() / viewportH
                // Centre-crop the camera frame to each tall/wide eye viewport.
                // CameraX frames are landscape; VR viewers should be used in landscape orientation.
                val frameAspect = frameWidth.toFloat() / frameHeight.toFloat()
                val cropX = if (frameAspect > aspect) aspect / frameAspect else 1f
                val cropY = if (frameAspect > aspect) 1f else frameAspect / aspect
                val pxPerMm = xdpi / 25.4f
                val lensOffsetPx = 31.5f * pxPerMm
                val centerOffset = (lensOffsetPx / half.toFloat()).coerceIn(0.04f, 0.28f)
                for (eye in 0..1) {
                    GLES20.glViewport(eye * half, 0, half, viewportH)
                    // Each eye's optical centre is shifted toward the bridge of the nose.
                    val lensX = if (eye == 0) 0.5f + centerOffset else 0.5f - centerOffset
                    GLES20.glUniform2f(center, lensX, 0.5f)
                    GLES20.glUniform2f(coefficients, 0.22f, 0.06f)
                    GLES20.glUniform2f(cropUniform, cropX, cropY)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(uv)
        }

        private fun linkProgram(vertex: String, fragment: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
                return shader
            }
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(p) }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return p
        }
    }

    private class HandOverlay(context: Context) : View(context) {
        @Volatile var hands: List<TrackedHand> = emptyList()
        @Volatile var singleView = false
        @Volatile var frameWidth = 640
        @Volatile var frameHeight = 480
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3.5f }
        private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 25f
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (singleView) {
                drawHands(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            } else {
                val half = width / 2f
                drawHands(canvas, RectF(0f, 0f, half, height.toFloat()))
                drawHands(canvas, RectF(half, 0f, width.toFloat(), height.toFloat()))
            }
        }

        private fun drawHands(canvas: Canvas, viewport: RectF) {
            val save = canvas.save()
            val lens = Path().apply {
                moveTo(viewport.left + viewport.width() * 0.12f, viewport.top + viewport.height() * 0.08f)
                cubicTo(viewport.left + viewport.width() * 0.34f, viewport.top, viewport.left + viewport.width() * 0.66f, viewport.top, viewport.left + viewport.width() * 0.88f, viewport.top + viewport.height() * 0.08f)
                cubicTo(viewport.left + viewport.width() * 0.98f, viewport.top + viewport.height() * 0.28f, viewport.left + viewport.width() * 0.98f, viewport.top + viewport.height() * 0.70f, viewport.left + viewport.width() * 0.88f, viewport.top + viewport.height() * 0.90f)
                cubicTo(viewport.left + viewport.width() * 0.66f, viewport.top + viewport.height() * 0.99f, viewport.left + viewport.width() * 0.34f, viewport.top + viewport.height() * 0.99f, viewport.left + viewport.width() * 0.12f, viewport.top + viewport.height() * 0.90f)
                cubicTo(viewport.left + viewport.width() * 0.02f, viewport.top + viewport.height() * 0.70f, viewport.left + viewport.width() * 0.02f, viewport.top + viewport.height() * 0.28f, viewport.left + viewport.width() * 0.12f, viewport.top + viewport.height() * 0.08f)
                close()
            }
            canvas.clipPath(lens)
            val scale = max(viewport.width() / frameWidth.toFloat(), viewport.height() / frameHeight.toFloat())
            val dw = frameWidth * scale
            val dh = frameHeight * scale
            val left = viewport.centerX() - dw / 2f
            val top = viewport.centerY() - dh / 2f
            hands.forEach { hand ->
                val color = when (hand.label) {
                    "Esquerda" -> Color.rgb(65, 160, 255)
                    "Direita" -> Color.rgb(0, 230, 160)
                    else -> Color.YELLOW
                }
                linePaint.color = color
                pointPaint.color = color
                fun x(i: Int) = left + hand.landmarks[i].x * dw
                fun y(i: Int) = top + hand.landmarks[i].y * dh
                val links = arrayOf(
                    0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8,
                    5 to 9, 9 to 10, 10 to 11, 11 to 12, 9 to 13, 13 to 14, 14 to 15,
                    15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17
                )
                links.forEach { (a, b) ->
                    if (a < hand.landmarks.size && b < hand.landmarks.size) canvas.drawLine(x(a), y(a), x(b), y(b), linePaint)
                }
                hand.landmarks.indices.forEach { canvas.drawCircle(x(it), y(it), 4.5f, pointPaint) }
                if (hand.landmarks.isNotEmpty()) canvas.drawText(hand.label, x(0) + 8f, y(0) - 8f, labelPaint)
            }
            canvas.restoreToCount(save)
        }
    }
}
