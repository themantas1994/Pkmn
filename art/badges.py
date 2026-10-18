"""Gym badges -> assets/gfx/badges/badge_<n>.png (24x24, n = 1..8, same ints as the game's badge ids).

1 Sprout, 2 Tide, 3 Spark, 4 Stone, 5 Mist, 6 Ember, 7 Frost, 8 Dusk (world/campaign.json).
Each badge has its own silhouette, 4-colour palette [highlight, body, glyph, outline] and a
12x12 glyph ('#' = glyph colour, '+' = highlight colour). Transparent background.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixlib  # noqa: E402
from palettes import pal  # noqa: E402

N = 24
C = (N - 1) / 2.0


def _mask(fn):
    m = np.zeros((N, N), dtype=bool)
    for y in range(N):
        for x in range(N):
            m[y, x] = fn(x - C, y - C, y)
    return m


SHAPES = {
    "circle": lambda dx, dy, y: dx * dx + dy * dy <= 11.2 ** 2,
    "rsquare": lambda dx, dy, y: abs(dx) <= 10.5 and abs(dy) <= 10.5 and abs(dx) + abs(dy) <= 17,
    "octagon": lambda dx, dy, y: abs(dx) <= 11.5 and abs(dy) <= 11.5 and abs(dx) + abs(dy) <= 16.5,
    "hexagon": lambda dx, dy, y: abs(dy) <= 10.5 and abs(dx) + abs(dy) * 0.5 <= 11.5,
    "diamond": lambda dx, dy, y: abs(dx) + abs(dy) <= 12,
    "shield": lambda dx, dy, y: y <= 22 and abs(dx) <= (10.8 if y <= 11 else 10.8 - (y - 11) * 0.85) and y >= 1,
    "plus": lambda dx, dy, y: (abs(dx) <= 11.5 and abs(dy) <= 6.5) or (abs(dx) <= 6.5 and abs(dy) <= 11.5)
    or dx * dx + dy * dy <= 9.5 ** 2,
    "pentagon": lambda dx, dy, y: 1 <= y <= 22 and abs(dx) <= (min(11.5, 3.5 + y * 0.62) if y <= 13 else 11.5 - (y - 13) * 0.55),
}

GLYPHS = {
    "sprout": """
............
.###....###.
#+++#..#+++#
#++++##++++#
.#++++#+++#.
..#####+##..
.....##.....
.....##.....
.....##.....
..#######...
..#######...
............""",
    "drop": """
.....##.....
.....##.....
....#++#....
...#+++##...
..#++++++#..
..#+++++++#.
..#+++++++#.
..#+++++++#.
...#+++++#..
....#####...
............
............""",
    "bolt": """
.......###..
......###...
.....###....
....######..
......###...
.....###....
....###.....
...###......
..##........
............
............
............""",
    "rock": """
............
...####.....
..#++++##...
.#+++++++#..
.#++####++#.
.#+++#++++#.
..#++#++++#.
..#++++###..
...#####....
............
............
............""",
    "swirl": """
............
...#####....
..#+++++#...
.#++###++#..
.#++#+#++#..
.#++###+++#.
..#+++++++#.
...#######..
.#.#+++#.#..
#+#.....#+#.
.#..........
............""",
    "flame": """
.....#......
....##......
....###.....
...#+++#.#..
..#++++###..
..#+++++++#.
.#++##+++++#
.#++#+#++++#
.#+++#++++#.
..#+++++++#.
...#######..
............""",
    "snow": """
.....##.....
..#..##..#..
...#.##.#...
....####....
#.#..##..#.#
############
#.#..##..#.#
....####....
...#.##.#...
..#..##..#..
.....##.....
............""",
    "moon": """
............
....####....
...##+++#...
..##+++#....
..#++++#..+.
..#++++#.+++
..#++++#..+.
..##+++##...
...##+++#...
....#####...
............
............""",
}

# n: (name, shape, [highlight, body, glyph, outline], glyph)
BADGES = {
    1: ("sprout", "circle", ("C8F8A0", "58B038", "205020", "102810"), "sprout"),
    2: ("tide", "rsquare", ("A8E0F8", "3078D8", "103878", "081840"), "drop"),
    3: ("spark", "octagon", ("F8F098", "F0C020", "805800", "301800"), "bolt"),
    4: ("stone", "hexagon", ("E0D8C8", "A09078", "504838", "201810"), "rock"),
    5: ("mist", "diamond", ("F0E8F8", "B8A0E0", "584890", "281848"), "swirl"),
    6: ("ember", "shield", ("F8D880", "F07020", "A81810", "400808"), "flame"),
    7: ("frost", "plus", ("F8F8F8", "90D0F0", "3868B0", "102048"), "snow"),
    8: ("dusk", "pentagon", ("F8E8A0", "5040A0", "281858", "080818"), "moon"),
}


def _glyph(text):
    lines = [ln for ln in text.strip("\n").splitlines()]
    lines = [ln for ln in lines]
    assert len(lines) <= 12, len(lines)
    lines += ["." * 12] * (12 - len(lines))
    for ln in lines:
        assert len(ln) == 12, ln
    return lines


def render(n):
    _, shape, colours, glyph = BADGES[n]
    palette = pal(*colours)
    m = _mask(SHAPES[shape])
    a = np.full((N, N), -1, dtype=np.int8)
    a[m] = 1
    inner = m.copy()
    for y in range(N):
        for x in range(N):
            if m[y, x]:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    xx, yy = x + dx, y + dy
                    if not (0 <= xx < N and 0 <= yy < N) or not m[yy, xx]:
                        inner[y, x] = False
    a[m & ~inner] = 3
    # bevel highlight on the upper-left inside edge, 2 px wide
    for y in range(N):
        for x in range(N):
            if inner[y, x] and (x + y) < 20 and not inner[max(0, y - 2), x] or \
                    inner[y, x] and (x + y) < 20 and not inner[y, max(0, x - 2)]:
                a[y, x] = 0
    # glyph, centred
    for y, ln in enumerate(_glyph(GLYPHS[glyph])):
        for x, ch in enumerate(ln):
            if ch in "#+":
                a[6 + y, 6 + x] = 2 if ch == "#" else 0
    return pixlib.to_rgba(a, palette)


def build():
    out = os.path.join(pixlib.GFX, "badges")
    import hashlib
    seen = {}
    for n in BADGES:
        p = os.path.join(out, f"badge_{n}.png")
        pixlib.save_png(render(n), p)
        with open(p, "rb") as f:
            d = hashlib.md5(f.read()).hexdigest()
        assert d not in seen, f"badge {n} equals badge {seen[d]}"
        seen[d] = n


if __name__ == "__main__":
    build()
