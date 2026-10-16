"""UI pieces -> assets/gfx/ui/*.png (+ json descriptors).

frame / frame_alt (24x24 nine-slice, 8px borders), cursor, down_arrow,
hp_bar / exp_bar (+json), status_icons, type_icons (+json), ball_icons,
gimmick_icons, pokeball_icon, touch controls, weather overlays.
The title logo and region map live in logo.py / regionmap.py.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixlib  # noqa: E402
from pixlib import grid  # noqa: E402
from palettes import UI, TYPE_COLOURS, pal, _c  # noqa: E402

OUT = os.path.join(pixlib.GFX, "ui")
INK = _c("181818")
WHITE = _c("F8F8F8")

# ---------------------------------------------------------------- micro font
# 5px tall proportional caps used for tiny labels (type icons, HUD, buttons)
MICRO_SRC = {
    "A": ".#.|#.#|###|#.#|#.#", "B": "##.|#.#|##.|#.#|##.", "C": ".##|#..|#..|#..|.##",
    "D": "##.|#.#|#.#|#.#|##.", "E": "###|#..|##.|#..|###", "F": "###|#..|##.|#..|#..",
    "G": ".##|#..|#.#|#.#|.##", "H": "#.#|#.#|###|#.#|#.#", "I": "#|#|#|#|#",
    "K": "#.#|#.#|##.|#.#|#.#", "L": "#..|#..|#..|#..|###", "M": "#...#|##.##|#.#.#|#...#|#...#",
    "N": "#..#|##.#|#.##|#..#|#..#", "O": ".#.|#.#|#.#|#.#|.#.", "P": "##.|#.#|##.|#..|#..",
    "R": "##.|#.#|##.|#.#|#.#", "S": ".##|#..|.#.|..#|##.", "T": "###|.#.|.#.|.#.|.#.",
    "U": "#.#|#.#|#.#|#.#|###", "V": "#.#|#.#|#.#|#.#|.#.", "W": "#...#|#...#|#.#.#|##.##|#...#",
    "X": "#.#|#.#|.#.|#.#|#.#", "Y": "#.#|#.#|.#.|.#.|.#.", "Z": "###|..#|.#.|#..|###",
    ":": ".|#|.|#|.", " ": "..|..|..|..|..", "J": "..#|..#|..#|#.#|.#.",
}
MICRO = {k: np.array([[c == "#" for c in row] for row in v.split("|")]) for k, v in MICRO_SRC.items()}


def micro_mask(text):
    cols = []
    for i, ch in enumerate(text):
        g = MICRO[ch]
        if i:
            cols.append(np.zeros((5, 1), bool))
        cols.append(g)
    return np.hstack(cols) if cols else np.zeros((5, 0), bool)


def put_mask(rgba, mask, x, y, colour):
    h, w = mask.shape
    sub = rgba[y:y + h, x:x + w]
    sub[mask[:sub.shape[0], :sub.shape[1]]] = (*colour, 255)


def img_from(arr):
    return Image.fromarray(arr, "RGBA")


def blank(w, h):
    return np.zeros((h, w, 4), np.uint8)


def darker(c, f=0.55):
    return tuple(int(v * f) // 8 * 8 for v in c)


# ------------------------------------------------------------------- frames
def frame(kind):
    """24x24 nine-slice; 8px corners. GSC-style double line."""
    p = UI[kind]
    a = np.full((24, 24), 0, np.int8)
    if kind == "frame":
        rings = {0: 3, 1: 1, 2: 3, 3: 0, 4: 2}
    else:
        rings = {0: 3, 1: 3, 2: 1, 3: 2, 4: 0}
    Y, X = np.mgrid[0:24, 0:24]
    inset = np.minimum(np.minimum(X, Y), np.minimum(23 - X, 23 - Y))
    for k, c in rings.items():
        a[inset == k] = c
    # rounded outer corners
    for (cx, cy) in ((0, 0), (23, 0), (0, 23), (23, 23)):
        a[cy, cx] = -1
        a[cy, 1 if cx == 0 else 22] = 3 if kind == "frame" else 3
        a[1 if cy == 0 else 22, cx] = 3
        a[1 if cy == 0 else 22, 1 if cx == 0 else 22] = 3
    return pixlib.to_rgba(a, p)


CURSOR = grid("""
.3......
.33.....
.333....
.3333...
.333....
.33.....
.3......
........
""")
DOWN = grid("""
........
........
3333333.
.33333..
..333...
...3....
........
........
""")
INKPAL = pal("F8F8F8", "B8B8B8", "686868", "181818")


# ------------------------------------------------------------------ hp / exp
def bar(width, label, fill_x, fill_w):
    a = blank(width, 8)
    m = micro_mask(label)
    # dark label plate like the GSC HUD
    a[1:7, 0:m.shape[1] + 3] = (*INK, 255)
    put_mask(a, m, 2, 1 + 0, (248, 216, 72))
    x1 = fill_x + fill_w           # first column right of the fill area
    a[2, fill_x:x1] = (*INK, 255)
    a[5, fill_x:x1] = (*INK, 255)
    a[3:5, fill_x - 1] = (*INK, 255)
    a[3:5, x1] = (*INK, 255)
    a[3:5, fill_x:x1] = (*WHITE, 255)
    return img_from(a), {"x": fill_x, "y": 3, "w": fill_w, "h": 2}


# ----------------------------------------------------------- status / types
STATUS = [("BRN", "E86830"), ("PAR", "D8B020"), ("PSN", "A040A0"), ("SLP", "8888A0"), ("FRZ", "48A8D8")]


def pill(w, h, colour, text):
    a = blank(w, h)
    col = _c(colour)
    dk = darker(col)
    a[:, :] = (*col, 255)
    a[0, :] = a[-1, :] = (*dk, 255)
    a[:, 0] = a[:, -1] = (*dk, 255)
    for (x, y) in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        a[y, x] = (0, 0, 0, 0)
    m = micro_mask(text)
    x = (w - m.shape[1] + 1) // 2
    y = (h - 5) // 2
    put_mask(a, m, x, y + 1, dk)       # drop shadow
    put_mask(a, m, x, y, WHITE)
    return a


TYPE_ORDER = ["normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison",
              "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark",
              "steel", "fairy", "stellar"]


def type_icons():
    cols = 4
    rows = (len(TYPE_ORDER) + cols - 1) // cols
    a = blank(32 * cols, 12 * rows)
    for i, t in enumerate(TYPE_ORDER):
        x, y = (i % cols) * 32, (i // cols) * 12
        a[y:y + 12, x:x + 32] = pill(32, 12, TYPE_COLOURS[t], t.upper())
    meta = {"cellW": 32, "cellH": 12, "columns": cols,
            "types": {t: i for i, t in enumerate(TYPE_ORDER)}}
    return img_from(a), meta


# ---------------------------------------------------------------- icons
BALLPAL = pal("F8F8F8", "B8B8B8", "E03828", "181010")
BALL8 = {
    "full": grid("""
..3333..
.322223.
32122223
33300333
30033003
.300003.
..3333..
........
"""),
    "empty": grid("""
..3333..
.3....3.
3......3
3......3
3......3
.3....3.
..3333..
........
"""),
    "fainted": grid("""
..3333..
.311113.
31111113
33311333
31133113
.311113.
..3333..
........
"""),
    "status": grid("""
..3333..
.311113.
31011113
33300333
30033003
.300003.
..3333..
........
"""),
}

POKEBALL16 = grid("""
................
.....333333.....
...3322222233...
..322112222223..
..321122222223..
.32112222222223.
.32222222222223.
.33333300333333.
.30000033000003.
.30000300300003.
.30000033000003.
..300000000003..
..300000000003..
...3300000033...
.....333333.....
................
""")

GIMMICK_SRC = {
    # mega: swirling orb
    "mega": (pal("F8F8F8", "F0B0F8", "A050D8", "301040"), """
.....333333.....
...3311111133...
..311000001113..
.31102222200113.
.31022333220113.
3110232113201113
3102321003201113
3102310110320113
3110230013220113
3111023112320113
3111102233220113
.3111022222013..
.3311100000113..
..331111111133..
...3333333333...
................
"""),
    # z: bolt shaped Z
    "zmove": (pal("F8F8F8", "F8E070", "E0A020", "382008"), """
................
.33333333333333.
.30000000000013.
.31111111111123.
.33333333112233.
.......3112233..
......3112233...
.....3112233....
....3112233.....
...3112233......
..3112233.......
.3112233333333..
.31122222222223.
.30000000000013.
.33333333333333.
................
"""),
    # dynamax: expanding burst with up arrow
    "dynamax": (pal("F8F8F8", "F8A0A0", "E02848", "400818"), """
.......33.......
......3003......
.....300003.....
....30000003....
...3333003333...
..33..3003..33..
.32...3003...23.
32.3333003333.23
32.3111111113.23
32.3122222213.23
.32.31222213.23.
..33.311113.33..
...333.33.333...
.....3....3.....
......3333......
................
"""),
    # tera: faceted crystal
    "tera": (pal("F8F8F8", "B0F0F8", "40A8D8", "102848"), """
................
......3333......
.....310123.....
....31001223....
...3100012223...
..310000122223..
.33333333333333.
.31000001222223.
..310001222223..
...3100122223...
....31012223....
.....311223.....
......3123......
.......33.......
................
................
"""),
}


# ------------------------------------------------------------- touch input
def touch_dpad():
    S = 96
    a = blank(S, S)
    Y, X = np.mgrid[0:S, 0:S]
    arm = 32
    cross = ((np.abs(X - 47.5) < arm / 2) & (np.abs(Y - 47.5) < 46)) | \
            ((np.abs(Y - 47.5) < arm / 2) & (np.abs(X - 47.5) < 46))
    edge = cross & ~(np.roll(cross, 2, 0) & np.roll(cross, -2, 0) & np.roll(cross, 2, 1) & np.roll(cross, -2, 1))
    a[cross] = (40, 40, 56, 140)
    a[edge] = (248, 248, 248, 170)
    centre = (np.abs(X - 47.5) < 7) & (np.abs(Y - 47.5) < 7)
    a[centre] = (24, 24, 32, 150)
    tri = grid("""
....3....
...333...
..33333..
.3333333.
333333333
""")
    for k, (x, y) in enumerate(((43, 10), (10, 43), (43, 80), (80, 43))):
        t = np.rot90(tri, k)
        h, w = t.shape
        ox, oy = x + (9 - w) // 2 - (0 if w == 9 else 0), y + (9 - h) // 2 - (2 if h == 5 else 0)
        if k == 0:
            ox, oy = 44, 10
        elif k == 1:
            ox, oy = 10, 44
        elif k == 2:
            ox, oy = 44, 81
        else:
            ox, oy = 81, 44
        sub = a[oy:oy + h, ox:ox + w]
        sub[t == 3] = (248, 248, 248, 200)
    return img_from(a)


def touch_button(letter, colour):
    S = 40
    a = blank(S, S)
    Y, X = np.mgrid[0:S, 0:S]
    d = np.hypot(X + 0.5 - 20, Y + 0.5 - 20)
    a[d <= 19] = (248, 248, 248, 170)
    a[d <= 17] = (*_c(colour), 150)
    a[(d <= 17) & (d > 15) & (Y > 20)] = (*darker(_c(colour)), 160)
    import font
    m = font.cell_mask(letter, 8)
    m = np.kron(m, np.ones((2, 2), bool))
    put = a[12:28, 13:29]
    put[m[:16, :16]] = (248, 248, 248, 230)
    return img_from(a)


def touch_pill(text):
    w, h = 40, 16
    a = blank(w, h)
    Y, X = np.mgrid[0:h, 0:w]
    inside = (np.abs(X + 0.5 - 20) <= 12) & (Y >= 1) & (Y < 15) | \
        (np.hypot(X + 0.5 - 8, Y + 0.5 - 8) <= 7) | (np.hypot(X + 0.5 - 32, Y + 0.5 - 8) <= 7)
    edge = inside & ~(np.roll(inside, 1, 0) & np.roll(inside, -1, 0) & np.roll(inside, 1, 1) & np.roll(inside, -1, 1))
    a[inside] = (56, 56, 72, 150)
    a[edge] = (248, 248, 248, 170)
    m = micro_mask(text)
    put_mask(a, m, (w - m.shape[1] + 1) // 2, 6, (248, 248, 248))
    return img_from(a)


# ------------------------------------------------------------------ weather
def weather(kind):
    S = 64
    a = blank(S, S)
    rng = np.random.RandomState(1234 if kind == "rain" else 4321)
    if kind == "rain":
        for _ in range(26):
            x, y = rng.randint(0, S, 2)
            ln = rng.randint(4, 7)
            for k in range(ln):
                a[(y + k) % S, (x - k // 2) % S] = (200, 224, 248, 190 if k else 120)
    else:
        for _ in range(30):
            x, y = rng.randint(0, S, 2)
            big = rng.rand() < 0.35
            pts = [(0, 0)] + ([(1, 0), (0, 1), (1, 1)] if big else [])
            for dx, dy in pts:
                a[(y + dy) % S, (x + dx) % S] = (248, 248, 248, 230)
    return img_from(a)


def build():
    os.makedirs(OUT, exist_ok=True)
    save = lambda im, n: pixlib.save_png(im, os.path.join(OUT, n))  # noqa: E731
    save(frame("frame"), "frame.png")
    save(frame("frame_alt"), "frame_alt.png")
    save(pixlib.to_rgba(CURSOR, INKPAL), "cursor.png")
    save(pixlib.to_rgba(DOWN, INKPAL), "down_arrow.png")
    hp_c = UI["hp"]
    hp, fill = bar(64, "HP", 15, 48)
    save(hp, "hp_bar.png")
    pixlib.save_json({"width": 64, "height": 8, "fill": fill,
                      "fillColours": {"high": "#%02X%02X%02X" % hp_c["green"],
                                      "mid": "#%02X%02X%02X" % hp_c["yellow"],
                                      "low": "#%02X%02X%02X" % hp_c["red"]},
                      "thresholds": {"mid": 0.5, "low": 0.2},
                      "notes": "Draw the bar, then fill fill.w*hp/maxHp pixels from fill.x."},
                     os.path.join(OUT, "hp_bar.json"))
    ex, fill = bar(80, "EXP", 18, 60)
    save(ex, "exp_bar.png")
    pixlib.save_json({"width": 80, "height": 8, "fill": fill,
                      "fillColour": "#%02X%02X%02X" % hp_c["exp"]},
                     os.path.join(OUT, "exp_bar.json"))
    st = blank(24 * len(STATUS), 8)
    for i, (t, c) in enumerate(STATUS):
        st[:, i * 24:(i + 1) * 24] = pill(24, 8, c, t)
    save(img_from(st), "status_icons.png")
    pixlib.save_json({"cellW": 24, "cellH": 8, "order": [s for s, _ in STATUS]},
                     os.path.join(OUT, "status_icons.json"))
    ti, meta = type_icons()
    save(ti, "type_icons.png")
    pixlib.save_json(meta, os.path.join(OUT, "type_icons.json"))
    balls = Image.new("RGBA", (32, 8), (0, 0, 0, 0))
    ballpals = {"full": BALLPAL, "empty": BALLPAL,
                "fainted": pal("F8F8F8", "909090", "585858", "181818"),
                "status": pal("F8F8F8", "F8D048", "E03828", "181010")}
    for i, k in enumerate(("full", "empty", "fainted", "status")):
        balls.paste(pixlib.to_rgba(BALL8[k], ballpals[k]), (i * 8, 0))
    save(balls, "ball_icons.png")
    pixlib.save_json({"cellW": 8, "cellH": 8, "order": ["full", "empty", "fainted", "status"]},
                     os.path.join(OUT, "ball_icons.json"))
    gi = Image.new("RGBA", (64, 16), (0, 0, 0, 0))
    for i, k in enumerate(("mega", "zmove", "dynamax", "tera")):
        p, src = GIMMICK_SRC[k]
        gi.paste(pixlib.to_rgba(grid(src), p), (i * 16, 0))
    save(gi, "gimmick_icons.png")
    pixlib.save_json({"cellW": 16, "cellH": 16, "order": ["mega", "zmove", "dynamax", "tera"]},
                     os.path.join(OUT, "gimmick_icons.json"))
    save(pixlib.to_rgba(POKEBALL16, BALLPAL), "pokeball_icon.png")
    save(touch_dpad(), "touch_dpad.png")
    save(touch_button("A", "D83848"), "touch_button_a.png")
    save(touch_button("B", "3868D8"), "touch_button_b.png")
    save(touch_pill("START"), "touch_button_start.png")
    save(touch_pill("SELECT"), "touch_button_select.png")
    save(weather("rain"), "weather_rain.png")
    save(weather("snow"), "weather_snow.png")


if __name__ == "__main__":
    build()
