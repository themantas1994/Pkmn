"""Named GBC-style palettes for Pokemon Crystalline (region: Tessera).

Rules (see docs/ART_STYLE.md):
  * Every palette has exactly 4 colours, ordered lightest (index 0) -> darkest (3).
  * Every channel is a multiple of 8 (the GBC has 5 bits per channel).
  * A single 16x16 tile or sprite frame may only use the 4 colours of ONE
    palette (sprites additionally use full transparency).
  * Sprite palettes are always [white, skin/light, role colour, outline].
"""


def _c(hexstr):
    hexstr = hexstr.lstrip("#")
    r, g, b = (int(hexstr[i:i + 2], 16) for i in (0, 2, 4))
    for v in (r, g, b):
        assert v % 8 == 0, f"{hexstr}: channels must be multiples of 8"
    return (r, g, b)


def pal(*hexes):
    assert len(hexes) == 4
    return tuple(_c(h) for h in hexes)


# --------------------------------------------------------------------------
# Background / tile palettes
# --------------------------------------------------------------------------
TILE = {
    # day outdoor greens: cream, light green, green, deep green
    "outdoor": pal("F8F0C0", "98D068", "409048", "183020"),
    # flowers: white petals, grass, red petals, green
    "flowers": pal("F8F8F8", "98D068", "E04848", "409048"),
    # dirt paths: cream, tan, grass, green (path edges blend into grass)
    "path": pal("F8F0C0", "E0C890", "98D068", "409048"),
    # beach sand
    "sand": pal("F8F0C0", "F0D898", "C8A060", "583820"),
    # rocks sitting on grass: cream highlight, grass, grey, dark
    "rocky": pal("F0F0E0", "98D068", "A0A090", "303028"),
    # wooden fences / signs on grass
    "fence": pal("F8F0C0", "98D068", "B88050", "402818"),
    # lakes / sea: land, foam, water, deep edge
    "water": pal("98D068", "B8E0F8", "4890E8", "182860"),
    "waterfall": pal("F8F8F8", "B8E0F8", "4890E8", "182860"),
    "bridge": pal("F8E0B0", "D0A060", "986030", "402010"),
    # town palette: lighter path for paved squares
    "town": pal("F8F8E0", "D8D8C0", "98D068", "409048"),
    "mountain": pal("F8E8B8", "D8B070", "986838", "302018"),
    "snow": pal("F8F8F8", "D8E8F8", "98B8E8", "283868"),
    "ice": pal("F8F8F8", "B8E8F8", "70B0E0", "205078"),
    # buildings
    "roof_red": pal("F8F8F8", "F89880", "D03830", "401010"),
    "roof_blue": pal("F8F8F8", "88B8F8", "3058C8", "101848"),
    "roof_center": pal("F8F8F8", "F8A8A8", "E04050", "481018"),
    "roof_gym": pal("F8F8F8", "C8C8D8", "787898", "202030"),
    "wall": pal("F8F8F8", "E8E0C8", "A89880", "302820"),
    "wall_window": pal("F8F8F8", "E8E0C8", "68A0E8", "302820"),
    "door": pal("F8F0C0", "D8A868", "885830", "302010"),
    "glass_door": pal("E8E0C8", "B8E0F8", "5888C8", "302820"),
    "gym_wall": pal("F8F8F8", "D0D0E0", "9090A8", "202030"),
    "sign_center": pal("F8F8F8", "F8A8A8", "E04050", "202020"),
    "sign_mart": pal("F8F8F8", "88B8F8", "3058C8", "101848"),
    "sign_gym": pal("F8F8F8", "F8D850", "787898", "202030"),
    # interiors
    "floor_wood": pal("F8E0B0", "D8B078", "A07040", "402818"),
    "floor_tile": pal("F8F8F8", "D8E0E8", "A0B0C0", "404858"),
    "carpet": pal("F8D8C8", "E89880", "B05848", "401818"),
    "wall_in": pal("F8F8F0", "E8E0C8", "B0A890", "383028"),
    "window_in": pal("F8F8F8", "B8E0F8", "88A8C8", "383028"),
    "furniture": pal("F8E0B0", "D8B078", "A07040", "402818"),  # = floor_wood tones
    "tv": pal("F8F8F8", "D8B078", "5878A0", "202838"),
    "bed": pal("F8F8F8", "88B8F8", "906038", "302010"),
    "tech": pal("F8F8F8", "B8C8D8", "5878A0", "202838"),
    "plant": pal("D8B078", "88C860", "388040", "203018"),
    "heal": pal("F8F8F8", "F8A8A8", "D84050", "302830"),
    "mart_shelf": pal("F8F8F8", "88B8F8", "C88848", "302010"),
    "rug": pal("F8D8C8", "E88070", "A03838", "401818"),
    # caves
    "cave": pal("E0C090", "B08858", "785030", "281810"),
    "cave_water": pal("B08858", "88B8E8", "3870C0", "101840"),
    # gyms / special areas
    "gym": pal("F8F8F8", "C8D0E0", "8090B0", "283040"),
    "puzzle": pal("F8F8F8", "F8D850", "8090B0", "283040"),
    "ruins": pal("E8E0B0", "C0B078", "807048", "302818"),
    "league": pal("E8D8F8", "B090D8", "6848A0", "201040"),
    "dark": pal("606078", "404058", "282838", "101018"),
    # themed gym tile sets (art/gym_tiles.py); every set carries its badge colours
    # 1 Sprout: lawn, hedge, blossom, stepping stones, planter, leaf statue
    "gym_sprout": pal("C8F8A0", "88D058", "489838", "183820"),
    "gym_sprout_stones": pal("E8E0C0", "88D058", "B0A888", "305028"),
    "gym_sprout_hedge": pal("98D858", "48A030", "287828", "102810"),
    "gym_sprout_bloom": pal("F8F8F8", "F890B8", "48A030", "183820"),
    "gym_sprout_pot": pal("88D058", "489838", "C08048", "183820"),
    "gym_sprout_statue": pal("E8F8D0", "A8D890", "58B038", "205020"),
    # 2 Tide: wet stone, wave wall, dock planks, raft on water, drop fountain
    "gym_tide": pal("D8F0F8", "A8D8F0", "6098D0", "103878"),
    "gym_tide_wall": pal("A8E0F8", "3078D8", "103878", "081840"),
    "gym_tide_dock": pal("E8C890", "C09058", "885830", "402010"),
    "gym_tide_float": pal("E8C890", "B88048", "3078D8", "103878"),
    "gym_tide_statue": pal("D8F0F8", "A8E0F8", "3078D8", "103878"),
    # 3 Spark: steel plates, live circuit traces, hazard-yellow machinery
    "gym_spark": pal("B0B8D0", "707890", "384058", "101020"),
    "gym_spark_live": pal("F8F098", "F0C020", "505870", "181828"),
    "gym_spark_hazard": pal("F8F098", "F0C020", "384058", "101020"),
    # 4 Stone: flagstones, mine wall, ore vein, lantern
    "gym_stone": pal("E0D8C8", "B8A888", "786850", "282018"),
    "gym_stone_wall": pal("B8A888", "786850", "504838", "201810"),
    "gym_stone_ore": pal("E8F0F8", "988868", "4890D8", "282018"),
    "gym_stone_lamp": pal("F8E8A0", "D89830", "786850", "282018"),
    # 5 Mist: pale lavender slabs, fog, glow flowers, crystal statue
    "gym_mist": pal("F0E8F8", "D0C0F0", "9880D0", "483878"),
    "gym_mist_wall": pal("D0C0F0", "9880D0", "584890", "281848"),
    "gym_mist_glow": pal("F8F8C0", "D0C0F0", "58A070", "483878"),
    "gym_mist_statue": pal("F0E8F8", "D0B8F0", "8868C8", "281848"),
    # 6 Ember: basalt, glowing cracks, lava, crust, brazier
    "gym_ember": pal("786860", "504040", "302828", "181010"),
    "gym_ember_hot": pal("F8D880", "F07020", "504040", "181010"),
    "gym_ember_lava": pal("F8D880", "F89830", "E04018", "801008"),
    "gym_ember_crust": pal("F89830", "E04018", "504040", "181010"),
    # 7 Frost: polished ice tile, ice brick, crystal statue
    "gym_frost": pal("F8F8F8", "D0E8F8", "90D0F0", "3868B0"),
    "gym_frost_wall": pal("D0E8F8", "90D0F0", "3868B0", "102048"),
    "gym_frost_statue": pal("F8F8F8", "B8E0F8", "68A8E0", "102048"),
    # 8 Dusk: ancient violet slabs, gold runes, dragon scales, dragon statue
    "gym_dusk": pal("6858A8", "403078", "281858", "080818"),
    "gym_dusk_rune": pal("F8E8A0", "5040A0", "281858", "080818"),
    "gym_dusk_wall": pal("8068C0", "5040A0", "281858", "080818"),
    "gym_dusk_statue": pal("F8E8A0", "B09850", "403078", "080818"),
    # entrance tiles (warp doors that must look like where they lead)
    # forest path gap: tan path, light green, green, deep green
    "forest_gap": pal("E0C890", "98D068", "409048", "183020"),
    # tower door: pale stone, stone, door wood, outline
    "tower": pal("D8D8E8", "A0A0B8", "885830", "202030"),
    # gate house passage: cream, path tan, wall shade, dark
    "gate": pal("F8F0C0", "E0C890", "A89880", "302820"),
    # grand building double door: white, stone, brass/wood, outline
    "building": pal("F8F8F8", "D0D0E0", "B88050", "202030"),
}

# --------------------------------------------------------------------------
# Sprite palettes: [white, skin, role colour, outline]
# --------------------------------------------------------------------------
WHITE = "F8F8F8"
SKIN = "F8C098"
SKIN_TAN = "E0A070"
OUTLINE = "181010"


def spr(role_hex, skin=SKIN, white=WHITE, outline=OUTLINE):
    return pal(white, skin, role_hex, outline)


ROLE = {
    "red": "E03828",
    "crimson": "B82038",
    "orange": "F07820",
    "gold": "D8A020",
    "yellow": "E8C828",
    "lime": "68B830",
    "green": "389848",
    "teal": "20A098",
    "cyan": "38A8E0",
    "blue": "3868D8",
    "navy": "283890",
    "indigo": "5040B0",
    "purple": "8840B8",
    "magenta": "C83898",
    "pink": "F070A8",
    "brown": "985828",
    "tan": "C09060",
    "grey": "787880",
    "slate": "485868",
    "black": "383038",
}

# --------------------------------------------------------------------------
# UI palettes
# --------------------------------------------------------------------------
UI = {
    "frame": pal("F8F8F8", "B8C8E0", "4868A8", "181820"),
    "frame_alt": pal("F8F8F8", "D0D8D0", "686868", "181818"),
    "hp": {"green": _c("38C048"), "yellow": _c("F0C020"), "red": _c("E83820"),
           "exp": _c("3890F0"), "frame": _c("181818"), "empty": _c("F8F8F8")},
}

# Type colours (used for type_icons.png)
TYPE_COLOURS = {
    "normal": "A8A878", "fire": "F08030", "water": "6890F0", "electric": "E8C028",
    "grass": "78C850", "ice": "88D0D0", "fighting": "C03028", "poison": "A040A0",
    "ground": "D8B860", "flying": "A890F0", "psychic": "F85888", "bug": "A8B820",
    "rock": "B8A038", "ghost": "705898", "dragon": "7038F8", "dark": "705848",
    "steel": "B8B8D0", "fairy": "E898B0", "stellar": "40A8C8",
}

if __name__ == "__main__":
    print(len(TILE), "tile palettes;", len(ROLE), "role colours")
