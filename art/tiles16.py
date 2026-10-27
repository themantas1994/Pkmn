"""16-colour world tiles (P5d), one section per map-type batch.

Pixels are palette slots 1..15 of a palettes.TILE16 palette (hex digits in text grids). The functions here derive
shaded versions from the 4-colour silhouettes in tiles.py, so shapes stay identical and only the shading changes:
light comes from the top left, outlines hug the silhouette, interior shadow steps through the ramp.
"""
import numpy as np

TS = 16
BAYER = ((0, 2), (3, 1))  # 2x2 ordered dither, thresholds 0..3


def _ground_mask(src):
    """True for old-index-1 pixels connected (4-neighbour) to the tile border: the open ground around a shape."""
    h, w = src.shape
    seen = np.zeros((h, w), bool)
    stack = [(y, x) for y in range(h) for x in range(w) if (y in (0, h - 1) or x in (0, w - 1)) and src[y, x] == 1]
    while stack:
        y, x = stack.pop()
        if seen[y, x]:
            continue
        seen[y, x] = True
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and src[ny, nx] == 1 and not seen[ny, nx]:
                stack.append((ny, nx))
    return seen


def route_grass(seed=0, tufts=((2, 2), (10, 5), (5, 10), (12, 12)), flowers=()):
    """Grass: base slot 6 with a faint dithered 5/7 texture, 'v' tufts in 3/4 with a 7 highlight."""
    a = np.full((TS, TS), 6, dtype=np.int8)
    for y in range(TS):
        for x in range(TS):
            n = (x * 7 + y * 13 + seed * 5) % 11
            if n == 0:
                a[y, x] = 5
            elif n == 5 and (x + y) % 2 == 0:
                a[y, x] = 7
    for x, y in tufts:
        for dx, dy, c in ((0, 0, 4), (2, 0, 4), (1, 1, 3), (1, 0, 7), (0, -1, 7)):
            a[(y + dy) % TS, (x + dx) % TS] = c
    for x, y in flowers:
        a[y % TS, x % TS] = 9
    return a


def route_tall_grass(src):
    """Tall grass: dark outline blades (1/2), ramp 3..8 lit from the upper left, bright tips."""
    a = np.zeros((TS, TS), dtype=np.int8)
    for y in range(TS):
        for x in range(TS):
            v = int(src[y, x])
            row = y % 8  # two stacked 8-row blade bands
            if v == 3:
                a[y, x] = 1 if row >= 5 else 2
            elif v == 2:
                a[y, x] = 3 + min(2, row // 3) + (1 if (x + y) % 4 == 0 else 0)
            elif v == 1:
                a[y, x] = 7 if row <= 2 else 6
            else:
                a[y, x] = 8
    return a


def route_canopy(src, trunk=True, oy=0, total_h=None):
    """Tree / bush from a 4-colour silhouette: 1 ground, 2 canopy, 3 outline, 0 trunk.

    oy / total_h place the tile inside a taller object (tree_top over tree_bottom) so the light gradient is continuous."""
    h, w = src.shape
    ground = _ground_mask(src)
    out = np.zeros((h, w), dtype=np.int8)
    for y in range(h):
        for x in range(w):
            v = int(src[y, x])
            if ground[y, x]:
                out[y, x] = 6 if (x * 7 + y * 13) % 11 else 5
            elif v == 3:
                near_ground = any(
                    0 <= y + dy < h and 0 <= x + dx < w and ground[y + dy, x + dx]
                    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                edge = y in (0, h - 1) or x in (0, w - 1)
                out[y, x] = 1 if (near_ground or edge) else 2
            elif v == 0:
                out[y, x] = (11 if x % 2 == 0 else 12) if trunk else 1
            elif v == 1:  # highlight inside the canopy
                out[y, x] = 8
            else:  # canopy: lit from the top left, dithered band edges
                t = (x + 1.3 * (y + oy)) / (w + 1.3 * (total_h or h))  # 0 top-left .. 1 bottom-right
                level = 7.4 - 4.8 * t + (BAYER[y % 2][x % 2] - 1.5) * 0.4  # 7 (lit) .. 3 (shadow)
                out[y, x] = int(min(7, max(3, round(level))))
    return out
