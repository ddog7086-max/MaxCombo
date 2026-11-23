package com.example.maxcombo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.maxcombo.core.Orb
import com.example.maxcombo.core.PDBoard

/**
 * Tiny standalone colour key in its own small window at the screen's top-left.
 * Shows the total detected orb count plus a compact per-colour legend
 * (colour dot + count) so the user can verify recognition against the real board.
 * Touch-through; never blocks play.
 */
class ColorKeyView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var cells: IntArray? = null

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.density
        isFakeBoldText = true
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun setBoard(board: PDBoard) {
        cells = board.cells
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density

        // Always draw the rounded panel so the user can see where the count sits,
        // even before the first analysis.
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f * d, 8f * d, bg)

        val c = cells
        if (c == null) {
            canvas.drawText("按「分析」", 10f * d, 20f * d, text)
            return
        }

        val counts = IntArray(Orb.COLOR_COUNT)
        var filled = 0
        for (v in c) {
            if (v != Orb.EMPTY) {
                counts[v]++
                filled++
            }
        }

        canvas.drawText("珠 $filled/30", 10f * d, 18f * d, text)

        val names = arrayOf("火", "水", "木", "光", "闇", "心")
        for (i in 0 until Orb.COLOR_COUNT) {
            val row = i / 3
            val col = i % 3
            val cx = (12 + col * 52) * d
            val cy = (34 + row * 20) * d
            dot.color = orbColor(i)
            canvas.drawCircle(cx, cy, 6f * d, dot)
            canvas.drawText("${names[i]}×${counts[i]}", cx + 10f * d, cy + 4f * d, text)
        }
    }

    private fun orbColor(orb: Int): Int = when (orb) {
        Orb.FIRE -> Color.rgb(230, 64, 56)
        Orb.WATER -> Color.rgb(64, 140, 242)
        Orb.WOOD -> Color.rgb(97, 204, 97)
        Orb.LIGHT -> Color.rgb(250, 219, 71)
        Orb.DARK -> Color.rgb(158, 82, 199)
        Orb.HEART -> Color.rgb(245, 128, 161)
        else -> Color.rgb(90, 90, 90)
    }
}
