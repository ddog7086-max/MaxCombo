package com.example.maxcombo.core

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a screen bitmap plus a board rectangle into 30 orb colours.
 *
 * Colour classification uses HSV hue (colour wheel) instead of RGB Euclidean
 * distance: the six orbs are saturated pure colours, so their hue angle is a
 * far more stable feature than raw red/green/blue differences, which drift with
 * brightness and theme. Low-saturation / too-dark pixels (background, empty)
 * map to EMPTY.
 */
object OrbRecognizer {

    fun classify(bitmap: Bitmap, rect: PDBoardRect): PDBoard? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val cells = IntArray(PDBoard.CELL_COUNT) { Orb.EMPTY }

        for (row in 0 until PDBoard.ROWS) {
            for (col in 0 until PDBoard.COLS) {
                val fx = rect.x + rect.width * (col + 0.5) / PDBoard.COLS
                val fy = rect.y + rect.height * (row + 0.5) / PDBoard.ROWS
                val px = (fx * width).toInt()
                val py = (fy * height).toInt()
                if (px < 2 || px >= width - 2 || py < 2 || py >= height - 2) continue

                // Sample the cell's centre window and keep only the most saturated
                // pixels: this reads the orb's true base colour.
                val cellW = rect.width * width / PDBoard.COLS
                val cellH = rect.height * height / PDBoard.ROWS
                val half = maxOf(3, (minOf(cellW, cellH) / 6).toInt())

                val sampled = ArrayList<Pair<Double, Int>>()
                for (dy in -half..half) {
                    for (dx in -half..half) {
                        val x = px + dx
                        val y = py + dy
                        if (x in 0 until width && y in 0 until height) {
                            val pixel = pixels[y * width + x]
                            sampled.add(saturationOf(pixel) to pixel)
                        }
                    }
                }
                sampled.sortByDescending { it.first }
                val topN = maxOf(8, sampled.size / 6)

                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (i in 0 until topN) {
                    val p = sampled[i].second
                    r += (p shr 16 and 0xFF)
                    g += (p shr 8 and 0xFF)
                    b += (p and 0xFF)
                }
                r /= topN * 255.0
                g /= topN * 255.0
                b /= topN * 255.0

                val (h, s, v) = rgbToHsv(r, g, b)
                // Desaturated (grey/white) or too dark = no orb here.
                if (s < 0.18 || v < 0.15) {
                    cells[PDBoard.index(row, col)] = Orb.EMPTY
                    continue
                }
                // Fixed hue bands (measured from this skin) instead of nearest-neighbour:
                // nearest-neighbour pulls orange fire (h30-33) toward yellow light (h50).
                cells[PDBoard.index(row, col)] = hueBand(h)
            }
        }
        return PDBoard(cells)
    }

    /**
     * Fixed hue bands on the colour wheel (degrees). Measured per-orb from a real
     * screenshot so highlight/skin drift stays inside the right bucket:
     * fire h30-33, light h52-66, wood h103-114, water h198-217, dark h271-282,
     * heart h328-329.
     */
    private fun hueBand(h: Double): Int = when {
        h >= 305 && h <= 350 -> Orb.HEART
        h < 42 || h >= 345 -> Orb.FIRE
        h < 75 -> Orb.LIGHT
        h >= 95 && h <= 150 -> Orb.WOOD
        h >= 190 && h <= 235 -> Orb.WATER
        h >= 250 && h <= 300 -> Orb.DARK
        else -> Orb.EMPTY
    }

    /** Saturation of a packed ARGB pixel, used to rank orb-base pixels over face. */
    private fun saturationOf(pixel: Int): Double {
        val r = (pixel shr 16 and 0xFF) / 255.0
        val g = (pixel shr 8 and 0xFF) / 255.0
        val b = (pixel and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return if (mx == 0.0) 0.0 else (mx - mn) / mx
    }

    private fun rgbToHsv(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val v = mx
        val s = if (mx == 0.0) 0.0 else d / mx
        var h = 0.0
        if (d != 0.0) {
            h = when (mx) {
                r -> ((g - b) / d) % 6.0
                g -> (b - r) / d + 2.0
                else -> (r - g) / d + 4.0
            }
            h = (h * 60.0 + 360.0) % 360.0
        }
        return Triple(h, s, v)
    }
}
