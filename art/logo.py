"""Title logo -> assets/gfx/ui/title_logo.png (224x64).

"CRYSTALLINE" in chunky faceted letters (pixel8 glyph shapes scaled x3 with a
2px outline, a light upper facet and a darker lower facet) under a cluster of
three crystal shards, plus a few sparkles. Original artwork.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import font  # noqa: E402
import pixlib  # noqa: E402
from palettes import _c  # noqa: E402

W, H = 224, 64
NAVY = _c("182860")
BLUE = _c("3870D0")
CYAN = _c("70C8F0")
PALE = _c("C0F0F8")
WHITE = _c("F8F8F8")


def grow(m, r=1):
    out = m.copy()
    for _ in range(r):
        g = out.copy()
        g[1:, :] |= out[:-1, :]
        g[:-1, :] |= out[1:, :]
        g[:, 1:] |= out[:, :-1]
        g[:, :-1] |= out[:, 1:]
        out = g
    return out


def build_logo():
    text = "CRYSTALLINE"
    k = 3
    letters = []
    for ch in text:
        g = font.glyph(ch)[:6]           # cap rows only
        letters.append(np.kron(g, np.ones((k, k), bool)))
    gap = 2
    tw = sum(l.shape[1] for l in letters) + gap * (len(letters) - 1)
    th = 6 * k
    mask = np.zeros((H, W), bool)
    x = (W - tw) // 2
    y0 = 36
    for l in letters:
        mask[y0:y0 + th, x:x + l.shape[1]] |= l
        x += l.shape[1] + gap
    rgba = np.zeros((H, W, 4), np.uint8)
    Y, X = np.mgrid[0:H, 0:W]

    def paint(m, c):
        rgba[m] = (*c, 255)

    # crystal shards (behind the text)
    shards = np.zeros((H, W), bool)
    light = np.zeros((H, W), bool)
    for cx, top, hw, base in ((112, 2, 13, 40), (92, 12, 9, 40), (132, 12, 9, 40)):
        # diamond-ish shard: pointed top, straight sides
        for yy in range(top, base):
            t = (yy - top) / max(1, (hw * 1.1))
            half = int(min(hw, t * hw)) if yy - top < hw * 1.1 else hw
            shards[yy, cx - half:cx + half + 1] = True
            light[yy, cx - half:cx + 1] = True
    shard_edge = grow(shards, 2) & ~shards
    paint(shard_edge, NAVY)
    paint(shards & light, PALE)
    paint(shards & ~light, CYAN)
    # facet lines on the shards
    for cx, top in ((112, 2), (92, 12), (132, 12)):
        for yy in range(top + 6, 40, 1):
            pass
        rgba[top + 3:40, cx] = (*BLUE, 255)
    # text with outline and facets
    out2 = grow(mask, 2) & ~mask
    shadow = np.zeros_like(mask)
    shadow[3:, 2:] = grow(mask, 2)[:-3, :-2]
    paint(shadow & ~grow(mask, 2), BLUE)
    paint(out2, NAVY)
    upper = mask & (Y < y0 + th // 2)
    lower = mask & (Y >= y0 + th // 2)
    paint(upper, WHITE)
    paint(lower, CYAN)
    paint(mask & (Y == y0 + th // 2), PALE)
    paint(mask & (Y >= y0 + th - 3), BLUE)
    # sparkles
    for sx, sy in ((20, 18), (204, 14), (160, 6), (60, 8)):
        for d in range(-3, 4):
            a = 255
            rgba[sy, sx + d] = (*WHITE, a) if abs(d) < 2 else (*PALE, a)
            rgba[sy + d, sx] = (*WHITE, a) if abs(d) < 2 else (*PALE, a)
    return Image.fromarray(rgba, "RGBA")


def build():
    img = build_logo()
    assert img.size == (W, H)
    pixlib.save_png(img, os.path.join(pixlib.GFX, "ui", "title_logo.png"))


if __name__ == "__main__":
    build()
