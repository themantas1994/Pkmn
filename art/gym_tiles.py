"""Themed gym tile sets (one per gym) -> appended to the world tileset by tiles.py.

Each gym gets 7-8 original 16x16 tiles in its own palettes (palettes.TILE, keys
``gym_<theme>_*``): a floor, a wall, decor, and puzzle / hazard pieces where the identity
calls for them. Every palette carries the gym's badge colours (see ``badges.BADGES``).

Tile names follow the engine's name-based rules (``TileRules`` in engine/world/WorldModel.kt):
``gym_wall_*``, ``statue_*``, ``pillar_*``, ``lamp_*``, ``plant_*``, ``bush_*``, ``machine_*``,
``rock_*``, ``puzzle_wall_*`` and ``lava*`` are solid; ``gym_floor_*``, ``dock_*``, ``float_*``,
``circuit_*``, ``rail_*`` are walkable. Sliding ice stays the existing ``ice`` tile.

Everything is deterministic (no randomness; fixed coordinates only).
"""
import math

import numpy as np

TS = 16


# ------------------------------------------------------------------ helpers
def F(c):
    return np.full((TS, TS), c, dtype=np.int8)


def rect(a, x, y, w, h, c):
    a[y:y + h, x:x + w] = c


def px(a, pts, c):
    for x, y in pts:
        a[y % TS, x % TS] = c


def hline(a, x0, x1, y, c):
    a[y, x0:x1 + 1] = c


def vline(a, x, y0, y1, c):
    a[y0:y1 + 1, x] = c


def frame(a, c, t=1):
    a[:t, :] = c
    a[-t:, :] = c
    a[:, :t] = c
    a[:, -t:] = c


def ell(a, cx, cy, rx, ry, c):
    h, w = a.shape
    for y in range(h):
        for x in range(w):
            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1.0:
                a[y, x] = c


def poly(a, pts, c):
    """Bresenham polyline through pts."""
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            a[y0 % TS, x0 % TS] = c
            if (x0, y0) == (x1, y1):
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy


def halo(a, bg, c):
    """Set every `bg` pixel that touches a non-bg pixel (4-neighbourhood) to c."""
    m = a != bg
    g = np.zeros_like(m)
    g[1:, :] |= m[:-1, :]
    g[:-1, :] |= m[1:, :]
    g[:, 1:] |= m[:, :-1]
    g[:, :-1] |= m[:, 1:]
    a[g & ~m] = c
    return a


def checker(a, x0, y0, w, h, c, phase=0):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            if (x + y + phase) % 2 == 0:
                a[y, x] = c


def blocks(a, step, c_hi, c_lo, c_seam, offset_rows=False, hi=True):
    """Square-block floor: seam on bottom/right of every block, light edge on top/left."""
    n = TS // step
    for by in range(n):
        off = (step // 2) if (offset_rows and by % 2) else 0
        y0 = by * step
        a[y0 + step - 1, :] = c_seam
        for bx in range(-1, n + 1):
            x0 = bx * step + off
            xs = x0 % TS
            if 0 <= x0 < TS or off:
                a[y0:y0 + step - 1, (x0 + step - 1) % TS] = c_seam
            if hi:
                a[y0, xs:min(TS, xs + step - 1)] = c_hi if c_hi is not None else a[y0, xs]
                a[y0 + 1:y0 + step - 1, xs] = c_hi if c_hi is not None else a[y0 + 1, xs]
    return a


# ------------------------------------------------------------------ statues
PEDESTAL = [
    "...oooooooooo...",
    "..ohhhhhhhhhho..",
    "..ommmmmmmmmmo..",
    "..oooooooooooo..",
    "..ohmhmhmhmhmo..",
    "..ommmmmmmmmmo..",
    "..oooooooooooo..",
]


def statue(figure, idx):
    """figure: 9 strings of 16 chars ('.' bg, h/m fill, o outline). idx = (bg, hi, mid, outline)."""
    rows = list(figure) + PEDESTAL
    assert len(rows) == TS, len(rows)
    m = {".": idx[0], "h": idx[1], "m": idx[2], "o": idx[3]}
    a = np.zeros((TS, TS), dtype=np.int8)
    for y, r in enumerate(rows):
        assert len(r) == TS, (y, r)
        for x, ch in enumerate(r):
            a[y, x] = m[ch]
    return a


def fig_from_array(arr):
    """9x16 array of 0 bg / 1 hi / 2 mid / 3 outline -> strings."""
    ch = ".hmo"
    return ["".join(ch[v] for v in row) for row in arr]


def fig_widths(widths, shade_from=1, top=0):
    """Symmetric figure from per-row widths (rows 0..8) with left highlight and right shade."""
    arr = np.zeros((9, TS), dtype=np.int8)
    for y, w in enumerate(widths):
        if w <= 0:
            continue
        x0 = 8 - w // 2
        arr[y, x0:x0 + w] = 1
        if w >= 3:
            arr[y, x0 + w - max(1, w // 3):x0 + w] = 2
    return halo_fig(arr)


def halo_fig(arr):
    m = arr != 0
    g = np.zeros_like(m)
    g[1:, :] |= m[:-1, :]
    g[:-1, :] |= m[1:, :]
    g[:, 1:] |= m[:, :-1]
    g[:, :-1] |= m[:, 1:]
    out = arr.copy()
    out[g & ~m] = 3
    return fig_from_array(out)


def fig_sprout():
    arr = np.zeros((9, TS), dtype=np.int8)
    ell(arr, 5, 4, 3.2, 2.0, 1)
    ell(arr, 11, 3, 3.2, 2.0, 1)
    ell(arr, 8, 1, 1.6, 1.4, 1)
    for x, y in ((6, 5), (7, 5), (9, 4), (10, 4), (12, 4), (8, 2)):
        arr[y, x] = 2
    arr[5:9, 8] = 2
    arr[6:9, 7] = 1
    return halo_fig(arr)


def fig_drop():
    return fig_widths([2, 2, 4, 5, 7, 8, 8, 7, 4])


def fig_crystal():
    return fig_widths([2, 4, 6, 8, 8, 8, 6, 4, 2])


def fig_flake():
    arr = np.zeros((9, TS), dtype=np.int8)
    vline(arr, 8, 0, 8, 1)
    hline(arr, 3, 13, 4, 1)
    poly(arr, [(4, 1), (12, 7)], 2)
    poly(arr, [(12, 1), (4, 7)], 2)
    px(arr, [(7, 1), (9, 1), (7, 7), (9, 7), (4, 3), (4, 5), (12, 3), (12, 5)], 1)
    arr[4, 8] = 2
    return halo_fig(arr)


FIG_DRAGON = [
    "..o..........o..",
    "..oo........oo..",
    "..ohoo.oo.oohho.",
    "...ohhohhohhho..",
    "...ohhhhhhhhho..",
    "..ohoohhhhoohho.",
    "..ohhhhmmmmhhho.",
    "...ohmoommoomo..",
    "....ooo.oo.ooo..",
]


# ------------------------------------------------------------------ SPROUT
def sprout_floor():
    # mown-lawn stripes: every other 8 px column is lighter, joined by a 50% checker seam
    a = F(1)
    a[:, 8:15] = 0
    for y in range(16):
        a[y, 7 if y % 2 else 15] = 0
    for x, y in ((2, 2), (10, 5), (5, 10), (12, 12)):
        px(a, [(x, y), (x + 2, y), (x + 1, y + 1)], 2)
    px(a, [(8, 12), (9, 12), (8, 13)], 3)
    return a


def sprout_stones():
    a = F(1)
    for x, y in ((1, 1), (9, 5), (2, 10), (11, 12)):
        ell(a, x + 3, y + 2, 3.2, 2.4, 2)
        px(a, [(x + 1, y), (x + 2, y), (x + 3, y)], 0)
        px(a, [(x, y + 1), (x + 1, y + 1)], 0)
        px(a, [(x + 3, y + 4), (x + 4, y + 4), (x + 5, y + 3)], 3)
    px(a, [(7, 2), (13, 2), (6, 14), (15, 9)], 0)
    return a


def hedge_base(a):
    for cx, cy in ((2, 1), (6, 1), (10, 1), (14, 1), (0, 5), (4, 5), (8, 5), (12, 5),
                   (2, 9), (6, 9), (10, 9), (14, 9), (0, 13), (4, 13), (8, 13), (12, 13)):
        px(a, [(cx, cy)], 0)
        px(a, [(cx - 1, cy + 1), (cx + 1, cy + 1), (cx, cy + 2)], 2)
    return a


def sprout_hedge():
    a = hedge_base(F(1))
    a[0, :] = 2
    px(a, [(1, 0), (2, 0), (5, 0), (6, 0), (7, 0), (11, 0), (12, 0)], 1)
    px(a, [(3, 0), (9, 0), (14, 0)], 0)
    a[14, :] = 2
    a[15, :] = 3
    return a


def sprout_hedge_block():
    a = hedge_base(F(1))
    a[1, 1:15] = 0
    a[1:14, 1] = 0
    a[13, 2:15] = 2
    a[2:14, 14] = 2
    frame(a, 3)
    a[14:, :] = 3
    a[0, 0] = a[0, 15] = 3
    return a


def sprout_blossom():
    a = F(2)
    px(a, [(1, 2), (6, 6), (12, 1), (9, 10), (2, 11), (14, 13), (5, 14)], 3)
    for x, y in ((3, 3), (10, 3), (6, 8), (13, 9), (2, 12), (9, 13)):
        px(a, [(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)], 1)
        px(a, [(x, y)], 0)
    a[15, :] = 3
    a[14, ::2] = 3
    return a


def sprout_plant():
    a = F(0)
    rect(a, 3, 9, 10, 2, 2)
    rect(a, 4, 11, 8, 4, 2)
    a[11:15, 11] = 3
    px(a, [(5, 12), (6, 12)], 3)
    ell(a, 5, 5, 3.2, 2.0, 1)
    ell(a, 11, 4, 3.2, 2.0, 1)
    ell(a, 8, 2, 1.8, 1.6, 1)
    vline(a, 8, 5, 8, 3)
    px(a, [(6, 6), (7, 6), (9, 5), (10, 5), (8, 3)], 3)
    # outline
    halo(a, 0, 3)
    return a


# ------------------------------------------------------------------ TIDE
def tide_floor():
    a = F(1)
    for by in (0, 8):
        for bx in (0, 8):
            a[by, bx:bx + 7] = 0
            a[by + 1:by + 7, bx] = 0
            a[by + 7, bx:bx + 8] = 2
            a[by:by + 8, bx + 7] = 2
    px(a, [(3, 4), (4, 3), (5, 3), (11, 12), (12, 11), (13, 11)], 2)
    px(a, [(5, 5), (13, 4), (2, 13), (10, 10)], 0)
    return a


def tide_wall():
    a = F(1)
    for b in range(4):
        y0, off = 4 * b, 4 * (b % 2)
        for x in range(TS):
            k = (x + off) % 8
            if k in (1, 2):
                a[y0, x] = 0
            if k in (0, 3):
                a[y0 + 1, x] = 0
            if k in (4, 5, 6, 7):
                a[y0 + 2, x] = 2
            if k in (6, 7):
                a[y0 + 3, x] = 2
    a[0, :] = 3
    a[14, :] = 2
    a[15, :] = 3
    return a


def dock_plank():
    a = F(1)
    for r, seam_x in ((0, 5), (1, 12), (2, 8), (3, 2)):
        y0 = 4 * r
        a[y0, :] = 3
        a[y0 + 1, :] = 0
        a[y0 + 1:y0 + 4, seam_x] = 3
        px(a, [(seam_x - 2, y0 + 2), (seam_x + 2, y0 + 2)], 2)
        px(a, [(seam_x + 4, y0 + 3), (seam_x - 4, y0 + 1)], 2)
    return a


def dock_plank_v():
    return np.rot90(dock_plank()).copy()


def float_platform():
    a = F(2)
    px(a, [(0, 3), (1, 3), (14, 12), (15, 12), (6, 0), (7, 0), (8, 15)], 3)
    rect(a, 1, 1, 14, 14, 3)
    rect(a, 2, 2, 12, 12, 1)
    for y in (2, 6, 10):
        a[y, 2:14] = 0
    for y in (5, 9, 13):
        a[y, 2:14] = 3
    for x, y in ((2, 2), (13, 2), (2, 12), (13, 12)):
        px(a, [(x, y)], 3)
    px(a, [(5, 4), (10, 8), (4, 12)], 3)
    return a


def pillar_mooring():
    a = dock_plank()
    a[:, :] = np.where(a == 3, 2, a)
    ell(a, 8, 4, 4, 2.4, 0)
    rect(a, 5, 4, 6, 9, 1)
    a[4:13, 5] = 0
    a[4:13, 9:11] = 2
    px(a, [(6, 7), (7, 7), (8, 7), (9, 7), (10, 7)], 3)
    px(a, [(6, 9), (7, 9), (8, 9), (9, 9), (10, 9)], 3)
    ell(a, 8, 13, 3.2, 1.2, 3)
    ell(a, 8, 4, 3, 1.5, 0)
    # outline of the post
    m = np.zeros_like(a, dtype=bool)
    m[3:5, 4:12] = True
    m[4:13, 5:11] = True
    m[12:14, 4:12] = True
    o = np.zeros_like(m)
    o[1:, :] |= m[:-1, :]
    o[:-1, :] |= m[1:, :]
    o[:, 1:] |= m[:, :-1]
    o[:, :-1] |= m[:, 1:]
    a[o & ~m] = 3
    return a


def tide_crate():
    a = F(1)
    frame(a, 3)
    rect(a, 1, 1, 14, 14, 1)
    a[1, 1:15] = 0
    a[1:15, 1] = 0
    a[14, 1:15] = 2
    a[1:15, 14] = 2
    for i in range(2, 14):
        px(a, [(i, i), (15 - i, i)], 2)
    frame(a, 3)
    rect(a, 2, 2, 12, 1, 3)
    rect(a, 2, 13, 12, 1, 3)
    a[3:13, 2] = 3
    a[3:13, 13] = 3
    px(a, [(2, 2), (13, 2), (2, 13), (13, 13)], 0)
    return a


# ------------------------------------------------------------------ SPARK
def spark_floor():
    a = F(1)
    for by in (0, 8):
        for bx in (0, 8):
            a[by, bx:bx + 7] = 0
            a[by + 1:by + 7, bx] = 0
            a[by + 7, bx:bx + 8] = 2
            a[by:by + 8, bx + 7] = 2
            px(a, [(bx + 3, by + 3)], 0)
            px(a, [(bx + 4, by + 4)], 3)
    return a


def spark_circuit():
    a = F(2)
    frame(a, 3)
    a[1, 1:15] = 2
    poly(a, [(0, 4), (5, 4), (5, 11), (10, 11), (10, 7), (15, 7)], 1)
    poly(a, [(0, 12), (3, 12)], 1)
    poly(a, [(7, 0), (7, 3)], 1)
    poly(a, [(12, 15), (12, 12)], 1)
    for x, y in ((5, 4), (5, 11), (10, 7), (10, 11)):
        rect(a, x - 1, y - 1, 3, 3, 0)
        px(a, [(x, y)], 1)
    px(a, [(3, 13), (13, 3), (8, 9)], 3)
    return a


def spark_cable():
    a = spark_floor()
    a[5, :] = 3
    a[10, :] = 3
    a[6:10, :] = 2
    a[6, :] = 0
    a[9, :] = 3
    for x in range(1, 16, 4):
        a[7, x] = 3
        a[8, x:x + 2] = 3
    return a


def spark_wall():
    a = F(2)
    a[0, :] = 3
    for y in (2, 9):
        a[y, :] = 3
        a[y + 1, :] = 0
        a[y + 2, :] = 1
        a[y + 3, :] = 1
        a[y + 4, :] = 3
    for x in (3, 12):
        for y in (2, 9):
            a[y:y + 5, x] = 3
    a[14, :] = 3
    for x in range(TS):
        a[15, x] = 1 if (x // 2) % 2 == 0 else 3
    px(a, [(7, 7), (8, 14)], 0)
    a[7:9, 6:10] = 3
    px(a, [(7, 7), (8, 8)], 1)
    return a


def spark_generator():
    a = F(2)
    frame(a, 3)
    rect(a, 2, 2, 12, 12, 2)
    a[2, 2:14] = 3
    a[2, 2:5] = 0
    rect(a, 4, 3, 8, 6, 3)
    rect(a, 5, 4, 6, 4, 1)
    # lightning bolt in the window
    poly(a, [(9, 4), (7, 6), (9, 6), (7, 8)], 3)
    px(a, [(8, 5)], 0)
    for y in (10, 12):
        a[y, 4:12] = 3
    a[11, 4:12] = 2
    px(a, [(3, 3), (12, 3), (3, 12), (12, 12)], 0)
    for x in range(1, 15):
        a[14, x] = 1 if (x // 2) % 2 == 0 else 3
    return a


def spark_coil():
    a = F(2)
    a[12:16, :] = 3
    a[12, 4:12] = 1
    rect(a, 5, 9, 6, 4, 3)
    for i, y in enumerate((9, 11)):
        a[y, 5:11] = 1
    ell(a, 8, 5, 3.2, 3.0, 3)
    ell(a, 8, 5, 2.4, 2.2, 0)
    px(a, [(8, 5), (7, 4)], 1)
    vline(a, 8, 8, 9, 1)
    px(a, [(2, 2), (13, 3), (3, 8), (12, 8), (1, 5), (14, 6)], 1)
    px(a, [(2, 3), (13, 2), (4, 7), (11, 9)], 0)
    return a


def spark_barrier():
    a = F(3)
    for x in (3, 8, 12):
        a[:, x] = 1
        a[:, x - 1] = 2
    a[:, 8] = 0
    a[0:2, :] = 2
    a[14:16, :] = 2
    for x in range(1, 16, 3):
        a[0, x] = 0
        a[15, x] = 0
    a[2, :] = 3
    a[13, :] = 3
    px(a, [(5, 6), (6, 7), (5, 8), (10, 4), (11, 5), (10, 6), (6, 11), (10, 10)], 1)
    return a


# ------------------------------------------------------------------ STONE
def stone_floor():
    a = F(1)
    poly(a, [(0, 5), (6, 5), (8, 7), (15, 7)], 2)
    poly(a, [(6, 5), (6, 0)], 2)
    poly(a, [(11, 7), (11, 11), (15, 12)], 2)
    poly(a, [(0, 12), (4, 12), (5, 15)], 2)
    for pts in (((1, 0), (5, 0)), ((7, 0), (10, 0))):
        pass
    px(a, [(1, 1), (2, 1), (8, 1), (9, 1), (1, 6), (2, 6), (9, 8), (10, 8), (12, 8), (1, 13), (2, 13), (6, 13)], 0)
    px(a, [(3, 3), (13, 4), (7, 10), (13, 14)], 3)
    return a


def stone_wall():
    a = F(1)
    for k in range(3):
        y0 = 5 * k
        a[y0, :] = 3
        a[y0 + 1, :] = 0
        a[y0 + 4, :] = 2
        jx = 4 + 8 * (k % 2)
        for x in (jx, (jx + 8) % 16):
            a[y0 + 1:y0 + 5, x] = 3
            a[y0 + 1:y0 + 5, (x + 1) % 16] = 0 if False else a[y0 + 1:y0 + 5, (x + 1) % 16]
    a[15, :] = 3
    px(a, [(8, 3), (2, 8), (12, 13), (14, 8)], 2)
    return a


def stone_ore():
    a = F(1)
    poly(a, [(0, 3), (4, 5), (3, 9), (7, 12), (6, 15)], 3)
    poly(a, [(15, 2), (11, 4), (13, 8)], 3)
    px(a, [(1, 1), (2, 1), (9, 1), (14, 11), (13, 11), (9, 14)], 0)
    for x, y in ((9, 6), (3, 11), (12, 12)):
        px(a, [(x, y - 1), (x - 1, y), (x + 1, y), (x, y + 1)], 2)
        px(a, [(x, y)], 2)
        px(a, [(x, y - 1)], 0)
        px(a, [(x - 1, y + 1), (x + 1, y + 1)], 3)
    return a


def stone_rubble():
    a = stone_floor()
    for cx, cy, rx, ry in ((5, 10, 4.2, 3.2), (11, 7, 3.4, 2.8), (10, 12, 3.0, 2.2)):
        ell(a, cx, cy, rx, ry, 2)
    mask = a == 2
    for cx, cy, rx, ry in ((5, 10, 4.2, 3.2), (11, 7, 3.4, 2.8), (10, 12, 3.0, 2.2)):
        px(a, [(int(cx - 1), int(cy - 2)), (int(cx), int(cy - 2)), (int(cx - 2), int(cy - 1))], 0)
        px(a, [(int(cx + 1), int(cy + 2)), (int(cx + 2), int(cy + 1))], 3)
    return a


def rail_h():
    a = stone_floor()
    for x in (2, 8, 14):
        rect(a, x - 1, 3, 2, 10, 2)
        a[3:13, x] = 3
    for y in (4, 10):
        a[y, :] = 0
        a[y + 1, :] = 3
    return a


def rail_v():
    return np.rot90(rail_h()).copy()


def mine_lamp():
    a = F(2)
    a[0, :] = 3
    px(a, [(2, 4), (3, 4), (12, 9), (13, 9), (5, 13)], 3)
    # hanging post and hook
    vline(a, 8, 0, 3, 3)
    hline(a, 6, 10, 3, 3)
    # lantern body
    rect(a, 5, 4, 6, 8, 3)
    rect(a, 6, 5, 4, 6, 1)
    rect(a, 7, 6, 2, 3, 0)
    a[4, 6:10] = 3
    a[11, 5:11] = 3
    # warm glow, dithered
    checker(a, 3, 3, 1, 10, 1, 1)
    checker(a, 12, 3, 1, 10, 1, 0)
    return a


def stone_cracked():
    a = stone_wall()
    poly(a, [(7, 0), (6, 4), (9, 7), (7, 11), (8, 15)], 3)
    px(a, [(5, 4), (10, 7), (6, 11)], 3)
    frame(a, 3)
    a[1, 1:15] = 0
    a[1:15, 1] = 0
    return a


# ------------------------------------------------------------------ MIST
def mist_floor():
    a = F(1)
    a[0, :] = 2
    a[8, :] = 2
    a[0:8, 4] = 2
    a[8:16, 12] = 2
    a[1, 5:15] = 0
    a[1:8, 5] = 0
    a[9, 0:11] = 0
    a[9:16, 0] = 0
    a[9, 13:16] = 0
    a[1, 0:3] = 0
    px(a, [(7, 4), (8, 4), (9, 4), (2, 12), (3, 12), (4, 12)], 0)
    px(a, [(10, 12), (9, 5)], 2)
    return a


def mist_floor_deep():
    a = F(0)
    for y in range(TS):
        for x in range(TS):
            v = (x * 3 + y * 5 + (x * y) // 3) % 11
            if v in (0, 1, 2):
                a[y, x] = 1
    checker(a, 0, 5, 16, 2, 1, 0)
    checker(a, 3, 11, 12, 3, 1, 1)
    px(a, [(4, 2), (11, 9), (7, 14)], 2)
    return a


def mist_path():
    a = F(1)
    for y in range(TS):
        for x in range(TS):
            if (x + y) % 2 == 0 and (x // 3 + y // 3) % 3 == 0:
                a[y, x] = 0
    for x, y in ((1, 2), (9, 1), (5, 8), (11, 10), (2, 12)):
        ell(a, x + 2, y + 1, 2.4, 1.6, 2)
        px(a, [(x + 1, y), (x + 2, y), (x + 1, y + 1)], 0)
        px(a, [(x + 3, y + 2), (x + 2, y + 2)], 3)
    return a


def mist_wall():
    a = F(2)
    a[0, :] = 3
    a[1, :] = 0
    a[2, :] = 1
    a[3, :] = 3
    for x0 in (1, 9):
        rect(a, x0, 5, 6, 10, 3)
        rect(a, x0 + 1, 6, 4, 9, 1)
        a[6:15, x0 + 1] = 0
        a[6, x0 + 1:x0 + 5] = 0
        a[6:15, x0 + 4] = 2
        checker(a, x0 + 1, 12, 4, 3, 0, 1)
    a[15, :] = 3
    return a


def mist_plant():
    a = F(1)
    checker(a, 0, 13, 16, 3, 0, 0)
    vline(a, 5, 7, 14, 2)
    vline(a, 10, 5, 14, 2)
    poly(a, [(5, 11), (3, 9)], 2)
    poly(a, [(10, 10), (12, 8)], 2)
    for cx, cy in ((5, 6), (10, 4), (2, 8), (13, 7)):
        ell(a, cx, cy, 1.8, 1.8, 0)
        px(a, [(cx, cy)], 1)
        halo(a[max(0, cy - 3):cy + 3, max(0, cx - 3):cx + 4], 1, 1) if False else None
    m = a == 0
    # outline the bulbs only
    o = np.zeros_like(m)
    o[1:, :] |= m[:-1, :]
    o[:-1, :] |= m[1:, :]
    o[:, 1:] |= m[:, :-1]
    o[:, :-1] |= m[:, 1:]
    chk = (a == 1) & o
    a[chk] = 3
    # restore dithered floor outside the outline
    return a


def mist_barrier():
    a = F(1)
    for y in range(TS):
        for x in range(TS):
            d = abs(x - 7.5) + abs(y - 7.5)
            ring = int(d // 2.2) % 3
            a[y, x] = (0, 1, 2)[ring]
    # fade rings with dither at the boundaries
    for y in range(TS):
        for x in range(TS):
            d = abs(x - 7.5) + abs(y - 7.5)
            frac = (d / 2.2) - int(d / 2.2)
            if frac > 0.75 and (x + y) % 2 == 0:
                a[y, x] = (1, 2, 0)[int(d // 2.2) % 3]
    frame(a, 3)
    return a


# ------------------------------------------------------------------ EMBER
def ember_floor():
    a = F(1)
    for by in (0, 8):
        off = 4 if by else 0
        a[by + 7, :] = 2
        for bx in (0, 8):
            x = (bx + off + 7) % 16
            a[by:by + 7, x] = 2
    px(a, [(1, 1), (2, 1), (9, 2), (5, 9), (13, 10), (14, 10)], 0)
    px(a, [(4, 4), (11, 5), (7, 12), (2, 13)], 3)
    return a


def ember_floor_hot():
    # same slabs as gym_floor_ember, remapped into the hot palette (1 = glow orange there)
    a = ember_floor()
    a = np.where(a == 1, 2, np.where(a == 2, 3, np.where(a == 0, 2, a))).astype(np.int8)
    poly(a, [(0, 6), (4, 6), (6, 9), (11, 9), (13, 12), (15, 12)], 1)
    poly(a, [(8, 0), (8, 3), (10, 5)], 1)
    px(a, [(6, 9), (7, 9), (11, 9)], 0)
    px(a, [(4, 6)], 0)
    return a


def ember_wall():
    a = F(2)
    for x in range(0, TS, 5):
        a[:, x] = 3
    for x in range(1, TS, 5):
        a[:13, x] = 2
    for x in range(2, TS, 5):
        a[:12, x] = 2
    px(a, [(1, 1), (6, 3), (11, 2), (2, 6), (7, 8), (12, 7), (1, 10)], 3)
    a[13, :] = 3
    a[14, :] = 1
    a[15, :] = 1
    for x in (2, 7, 12):
        a[14:16, x] = 0
        a[13, x] = 1
    return a


def lava_frame(f):
    a = F(2)
    s = 4 * f
    # bright flowing bands drifting 4 px per frame (loops in 4 frames)
    for x0, y, w in ((0, 3, 7), (9, 8, 6), (3, 13, 8)):
        for i in range(w):
            a[y, (x0 + i + s) % TS] = 1
    for x0, y in ((2, 4), (11, 9), (5, 14)):
        a[y, (x0 + s) % TS] = 0
        a[y, (x0 + 1 + s) % TS] = 0
    # cooling crust blobs
    for x0, y in ((12, 1), (4, 6), (13, 11)):
        for dx, dy in ((0, 0), (1, 0), (2, 0), (0, 1), (1, 1)):
            a[(y + dy) % TS, (x0 + dx + s) % TS] = 3
    px(a, [((8 + s) % TS, 6), ((1 + s) % TS, 10)], 3)
    return a


def ember_crust():
    a = F(1)
    for x, y in ((0, 5), (13, 2), (7, 14)):
        px(a, [(x, y), (x + 1, y)], 0)
    rect(a, 2, 2, 12, 12, 3)
    rect(a, 3, 3, 10, 10, 2)
    px(a, [(2, 2), (13, 2), (2, 13), (13, 13)], 1)
    poly(a, [(5, 5), (7, 8), (6, 11)], 3)
    poly(a, [(9, 4), (10, 7)], 3)
    px(a, [(10, 10), (11, 10)], 3)
    return a


def ember_brazier():
    a = F(2)
    px(a, [(1, 3), (13, 5), (3, 12), (11, 13), (7, 1)], 3)
    # flame
    ell(a, 8, 5, 3.0, 4.2, 1)
    ell(a, 8, 6, 1.8, 2.8, 0)
    px(a, [(8, 0), (8, 1)], 1)
    # bowl
    rect(a, 4, 9, 8, 2, 3)
    rect(a, 5, 11, 6, 3, 3)
    a[10, 5:11] = 1
    a[11, 6:10] = 1
    rect(a, 6, 14, 4, 1, 3)
    return a


def ember_obsidian():
    a = F(3)
    rect(a, 1, 1, 14, 14, 2)
    a[1, 1:15] = 1
    a[1:15, 1] = 1
    poly(a, [(2, 13), (8, 2)], 1)
    poly(a, [(5, 14), (11, 4)], 1)
    px(a, [(9, 5), (10, 5), (7, 9)], 0)
    px(a, [(13, 2), (3, 3)], 0)
    return a


# ------------------------------------------------------------------ FROST
def frost_floor():
    a = F(1)
    a[7, :] = 2
    a[15, :] = 2
    a[:, 7] = 2
    a[:, 15] = 2
    for bx, by in ((0, 0), (8, 0), (0, 8), (8, 8)):
        poly(a, [(bx + 2, by + 5), (bx + 4, by + 3)], 0)
        px(a, [(bx + 1, by + 1)], 0)
        px(a, [(bx + 5, by + 5)], 3)
    return a


def frost_cracked():
    a = frost_floor()
    poly(a, [(1, 2), (5, 5), (7, 9), (12, 11), (15, 15)], 3)
    poly(a, [(5, 5), (4, 10), (2, 13)], 3)
    poly(a, [(12, 11), (14, 7)], 3)
    return a


def frost_snow():
    a = F(0)
    for cx, cy, rx, ry in ((4, 5, 5.0, 2.4), (12, 10, 5.5, 2.8), (3, 13, 3.5, 1.8)):
        ell(a, cx, cy + 1, rx, ry, 2)
        ell(a, cx, cy, rx, ry, 1)
        ell(a, cx - 1, cy - 1, rx - 2.2, max(1.0, ry - 1.2), 0)
    px(a, [(1, 0), (9, 1), (14, 2), (7, 7), (15, 14)], 1)
    px(a, [(5, 10), (13, 5), (10, 14)], 3)
    return a


def frost_wall():
    a = F(1)
    for k in range(3):
        y0 = 5 * k
        a[y0, :] = 3
        a[y0 + 1, :] = 0
        a[y0 + 4, :] = 2
        jx = 3 + 8 * (k % 2)
        for x in (jx, (jx + 8) % 16):
            a[y0 + 1:y0 + 5, x] = 3
        poly(a, [((jx + 2) % 16, y0 + 3), ((jx + 4) % 16, y0 + 2)], 0)
    a[15, :] = 3
    return a


def frost_pillar():
    a = F(1)
    a[13:16, :] = 2
    ell(a, 8, 14, 5.5, 1.6, 2)
    rect(a, 5, 1, 6, 13, 3)
    rect(a, 6, 2, 4, 11, 1)
    a[2:13, 6] = 0
    a[2:13, 9] = 2
    rect(a, 4, 0, 8, 2, 3)
    a[1, 5:11] = 0
    rect(a, 4, 12, 8, 2, 3)
    a[12, 5:11] = 0
    poly(a, [(7, 4), (8, 7), (7, 10)], 2)
    px(a, [(7, 3)], 0)
    return a


def frost_block():
    a = F(1)
    frame(a, 3)
    a[1, 1:15] = 0
    a[1:15, 1] = 0
    a[14, 1:15] = 2
    a[1:15, 14] = 2
    for k in (4, 8, 12):
        poly(a, [(k, 13), (k + 4, 9)], 0)
    poly(a, [(3, 6), (6, 3)], 0)
    px(a, [(9, 6), (10, 5), (11, 4)], 2)
    px(a, [(5, 9)], 2)
    return a


# ------------------------------------------------------------------ DUSK
def dusk_floor():
    a = F(1)
    a[7, :] = 3
    a[15, :] = 3
    a[:7, 9] = 3
    a[8:15, 3] = 3
    a[0, 0:9] = 0
    a[1:7, 0] = 0
    a[8, 4:16] = 0
    a[9:15, 4] = 0
    a[8, 0:3] = 0
    a[0, 10:16] = 0
    a[1:7, 10] = 0
    px(a, [(5, 4), (13, 3), (8, 12), (14, 12), (1, 11)], 2)
    px(a, [(6, 3), (12, 12)], 0)
    return a


def dusk_rune():
    a = F(2)
    frame(a, 3)
    a[1, 1:15] = 1
    a[1:15, 1] = 1
    cx = cy = 7.5
    for y in range(TS):
        for x in range(TS):
            d = math.hypot(x - cx, y - cy)
            if 4.8 <= d <= 5.8:
                ang = math.atan2(y - cy, x - cx)
                if int((ang + math.pi) / (math.pi / 8)) % 2 == 0:
                    a[y, x] = 0
            if d <= 3.0:
                a[y, x] = 0
            if math.hypot(x - 9.2, y - 6.4) <= 2.7:
                a[y, x] = 2
    px(a, [(7, 0), (8, 0), (7, 15), (8, 15), (0, 7), (0, 8), (15, 7), (15, 8)], 0)
    return a


def dusk_wall():
    a = F(1)
    for r in range(4):
        y0, off = 4 * r, 4 * (r % 2)
        for x in range(TS):
            k = (x - off) % 8
            if 2 <= k <= 5:
                a[y0, x] = 0
            if k in (1, 6):
                a[y0 + 1, x] = 0
            if k in (0, 7):
                a[y0 + 2, x] = 2
            a[y0 + 3, x] = 2 if k not in (0, 7) else 3
    a[15, :] = 3
    px(a, [(3, 1), (11, 5), (7, 9), (15, 13)], 3)
    return a


def dusk_pillar():
    a = F(2)
    px(a, [(1, 4), (14, 9), (2, 13), (13, 2)], 3)
    a[14:16, :] = 3
    rect(a, 3, 12, 10, 3, 3)
    rect(a, 4, 12, 8, 2, 1)
    rect(a, 3, 0, 10, 3, 3)
    rect(a, 4, 0, 8, 2, 1)
    a[0, 4:12] = 0
    rect(a, 5, 3, 6, 9, 3)
    rect(a, 6, 3, 4, 9, 1)
    a[3:12, 6] = 0
    a[3:12, 9] = 2
    for y in (5, 8):
        a[y, 6:10] = 2
    poly(a, [(7, 4), (8, 6), (7, 8), (8, 10)], 3) if False else None
    px(a, [(5, 12), (10, 12)], 3)
    return a


def dusk_brazier():
    a = F(2)
    px(a, [(2, 3), (13, 6), (3, 11), (12, 12)], 3)
    ell(a, 8, 5, 2.8, 4.0, 1)
    ell(a, 8, 6, 1.6, 2.6, 0)
    px(a, [(8, 0), (8, 1)], 1)
    rect(a, 4, 9, 8, 2, 3)
    a[9, 5:11] = 1
    rect(a, 6, 11, 4, 3, 3)
    a[11, 7:9] = 1
    rect(a, 4, 14, 8, 1, 3)
    return a


def dusk_seal():
    a = F(2)
    frame(a, 3)
    a[1, 1:15] = 1
    a[1:15, 1] = 1
    for x in (4, 11):
        a[2:14, x] = 3
        a[2:14, x + 1] = 1
    ell(a, 7.5, 7.5, 3.4, 3.4, 0)
    ell(a, 9.0, 6.6, 2.6, 2.6, 2)
    rect(a, 2, 2, 2, 1, 0)
    rect(a, 12, 13, 2, 1, 0)
    return a


# ------------------------------------------------------------------ catalogue
# (theme, [(tile name, palette, builder)]); order = index order in the tileset.
def _statue_pal(fig, idx):
    return lambda: statue(fig(), idx) if callable(fig) else statue(fig, idx)


THEMES = {
    "sprout": [
        ("gym_floor_sprout", "gym_sprout", sprout_floor),
        ("gym_floor_sprout_stones", "gym_sprout_stones", sprout_stones),
        ("gym_wall_hedge", "gym_sprout_hedge", sprout_hedge),
        ("puzzle_wall_hedge", "gym_sprout_hedge", sprout_hedge_block),
        ("bush_blossom", "gym_sprout_bloom", sprout_blossom),
        ("plant_sprout", "gym_sprout_pot", sprout_plant),
        ("statue_sprout", "gym_sprout_statue", lambda: statue(fig_sprout(), (0, 1, 2, 3))),
    ],
    "tide": [
        ("gym_floor_tide", "gym_tide", tide_floor),
        ("gym_wall_tide", "gym_tide_wall", tide_wall),
        ("dock_plank", "gym_tide_dock", dock_plank),
        ("dock_plank_v", "gym_tide_dock", dock_plank_v),
        ("float_platform", "gym_tide_float", float_platform),
        ("pillar_mooring", "gym_tide_dock", pillar_mooring),
        ("puzzle_wall_crate", "gym_tide_dock", tide_crate),
        ("statue_tide", "gym_tide_statue", lambda: statue(fig_drop(), (0, 1, 2, 3))),
    ],
    "spark": [
        ("gym_floor_spark", "gym_spark", spark_floor),
        ("circuit_panel", "gym_spark_live", spark_circuit),
        ("circuit_cable", "gym_spark", spark_cable),
        ("gym_wall_spark", "gym_spark_hazard", spark_wall),
        ("machine_generator", "gym_spark_hazard", spark_generator),
        ("machine_coil", "gym_spark_live", spark_coil),
        ("puzzle_wall_spark", "gym_spark_live", spark_barrier),
    ],
    "stone": [
        ("gym_floor_stone", "gym_stone", stone_floor),
        ("gym_wall_stone", "gym_stone_wall", stone_wall),
        ("rock_ore", "gym_stone_ore", stone_ore),
        ("rock_rubble", "gym_stone", stone_rubble),
        ("rail_h", "gym_stone", rail_h),
        ("rail_v", "gym_stone", rail_v),
        ("lamp_mine", "gym_stone_lamp", mine_lamp),
        ("puzzle_wall_stone", "gym_stone_wall", stone_cracked),
    ],
    "mist": [
        ("gym_floor_mist", "gym_mist", mist_floor),
        ("gym_floor_mist_deep", "gym_mist", mist_floor_deep),
        ("gym_floor_mist_path", "gym_mist", mist_path),
        ("gym_wall_mist", "gym_mist_wall", mist_wall),
        ("statue_mist", "gym_mist_statue", lambda: statue(fig_crystal(), (0, 1, 2, 3))),
        ("plant_mist", "gym_mist_glow", mist_plant),
        ("puzzle_wall_mist", "gym_mist_wall", mist_barrier),
    ],
    "ember": [
        ("gym_floor_ember", "gym_ember", ember_floor),
        ("gym_floor_ember_hot", "gym_ember_hot", ember_floor_hot),
        ("gym_wall_ember", "gym_ember_hot", ember_wall),
        ("lava", "gym_ember_lava", [lava_frame(f) for f in range(4)]),
        ("gym_floor_ember_crust", "gym_ember_crust", ember_crust),
        ("lamp_brazier", "gym_ember_hot", ember_brazier),
        ("puzzle_wall_ember", "gym_ember_hot", ember_obsidian),
    ],
    "frost": [
        ("gym_floor_frost", "gym_frost", frost_floor),
        ("gym_floor_frost_cracked", "gym_frost", frost_cracked),
        ("gym_floor_frost_snow", "snow", frost_snow),
        ("gym_wall_frost", "gym_frost_wall", frost_wall),
        ("pillar_ice", "gym_frost_wall", frost_pillar),
        ("statue_frost", "gym_frost_statue", lambda: statue(fig_flake(), (0, 1, 2, 3))),
        ("puzzle_wall_frost", "gym_frost_wall", frost_block),
    ],
    "dusk": [
        ("gym_floor_dusk", "gym_dusk", dusk_floor),
        ("gym_floor_dusk_rune", "gym_dusk_rune", dusk_rune),
        ("gym_wall_dusk", "gym_dusk_wall", dusk_wall),
        ("pillar_dusk", "gym_dusk_wall", dusk_pillar),
        ("statue_dragon", "gym_dusk_statue", lambda: statue(FIG_DRAGON, (2, 0, 1, 3))),
        ("lamp_dusk", "gym_dusk_rune", dusk_brazier),
        ("puzzle_wall_dusk", "gym_dusk_rune", dusk_seal),
    ],
}

GYM_TILE_NAMES = {t: [n for n, _, _ in rows] for t, rows in THEMES.items()}

# tile names that must be solid / walkable / water-free in the engine (checked by tests)
SOLID_PREFIXES = ("gym_wall_", "puzzle_wall_", "statue_", "pillar_", "lamp_", "plant_", "bush_",
                  "machine_", "rock_", "lava")


def gym_tiles():
    """[(name, palette, [frames])] for every themed tile, in index order."""
    out = []
    for theme, rows in THEMES.items():
        for name, pal, fn in rows:
            frames = fn if isinstance(fn, list) else [fn()]
            out.append((name, pal, frames))
    return out


if __name__ == "__main__":
    t = gym_tiles()
    print(len(t), "themed tiles,", sum(len(f) for _, _, f in t), "frames")
