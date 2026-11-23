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

            // Alternating parallel lanes: every edge steps onto the opposite side of
            // the track (even edges +, odd edges -) so consecutive segments run in
            // two separated lanes instead of stacking on the same line. Purely
            // visual; the solved drag path and clear count are unchanged.
            fun laneOffset(i: Int): FloatArray {
                val u = center(path[i - 1]); val v = center(path[i])
                val side = if (i % 2 == 0) 1f else -1f
                val mag = cell * 0.18f * side
                return if (Math.abs(v[0] - u[0]) > Math.abs(v[1] - u[1])) {
                    floatArrayOf(0f, mag)   // horizontal edge: lane off vertically
                } else {
                    floatArrayOf(mag, 0f)   // vertical edge: lane off horizontally
                }
            }
            // Each vertex sits on its outgoing edge's lane so consecutive edges
            // stay in two separated parallel channels through the turn.
            val rawPts = ArrayList<FloatArray>()
            for (i in path.indices) {
                val c = center(path[i])
                val offOut = if (i < path.size - 1) laneOffset(i + 1) else floatArrayOf(0f, 0f)
                rawPts.add(
                    floatArrayOf(
                        c[0] + offOut[0],
                        c[1] + offOut[1],
                    )
                )
            }

            // Orthogonalise fallback: split any residual diagonal into H-then-V.
            val pts = ArrayList<FloatArray>()
            pts.add(rawPts[0])
            for (i in 1 until rawPts.size) {
                val p = pts.last()
                val c = rawPts[i]
                if (Math.abs(c[0] - p[0]) > 1f && Math.abs(c[1] - p[1]) > 1f) {
                    pts.add(floatArrayOf(c[0], p[1]))
                }
                pts.add(c)
            }

            // LOCKED style: rectilinear / Manhattan track with a LARGE rounded
            // fillet so each 90-degree turn sweeps through a near-semicircle
            // instead of a sharp corner. Do not use free-form bezier.
            val curve = Path()
            fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
            )
            curve.moveTo(pts[0][0], pts[0][1])
            for (i in 1 until pts.size - 1) {
                val p0 = pts[i - 1]; val p1 = pts[i]; val p2 = pts[i + 1]
                val d1x = p1[0] - p0[0]; val d1y = p1[1] - p0[1]
                val d2x = p2[0] - p1[0]; val d2y = p2[1] - p1[1]
                val dot = d1x * d2x + d1y * d2y
                // Only a true reversal (180°, dot<0) draws a sharp joint; every
                // 90° turn sweeps through a fillet using half of each adjacent
                // leg, i.e. a near-semicircle, never a right-angle.
                if (dot < 0f) {
                    curve.lineTo(p1[0], p1[1])
                    continue
                }
                val l1 = Math.hypot(d1x.toDouble(), d1y.toDouble()).toFloat()
                val l2 = Math.hypot(d2x.toDouble(), d2y.toDouble()).toFloat()
                val rr = minOf(l1 / 2f, l2 / 2f)
                if (rr < 1f) {
                    curve.lineTo(p1[0], p1[1])
                    continue
                }
                val a = lerp(p1, p0, rr / l1)
                val b = lerp(p1, p2, rr / l2)
                curve.lineTo(a[0], a[1])
                curve.quadTo(p1[0], p1[1], b[0], b[1])
            }
            curve.lineTo(pts.last()[0], pts.last()[1])

            // Dark near-black outer pipe (thicker) for clear contrast over bright
            // orbs. Inner core fades white -> black UNIFORMLY by walked distance
            // (arc-length proportion), so the whole route shades evenly instead of
            // only the head being white and the tail all black.
            val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 14f
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
                color = Color.argb(a, 35, 33, 33)
            }
            canvas.drawPath(curve, outerPaint)

            // Cumulative arc length so each segment's shade follows how far along
            // the route it is (0 = white head, 1 = black tail, mid = even grey).
            val segLen = FloatArray(pts.size - 1)
            var total = 0f
            for (j in 0 until pts.size - 1) {
                val dx = pts[j + 1][0] - pts[j][0]
                val dy = pts[j + 1][1] - pts[j][1]
                segLen[j] = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                total += segLen[j]
            }
            val innerSeg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
            var acc = 0f
            for (j in 0 until pts.size - 1) {
                val t = if (total > 0f) (acc + segLen[j] / 2f) / total else 0f
                val g = (255 * (1f - t)).toInt().coerceIn(0, 255)
                innerSeg.color = Color.argb(a, g, g, g)
                val sp = Path()
                sp.moveTo(pts[j][0], pts[j][1])
                sp.lineTo(pts[j + 1][0], pts[j + 1][1])
                canvas.drawPath(sp, innerSeg)
                acc += segLen[j]
            }

            // Start: the white end of the line itself, a white filled head seated
            // right on the first point so it reads as one piece with the route.
            val start = pts[0]
            val dotR = cell * 0.11f
            val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(a, 255, 255, 255)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(start[0], start[1], dotR, headPaint)

            // End: a solid BLACK arrowhead whose tip sits exactly on the last
            // point, rooted into the line tail so it is one piece with the black
            // end of the gradient (not a floating white triangle).
            val prev = pts[pts.size - 2]
            val end = pts.last()
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
                    color = Color.argb(a, 0, 0, 0)
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
