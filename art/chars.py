"""Overworld character sheets -> assets/gfx/chars/<id>.png (48x64).

Layout: 4 rows (down, up, left, right) x 3 columns (stand, step A, step B),
16x16 frames, transparent background, 4 colours per sprite:
    0 white, 1 skin, 2 role colour, 3 outline.

Sprites are assembled from text templates ("paper doll"):
    head template (rows 0-12, drawn last) + body template (rows 10-13)
    + legs template (rows 14-15).
Template letters:
    . transparent   k outline   w white   s skin
    h hair  a hat/hood  o outer top  c inner top  d pants/skirt  l legs
    b beard/moustache
Each character maps h/a/o/c/d/l/b to one of K(outline) W(white) S(skin)
P(role colour). Right-facing frames are mirrored left-facing ones.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixlib  # noqa: E402
from pixlib import chargrid  # noqa: E402
from palettes import ROLE, SKIN, SKIN_TAN, spr  # noqa: E402

K, W, S, P = 3, 0, 1, 2
FIXED = {"k": K, "w": W, "s": S}


def H(text):
    """head grid: pad to 13 rows"""
    g = chargrid(text)
    if g.shape[0] < 13:
        g = np.vstack([g, np.full((13 - g.shape[0], 16), ".")])
    assert g.shape == (13, 16), g.shape
    return g


# ------------------------------------------------------------------ heads
FACE_D = """
.khhshhhhhhshhk.
.khsskssssksshk.
..kssksssskssk..
...kssssssssk...
"""
FACE_U = """
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
..kshhhhhhhhsk..
...kksssssskk...
"""
FACE_L = """
.kssshhhhhhhhhk.
ksssksshhhhhhhk.
..ksksssshhhhk..
...ksssssshkk...
"""
TOP_SHORT = """
................
.....kkkkkk.....
...kkhhhhhhkk...
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
"""

HEADS = {
    "short": {
        "down": H(TOP_SHORT + FACE_D),
        "up": H(TOP_SHORT + FACE_U),
        "left": H(TOP_SHORT + FACE_L),
    },
    "spiky": {
        "down": H("""
....k..kk..k....
...khkkhhkkhk...
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
khhhhhhhhhhhhhhk
.khhhhhhhhhhhhk.
""" + FACE_D),
        "up": H("""
....k..kk..k....
...khkkhhkkhk...
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
khhhhhhhhhhhhhhk
.khhhhhhhhhhhhk.
""" + FACE_U),
        "left": H("""
......k..k......
.....khkkhk.k...
...kkhhhhhhkhk..
..khhhhhhhhhhhk.
.khhhhhhhhhhhhhk
.khhhhhhhhhhhhk.
""" + FACE_L),
    },
    "cap": {
        "down": H("""
................
.....kkkkkk.....
...kkaaaaaakk...
..kaaaawwaaaak..
.kaaaaaaaaaaaak.
.kkkkkkkkkkkkkk.
.khkaaaaaaaakhk.
.khsskssssksshk.
..kssksssskssk..
...kssssssssk...
"""),
        "up": H("""
................
.....kkkkkk.....
...kkaaaaaakk...
..kaaaaaaaaaak..
.kaaaaaaaaaaaak.
.kkkkkkkkkkkkkk.
""" + FACE_U),
        "left": H("""
................
.....kkkkkk.....
...kkaaaaaakk...
..kaawwaaaaaak..
.kaaaaaaaaaaaak.
kaaaakkkkkkkkkk.
kkkkkshhhhhhhhk.
ksssksshhhhhhhk.
..ksksssshhhhk..
...ksssssshkk...
"""),
    },
    "widehat": {
        "down": H("""
................
......kkkk......
....kkaaaakk....
...kaaaaaaaak...
.kkccccccccccck.
kaaaaaaaaaaaaaak
.kkkkkkkkkkkkkk.
.khsskssssksshk.
..kssksssskssk..
...kssssssssk...
"""),
        "up": H("""
................
......kkkk......
....kkaaaakk....
...kaaaaaaaak...
.kkccccccccccck.
kaaaaaaaaaaaaaak
.kkkkkkkkkkkkkk.
.khhhhhhhhhhhhk.
..kshhhhhhhhsk..
...kksssssskk...
"""),
        "left": H("""
................
......kkkk......
....kkaaaakk....
...kaaaaaaaak...
.kkccccccccccck.
kaaaaaaaaaaaaaak
.kkkkkkkkkkkkkk.
ksssksshhhhhhhk.
..ksksssshhhhk..
...ksssssshkk...
"""),
    },
    "tophat": {
        "down": H("""
....kkkkkkkk....
....kaaaaaak....
....kaaaaaak....
....kwwwwwwk....
..kkkkkkkkkkkk..
.khhhhhhhhhhhhk.
""" + FACE_D),
        "up": H("""
....kkkkkkkk....
....kaaaaaak....
....kaaaaaak....
....kwwwwwwk....
..kkkkkkkkkkkk..
.khhhhhhhhhhhhk.
""" + FACE_U),
        "left": H("""
....kkkkkkkk....
....kaaaaaak....
....kaaaaaak....
....kwwwwwwk....
.kkkkkkkkkkkkk..
.khhhhhhhhhhhhk.
""" + FACE_L),
    },
    "long": {
        "down": H("""
................
.....kkkkkk.....
...kkhhhhhhkk...
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
.khhshhhhhhshhk.
.khsskssssksshk.
.khksksssskskhk.
.khhkssssssk.hk.
.khhk......khhk.
.khk........khk.
..k..........k..
"""),
        "up": H("""
................
.....kkkkkk.....
...kkhhhhhhkk...
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
..khhhhhhhhhhk..
...khhhhhhhhk...
....kkkkkkkk....
"""),
        "left": H("""
................
.....kkkkkk.....
...kkhhhhhhkk...
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
.kssshhhhhhhhhk.
ksssksshhhhhhhk.
..ksksssshhhhhk.
...ksssssshhhhk.
........khhhhk..
.........khhk...
..........kk....
"""),
    },
    "twintails": {
        "down": H(TOP_SHORT + """
.khhshhhhhhshhk.
khhsskssssksshhk
khkssksssskssKhk
kh.kssssssssk.hk
kh............hk
.k............k.
""".replace("K", "k")),
        "up": H(TOP_SHORT + """
.khhhhhhhhhhhhk.
khhhhhhhhhhhhhhk
khkshhhhhhhhskhk
kh.kksssssskk.hk
kh............hk
.k............k.
"""),
        "left": H(TOP_SHORT + """
.kssshhhhhhhhhk.
.ksskssshhhhhhkk
..ksksssshhhhkhk
...ksssssshkk.hk
..............hk
..............k.
"""),
    },
    "bun": {
        "down": H("""
......kkkk......
.....khhhhk.....
...kkkhhhhkkk...
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
""" + FACE_D),
        "up": H("""
......kkkk......
.....khhhhk.....
...kkkhhhhkkk...
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
""" + FACE_U),
        "left": H("""
.........kkkk...
........khhhhk..
...kkkkkkhhhhk..
..khhhhhhhhhhk..
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
""" + FACE_L),
    },
    "bald": {
        "down": H("""
................
.....kkkkkk.....
...kksssssskk...
..ksswssssssk...
.kssssssssssssk.
.khsssssssssshk.
.khhsssssssshhk.
.khsskssssksshk.
..kssksssskssk..
...kssssssssk...
"""),
        "up": H("""
................
.....kkkkkk.....
...kksssssskk...
..kssssssssssk..
.kssssssssssssk.
.khsssssssssshk.
.khhhhhhhhhhhhk.
.khhhhhhhhhhhhk.
..kshhhhhhhhsk..
...kksssssskk...
"""),
        "left": H("""
................
.....kkkkkk.....
...kksssssskk...
..kswsssssssk...
.kssssssssssssk.
.ksssssssssshhk.
.kssssssshhhhhk.
ksssksshhhhhhhk.
..ksksssshhhhk..
...ksssssshkk...
"""),
    },
    "hood": {
        "down": H("""
................
.....kkkkkk.....
...kkaaaaaakk...
..kaaaaaaaaaak..
.kaaaaaaaaaaaak.
.kaakkkkkkkkaak.
.kakhhssssshkak.
.kaksksssskskak.
.kaakssssssk aak.
..kaakkkkkkaak..
""".replace("k aak", "kaak")),
        "up": H("""
................
.....kkkkkk.....
...kkaaaaaakk...
..kaaaaaaaaaak..
.kaaaaaaaaaaaak.
.kaaaaaaaaaaaak.
.kaaaaaaaaaaaak.
.kaaaaaaaaaaaak.
.kaaaaaaaaaaaak.
..kaaaaaaaaaak..
"""),
        "left": H("""
................
.....kkkkkk.....
...kkaaaaaakk...
..kaaaaaaaaaak..
.kkkkkaaaaaaaak.
.kshkkaaaaaaaak.
.ksskkaaaaaaaak.
..kskaaaaaaaaak.
...kkaaaaaaaak..
....kkkkkkkkk...
"""),
    },
}

# ------------------------------------------------------------------ bodies
BODIES = {
    "std": {
        "down": chargrid("""
..kooowwwwoook..
.kooooccccooook.
.ksoooccccooosk.
..kkddddddddkk..
"""),
        "up": chargrid("""
..kooooooooook..
.kooooooooooook.
.ksoooooooooosk.
..kkddddddddkk..
"""),
        "left": chargrid("""
....kwoooooook..
....koookooook..
....koooosoook..
....kddddddddk..
"""),
    },
    "skirt": {
        "down": chargrid("""
..kooowwwwoook..
.kooooccccooook.
.ksoooccccooosk.
..kddddddddddk..
"""),
        "up": chargrid("""
..kooooooooook..
.kooooooooooook.
.ksoooooooooosk.
..kddddddddddk..
"""),
        "left": chargrid("""
....kwoooooook..
....koookooook..
....koooosoook..
...kddddddddddk.
"""),
    },
    "robe": {
        "down": chargrid("""
..kooowwwwoook..
.kooooccccooook.
.ksoooccccooosk.
..kooooccooook..
"""),
        "up": chargrid("""
..kooooooooook..
.kooooooooooook.
.ksoooooooooosk.
..kooooooooook..
"""),
        "left": chargrid("""
....kwoooooook..
....koookooook..
....koooosoook..
...kooooooooook.
"""),
    },
}

LEGS = {
    "down": [chargrid("""
...klllkklllk...
...kkkk..kkkk...
"""), chargrid("""
...klllk.kkkk...
...kkkk.........
""")],
    "up": [chargrid("""
...klllkklllk...
...kkkk..kkkk...
"""), chargrid("""
...klllk.kkkk...
...kkkk.........
""")],
    "left": [chargrid("""
.....kllllk.....
....kkkkkk......
"""), chargrid("""
....kllkkllk....
...kkkk.kkkk....
"""), chargrid("""
.....kllkllk....
....kkk.kkkk....
""")],
}

ROBE_LEGS = {
    "down": [chargrid("""
..kooooooooook..
..kkkkkkkkkkkk..
"""), chargrid("""
..kooooooooook..
..kkkkkkkkkkk...
""")],
    "up": [chargrid("""
..kooooooooook..
..kkkkkkkkkkkk..
"""), chargrid("""
..kooooooooook..
..kkkkkkkkkkk...
""")],
    "left": [chargrid("""
...kooooooook...
...kkkkkkkkkk...
"""), chargrid("""
...kooooooooook.
..kkkkkkkkkkkk..
"""), chargrid("""
...koooooooook..
...kkkkkkkkkkk..
""")],
}


def _fix_widths():
    for d in BODIES.values():
        for k, g in d.items():
            assert g.shape == (4, 16), (k, g.shape)


_fix_widths()

# ------------------------------------------------------------- accessories
ACCESSORIES = {
    # (direction) -> list of (row, col, letter)
    "glasses": {
        "down": [(7, c, ch) for c, ch in zip(range(4, 12), "kwkkkkwk")],
        "left": [(7, c, ch) for c, ch in zip(range(1, 6), "kkwkk")],
    },
    "beard": {
        "down": [(8, c, "b") for c in range(4, 12)] + [(9, c, "b") for c in range(4, 12)]
        + [(8, 5, "k"), (8, 10, "k")],
        "left": [(8, c, "b") for c in range(2, 8)] + [(9, c, "b") for c in range(3, 8)],
    },
    "moustache": {
        "down": [(9, c, "b") for c in range(5, 11)],
        "left": [(9, c, "b") for c in range(3, 6)],
    },
    "headband": {
        "down": [(5, c, "a") for c in range(2, 14)],
        "up": [(5, c, "a") for c in range(2, 14)] + [(6, 7, "a"), (7, 8, "a"), (6, 8, "a")],
        "left": [(5, c, "a") for c in range(2, 14)] + [(6, 14, "a"), (7, 14, "a")],
    },
    "nursecap": {
        "down": [(1, c, "k") for c in range(5, 11)] + [(2, c, "w") for c in range(5, 11)]
        + [(2, 7, "a"), (2, 8, "a"), (3, 7, "a"), (3, 8, "a")] + [(2, 4, "k"), (2, 11, "k")],
        "up": [(1, c, "k") for c in range(5, 11)] + [(2, c, "w") for c in range(5, 11)],
        "left": [(1, c, "k") for c in range(4, 10)] + [(2, c, "w") for c in range(4, 10)],
    },
    "goggles": {
        "down": [(6, c, ch) for c, ch in zip(range(3, 13), "kwwkkkkwwk")],
        "left": [(6, c, ch) for c, ch in zip(range(1, 5), "kwwk")],
    },
}

# --------------------------------------------------------------- characters
# id: (role colour, head, body, mapping, accessories[, skin])
D = dict  # brevity
CHARACTERS = {
    "player_m": ("red", "cap", "std", D(h=K, a=P, o=P, c=W, d=K, l=K), []),
    "player_f": ("teal", "twintails", "skirt", D(h=K, o=P, c=W, d=P, l=S), []),
    "rival": ("crimson", "spiky", "std", D(h=P, o=K, c=W, d=K, l=K), []),
    "mom": ("brown", "bun", "skirt", D(h=P, o=P, c=W, d=W, l=S), []),
    "professor": ("brown", "short", "std", D(h=W, o=W, c=P, d=P, l=K), []),
    "nurse": ("pink", "long", "skirt", D(h=P, a=P, o=P, c=W, d=W, l=S), ["nursecap"]),
    "clerk": ("blue", "short", "std", D(h=K, o=P, c=W, d=K, l=K), []),
    "youngster": ("orange", "cap", "std", D(h=K, a=P, o=W, c=W, d=P, l=S), []),
    "lass": ("magenta", "long", "skirt", D(h=K, o=W, c=W, d=P, l=S), []),
    "bugcatcher": ("green", "widehat", "std", D(h=K, a=W, c=P, o=W, d=P, l=S), []),
    "hiker": ("brown", "short", "std", D(h=K, o=P, c=P, d=K, l=K, b=K), ["beard"], SKIN_TAN),
    "fisherman": ("navy", "widehat", "std", D(h=K, a=W, c=P, o=P, d=K, l=K), []),
    "swimmer_m": ("blue", "short", "std", D(h=P, o=S, c=S, d=P, l=S), ["goggles"]),
    "swimmer_f": ("cyan", "long", "std", D(h=K, o=P, c=P, d=P, l=S), []),
    "gentleman": ("slate", "tophat", "std", D(h=W, a=K, o=P, c=W, d=P, l=K, b=W), ["moustache"]),
    "beauty": ("magenta", "long", "skirt", D(h=P, o=P, c=W, d=P, l=S), []),
    "scientist": ("cyan", "short", "std", D(h=K, o=W, c=P, d=K, l=K), ["glasses"]),
    "sage": ("tan", "bald", "robe", D(h=K, o=P, c=P), []),
    "blackbelt": ("orange", "short", "std", D(h=K, a=P, o=W, c=W, d=K, l=W), ["headband"], SKIN_TAN),
    "psychic": ("purple", "short", "robe", D(h=P, o=P, c=W), []),
    "cooltrainer_m": ("green", "spiky", "std", D(h=K, o=P, c=W, d=K, l=K), []),
    "cooltrainer_f": ("orange", "long", "std", D(h=P, o=P, c=W, d=K, l=K), []),
    "pokefan": ("yellow", "cap", "std", D(h=K, a=P, o=P, c=W, d=K, l=K), []),
    "ranger": ("lime", "widehat", "std", D(h=K, a=P, c=K, o=P, d=K, l=K), []),
    "oldman": ("tan", "bald", "std", D(h=W, o=P, c=P, d=K, l=K, b=W), ["beard"]),
    "oldwoman": ("indigo", "bun", "skirt", D(h=W, o=P, c=P, d=P, l=S), []),
    "kid": ("yellow", "short", "std", D(h=K, o=P, c=P, d=K, l=S), []),
    "policeman": ("navy", "cap", "std", D(h=K, a=P, o=P, c=W, d=P, l=K), []),
    "sailor": ("blue", "cap", "std", D(h=K, a=W, o=W, c=P, d=P, l=K), []),
    "grunt_m": ("slate", "cap", "std", D(h=K, a=P, o=P, c=W, d=K, l=K), []),
    "grunt_f": ("slate", "long", "skirt", D(h=P, o=P, c=W, d=K, l=K), []),
    "admin": ("indigo", "spiky", "robe", D(h=W, o=P, c=K), []),
    "boss": ("crimson", "short", "robe", D(h=K, o=K, c=P), []),
    "leader1": ("lime", "short", "std", D(h=P, o=P, c=W, d=K, l=K), []),
    "leader2": ("orange", "twintails", "skirt", D(h=P, o=P, c=W, d=P, l=S), []),
    "leader3": ("blue", "spiky", "std", D(h=P, o=W, c=P, d=P, l=K), []),
    "leader4": ("purple", "hood", "robe", D(h=K, a=P, o=P, c=K), []),
    "leader5": ("brown", "bald", "std", D(h=K, o=P, c=W, d=K, l=K, b=K), ["beard"], SKIN_TAN),
    "leader6": ("cyan", "long", "skirt", D(h=W, o=P, c=W, d=P, l=S), []),
    "leader7": ("red", "short", "std", D(h=K, a=P, o=W, c=P, d=P, l=K), ["headband"]),
    "leader8": ("indigo", "long", "robe", D(h=P, o=P, c=W), []),
    "elite1": ("magenta", "bun", "robe", D(h=P, o=P, c=W), []),
    "elite2": ("green", "tophat", "std", D(h=K, a=P, o=P, c=W, d=K, l=K), []),
    "elite3": ("navy", "spiky", "std", D(h=W, o=P, c=K, d=K, l=K), ["glasses"]),
    "elite4": ("gold", "long", "std", D(h=P, o=K, c=P, d=K, l=K), []),
    "champion": ("teal", "short", "robe", D(h=K, o=P, c=W), []),
}

DIRS = ("down", "up", "left")


def resolve(g, mapping):
    out = np.full(g.shape, -1, dtype=np.int8)
    for ch, v in FIXED.items():
        out[g == ch] = v
    for ch in "haocdlb":
        v = mapping.get(ch, {"h": K, "a": P, "o": P, "c": W, "d": K, "l": K, "b": W}[ch])
        out[g == ch] = v
    hv = mapping.get("h", K)
    out[g == "g"] = W if hv == K else hv
    return out


def head_grid(style, d, acc):
    g = HEADS[style][d].copy()
    for name in acc:
        for (r, c, ch) in ACCESSORIES.get(name, {}).get(d, []):
            if g[r, c] != "." or ch == "k":
                g[r, c] = ch
    return g


HIGHLIGHT = {"down": [(3, 4), (3, 5), (4, 3)], "up": [(3, 4), (3, 5), (4, 3), (3, 10)],
             "left": [(3, 6), (3, 7), (4, 5)]}


def hair_highlight(canvas, d):
    """mark a few hair pixels as shine ('g'); only visible on dark hair"""
    for r, c in HIGHLIGHT[d]:
        if canvas[r, c] == "h":
            canvas[r, c] = "g"


def frame(style, body, d, step, mapping, acc):
    """step: 0 stand, 1 step A, 2 step B"""
    canvas = np.full((16, 16), ".", dtype="<U1")
    b = BODIES[body][d]
    canvas[10:14] = b
    legs = ROBE_LEGS if body == "robe" else LEGS
    if d in ("down", "up"):
        lg = legs[d][0 if step == 0 else 1]
        if step == 2:
            lg = lg[:, ::-1]
    else:
        lg = legs[d][step]
    canvas[14:16] = lg
    h = head_grid(style, d, acc)
    m = h != "."
    canvas[:13][m] = h[m]
    hair_highlight(canvas, d)
    a = resolve(canvas, mapping)
    if d in ("down", "up") and step:
        # 1px bob: the whole sprite dips while walking
        a = np.vstack([np.full((1, 16), -1, np.int8), a[:-1]])
        a[15] = np.where(a[15] == -1, -1, a[15])
    return a


def sheet(spec):
    role, style, body, mapping, acc = spec[:5]
    rows = []
    for d in DIRS:
        rows.append([frame(style, body, d, s, mapping, acc) for s in range(3)])
    rows.append([fr[:, ::-1].copy() for fr in rows[2]])
    return rows


def palette_for(spec):
    role = spec[0]
    skin = spec[5] if len(spec) > 5 else SKIN
    return spr(ROLE[role], skin=skin)


def render_sheet(rows, pal):
    img = Image.new("RGBA", (48, 64), (0, 0, 0, 0))
    for r, fr_row in enumerate(rows):
        for c, fr in enumerate(fr_row):
            img.paste(pixlib.to_rgba(fr, pal), (c * 16, r * 16))
    return img


# ---------------------------------------------------------------- bike/surf
def bike_frames(spec):
    role, style, body, mapping, acc = spec[:5]
    rows = []
    for d in DIRS:
        frs = []
        for s in range(3):
            canvas = np.full((16, 16), ".", dtype="<U1")
            canvas[10:14] = BODIES["std"][d]
            if d in ("down", "up"):
                bike = chargrid("""
.kkkkkkkkkkkkkk.
..k....kk....k..
.......kk.......
......kwwk......
......kwwk......
.......kk.......
""" if d == "down" else """
.kkkkkkkkkkkkkk.
..k..kkkkkk..k..
.....kooook.....
......kwwk......
......kwwk......
.......kk.......
""")
                if s:
                    bike[3 + (s - 1), 7] = "k"
                canvas[10:16][bike != "."] = bike[bike != "."]
            else:
                bike = chargrid("""
.....kkkkk......
..kkk.kook......
.k.kkkoook.kkk..
k.kwk.kkk.k.kwk.
k.kkk.....k.kkk.
.k...k...k.k...k
..kkk.....kkk...
""" .replace("k.kwk.kkk.k.kwk.", "kwkwk.kkk.kwkwk."))
                bike = bike[:, :16]
                if s == 1:
                    bike[3:5] = bike[3:5][:, ::-1][:, ::-1]
                    bike[4, 2], bike[4, 12] = "w", "w"
                elif s == 2:
                    bike[3, 2], bike[3, 12] = "k", "k"
                canvas[9:16][bike != "."] = bike[bike != "."]
            h = head_grid(style, d, acc)
            m = h != "."
            hh = np.full((16, 16), ".", dtype="<U1")
            hh[0:13][m] = h[m]
            hh = np.vstack([np.full((0, 16), "."), hh])
            mm = hh != "."
            canvas[mm] = hh[mm]
            frs.append(resolve(canvas, mapping))
        rows.append(frs)
    rows.append([fr[:, ::-1].copy() for fr in rows[2]])
    return rows


SURF = {
    "down": chargrid("""
................
................
................
................
................
................
................
................
..kk........kk..
.kwwkkkkkkkkwwk.
kwwwwwwwwwwwwwwk
kwwkwwwwwwwwkwwk
kwwwwwccwwwwwwwk
.kwwwwwwwwwwwwk.
..kkwwwwwwwwkk..
....kkkkkkkk....
"""),
    "up": chargrid("""
................
................
................
................
................
................
................
................
....kkkkkkkk....
..kkwwwwwwwwkk..
.kwwwwwwwwwwwwk.
kwwwccwwwwccwwwk
kwwwwwwwwwwwwwwk
.kwwwwwwwwwwwwk.
..kkkkwwwwkkkk..
......kkkk......
"""),
    "left": chargrid("""
................
................
................
................
................
................
................
................
.kkk............
kwwwk...kkkkkk..
kwkwwkkkwwwwwwk.
kwwwwwwwwwwwwwwk
.kwwwwwwccwwwwwk
..kwwwwwwwwwwwk.
...kkwwwwwwwkkkk
.....kkkkkkk....
"""),
}


def surf_frames(spec):
    role, style, body, mapping, acc = spec[:5]
    rows = []
    for d in DIRS:
        frs = []
        for s in range(3):
            canvas = np.full((16, 16), ".", dtype="<U1")
            # player's upper body
            canvas[9:12] = BODIES["std"][d][0:3]
            h = head_grid(style, d, acc)
            sh = 1 if s == 1 else 0
            creature = SURF[d].copy()
            if s == 2:
                creature = np.vstack([np.full((1, 16), "."), creature[:-1]])
            m = creature != "."
            hh = np.full((16, 16), ".", dtype="<U1")
            hh[0:13][h != "."] = h[h != "."]
            hh = hh[:, :]
            # rider sits lower into the creature
            rider = np.vstack([np.full((1, 16), "."), canvas[:-1]]) if sh else canvas
            rm = rider != "."
            out = np.full((16, 16), ".", dtype="<U1")
            out[rm] = rider[rm]
            hm = hh != "."
            hshift = np.vstack([np.full((sh, 16), "."), hh[:16 - sh]]) if sh else hh
            hm = hshift != "."
            out[hm] = hshift[hm]
            # creature drawn on top of rider's lower body
            out[m & (np.arange(16)[:, None] >= 10)] = creature[m & (np.arange(16)[:, None] >= 10)]
            # map creature colour: 'c' spots use role colour
            frs.append(resolve(out, dict(mapping, c=mapping.get("c", W) if False else P)))
        rows.append(frs)
    rows.append([fr[:, ::-1].copy() for fr in rows[2]])
    return rows


def build():
    out = os.path.join(pixlib.GFX, "chars")
    for cid, spec in CHARACTERS.items():
        pal = palette_for(spec)
        img = render_sheet(sheet(spec), pal)
        assert img.size == (48, 64)
        pixlib.save_png(img, os.path.join(out, cid + ".png"))
    for pid in ("player_m", "player_f"):
        spec = CHARACTERS[pid]
        pal = palette_for(spec)
        pixlib.save_png(render_sheet(bike_frames(spec), pal), os.path.join(out, pid + "_bike.png"))
        pixlib.save_png(render_sheet(surf_frames(spec), pal), os.path.join(out, pid + "_surf.png"))


if __name__ == "__main__":
    build()
    print("characters:", len(CHARACTERS))
