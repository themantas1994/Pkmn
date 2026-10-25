# GBA-style graphics upgrade spec (PKM-65)

Spec only (board request PKM-62). No implementation, no asset regeneration. Style reference: the Game Boy Advance
generation (FireRed/LeafGreen look). All art stays original and CC0 (§8). Builds on `docs/ART_STYLE.md` (current GBC
look) and `docs/UI_SPEC.md` (PKM-49, branch `pkm/PKM-49`, not yet on main). Figures marked "~" describe the hardware
generation's usual practice and are targets for our own art, not measurements of any game's files.

## 0. Decisions needed from the board

| # | Decision | Recommendation |
|---|---|---|
| D1 | Change the logical screen from 256×224 to GBA 240×160? | **Not by midnight.** Every screen (battle, party, bag, dex, region map, 223 map views) is laid out for 256×224. 240×160 is a re-layout of all of them. Keep 256×224 for now; do the GBA look (palettes, sprites, tiles, battle UI) inside it. Switch in a later phase (P5) if wanted. |
| D2 | Pokémon sprites: public builds use generated placeholders. | Out of scope here. GBA-style 64×64 art for 1000+ species cannot be original and hand-made. Only the placeholder generator is in scope (§3.3). |
| D3 | Android scaling mode | The request says "reuse the sharp-bilinear mode". **No such mode exists in the repo** (grep for bilinear: none). Today: framebuffer is `Nearest` and scale is an integer (`PkmnGame.resize`, `PkmnGame.kt:82,245-249`). §1 specifies it as new work. |

## 1. Target resolution and scaling

| Item | Now | GBA target |
|---|---|---|
| Logical screen | 256×224 | 240×160 (15×10 tiles of 16 px). Gated by D1. |
| Tile / metatile | 16×16 | 16×16 metatile built from four 8×8 tiles (§4) |
| Colour depth | 15-bit, 4 colours per tile | 15-bit, 16 colours per palette (§2) |
| Scaling | integer, nearest | integer + sharp-bilinear (below) |

**Sharp-bilinear (new, ~40 lines in `PkmnGame` render/resize):**
1. Render to the existing FBO at logical size, `Nearest` (unchanged).
2. Pick `k = max(1, floor(min(W/Lw, H/Lh)))` (integer pre-scale) and draw the FBO to the screen with a `Linear` filter at the
   exact fractional size `min(W/Lw, H/Lh)`; blur is confined to a 1/k-pixel seam between source pixels. Use a fragment
   shader that clamps the UV to the nearest texel centre ± `0.5 * (1 - k/s)` (standard sharp-bilinear), where `s` is the
   fractional scale.
3. Option `Options.scaling = INTEGER | SHARP` (default `SHARP` on Android, `INTEGER` on desktop). Integer remains for
   pixel-perfect screenshots; `ScreenshotCompare` must keep using `INTEGER` so references do not change.
4. Letterbox stays black; touch layout (`TouchControls.layout`) already takes `gameRect`, so no change.

Examples: 1080×2400 portrait phone at 256×224 → fractional 4.21 (now 4× with 4% unused); at 240×160 → 4.5.

## 2. Palette rules

* **Format:** each palette is 16 slots: slot 0 is transparent, slots 1-15 are colours. That is "15 colours + transparent".
  Every channel a multiple of 8 (15-bit), same rule as now.
* **Tilesets:** one tileset uses up to **6 palettes** (6×15 = 90 colours); the world uses a shared primary set (outdoor
  greens, path, water, roofs, buildings) plus a per-map-type secondary set (interior, cave, gym, etc.). Total per map
  view: at most 13 palettes, matching the hardware generation's split.
* **Sprites:** one 16-colour palette per sprite sheet; overworld NPCs may share one of **8 sprite palettes** per map
  (hardware limit ~16 in OAM, we cap at 10 for tooling simplicity). Trainer portrait and overworld sheet of the same id keep
  the same palette (existing rule, `ART_STYLE.md` "Palettes").
* **Pokémon battle sprites:** own 16-colour palette each; front and back share it; shiny is a second 16-colour palette.
* **Slot convention** (so recolours are mechanical): 0 transparent, 1 outline (darkest), 2-3 shadow, 4-8 base ramp,
  9-12 highlight ramp, 13-15 accent. Skin and eye whites sit in fixed slots across all humans (slots 4, 9, 13).
* **Day/night:** tint is applied to **palettes at load time**, not as a screen overlay. Today the overlay is
  `Renderer.NIGHT = (0.10, 0.12, 0.35, 0.38)` (`Renderer.kt:102`) and battles use `bg.mul(0.72, 0.76, 0.95)` (`BattleScreen.kt:673`).
  New: `Palette.tinted(timeOfDay)` produces morning/day/evening/night variants per palette (multiply + lift), computed once
  per map load. Rules: the outline slot never goes lighter than slot 2 at night; window and lamp tiles are in a **no-tint
  palette** (`lit`) so they glow at night; UI palettes are never tinted; interiors and caves are never tinted.
  The existing four time-of-day screenshots (`docs/screenshots/town_*.png`) are the visual regression set.

Validator: for each PNG, ≤15 opaque colours + alpha, all channels multiples of 8, and the sheet's palette matches its
declared palette name (replaces the current "≤4 colours" check in `art/build_all.py`).

## 3. Sprites

### 3.1 Overworld characters
| Sheet | Now | GBA target |
|---|---|---|
| Walking NPC / player | 16×16 cells, 48×64 sheet (3 cols × 4 rows: stand, stepA, stepB × down/up/left/right) | **16×32** cells (head overlaps the tile above), 9 frames: stand + 2 steps × {down, up, left}; right = left mirrored at draw time. Sheet 48×96. |
| Run (player only) | none | 9 frames, same layout, 16×32; sheet 48×96 |
| Bike (player m/f) | 48×64 | **32×32** cells, 9 frames (3 directions), sheet 96×96 |
| Surf (player m/f + creature) | 48×64 | 32×32, 3 frames × 3 directions (player seated on creature), sheet 96×96 |
| Animation timing | 250 ms per frame | walk 8 frames of motion per 16 px step (player 4 px per 2 game frames), step frame every 8 game frames at 60 fps = 133 ms; run 2× faster |
| Count | 50 sheets in `assets/gfx/chars` | 50 walk sheets + 2 run + 2 bike + 2 surf redraws (players only; NPCs need no run/bike) |

16×32 requires an NPC draw offset (`y - 16`) and y-sorting by the lower tile; a rendering change in `OverworldScreen`, not
only art.

### 3.2 Trainer sprites
Battle-intro portraits: **64×64** front, 16-colour palette, full bust rather than the current cropped upper body
(`assets/gfx/trainers`, 48 files at 64×64 today, ≤4 colours). Player back sprites `player_m_back`/`player_f_back`: 64×64
(enemy-side mirror of the front pose is not allowed; draw separately). Entry animation: slide in from the screen edge over 20
game frames, then 2-frame throw (hand up, hand forward), as an animation sheet `trainer_throw.png` (64×128).

### 3.3 Pokémon battle sprites
* Front 64×64, back 64×64 (back is drawn at 2× the front's apparent size on hardware; we store a native 64×64 and draw at 1×, with
  a 96×96 optional "close-up" upscale only if a species opts in). 16 colours incl. transparent.
* Animation: 2-frame idle (front only), 12-frame bob for the player's back sprite is not required. Faint = slide down 8 px over
  8 frames with clip. Existing `BattleScreen.kt:730-735` tint/clip code stays.
* Public builds: the placeholder generator (`SpriteStyler`, 48-px grid ×2, ≤4 colours) is upgraded to 16-colour output and
  64×64 canvas. Personal builds with imported sprites keep the existing path (`docs/ASSET_IMPORT.md`); they are not
  redistributed.
* Party/menu icons: 32×32, 2 frames, 16 colours (current 24 px icons in `PartyScreens`).

## 4. Tilesets

* **Layers** (3, drawn in this order): (1) **ground** (terrain, paths, water, floors), (2) **objects** (trees, signs,
  buildings, furniture; drawn above ground, below the player), (3) **overhang** (tree tops, roof edges, bridge rails, cave
  arches; drawn above the player). A metatile holds two of the three: *normal* = ground+objects, *covered* = ground+overhang
  (bottom half walkable-under), *split* = objects+overhang. This is the hardware layer-type model and replaces the
  current single-layer `world.json` tile + separate `*_top` tile names.
* **Tile construction:** a 16×16 metatile = four 8×8 tiles ×2 layers. The sheet stores 8×8 tiles (deduplicated, h/v flip allowed)
  and `world.json` stores a metatile table (`ground[4]`, `top[4]`, `type`, `palette`, `solid`, `behaviour`). Flip is the
  main size saver: 16 px symmetrical objects cost 2 tiles instead of 4.
* **Budget per tileset (primary / secondary):** primary 512 tiles (8×8) + ~512 metatiles; secondary 512 tiles + ~512
  metatiles. Today: 207 named 16×16 tiles in one 256×272 sheet.
* **Autotile rules** (replace the hand-named `path_edge_n`/`water_nw` variants; data in `world.json`, logic in tools):
  1. 8-neighbour bitmask (N,NE,E,SE,S,SW,W,NW; diagonal counts only if both adjacent orthogonals are set), 47 unique
     results per autotile family, mapped through a table to metatile ids.
  2. Families: grass↔path, grass↔water (shore 4 px strip), grass↔tall grass, ground↔cliff, floor↔carpet, cave floor↔wall.
  3. Water and tall grass animate (4 frames, 250 ms existing `frameMs`); flowers 4 frames; ledge and door tiles are never
     autotiled.
  4. The map compiler resolves autotiles at compile time from a logical "terrain" paint layer, so `world/maps` files are
     unchanged for authors (the compiler picks metatile ids, the same way `art/previews.py` `autotile_path/water` do today).
* **Tile counts per map type (metatiles used, target):**

| Map type | Metatiles | Palettes | Notes |
|---|---|---|---|
| Town / city (p4a/p4b/p4c style) | 160-220 | 6 + 6 | houses, roofs ×4 colours, fences, flowers |
| Route | 100-160 | 6 | grass, trees, water edges, ledges, signs |
| Cave / mine | 80-120 | 4 | floor, wall autotile, boulders, water |
| Interior (house, centre, mart) | 90-140 | 6 | per-room set, shared primary |
| Gym / special | 60-100 | 4-6 | theme palette per gym |
| Dungeon / ruins / league | 80-120 | 4-6 | puzzle tiles, dark variants |

## 5. Battle UI

All sizes in logical pixels at 240×160; at 256×224 add 8 px margin each side and the vertical difference becomes the
backdrop (the same layout, centred). Consistency with `UI_SPEC.md`: one `frame`, one HP-bar helper, `pixel8` default,
cursor `textX - 10`, multiples of 8.

* **Backgrounds:** one 240×112 backdrop (sky/ground, 16 colours) per environment, plus two 64×16 platforms (foe ellipse,
  player ellipse), as separate sprites so they can be tinted for time of day. Environments: grass, tall grass, water, cave,
  indoor, sand, snow, rock, building, gym, league (11). Replace the current single-colour backdrops.
* **Foe HUD** (top-left, ~104×40, frame at x=8, y=12): name (left), level (right), status icon, HP bar 48 px + `HP` label,
  no numbers. Caught-ball marker 8×8 left of the name if the species is registered.
* **Player HUD** (bottom-right, ~104×40, frame at x=128, y=76): name, level, HP bar 48 px, `hp/max` right-aligned (`pixel6`),
  EXP bar 64 px below, 1 px white/dark outline. HUD slides in from off-screen over 12 frames at battle start.
* **Text box:** full-width bottom, 240×48 (frame_alt, the grey box from UI_SPEC §2.1), 2 lines of `pixel8`, 8 px padding,
  `down_arrow` blinking at bottom right, 30 characters per line.
* **Menu layout:** action menu 2×2 (FIGHT, BAG, POKéMON, RUN) in a 112×48 frame at x=128 right of the text box; text box
  shrinks to 128 px wide while the menu is open. Move menu: 4 moves in a 2×2 grid 144×48 plus a 96×48 info frame (PP, type
  chip) to the right. Replaces the current stacked blue-on-grey frames (UI_SPEC F4).
* **Font:** keep `pixel8`/`pixel6` (`assets/fonts`), original. GBA-style option: new 8×16 "gba8" cell (variable width 5-8 px,
  1 px shadow in slot 2) for dialogue and menus; `pixel6` remains for numbers. Recommended in P4 only; the current font
  already conforms to the UI_SPEC rules.

## 6. Gap analysis

| Area | Current (from `assets/` and `STATUS.md`) | Gap |
|---|---|---|
| Screen | 256×224, integer/nearest | Add sharp-bilinear; 240×160 re-layout (D1) |
| Palettes | 4 colours, `art/palettes.py`, per tile | 16-slot palette model, per-sprite palettes, palette tint |
| Tiles | 207 named 16×16 tiles, 82 distinct 4-colour palettes, 17 animated; 256×272 `world.png`; edge tiles hand-named | 8×8 tile + metatile table, 2 layers, autotile bitmask |
| Overworld chars | 50 sheets 48×64, 16×16, 3 frames | 16×32, 9 frames, run/bike/surf redraw |
| Trainers | 48 portraits 64×64, ≤4 colours | 16 colours, bust, throw sheet |
| Pokémon | placeholders (`SpriteStyler`, ≤4 colours) | 16 colours, 64×64 |
| Battle UI | single-colour backdrop, rect-drawn bars, `frame_alt` + blue frame stacked | backdrops ×11, HUD boxes, HP/EXP skins, menu layout |
| Day/night | overlay `Renderer.NIGHT`, battle `mul` | palette tint |

**Pipeline changes.** Note: art sources are the generators in `art/*.py` (`python3 -I art/build_all.py`), outputs are
committed to `assets/gfx`; `data-src/` holds only PokeAPI/Showdown data and is **not** touched. `:tools` handles content
validation and the sprite styler.
* `art/palettes.py`: 16-slot palettes, palette names per tileset; `art/tiles.py`: emit 8×8 sheet + metatile table; `art/chars.py`:
  16×32 cells and new frame layout; `art/build_all.py`: new colour/size validators (§2), contact-sheet previews updated.
* `:tools` `SpriteStyler`/`SpriteLayout` (Kotlin): 16-colour quantiser (median-cut, fixed slot convention), 64×64 canvas;
  `SpriteStylerTest` (28) updated.
* `:tools` `MapCompilerTool` + `ContentValidator`: terrain layer → autotile resolution, metatile/palette-count limits, layer
  type, new sprite sizes; `AssetManifestTool` hashes unchanged.
* `core`: `Assets`/`Renderer` (palette tint, 16×32 draw offset, three layers), `OverworldScreen`, `BattleScreen`, `PkmnGame`
  (scaling shader), `Options`.
* `docs/ART_STYLE.md`, `docs/WORLD_FORMAT.md`: update with the final numbers.

## 7. Phased plan (ordered by visual impact)

Sizes are one agent working serially. "Now" is 2026-10-25 ~10:00 WET; deadline 2026-10-25 23:59.

| Phase | Scope | Size | By midnight? |
|---|---|---|---|
| P1 Palettes + validators | 16-slot model, tint-at-load, `build_all.py` validators, no art change (same pixels, new palette data) | S (2-3 h) | **Yes** |
| P2 Battle UI | backdrops ×11 (reuse palettes), HUD/text/menu layout per §5, UI_SPEC PRs 1-3 first | M (5-6 h) | **Partly**: UI_SPEC PR 1 must merge first; backdrops + HUD feasible, menu re-layout likely not |
| P3 Scaling | sharp-bilinear shader + option (§1) | S (2 h) | **Yes** (independent) |
| P4 Overworld characters | 16×32, 9 frames, run/bike/surf for players; 50 NPC sheets | L (1.5 days) | **No**; players only (4-6 sheets) is feasible after P1 |
| P5 Tilesets | metatile model, layers, autotile, redraw 207 tiles to 16 colours | XL (3-5 days) | **No** |
| P6 Trainers + Pokémon placeholders | 48 portraits, styler 16-colour/64×64 | L (1-2 days) | **No** |
| P7 240×160 re-layout (D1) | all screens | XL (3-4 days) | **No** |

Feasible by midnight: **P1 + P3**, plus the P2 backdrops and HUD if PKM-49's helper PR merges first. Everything else is multi-day.

Per-phase acceptance criteria and verification (all phases also run the CI gates in `CLAUDE.md`):

* **P1:** no tile/sprite changes beyond palette data; every palette has ≤15 colours + transparent; `tinted()` for four times of
  day matches `docs/screenshots/town_*.png` within a per-channel ±8; interiors/caves untinted.
  Verify: `python3 -I art/build_all.py` then
  `./gradlew :tools:compileMaps :tools:checkProgression :tools:validateContent :tools:buildAssetManifest`,
  `git diff --exit-code -- assets/`, `./gradlew :engine:test :core:test :tools:test`.
* **P2:** backdrop per environment, HUD boxes at the §5 positions, no stacked grey/blue frames; `BattleMenusTest` passes.
  Verify: same commands + `./gradlew :android:assembleDebug` + emulator screenshots of wild, trainer, cave and night battles.
* **P3:** `SHARP` on Android gives no visible seams at scales 3.0, 4.2 and 4.5, `INTEGER` output is byte-identical to the
  current screenshots. Verify: `ScreenshotCompare` (`:tools`) with `INTEGER`; emulator screenshot at 1080×2400 and a tablet.
* **P4:** sheet sizes and frame counts as §3.1, walk loop has no 1-frame pop, NPC feet align to tile; `validateContent`
  rejects wrong-size sheets. Verify: `art/build_all.py`, `:tools:validateContent`, emulator walk/run/bike clip.
* **P5:** `compileMaps` reports 0 errors for all 223 maps with autotiles resolved; metatile/palette limits enforced per
  tileset; all map screenshots unchanged apart from intended art. Verify: compile gates, `git diff --exit-code -- assets/`,
  emulator spot check of one map per map type (§4 table).
* **P6:** `SpriteStylerTest` updated and green, 64×64 canvas, ≤16 colours, full-roster run completes (6,492 sprites per
  STATUS.md). Verify: `:tools:test`, `./gradlew :android:assemblePersonalDebugWithSprites` (local only).
* **P7:** every screen fits 240×160 with no clipped text; `UI_SPEC` metrics hold. Verify: emulator screenshot of each screen.

## 8. Licence note

Original art only. Nothing is ripped, traced, recoloured or converted from Nintendo, Game Freak or Creatures games, ROMs or
fan-made rips; no reference images are committed. FireRed is a **style reference only** (resolution, palette discipline,
layering, layout proportions). New files are CC0-1.0 and listed in `assets/ATTRIBUTION.json`. Personal builds with imported
Pokémon sprites keep the existing local-only path (`docs/ASSET_IMPORT.md`) and are never distributed.

## 9. Risks

* D1 is the biggest cost; the visual gain of 240×160 is small compared with palettes, 16×32 characters and metatile layers.
* 16×32 characters change y-sorting and collision visuals; needs an `OverworldScreen` change that the Game Developer owns.
* The sharp-bilinear shader must be tested on Android GL ES 2.0 devices (libGDX default); the fallback is `INTEGER`.
* Concurrent work on `Renderer.kt` (PKM-49 PR 1) conflicts with P2; merge order matters.
