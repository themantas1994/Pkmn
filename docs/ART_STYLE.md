# Pokémon Crystalline: art style guide

All artwork is original. It is generated from text pixel grids and small shape
programs in `art/*.py` and licensed CC0-1.0 (see `assets/ATTRIBUTION.json`).
Nothing is extracted or traced from Nintendo, Game Freak or Creatures games. The
target look is Game Boy Color Gold/Silver: chunky 16×16 cells, four colours per
tile or sprite, crisp pixels, no anti-aliasing and no gradients. The "Visual style sheet" section is the
contract for new hand-made art: outlines, environment palettes, Pokémon sprites, backdrops and effects.

## Regenerating

```
./gradlew :tools:buildArt            # canonical: pinned venv (art/requirements.txt), writes assets/gfx and assets/fonts
python3 -I art/build_all.py          # build everything, validate, render previews
python3 -I art/build_all.py --no-previews
python3 -I art/tiles.py              # any single generator also runs on its own
```

CI runs `:tools:buildArt` before the content gates and fails if `assets/` drifts, so
commit regenerated art. Use the pinned numpy/Pillow (a different Pillow can change PNG bytes);
`-Ppkmn.art.python=<python>` selects another interpreter that already has them.

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
| Character frame | 16×32 (head overlaps the tile above, feet on the last row). Each walk/run sheet is 48×96: rows down/up/left, columns stand/stepA/stepB; right = left mirrored at draw time. Bike/surf: 32×32 frames, 96×96 sheets. |
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

## Visual style sheet (D3.1)

This section is the contract for every new picture: hand-made pixel art, effect sheets, battle backdrops
and Pokémon sprites. It keeps the Game Boy Color look (decision D1) and adds the parts the earlier sections
did not fix: where light comes from, how outlines work, which colours each environment uses, and how effects
are drawn. Generated art (`art/*.py`) and hand-made art follow the same rules. A reviewer checks a new asset
against the checklist at the end of this section.

### Pillars

1. **Four colours, one palette, per cell.** A tile, character frame, sprite or effect frame uses at most 4
   opaque colours from one palette, plus transparency. A palette is ordered lightest (index 0) to darkest
   (index 3) and every channel is a multiple of 8. Backdrops follow the same rule per 8×8 cell (see Battle backdrops).
2. **Hard pixels.** No anti-aliasing, no gradients, no translucent pixels (alpha is 0 or 255), no blur,
   no glow. Runtime scaling is by whole numbers with Nearest filtering. Sprites and effects are never rotated by
   an angle at runtime: only flips and 90° turns are allowed, so a diagonal is drawn, not rotated.
3. **Light from the upper left.** Highlights sit on the top and left edges, shade on the bottom and right.
   Cast shadows fall down and to the right. This already holds for characters and portraits (dither on the right).
4. **Value before hue.** An object must be readable in a greyscale screenshot. Type, status and effect meaning is carried by
   shape and motion as well as colour (see Effect style guide).
5. **Two pixel densities, never mixed inside one object.** World and UI art is 1 logical px per art pixel.
   Pokémon battle sprites are drawn on a 48 px grid and enlarged 2× (one art pixel = 2×2 logical px). An effect
   is authored at 1× or at 2× (see Effect classes), and one effect frame uses only one of them.

### Outline rules

| Asset class | Outline | Interior lines |
|---|---|---|
| Pokémon sprites (front, back, icon) | A closed 1 art-pixel line around the whole silhouette in the sprite's darkest colour (palette index 3). No gaps, no double lines. Feet and base are outlined too. | Index 3 is allowed only for eyes, mouth, claws and the separation of overlapping limbs. Shading uses indices 1 and 2, never index 3. |
| Overworld characters | Index 3 of the character palette (`181010`), 1 px, as in `art/chars.py`. | Same palette slots as today. |
| Trainer portraits | 1 px line where layers meet (existing rule). | Checker dither on the right side only. |
| Terrain tiles (grass, path, water, floors) | None. Boundaries are made with the edge and corner tiles. | Detail uses the tile palette's own darker colours. |
| Objects standing on terrain (trees, rocks, signs, buildings, furniture) | 1 px in the tile palette's index 3. | |
| Battle backdrops | None on scenery. Platforms get a 1 px index 3 rim on the lower edge only. | |
| Effects: solid things (rocks, shards, ice, claws, leaves) | 1 px index 3. | |
| Effects: energy (fire, water, bolt, beam, wind, rings, sparkle) | None. They rely on a light core (index 0) and a dark edge pixel (index 3) on the outer frame. | |
| UI frames and icons | As in the UI table below (frames are double lines; icons use the darkest palette colour). | |

### Dither

* Pokémon sprites, characters and effects: **no dither**.
* Trainer portraits: 50% checker on the shaded side only (existing).
* Backdrops and tiles: 50% checker between two **adjacent** ramp indices only, in bands at most 8 px
  tall, and never in a sprite zone (see below).

### Environment palettes

**Overworld.** Draw everything in daylight colours. Time of day is a runtime tint and is never baked into
tiles (the lit windows at night are the one exception: they are drawn after the tint). Current tint
multipliers (`OverworldScreen.lightTint`): morning `1.00 0.96 0.88`, day `1.00 1.00 1.00`,
evening `1.00 0.84 0.72`, night `0.58 0.62 0.86`. Interpolation between phases is task G2.2 and changes
nothing in the art. Each map `environment` uses these tile palette families; a map may add the accent families listed.

| `environment` | Main tile palettes | Allowed accents |
|---|---|---|
| `grass`, `plain` | `outdoor`, `path`, `flowers`, `town` | `water`, `rocky`, `fence`, `sand` |
| `forest` (not a map value yet) | `outdoor`, `forest_gap`, `plant` | `path`, `water` |
| `sand` | `sand`, `rocky` | `water`, `outdoor` |
| `water` | `water`, `bridge` | `sand`, `rocky`, `outdoor` |
| `snow` | `snow`, `ice`, `mountain` | `rocky`, `water` |
| `cave` | `cave`, `cave_water`, `dark` | `rocky`, `ruins` |
| `indoor` | `floor_wood`, `wall_in`, `furniture`, `carpet`, `rug` | `plant`, `tv`, `bed` |
| `building` | `floor_tile`, `wall_in`, `tech`, `heal`, `mart_shelf` | `carpet`, `glass_door` |
| gyms, league | the matching `gym_<theme>*` set, `league` | `gym`, `puzzle` |

**Battle backdrops.** Each environment has three palettes: `sky` (back wall or horizon), `ground` (the floor
under and between the platforms) and `platform` (both mon platforms). The values below are the day colours,
lightest first, and are the single source in `art/palettes.py` (`BATTLE_ENV`). `python3 -I art/style_check.py`
fails if this table and the code disagree.

| Environment | Sky | Ground | Platform |
|---|---|---|---|
| `grass` | F0F8F8 C8E8F8 98D0F0 68A8E0 | F8F0C0 98D068 409048 183020 | E0F0A0 98D068 58A048 285830 |
| `tall_grass` | F0F8F8 C8E8F8 98D0F0 68A8E0 | D8F098 68B848 307838 102818 | C8E888 68B848 388040 183820 |
| `forest` | C8E0C0 90C090 58A068 286040 | A8D070 509840 286830 102818 | 98C868 58A040 307030 102010 |
| `cave` | E0C090 B89060 906038 281810 | E0C090 B08858 906038 281810 | D0B080 B88850 906038 281810 |
| `water` | F8F8F8 C8E8F8 98D0F0 6098D0 | E0F0F8 B8E0F8 4890E8 182860 | E8F0F8 98C8E8 5088C8 183060 |
| `snow` | F8F8F8 E0E8F8 B8D0F0 8098D0 | F8F8F8 D8E8F8 98B8E8 283868 | F8F8F8 C8D8F0 88A8D8 384880 |
| `sand` | F8F0D8 F8E0A8 F0C880 D8A060 | F8F0C0 F0D898 C8A060 583820 | F0E0A8 D8B878 B08850 583820 |
| `indoor` | F8F0E0 E8E0C8 C0B098 383028 | F8E0B0 D8B078 A07040 402818 | E8D098 C09860 906838 402818 |
| `building` | F8F8F0 E8E0C8 C0B8A0 383028 | F8F8F8 D8E0E8 A0B0C0 404858 | E8F0F8 B8C8D8 8898B0 404858 |
| `league` | E8D8F8 B090D8 8060B8 201040 | F0E8F8 C8B0E8 9070C8 301860 | E0D0F8 A888D8 7858B0 301860 |
| `gym_sprout` | C8F8A0 88D058 489838 183820 | E8F8C8 88D058 489838 183820 | C8F0A0 70C048 409030 183820 |
| `gym_tide` | D8F0F8 A8D8F0 6098D0 103878 | E8F0F8 A8D8F0 6098D0 103878 | E8C890 C09058 906038 402010 |
| `gym_spark` | C8D0E0 98A0B8 687090 101020 | F8F098 F0C020 A09040 383018 | B0B8D0 8890A8 687090 101020 |
| `gym_stone` | E0D8C8 B8A888 907860 282018 | E8E0D0 C8B898 A08868 282018 | D0C0A0 A89070 806850 282018 |
| `gym_mist` | F0E8F8 D0C0F0 9880D0 483878 | F0E8F8 D0C0F0 9880D0 483878 | E0D0F8 B8A0E8 8868C8 483878 |
| `gym_ember` | D8B8A8 B08070 885850 181010 | F8D880 F89830 C06020 301810 | E0A860 C07038 905028 301810 |
| `gym_frost` | F8F8F8 D0E8F8 90D0F0 3868B0 | F8F8F8 D0E8F8 90D0F0 3868B0 | E8F8F8 B8E0F8 78B8E8 3868B0 |
| `gym_dusk` | A898E8 8070C8 6050A8 281858 | B0A0E8 8068C8 6048A8 281858 | F8E8A0 C0A860 907838 281858 |

Where a backdrop is chosen, this is the intent (the lookup is task G3.1): `grass` and `plain` → `grass`;
`tall_grass` for encounters in tall grass; `forest` for forest maps; `water`, `snow`, `sand`, `cave`,
`indoor`, `building` map one to one; `gym_*` and `league` come from an explicit `battleBackground` key.

**Night variants are palette swaps, not new pictures.** Draw the day backdrop once. The night image is the same
pixels with each colour multiplied by `0.72 0.76 0.95` and rounded to a multiple of 8 (`night_variant()` in
`palettes.py`, the same factors `BattleScreen` uses today). The Artist only redraws night when something must
change that a swap cannot express (a lit window, a lamp, stars); those pixels still use the swapped palette.
Run `python3 -I art/style_check.py --markdown` for the night colours. `art/previews/style_palettes.png`
shows every environment, day then night, and every type ramp.

**Sprite zones and readability rules.** Pokémon have a dark outline, so a backdrop must not swallow it.
The sprite zones are the foe sprite (x 148-243, y 0-95), the player sprite (x 16-111, y 64-159) and the
platforms. Inside a zone:

1. Indices 0 and 1 of a palette are sprite-safe: relative luminance ≥ 0.12 (contrast ≥ 3:1 against the
   `181010` outline), day and night.
2. Index 2 may be used only as texture covering at most 25% of the zone; luminance ≥ 0.06.
3. Index 3 is never used inside a zone. It is for the horizon line, platform rim, borders and the area under the HUD.
4. Luminance falls strictly from index 0 to index 3 in every palette.
5. Each 8×8 cell uses colours of one palette (sky, ground or platform); up to 3 palettes per environment, 12 colours in total.

The check script enforces rules 1, 2 and 4 on all 18 environments, day and night.

**Type ramps (effects and sprite starting points).** Every type has a 4-colour ramp built from its main colour in
`art/palettes.py` (`TYPE_COLOURS`) by `type_ramp()`: highlight = main mixed 75% to white, light = 40% to white,
main, dark = main × 0.5, all rounded to multiples of 8.

| Type | Highlight | Light | Main | Dark |
|---|---|---|---|---|
| normal | E0E0D8 | C8C8A8 | A8A878 | 505040 |
| fire | F8D8C8 | F0B080 | F08030 | 784018 |
| water | D0E0F8 | A0B8F0 | 6890F0 | 304878 |
| electric | F0E8C0 | F0D878 | E8C028 | 706010 |
| grass | D8F0D0 | A8D890 | 78C850 | 406028 |
| ice | E0F0F0 | B8E0E0 | 88D0D0 | 406868 |
| fighting | E8C8C0 | D88078 | C03028 | 601810 |
| poison | E0C8E0 | C088C0 | A040A0 | 502050 |
| ground | F0E8D0 | E8D0A0 | D8B860 | 706030 |
| flying | E0E0F8 | C8B8F0 | A890F0 | 504878 |
| psychic | F8D0E0 | F898B8 | F85888 | 803040 |
| bug | E0E8C0 | C8D078 | A8B820 | 506010 |
| rock | E8E0C8 | D0C088 | B8A038 | 605020 |
| ghost | D8D0E0 | A898C0 | 705898 | 383050 |
| dragon | D8C8F8 | A888F8 | 7038F8 | 382080 |
| dark | D8D0D0 | A89890 | 705848 | 383020 |
| steel | E8E8F0 | D0D0E0 | B8B8D0 | 606068 |
| fairy | F0E0E8 | F0C0D0 | E898B0 | 705058 |
| stellar | C8E0F0 | 88C8D8 | 40A8C8 | 205060 |

The runtime `Palette.TYPE` in `core/.../gfx/Renderer.kt` differs from `TYPE_COLOURS` for five types
(electric, ice, ground, fairy, stellar; `style_check.py` prints the values) and is not on the multiple-of-8 grid.
**Decision: `art/palettes.py` is canonical.** Task G5.1 (single palette source) should move `Palette.TYPE` to it.
Until then, art uses the table above and the UI keeps its current colours.

### Pokémon sprites

The contract below matches `SpriteStyler` (the converter for the local override), so original and
locally imported sprites look and behave the same. Original art is public; official art stays in `assets-local/` only.

| Kind | File | Size | Grid | Notes |
|---|---|---|---|---|
| Front | `front/<speciesId>.png` | 96×96 | 48×48 at 2× | Faces the player, drawn at the foe position. |
| Back | `back/<speciesId>.png` | 96×96 | 48×48 at 2× | The same creature seen from behind, drawn at the player position. A separate drawing, not a mirror of the front. |
| Icon | `icon/<speciesId>.png` | 32×32 | 16×16 at 2× | Party, box and overworld. Readable at 24×24 on screen. |

Rules:

* **Palette:** at most 4 opaque colours, ordered lightest to darkest as [highlight, base, shade, outline].
  Transparent background, alpha 0 or 255. Channels are multiples of 8. Start from the type ramp
  (highlight, light/base, main/shade-ish, dark/outline) and adjust by eye; dual types use one type for
  base and the other for accents.
* **Outline:** closed 1 art-pixel line in index 3, see the outline table. Index 3 luminance ≤ 0.06 so it separates the sprite from every backdrop.
* **Anchor:** the lowest opaque row touches the bottom row of the canvas (row 47 of the grid, row 15 for icons) and the
  silhouette is horizontally centred within ±1 grid pixel. The sprite is drawn over the platform; its bottom row sits
  8-10 logical px below the platform's vertical centre line (today foe 96 vs 86, player 160 vs 152).
* **Size classes (guidance, confirm in the Tier 1 pilot):** tiny ≤ 20 grid px tall, small ≤ 32, medium ≤ 40, large ≤ 46,
  huge 48 (fills the canvas). Pick the class from the species' height so a roster reads at the right relative scale.
  Sprites do not touch the left or right canvas edge, except huge ones.
* **No** dither, no anti-aliased edges, no ground shadow baked into the sprite (the platform gives it), no text or numbers.
* **Front and back must differ:** the back view shows the back of the head, tail and spots, never the face.
* **Shiny:** do not draw a second PNG. Give four hex colours in the same index order (outline stays index 3
  and luminance must still fall from index 0 to 3). The shiny loader (task G4.2) swaps the palette. It must also work on locally imported sprites.
* **Icon:** a simplified head or full-body view in the same palette as the front sprite; readable silhouette first, detail second.
* **File and id rules:** lowercase snake_case speciesId as in `species.json`. Forms use the existing id.

Compatibility with the override: `SpriteStyler` writes icons at 16×16, original art uses 32×32. Every draw site I
checked (`MiscScreens`, `OverworldScreen`) scales the icon to an explicit rectangle, so both sizes render. Task G0.2's
`ArtValidator` must therefore accept 16×16 or 32×32 for icons and 96×96 for front and back, and the lookup order stays
local override → bundled → `baseSpecies` → fallback.

### Battle backdrops and platforms

* **Backdrop:** `bg_<environment>.png`, 256×160, covering the area above the text box. Sky above a horizon line at about
  y 64-80 (above the foe platform centre at y 86), ground below. Keep detail out of the two HUD areas, where text and bars are
  drawn: foe HUD x 8-120, y 4-38; player HUD x 136-249, y 96-149 (positions from `BattleScreen.drawFoeHud` and `drawPlayerHud`).
* **Colours:** only the environment's three palettes, per the readability rules above. Night = palette swap.
* **Platforms:** `platform_foe_<environment>.png` 112×24 and `platform_player_<environment>.png` 128×24 (today's ellipses are
  104×18 and 120×18 at centres (196, 86) and (64, 152)). One palette (`platform`), an elliptical top face in indices 0-1,
  a 1 px index 2 rim and a 2 px index 3 front band. Transparent outside the shape.
* **Perspective:** top-down three-quarter, flat horizon, no vanishing-point lines. The foe platform is smaller than the player's.
* **Motion:** none in the backdrop for the pilot. Animated backdrops (water shimmer) come later as 2-4 frame palette cycles.

### Effect style guide

Effects replace the procedural `MoveFx` styles, which stay as the fallback when a sheet is missing.

**Effect classes (frame sizes).**

| Class | Frame | Density | Use |
|---|---|---|---|
| S | 32×32 | 1× (1 art px = 1 logical px) | Particles, sparkles, stat arrows, hearts, small bursts. |
| M | 64×64 | 2× (a 32×32 art grid) | Bodies of flame, splashes, claws, rock falls, rings. Matches the sprite density. |
| B | 32×32 cells: head, body (tiles along the beam), tail | 1× | Beams and waves. The body cell tiles horizontally. |

A sheet is one row of 4-8 frames, left to right, transparent background. One frame uses one class only.
Frame time defaults to 80 ms; the total length of an effect stays in `styleDurations` of `assets/anim/battle.json`,
so retiming needs no art change.

**Colour.** Each frame uses at most 4 colours, in the order [highlight, light, main, dark], with transparency.

* Generic effects (`impact`, `slash`, `beam`, `heal`, `stat`, `status`, `sparkle`) are drawn once in the neutral order and recoloured
  at runtime by index with the move type's ramp (the same palette-swap mechanism as shiny). This needs task G3.2 to support index remapping.
* Type effects are drawn directly in their type ramp from the table above, in the same index order, so they can still be remapped.
* Use the highlight for a core of at least 2 art px and the dark colour for an edge pixel on the outer
  frame. Measured worst case: against every sprite-safe backdrop colour, day and night, the better of highlight and
  dark has contrast ≥ 2.0:1 against the backdrop (the check script prints the real minimum).

**Shape and motion, not colour alone.** Every type effect has its own motif so it reads without hue:

| Effect | Class | Frames | Motif |
|---|---|---|---|
| `fire` | M | 6 | Teardrop flames that rise and flicker; tips cut off in 2 px steps. |
| `water` | M | 6 | Round drops that fall into a splash crown. |
| `electric` | M | 4 | Zig-zag bolt with 2 px kinks; frames alternate flips. |
| `grass` | M | 6 | Four-point leaves that spin and drift. |
| `ice` | M | 5 | Hexagonal crystal shards that grow from a point. |
| `fighting` | M | 4 | Fist burst: a star with four straight rays. |
| `poison` | M | 6 | Round bubbles with a highlight pixel, rising, with one drip. |
| `ground` | M | 5 | Crack lines plus a dust puff that spreads sideways. |
| `wind` | M | 6 | Curved arcs and chevrons moving across. |
| `psychic` | M | 6 | Concentric 2 px rings that pulse outward. |
| `bug` | S | 4 | A swarm of 2-3 px dots on a zig-zag path. |
| `rock` | M | 5 | Angular chunks that fall and bounce once. |
| `ghost` | M | 6 | A flame-like wisp with two dark eye pixels. |
| `dragon` | M | 6 | A curved claw cone or a spiral, wider than a slash. |
| `dark` | M | 5 | A ring that converges inward on the target. |
| `steel` | M | 4 | A diagonal glint with a cross-shaped flash. |
| `fairy` | S | 6 | Four-point twinkles and small hearts. |
| `slash` | M | 4 | Three diagonal strokes drawn in sequence. |
| `impact` | M | 4 | Eight-point burst that expands and breaks up. |
| `beam` | B | 4 | A beam body with a round head and a tapered tail. |
| `heal` | S | 6 | Plus signs rising. |
| `stat` | S | 4 | Up: chevrons rise. Down: chevrons fall. The arrow direction carries the meaning. Two rows. |
| `status` | S | 4 | Five rows: burn (flame), paralysis (sparks), poison (bubbles), sleep (Z), freeze (crystals). |
| `sparkle` | S | 6 | A four-point star that twinkles. |

**Safety and accessibility.**

* No more than 3 full-screen flashes in any one second, and a flash uses only highlight or light colours, never the dark colour.
* Every effect has a static fallback frame. With REDUCE MOTION on, flashes and shakes become that single frame; with BATTLE ANIM OFF
  the effect is skipped (`AnimationPlayerTest` already protects this).
* Effects never cover the HP bar, the status text or the message box.
* Gimmick transformations (Mega, Z, Dynamax, Tera) use the palettes of their icons in `gimmick_icons.png` (`art/ui.py`), so the
  menu icon and the effect belong together.

### Review checklist

A reviewer (Designer, then CEO for pilots) checks, and QA attaches the output:

1. Size, grid and frame count match this section; at most 4 colours per frame; channels are multiples of 8; binary alpha.
2. Outline follows the table; light comes from the upper left; no dither or glow where forbidden.
3. A backdrop passes the sprite-zone rules and `python3 -I art/style_check.py` exits 0; night is a swap.
4. A sprite is bottom-anchored and centred, front and back differ, and it reads against the darkest and lightest backdrops (a
   screenshot of grass day and cave night is enough for the pilot).
5. Effects read on a light backdrop (snow) and a dark one (cave night), and keep the motif of their table row.
6. Nothing is traced or derived from official art, and nothing overwrites an `assets-local/` or `imported/` path.
7. Screenshots are 256×224 native, or an integer multiple, never smoothed.

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
hat style, 13 rows, placed at row 10 of the 32-row cell), a body template (`std`, `skirt` or `robe`, 4 rows stretched
to 8) and a legs template (2 rows stretched to 4) are combined. Template letters (`h` hair, `a` hat,
`o` outer top, `c` inner top, `d` pants/skirt, `l` legs, `b` beard) map to
palette slots per character in the `CHARACTERS` table. The sheet has no right-facing row:
the game flips the left row (`Assets.character`). Dark hair gets 2-3 white shine pixels.

To add a character, add a row to `CHARACTERS` with a role colour, head style, body,
letter mapping and accessories (`glasses`, `beard`, `moustache`, `headband`,
`nursecap`, `goggles`). The trainer portrait is generated from the same row by
`art/trainers.py`. To add a new hairstyle, add `down`, `up` and `left` grids to
`HEADS` in `chars.py` and a matching branch in `hair_masks()`/`hat_masks()` in
`trainers.py`.

`player_m`/`player_f` also have `_run` (48×96, same layout; steps dip 2 px and lean), `_bike` and `_surf`
(96×96, 32×32 frames, 3 directions x 3 frames). The surf creature is an original pale sea-beast with
role-coloured spots. `:tools:validateContent` rejects wrong-size sheets, frames whose feet are not on the cell's last
row, and frame-top jumps over 2 px within a direction (`ContentValidator.validateCharSheets`).

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
