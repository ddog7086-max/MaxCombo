"""P&D (Puzzle & Dragons) board mechanics + route solver.

Python reference implementation. The Swift code under Sources/Shared mirrors
this file 1:1, so anything verified here is verified there (modulo porting).

Board layout: 6 columns (c) x 5 rows (r), r=0 is the top row.
"""

import random

W, H = 6, 5
FIRE, WATER, WOOD, LIGHT, DARK, HEART = range(6)
EMPTY = -1


def new_board(rng=random, colors=6):
    return [[rng.randrange(colors) for _ in range(W)] for _ in range(H)]


# ---------------------------------------------------------------- mechanics
def apply_drag(board, path):
    """Net effect of a P&D drag: every orb on the path shifts one cell
    backwards, and the start orb ends up at the last cell."""
    b = [row[:] for row in board]
    vals = [b[r][c] for r, c in path]
    for i in range(len(path) - 1):
        r, c = path[i]
        b[r][c] = vals[i + 1]
    r, c = path[-1]
    b[r][c] = vals[0]
    return b


def runs(b):
    """All maximal horizontal/vertical runs of length >= 3."""
    out = []
    for r in range(H):
        c = 0
        while c < W:
            v = b[r][c]
            s = c
            while c < W and b[r][c] == v:
                c += 1
            if v != EMPTY and c - s >= 3:
                out.append(frozenset((r, x) for x in range(s, c)))
    for c in range(W):
        r = 0
        while r < H:
            v = b[r][c]
            s = r
            while r < H and b[r][c] == v:
                r += 1
            if v != EMPTY and r - s >= 3:
                out.append(frozenset((y, c) for y in range(s, r)))
    return out


def resolve(b, skyfall=None, max_iter=100):
    """Clear/cascade loop. Returns total combo count (number of runs cleared).
    skyfall=None means we ignore skyfall chains (solver-friendly, deterministic)."""
    combos = 0
    for _ in range(max_iter):
        rs = runs(b)
        if not rs:
            break
        cells = set()
        for run in rs:
            cells |= run
        combos += len(rs)
        for (r, c) in cells:
            b[r][c] = EMPTY
        for c in range(W):
            col = [b[r][c] for r in range(H) if b[r][c] != EMPTY]
            newcol = [EMPTY] * (H - len(col)) + col
            for r in range(H):
                b[r][c] = newcol[r]
        if skyfall:
            for c in range(W):
                for r in range(H):
                    if b[r][c] == EMPTY:
                        b[r][c] = skyfall()
    return combos


def near_score(b):
    """Cheap heuristic for a partially-dragged board: how close are we to
    matches. Counts cells sitting in runs of length >= 2."""
    score = 0
    for r in range(H):
        c = 0
        while c < W:
            v = b[r][c]
            s = c
            while c < W and b[r][c] == v:
                c += 1
            if v != EMPTY and c - s == 2:
                score += 1
    for c in range(W):
        r = 0
        while r < H:
            v = b[r][c]
            s = r
            while r < H and b[r][c] == v:
                r += 1
            if v != EMPTY and r - s == 2:
                score += 1
    return score


# ------------------------------------------------------------------ solver
NEI = ((1, 0), (-1, 0), (0, 1), (0, -1))


def solve(board, max_steps=26, beam=40, keep=5):
    """Beam search over drag paths. Returns (combos, path, board_after_drag)."""
    best_combos = -1
    best = None

    frontier = []
    for r in range(H):
        for c in range(W):
            frontier.append((board, [(r, c)], near_score(board)))
    # dedupe identical boards, keep the shortest path
    frontier = _dedupe(frontier)[:beam]

    for step in range(1, max_steps + 1):
        cand = []
        for (b, path, _) in frontier:
            last = path[-1]
            prev = path[-2] if len(path) > 1 else None
            for dr, dc in NEI:
                nr, nc = last[0] + dr, last[1] + dc
                if not (0 <= nr < H and 0 <= nc < W):
                    continue
                if prev is not None and (nr, nc) == prev:
                    continue
                nb = apply_drag(b, [last, (nr, nc)])
                cand.append((nb, path + [(nr, nc)], near_score(nb)))
        if not cand:
            break
        frontier = _dedupe(cand)[:beam]

        for (b, path, _) in frontier:
            combos = resolve([row[:] for row in b])
            key = (combos, -len(path))
            if key > (best_combos, -(len(best[1]) if best else 10**9)):
                best_combos, best = combos, (b, path)
    return best_combos, (best[1] if best else []), (best[0] if best else board)


def _dedupe(items, limit=None):
    seen = {}
    for b, path, sc in items:
        k = tuple(tuple(row) for row in b)
        if k not in seen or len(path) < len(seen[k][1]):
            seen[k] = (b, path, sc)
    out = sorted(seen.values(), key=lambda t: -t[2])
    return out[:limit] if limit else out


if __name__ == "__main__":
    rng = random.Random(7)
    tot = 0
    n = 8
    for i in range(n):
        b = new_board(rng)
        combos, path, _ = solve(b)
        tot += combos
        print(f"board {i}: {combos} combos, {len(path)} cells")
    print(f"average: {tot / n:.2f} combos")
