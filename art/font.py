"""Original 8x8 (pixel8) and 6x8 (pixel6) monospace bitmap fonts.

Glyphs are hand-drawn below as 7-row grids ('#' = ink). Capitals and digits
use rows 0-5, lowercase x-height is rows 2-5 and descenders use row 6, so no
glyph is taller than 7 px. Most glyphs are 5 px wide; pixel8 centres them in
an 8 px cell, pixel6 in a 6 px cell (always leaving >=1 px spacing).
Glyphs wider than 5 px get a narrow override for pixel6 (NARROW below).

Private-use code points: U+E000 = "PK" ligature, U+E001 = "MN" ligature.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixlib  # noqa: E402

PK = ""
MN = ""

GLYPH_SRC = r"""
@A
.###.
#...#
#...#
#####
#...#
#...#
@B
####.
#...#
####.
#...#
#...#
####.
@C
.###.
#...#
#....
#....
#...#
.###.
@D
###..
#..#.
#...#
#...#
#..#.
###..
@E
#####
#....
####.
#....
#....
#####
@F
#####
#....
####.
#....
#....
#....
@G
.###.
#....
#.###
#...#
#...#
.###.
@H
#...#
#...#
#####
#...#
#...#
#...#
@I
###
.#.
.#.
.#.
.#.
###
@J
..###
...#.
...#.
...#.
#..#.
.##..
@K
#...#
#..#.
###..
#.#..
#..#.
#...#
@L
#....
#....
#....
#....
#....
#####
@M
#...#
##.##
#.#.#
#.#.#
#...#
#...#
@N
#...#
##..#
#.#.#
#..##
#...#
#...#
@O
.###.
#...#
#...#
#...#
#...#
.###.
@P
####.
#...#
#...#
####.
#....
#....
@Q
.###.
#...#
#...#
#.#.#
#..#.
.##.#
@R
####.
#...#
#...#
####.
#..#.
#...#
@S
.####
#....
.###.
....#
....#
####.
@T
#####
..#..
..#..
..#..
..#..
..#..
@U
#...#
#...#
#...#
#...#
#...#
.###.
@V
#...#
#...#
#...#
.#.#.
.#.#.
..#..
@W
#...#
#...#
#.#.#
#.#.#
##.##
#...#
@X
#...#
.#.#.
..#..
..#..
.#.#.
#...#
@Y
#...#
.#.#.
..#..
..#..
..#..
..#..
@Z
#####
...#.
..#..
.#...
#....
#####
@a
.....
.....
.####
#...#
#..##
.##.#
@b
#....
#....
####.
#...#
#...#
####.
@c
.....
.....
.####
#....
#....
.####
@d
....#
....#
.####
#...#
#...#
.####
@e
.....
.....
.###.
#####
#....
.###.
@f
..##.
.#...
####.
.#...
.#...
.#...
@g
.....
.....
.####
#...#
.####
....#
.###.
@h
#....
#....
####.
#...#
#...#
#...#
@i
.#.
...
##.
.#.
.#.
###
@j
...#
....
..##
...#
...#
#..#
.##.
@k
#....
#....
#..#.
###..
#.#..
#..#.
@l
##.
.#.
.#.
.#.
.#.
###
@m
.....
.....
##.#.
#.#.#
#.#.#
#.#.#
@n
.....
.....
####.
#...#
#...#
#...#
@o
.....
.....
.###.
#...#
#...#
.###.
@p
.....
.....
####.
#...#
####.
#....
#....
@q
.....
.....
.####
#...#
.####
....#
....#
@r
.....
.....
#.##.
##...
#....
#....
@s
.....
.....
.####
##...
..###
####.
@t
.#...
.#...
####.
.#...
.#...
..##.
@u
.....
.....
#...#
#...#
#...#
.####
@v
.....
.....
#...#
#...#
.#.#.
..#..
@w
.....
.....
#...#
#.#.#
#.#.#
.#.#.
@x
.....
.....
#...#
.#.#.
.###.
#...#
@y
.....
.....
#...#
#...#
.####
....#
.###.
@z
.....
.....
#####
..##.
.#...
#####
@0
.###.
#..##
#.#.#
#.#.#
##..#
.###.
@1
..#..
.##..
..#..
..#..
..#..
.###.
@2
.###.
#...#
...#.
..#..
.#...
#####
@3
####.
....#
.###.
....#
....#
####.
@4
...#.
..##.
.#.#.
#..#.
#####
...#.
@5
#####
#....
####.
....#
....#
####.
@6
.###.
#....
####.
#...#
#...#
.###.
@7
#####
....#
...#.
..#..
..#..
..#..
@8
.###.
#...#
.###.
#...#
#...#
.###.
@9
.###.
#...#
#...#
.####
....#
.###.
@space
.....
@!
#
#
#
#
.
#
@"
#.#
#.#
@#
.#.#.
#####
.#.#.
.#.#.
#####
.#.#.
@$
..#..
.####
#.#..
.###.
..#.#
####.
..#..
@%
##...
##..#
...#.
..#..
.#...
#..##
...##
@&
.##..
#..#.
.##..
#.#.#
#..#.
.##.#
@'
#
#
@(
..#
.#.
.#.
.#.
.#.
..#
@)
#..
.#.
.#.
.#.
.#.
#..
@*
.....
..#..
#.#.#
.###.
#.#.#
..#..
@+
.....
..#..
..#..
#####
..#..
..#..
@,
..
..
..
..
##
.#
#.
@-
....
....
....
####
@.
..
..
..
..
##
##
@/
....#
...#.
..#..
..#..
.#...
#....
@:
..
##
##
..
##
##
@;
..
##
##
..
##
.#
#.
@<
...#
..#.
.#..
#...
.#..
..#.
...#
@=
.....
.....
#####
.....
#####
@>
#...
.#..
..#.
...#
..#.
.#..
#...
@?
.###.
#...#
...#.
..#..
.....
..#..
@@
.###.
#...#
#.###
#.#.#
#.###
#....
.###.
@[
##
#.
#.
#.
#.
##
@\
#....
.#...
..#..
..#..
...#.
....#
@]
##
.#
.#
.#
.#
##
@^
..#..
.#.#.
#...#
@_
.....
.....
.....
.....
.....
.....
#####
@`
#.
.#
@{
..#
.#.
.#.
#..
.#.
.#.
..#
@|
#
#
#
#
#
#
#
@}
#..
.#.
.#.
..#
.#.
.#.
#..
@~
.....
.....
.#..#
#.##.
@é
...#.
..#..
.###.
#####
#....
.###.
@É
..##.
#####
#....
####.
#....
#####
@♂
..###
...##
.##.#
#..#.
#..#.
.##..
@♀
.###.
#...#
#...#
.###.
..#..
.###.
..#..
@…
.....
.....
.....
.....
.....
#.#.#
@’
.#
.#
#.
@“
.#.#
#.#.
#.#.
@”
.#.#
.#.#
#.#.
@×
.....
#...#
.#.#.
..#..
.#.#.
#...#
@▶
#...
##..
###.
####
###.
##..
#...
@▼
.......
#######
.#####.
..###..
...#...
@★
...#...
..###..
#######
.#####.
..###..
.##.##.
.#...#.
@♪
..#..
..##.
..#.#
..#..
###..
###..
@PK
.......
###.#.#
#.#.#.#
###.##.
#...#.#
#...#.#
@MN
.......
#.#.##.
###.#.#
###.#.#
#.#.#.#
#.#.#.#
@◀
...#
..##
.###
####
.###
..##
...#
@▲
...#...
..###..
.#####.
#######
.......
@●
.....
.###.
#####
#####
#####
.###.
.....
@◆
..#..
.###.
#####
.###.
..#..
@♥
.....
##.##
#####
#####
.###.
..#..
@✓
.....
....#
...##
#.##.
###..
.#...
@¥
#...#
.#.#.
#####
..#..
#####
..#..
"""

# narrow (<=5 px, or 6 for ligatures) versions for pixel6
NARROW_SRC = r"""
@▼
.....
#####
.###.
..#..
@★
..#..
.###.
#####
.###.
.#.#.
@PK
......
##.#.#
##.#.#
#..##.
#..#.#
#..#.#
@MN
......
#.##..
####.#
#.##.#
#.#.##
#.#..#
@▲
..#..
.###.
#####
.....
"""

NAMES = {"space": " ", "PK": PK, "MN": MN}

EXTRAS = "éÉ♂♀…’“”×▶▼★♪" + PK + MN + "◀▲●◆♥✓¥"
CHARS = "".join(chr(c) for c in range(32, 127)) + EXTRAS


def _parse(src):
    glyphs = {}
    cur, rows = None, []
    for line in src.strip("\n").splitlines():
        line = line.rstrip()
        if line.startswith("@") and len(line) > 1:
            if cur is not None:
                glyphs[cur] = rows
            name = line[1:]
            cur, rows = NAMES.get(name, name), []
        elif line:
            rows.append(line)
    glyphs[cur] = rows
    out = {}
    for ch, rows in glyphs.items():
        w = max(len(r) for r in rows)
        a = np.zeros((7, w), dtype=bool)
        assert len(rows) <= 7, f"glyph {ch!r} too tall"
        for y, r in enumerate(rows):
            assert len(r) == w, f"glyph {ch!r} row {y} width"
            for x, c in enumerate(r):
                a[y, x] = c == "#"
        out[ch] = a
    return out


GLYPHS = _parse(GLYPH_SRC)
NARROW = _parse(NARROW_SRC)
for _c in CHARS:
    assert _c in GLYPHS, f"missing glyph {_c!r}"


def glyph(ch, narrow=False):
    if narrow and ch in NARROW:
        return NARROW[ch]
    return GLYPHS.get(ch, GLYPHS["?"])


# pixel8 widens most 5-px glyphs to 6 px by doubling the centre column (the
# GSC font is chunky); glyphs whose centre column is a vertical stem stay 5 px.
NO_WIDEN = set("TY*+17Jftm/\\♪Iil")


def _stem_score(g, c):
    s = 0
    for y in range(g.shape[0]):
        if g[y, c] and not g[y, c - 1] and not g[y, c + 1]:
            for yy in (y - 1, y + 1):
                if (0 <= yy < g.shape[0] and g[yy, c]
                        and not g[yy, c - 1] and not g[yy, c + 1]):
                    s += 1
                    break
    return s


def wide_glyph(ch):
    g = glyph(ch)
    if g.shape[1] == 5 and ch not in NO_WIDEN and _stem_score(g, 2) <= 2:
        g = np.concatenate([g[:, :3], g[:, 2:]], axis=1)
    return g


def cell_mask(ch, cell_w):
    """Glyph placed in a cell of width cell_w (8 or 6), 8 px tall."""
    g = glyph(ch, narrow=True) if cell_w < 8 else wide_glyph(ch)
    w = g.shape[1]
    m = np.zeros((8, cell_w), dtype=bool)
    body = 7 if cell_w == 8 else 5
    x = (body - w + 1) // 2 if w <= body else 0
    ww = min(w, cell_w - x)
    m[0:7, x:x + ww] = g[:, :ww]
    return m


def sheet(cell_w, ink=(24, 24, 24)):
    cols = 16
    rows = (len(CHARS) + cols - 1) // cols
    rgba = np.zeros((rows * 8, cols * cell_w, 4), dtype=np.uint8)
    for i, ch in enumerate(CHARS):
        cx, cy = (i % cols) * cell_w, (i // cols) * 8
        m = cell_mask(ch, cell_w)
        sub = rgba[cy:cy + 8, cx:cx + cell_w]
        sub[m] = (*ink, 255)
    return Image.fromarray(rgba, "RGBA")


def text_mask(s, cell_w=8):
    """Boolean mask for a string rendered monospace (used by other scripts)."""
    m = np.zeros((8, cell_w * len(s)), dtype=bool)
    for i, ch in enumerate(s):
        m[:, i * cell_w:(i + 1) * cell_w] = cell_mask(ch, cell_w)
    return m


def draw_text(arr, s, x, y, c, cell_w=8):
    """Draw text into an index array (in place)."""
    m = text_mask(s, cell_w)
    h, w = m.shape
    H, W = arr.shape
    sub = arr[y:y + h, x:x + w]
    mm = m[:sub.shape[0], :sub.shape[1]]
    sub[mm] = c
    return arr


def draw_text_rgba(img, s, x, y, colour, cell_w=8):
    a = np.asarray(img).copy()
    m = text_mask(s, cell_w)
    h, w = m.shape
    sub = a[y:y + h, x:x + w]
    mm = m[:sub.shape[0], :sub.shape[1]]
    sub[mm] = (*colour, 255) if len(colour) == 3 else colour
    return Image.fromarray(a, img.mode)


def build():
    out = pixlib.ASSETS + "/fonts/"
    for name, cw in (("pixel8", 8), ("pixel6", 6)):
        pixlib.save_png(sheet(cw), out + f"{name}.png")
        pixlib.save_png(sheet(cw, ink=(255, 255, 255)), out + f"{name}_white.png")
        pixlib.save_json({
            "cellW": cw, "cellH": 8, "columns": 16, "chars": CHARS,
            "glyphHeight": 7, "baseline": 6, "lineHeight": 8,
            "ligatures": {"PK": PK, "MN": MN},
            "variants": {"dark": f"{name}.png", "white": f"{name}_white.png"},
            "notes": "Monospace; advance = cellW. Rows 0-5 caps, row 6 descenders. "
                     "White variant is meant to be tinted at draw time.",
        }, out + f"{name}.json")


if __name__ == "__main__":
    build()
