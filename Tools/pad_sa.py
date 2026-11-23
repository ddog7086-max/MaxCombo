"""Experiment: simulated annealing over full drag paths."""
import math
import random
import time
from pad_solver import W, H, apply_drag, resolve, new_board, NEI


def random_path(rng, start, length):
    path = [start]
    prev = None
    last = start
    for _ in range(max(0, length)):
        opts = []
        for dr, dc in NEI:
            nr, nc = last[0] + dr, last[1] + dc
            if 0 <= nr < H and 0 <= nc < W and (nr, nc) != prev:
                opts.append((nr, nc))
        if not opts:
            break
        nxt = rng.choice(opts)
        path.append(nxt)
        prev, last = last, nxt
    return path


def score(board, path, length_weight=0.35):
    combos = resolve(apply_drag(board, path))
    return combos * 10 - length_weight * len(path), combos


def solve_sa(board, rng, budget=1500, max_len=24, restarts=8, t_start=2.5):
    best_path, best_sc, best_combos = None, -1e9, 0
    for _ in range(12):
        start = (rng.randrange(H), rng.randrange(W))
        p = random_path(rng, start, rng.randint(8, max_len))
        sc, cb = score(board, p)
        if sc > best_sc:
            best_sc, best_path, best_combos = sc, p, cb

    for _ in range(restarts):
        cur_path, cur_sc = list(best_path), best_sc
        temp = t_start
        for _ in range(budget):
            cand = list(cur_path)
            if rng.random() < 0.6:
                i = rng.randrange(1, len(cand)) if len(cand) > 1 else 1
                cand = cand[:i]
                room = max_len - len(cand)
                length = rng.randint(1, room) if room >= 1 else 0
                if length:
                    cand = cand + random_path(rng, cand[-1], length)[1:]
            else:
                if len(cand) < 2:
                    continue
                i = rng.randrange(1, len(cand))
                opts = []
                for dr, dc in NEI:
                    nr, nc = cand[i - 1][0] + dr, cand[i - 1][1] + dc
                    if 0 <= nr < H and 0 <= nc < W:
                        opts.append((nr, nc))
                if not opts:
                    continue
                cand[i] = rng.choice(opts)
                for j in range(1, len(cand)):
                    if abs(cand[j][0] - cand[j - 1][0]) + abs(cand[j][1] - cand[j - 1][1]) != 1:
                        cand = cand[:j]
                        break
            if len(cand) < 2:
                continue
            sc, cb = score(board, cand)
            delta = sc - cur_sc
            if delta >= 0 or rng.random() < math.exp(delta / max(temp, 1e-6)):
                cur_path, cur_sc = cand, sc
                if sc > best_sc:
                    best_sc, best_path, best_combos = sc, cand, cb
            temp *= 0.9965
            if temp < 0.05:
                temp = t_start
    return best_combos, best_path


if __name__ == "__main__":
    rng = random.Random(11)
    n = 10
    tot = 0
    t0 = time.time()
    for i in range(n):
        b = new_board(rng)
        c, p = solve_sa(b, rng)
        tot += c
        print(f"board {i}: {c} combos, {len(p)} cells")
    print(f"average {tot / n:.2f}, {(time.time() - t0) / n * 1000:.0f} ms/board")
