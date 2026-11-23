package com.example.maxcombo.core

/**
 * A clearing goal the solver tries to satisfy (PuzzCombo-style presets).
 * [MaxCombo] keeps the original behaviour: just chase the largest combo / clear.
 */
sealed class OrbTarget {
    /** Default: maximise combos / cleared orbs, no extra condition. */
    object MaxCombo : OrbTarget()

    /** Clear at least [count] orbs of [color] in the first (non-cascade) wave. */
    data class ColorCount(val color: Int, val count: Int) : OrbTarget()

    /** Form one full horizontal row of [color] (six in a row). */
    data class Horizontal(val color: Int) : OrbTarget()

    /** Form a plus/cross of [color] (centre + four arms, five orbs). */
    data class Cross(val color: Int) : OrbTarget()

    /** Fixed five colours: fire/water/wood/light/dark each clear a group (>=3). */
    object FiveColor : OrbTarget()

    /** Human-readable label for the preset button / menu. */
    val label: String
        get() = when (this) {
            MaxCombo -> "最大 Combo"
            is ColorCount -> "${colorName(color)} ${count} 颗"
            is Horizontal -> "${colorName(color)} 横排"
            is Cross -> "${colorName(color)} 十字"
            FiveColor -> "固定 5 色"
        }

    private fun colorName(c: Int): String = when (c) {
        Orb.FIRE -> "火"
        Orb.WATER -> "水"
        Orb.WOOD -> "木"
        Orb.LIGHT -> "光"
        Orb.DARK -> "闇"
        Orb.HEART -> "心"
        else -> "?"
    }
}

/** First-wave (before cascade) clearing facts of a board right after a drag. */
data class FirstWave(
    val colorCleared: IntArray,
    val horizontalColor: Int,
    val crossColor: Int,
) {
    fun colorCount(c: Int): Int = colorCleared[c]
}

/** Does this target hold against the first-wave result of the dragged board? */
fun OrbTarget.matches(fw: FirstWave): Boolean = when (this) {
    OrbTarget.MaxCombo -> true
    is OrbTarget.ColorCount -> fw.colorCount(color) >= count
    is OrbTarget.Horizontal -> fw.horizontalColor == color
    is OrbTarget.Cross -> fw.crossColor == color
    OrbTarget.FiveColor -> listOf(Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK)
        .all { fw.colorCount(it) >= 3 }
}
