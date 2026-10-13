"""Preview renders (contact sheets and mock scenes) -> art/previews/*.png

Run after the asset generators; it reads the generated PNG/JSON files so the
previews show exactly what the game will load.
"""
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import font  # noqa: E402
import pixlib  # noqa: E402

GFX = pixlib.GFX
OUT = pixlib.PREVIEWS
BG = (232, 232, 224, 255)
INK = (24, 24, 24)


class Tileset:
    def __init__(self):
        self.img = Image.open(os.path.join(GFX, "tiles", "world.png")).convert("RGBA")
        with open(os.path.join(GFX, "tiles", "world.json"), encoding="utf-8") as f:
            self.meta = json.load(f)
        self.cols = self.meta["columns"]

    def tile(self, name, frame=0):
        e = self.meta["tiles"][name]
        idx = e.get("frames", [e["index"]])[frame % len(e.get("frames", [e["index"]]))]
        x, y = (idx % self.cols) * 16, (idx // self.cols) * 16
        return self.img.crop((x, y, x + 16, y + 16))


def label(img, text, x, y, cw=6, colour=INK):
    return font.draw_text_rgba(img, text, x, y, colour, cell_w=cw)


# ----------------------------------------------------------------- autotile
def autotile_path(is_p, x, y):
    n, s, w, e = is_p(x, y - 1), is_p(x, y + 1), is_p(x - 1, y), is_p(x + 1, y)
    if not n and not w:
        return "path_corner_nw"
    if not n and not e:
        return "path_corner_ne"
    if not s and not w:
        return "path_corner_sw"
    if not s and not e:
        return "path_corner_se"
    for ok, nm in ((n, "n"), (s, "s"), (w, "w"), (e, "e")):
        if not ok:
            return "path_edge_" + nm
    return "path"


def autotile_water(is_w, x, y):
    n, s, w, e = is_w(x, y - 1), is_w(x, y + 1), is_w(x - 1, y), is_w(x + 1, y)
    if not n and not w:
        return "water_nw"
    if not n and not e:
        return "water_ne"
    if not s and not w:
        return "water_sw"
    if not s and not e:
        return "water_se"
    for ok, nm in ((n, "n"), (s, "s"), (w, "w"), (e, "e")):
        if not ok:
            return "water_" + nm
    for dx, dy, nm in ((1, -1, "ine"), (-1, -1, "inw"), (1, 1, "ise"), (-1, 1, "isw")):
        if not is_w(x + dx, y + dy):
            return "water_" + nm
    return "water"


PREFABS = {
    "house": [["roof_l_top", "roof_m_top", "roof_m_top", "roof_r_top"],
              ["roof_l", "roof_m", "roof_m", "roof_r"],
              ["wall_l", "wall_window", "door", "wall_r"]],
    "house2": [["roof2_l", "roof2_m", "roof2_m", "roof2_r"],
               ["roof2_l", "roof2_m", "roof2_m", "roof2_r"],
               ["wall_l", "door", "wall_window", "wall_r"]],
    "center": [["center_roof_l", "center_roof_m", "center_roof_m", "center_roof_m", "center_roof_r"],
               ["center_roof_l", "center_roof_m", "center_sign", "center_roof_m", "center_roof_r"],
               ["center_wall_l", "wall_window", "center_door", "wall_window", "center_wall_r"]],
    "mart": [["mart_roof_l", "mart_roof_m", "mart_roof_m", "mart_roof_r"],
             ["mart_roof_l", "mart_sign", "mart_roof_m", "mart_roof_r"],
             ["mart_wall_l", "wall_window", "mart_door", "mart_wall_r"]],
    "gym": [["gym_roof_l", "gym_roof_m", "gym_roof_m", "gym_roof_m", "gym_roof_m", "gym_roof_r"],
            ["gym_roof_l", "gym_roof_m", "gym_sign", "gym_roof_m", "gym_roof_m", "gym_roof_r"],
            ["gym_wall_l", "gym_window", "gym_wall_m", "gym_wall_m", "gym_window", "gym_wall_r"],
            ["gym_wall_l", "gym_wall_m", "gym_door", "gym_wall_m", "gym_wall_m", "gym_wall_r"]],
}

LEGEND = {
    "T": "tree", ",": "grass", ";": "grass_alt", "G": "tall_grass", "f": "flowers",
    "s": "sign", "L": "ledge_s", "b": "bush", "r": "rock", "B": "boulder",
    "F": "fence_h", "c": "cut_tree", "S": "sand", "=": "bridge_h",
}

TOWN = """
TTTTTTTTTTTTTTTT
T,,,,,,pp,,,,,,T
T,,,,,,pp,,,,,,T
T,,,,,,pp,,,,,,T
T,,,,,,pp,,,,,,T
T,f,s,pppppppp,T
T,ff,,pppppppp,T
T~~~~,,pp,,GGGGT
T~~~~~,pp,,GGGGT
T~~~~~,pp,,GGGGT
T~~~~,,pp,,rGGGT
TLLLLL,pp,LLLLLT
T,;,,,,pp,,,;,,T
TTTTTTTppTTTTTTT
"""


def compose_map(ts, text, stamps=(), frame=0):
    rows = [r.strip() for r in text.strip().splitlines()]
    H, W = len(rows), len(rows[0])
    img = Image.new("RGBA", (W * 16, H * 16), (0, 0, 0, 255))

    def at(x, y):
        if 0 <= y < H and 0 <= x < W:
            return rows[y][x]
        return None

    def is_p(x, y):
        c = at(x, y)
        return c == "p" or c is None

    def is_w(x, y):
        c = at(x, y)
        return c == "~" or c is None

    for y in range(H):
        for x in range(W):
            c = rows[y][x]
            if c == "p":
                nm = autotile_path(is_p, x, y)
            elif c == "~":
                nm = autotile_water(is_w, x, y)
            else:
                nm = LEGEND.get(c, "grass")
            img.paste(ts.tile(nm, frame), (x * 16, y * 16))
    for name, tx, ty in stamps:
        for j, row in enumerate(PREFABS[name]):
            for i, nm in enumerate(row):
                img.paste(ts.tile(nm, frame), ((tx + i) * 16, (ty + j) * 16))
    return img


def char_frame(cid, row, col, folder="chars"):
    p = os.path.join(GFX, folder, cid + ".png")
    if not os.path.exists(p):
        return None
    im = Image.open(p).convert("RGBA")
    return im.crop((col * 16, row * 16, col * 16 + 16, row * 16 + 16))


def put(img, spr, x, y):
    if spr is not None:
        img.alpha_composite(spr, (x, y))


def scene_town(ts, frame=0):
    img = compose_map(ts, TOWN, [("house", 1, 1), ("center", 9, 2)])
    for cid, r, c, x, y in (("player_m", 0, 0, 7, 6), ("rival", 2, 1, 10, 5),
                            ("youngster", 1, 0, 4, 9), ("lass", 3, 0, 12, 12),
                            ("oldman", 0, 0, 4, 6)):
        put(img, char_frame(cid, r, c), x * 16, y * 16 - 4)
    for oid, x, y in (("itemball", 13, 7),):
        p = os.path.join(GFX, "objects", oid + ".png")
        if os.path.exists(p):
            put(img, Image.open(p).convert("RGBA"), x * 16, y * 16)
    # text box at the bottom like the game
    frame_p = os.path.join(GFX, "ui", "frame.png")
    if os.path.exists(frame_p):
        box = nine_slice(Image.open(frame_p).convert("RGBA"), 256, 48)
        img.alpha_composite(box, (0, 176))
        img = font.draw_text_rgba(img, "Welcome to TESSERA! This", 8, 186, INK)
        img = font.draw_text_rgba(img, "island hides crystals.", 8, 202, INK)
    return img


INDOOR = """
TTTTTTTTTTTTTTTT
"""


def scene_indoor(ts):
    lay = [
        ["wall_in_top"] * 3 + ["window_in", "wall_in_top", "wall_in_top", "window_in"] + ["wall_in_top"] * 3,
        ["bookshelf", "bookshelf", "wall_in", "wall_in", "tv", "wall_in", "wall_in", "pc", "wall_in", "stairs_in_up"],
        ["floor_wood"] * 10,
        ["plant", "floor_wood", "floor_wood", "table", "table", "floor_wood", "floor_wood", "floor_wood", "bed_top", "floor_wood"],
        ["floor_wood", "floor_wood", "chair", "floor_wood", "floor_wood", "chair", "floor_wood", "floor_wood", "bed_bottom", "floor_wood"],
        ["floor_wood", "floor_carpet", "floor_carpet", "floor_carpet", "floor_carpet", "floor_wood", "floor_wood", "floor_wood", "floor_wood", "plant"],
        ["floor_wood", "floor_carpet", "floor_carpet", "floor_carpet", "floor_carpet", "floor_wood", "floor_wood", "floor_wood", "floor_wood", "floor_wood"],
        ["floor_wood", "floor_wood", "floor_wood", "floor_wood", "rug_l", "rug_r", "floor_wood", "floor_wood", "floor_wood", "floor_wood"],
    ]
    lay2 = [
        ["wall_in_top"] * 6,
        ["healing_machine", "wall_in", "pc", "wall_in", "mart_shelf", "mart_shelf"],
        ["counter", "counter", "counter", "floor_tile", "floor_tile", "floor_tile"],
        ["floor_tile"] * 6,
        ["floor_tile", "floor_tile", "rug_mat", "floor_tile", "mart_shelf", "mart_shelf"],
        ["floor_tile"] * 6,
        ["cave_wall_top", "cave_wall_top", "cave_wall", "cave_wall", "cave_ladder", "cave_floor"],
        ["cave_floor", "cave_rock", "cave_floor", "cave_water", "cave_water", "cave_floor"],
    ]
    img = Image.new("RGBA", (256, 224 - 96), (0, 0, 0, 255))
    for j, row in enumerate(lay):
        for i, nm in enumerate(row):
            img.paste(ts.tile(nm), (i * 16, j * 16))
    for j, row in enumerate(lay2):
        for i, nm in enumerate(row):
            img.paste(ts.tile(nm), (160 + i * 16, j * 16))
    put(img, char_frame("mom", 0, 0), 5 * 16, 4 * 16 - 4)
    put(img, char_frame("player_f", 1, 1), 4 * 16, 6 * 16 - 4)
    put(img, char_frame("nurse", 0, 0), 160 + 16, 1 * 16 + 4)
    put(img, char_frame("clerk", 0, 0), 160 + 48, 3 * 16 - 4)
    return img


def nine_slice(src, w, h, c=8):
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    S = src.width

    def part(x0, y0, x1, y1):
        return src.crop((x0, y0, x1, y1))
    mid = S - 2 * c
    centre = part(c, c, S - c, S - c)
    for y in range(c, h - c, mid):
        for x in range(c, w - c, mid):
            out.paste(centre, (x, y))
    for x in range(c, w - c, mid):
        out.paste(part(c, 0, S - c, c), (x, 0))
        out.paste(part(c, S - c, S - c, S), (x, h - c))
    for y in range(c, h - c, mid):
        out.paste(part(0, c, c, S - c), (0, y))
        out.paste(part(S - c, c, S, S - c), (w - c, y))
    out.paste(part(0, 0, c, c), (0, 0))
    out.paste(part(S - c, 0, S, c), (w - c, 0))
    out.paste(part(0, S - c, c, S), (0, h - c))
    out.paste(part(S - c, S - c, S, S), (w - c, h - c))
    return out


def tiles_contact(ts):
    names = list(ts.meta["tiles"].keys())
    cols, cw, ch = 8, 124, 20
    rows = (len(names) + cols - 1) // cols
    img = Image.new("RGBA", (cols * cw, rows * ch + 4), BG)
    for i, nm in enumerate(names):
        x, y = (i % cols) * cw + 2, (i // cols) * ch + 2
        img.paste(ts.tile(nm), (x, y))
        img = label(img, nm, x + 19, y + 5)
    return pixlib.scale(img, 2)


def chars_contact():
    folder = os.path.join(GFX, "chars")
    if not os.path.isdir(folder):
        return None
    ids = sorted(f[:-4] for f in os.listdir(folder) if f.endswith(".png"))
    if not ids:
        return None
    cols, cw, ch = 4, 128, 70
    rows = (len(ids) + cols - 1) // cols
    img = Image.new("RGBA", (cols * cw, rows * ch), BG)
    for i, cid in enumerate(ids):
        x, y = (i % cols) * cw + 2, (i // cols) * ch + 2
        sheet = Image.open(os.path.join(folder, cid + ".png")).convert("RGBA")
        img.alpha_composite(sheet, (x, y))
        img = label(img, cid[:12], x + 50, y + 2)
        # standing poses in all four directions to the right
        for d in range(4):
            img.alpha_composite(sheet.crop((0, d * 16, 16, d * 16 + 16)), (x + 52 + (d % 2) * 18, y + 14 + (d // 2) * 18))
    return pixlib.scale(img, 2)


def trainers_contact():
    folder = os.path.join(GFX, "trainers")
    if not os.path.isdir(folder):
        return None
    ids = sorted(f[:-4] for f in os.listdir(folder) if f.endswith(".png"))
    if not ids:
        return None
    cols, cw, ch = 10, 68, 76
    rows = (len(ids) + cols - 1) // cols
    img = Image.new("RGBA", (cols * cw, rows * ch), (248, 248, 248, 255))
    for i, cid in enumerate(ids):
        x, y = (i % cols) * cw + 2, (i // cols) * ch + 2
        img.alpha_composite(Image.open(os.path.join(folder, cid + ".png")).convert("RGBA"), (x, y))
        img = label(img, cid[:11], x, y + 66)
    return pixlib.scale(img, 2)


def ui_contact():
    folder = os.path.join(GFX, "ui")
    if not os.path.isdir(folder):
        return None
    files = sorted(f for f in os.listdir(folder) if f.endswith(".png") and f != "region_map.png")
    x, y, rowh = 4, 4, 0
    W = 520
    placed = []
    for f in files:
        im = Image.open(os.path.join(folder, f)).convert("RGBA")
        w, h = im.width, im.height + 10
        if x + max(w, len(f) * 6) > W:
            x, y, rowh = 4, y + rowh + 6, 0
        placed.append((im, f, x, y))
        x += max(w, min(len(f), 20) * 6) + 8
        rowh = max(rowh, h)
    img = Image.new("RGBA", (W, y + rowh + 8), (176, 176, 168, 255))
    for im, f, px, py in placed:
        img.alpha_composite(im, (px, py))
        img = label(img, f[:-4][:20], px, py + im.height + 1)
    return pixlib.scale(img, 2)


def battle_scene():
    ui = os.path.join(GFX, "ui")
    tr = os.path.join(GFX, "trainers")
    if not os.path.exists(os.path.join(tr, "rival.png")):
        return None
    img = Image.new("RGBA", (256, 224), (248, 248, 248, 255))
    img.alpha_composite(Image.open(os.path.join(tr, "rival.png")).convert("RGBA"), (176, 8))
    img.alpha_composite(Image.open(os.path.join(tr, "player_m_back.png")).convert("RGBA"), (16, 88))
    fr = Image.open(os.path.join(ui, "frame_alt.png")).convert("RGBA")
    img.alpha_composite(nine_slice(fr, 256, 64), (0, 160))
    img = font.draw_text_rgba(img, "RIVAL KAI wants to", 12, 176, INK)
    img = font.draw_text_rgba(img, "battle!", 12, 192, INK)
    hp = Image.open(os.path.join(ui, "hp_bar.png")).convert("RGBA")
    img.alpha_composite(hp, (24, 32))
    img.alpha_composite(hp, (136, 120))
    img = font.draw_text_rgba(img, "SPARKIT  :L5", 16, 16, INK)
    img = font.draw_text_rgba(img, "AQUILL   :L5", 128, 104, INK)
    ex = Image.open(os.path.join(ui, "exp_bar.png")).convert("RGBA")
    img.alpha_composite(ex, (128, 144))
    balls = Image.open(os.path.join(ui, "ball_icons.png")).convert("RGBA")
    for i in range(6):
        k = 0 if i < 3 else 1
        img.alpha_composite(balls.crop((k * 8, 0, k * 8 + 8, 8)), (24 + i * 9, 48))
    dn = Image.open(os.path.join(ui, "down_arrow.png")).convert("RGBA")
    img.alpha_composite(dn, (236, 204))
    return img


def build():
    os.makedirs(OUT, exist_ok=True)
    ts = Tileset()
    pixlib.save_png(tiles_contact(ts), os.path.join(OUT, "tiles_contact.png"))
    town = scene_town(ts)
    pixlib.save_png(pixlib.scale(town, 3), os.path.join(OUT, "scene_town_x3.png"))
    pixlib.save_png(pixlib.scale(scene_indoor(ts), 3), os.path.join(OUT, "scene_indoor_x3.png"))
    for nm, fn in (("chars_contact", chars_contact), ("trainers_contact", trainers_contact),
                   ("ui_contact", ui_contact)):
        im = fn()
        if im is not None:
            pixlib.save_png(im, os.path.join(OUT, nm + ".png"))
    b = battle_scene()
    if b is not None:
        pixlib.save_png(pixlib.scale(b, 3), os.path.join(OUT, "scene_battle_x3.png"))
    rm = os.path.join(GFX, "ui", "region_map.png")
    if os.path.exists(rm):
        pixlib.save_png(pixlib.scale(Image.open(rm).convert("RGBA"), 2), os.path.join(OUT, "region_map_x2.png"))


if __name__ == "__main__":
    build()
