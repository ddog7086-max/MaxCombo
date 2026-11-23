package com.example.maxcombo.core

/**
 * Preset list shown in the target picker. Starts with the most-used clearing
 * goals; more count levels / skill presets are added in later revisions.
 */
object OrbPresets {
    val all: List<OrbTarget> = buildList {
        add(OrbTarget.MaxCombo)
        // Single-colour clear-N (first wave).
        for (color in intArrayOf(Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK)) {
            add(OrbTarget.ColorCount(color, 4))
        }
        // Horizontal row of each colour.
        for (color in intArrayOf(Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART)) {
            add(OrbTarget.Horizontal(color))
        }
        // Plus/cross of each colour.
        for (color in intArrayOf(Orb.FIRE, Orb.WATER, Orb.WOOD, Orb.LIGHT, Orb.DARK, Orb.HEART)) {
            add(OrbTarget.Cross(color))
        }
    }
}
