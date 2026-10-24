# UI consistency spec (C2, PKM-49)

Scope: battle, party, bag and Pokédex screens. Spec only; no code in this change. Base: the UI section of
`docs/ART_STYLE.md` (PKM-38 style sheet) and the draw code as of this branch. Logical screen is 256×224, whole-number
scaling, nearest-neighbour only. Out of scope: new features, map art, sprites.

## 1. Findings (current state)

| # | Finding | Evidence |
|---|---|---|
| F1 | HP bar is drawn three ways. Battle: `HP` label + 1 px black outline, grey track, 3 px fill (`hpBar`). Party: no label, 82×5 outline, no grey track, 80 px fill. Summary prints `HP  n/n` as text only. | `battle/BattleScreen.kt:749`, `screens/PartyScreens.kt:222-225`, `PartyScreens.kt:270` |
| F2 | `ui/hp_bar.png` + `.json`, `exp_bar.png`, `status_icons.png`, `type_icons.png` are shipped and documented but no code loads them. Bars are `rect()` calls; status is red text; types are coloured rects. | grep of `core/src/main` finds no use |
| F3 | HP colour thresholds are duplicated (`f > 0.5`, `f > 0.2`) in two places. | `BattleScreen.kt:753`, `PartyScreens.kt:224` |
| F4 | Menu frame: `frame` (blue double line) is used for menus, but the battle bottom panel uses `frame_alt` (grey) and then draws `frame` boxes on top of it (action menu, move menu). Result is a grey box containing blue boxes. | `BattleScreen.kt:693`, `:848`, `:860`, `:870`, `:883`, `:887` |
| F5 | Party and Dex list screens fill the whole screen with `WINDOW` and draw no outer frame; Bag and Dex header use frames. Party selection is a full-width highlight band, bag/dex use only the cursor. | `PartyScreens.kt:208-213`, `BagScreen.kt:162-166`, `MiscScreens.kt:39-51` |
| F6 | Cursor x/y offsets differ per screen: battle `x-10`, menus `x+8`, bag `10`, dex `8`, party `0` (inside the highlight band, overlapping the icon), start menu `W-90`. Text origin differs too (18, 20, 18, 68, 40). | `BattleScreen.kt:855,863,879`, `Widgets.kt:120`, `BagScreen.kt:181`, `MiscScreens.kt:62`, `PartyScreens.kt:226`, `:60` |
| F7 | Row pitch varies: 13 px (battle move/gimmick lists), 14, 16 (bag, dex, `Menu`, start menu), 28 (party). Battle action menu uses 14 or 18. | same files |
| F8 | Font size choice is ad hoc: `pixel6` is used for labels (HP, EXP, move stats, party HP numbers) and, via `small = name.length > 14`, for long names only. Disabled text uses `SHADE`, which is also the HP track colour. | `BattleScreen.kt:879`, `:750` |
| F9 | Two text-white values exist: `WINDOW` (f8f8f0) as background and `WHITE`; type labels use `WHITE` on type colours with no contrast check. | `PartyScreens.kt:253` |

## 2. Unified rules

All values are logical pixels. Names in code stay `Palette.*`; new constants go in `gfx/Renderer.kt` (`object UiMetrics`)
so every screen reads one source.

### 2.1 Menu frame
* Every menu, list panel, description box and dialogue box uses `ui/frame.png` (blue double line) via `Renderer.frame(...)`.
* `frame_alt` (grey) is only for the battle message box (`Menu.NONE` text in `drawBattle`). The battle bottom area must not
  draw a grey `frame_alt` panel behind menus. Menus replace it: `Menu.ACTION`, `Menu.GIMMICKS` and `Menu.MOVES` draw
  `frame` only; the grey box is drawn only when `menu == NONE`.
* Frame bounds are multiples of 8 px in x, y, w, h (the 9-slice corner is 8 px) so the double line never lands on a half tile.
* Inner padding: 8 px from the frame's outer edge to the first text column for left-aligned lists, 6 px top.
* Full-screen list screens (Bag, Party, Dex list) get one frame around the list area and one around the description/prompt
  area. No unframed `WINDOW` fill is visible except as background behind frames.

### 2.2 Font
* `pixel8` (dark ink `TEXT`) is the default for all names, labels and menu items.
* `pixel6` only for: (a) description and help text, (b) numeric stat columns (HP n/n, POW, ACC, PP), (c) a move or
  item name that does not fit in 14 chars at `pixel8`. Never for menu command labels (FIGHT, BAG, ...).
* Menu text is upper-case (items, moves, commands). Prose (dialogue, descriptions) keeps sentence case.
* Disabled entries use `GREY` (787878), not `SHADE`, so they stay distinct from the HP track and meet contrast on `WINDOW`.
* White text is only drawn on coloured fills (type chips, `WHITE`); on `WINDOW` always use `TEXT`.

### 2.3 Cursor
* One cursor: `ui/cursor.png` 8×8, drawn with `Renderer.cursor(x, y)`.
* Cursor position is derived from the row, not hand-placed: `cursorX = textX - 10`, `cursorY = textY` (same baseline
  as the text it points at). Text columns: list text starts at frame x + 18, so the cursor sits at frame x + 8.
* Party keeps its highlight band (`HIGHLIGHT`) but the cursor moves to x = 0 → 4 and the icon to x = 12 so they do not overlap.
* Idle cursor does not blink. Only `down_arrow.png` blinks (500 ms, per ART_STYLE).
* Move sound `menu_move` on every cursor change, in all four screens (battle and `Menu` already do; Bag, Dex, Party must).

### 2.4 Row spacing
* Single-line list rows: 16 px pitch. Battle move and gimmick lists (4 rows in a 64 px frame) are the one exception at
  13 px and stay as is; no other screen may use 13 or 14.
* Party rows: 28 px pitch (icon 24 px + 4 px).

### 2.5 HP bar skin
* One helper: `Renderer.hpBar(x, y, w, fraction)` replacing both private implementations. One function `Palette.hpColor(f)`
  holds the thresholds (green above 50%, yellow above 20%, red otherwise, matching ART_STYLE).
* Skin: `ui/hp_bar.png` (64×8) as the frame with the fill rect `{x:15,y:3,w:48,h:2}` from `hp_bar.json`, scaled in x for wider
  bars by stretching the middle (or, if the bar must be 80 px wide, keeping the label cell fixed and stretching the track).
  The `HP` label is part of the PNG; remove the text label.
* If the PNG is judged unfit on the emulator, the fallback skin is: 1 px `TEXT` outline, `SHADE` track, 2 px fill, same size
  everywhere. Pick one; both do not coexist.
* Bar widths: battle foe 80, battle player 80, party row 80, summary 80. Height identical everywhere.
* EXP bar: same helper with `exp_bar.png` and `EXP_BLUE`; used in battle player HUD and the Summary.
* Fainted: the fill is 0 width and the bar still draws its track.
* Numbers `hp/max` use `pixel6`, right-aligned to the bar's right edge; wild or foe HUD shows no numbers.
* Status: use `status_icons.png` (BRN PAR PSN SLP FRZ, 24×8) instead of red text, placed right of the level in battle and
  at the row's status column in the party. `FNT` stays text.

## 3. Per-screen acceptance criteria

Each criterion is checked on the emulator (Android QA plugin) with a screenshot attached to the PR, plus the commands in §4.

### 3.1 Battle: `core/src/main/kotlin/pkmn/game/battle/BattleScreen.kt` (`drawBattle`, `drawActionMenu`, `drawMoveMenu`, `drawGimmickMenu`, `hpBar`, `drawFoeHud`, `drawPlayerHud`), `battle/BattleMenus.kt`
1. Action, move and gimmick menus show a single blue `frame` with no grey panel behind them (F4). The text box (`Menu.NONE`) keeps `frame_alt`.
2. Move menu: frames at x/y/w/h multiples of 8; cursor at text x - 10; 4 rows fit with no clipping at 6 move names of 14 chars.
3. Foe and player HP bars are identical in height, skin and thresholds; player bar shows `hp/max` in `pixel6`.
4. Status shows the status icon, not red text.
5. Disabled Z-move entries use `GREY`.
6. No change to battle logic or `BattleMenus` navigation; `BattleMenusTest` still passes.

### 3.2 Party: `core/src/main/kotlin/pkmn/game/screens/PartyScreens.kt` (`PartyScreen.draw` 205-232, `StartMenu` 20-65, `SummaryScreen` 235+)
1. Party rows sit in one framed list area; the prompt box at the bottom stays a `frame`.
2. HP bar uses the shared helper at 80 px; thresholds identical to battle. `hp/max` right-aligned under `pixel6`.
3. Cursor at x = 0..4, icon at x = 12; no overlap in the screenshot.
4. Status icon replaces red status text; `FNT` stays text.
5. `SummaryScreen` HP line includes the bar; EXP uses the EXP bar skin.
6. `StartMenu` cursor offset follows 2.3.

### 3.3 Bag: `core/src/main/kotlin/pkmn/game/screens/BagScreen.kt` (`draw` 160-192)
1. Pocket header, list and description each in a `frame`; 16 px row pitch; text at frame x + 18 (current 20 for non-ball rows); cursor at frame x + 8.
2. Ball rows keep the icon; its text starts at x + 38 for all rows with icons.
3. `CANCEL` row text uses same column as items.
4. Description text uses `pixel6`, 3 lines max, inside the frame padding.
5. Cursor movement plays `menu_move`.

### 3.4 Pokédex: `core/src/main/kotlin/pkmn/game/screens/MiscScreens.kt` (`PokedexScreen.draw` 38-70), `screens/AcquisitionScreens.kt` (`PokedexPage`)
1. List area has a `frame`; header text uses `pixel8`; row pitch 16; cursor at frame x + 8, number at x + 18.
2. Caught marker `●` and `-----` for unseen follow the same text colours as bag (`TEXT`, `RED` marker).
3. Detail page (`PokedexPage`) uses `frame` and `pixel6` for flavour text only.
4. Cursor changes play `menu_move`.

## 4. Verification commands (per PR)
```
./gradlew :engine:test :core:test :tools:test
./gradlew :tools:compileMaps :tools:checkProgression :tools:validateContent :tools:buildAssetManifest && git diff --exit-code -- assets/
./gradlew :android:assembleDebug
```
Plus one emulator screenshot per touched screen, before and after if possible.

## 5. Suggested PR split (for the CEO)
1. Shared `hpBar`/`hpColor`/`UiMetrics` helpers in `Renderer.kt` plus battle and party HP bars (F1-F3). Includes the `hp_bar.png` decision.
2. Battle menu frames (F4) and disabled-text colour (F8).
3. Cursor and row-spacing pass for Bag, Dex, Party, `Menu` (F5-F7).
4. Status icons in battle and party (F2).

Each is independent after PR 1. If `Renderer.kt` is touched by other work, PR 1 goes first.

## 6. Risks and open points
* Whether `hp_bar.png` reads well at 80 px is unverified; no emulator run was done for this spec (spec only).
* `status_icons.png` and `type_icons.png` are loaded by nothing today, so their orientation and sizing in the PNG are untested.
* Type chip colour contrast (F9) is flagged, not specified; needs a Pixel Artist decision if chip colours change.
