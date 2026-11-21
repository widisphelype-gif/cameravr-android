package com.cameravr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Side-by-side VR Box preview. Both eyes currently show the same camera frame (2D, not stereo depth). */
internal class VrStereoView(context: Context) : View(context) {
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3.5f }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 25f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    @Volatile private var frame: Bitmap? = null
    @Volatile private var hands: List<TrackedHand> = emptyList()
    @Volatile private var singleView: Boolean = false

    fun setSingleView(enabled: Boolean) {
        singleView = enabled
        invalidate()
    }

    fun isSingleView(): Boolean = singleView

    fun update(bitmap: Bitmap, trackedHands: List<TrackedHand>) {
        val old = frame
        frame = bitmap
        hands = trackedHands
        if (old != null && !old.isRecycled) old.recycle()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val current = frame ?: return
        if (singleView) {
            drawEye(canvas, current, RectF(0f, 0f, width.toFloat(), height.toFloat()))
        } else {
            val half = width / 2f
            drawEye(canvas, current, RectF(0f, 0f, half, height.toFloat()))
            drawEye(canvas, current, RectF(half, 0f, width.toFloat(), height.toFloat()))
            val divider = Paint().apply { color = Color.DKGRAY; strokeWidth = 2f }
            canvas.drawLine(half, 0f, half, height.toFloat(), divider)
        }
    }

    private fun drawEye(canvas: Canvas, bitmap: Bitmap, viewport: RectF) {
        val save = canvas.save()
        canvas.clipRect(viewport)
        val scale = maxOf(viewport.width() / bitmap.width, viewport.height() / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val dst = RectF(
            viewport.centerX() - dw / 2f, viewport.centerY() - dh / 2f,
            viewport.centerX() + dw / 2f, viewport.centerY() + dh / 2f
        )
        canvas.drawBitmap(bitmap, null as Rect?, dst, imagePaint)
        hands.forEach { hand ->
            val color = when (hand.label) {
                "Esquerda" -> Color.rgb(65, 160, 255)
                "Direita" -> Color.rgb(0, 230, 160)
                else -> Color.YELLOW
            }
            linePaint.color = color
            pointPaint.color = color
            fun x(i: Int) = viewport.centerX() - dw / 2f + hand.landmarks[i].x * dw
            fun y(i: Int) = viewport.centerY() - dh / 2f + hand.landmarks[i].y * dh
            val links = arrayOf(
                0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8,
                5 to 9, 9 to 10, 10 to 11, 11 to 12, 9 to 13, 13 to 14, 14 to 15,
                15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17
            )
            links.forEach { (a, b) ->
                if (b < hand.landmarks.size) canvas.drawLine(x(a), y(a), x(b), y(b), linePaint)
            }
            hand.landmarks.indices.forEach { canvas.drawCircle(x(it), y(it), 4.5f, pointPaint) }
            if (hand.landmarks.isNotEmpty()) canvas.drawText(hand.label, x(0) + 8f, y(0) - 8f, labelPaint)
        }
        canvas.restoreToCount(save)
    }
}
