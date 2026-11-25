package com.example.maxcombo.core

import kotlin.math.exp
import kotlin.math.max

/**
 * Deterministic, fast RNG (SplitMix64). Deterministic matters: the same board
 * must always produce the same route, otherwise the overlay flickers between
 * candidates every frame. 1:1 port of the Swift solver.
 */
class SplitMix64(seed: ULong = 0x9E3779B97F4A7C15u) {
    private var state: ULong = seed

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15u
        var z = state
        z = (z xor (z.toLong() ushr 30).toULong()) * 0xBF58476D1CE4E5B9u
        z = (z xor (z.toLong() ushr 27).toULong()) * 0x94D049BB13311EBu
        return z xor (z.toLong() ushr 31).toULong()
    }

    /** Uniform double in [0, 1). */
    fun nextUnit(): Double = (next().toLong() ushr 11).toDouble() / 9_007_199_254_740_992.0
}

data class PDRoute(
    val path: List<Int> = emptyList(),
    val combos: Int = 0,
    val cleared: Int = 0,
)

/** Simulated-annealing search over complete drag paths. */
object PDSolver {
    private val NEIGHBOURS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /** Independent SA runs per board; the best result is kept. */
    private const val MULTI_RUNS = 10

    fun solve(
        board: PDBoard,
        maxLen: Int = 30,
        complexity: Int = 8,
        budgetPerRestart: Int = 0,
        restarts: Int = 0,
        seed: ULong = 0x123456789ABCDEF0uL,
        target: OrbTarget = OrbTarget.MaxCombo,
        fixedStart: Int = -1,
    ): PDRoute {
        // Complexity 0..20 scales search effort (more restarts / budget); high
        // settings run a deeper search to chase a full board clear.
        val level = complexity.coerceIn(0, 20)

        // Deterministic beam search (PuzzCombo-style scored expansion + beam
        // pruning + self-avoid dedup) instead of random SA: it systematically
        // grows simple non-crossing paths and keeps the highest-combo one, so
        // the route never knots itself. Beam width follows 計算量 (quality).
        val quality = SolverSettings.quality
        val (beamPath, _) = beamSearch(board, maxLen, level, target, fixedStart, quality)
        // Beam gives a strong non-knotting seed; run a bounded SA local polish on
        // top to escape its local 5-6 combo optimum and find the extra waves.
        val rng = SplitMix64(seed)
        val budget = when {
            level >= 10 -> 4000
            level >= 6 -> 2500
            else -> 1200
        }
        val restarts = when {
            level >= 10 -> 6
            level >= 6 -> 4
            else -> 3
        }
        val (_, saPath, _) = saOnce(board, rng, maxLen, budget, restarts, target, fixedStart)

        // Keep whichever route truly yields more combos (tie-break by cleared).
        fun statOf(path: List<Int>) = board.dragged(along = path).resolveStats()
        val beamR = statOf(beamPath)
        val saR = statOf(saPath)
        val bestPath = if (saR.combos > beamR.combos ||
            (saR.combos == beamR.combos && saR.cleared > beamR.cleared)
        ) saPath else beamPath
        val st = statOf(bestPath)
        return PDRoute(bestPath, st.combos, st.cleared)
    }

    /**
     * Deterministic scored beam search: grow self-avoiding adjacent paths from
     * every start, score each by combos, keep only the top-width beam each
     * depth (pruning + dedup), and remember the highest-combo path found.
     * No randomness -> same board always yields the same clean route.
     */
    private fun beamSearch(
        board: PDBoard,
        maxLen: Int,
        level: Int,
        target: OrbTarget,
        fixedStart: Int,
        quality: Int,
    ): Pair<List<Int>, Int> {
        // 計算量: 高速=窄 beam, 高品質=宽 beam (×1 基準, ×1.6 高品質).
        val baseWidth = when {
            level >= 10 -> 800
            level >= 6 -> 450
            else -> 180
        }
        val width = (baseWidth * (0.7 + quality * 0.3)).toInt()
        val starts = if (fixedStart in 0 until PDBoard.CELL_COUNT) listOf(fixedStart)
            else (0 until PDBoard.CELL_COUNT).toList()

        var beams = starts.map { listOf(it) to 0 }
        var bestPath: List<Int> = emptyList()
        var bestCombos = 0

        for (depth in 1..maxLen) {
            // path -> score -> combos
            val candidates = ArrayList<Triple<List<Int>, Int, Int>>()
            for ((path, _) in beams) {
                val last = path.last()
                for (nb in neighbourIndicesOf(last)) {
                    // PuzzCombo-style simple route: self-avoiding. Never revisit a
                    // cell, so the overlay line never loops back, crosses itself or
                    // stacks on top of itself and stays readable.
                    if (nb in path) continue
                    if (path.size >= 2 && nb == path[path.size - 2]) continue
                    val np = path + nb
                    if (hasShortLoop(np)) continue
                    val after = board.dragged(np)
                    val st = after.resolveStats()
                    val bonus = if (target.matches(after.firstWave())) 300 else 0
                    // Adjacent same-colour pairs are the seeds of 3-matches: before
                    // any combo appears they keep promising paths alive in the beam
                    // instead of being pruned as zero-score early paths.
                    val pairs = countAdjacentPairs(after)
                    val score = st.combos * 100 + st.cleared * 8 + pairs * 2 + bonus
                    candidates.add(Triple(np, score, st.combos))
                }
            }
            if (candidates.isEmpty()) break
            val distinct = candidates.distinctBy { it.first }
            val bestSeen = distinct.maxOf { it.third }
            val keep = LinkedHashSet<List<Int>>()
            // Top-scored window, plus an elite band: every path within one combo of
            // the best seen survives, so a near-optimal long route is never pruned
            // just because its raw score ranks below a shorter early-mature one.
            distinct.sortedByDescending { it.second }.take(width).forEach { keep.add(it.first) }
            distinct.filter { it.third >= bestSeen - 1 }.forEach { keep.add(it.first) }
            for (path in keep) {
                val combos = board.dragged(path).resolveStats().combos
                if (combos > bestCombos || (combos == bestCombos && path.size > bestPath.size)) {
                    bestCombos = combos
                    bestPath = path
                }
            }
            beams = keep.map { it to 0 }
        }
        return bestPath to bestCombos
    }

    /** Count horizontal/vertical adjacent same-colour pairs (3-match seeds). */
    private fun countAdjacentPairs(b: PDBoard): Int {
        var n = 0
        for (row in 0 until PDBoard.ROWS) {
            for (col in 0 until PDBoard.COLS) {
                val v = b.cell(row, col)
                if (v == Orb.EMPTY) continue
                if (col + 1 < PDBoard.COLS && b.cell(row, col + 1) == v) n++
                if (row + 1 < PDBoard.ROWS && b.cell(row + 1, col) == v) n++
            }
        }
        return n
    }

    private fun saOnce(
        board: PDBoard,
        rng: SplitMix64,
        maxLen: Int,
        budget: Int,
        restarts: Int,
        target: OrbTarget,
        fixedStart: Int = -1,
    ): Triple<Double, List<Int>, Int> {
        var bestPath: List<Int> = emptyList()
        var bestScore = Double.NEGATIVE_INFINITY
        var bestCombos = 0

        // Seed from many random drags of varied length to give SA diverse starts.
        repeat(64) {
            val start = if (fixedStart in 0 until PDBoard.CELL_COUNT) {
                fixedStart
            } else {
                rng.nextInt(PDBoard.CELL_COUNT)
            }
            val length = rng.nextInt(4, maxLen + 1)
            val candidate = randomPathFrom(start, length, maxLen, rng)
            val (score, combos) = evaluate(board, candidate, target)
            if (score > bestScore) {
                bestScore = score
                bestPath = candidate
                bestCombos = combos
            }
        }

        repeat(restarts) {
            var currentPath = bestPath
            var currentScore = bestScore
            var temperature = 2.5

            repeat(budget) {
                var candidate = currentPath

                val roll = rng.nextUnit()
                when {
                    roll < 0.55 -> {
                        // Re-route the tail from a random cell.
                        val cut = if (candidate.size > 1) rng.nextInt(1, candidate.size) else 1
                        val head = candidate.subList(0, cut)
                        val room = maxLen - head.size
                        if (room >= 1) {
                            val extra = rng.nextInt(1, room + 1)
                            val tail = randomPathFrom(
                                head[head.size - 1], extra, maxLen, rng,
                                excluded = head.toSet(),
                            )
                            candidate = head + tail.drop(1)
                        } else {
                            return@repeat
                        }
                    }
                    roll < 0.80 -> {
                        // Wiggle a single step to any neighbour; stay self-avoiding.
                        if (candidate.size < 2) return@repeat
                        val i = rng.nextInt(1, candidate.size)
                        val others = candidate.toSet() - candidate[i]
                        val options = neighbourIndicesOf(candidate[i - 1])
                            .filter { it !in others }
                        if (options.isNotEmpty()) {
                            candidate = candidate.toMutableList().also {
                                it[i] = options[rng.nextInt(options.size)]
                            }
                            val broken = candidate.indices.drop(1).firstOrNull { j ->
                                !areAdjacent(candidate[j - 1], candidate[j])
                            }
                            if (broken != null) candidate = candidate.subList(0, broken)
                        }
                    }
                    roll < 0.90 && candidate.size >= 3 -> {
                        // Swap two adjacent steps; keep the walk adjacent.
                        val i = rng.nextInt(1, candidate.size)
                        val q = candidate.toMutableList()
                        val t = q[i - 1]; q[i - 1] = q[i]; q[i] = t
                        val linksOk = (i - 1 == 0 || areAdjacent(q[i - 2], q[i - 1])) &&
                            (i == candidate.size - 1 || areAdjacent(q[i], q[i + 1]))
                        if (linksOk) candidate = q
                    }
                    candidate.size >= 3 && candidate.size < maxLen -> {
                        // Insert one cell between two consecutive steps (a zigzag).
                        val i = rng.nextInt(1, candidate.size)
                        val a = candidate[i - 1]; val b = candidate[i]
                        val x = neighbourIndicesOf(a)
                            .filter { it != b && areAdjacent(it, b) }
                        if (x.isNotEmpty()) {
                            val q = candidate.toMutableList()
                            q.add(i, x[rng.nextInt(x.size)])
                            candidate = q
                        }
                    }
                }

                if (candidate.size < 2) return@repeat
                // Keep the route self-avoiding: reject any path that revisits a cell.
                if (candidate.toSet().size != candidate.size) return@repeat
                if (hasShortLoop(candidate)) return@repeat
                val (score, combos) = evaluate(board, candidate, target)
                val delta = score - currentScore
                if (delta >= 0 || rng.nextUnit() < exp(delta / max(temperature, 1e-6))) {
                    currentPath = candidate
                    currentScore = score
                    if (score > bestScore) {
                        bestScore = score
                        bestPath = candidate
                        bestCombos = combos
                    }
                }

                temperature *= 0.9965
                if (temperature < 0.05) temperature = 2.5
            }
        }

        return Triple(bestScore, bestPath, bestCombos)
    }

    fun evaluate(
        board: PDBoard,
        path: List<Int>,
        target: OrbTarget = OrbTarget.MaxCombo,
    ): Pair<Double, Int> {
        val after = board.dragged(along = path)
        val st = after.resolveStats()
        val fw = after.firstWave()
        // Max-combo is the primary goal: every extra combo scores high and
        // monotonically beats fewer combos (no artificial 5..7 lock window).
        // Cleared orbs only give a small supporting bonus; they never outrank
        // an extra combo. Goal presets keep their large match bonus.
        val targetBonus = if (target.matches(fw)) 300.0 else 0.0

        // PuzzCombo per-colour priority (無効/並/優先): a preferred colour's
        // first-wave clears earn extra weight; 並 gives a small nudge.
        var priorityBonus = 0.0
        for (color in 0 until 6) {
            val n = fw.colorCount(color)
            when (SolverSettings.colorPriority[color]) {
                SolverSettings.PRIORITY_PREFERRED -> priorityBonus += n * 12.0
                SolverSettings.PRIORITY_NORMAL -> priorityBonus += n * 4.0
                else -> {}
            }
        }

        // 全体攻撃 mode: reward a full horizontal row in the first wave and a
        // large mass clear; コンボ優先 mode keeps combos dominant.
        var attackBonus = 0.0
        if (SolverSettings.attackMode) {
            if (fw.horizontalColor >= 0) attackBonus += 60.0
            attackBonus += st.cleared * 2.0
        }

        // 移動 (少なめ..多め): nudge route length. 少なめ prefers short,
        // 多め tolerates a longer route; tiny vs one combo.
        val moveBias = (SolverSettings.moveBias - 1) * 0.3

        // Count direction changes (turns). A small per-turn penalty prefers a
        // plain, straight, non-zigzag route over one that shuttles left-right;
        // tiny vs one extra combo so combos still dominate.
        var turns = 0
        for (i in 1 until path.size - 1) {
            val d1r = PDBoard.rowOf(path[i]) - PDBoard.rowOf(path[i - 1])
            val d1c = PDBoard.colOf(path[i]) - PDBoard.colOf(path[i - 1])
            val d2r = PDBoard.rowOf(path[i + 1]) - PDBoard.rowOf(path[i])
            val d2c = PDBoard.colOf(path[i + 1]) - PDBoard.colOf(path[i])
            if (d1r != d2r || d1c != d2c) turns++
        }
        val score = st.combos * 100.0 + st.cleared * 8.0 +
            targetBonus + priorityBonus + attackBonus +
            path.size * (0.15 + moveBias) - turns * 1.0
        return Pair(score, st.combos)
    }

    private fun randomPathFrom(
        start: Int,
        length: Int,
        maxLen: Int,
        rng: SplitMix64,
        excluded: Set<Int> = emptySet(),
    ): List<Int> {
        // Self-avoiding: the next cell may not be the one just left (about-turn)
        // nor any cell already visited, so the route never loops back.
        val path = mutableListOf(start)
        val visited = mutableSetOf(start)
        var previous = -1
        var last = start
        val target = minOf(length, maxLen)

        while (path.size < target) {
            val options = neighbourIndicesOf(last)
                .filter { it != previous && it !in excluded && it !in visited }
            if (options.isEmpty()) break
            val next = options[rng.nextInt(options.size)]
            path.add(next)
            visited.add(next)
            previous = last
            last = next
        }
        return path
    }

    /**
     * True if a cell is revisited after only a short gap (a tight little loop /
     * knot). Long detours that come back many steps later are allowed (they help
     * clear orbs); only the short local loops are rejected.
     */
    private fun hasShortLoop(path: List<Int>, maxGap: Int = 4): Boolean {
        val lastPos = HashMap<Int, Int>()
        for (i in path.indices) {
            val prev = lastPos[path[i]]
            if (prev != null && i - prev <= maxGap) return true
            lastPos[path[i]] = i
        }
        return false
    }

    private fun neighbourIndicesOf(index: Int): List<Int> {
        val row = PDBoard.rowOf(index)
        val col = PDBoard.colOf(index)
        val options = ArrayList<Int>(4)
        for ((dRow, dCol) in NEIGHBOURS) {
            val nextRow = row + dRow
            val nextCol = col + dCol
            if (nextRow in 0 until PDBoard.ROWS && nextCol in 0 until PDBoard.COLS) {
                options.add(PDBoard.index(nextRow, nextCol))
            }
        }
        return options
    }

    private fun areAdjacent(a: Int, b: Int): Boolean =
        Math.abs(PDBoard.rowOf(a) - PDBoard.rowOf(b)) +
            Math.abs(PDBoard.colOf(a) - PDBoard.colOf(b)) == 1

    // -- helpers on SplitMix64: uniform int ranges, mirroring Swift's Int.random --

    private fun SplitMix64.nextInt(until: Int): Int {
        if (until <= 0) return 0
        return (next() % until.toULong()).toInt()
    }

    private fun SplitMix64.nextInt(from: Int, until: Int): Int {
        if (until <= from) return from
        return from + nextInt(until - from)
    }
}
