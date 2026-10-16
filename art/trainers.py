"""Trainer battle portraits -> assets/gfx/trainers/<id>.png (64x64).

Front-facing upper-body portraits in the 4-colour GSC style, built from
layered vector-ish masks evaluated on the pixel grid (no anti-aliasing):
back hair -> torso -> inner shirt -> neck -> head -> hair -> hat -> extras.
After compositing, a 1px outline is drawn on the upper layer wherever two
layer groups meet (or at the silhouette), then face details are stamped.

The character table (palette role, hair/hat style, body, colour mapping,
accessories) is shared with chars.py so overworld sprites and portraits match.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixlib  # noqa: E402
from chars import CHARACTERS, K, W, S, P, palette_for  # noqa: E402

N = 64
Y, X = np.mgrid[0:N, 0:N]
XC, YC = X + 0.5, Y + 0.5

FEMALE_HEADS = {"long", "twintails", "bun"}
OLD = {"oldman", "oldwoman", "sage", "gentleman", "professor", "leader5"}
DEFAULTS = {"h": K, "a": P, "o": P, "c": W, "d": K, "l": K, "b": W}


def ell(cx, cy, rx, ry):
    return ((XC - cx) / rx) ** 2 + ((YC - cy) / ry) ** 2 <= 1.0


def rect(x0, y0, x1, y1):
    return (X >= x0) & (X < x1) & (Y >= y0) & (Y < y1)


def poly(pts):
    """even-odd polygon fill evaluated at pixel centres"""
    inside = np.zeros((N, N), bool)
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        cond = ((y1 > YC) != (y2 > YC))
        with np.errstate(divide="ignore", invalid="ignore"):
            xint = (x2 - x1) * (YC - y1) / (y2 - y1 + 1e-9) + x1
        inside ^= cond & (XC < xint)
    return inside


class Portrait:
    def __init__(self, mapping):
        self.mapping = dict(DEFAULTS, **mapping)
        self.region = np.full((N, N), -1, np.int16)
        self.group = {}
        self.col = np.full((N, N), -1, np.int8)
        self.n = 0

    def c(self, letter):
        if letter in ("k", "w", "s"):
            return {"k": K, "w": W, "s": S}[letter]
        if letter == "p":
            return P
        return self.mapping[letter]

    def layer(self, mask, letter, group=None):
        self.n += 1
        self.group[self.n] = group or f"g{self.n}"
        self.region[mask] = self.n
        self.col[mask] = self.c(letter)
        return self.n

    def outline(self):
        r = self.region
        g = np.vectorize(lambda v: hash(self.group.get(int(v), "none")) if v >= 0 else 0)(r)
        line = np.zeros((N, N), bool)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            rn = np.full((N, N), -2, np.int16)
            gn = np.zeros((N, N), np.int64)
            ys = slice(max(0, dy), N + min(0, dy))
            yd = slice(max(0, -dy), N + min(0, -dy))
            xs = slice(max(0, dx), N + min(0, dx))
            xd = slice(max(0, -dx), N + min(0, -dx))
            rn[yd, xd] = r[ys, xs]
            gn[yd, xd] = g[ys, xs]
            # out of image counts as "same" (no border at the frame edge)
            oob = rn == -2
            rn[oob] = r[oob]
            gn[oob] = g[oob]
            line |= (r >= 0) & ((rn == -1) | ((rn < r) & (gn != g)))
        self.col[line] = K

    def px(self, pts, letter):
        for x, y in pts:
            if 0 <= x < N and 0 <= y < N:
                self.col[y, x] = self.c(letter)

    def dither(self, mask, letter="k"):
        m = mask & ((X + Y) % 2 == 0) & (self.col >= 0) & (self.col != K)
        self.col[m] = self.c(letter)


# ------------------------------------------------------------------ pieces
HCX, HCY, HRX, HRY = 32, 24, 14, 15   # head ellipse (centre / radii)
EYE_Y = HCY + 1                         # top row of the eyes
CHIN = HCY + HRY                        # approx. bottom of the face
SHOULDER = HCY + 17                     # top of the torso


def head_masks():
    head = ell(HCX, HCY, HRX, HRY) | ell(HCX, HCY + 4, HRX - 1.5, HRY - 3)
    ears = ell(HCX - HRX, HCY + 3, 2.5, 4) | ell(HCX + HRX, HCY + 3, 2.5, 4)
    return head, ears


def fringe(y_base, depth=3, period=5, phase=0):
    """mask of hair above a saw-tooth fringe line"""
    saw = ((XC + phase) % period) / period
    line = y_base + depth * (1 - np.abs(saw * 2 - 1))
    return YC < line


def hair_masks(style):
    """returns (back_hair, front_hair)"""
    cap_top = ell(HCX, HCY - 1, HRX + 1.5, HRY + 1.5)
    back = np.zeros((N, N), bool)
    front = np.zeros((N, N), bool)
    if style in ("short", "spiky", "long", "twintails", "bun", "cap", "widehat", "tophat"):
        front = cap_top & fringe(HCY - 7, depth=4, period=7, phase=3)
        # sideburns
        front |= cap_top & (YC < HCY + 3) & ((XC < HCX - HRX + 3) | (XC > HCX + HRX - 3))
    if style == "spiky":
        tips = ((HCX - 14, HCY - 14), (HCX - 7, HCY - 21), (HCX, HCY - 23),
                (HCX + 7, HCY - 21), (HCX + 14, HCY - 14))
        for bx, by in tips:
            lean = (bx - HCX) * 0.25
            front |= poly([(bx - 6 - lean, by + 12), (bx + lean, by), (bx + 6 - lean, by + 12)])
        front |= poly([(HCX - HRX - 1, HCY + 1), (HCX - HRX - 7, HCY - 7), (HCX - HRX + 4, HCY - 8)])
        front |= poly([(HCX + HRX + 1, HCY + 1), (HCX + HRX + 7, HCY - 7), (HCX + HRX - 4, HCY - 8)])
    if style == "long":
        back = (ell(HCX, HCY + 4, HRX + 5, HRY + 6) | rect(HCX - HRX - 5, HCY, HCX + HRX + 5, HCY + 26)) \
            & (YC < HCY + 27)
        back &= ~((YC > HCY + 17) & (np.abs(XC - HCX) < 8))
    if style == "twintails":
        back = ell(HCX - HRX - 4, HCY + 11, 5.5, 13) | ell(HCX + HRX + 4, HCY + 11, 5.5, 13)
        back |= ell(HCX - HRX - 1, HCY - 5, 4.5, 4.5) | ell(HCX + HRX + 1, HCY - 5, 4.5, 4.5)
    if style == "bun":
        back = ell(HCX, HCY - HRY - 2, 7, 5.5)
    if style == "bald":
        front = ell(HCX, HCY - 1, HRX + 1.5, HRY + 1.5) & (YC > HCY - 5) & (YC < HCY + 5) \
            & ((XC < HCX - HRX + 3) | (XC > HCX + HRX - 3))
    return back, front


def hat_masks(style):
    if style == "cap":
        crown = ell(HCX, HCY - 4, HRX + 2, HRY - 1) & (YC < HCY - 6)
        brim = ell(HCX, HCY - 7, HRX + 4, 4.5) & (YC > HCY - 7.5)
        return crown | brim, crown, brim
    if style == "widehat":
        crown = ell(HCX, HCY - 9, HRX - 2, 10) & (YC < HCY - 8)
        brim = ell(HCX, HCY - 8, HRX + 12, 5)
        return crown | brim, crown, brim
    if style == "tophat":
        crown = rect(HCX - 10, 0, HCX + 10, HCY - 9)
        brim = ell(HCX, HCY - 9, HRX + 6, 3.5)
        return crown | brim, crown, brim
    if style == "hood":
        hood = ell(HCX, HCY + 1, HRX + 5, HRY + 5) | (ell(HCX, 70, 30, 32) & (YC > SHOULDER - 2))
        return hood, hood, None
    return None, None, None


def torso_masks(body):
    torso = ell(HCX, 74, 32, 35) & (YC > SHOULDER)
    if body == "robe":
        torso = ell(HCX, 72, 29, 33) & (YC > SHOULDER)
    neck = rect(HCX - 6, HCY + 9, HCX + 6, SHOULDER + 4)
    vee = (np.abs(XC - HCX) < (YC - SHOULDER) * 0.32 + 3) & torso
    if body == "robe":
        vee = (np.abs(XC - HCX) < 3 + (YC - SHOULDER) * 0.08) & torso
    return torso, neck, vee


def eyes(p, female, old):
    y = EYE_Y
    lx, rx = HCX - 8, HCX + 5
    if old:
        for x in (lx, rx):
            p.px([(x, y + 2), (x + 1, y + 2), (x + 2, y + 2), (x - 1, y + 1), (x + 3, y + 1)], "k")
        return
    for x in (lx, rx):
        p.px([(x + i, y + j) for i in range(3) for j in range(5)], "k")
        p.px([(x + 1, y + 1)], "w")
    if female:
        p.px([(lx - 1, y), (lx - 2, y - 1), (rx + 3, y), (rx + 4, y - 1)], "k")


def face(p, cid, style, female, old):
    eyes(p, female, old)
    by = EYE_Y - 4
    lx, rx = HCX - 9, HCX + 5
    if female:
        p.px([(lx + 1 + i, by) for i in range(3)] + [(rx + i, by) for i in range(3)], "k")
    else:
        p.px([(lx + i, by + (1 if i == 4 else 0)) for i in range(5)]
             + [(rx + i, by + (1 if i == 0 else 0)) for i in range(5)], "k")
    ny = EYE_Y + 6
    p.px([(HCX + 1, ny), (HCX + 1, ny + 1), (HCX, ny + 1)], "k")
    my = EYE_Y + 10
    if female:
        p.px([(HCX - 1, my), (HCX, my), (HCX + 1, my)], "k")
    else:
        p.px([(HCX - 3 + i, my) for i in range(6)], "k")
    if old:
        p.px([(HCX - 10, ny), (HCX - 9, ny + 1), (HCX + 10, ny), (HCX + 9, ny + 1)], "k")


def accessory(p, name, mapping):
    if name == "glasses":
        for x in (HCX - 10, HCX + 3):
            y0 = EYE_Y - 2
            p.px([(x + i, y0) for i in range(8)] + [(x + i, y0 + 8) for i in range(8)]
                 + [(x, y0 + j) for j in range(9)] + [(x + 7, y0 + j) for j in range(9)], "k")
        p.px([(HCX - 2 + i, EYE_Y) for i in range(5)], "k")
    if name in ("beard", "moustache"):
        if name == "beard":
            m = ell(HCX, CHIN - 4, 11, 8) & (YC > EYE_Y + 7)
            m &= ~ell(HCX, EYE_Y + 10, 3.5, 1.5)
        else:
            m = ell(HCX, EYE_Y + 8.5, 6, 2)
        p.region[m] = 99
        p.col[m] = p.c("b")
        edge = m & ~(np.roll(m, 1, 0) & np.roll(m, -1, 0) & np.roll(m, 1, 1) & np.roll(m, -1, 1))
        p.col[edge] = K
        if name == "beard":
            p.px([(HCX - 3 + i, EYE_Y + 10) for i in range(6)], "k")
    if name == "headband":
        y0 = HCY - 9
        m = ell(HCX, HCY - 1, HRX + 1.5, HRY + 1.5) & (YC >= y0) & (YC < y0 + 4)
        p.col[m] = p.c("a")
        p.col[m & ((Y == y0) | (Y == y0 + 3))] = K
        x = HCX + HRX + 1
        p.px([(x, y0 + 3), (x + 1, y0 + 4), (x + 2, y0 + 5), (x + 1, y0 + 6), (x + 2, y0 + 7)], "a")
    if name == "goggles":
        y0 = HCY - 10
        for x in (HCX - 11, HCX + 2):
            m = rect(x, y0, x + 9, y0 + 6)
            p.col[m] = W
            p.col[m & ((Y == y0) | (Y == y0 + 5) | (X == x) | (X == x + 8))] = K
        p.px([(HCX - 2 + i, y0 + 2) for i in range(4)], "k")
    if name == "nursecap":
        y0 = HCY - HRY - 4
        m = rect(HCX - 7, y0, HCX + 7, y0 + 8)
        p.col[m] = W
        p.col[m & ((Y == y0) | (Y == y0 + 7) | (X == HCX - 7) | (X == HCX + 6))] = K
        p.px([(HCX - 1, y0 + 2), (HCX, y0 + 2), (HCX - 2, y0 + 3), (HCX - 1, y0 + 3), (HCX, y0 + 3),
              (HCX + 1, y0 + 3), (HCX - 2, y0 + 4), (HCX - 1, y0 + 4), (HCX, y0 + 4), (HCX + 1, y0 + 4),
              (HCX - 1, y0 + 5), (HCX, y0 + 5)], "a")


def portrait(cid, spec):
    role, style, body, mapping = spec[:4]
    acc = spec[4]
    female = style in FEMALE_HEADS or cid in ("beauty", "lass", "nurse", "swimmer_f", "grunt_f",
                                              "cooltrainer_f", "player_f")
    old = cid in OLD
    p = Portrait(mapping)
    back, front = hair_masks(style)
    hat, crown, brim = hat_masks(style)
    torso, neck, vee = torso_masks(body)
    head, ears = head_masks()

    if style == "hood":
        p.layer(hat, "a", "hood")
    p.layer(back, "h", "hairback")
    p.layer(torso, "o", "torso")
    p.layer(vee, "c", "inner")
    neck_id = p.layer(neck, "s", "skin")
    p.layer(ears, "s", "ears")
    p.layer(head, "s", "skin")
    if style != "hood":
        p.layer(front, "h", "hair")
    else:
        rim = ell(HCX, HCY + 1, HRX + 5, HRY + 5) & ~ell(HCX, HCY + 3, HRX - 1, HRY - 1)
        rim &= YC < HCY + 14
        p.layer(rim, "a", "hoodrim")
    if hat is not None and style != "hood":
        p.layer(crown, "a", "crown")
        p.layer(brim, "a", "brim")
        if style == "widehat":
            band = ell(HCX, HCY - 9, HRX - 2, 10) & (YC > HCY - 13) & ~brim
            p.layer(band, "c" if mapping.get("c", W) != mapping.get("a", P) else "k", "band")
        if style == "tophat":
            p.layer(rect(HCX - 10, HCY - 14, HCX + 10, HCY - 11), "w", "band")
    p.outline()

    # ---- details
    shade = torso & (XC > HCX + 13) & ~ell(HCX - 5, 74, 32, 35)
    p.dither(shade)
    p.col[(p.region == neck_id) & (Y == HCY + HRY + 1) & (p.col == S)] = K
    if style not in ("bald", "hood"):
        sh = front & ~ell(HCX - 4, HCY - 4, HRX + 1, HRY + 1)
        p.dither(sh)
        if mapping.get("h", K) in (K, P) and style not in ("cap", "widehat", "tophat"):
            p.px([(HCX - 7 + i, HCY - 12 - (i // 3)) for i in range(6)], "w")
    if style == "cap":
        y0 = HCY - 13
        p.px([(HCX - 1, y0), (HCX, y0), (HCX - 2, y0 + 1), (HCX - 1, y0 + 1), (HCX, y0 + 1), (HCX + 1, y0 + 1),
              (HCX - 2, y0 + 2), (HCX - 1, y0 + 2), (HCX, y0 + 2), (HCX + 1, y0 + 2), (HCX - 1, y0 + 3), (HCX, y0 + 3)], "w")
    for x in (HCX - 18, HCX + 17):
        for y in range(SHOULDER + 12, 64):
            if p.col[y, x] not in (K, -1):
                p.col[y, x] = K
    if body == "std" and mapping.get("c", W) == W and mapping.get("o", P) != W:
        for y in (SHOULDER + 8, SHOULDER + 14, SHOULDER + 20):
            p.px([(HCX, y)], "k")
    face(p, cid, style, female, old)
    for a in acc:
        accessory(p, a, mapping)
    return p.col


def back_portrait(cid, spec):
    """Player seen from behind for the battle screen (bigger, cropped)."""
    role, style, body, mapping = spec[:4]
    p = Portrait(mapping)
    cx, cy, rx, ry = 34, 26, 14, 15
    torso = ell(cx, 74, 32, 34) & (YC > 40)
    head = ell(cx, cy, rx, ry)
    neck = rect(cx - 6, cy + 8, cx + 6, 46)
    p.layer(torso, "o", "torso")
    p.layer(neck, "s", "skin")
    p.layer(ell(cx - rx, cy + 3, 2.5, 4) | ell(cx + rx, cy + 3, 2.5, 4), "s", "ears")
    p.layer(head, "s", "skin")
    hair = ell(cx, cy - 1, rx + 1.5, ry + 0.5) & (YC < cy + 10)
    if style == "twintails":
        p.layer(ell(cx - rx - 4, cy + 12, 5, 12) | ell(cx + rx + 4, cy + 12, 5, 12), "h", "tails")
    p.layer(hair, "h", "hair")
    if style == "cap":
        crown = ell(cx, cy - 3, rx + 2, ry - 1) & (YC < cy - 2)
        band = ell(cx, cy - 3, rx + 2, ry - 1) & (YC >= cy - 4) & (YC < cy - 2)
        p.layer(crown, "a", "crown")
        p.layer(band, "w", "band")
    p.outline()
    p.dither(torso & (XC > cx + 14) & ~ell(cx - 5, 74, 32, 34))
    p.dither(hair & ~ell(cx - 4, cy - 4, rx, ry))
    for x in (cx - 18, cx + 17):
        for y in range(52, 64):
            if p.col[y, x] not in (K, -1):
                p.col[y, x] = K
    return p.col


def build():
    out = os.path.join(pixlib.GFX, "trainers")
    for cid, spec in CHARACTERS.items():
        a = portrait(cid, spec)
        img = pixlib.to_rgba(a, palette_for(spec))
        assert img.size == (64, 64)
        pixlib.save_png(img, os.path.join(out, cid + ".png"))
    for pid in ("player_m", "player_f"):
        spec = CHARACTERS[pid]
        img = pixlib.to_rgba(back_portrait(pid, spec), palette_for(spec))
        pixlib.save_png(img, os.path.join(out, pid + "_back.png"))


if __name__ == "__main__":
    build()
