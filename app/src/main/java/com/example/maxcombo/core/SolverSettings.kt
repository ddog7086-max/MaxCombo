package com.example.maxcombo.core

import android.content.Context
import android.content.SharedPreferences

/**
 * PuzzCombo-style solver settings, persisted in SharedPreferences.
 *
 * - Per-colour priority (fire/water/wood/light/dark/heart): 0=無効, 1=並, 2=優先.
 *   A preferred colour gets extra score when its orbs are cleared first-wave.
 * - quality (計算量): 0=高速 .. 2=高品質, scales beam width / search depth.
 * - moveBias (移動): 0=少なめ (prefer short routes) .. 2=多め (allow longer routes).
 * - attackMode: true=全体攻撃 (favour full-row / mass clears), false=コンボ優先
 *   (favour maximum combos) — mirrors the PuzzCombo checkboxes.
 */
object SolverSettings {
    const val PRIORITY_DISABLED = 0
    const val PRIORITY_NORMAL = 1
    const val PRIORITY_PREFERRED = 2

    private const val PREFS = "maxcombo_solver"
    private const val KEY_PRIORITY = "color_priority"
    private const val KEY_QUALITY = "quality"
    private const val KEY_MOVE = "move_bias"
    private const val KEY_ATTACK = "attack_mode"

    /** Per-colour priority 0..2, indexed by Orb colour (0..5). */
    val colorPriority: IntArray = IntArray(6) { PRIORITY_NORMAL }

    /** 0=高速 .. 2=高品質. */
    var quality: Int = 1

    /** 0=少なめ .. 2=多め. */
    var moveBias: Int = 1

    /** true=全体攻撃, false=コンボ優先. */
    var attackMode: Boolean = false

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val s = sp(ctx)
        val raw = s.getString(KEY_PRIORITY, null)
        if (raw != null) {
            raw.split(',').forEachIndexed { i, v ->
                if (i < 6) colorPriority[i] = v.trim().toIntOrNull() ?: PRIORITY_NORMAL
            }
        } else {
            colorPriority.fill(PRIORITY_NORMAL)
        }
        quality = s.getInt(KEY_QUALITY, 1)
        moveBias = s.getInt(KEY_MOVE, 1)
        attackMode = s.getBoolean(KEY_ATTACK, false)
    }

    fun save(ctx: Context) {
        sp(ctx).edit()
            .putString(KEY_PRIORITY, colorPriority.joinToString(","))
            .putInt(KEY_QUALITY, quality)
            .putInt(KEY_MOVE, moveBias)
            .putBoolean(KEY_ATTACK, attackMode)
            .apply()
    }
}
