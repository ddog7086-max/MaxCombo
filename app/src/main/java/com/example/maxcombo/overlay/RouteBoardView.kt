package com.example.maxcombo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.maxcombo.core.Orb
import com.example.maxcombo.core.PDBoard
import com.example.maxcombo.core.PDBoardRect
import com.example.maxcombo.core.PDRoute

/**
 * Transparent route layer over the real board (static frame mode). Maps the 6x5
 * grid onto the fixed FALLBACK full-board rectangle and draws the classic style:
 * hollow white circles on every cell the route visits, a smoothed white curve
 * linking them, a start circle and an end marker, with mid-point arrows.
 */
class RouteBoardView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var board: PDBoard? = null
    private var route: PDRoute = PDRoute()

    /** Main preview re-draws the board; overlay keeps false. */
    var showOrbs = false

    /** Calibrated board rect supplied by the service (normalised 0..1). */
    var boardRect: PDBoardRect? = null

    /** Invoked with the tapped cell index in the main preview (fixed-start pick). */
    var onCellClick: ((Int) -> Unit)? = null

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 255, 255)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /** Visibility of the whole route (line / start dot / end arrow), 0..255. */
    var routeAlpha: Int = 245
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    fun show(board: PDBoard, route: PDRoute) {
        this.board = board
        this.route = route
        invalidate()
    }

    init {
        setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                val fb = if (showOrbs) PDBoardRect.FALLBACK
                    else (boardRect ?: PDBoardRect.LOCKED)
                val ox = fb.x * width
                val oy = fb.y * height
                val cellW = fb.width * width / PDBoard.COLS
                val cellH = fb.height * height / PDBoard.ROWS
                val col = ((e.x - ox) / cellW).toInt()
                val row = ((e.y - oy) / cellH).toInt()
                if (row in 0 until PDBoard.ROWS && col in 0 until PDBoard.COLS) {
                    onCellClick?.invoke(PDBoard.index(row, col))
                }
            }
            true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = routeAlpha.coerceIn(0, 255)
        linePaint.alpha = a
        dotPaint.alpha = a
        fillPaint.alpha = a

        // Main preview fills itself; the overlay uses the calibrated rect,
        // falling back to an auto-fit square grid if none was saved.
        val fb = if (showOrbs) PDBoardRect.FALLBACK
            else (boardRect ?: PDBoardRect.LOCKED)
        val ox = (fb.x * width).toFloat()
        val oy = (fb.y * height).toFloat()
        val cellW = (fb.width * width / PDBoard.COLS).toFloat()
        val cellH = (fb.height * height / PDBoard.ROWS).toFloat()
        val orb = minOf(cellW, cellH) * 0.82f

        val b = board
        if (showOrbs && b != null) {
            for (index in 0 until PDBoard.CELL_COUNT) {
                val cx = ox + (PDBoard.colOf(index) + 0.5f) * cellW
                val cy = oy + (PDBoard.rowOf(index) + 0.5f) * cellH
                orbPaint.color = orbColor(b.cells[index])
                canvas.drawCircle(cx, cy, orb / 2f, orbPaint)
            }
        }

        val path = route.path
        if (path.size >= 2) {
            fun center(i: Int): FloatArray {
                val cx = ox + (PDBoard.colOf(i) + 0.5f) * cellW
                val cy = oy + (PDBoard.rowOf(i) + 0.5f) * cellH
                return floatArrayOf(cx, cy)
            }
            val cell = minOf(cellW, cellH)

            // PuzzCombo-style flowing smooth curve through the orb centres in
            // walk order: quadratic Bézier rounds every corner instead of a hard
            // right-angle polyline.
            val curve = Path()
            val pts = path.map { center(it) }
            curve.moveTo(pts[0][0], pts[0][1])
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i][0] + pts[i + 1][0]) / 2f
                val my = (pts[i][1] + pts[i + 1][1]) / 2f
                curve.quadTo(pts[i][0], pts[i][1], mx, my)
            }
            curve.lineTo(pts.last()[0], pts.last()[1])
            val cleanLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 7f
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
                color = Color.argb(a, 255, 255, 255)
            }
            canvas.drawPath(curve, cleanLine)

            // Start: a solid white dot on the first orb; the flowing white line
            // itself shows the drag order, so no step numbers are drawn (they
            // overlapped and hid each other on multi-visit cells).
            val start = center(path.first())
            canvas.drawCircle(start[0], start[1], cell * 0.16f, dotPaint)

            // Direction arrows along the line: one small arrowhead near the
            // midpoint of each segment so the walk order reads at a glance.
            for (i in 1 until path.size) {
                val a = center(path[i - 1])
                val b = center(path[i])
                val mx = (a[0] + b[0]) / 2f
                val my = (a[1] + b[1]) / 2f
                val dx = b[0] - a[0]
                val dy = b[1] - a[1]
                val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (len < 1f) continue
                val ux = dx / len
                val uy = dy / len
                val px = -uy
                val py = ux
                val s = cell * 0.11f
                val arrow = Path()
                arrow.moveTo(mx + ux * s, my + uy * s)
                arrow.lineTo(mx - ux * s * 0.6f + px * s * 0.6f, my - uy * s * 0.6f + py * s * 0.6f)
                arrow.lineTo(mx - ux * s * 0.6f - px * s * 0.6f, my - uy * s * 0.6f - py * s * 0.6f)
                arrow.close()
                canvas.drawPath(arrow, fillPaint)
            }

            // End: a solid arrowhead whose tip sits exactly on the last point.
            val prev = center(path[path.size - 2])
            val end = center(path.last())
            val dx = end[0] - prev[0]
            val dy = end[1] - prev[1]
            val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len > 1f) {
                val ux = dx / len
                val uy = dy / len
                val px = -uy
                val py = ux
                val s = cell * 0.20f
                val arrow = Path()
                arrow.moveTo(end[0], end[1])
                arrow.lineTo(end[0] - ux * s + px * s * 0.55f, end[1] - uy * s + py * s * 0.55f)
                arrow.lineTo(end[0] - ux * s - px * s * 0.55f, end[1] - uy * s - py * s * 0.55f)
                arrow.close()
                val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(a, 255, 255, 255)
                    style = Paint.Style.FILL
                }
                canvas.drawPath(arrow, arrowPaint)
            }

            val td = resources.displayMetrics.density
            val label = "消 ${route.cleared}/30 颗 · ${route.combos} combo · ${path.size} 格"
            val bx = 16f * td
            val by = 150f * td
            val tw = textPaint.measureText(label)
            canvas.drawRect(bx, by - 24f * td, bx + tw + 16f * td, by + 8f * td, bgPaint)
            canvas.drawText(label, bx, by, textPaint)
        }
    }

    companion object {
        private fun rgb(r: Double, g: Double, b: Double) =
            Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())

        fun orbColor(orb: Int): Int = when (orb) {
            Orb.FIRE -> rgb(0.90, 0.25, 0.22)
            Orb.WATER -> rgb(0.25, 0.55, 0.95)
            Orb.WOOD -> rgb(0.38, 0.80, 0.38)
            Orb.LIGHT -> rgb(0.98, 0.86, 0.28)
            Orb.DARK -> rgb(0.62, 0.32, 0.78)
            Orb.HEART -> rgb(0.96, 0.50, 0.63)
            else -> rgb(0.35, 0.35, 0.35)
        }
    }
}
