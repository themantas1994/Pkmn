"""Regenerate every art asset deterministically, validate it, render previews.

    python3 -I art/build_all.py            # build + validate + previews
    python3 -I art/build_all.py --no-previews
"""
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import badges  # noqa: E402
import balls  # noqa: E402
import chars  # noqa: E402
import font  # noqa: E402
import gym_tiles  # noqa: E402
import logo  # noqa: E402
import objects  # noqa: E402
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


def validate():
    with open(os.path.join(G, "tiles", "world.json"), encoding="utf-8") as f:
        w = json.load(f)
    assert w["tileSize"] == 16 and w["columns"] == 16
    with Image.open(os.path.join(G, "tiles", "world.png")) as im:
        assert im.width == 256 and im.height % 16 == 0
        n = (im.height // 16) * 16
    for t in REQUIRED_TILES:
        assert t in w["tiles"], f"missing tile {t}"
    for name, e in w["tiles"].items():
        for i in e.get("frames", [e["index"]]):
            assert 0 <= i < n, f"{name} index out of range"
    for t in ANIMATED + ["lava"]:
        assert len(w["tiles"][t]["frames"]) == 4
    # themed gym tile sets: one non-empty set per gym, every tile present and pairwise distinct
    assert len(gym_tiles.GYM_TILE_NAMES) == 8, "expected 8 themed gym tile sets"
    with Image.open(os.path.join(G, "tiles", "world.png")) as sheet:
        sheet = sheet.convert("RGBA")
        for theme, names in gym_tiles.GYM_TILE_NAMES.items():
            assert 5 <= len(names) <= 8, f"gym {theme}: {len(names)} tiles"
            seen = {}
            for name in names:
                assert name in w["tiles"], f"gym {theme}: missing tile {name}"
                for i in w["tiles"][name].get("frames", [w["tiles"][name]["index"]]):
                    x, y = (i % 16) * 16, (i // 16) * 16
                    key = sheet.crop((x, y, x + 16, y + 16)).tobytes()
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
    # every sprite frame: <= 4 opaque colours
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
                        assert c <= 4, f"{folder}/{fn} frame {x},{y} uses {c} colours"
    for jf in ("hp_bar.json", "exp_bar.json", "type_icons.json", "region_map.json",
               "status_icons.json", "ball_icons.json", "gimmick_icons.json"):
        with open(os.path.join(G, "ui", jf), encoding="utf-8") as f:
            json.load(f)
    print("validation OK:", len(w["tiles"]), "tiles,", len(CHAR_IDS), "characters")


def main():
    for mod in (font, tiles, chars, objects, trainers, ui, balls, badges, logo, regionmap):
        mod.build()
    validate()
    if "--no-previews" not in sys.argv:
        previews.build()


if __name__ == "__main__":
    main()
