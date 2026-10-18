# Pokémon Crystalline: art style guide

All artwork is original. It is generated from text pixel grids and small shape
programs in `art/*.py` and licensed CC0-1.0 (see `assets/ATTRIBUTION.json`).
Nothing is extracted or traced from Nintendo, Game Freak or Creatures games. The
target look is Game Boy Color Gold/Silver: chunky 16×16 cells, four colours per
tile or sprite, crisp pixels, no anti-aliasing and no gradients.

## Regenerating

```
python3 -I art/build_all.py          # build everything, validate, render previews
python3 -I art/build_all.py --no-previews
python3 -I art/tiles.py              # any single generator also runs on its own
```

The build is deterministic: running it twice gives byte-identical PNGs. Any
randomness uses a fixed seed. `build_all.py` checks that every required file
exists, that each file has the exact size the code expects, that tile indices are
in range, that every sprite frame uses 4 colours or fewer, and that every JSON file parses.
Previews go to `art/previews/`. They include a tile contact sheet, a character
sheet, a trainer sheet, a UI sheet, a town scene at ×3, an interior/cave scene,
a battle scene and the region map at ×2.

## Core numbers

| Item | Value |
|---|---|
| Logical screen | 256×224 px. Scale by whole numbers with nearest-neighbour filtering only. |
| Tile size | 16×16. The tileset is 16 columns wide. |
| Character frame | 16×16. Each sheet is 48×64: rows down/up/left/right, columns stand/stepA/stepB. |
| Trainer portrait | 64×64 with a transparent background. The figure is cropped at the bottom edge. |
| Animation | 4 frames at 250 ms each (`frameMs` in `world.json`). Water drifts 4 px per frame, so it loops in 4 frames. |
| Walk cycle | stand → stepA → stand → stepB. Down and up step frames also dip 1 px. |

## Palettes (`art/palettes.py`)

* Every palette has exactly **4 colours**, ordered from lightest (index 0) to darkest (index 3).
* Every channel value is a multiple of 8, matching the GBC's 15-bit colour.
* A tile uses one palette. `tiles.py` asserts that no tile exceeds 4 colours.
  The palette name for each tile is stored in `world.json`, so a shader or a
  day/night tint can work per palette.
* Sprite palettes always follow **[white, skin, role colour, outline]**. Overworld
  sprites, bike and surf sheets, and trainer portraits for the same id share one
  palette, so they read as the same person. Transparency is extra; it is not a palette slot.
* The main tile palette families are outdoor greens (`outdoor`, `path`,
  `flowers`, `rocky`, `fence`), `water`, `mountain`, `snow`/`ice`, roofs
  (`roof_red`, `roof_blue`, `roof_center`, `roof_gym`), walls, interiors
  (`floor_wood`, `floor_tile`, `furniture`, `tech`, `bed`, `plant`, `heal`, ...),
  `cave`, and the special areas `gym`, `puzzle`, `ruins`, `league` and `dark`.
* Shading uses the palette's own dark colours or a 50% checker dither on
  portraits. Never add in-between colours.

## Tileset: `assets/gfx/tiles/world.png` + `world.json`

```json
{ "tileSize": 16, "columns": 16,
  "tiles": { "water": { "index": 40, "palette": "water", "frames": [40,41,42,43], "frameMs": 250 } } }
```

`index` is always the first frame. Animated tiles are `flowers`, `water`, every
`water_*` shoreline tile, `waterfall` and `cave_water`. The shoreline tiles
animate in sync with open water. Index 0 is `void`, a solid near-black tile.

### Edge and corner naming

* `path_edge_n`: a path tile with a grass border on its **north** side. Use it on
  the top row of a path area. `_s`, `_e` and `_w` work the same way.
* `path_corner_nw`: an outer corner with grass on the north and west sides. Use it
  at the NW corner of a path area.
* `water_n`, `water_s`, `water_e`, `water_w`: water with shore along that side.
  The land strip is 4 px of plain grass colour.
* `water_nw` and the other outer corners: land on both named sides, with a rounded
  water corner.
* `water_inw` and the other inner corners: mostly water with a small land notch in
  that corner. Place `water_inw` where the north neighbour is `water_w` and the
  west neighbour is `water_n`.
* `ledge_s`, `ledge_e`, `ledge_w`: the player can hop off the ledge in the named direction.
* `art/previews.py` (`autotile_path`, `autotile_water`) contains reference
  autotile logic based on the 4 orthogonal and 4 diagonal neighbours.

### Buildings (prefabs)

`roof_*` tiles **repeat vertically**, so stacking two roof rows works. Each roof
also has an optional ridge-cap variant named `<roof>_l_top`, `_m_top` and
`_r_top` for the top row. Center and Mart walls share the house wall style, so
`wall_window` fits all of them.

| Prefab | Rows (top → bottom) |
|---|---|
| House 4×3 | `roof_l roof_m roof_m roof_r` ×2 / `wall_l wall_window door wall_r` (`roof2_*` gives a blue roof) |
| Lab 6×3 | `roof2_l roof2_m×4 roof2_r` ×2 / `wall_l wall_window wall_m door wall_window wall_r` |
| Center 5×3 | `center_roof_l center_roof_m×3 center_roof_r` / same row with `center_sign` in the middle / `center_wall_l wall_window center_door wall_window center_wall_r` |
| Mart 4×3 | `mart_roof_l mart_roof_m mart_roof_m mart_roof_r` / same row with `mart_sign` / `mart_wall_l wall_window mart_door mart_wall_r` |
| Gym 6×4 | `gym_roof_l gym_roof_m×4 gym_roof_r` / same row with `gym_sign` / `gym_wall_l gym_window gym_wall_m×2 gym_window gym_wall_r` / `gym_wall_l gym_wall_m gym_door gym_wall_m gym_wall_m gym_wall_r` |

The generator also provides `center_wall_m`, `mart_wall_m`, `gym_window`, the
`town_floor` plaza paving, `rug_l` and `rug_r` (a 2-wide exit mat), `rug_mat`
(a single warp mat) and `spinner_up`, `spinner_down`, `spinner_left` and `spinner_right`.

### Gym themes (`art/gym_tiles.py`)

Each gym has its own tile set (7-8 tiles, own palettes `gym_<theme>*` that carry the badge colours of
`assets/gfx/badges`). They are appended after every older tile, so no existing index moves. Collision comes from the tile
name only (`TileRules`): `gym_wall_*`, `puzzle_wall_*`, `statue_*`, `pillar_*`, `lamp_*`, `plant_*`, `bush_*`, `machine_*`,
`rock_*` and `lava` are solid; `gym_floor_*`, `dock_plank*`, `float_platform`, `circuit_*` and `rail_*` are walkable floor.
Sliding ice is still the single existing `ice` tile (`gym_floor_frost*` do not slide); water pools reuse `water`.

| Gym | Tiles |
|---|---|
| 1 Sprout | `gym_floor_sprout`, `gym_floor_sprout_stones`, `gym_wall_hedge`, `puzzle_wall_hedge`, `bush_blossom`, `plant_sprout`, `statue_sprout` |
| 2 Tide | `gym_floor_tide`, `gym_wall_tide`, `dock_plank`, `dock_plank_v`, `float_platform`, `pillar_mooring`, `puzzle_wall_crate`, `statue_tide` |
| 3 Spark | `gym_floor_spark`, `circuit_panel`, `circuit_cable`, `gym_wall_spark`, `machine_generator`, `machine_coil`, `puzzle_wall_spark` |
| 4 Stone | `gym_floor_stone`, `gym_wall_stone`, `rock_ore`, `rock_rubble`, `rail_h`, `rail_v`, `lamp_mine`, `puzzle_wall_stone` |
| 5 Mist | `gym_floor_mist`, `gym_floor_mist_deep`, `gym_floor_mist_path`, `gym_wall_mist`, `statue_mist`, `plant_mist`, `puzzle_wall_mist` |
| 6 Ember | `gym_floor_ember`, `gym_floor_ember_hot`, `gym_wall_ember`, `lava` (4 frames), `gym_floor_ember_crust`, `lamp_brazier`, `puzzle_wall_ember` |
| 7 Frost | `gym_floor_frost`, `gym_floor_frost_cracked`, `gym_floor_frost_snow`, `gym_wall_frost`, `pillar_ice`, `statue_frost`, `puzzle_wall_frost` |
| 8 Dusk | `gym_floor_dusk`, `gym_floor_dusk_rune`, `gym_wall_dusk`, `pillar_dusk`, `statue_dragon`, `lamp_dusk`, `puzzle_wall_dusk` |

`build_all.py` checks that every set exists, has 5-8 tiles and that no two tiles of a set are identical;
`GymTilesetTest` repeats that on the shipped PNG (index range, at most 4 colours per tile, distinct images).
`art/previews/gym_themes_contact.png` shows one row per gym. The `lava` tile is the only animated one added (4 frames, the
pattern drifts 4 px per frame like water). Gym maps do not use these tiles yet.

### Interiors

Furniture tiles are opaque. Home furniture (`table`, `chair`, `bed_*`, `plant`,
`tv`, `bookshelf`) is drawn over a plain floor colour close to the wood floor's
base tone. Center and Mart pieces (`counter`, `pc`, `healing_machine` and
`mart_shelf`) fill the whole tile or sit on a light grey close to `floor_tile`.
Indoor walls use two rows: `wall_in_top` (ceiling trim) above `wall_in`
(wallpaper plus skirting). `window_in` replaces a `wall_in_top` tile.

## Characters: `assets/gfx/chars/<id>.png`

The sprites use a paper-doll system in `art/chars.py`. A head template (hair or
hat style, rows 0-12), a body template (`std`, `skirt` or `robe`, rows 10-13) and
a legs template (rows 14-15) are combined. Template letters (`h` hair, `a` hat,
`o` outer top, `c` inner top, `d` pants/skirt, `l` legs, `b` beard) map to
palette slots per character in the `CHARACTERS` table. Right-facing frames are
mirrored left-facing ones. Dark hair gets 2-3 white shine pixels.

To add a character, add a row to `CHARACTERS` with a role colour, head style, body,
letter mapping and accessories (`glasses`, `beard`, `moustache`, `headband`,
`nursecap`, `goggles`). The trainer portrait is generated from the same row by
`art/trainers.py`. To add a new hairstyle, add `down`, `up` and `left` grids to
`HEADS` in `chars.py` and a matching branch in `hair_masks()`/`hat_masks()` in
`trainers.py`.

Bike and surf sheets use the same 48×64 layout. The surf creature is an original
pale sea-beast with role-coloured spots.

Objects (`assets/gfx/objects/`) are 16×16 single frames. `legendary_shadow.png`
is 32×32, a generic winged silhouette for scripted legendary events.

## Trainer portraits: `assets/gfx/trainers/<id>.png`

These are 64×64 front-facing upper bodies. They are built from layered masks:
back hair, torso, shirt, neck, head, hair, hat. A 1 px black line is drawn where
layers meet, then the eyes, brows, nose and mouth are stamped on, and the right
side gets a checker dither for shading. `player_m_back.png` and
`player_f_back.png` are the larger back views for the battle screen.

## UI: `assets/gfx/ui/`

| File | Notes |
|---|---|
| `frame.png`, `frame_alt.png` | 24×24 nine-slice with 8 px corners and edges and a white centre. `frame` is the blue double-line dialogue box; `frame_alt` is the grey battle text box. |
| `cursor.png`, `down_arrow.png` | 8×8. Blink the arrow at about 500 ms. |
| `hp_bar.png` + `hp_bar.json` | 64×8. The fill rect is `{x:15,y:3,w:48,h:2}`. Fill colours: green above 50%, yellow above 20%, red otherwise. |
| `exp_bar.png` + `exp_bar.json` | 80×8. The fill rect is `{x:18,y:3,w:60,h:2}` in the blue exp colour. |
| `status_icons.png` | 5 × 24×8 in a row: BRN PAR PSN SLP FRZ (also listed in the json). |
| `type_icons.png` + json | 19 × 32×12 in a 4-column grid. Index order is in `type_icons.json` (Stellar is last). |
| `ball_icons.png` | 8×8 ×4: full, empty, fainted, status. |
| `gimmick_icons.png` | 16×16 ×4: mega, zmove, dynamax, tera. |
| `title_logo.png` | 224×64, original "CRYSTALLINE" lettering with a crystal cluster. |
| `pokeball_icon.png` | 16×16, fallback ball icon |
| `touch_*` | Translucent (alpha around 150): 96×96 D-pad, 40×40 A/B, 40×16 START/SELECT. |
| `weather_rain.png`, `weather_snow.png` | 64×64 tileable alpha overlays. Scroll rain diagonally and snow slowly downward. |

## Ball and badge art: `assets/gfx/balls/`, `assets/gfx/badges/`

* `balls/<item id>.png`: 16×16 icon for every `isPokeball` item (39), generated by `art/balls.py` from one shared
  silhouette, a per-ball 4-colour palette and a pattern overlay. The file name is the item id, so
  `Assets.ball(id)` is the single id → icon lookup (battle throw/catch, bag list). Unknown ids fall back to
  `ui/pokeball_icon.png`. `BallBadgeArtTest` checks every ball has an icon and that all are pairwise distinct.
* `badges/badge_<n>.png`: 24×24, n = 1..8 (Sprout, Tide, Spark, Stone, Mist, Ember, Frost, Dusk), generated by
  `art/badges.py` (own silhouette, palette and glyph per badge). The trainer card draws earned badges in colour and
  unearned ones as a dark silhouette; the `badge N` script command shows the art at 2× above the award text.

## Fonts: `assets/fonts/pixel8.*`, `pixel6.*`

These are 8×8 and 6×8 monospace sheets, 16 columns, in the order given by the
`chars` string in the json. They cover ASCII 32-126 plus `é É ♂ ♀ … ’ “ ” × ▶ ▼ ★ ♪`
and the ligatures **U+E000 = "PK"** and **U+E001 = "MN"**. Capitals use rows
0-5, lowercase x-height is rows 2-5 and descenders use row 6, so no glyph is
taller than 7 px. `pixel8.png` uses dark ink (#181818); `pixel8_white.png` is
white and meant to be tinted when drawn. `pixel6` is for long move names.

## Region map: Tessera (`ui/region_map.png` + `region_map.json`)

The map is a 32×28 grid of 8×8 cells. `region_map.json` lists every town's cell,
its pixel centre and every route as a cell polyline. Tessera is one rounded,
diamond-shaped continent. Its north is a snow-capped mountain range, the Zenith
Plateau league sits at the summit, a large lake fills the centre-east, forests
lie in the west and south-west, and a sandy coast runs down the south-east. Beryl
Isle lies off the south coast.

| # | Town | Cell (x,y) | Kind | Connections |
|---|---|---|---|---|
| 1 | Lumen Town (start) | 12,23 | town | Route 1 north; Route 14 by sea east to Beryl Isle |
| 2 | Quartzford | 12,19 | town | R1, R2 west, R3 north |
| 3 | Mica City | 5,19 | city | R2, R4 north |
| 4 | Garnet Port | 5,15 | city | R4, R5 north (west coast port) |
| 5 | Basalt Town | 4,11 | town | R5, R7 east |
| 6 | Feldspar Town | 12,16 | town | R3, R6 north, R8 east (south of the lake) |
| 7 | Prism City | 12,11 | city | R6, R7, R12 north (central hub) |
| 8 | Opaline City | 26,14 | city | R8, R9 north (east coast) |
| 9 | Jasper City | 25,9 | city | R9, R10 west |
| 10 | Selenite Village | 19,9 | town | R10, R11 north (north shore of the lake) |
| 11 | Cobalt City | 16,6 | city | R11, R13, Victory Road north (mountain pass) |
| 12 | Obsidian Town | 12,6 | town | R12, R13 (mountain village) |
| 13 | Beryl Isle | 26,24 | town | R14 (sea route) |
| 14 | Zenith Plateau | 16,2 | league | Victory Road |

To change the map, edit `TOWNS`, `ROUTES` or the terrain shapes in `art/regionmap.py`.

## File naming

Use lowercase snake_case everywhere. Character and trainer files share the same
id (`grunt_f.png`). Variants add a suffix (`_bike`, `_surf`, `_back`, `_top`).
JSON descriptors sit next to their PNG and share its base name.

## Adding new art

1. Write it as a text grid or a shape function in the matching `art/*.py`. Use an
   existing palette, or add a new 4-colour one to `palettes.py` (multiples of 8).
2. For tiles, `add("name", "palette", grid_or_frames)` in `tiles.build_tiles()`.
   Indices are assigned in order, so append to the end of the list if the game
   needs existing indices to stay stable. The game should look tiles up by name
   through `world.json`, never by raw index.
3. Run `python3 -I art/build_all.py`, check the previews, commit the generator,
   the outputs and the previews together, and add any new output paths to
   `assets/ATTRIBUTION.json`.
