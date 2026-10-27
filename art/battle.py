"""Battle backdrops and platforms -> assets/gfx/battle/*.png (GBA spec section 5, phase P2).

bg_<env>.png               256x160 (area above the text box): sky above a horizon at y 72, ground below.
platform_foe_<env>.png     112x24, platform_player_<env>.png 128x24 (elliptical top face, rim, front band).
Colours come only from palettes.BATTLE_ENV (sky + ground = 8 colours per backdrop, platform = 4). Night is a palette
swap done by BattleScreen (palettes.night_variant factors), never a second picture. Detail stays out of the HUD
areas: foe HUD x 8-120 / y 4-38, player HUD x 136-249 / y 96-149 (docs/ART_STYLE.md, Battle backdrops).
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pixlib  # noqa: E402
from palettes import BATTLE_ENV  # noqa: E402

OUT = os.path.join(pixlib.GFX, "battle")
W, H, HORIZON = 256, 160, 72
# Eleven backdrops (spec section 5); the palette each one uses comes from BATTLE_ENV.
ENVS = ("grass", "tall_grass", "forest", "cave", "water", "snow", "sand", "indoor", "building", "gym", "league")
PALETTE_OF = {"gym": "gym_stone"}


def _dither(y, x):
    return (x + y) % 2 == 0


def _bands(a, sky, ground):
    """Sky in three bands (lightest at the top) and ground in two, with a checker dither on each seam."""
    for y in range(HORIZON):
        idx = 0 if y < 20 else 1 if y < 44 else 2
        seam = y in (19, 43)
        for x in range(W):
            a[y, x] = sky[min(idx + 1, 3) if seam and _dither(y, x) else idx]
    for y in range(HORIZON, H):
        idx = 1 if y < 112 else 0
        seam = y == 111
        for x in range(W):
            a[y, x] = ground[0 if seam and _dither(y, x) else idx]
    a[HORIZON, :] = ground[2]
    a[HORIZON + 1, ::2] = ground[2]


def _tri(a, cx, base, h, half, c, up=True):
    for i in range(h):
        w = int(half * (i + 1) / h)
        y = base - i if up else base + i
        a[y, max(0, cx - w):cx + w + 1] = c


def _trees(a, ground, dense):
    step = 14 if dense else 22
    for i, cx in enumerate(range(6, W, step)):
        h = 14 + (i * 5) % 9 if dense else 10 + (i * 3) % 5
        _tri(a, cx, HORIZON, h, 6 if dense else 5, ground[2])
        a[HORIZON - 1:HORIZON + 1, cx - 1:cx + 2] = ground[3]


def _decor(env, a, sky, ground):
    if env in ("grass", "tall_grass"):
        # low hedge row on the horizon; tall grass adds tufts on the ground
        for x in range(0, W, 2):
            a[HORIZON - 1 - (x // 2) % 3, x:x + 2] = ground[2]
        if env == "tall_grass":
            for y in range(HORIZON + 6, H - 4, 12):
                for x in range((y // 4) % 8, W, 16):
                    a[y:y + 3, x] = ground[2]
                    a[y + 1:y + 3, x + 2] = ground[2]
    elif env == "forest":
        _trees(a, ground, True)
    elif env == "cave":
        for cx in range(8, W, 18):  # stalactites hang from the top edge
            _tri(a, cx, 0, 6 + (cx * 7) % 7, 3, sky[3], up=False)
        for cx in range(150, W, 26):  # rocks on the horizon, right of the foe HUD
            _tri(a, cx, HORIZON, 6, 6, ground[2])
    elif env == "water":
        for y in range(HORIZON + 4, H - 20, 8):  # wave dashes
            for x in range((y * 3) % 24, W, 24):
                a[y, x:x + 6] = ground[0]
        for cx in range(160, 250, 30):  # far shore
            _tri(a, cx, HORIZON, 5, 14, ground[1])
    elif env == "snow":
        _trees(a, ground, False)
        for y in range(HORIZON + 6, H - 6, 10):
            for x in range((y * 5) % 17, W, 17):
                a[y, x] = ground[2]
    elif env == "sand":
        for cx in (170, 230, 40):  # dunes
            for dx in range(-22, 23):
                a[HORIZON - max(0, 8 - abs(dx) // 3), cx + dx] = ground[2] if abs(dx) < 22 else a[HORIZON, 0]
        for y in range(HORIZON + 6, H - 6, 11):
            for x in range((y * 7) % 19, W, 19):
                a[y, x:x + 3] = ground[1]
    elif env in ("indoor", "building", "gym", "league"):
        # back wall to y 72: vertical panels, a rail, a skirting line; floor lines below
        for x in range(0, W, 32):
            a[0:HORIZON, x] = sky[2]
        a[HORIZON - 18, :] = sky[3] if env != "league" else sky[2]
        if env == "league":
            for x in range(40, W, 64):  # pillars right of / between the HUD zones
                a[0:HORIZON, x:x + 6] = sky[2]
                a[0:HORIZON, x + 6] = sky[3]
        if env == "gym":
            for x in range(0, W, 16):
                if (x // 16) % 2 == 0:
                    a[HORIZON - 6:HORIZON, x:x + 16] = ground[1]
        step = 32 if env == "indoor" else 24
        for x in range(-HORIZON, W, step):  # converging floor seams
            for y in range(HORIZON + 1, H):
                px = x + (y - HORIZON) * (1 if x < W // 2 else -1) // 3
                if 0 <= px < W and (y - HORIZON) % 2 == 0:
                    a[y, px] = ground[2]
        for y in range(HORIZON + 14, H, 24):
            a[y, ::2] = ground[1]


def backdrop(env):
    pal = BATTLE_ENV[PALETTE_OF.get(env, env)]
    a = np.zeros((H, W, 3), np.uint8)
    _bands(a, pal["sky"], pal["ground"])
    _decor(env, a, pal["sky"], pal["ground"])
    rgba = np.dstack([a, np.full((H, W), 255, np.uint8)])
    return Image.fromarray(rgba, "RGBA")


def platform(env, w, h=24):
    """Elliptical platform: top face indices 0-1, 1 px index 2 rim, 2 px index 3 front band, transparent outside."""
    p = BATTLE_ENV[PALETTE_OF.get(env, env)]["platform"]
    rgba = np.zeros((h, w, 4), np.uint8)
    cx, rx, ry, top = w / 2, w / 2 - 1, 8.5, 1
    for y in range(h):
        for x in range(w):
            ty = y - top
            def inside(dy):
                return ((x + 0.5 - cx) / rx) ** 2 + ((ty - dy + 0.5 - ry) / ry) ** 2 <= 1.0
            if inside(0):
                dx = (x + 0.5 - cx) / rx
                dy = (ty + 0.5 - ry) / ry
                r2 = dx * dx + dy * dy
                c = p[0] if r2 < 0.55 or dy < -0.35 else p[1]
                if r2 > 0.85:
                    c = p[2]
                rgba[y, x] = (*c, 255)
            elif inside(-3):  # front band hangs 3 px below the top face
                rgba[y, x] = (*p[3], 255)
    # 1 px rim on the lower edge of the top face, index 2
    for x in range(w):
        col = [y for y in range(h) if rgba[y, x, 3] and tuple(rgba[y, x, :3]) != p[3]]
        if col:
            rgba[col[-1], x, :3] = p[2]
    return Image.fromarray(rgba, "RGBA")


def build():
    os.makedirs(OUT, exist_ok=True)
    for env in ENVS:
        pixlib.save_png(backdrop(env), os.path.join(OUT, f"bg_{env}.png"))
        pixlib.save_png(platform(env, 112), os.path.join(OUT, f"platform_foe_{env}.png"))
        pixlib.save_png(platform(env, 128), os.path.join(OUT, f"platform_player_{env}.png"))


if __name__ == "__main__":
    build()
