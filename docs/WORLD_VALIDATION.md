# World validation: entrances and items

Two classes of content bugs that the plain reference checks in `compileMaps` cannot see are validated here:

1. **Entrances that do not look like where they lead** (a house door into a forest, a cave mouth into a temple).
2. **Items that cannot be picked up** (enclosed by scenery or solid NPCs, hidden where interacting never finds them, sharing a pickup flag).

| Command | What it does | Output |
|---|---|---|
| `./gradlew :tools:compileMaps` | Errors on a warp whose tile is not a warp tile or does not match its destination, on a warp that lands on water, on out-of-bounds items, hidden items on water/PC/TV/bookshelf/sign tiles, items on a warp tile, colliding item ids | `reports/world-compile.json` |
| `./gradlew :tools:auditEntrances` | Audits every warp (427) | `reports/entrances.json`, `reports/entrances.md` |
| `./gradlew :tools:auditItems` | Strict reachability of every item (572) per badge stage and placement checks | `reports/items.json`, `reports/items.md` |
| `./gradlew :engine:test :tools:test` | `EntranceTest`, `EntranceAuditTest`, `ItemAuditTest`, `StrictReachTest` fail on any entrance conflict, unreachable item or item defect | |

Both audits exit non-zero on a finding, so they can gate CI like `checkProgression`. `-Pargs="--root=<dir> --no-write"` audits another
checkout's `assets/` without writing reports (used for the before numbers below).

## 1. Entrances

### Classification (`engine/.../world/Entrances.kt`)

`Entrances.kindOfTile(tile)` returns the entrance kind a warp tile draws, or `null` when the tile is not a warp tile:

| Tile | Kind | | Tile | Kind |
|---|---|---|---|---|
| `door` | HOUSE | | `cave_entrance` | CAVE |
| `building_door` | BUILDING | | `forest_entrance` | FOREST |
| `lab_door` | LAB | | `ruins_entrance` | RUINS |
| `center_door` | CENTER | | `mine_entrance` | MINE |
| `mart_door` | MART | | `tower_door` | TOWER |
| `gym_door` | GYM | | `gate_door` | GATE |
| `stairs_*` | STAIRS | | `cave_ladder` | LADDER |
| `rug_*` | EXIT_MAT | | any other warp tile | OTHER |

`Entrances.destinationKind(map)` is what a map *is* as a destination. The explicit `class <kind>` line in the map source
(`MapData.zoneClass`, see [WORLD_FORMAT.md](WORLD_FORMAT.md)) wins. Otherwise, in this order:

1. `environment cave`: MINE (name has mine/shaft/quarry), RUINS (ruins/catacomb/sanctum/temple), TOWER (tower/lighthouse/pillar), else CAVE.
2. Indoor or `indoor`/`building` environment: CENTER (`pokecenter` tag or music), MART (mart music or name), GYM (`gym` tag),
   LAB, TOWER, GATE, RUINS (catacomb/sanctum/temple), BUILDING (`building` environment), else HOUSE.
3. Outdoor with `forest` music or a wood/forest/thicket/glade/grove name: FOREST.
4. Any other outdoor map: OTHER, which means open air (towns and routes).

`Entrances.compatible(tile, from, dest)` is the rule the compiler and the audit apply to every warp tile (and to every `tileif`
variant of it):

| Warp | The tile must be |
|---|---|
| open air to a zone | the destination's own entrance: HOUSE `door`; BUILDING `building_door` or `door`; LAB `lab_door`, `door` or `building_door`; CENTER, MART, GYM, CAVE, FOREST, RUINS, MINE, TOWER, GATE: their own tile |
| open air to open air (a pass through a cliff) | `cave_entrance` or `gate_door` |
| out of a building or any indoor map to open air | `rug_mat` (or the building's own entrance) |
| out of a cave, mine, ruins or forest to open air | the opening the zone is entered by (caves, mines and ruins may also use `cave_entrance`); never a door mat |
| zone to zone (floors of a tower, cave levels, forest clearings, inner doorways) | stairs or a ladder (never into or out of a forest), the tile of either zone's class, a door mat inside buildings |

A warp whose tile is not a warp tile is an error, except a **gated warp**: a wall tile that a `tileif` turns into stairs once a
flag is set (`lumen_ruins` 18,16 on `ruins_wall`, `p4a_lighthouse_2f` 1,2 on `puzzle_wall`). Those are valid (the reported
"warp on a wall" were exactly these two) and are listed as `gated` in the report; the flag's tile variant is what gets validated.

Not every kind has a map yet: no map uses `lab_door` (labs keep the `door`, which LAB accepts) or `gate_door` (the world has no
gatehouses; Ridge Station is a research building and keeps `door`). The tile and the rule exist so the next such map is checked.

### New tiles (`art/tiles.py`, appended at the end of the sheet so every existing tile index is unchanged)

`forest_entrance` (a worn path through a gap between two trees), `ruins_entrance` (stone doorway), `mine_entrance` (timber-framed
shaft mouth), `tower_door` (arched wooden door in a stone wall), `gate_door` (gatehouse passage), `building_door` (grand double
door). 16x16, four colours each, GSC style, all walkable `WARP` tiles (`TileRules`: any `*_entrance` is a warp tile now).
`art/previews/tiles_contact.png` shows them. Legend characters `N A J O C Q` were added to `world/legend.txt`.

### Audit report (`reports/entrances.{json,md}`)

One row per warp: source map, x,y, ground tile (and the gated tile), entrance kind, source class, destination map, destination class, destination x,y and
landing tile, reverse-transition check (`ok`, `ok (@back)`, `one-way (league chain / entered by script)`, `far` if the return lands
more than three tiles from the door, `missing`), traversable (the warp tile has a walkable neighbour or sits on the map edge, the landing tile is
walkable, not water and not enclosed) and the verdict. A warp is a conflict when its tile is not a warp tile, does not match its
destination, is unapproachable, lands on a solid or water tile, or has no way back. Intentional one-ways: the League rooms (`league` tag
chain) and rooms entered by a script `warp` (the Battle Tower room).

### Before and after

Counted with the same rules (`--root` on the `HEAD` assets for "before"):

| | before | after |
|---|---|---|
| warps audited | 427 | 427 |
| conflicts | **29** | **0** |
| of which incompatible entrance tile | 28 | 0 |
| of which untraversable (landing on water) | 1 | 0 |
| warp on a non-warp tile (not gated) | 0 | 0 |
| no reverse transition | 0 | 0 |
| gated warps (valid) | 2 | 2 |

Fixes (all in map data, spawn coordinates and facings preserved; both directions of every pair re-checked):

| Group | Conflicts | Fix |
|---|---|---|
| Route 2 to Whisperwood | 2 doors | the two house stamps replaced by the new `grove` prefab (a thicket of trees whose gap is a `forest_entrance`; same door offset, same warp) |
| Whisperwood, Mossgrove, Mossgrove Glade exits and links | 6 door mats | `forest_entrance` on every forest map edge exit |
| Mist Hollow to Mistwood and back | 2 cave mouths | `forest_entrance` (both are forest clearings) |
| Root Hollow ladder up to Mossgrove Glade | 1 ladder | `cave_entrance`, like the glade side |
| Ember Tower (Ashfall), Lantern Tower (Brindle), Tidewater Lighthouse, Sky Pillar, Battle Tower | 5 `door`/`gym_door`, 1 stairs pair | `tower_door` (prefabs `p3a_tower`, `p3c_tower`, `p4a_lighthouse`; `tile 11 10 tower_door` on Pillar Isle); `sky_pillar_top` gets `class tower` |
| Lumen Village to Lumen Ruins and the ruins exits | 1 cave mouth, 2 mats | `ruins_entrance` (outside and the two exit tiles) |
| Ruins of Sol temple, Sol catacombs and their return | 2 + 1 | `ruins_entrance` (prefab `p3c_shrine` also gives the two altars a ruins doorway; both altars get `class ruins`) |
| Old Mine at Quarrytown | 1 cave mouth | `mine_entrance` on both sides |
| Champions' Hall (Coral Town) | 1 door | `building_door` (prefab `p3c_hall`), `class building` on both hall maps |
| Prism League (Prism Plateau) | 1 gym door | `building_door` (prefab `p3b_league`), `class building` (the lobby carries the `pokecenter` tag) |
| Voltara Department Store | 1 door | `mart_door` (`tile 6 5 mart_door`) |
| Frozen Falls exit onto Route 12 | landing on a water tile | lands on the cave mouth tile `route12 30 1` instead of the pond below it |

Collision fix found while auditing: `gym_window` was walkable (not in `TileRules`' solid prefixes); the four building facades that use it
are now solid there too.

## 2. Items

### Model (`StrictReach`, `ItemAudit`)

The old `ProgressionChecker` model is optimistic. The item audit runs a stricter graph search per badge stage (0..8) from
`WorldAnalysis.stage` (field moves need only the badge: God Bidoof performs them, see `GameContext.canUseFieldMove`). It mirrors `OverworldRules.step` and
`OverworldScreen.onPlayerArrive`/`interact`:

* **Ice**: after arriving on ice the player keeps sliding in the same direction while the next step is a plain walk, and stops
  in front of walls, ledges, water, warps, NPCs, obstacles (and visible item balls, which are occupants until picked up). Ice tiles in the middle of a slide are not
  places the player can stand.
* **NPCs** with `static`, `look` or `path` movement are solid; `wander` NPCs are not. An NPC is **possibly gone** when it has `hideif` or a script on its map
  `hide`s or `move`s it. It stays solid until its flag has been set or that script can be triggered from tiles the player can reach
  (fixpoint). A second scenario removes every possibly-gone NPC from the start; an item reachable only then is `npc-dependent` (0 today).
* **Flag tiles** (`tileif`) count only once a script the player can reach sets the flag (`setflag`, and the implicit `halloffame`, `pokedex`, `unlock`); then both tile
  variants count. Script `warp`s (ferries, cutscenes) count when the script can be triggered.
* **Obstacles** (cut, smash) need their move at that stage. **Boulders** can be pushed when the tile behind them is free floor and Strength is
  usable. Approximation in `StrictReach`/`WorldAnalysis`: the boulder is not moved. Boulder persistence is instead covered by `BoulderPuzzles`
  (run by `checkProgression`, error `boulder.softlock`): an exhaustive search over boulder positions per map (a pushed boulder keeps its
  place across map re-entry, there is no pull). The game never pushes a boulder onto a warp or trigger tile, and refuses a push after which
  the player's region has no warp or connection to leave by (`BoulderRules`). With those rules the single-boulder maps cannot lose a route or
  reward. The three two-boulder maps (`mt_prism_1f`, `mt_prism_2f`, `victory_road_2f`) could (e.g. both boulders jammed so a door or the
  rest of the map is cut off), so they carry the tag `boulder_reset`: boulder positions are forgotten whenever the map is entered from another
  map, so leaving and re-entering always restores the puzzle (positions still persist across a save/load inside the map).
* **Water and ledges**: land to water needs Surf; surfing onto land lands; waterfalls need Surf and Waterfall; ledges are one-way. Warp tiles are never stood on (arriving fires the warp);
  `@back` returns to the door of a map the player entered from.
* **Interaction**: a visible item is picked up by facing it from an orthogonally adjacent standable tile (also while surfing); the player can turn on the spot. A hidden item is
  found by facing its tile and pressing A, but only when that tile holds no NPC, sign, obstacle or visible item and is not a PC, bookshelf, TV, water or waterfall
  tile. Note: `OverworldScreen.interact()` currently looks up any item on the faced tile (hidden ones included) before the sign, obstacle and tile checks, so these
  placements still work in the game as it is today; the audit holds the content to the documented rule so it stays valid if that code is tightened.

Verdicts: `reachable` (stage 0), `gated` (needs a later badge stage or a story flag such as the Hall of Fame), `npc-dependent`, `unreachable` (never, with every
move and every removable NPC gone). Other checks: item outside the map, on a warp tile, hidden under an NPC/sign/obstacle/item or on water/PC/TV/bookshelf/sign, a
visible ball drawn on a solid tile, inside a step-trigger area (warning), and pickup flags (`item:<map>_<id>`) shared across maps.

Not modelled (kept optimistic, like the old checker): conditions inside scripts, step-trigger scripts that turn the player back, Fly.

### Result

| | before | after |
|---|---|---|
| items | 572 | 572 |
| reachable from the start | 209 | 209 |
| gated by a later badge stage or story flag | 363 | 363 |
| npc-dependent | 0 | 0 |
| permanently unreachable | **0** | **0** |
| items with an error-level defect | 0 | 0 |
| warning: visible item on a solid tile | 1 | 0 |
| informational: strict model later than the old model | 5 | 5 |

The strict model found **no permanently unreachable item** in the current world. What it did find:

* `p4c_vr_deep_b1_vd1_stoneedge` (TM Stone Edge): the ball was drawn on a solid `cave_rock` tile at 3,20. Moved to the neighbouring floor tile 2,20 (id, pickup flag and badge stage unchanged).
  Recorded in `tools/src/main/resources/item-fixes.json`, which `reports/items.{json,md}` merge with the live post-fix result.
* Five items where the old checker said "reachable earlier" than the game allows: `coral_town_ct_ppmax` (3,19; a static fisher NPC stands in the one-tile sand strip, so it needs Surf: stage 4) and the four
  Crystal Cavern Depths items (the sealed crystal opens only after the Hall of Fame: stage 7). Both are intentional gating and left as they are.
* Ice puzzles (Frozen Falls hailstone at 14,16) are reachable only because a slide stops in front of the item ball; the model counts that.

Tests: `ItemAuditTest` (synthetic maps for each defect, and the real world must have none), `EntranceAuditTest` (synthetic conflicts, and the real world must have none),
`StrictReachTest` (ice, ledges, Surf, NPCs, flag tiles, obstacles, boulders, warps), `EntranceTest` (engine: `kindOfTile`, `destinationKind`, `compatible`).
