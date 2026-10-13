"""World tileset generator -> assets/gfx/tiles/world.png + world.json.

Every tile is a 16x16 grid of palette indices (0 lightest .. 3 darkest) paired
with a named palette from palettes.TILE. Grids are written as text below; tiles
that are pure combinations (path edges, shorelines, animation frames) are
derived in code from the base grids so they stay consistent.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixlib  # noqa: E402
from pixlib import grid  # noqa: E402
from palettes import TILE  # noqa: E402

COLUMNS = 16
TS = 16
FRAME_MS = 250


def full(c):
    return np.full((TS, TS), c, dtype=np.int8)


def dots(base, pts, c):
    a = base.copy()
    for x, y in pts:
        a[y % TS, x % TS] = c
    return a


def tuft(a, x, y, c):
    """small 'v' grass tuft"""
    for dx, dy in ((0, 0), (2, 0), (1, 1)):
        a[(y + dy) % TS, (x + dx) % TS] = c
    return a


# =========================================================================
# OUTDOOR  (palette 'outdoor': 0 cream, 1 light green, 2 green, 3 deep)
# =========================================================================
def make_grass():
    a = full(1)
    for x, y in ((2, 2), (10, 5), (5, 10), (12, 12)):
        tuft(a, x, y, 2)
    return a


def make_grass_alt():
    a = full(1)
    for x, y in ((4, 4), (11, 11)):
        tuft(a, x, y, 2)
    for x, y in ((12, 3), (2, 12), (8, 8)):
        a[y, x] = 0
    return a


TALL_GRASS = grid("""
2222222222222222
2122212222122212
2121212222121212
2111112212111112
3211123222211123
2321232222321232
2233322222233322
2222222222222222
2222221222222212
2122212122122212
2121211122121211
2111112211111112
3211123232111232
2321232223212322
2233322222333222
2222222222222222
""")

TREE = grid("""
1111133333311111
1113322222233111
1132211222222311
1321112222222231
1321122222222231
3221222222222223
3212222222222223
3222222222222223
3222222222222233
3222222222222323
1322222222223231
1332222222232331
1133332222333311
1111133003311111
1111113003111111
1111133333311111
""")

TREE_TOP = grid("""
1111113333111111
1111332222331111
1113221122223111
1132211222222311
1132112222222311
1321122222222231
1321222222222231
3221222222222223
3212222222222223
3222222222222223
3222222222222223
3222222222222233
3222222222222323
3222222222223223
1322222222232231
1322222222322231
""")

TREE_BOTTOM = grid("""
3222222222222223
3222222222222323
3222222222223223
3222222222232233
1322222222322331
1332222223223311
1133222232233111
1113333333331111
1111113003111111
1111113003111111
1111113003111111
1111133003311111
1111330000331111
1111333333331111
1111111111111111
1111111111111111
""")

BUSH = grid("""
1111111111111111
1111333333331111
1133222112223311
1322211122222231
1322111222222231
3222112222222223
3221122222222223
3222222222222223
3222222222222223
3222222222222323
3222222222223223
1322222222232231
1332222223223331
1133333333333311
1111111111111111
1111111111111111
""")

CUT_TREE = grid("""
1111111331111111
1111113223111111
1111132112311111
1111321122231111
1111321222231111
1113212222223111
1113222222223111
1132122222222311
1132222222232311
1132222222322311
1113222223223111
1111333332331111
1111113003111111
1111113003111111
1111133333311111
1111111111111111
""")

LEDGE_S = grid("""
1111111111111111
1121111111112111
1112111111121111
1111111111111111
1111111211111111
1111112121111111
1111111111111111
1111111111111111
0000000000000000
2222222222222222
2212222222122222
2222222222222222
3222222332222223
1333333113333331
1111111111111111
1111111111111111
""")


def make_ledge_e():
    """east-facing ledge: the drop is on the right side of the tile"""
    a = rot_ledge(LEDGE_S, 1)
    return a


def make_ledge_w():
    return rot_ledge(LEDGE_S, -1)


def rot_ledge(src, k):
    # rotate the lip only; keep the grass tufts upright by re-drawing grass
    lip = np.rot90(src, k).copy()
    a = make_grass()
    m = np.zeros((TS, TS), bool)
    if k == 1:   # counter-clockwise: bottom rows -> right columns
        m[:, 8:] = True
    else:
        m[:, :8] = True
    a[m] = lip[m]
    return a


# =========================================================================
# ROCKS (palette 'rocky': 0 cream, 1 grass, 2 grey, 3 dark)
# =========================================================================
ROCK = grid("""
1111111111111111
1111111111111111
1111111111111111
1111111111111111
1111111111111111
1111113333111111
1111330002331111
1113000022223111
1113002222223111
1130022222222311
1130222222223311
1132222222232311
1113222233322311
1111333333333111
1111111111111111
1111111111111111
""")

BOULDER = grid("""
1111133333311111
1113300002233111
1130000222222311
1300022222222231
1300222222222231
3002222222232223
3002222222232223
3022222222322223
3022222223222223
3222222222222233
3222222222222323
1322222222223231
1322322222232231
1132233222322311
1113332223333111
1111113333111111
""")

# =========================================================================
# FENCE / SIGN (palette 'fence': 0 cream, 1 grass, 2 brown, 3 dark)
# =========================================================================
FENCE_H = grid("""
1111111111111111
1111111111111111
1111111111111111
1133111111111331
1302311111113023
3333333333333333
0000000000000000
2222222222222222
3333333333333333
1302311111113023
3333333333333333
0000000000000000
2222222222222222
3333333333333333
1322311111113223
1133111111111331
""")

FENCE_V = grid("""
1111113333111111
1111130022311111
1111130022311111
1111130022311111
1111133333311111
1111130022311111
1111130022311111
1111130022311111
1111130022311111
1111130022311111
1111130022311111
1111133333311111
1111130022311111
1111130022311111
1111130022311111
1111130022311111
""")

SIGN = grid("""
1111111111111111
1133333333333311
1300000000000231
1302222222222231
1300000000000231
1302222222222231
1300000000000231
1322222222222231
1133333333333311
1111113003111111
1111113023111111
1111113023111111
1111113023111111
1111133223311111
1111133333311111
1111111111111111
""")

# =========================================================================
# MOUNTAIN (palette 'mountain': 0 cream, 1 tan, 2 brown, 3 dark)
# =========================================================================
MOUNTAIN_WALL = grid("""
1111011111110111
1111211111112111
2222222222222222
3333333333333333
0111111101111111
1111111121111111
2222222222222222
3333333333333333
1110111111101111
1112111111121111
2222222222222222
3333333333333333
0111111011111111
1111111211111111
2222222222222222
3333333333333333
""")

MOUNTAIN_TOP = grid("""
0000000000000000
0000000100000000
0010000000000010
0000000000000000
0000000000100000
0000010000000000
0000000000000000
0100000000000100
0000000000000000
0000000100000000
0000000000000000
0001000000001000
0000000000000000
0000000001000000
0000100000000000
0000000000000000
""")

CAVE_ENTRANCE = grid("""
1111011111110111
1111233333312111
2222333333332222
3333333333333333
0113333333333111
1113333333333111
2223333333333222
3333333333333333
1103333333333111
1113333333333111
2223333333333222
3333333333333333
0113333333333111
1113333333333111
2223333333333222
3333333333333333
""")

STAIRS_UP = grid("""
3333333333333333
3000000000000003
3111111111111113
3222222222222223
3333333333333333
3000000000000003
3111111111111113
3222222222222223
3333333333333333
3000000000000003
3111111111111113
3222222222222223
3333333333333333
3000000000000003
3111111111111113
3333333333333333
""")

STAIRS_DOWN = grid("""
3333333333333333
3222222222222223
3111111111111113
3000000000000003
3333333333333333
3222222222222223
3111111111111113
3000000000000003
3333333333333333
3222222222222223
3111111111111113
3000000000000003
3333333333333333
3222222222222223
3111111111111113
3333333333333333
""")

# =========================================================================
# BRIDGE (palette 'bridge': 0 light, 1 tan, 2 brown, 3 dark)
# =========================================================================
BRIDGE_H = grid("""
3333333333333333
0000000000000000
1111111111111111
3333333333333333
1101110111011101
1121112111211121
1121112111211121
1121112111211121
1121112111211121
1121112111211121
1121112111211121
1121112111211121
2222222222222222
3333333333333333
1111111111111111
3333333333333333
""")

# =========================================================================
# INDOOR & BUILDINGS & OTHERS are mostly built procedurally below
# =========================================================================


def path_base():
    a = full(0)
    for x, y in ((3, 2), (11, 4), (6, 9), (13, 12), (1, 13), (8, 14)):
        a[y, x] = 1
        a[y, (x + 1) % TS] = 1
    for x, y in ((14, 1), (9, 7), (2, 6), (4, 15)):
        a[y, x] = 1
    return a


EDGE_PROFILE = [3, 3, 4, 4, 3, 3, 3, 2, 3, 3, 4, 3, 3, 3, 2, 3]


def _side_mask(side, profile=EDGE_PROFILE):
    """True where 'outside' (grass/land) along one side of a tile."""
    Y, X = np.mgrid[0:TS, 0:TS]
    p = np.array(profile)
    if side == "n":
        return Y < p[X]
    if side == "s":
        return (TS - 1 - Y) < p[::-1][X]
    if side == "w":
        return X < p[::-1][Y]
    if side == "e":
        return (TS - 1 - X) < p[Y]
    raise ValueError(side)


def _neighbours(mask):
    g = np.zeros_like(mask)
    g[1:, :] |= mask[:-1, :]
    g[:-1, :] |= mask[1:, :]
    g[:, 1:] |= mask[:, :-1]
    g[:, :-1] |= mask[:, 1:]
    return g


def path_edge(sides):
    """path palette: 0 cream, 1 tan, 2 grass, 3 green."""
    a = path_base()
    m = np.zeros((TS, TS), dtype=bool)
    for s in sides:
        m |= _side_mask(s)
    a[m] = 2
    border = _neighbours(m) & ~m
    a[border] = 3
    # a few grass tufts on the grass part
    g = make_grass()
    a[m & (g == 2)] = 3
    return a


def sand_tile():
    a = full(1)
    for x, y in ((2, 3), (9, 1), (13, 7), (5, 10), (11, 13), (1, 14)):
        a[y, x] = 0
    for x, y in ((6, 5), (14, 11), (3, 8), (9, 15)):
        a[y, x] = 2
    return a


# =========================================================================
# WATER (palette 'water': 0 land/grass, 1 foam, 2 water, 3 deep edge)
# =========================================================================
WAVE = [(1, 2), (2, 1), (3, 1), (4, 2),          # wave A (row band 1-2)
        (9, 9), (10, 8), (11, 8), (12, 9)]       # wave B (row band 8-9)
WAVE2 = [(9, 4), (10, 3), (11, 3), (12, 4), (1, 12), (2, 11), (3, 11), (4, 12)]


def water_frame(f, base=2, wave=1):
    a = full(base)
    # wave bands drift in opposite directions; 4 px per frame loops in 4 frames
    for x, y in WAVE:
        a[y, (x + 4 * f) % TS] = wave
    for x, y in WAVE2:
        a[y, (x - 4 * f) % TS] = wave
    return a


def shore(frame, land_mask, land=0, edge=3, foam=1):
    a = frame.copy()
    a[land_mask] = land
    e = _neighbours(land_mask) & ~land_mask
    a[e] = edge
    f2 = _neighbours(land_mask | e) & ~(land_mask | e)
    # dashed foam line just inside the water
    Y, X = np.mgrid[0:TS, 0:TS]
    a[f2 & (((X + Y) % 3) != 0)] = foam
    return a


SHORE_B = 4  # land width on a shoreline tile


def shore_mask(kind):
    Y, X = np.mgrid[0:TS, 0:TS]
    Xc, Yc = X + 0.5, Y + 0.5
    b, r = SHORE_B, 4
    if kind in ("n", "s", "e", "w"):
        return {"n": Y < b, "s": Y >= TS - b, "w": X < b, "e": X >= TS - b}[kind]
    if kind in ("nw", "ne", "sw", "se"):
        # water is the inner rectangle with a rounded corner
        fx = Xc if "w" in kind else TS - Xc
        fy = Yc if "n" in kind else TS - Yc
        water = (fx >= b) & (fy >= b)
        corner = (fx < b + r) & (fy < b + r)
        inside = (fx - (b + r)) ** 2 + (fy - (b + r)) ** 2 <= r * r
        water &= ~corner | inside
        return ~water
    if kind in ("inw", "ine", "isw", "ise"):
        k = kind[1:]
        fx = Xc if "w" in k else TS - Xc
        fy = Yc if "n" in k else TS - Yc
        r2 = 2
        land = (fx < b) & (fy < b)
        corner = (fx > b - r2) & (fy > b - r2)
        inside = (fx - (b - r2)) ** 2 + (fy - (b - r2)) ** 2 <= r2 * r2
        land &= ~corner | inside
        return land
    raise ValueError(kind)


def waterfall_frame(f):
    """palette 'waterfall': 0 white, 1 foam, 2 water, 3 deep."""
    a = full(2)
    streaks = [(1, 0, 5), (4, 6, 4), (7, 2, 6), (10, 9, 5), (13, 4, 5), (14, 12, 3)]
    for x, y0, ln in streaks:
        for k in range(ln):
            a[(y0 + k + 4 * f) % TS, x] = 1 if k < ln - 1 else 0
    for x in (0, 15):
        a[:, x] = 3
    for x, y in ((2, 3), (8, 11), (12, 7), (5, 14)):
        a[(y + 4 * f) % TS, x] = 3
    return a


# =========================================================================
# SNOW / ICE
# =========================================================================
def snow_tile():
    a = full(0)
    for x, y in ((3, 2), (11, 5), (6, 10), (14, 13), (1, 8)):
        a[y, x] = 1
        a[y, x + 1 if x < 15 else x - 1] = 1
    for x, y in ((9, 1), (4, 14), (12, 9)):
        a[y, x] = 2
    return a


def ice_tile():
    a = full(1)
    for k in range(-16, 16):
        for x in range(TS):
            y = x + k
            if 0 <= y < TS and k in (-9, -8, 3, 4):
                a[y, x] = 0
    a[0, :] = 2
    a[:, 0] = 2
    for x, y in ((12, 3), (3, 12), (9, 13)):
        a[y, x] = 2
    return a


# =========================================================================
# BUILDINGS
# =========================================================================
def roof(part, top=False):
    """Generic shingled roof. part in l/m/r. palette: 0 white,1 light,2 main,3 dark"""
    a = full(2)
    rows = range(TS)
    for y in rows:
        band = y % 4
        if band == 0:
            a[y, :] = 1
        elif band == 3:
            a[y, :] = 3
    # vertical seams, staggered per band
    for band in range(4):
        off = 3 if band % 2 == 0 else 7
        for x in (off, (off + 8) % TS):
            a[band * 4 + 1:band * 4 + 3, x] = 3
    # roof_* tiles repeat vertically (prefabs stack 2 rows); the *_top variant
    # adds a ridge cap for the topmost row.
    if top:
        a[0, :] = 3
        a[1, :] = 0
        a[2, :] = 1
        a[3, :] = 3
    if part == "l":
        a[:, 0] = 3
        a[:, 1] = np.where(a[:, 1] == 3, 3, 1)
    if part == "r":
        a[:, 15] = 3
        a[:, 14] = np.where(a[:, 14] == 3, 3, 2)
    return a


def wall(part, siding=2):
    """palette wall: 0 white, 1 cream, 2 line, 3 dark"""
    a = full(1)
    a[0, :] = 3
    a[1, :] = 2
    for y in (5, 9):
        a[y, :] = siding
    a[13, :] = 2
    a[14, :] = 3
    a[15, :] = 3
    a[2, :] = 0
    if part == "l":
        a[:, 0] = 3
        a[2:13, 1] = 0
    if part == "r":
        a[:, 15] = 3
        a[2:13, 14] = 2
    return a


def window_on(a, glass=2, frame=3, shine=0, x0=3, y0=3, w=10, h=8):
    a[y0:y0 + h, x0:x0 + w] = frame
    a[y0 + 1:y0 + h - 1, x0 + 1:x0 + w - 1] = glass
    a[y0 + 1:y0 + h - 1, x0 + w // 2] = frame
    for k in range(3):
        a[y0 + 1 + k, x0 + 1 + (2 - k)] = shine
        a[y0 + 1 + k, x0 + w // 2 + 1 + (2 - k)] = shine
    a[y0 + h, x0 - 1:x0 + w + 1] = frame
    return a


def wall_window():
    # palette wall_window: 0 white, 1 cream, 2 blue, 3 dark  (siding uses dark)
    a = wall("m", siding=3)
    a[1, :] = 3
    a[13, :] = 3
    window_on(a)
    return a


def door_tile():
    """palette door: 0 cream, 1 tan, 2 brown, 3 dark"""
    a = full(0)
    a[0, :] = 3
    a[1, :] = 2
    a[14:, :] = 3
    a[2:16, 2:14] = 3
    a[3:16, 3:13] = 2
    a[4:14, 4:12] = 1
    a[4:14, 7:9] = 2
    a[8, 10] = 3
    a[8, 5] = 3
    a[15, 3:13] = 3
    return a


def modern_wall(part):
    """palette glass_door: 0 white, 1 pale blue, 2 blue, 3 dark"""
    a = full(0)
    a[0, :] = 3
    a[1, :] = 1
    a[7, :] = 1
    a[13, :] = 1
    a[14:, :] = 3
    if part == "l":
        a[:, 0] = 3
        a[2:14, 1] = 1
    if part == "r":
        a[:, 15] = 3
        a[2:14, 14] = 1
    return a


def modern_window():
    a = modern_wall("m")
    window_on(a, glass=2, frame=3, shine=1, y0=3, h=8)
    return a


def glass_door():
    """palette glass_door: 0 wall cream, 1 pale glass, 2 blue, 3 dark"""
    a = full(0)
    a[0, :] = 3
    a[1, :] = 3
    a[14:, :] = 3
    a[2:16, 1:15] = 3
    a[3:16, 2:14] = 1
    a[3:16, 7:9] = 3
    for k in range(4):
        a[4 + k, 3 + (3 - k)] = 0
        a[4 + k, 9 + (3 - k)] = 0
    a[3:16, 2] = 2
    a[3:16, 9] = 2
    a[3:16, 6] = 2
    a[3:16, 13] = 2
    return a


def gym_wall(part):
    """palette gym_wall: 0 white, 1 light stone, 2 stone line, 3 dark -- block wall"""
    a = full(1)
    a[0, :] = 3
    a[1, :] = 2
    for y in (5, 9, 13):
        a[y, :] = 2
    for i, y0 in enumerate((2, 6, 10)):
        for x in ((3, 11) if i % 2 == 0 else (7, 15)):
            a[y0:y0 + 3, x] = 2
        a[y0, :] = np.where(a[y0, :] == 1, 0, a[y0, :])
    a[14:, :] = 3
    if part == "l":
        a[:, 0] = 3
    if part == "r":
        a[:, 15] = 3
    return a


def gym_window():
    a = gym_wall("m")
    window_on(a, glass=2, frame=3, shine=0, y0=3, h=8)
    return a


def emblem_ball(bg):
    """centre sign: Poke-ball style emblem (palette sign_center)"""
    a = bg.copy()
    m = pixlib.disc_mask(TS, TS, 8, 8, 7)
    inner = pixlib.disc_mask(TS, TS, 8, 8, 6)
    a[m] = 3
    Y, X = np.mgrid[0:TS, 0:TS]
    a[inner & (Y < 7)] = 2
    a[inner & (Y > 8)] = 0
    a[inner & ((Y == 7) | (Y == 8))] = 3
    btn = pixlib.disc_mask(TS, TS, 8, 8, 2.6)
    a[btn] = 3
    a[pixlib.disc_mask(TS, TS, 8, 8, 1.6)] = 0
    a[3, 5:7] = 1
    a[4, 4] = 1
    return a


def mart_sign():
    """palette sign_mart: 0 white, 1 light blue, 2 blue, 3 dark"""
    a = grid("""
    3333333333333333
    3000000000000003
    3022222222222203
    3022222222222203
    3020220002200203
    3020202020200203
    3020200020200203
    3020200000200203
    3020200000200203
    3020200000200203
    3022222222222203
    3022222222222203
    3000000000000003
    3333333333333333
    1131111111111311
    3333333333333333
    """)
    # draw a white "M" glyph on the plate
    a[4:10, 3:13] = 2
    M = grid("""
    0.....0
    00...00
    0.0.0.0
    0..0..0
    0.....0
    0.....0
    """)
    pixlib.paste(a, M, 5, 4)
    return a


def gym_sign():
    """palette sign_gym: 0 white, 1 yellow, 2 slate, 3 dark  -- crystal emblem"""
    a = full(2)
    a[0, :] = 3
    a[15, :] = 3
    a[:, 0] = 3
    a[:, 15] = 3
    a[1, 1:15] = 0
    gem = grid("""
    ....3333....
    ...310013...
    ..31001113..
    .3100011113.
    333333333333
    .3111111113.
    ..31111113..
    ...311113...
    ....3113....
    .....33.....
    """)
    pixlib.paste(a, gem, 2, 3)
    return a


# =========================================================================
# INDOOR
# =========================================================================
def floor_wood():
    a = full(1)
    for y in (3, 7, 11, 15):
        a[y, :] = 2
    for y, xs in ((0, (5,)), (4, (12,)), (8, (2,)), (12, (9,))):
        for x in xs:
            a[y:y + 3, x] = 2
    for y in (0, 4, 8, 12):
        a[y, :] = np.where(a[y, :] == 1, 0, a[y, :])
    return a


def floor_tile():
    a = full(0)
    a[7, :] = 1
    a[15, :] = 1
    a[:, 7] = 1
    a[:, 15] = 1
    a[7, 7] = 2
    a[15, 15] = 2
    a[15, 7] = 2
    a[7, 15] = 2
    return a


def floor_carpet():
    a = full(1)
    for x, y in ((3, 3), (11, 3), (7, 7), (3, 11), (11, 11), (15, 7), (7, 15)):
        a[y, x] = 2
        a[y - 1, x] = 0
    return a


def wall_in():
    """palette wall_in: 0 white, 1 cream, 2 tan, 3 dark"""
    a = full(1)
    for x in (1, 5, 9, 13):
        a[:12, x] = 0
    for x in (3, 7, 11, 15):
        a[:12, x] = 2
    a[12, :] = 0
    a[13, :] = 2
    a[14, :] = 2
    a[15, :] = 3
    return a


def wall_in_top():
    a = full(1)
    a[0:2, :] = 3
    a[2, :] = 2
    a[3, :] = 0
    for x in (1, 5, 9, 13):
        a[4:, x] = 0
    for x in (3, 7, 11, 15):
        a[4:, x] = 2
    return a


def window_in():
    """palette window_in: 0 white, 1 sky, 2 frame, 3 dark"""
    a = full(0)
    a[1:13, 1:15] = 3
    a[2:12, 2:14] = 1
    a[2:12, 7:9] = 2
    a[6, 2:14] = 2
    for k in range(3):
        a[3 + k, 4 - k] = 0
        a[3 + k, 11 - k] = 0
    a[13, 0:16] = 2
    a[14, 0:16] = 3
    a[15, :] = 0
    return a


WOOD_BG = 1  # index of the floor-tan colour in furniture palettes

COUNTER = grid("""
3333333333333333
0000000000000000
0111111111111110
1111111111111111
2222222222222222
3333333333333333
2111111111111112
2121212121212122
2111111111111112
2121212121212122
2111111111111112
2121212121212122
2111111111111112
2222222222222222
3333333333333333
3333333333333333
""")

BOOKSHELF = grid("""
3333333333333333
3222222222222223
3300330033003303
3010300130013003
3010301130013013
3010301130013013
3333333333333333
3222222222222223
3033003303300333
3013013003100103
3013013013100103
3013013013100103
3333333333333333
3222222222222223
3222222222222223
3333333333333333
""")

TABLE = grid("""
1111111111111111
1111111111111111
1333333333333331
3000000000000003
3011111111111103
3011111111111103
3011111111111103
3011111111111103
3222222222222223
3333333333333333
1323111111113231
1323111111113231
1323111111113231
1333111111113331
1111111111111111
1111111111111111
""")

CHAIR = grid("""
1111111111111111
1111111111111111
1111111111111111
1111133333311111
1111302222031111
1111302222031111
1111300000031111
1113000000003111
1113011111103111
1113222222223111
1111333333331111
1111323113231111
1111323113231111
1111333113331111
1111111111111111
1111111111111111
""")

# bed palette: 0 white, 1 blue, 2 wood, 3 dark
BED_TOP = grid("""
3333333333333333
3222222222222223
3233333333333323
3300000000000033
3000000000000003
3000000000000003
3300000000000033
3333333333333333
3111111111111113
3111111111111113
3000000000000003
3111111111111113
3111111111111113
3111111111111113
3111111111111113
3111111111111113
""")

BED_BOTTOM = grid("""
3111111111111113
3111111111111113
3111111111111113
3111111111111113
3111111111111113
3111111111111113
3111111111111113
3111111111111113
3111111111111113
3311111111111133
3333333333333333
3222222222222223
3222222222222223
3333333333333333
2311111111111132
3311111111111133
""")

# tech palette: 0 white, 1 light grey, 2 blue, 3 dark  (bg = light grey floor)
PC = grid("""
1111111111111111
1333333333333331
3000000000000003
3033333333333303
3032222222222303
3032020022222303
3032222222202303
3032200222222303
3033333333333303
3000000000000003
1333333333333331
1113011111103111
3333333333333333
3000000000000003
3022002200220003
3333333333333333
""")

TV = grid("""
1111111111111111
1111113111311111
1111111313111111
1111111131111111
1333333333333331
3000000000000003
3033333333333303
3032222222220303
3032222222220303
3032222222220303
3032222222220303
3033333333333303
3000000000000303
1333333333333331
1131111111111311
1111111111111111
""")

# plant palette: 0 cream, 1 light green, 2 green, 3 dark  (bg = cream floor)
PLANT = grid("""
0000000330000000
0000033223300000
0003311223113000
0031122231122300
0312223122311230
0322231222122230
0322312213212230
0033122313222300
0003313333133000
0000033333300000
0000333333330000
0003000000003000
0003010101003000
0003101010103000
0000300000030000
0000033333300000
""")

# heal palette: 0 white, 1 pink, 2 red, 3 dark
HEAL = grid("""
3333333333333333
3000000000000003
3011111111111103
3013301330133103
3132213223132231
3130013003130031
3013301330133103
3011111111111103
3000000000000003
3333333333333333
3222222222222223
3200000000000023
3202020202020223
3200000000000023
3222222222222223
3333333333333333
""")

# mart_shelf palette: 0 white, 1 blue goods, 2 wood, 3 dark
MART_SHELF = grid("""
3333333333333333
3222222222222223
3011001100110013
3011001100110013
3333333333333333
3222222222222223
3100110011001103
3100110011001103
3333333333333333
3222222222222223
3011001100110013
3011001100110013
3333333333333333
3222222222222223
3222222222222223
3333333333333333
""")


def rug(part):
    """palette rug: 0 light, 1 mid, 2 red, 3 dark"""
    a = full(2)
    a[0, :] = 3
    a[15, :] = 3
    a[1, :] = 1
    a[14, :] = 1
    for y in range(3, 13, 2):
        a[y, :] = np.where(np.arange(TS) % 4 == (y // 2) % 4, 1, 2)
    if part in ("l", "single"):
        a[:, 0] = 3
        a[1:15, 1] = 1
    if part in ("r", "single"):
        a[:, 15] = 3
        a[1:15, 14] = 1
    return a


def stairs_in(up=True):
    """floor_wood palette"""
    a = full(1)
    a[:, 0] = 3
    a[:, 15] = 3
    for i, y in enumerate(range(0, 16, 4)):
        a[y, 1:15] = 3
        a[y + 1, 1:15] = 0 if up else 2
        a[y + 2, 1:15] = 1
        a[y + 3, 1:15] = 2 if up else 1
    if not up:
        a[12:16, 1:15] = 3
        a[13, 2:14] = 2
    return a


# =========================================================================
# CAVE (palette 'cave': 0 light, 1 mid, 2 dark, 3 darkest)
# =========================================================================
def cave_floor():
    a = full(1)
    for x, y in ((2, 3), (12, 2), (7, 8), (14, 11), (4, 13)):
        a[y, x] = 2
        a[y, x + 1 if x < 15 else x - 1] = 2
    for x, y in ((9, 5), (1, 9), (11, 14)):
        a[y, x] = 0
    return a


def cave_wall():
    a = MOUNTAIN_WALL.copy()
    return pixlib.recolor(a, {0: 1, 1: 2, 2: 2, 3: 3}) if False else a


CAVE_ROCK = grid("""
1111111111111111
1111113333111111
1111330001331111
1113000011113111
1130001111112311
1130011111112311
1300111111122231
1301111111212231
1301111112122231
1311111121222231
1311111212222231
1131112122222311
1133222222223311
1111333333331111
1111111111111111
1111111111111111
""")


def cave_ladder():
    a = full(3)
    a[0:2, :] = 1
    a[:, 0:2] = 1
    a[:, 14:] = 1
    a[14:, :] = 1
    a[2, 2:14] = 2
    a[2:14, 2] = 2
    a[:, 4] = 2
    a[:, 11] = 2
    a[:, 5] = 0
    a[:, 12] = 0
    for y in (1, 5, 9, 13):
        a[y, 4:13] = 0
        a[y + 1, 4:13] = 2
    a[:, 4] = 2
    a[:, 11] = 2
    return a


def cave_wall_top():
    a = full(2)
    for x, y in ((3, 4), (11, 2), (7, 9), (13, 12), (1, 13)):
        a[y, x] = 1
    a[14, :] = 1
    a[15, :] = 0
    return a


# =========================================================================
# GYM / SPECIAL
# =========================================================================
def gym_floor():
    a = full(0)
    a[0, :] = 2
    a[:, 0] = 2
    a[1, 1:] = 1
    a[1:, 1] = 1
    a[8, :] = 1
    a[:, 8] = 1
    return a


GYM_STATUE = grid("""
0000000330000000
0000003113000000
0000031011300000
0000310111130000
0003101111113000
0003111111213000
0000312112130000
0000031121300000
0000003223000000
0003333333333000
0031111111111300
0032222222222300
0033333333333300
0031212121212300
0032222222222300
0033333333333300
""")


def puzzle_switch():
    a = gym_floor()
    a[4:12, 4:12] = 3
    a[5:11, 5:11] = 1
    a[5, 5:11] = 0
    a[5:11, 5] = 0
    a[10, 6:11] = 2
    a[6:11, 10] = 2
    return a


def puzzle_wall():
    a = full(2)
    a[0, :] = 3
    a[15, :] = 3
    a[:, 0] = 3
    a[:, 15] = 3
    a[1, 1:15] = 0
    a[1:15, 1] = 0
    for k in range(2, 14):
        a[k, k] = 1
        a[k, 15 - k] = 1
    return a


def spinner(direction):
    a = gym_floor()
    a[3:13, 3:13] = 3
    a[4:12, 4:12] = 2
    arrow = grid("""
    ...00...
    ..0000..
    .000000.
    000000..
    ..00....
    ..00....
    ..00....
    ........
    """)
    arrow = grid("""
    ...00...
    ..0000..
    .000000.
    00000000
    ..0000..
    ..0000..
    ..0000..
    ........
    """)
    k = {"up": 0, "left": 1, "down": 2, "right": 3}[direction]
    pixlib.paste(a, np.rot90(arrow, k), 4, 4)
    return a


def ruins_floor():
    a = full(1)
    a[0, :] = 2
    a[8, :] = 2
    a[0:8, 0] = 2
    a[8:16, 8] = 2
    a[1, 1:] = 0
    a[9, :8] = 0
    a[9, 9:] = 0
    for x, y in ((4, 4), (12, 12), (11, 3)):
        a[y, x] = 2
    return a


RUINS_WALL = grid("""
3333333333333333
0000000300000003
1111111311111113
1111111311111113
2222222322222223
3333333333333333
0003000000030000
1113112221131111
1113121112131111
2223122222232222
3333333333333333
0000000300000003
1111111311111113
1111111311111113
2222222322222223
3333333333333333
""")


def league_floor():
    a = full(1)
    a[0, :] = 3
    a[:, 0] = 3
    d = grid("""
    ......0.......
    .....010......
    ....01210.....
    ...0122210....
    ..012232210...
    ...0122210....
    ....01210.....
    .....010......
    ......0.......
    """)
    pixlib.paste(a, d, 2, 4)
    a[1, 1:] = 0
    return a


def dark_floor():
    a = full(2)
    for x, y in ((3, 3), (11, 7), (6, 12), (14, 14)):
        a[y, x] = 1
    a[0, :] = 3
    a[:, 0] = 3
    return a


# =========================================================================
# TILE LIST
# =========================================================================
def build_tiles():
    """Return ordered list of (name, palette, [frames])."""
    T = []

    def add(name, pal, frames):
        if isinstance(frames, np.ndarray):
            frames = [frames]
        for f in frames:
            assert f.shape == (TS, TS), name
            assert f.min() >= 0, f"{name} has transparent pixels"
        T.append((name, pal, frames))

    add("void", "dark", full(3))
    # ---- outdoor ground
    add("grass", "outdoor", make_grass())
    add("grass_alt", "outdoor", make_grass_alt())
    add("tall_grass", "outdoor", TALL_GRASS)
    add("flowers", "flowers", flowers_frames())
    add("path", "path", path_base())
    for s in "nsew":
        add(f"path_edge_{s}", "path", path_edge(s))
    for c in ("ne", "nw", "se", "sw"):
        add(f"path_corner_{c}", "path", path_edge(c))
    add("sand", "sand", sand_tile())
    add("town_floor", "town", town_floor())
    # ---- vegetation
    add("tree", "outdoor", TREE)
    add("tree_top", "outdoor", TREE_TOP)
    add("tree_bottom", "outdoor", TREE_BOTTOM)
    add("bush", "outdoor", BUSH)
    add("cut_tree", "outdoor", CUT_TREE)
    add("rock", "rocky", ROCK)
    add("boulder", "rocky", BOULDER)
    add("ledge_s", "outdoor", LEDGE_S)
    add("ledge_e", "outdoor", make_ledge_e())
    add("ledge_w", "outdoor", make_ledge_w())
    add("fence_h", "fence", FENCE_H)
    add("fence_v", "fence", FENCE_V)
    add("sign", "fence", SIGN)
    # ---- water
    wf = [water_frame(f) for f in range(4)]
    add("water", "water", wf)
    for k in ("n", "s", "e", "w", "ne", "nw", "se", "sw", "ine", "inw", "ise", "isw"):
        m = shore_mask(k)
        add(f"water_{k}", "water", [shore(fr, m) for fr in wf])
    add("waterfall", "waterfall", [waterfall_frame(f) for f in range(4)])
    add("bridge_h", "bridge", BRIDGE_H)
    add("bridge_v", "bridge", np.rot90(BRIDGE_H).copy())
    # ---- mountain
    add("stairs_up", "mountain", STAIRS_UP)
    add("stairs_down", "mountain", STAIRS_DOWN)
    add("cave_entrance", "mountain", CAVE_ENTRANCE)
    add("mountain_wall", "mountain", MOUNTAIN_WALL)
    add("mountain_top", "mountain", MOUNTAIN_TOP)
    add("snow", "snow", snow_tile())
    add("ice", "ice", ice_tile())
    # ---- houses  (roof_* repeat vertically; *_top = optional ridge row)
    for prefix, pal in (("roof", "roof_red"), ("roof2", "roof_blue"),
                        ("center_roof", "roof_center"), ("mart_roof", "roof_blue"),
                        ("gym_roof", "roof_gym")):
        for p in "lmr":
            add(f"{prefix}_{p}", pal, roof(p))
        for p in "lmr":
            add(f"{prefix}_{p}_top", pal, roof(p, top=True))
    for p in "lmr":
        add(f"wall_{p}", "wall", wall(p))
    add("wall_window", "wall_window", wall_window())
    add("door", "door", door_tile())
    # ---- pokemon centre / mart share the house wall style so wall_window fits
    add("center_sign", "sign_center", emblem_ball(roof("m")))
    add("center_wall_l", "wall", wall("l"))
    add("center_wall_m", "wall", wall("m"))
    add("center_wall_r", "wall", wall("r"))
    add("center_door", "glass_door", glass_door())
    add("mart_sign", "sign_mart", mart_sign())
    add("mart_wall_l", "wall", wall("l"))
    add("mart_wall_m", "wall", wall("m"))
    add("mart_wall_r", "wall", wall("r"))
    add("mart_door", "glass_door", glass_door())
    # ---- gym
    add("gym_sign", "sign_gym", gym_sign())
    for p in "lmr":
        add(f"gym_wall_{p}", "gym_wall", gym_wall(p))
    add("gym_window", "gym_wall", gym_window())
    add("gym_door", "glass_door", glass_door())
    # ---- indoor
    add("floor_wood", "floor_wood", floor_wood())
    add("floor_tile", "floor_tile", floor_tile())
    add("floor_carpet", "carpet", floor_carpet())
    add("wall_in", "wall_in", wall_in())
    add("wall_in_top", "wall_in", wall_in_top())
    add("window_in", "window_in", window_in())
    add("counter", "furniture", COUNTER)
    add("pc", "tech", PC)
    add("bookshelf", "furniture", BOOKSHELF)
    add("table", "furniture", TABLE)
    add("chair", "furniture", CHAIR)
    add("bed_top", "bed", BED_TOP)
    add("bed_bottom", "bed", BED_BOTTOM)
    add("plant", "plant", PLANT)
    add("tv", "tech", TV)
    add("rug_l", "rug", rug("l"))
    add("rug_r", "rug", rug("r"))
    add("rug_mat", "rug", rug("single"))
    add("stairs_in_up", "floor_wood", stairs_in(True))
    add("stairs_in_down", "floor_wood", stairs_in(False))
    add("healing_machine", "heal", HEAL)
    add("mart_shelf", "mart_shelf", MART_SHELF)
    # ---- cave
    add("cave_floor", "cave", cave_floor())
    add("cave_wall", "cave", MOUNTAIN_WALL)
    add("cave_wall_top", "cave", cave_wall_top())
    add("cave_rock", "cave", CAVE_ROCK)
    add("cave_ladder", "cave", cave_ladder())
    cw = [shore(water_frame(f), np.zeros((TS, TS), bool)) for f in range(4)]
    add("cave_water", "cave_water", cw)
    # ---- gym / special
    add("gym_floor", "gym", gym_floor())
    add("gym_statue", "gym", GYM_STATUE)
    add("puzzle_switch", "puzzle", puzzle_switch())
    add("puzzle_wall", "puzzle", puzzle_wall())
    for d in ("up", "down", "left", "right"):
        add(f"spinner_{d}", "puzzle", spinner(d))
    add("ruins_floor", "ruins", ruins_floor())
    add("ruins_wall", "ruins", RUINS_WALL)
    add("league_floor", "league", league_floor())
    add("dark_floor", "dark", dark_floor())
    return T


def flowers_frames():
    """palette flowers: 0 white, 1 grass, 2 red, 3 green"""
    fa = grid("""
    .00.
    0220
    0220
    .00.
    """)
    fb = grid("""
    .22.
    2002
    2002
    .22.
    """)
    leaf = grid("""
    3..3
    .33.
    """)
    frames = []
    for f, (da, db) in enumerate(((0, 0), (1, -1), (0, 0), (-1, 1))):
        a = full(1)
        tuft(a, 11, 2, 3)
        tuft(a, 3, 12, 3)
        pixlib.paste(a, leaf, 3, 7)
        pixlib.paste(a, fa, 3 + da, 3)
        pixlib.paste(a, leaf, 10, 14)
        pixlib.paste(a, fb, 10 + db, 10)
        frames.append(a)
    return frames


def town_floor():
    """palette town: 0 light, 1 paving line, 2 grass, 3 green -- paved plaza"""
    a = full(0)
    a[7, :] = 1
    a[15, :] = 1
    a[0:7, 3] = 1
    a[8:15, 11] = 1
    return a


# =========================================================================
def build():
    tiles = build_tiles()
    n_frames = sum(len(f) for _, _, f in tiles)
    rows = (n_frames + COLUMNS - 1) // COLUMNS
    from PIL import Image
    sheet = Image.new("RGBA", (COLUMNS * TS, rows * TS), (0, 0, 0, 0))
    meta = {"tileSize": TS, "columns": COLUMNS, "tiles": {}}
    idx = 0
    for name, pal, frames in tiles:
        assert name not in meta["tiles"], f"duplicate tile {name}"
        ids = []
        for fr in frames:
            img = pixlib.to_rgba(fr, TILE[pal])
            assert pixlib.colours_used(img) <= 4
            sheet.paste(img, ((idx % COLUMNS) * TS, (idx // COLUMNS) * TS))
            ids.append(idx)
            idx += 1
        entry = {"index": ids[0], "palette": pal}
        if len(ids) > 1:
            entry["frames"] = ids
            entry["frameMs"] = FRAME_MS
        meta["tiles"][name] = entry
    out = os.path.join(pixlib.GFX, "tiles")
    pixlib.save_png(sheet, os.path.join(out, "world.png"))
    pixlib.save_json(meta, os.path.join(out, "world.json"))
    return sheet, meta


if __name__ == "__main__":
    s, m = build()
    print("tiles:", len(m["tiles"]), "sheet:", s.size)
