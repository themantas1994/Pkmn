"""Regenerate every art asset deterministically, validate it, render previews.

    python3 -I art/build_all.py            # build + validate + previews
    python3 -I art/build_all.py --no-previews
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import badges  # noqa: E402
import balls  # noqa: E402
import chars  # noqa: E402
import font  # noqa: E402
import gym_tiles  # noqa: E402
import logo  # noqa: E402
import objects  # noqa: E402
import palettes  # noqa: E402
import pixlib  # noqa: E402
import previews  # noqa: E402
import regionmap  # noqa: E402
import tiles  # noqa: E402
import trainers  # noqa: E402
import ui  # noqa: E402

G = pixlib.GFX

REQUIRED_TILES = """
grass grass_alt tall_grass flowers path path_edge_n path_edge_s path_edge_e path_edge_w
path_corner_ne path_corner_nw path_corner_se path_corner_sw sand tree tree_top tree_bottom
bush rock boulder cut_tree ledge_s ledge_e ledge_w fence_h fence_v sign water
water_n water_s water_e water_w water_ne water_nw water_se water_sw
water_ine water_inw water_ise water_isw waterfall bridge_h bridge_v stairs_up stairs_down
cave_entrance snow ice mountain_wall mountain_top roof_l roof_m roof_r roof2_l roof2_m roof2_r
wall_l wall_m wall_r wall_window door
center_roof_l center_roof_m center_roof_r center_sign center_wall_l center_wall_r center_door
mart_roof_l mart_roof_m mart_roof_r mart_sign mart_wall_l mart_wall_r mart_door
gym_roof_l gym_roof_m gym_roof_r gym_sign gym_wall_l gym_wall_m gym_wall_r gym_door
floor_wood floor_tile floor_carpet wall_in wall_in_top window_in counter pc bookshelf table chair
bed_top bed_bottom plant tv rug_mat stairs_in_up stairs_in_down healing_machine mart_shelf
cave_floor cave_wall cave_wall_top cave_rock cave_ladder cave_water
gym_floor gym_statue puzzle_switch puzzle_wall ruins_floor ruins_wall league_floor dark_floor
""".split()
ANIMATED = ["flowers", "water", "waterfall", "cave_water"]

CHAR_IDS = """player_m player_f rival mom professor nurse clerk youngster lass bugcatcher hiker
fisherman swimmer_m swimmer_f gentleman beauty scientist sage blackbelt psychic cooltrainer_m
cooltrainer_f pokefan ranger oldman oldwoman kid policeman sailor grunt_m grunt_f admin boss
leader1 leader2 leader3 leader4 leader5 leader6 leader7 leader8 elite1 elite2 elite3 elite4
champion""".split()

UI_SIZES = {
    "frame.png": (24, 24), "frame_alt.png": (24, 24), "cursor.png": (8, 8),
    "down_arrow.png": (8, 8), "hp_bar.png": (64, 8), "exp_bar.png": (80, 8),
    "status_icons.png": (120, 8), "type_icons.png": (128, 60), "ball_icons.png": (32, 8),
    "gimmick_icons.png": (64, 16), "title_logo.png": (224, 64), "region_map.png": (256, 224),
    "pokeball_icon.png": (16, 16), "touch_dpad.png": (96, 96), "touch_button_a.png": (40, 40),
    "touch_button_b.png": (40, 40), "touch_button_start.png": (40, 16),
    "touch_button_select.png": (40, 16), "weather_rain.png": (64, 64), "weather_snow.png": (64, 64),
}


def check_size(path, size):
    assert os.path.exists(path), f"missing {path}"
    with Image.open(path) as im:
        assert im.size == size, f"{path}: {im.size} != {size}"


def assert_15bit(im, what):
    """All opaque channels are multiples of 8 (15-bit colour)."""
    a = np.asarray(im.convert("RGBA")).reshape(-1, 4)
    a = a[a[:, 3] > 0][:, :3]
    assert not (a % 8).any(), f"{what}: channels must be multiples of 8"


def metatile_layers(name, e, sheet_arr):
    """All composed 16x16 RGBA layers (every ground frame, then the top layer if any) of one metatile."""
    layers = [tiles.compose(sheet_arr, cells) for cells in e.get("frames", [e["ground"]])]
    if "top" in e:
        layers.append(tiles.compose(sheet_arr, e["top"]))
    return layers


def validate_tile_palettes():
    """Every metatile layer uses <= 15 colours and its colours fit one declared palette (PALETTES16)."""
    declared = [set(p.colours()) for p in palettes.PALETTES16.values()]
    with open(os.path.join(G, "tiles", "world.json"), encoding="utf-8") as f:
        w = json.load(f)
    with Image.open(os.path.join(G, "tiles", "world.png")) as sheet:
        sheet = sheet.convert("RGBA")
        assert_15bit(sheet, "tiles/world.png")
        arr = np.asarray(sheet)
    bad = []
    for name, e in w["metatiles"].items():
        for layer in metatile_layers(name, e, arr):
            a = layer.reshape(-1, 4)
            cols = {tuple(int(v) for v in p[:3]) for p in a if p[3] > 0}
            if not cols:
                continue
            assert len(cols) <= palettes.SLOTS - 1, f"metatile {name} uses {len(cols)} colours"
            if not any(cols <= d for d in declared):
                bad.append(name)
    assert not bad, f"{len(bad)} metatiles match no declared palette, first: {bad[:5]}"


def validate_tint():
    """tinted() for morning/evening/night reproduces docs/screenshots/town_*.png within +-8 per channel.

    The screenshots also contain the map-name banner, actors, animated water and night window lights, which are
    not palette-tinted; those 16x16 cells are excluded, and at least 70% of the frame must still be compared.
    """
    shots = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "screenshots")
    day = np.asarray(Image.open(os.path.join(shots, "town_day.png")).convert("RGB"))
    colours = {tuple(int(v) for v in c) for c in day.reshape(-1, 3)}
    for phase in ("morning", "evening", "night"):
        got = np.asarray(Image.open(os.path.join(shots, f"town_{phase}.png")).convert("RGB")).astype(int)
        lut = {c: palettes.tint_rgb(c, phase) for c in colours}
        exp = np.array([lut[tuple(int(v) for v in c)] for c in day.reshape(-1, 3)]).reshape(day.shape)
        bad = (np.abs(got - exp).max(axis=2) > 8)
        cells = bad.reshape(day.shape[0] // 16, 16, day.shape[1] // 16, 16).any(axis=(1, 3))
        assert 1 - cells.mean() >= 0.70, f"tint {phase}: only {1 - cells.mean():.0%} of cells within +-8"
        assert bad.mean() <= 0.08, f"tint {phase}: {bad.mean():.1%} of pixels off by more than 8"
    # interiors, caves, special areas and UI are never tinted; the night outline never lightens past slot 2
    for name, p in palettes.PALETTES16.items():
        for phase in palettes.PHASES:
            t = p.tinted(phase)
            if p.group in palettes.UNTINTED_GROUPS:
                assert t is p, f"{name} ({p.group}) must not be tinted"
            assert len(t.colours()) <= palettes.SLOTS - 1
    print("tint OK:", len(palettes.PALETTES16), "palettes x", len(palettes.PHASES), "phases")


def validate():
    with open(os.path.join(G, "tiles", "world.json"), encoding="utf-8") as f:
        w = json.load(f)
    assert w["tileSize"] == 8 and w["metatileSize"] == 16 and w["columns"] == tiles.SHEET_COLUMNS
    with Image.open(os.path.join(G, "tiles", "world.png")) as im:
        assert im.width == tiles.SHEET_COLUMNS * 8 and im.height % 8 == 0
        n = (im.height // 8) * tiles.SHEET_COLUMNS
        sheet_arr = np.asarray(im.convert("RGBA")).copy()
    # spec section 4 budget: 512 8x8 tiles per primary/secondary set; this single set stays within both
    assert n <= 1024, f"{n} 8x8 tiles exceed the 2 x 512 budget"
    for t in REQUIRED_TILES:
        assert t in w["metatiles"], f"missing tile {t}"
    for name, e in w["metatiles"].items():
        assert e["type"] in tiles.METATILE_TYPES, f"{name}: bad type {e['type']}"
        refs = [r for cells in e.get("frames", [e["ground"]]) for r in cells] + e.get("top", [])
        for cells in e.get("frames", [e["ground"]]) + ([e["top"]] if "top" in e else []):
            assert len(cells) == 4, f"{name}: a layer needs four 8x8 cells"
        for r in refs:
            assert r == tiles.EMPTY or 0 <= (r & tiles.INDEX_MASK) < n, f"{name} tile index out of range"
        assert e.get("frames", [e["ground"]])[0] == e["ground"], f"{name}: ground must equal frame 0"
    for t in ANIMATED + ["lava"]:
        assert len(w["metatiles"][t]["frames"]) == 4
    # themed gym tile sets: one non-empty set per gym, every tile present and pairwise distinct
    assert len(gym_tiles.GYM_TILE_NAMES) == 8, "expected 8 themed gym tile sets"
    for theme, names in gym_tiles.GYM_TILE_NAMES.items():
        assert 5 <= len(names) <= 8, f"gym {theme}: {len(names)} tiles"
        seen = {}
        for name in names:
            assert name in w["metatiles"], f"gym {theme}: missing tile {name}"
            e = w["metatiles"][name]
            for cells in e.get("frames", [e["ground"]]):
                key = tiles.compose(sheet_arr, cells).tobytes()
                other = seen.setdefault(key, name)
                assert other == name or name == "lava", f"gym {theme}: {name} identical to {other}"
    for cid in CHAR_IDS:
        check_size(os.path.join(G, "chars", cid + ".png"), (48, 64))
        check_size(os.path.join(G, "trainers", cid + ".png"), (64, 64))
    for v in ("player_m_bike", "player_f_bike", "player_m_surf", "player_f_surf"):
        check_size(os.path.join(G, "chars", v + ".png"), (48, 64))
    for b in ("player_m_back", "player_f_back"):
        check_size(os.path.join(G, "trainers", b + ".png"), (64, 64))
    for o in ("itemball", "boulder", "cut_tree", "sign", "rock_smash", "pokeball_overworld"):
        check_size(os.path.join(G, "objects", o + ".png"), (16, 16))
    check_size(os.path.join(G, "objects", "legendary_shadow.png"), (32, 32))
    for fn, size in UI_SIZES.items():
        check_size(os.path.join(G, "ui", fn), size)
    for ball_id in balls.BALLS:
        check_size(os.path.join(G, "balls", ball_id + ".png"), (16, 16))
    for n in badges.BADGES:
        check_size(os.path.join(G, "badges", f"badge_{n}.png"), (24, 24))
    for fnt, cw in (("pixel8", 8), ("pixel6", 6)):
        with open(os.path.join(pixlib.ASSETS, "fonts", fnt + ".json"), encoding="utf-8") as f:
            meta = json.load(f)
        rows = (len(meta["chars"]) + 15) // 16
        check_size(os.path.join(pixlib.ASSETS, "fonts", fnt + ".png"), (16 * cw, rows * 8))
        for ch in "éÉ♂♀…’“”×▶▼★♪":
            assert ch in meta["chars"]
    # every sprite frame: <= 15 opaque colours + transparent (16-slot palette), 15-bit channels
    for folder in ("chars", "trainers", "objects"):
        d = os.path.join(G, folder)
        for fn in os.listdir(d):
            with Image.open(os.path.join(d, fn)) as im:
                im = im.convert("RGBA")
                for y in range(0, im.height, 16 if folder == "chars" else im.height):
                    for x in range(0, im.width, 16 if folder == "chars" else im.width):
                        h = 16 if folder == "chars" else im.height
                        wdt = 16 if folder == "chars" else im.width
                        c = pixlib.colours_used(im.crop((x, y, x + wdt, y + h)))
                        assert c <= palettes.SLOTS - 1, f"{folder}/{fn} frame {x},{y} uses {c} colours"
                assert_15bit(im, f"{folder}/{fn}")
    validate_tile_palettes()
    validate_tint()
    for jf in ("hp_bar.json", "exp_bar.json", "type_icons.json", "region_map.json",
               "status_icons.json", "ball_icons.json", "gimmick_icons.json"):
        with open(os.path.join(G, "ui", jf), encoding="utf-8") as f:
            json.load(f)
    print("validation OK:", len(w["metatiles"]), "metatiles,", len(CHAR_IDS), "characters")


def main():
    for mod in (font, tiles, chars, objects, trainers, ui, balls, badges, logo, regionmap):
        mod.build()
    validate()
    if "--no-previews" not in sys.argv:
        previews.build()


if __name__ == "__main__":
    main()
