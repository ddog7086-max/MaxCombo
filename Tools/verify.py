"""Correctness + performance checks for the P&D core."""
import random
import time
from pad_solver import (W, H, EMPTY, FIRE, WATER, WOOD, LIGHT, DARK, HEART,
                        apply_drag, resolve, runs, new_board)
from pad_sa import solve_sa


def check(cond, msg):
    print(("PASS  " if cond else "FAIL  ") + msg)
    assert cond, msg


# 1) a two-cell drag is a plain swap
b = [[0] * W for _ in range(H)]
b[0][0], b[0][1] = FIRE, WATER
after = apply_drag(b, [(0, 0), (0, 1)])
check(after[0][0] == WATER and after[0][1] == FIRE, "2-cell drag == swap")

# 2) path orbs shift one cell backwards, start orb lands at the end
b = [[0] * W for _ in range(H)]
seq = [FIRE, WATER, WOOD, LIGHT]
for i, v in enumerate(seq):
    b[0][i] = v
after = apply_drag(b, [(0, 0), (0, 1), (0, 2), (0, 3)])
check(after[0][:4] == [WATER, WOOD, LIGHT, FIRE], "path shift: %s" % after[0][:4])

# 3) non-path cells untouched
b = new_board(random.Random(3))
after = apply_drag(b, [(0, 0), (1, 0)])
check(all(after[r][c] == b[r][c] for r in range(H) for c in range(W)
          if (r, c) not in {(0, 0), (1, 0)}), "untouched cells preserved")

# 4) a 4-in-a-row clears, gravity drops the rest, combo counted once
b = [[EMPTY] * W for _ in range(H)]
for c in range(4):
    b[2][c] = FIRE
b[0][0] = WATER
b[1][0] = WOOD
check(len(runs(b)) == 1, "one run detected")
combos = resolve(b)
check(combos == 1, "one combo from one run, got %d" % combos)
check(b[3][0] == WATER and b[4][0] == WOOD, "gravity: column compacted to bottom")

# 5) simultaneous clear counts each run as a combo
b = [[EMPTY] * W for _ in range(H)]
for c in range(3):
    b[0][c] = FIRE
for c in range(3):
    b[3][c] = WATER
check(resolve(b) == 2, "two runs -> 2 combos")

# 6) solver self-consistency: the reported combo count must match the path
rng = random.Random(99)
bad = 0
for _ in range(60):
    board = new_board(rng)
    combos, path = solve_sa(board, rng, budget=400, restarts=2)
    recomputed = resolve(apply_drag(board, path))
    if recomputed != combos:
        bad += 1
check(bad == 0, "solver output reproducible (%d mismatches)" % bad)

# 7) stress: no crash / no illegal path on many boards
rng = random.Random(5)
for _ in range(120):
    board = new_board(rng)
    combos, path = solve_sa(board, rng, budget=300, restarts=2)
    for i in range(1, len(path)):
        check(sum(abs(path[i][k] - path[i - 1][k]) for k in (0, 1)) == 1,
              "path stays on adjacent cells")

# 8) average quality across boards
rng = random.Random(11)
n, tot, t0 = 20, 0, time.time()
for _ in range(n):
    board = new_board(rng)
    c, _ = solve_sa(board, rng)
    tot += c
print(f"\naverage {tot / n:.2f} combos over {n} boards, {(time.time() - t0) / n * 1000:.0f} ms/board")
