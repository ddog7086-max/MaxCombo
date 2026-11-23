package com.example.maxcombo.core

/**
 * Preset list shown in the target picker (PuzzCombo-style clearing goals).
 * Single-colour clear-N for every common count, horizontal rows, crosses,
 * and the fixed-five-colour goal.
 */
object OrbPresets {
    val all: List<OrbTarget> = buildList {
        add(OrbTarget.MaxCombo)

        // Single-colour clear-N: fire/water/wood/light/dark 4..9, heart 5/6/8.
        val five = intArrayOf(Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK)
        for (color in five) {
            for (n in intArrayOf(4, 5, 6, 7, 8, 9)) {
                add(OrbTarget.ColorCount(color, n))
            }
        }
        for (n in intArrayOf(5, 6, 8)) {
            add(OrbTarget.ColorCount(Orb.HEART, n))
        }

        // Horizontal row of each colour (including heart).
        for (color in intArrayOf(
            Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART,
        )) {
            add(OrbTarget.Horizontal(color))
        }

        // Plus/cross of each colour.
        for (color in intArrayOf(
            Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART,
        )) {
            add(OrbTarget.Cross(color))
        }

        add(OrbTarget.FiveColor)
    }
}
