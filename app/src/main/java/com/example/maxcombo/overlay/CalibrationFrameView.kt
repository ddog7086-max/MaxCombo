package com.example.maxcombo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.maxcombo.core.PDBoard
import com.example.maxcombo.core.PDBoardRect

/**
 * Visible calibration frame over the game board. In align mode the user drags
 * the frame body to move it and drags the bottom-right handle to resize; the
 * 6:5 (cols:rows) aspect ratio is locked so the grid never stretches. The
 * resulting normalised rectangle is persisted and reused on every analysis.
 */
class CalibrationFrameView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    /** Normalised 0..1 rectangle on screen. */
    var rect: PDBoardRect = PDBoardRect(0.0, 0.6, 0.96, 0.36)
        set(value) {
            field = value
            invalidate()
        }

    /** Called on every drag with the updated normalised rect (to persist). */
    var onChanged: ((PDBoardRect) -> Unit)? = null

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var mode = 0 // 0 = move, 1 = resize
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0.0
    private var startY = 0.0
    private var startW = 0.0
    private var startH = 0.0

    private fun handleSize() = 48f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = (rect.x * width).toFloat()
        val y = (rect.y * height).toFloat()
        val w = (rect.width * width).toFloat()
        val h = (rect.height * height).toFloat()
        canvas.drawRect(x, y, x + w, y + h, fillPaint)
        canvas.drawRect(x, y, x + w, y + h, framePaint)
        // Bottom-right resize handle.
        val hs = handleSize()
        canvas.drawRect(x + w - hs, y + h - hs, x + w, y + h, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val fx = (rect.x * width).toFloat()
        val fy = (rect.y * height).toFloat()
        val fw = (rect.width * width).toFloat()
        val fh = (rect.height * height).toFloat()
        val hs = handleSize()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = event.rawX
                startRawY = event.rawY
                startX = rect.x
                startY = rect.y
                startW = rect.width
                startH = rect.height
                // Bottom-right handle hit => resize, otherwise move.
                mode = if (event.x > fx + fw - hs && event.y > fy + fh - hs) 1 else 0
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - startRawX) / width
                val dy = (event.rawY - startRawY) / height
                var nx = startX
                var ny = startY
                var nw = startW
                var nh = startH
                if (mode == 1) {
                    // Width drives height at the locked 6:5 ratio.
                    nw = (startW + dx).coerceIn(0.25, 1.0)
                    nh = nw * PDBoard.ROWS / PDBoard.COLS
                    if (ny + nh > 1.0) nh = 1.0 - ny
                } else {
                    nx = (startX + dx).coerceIn(0.0, 1.0 - startW)
                    ny = (startY + dy).coerceIn(0.0, 1.0 - startH)
                }
                rect = PDBoardRect(nx, ny, nw, nh)
                onChanged?.invoke(rect)
                return true
            }
            MotionEvent.ACTION_UP -> {
                mode = 0
                return true
            }
        }
        return false
    }
}
