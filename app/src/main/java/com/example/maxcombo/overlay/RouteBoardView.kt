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
            val centers = path.map { center(it) }

            // --- Display-layer lane separation (drag logic untouched) ----------
            // The solver path is the real drag sequence; we only offset where it
            // is drawn. Group repeated undirected edges (u,v) and assign each
            // revisit to an alternate parallel lane, so a back-and-forth edge
            // never stacks on top of itself. Nodes still meet at the orb centre;
            // the offset applies only to the segment middle.
            val edgeCount = path.size - 1
            val groups = HashMap<Long, MutableList<Int>>()
            for (ei in 0 until edgeCount) {
                val u = path[ei]; val v = path[ei + 1]
                val key = (if (u < v) u.toLong() else v.toLong()) * 30 +
                        (if (u < v) v.toLong() else u.toLong())
                groups.getOrPut(key) { mutableListOf() }.add(ei)
            }
            val lane = IntArray(edgeCount)
            groups.values.forEach { list ->
                list.forEachIndexed { k, ei ->
                    lane[ei] = when {
                        k == 0 -> 0
                        k % 2 == 1 -> (k + 1) / 2
                        else -> -k / 2
                    }
                }
            }

            // Both endpoints of every edge ride their lane offset (not pulled
            // back to the orb centre), so the whole route stays one continuous
            // line; where it revisits an orb the passes run parallel instead of
            // stacking on the centre point.
            val sa = Array(edgeCount) { FloatArray(2) }
            val sb = Array(edgeCount) { FloatArray(2) }
            val segMid = Array(edgeCount) { FloatArray(2) }
            val segUx = FloatArray(edgeCount)
            val segUy = FloatArray(edgeCount)
            for (ei in 0 until edgeCount) {
                val cu = centers[ei]; val cv = centers[ei + 1]
                val dx = cv[0] - cu[0]; val dy = cv[1] - cu[1]
                val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val ux = if (len > 1f) dx / len else 0f
                val uy = if (len > 1f) dy / len else 0f
                val nx = -uy; val ny = ux
                val off = lane[ei] * 0.18f * cell
                sa[ei][0] = cu[0] + nx * off; sa[ei][1] = cu[1] + ny * off
                sb[ei][0] = cv[0] + nx * off; sb[ei][1] = cv[1] + ny * off
                segMid[ei][0] = (sa[ei][0] + sb[ei][0]) / 2f
                segMid[ei][1] = (sa[ei][1] + sb[ei][1]) / 2f
                segUx[ei] = ux; segUy[ei] = uy
            }

            val curve = Path()
            curve.moveTo(sa[0][0], sa[0][1])
            for (ei in 0 until edgeCount) {
                curve.lineTo(sb[ei][0], sb[ei][1])
                if (ei + 1 < edgeCount) curve.lineTo(sa[ei + 1][0], sa[ei + 1][1])
            }

            val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 11f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
                color = Color.argb(a, 35, 35, 35)
            }
            val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
                color = Color.argb(a, 255, 255, 255)
            }
            canvas.drawPath(curve, outerPaint)
            canvas.drawPath(curve, innerPaint)

            // Direction arrow on every segment middle (offset lane included), so
            // the walk order reads left-to-right/top-to-bottom at a glance.
            val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(a, 255, 255, 255)
                style = Paint.Style.FILL
            }
            for (ei in 0 until edgeCount) {
                if (segUx[ei] == 0f && segUy[ei] == 0f) continue
                val mx = segMid[ei][0]; val my = segMid[ei][1]
                val ux = segUx[ei]; val uy = segUy[ei]
                val px = -uy; val py = ux
                val s = cell * 0.10f
                val arrow = Path()
                arrow.moveTo(mx + ux * s, my + uy * s)
                arrow.lineTo(mx - ux * s * 0.6f + px * s * 0.6f, my - uy * s * 0.6f + py * s * 0.6f)
                arrow.lineTo(mx - ux * s * 0.6f - px * s * 0.6f, my - uy * s * 0.6f - py * s * 0.6f)
                arrow.close()
                canvas.drawPath(arrow, arrowFill)
            }

            // Start: a solid white dot on the first lane point.
            val start = sa[0]
            val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(a, 255, 255, 255)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(start[0], start[1], cell * 0.16f, startPaint)

            // End: a two-layer arrowhead whose tip sits exactly on the last lane point.
            val end = sb[edgeCount - 1]
            val ux = segUx[edgeCount - 1]
            val uy = segUy[edgeCount - 1]
            if (ux != 0f || uy != 0f) {
                val px = -uy; val py = ux
                val s = cell * 0.20f
                val outlineArrow = Path()
                outlineArrow.moveTo(end[0], end[1])
                outlineArrow.lineTo(end[0] - ux * s + px * s * 0.62f, end[1] - uy * s + py * s * 0.62f)
                outlineArrow.lineTo(end[0] - ux * s - px * s * 0.62f, end[1] - uy * s - py * s * 0.62f)
                outlineArrow.close()
                val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(a, 35, 35, 35)
                    style = Paint.Style.FILL
                }
                val coreArrow = Path()
                coreArrow.moveTo(end[0], end[1])
                coreArrow.lineTo(end[0] - ux * s * 0.72f + px * s * 0.40f, end[1] - uy * s * 0.72f + py * s * 0.40f)
                coreArrow.lineTo(end[0] - ux * s * 0.72f - px * s * 0.40f, end[1] - uy * s * 0.72f - py * s * 0.40f)
                coreArrow.close()
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(a, 255, 255, 255)
                    style = Paint.Style.FILL
                }
                canvas.drawPath(outlineArrow, outlinePaint)
                canvas.drawPath(coreArrow, corePaint)
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
