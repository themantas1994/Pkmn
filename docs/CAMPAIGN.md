# Tessera campaign plan (master design)

Original region **Tessera**, a mosaic of coast, forest, mountain and ice surrounding **Mt. Prism**, an ancient
crystal mountain where the Prism Pokémon (Necrozma) sleeps. GSC-style pacing: two towns before the first
gym, a loop around the central mountain, a northern endgame and a League on the mountain's summit plateau.

Target: ~30 h to the Elite Four for a first-time player (≈20 h story, ≈6 h optional, ≈4 h team building).
Part budgets: Part 1 ≈ 8 h, Part 2 ≈ 11 h, Part 3 ≈ 11 h + post-game.

## Cast
| Role | Name | Sprite | Notes |
|---|---|---|---|
| Professor | Prof. Linden | professor | Hollowbrook lab; studies people & Pokémon bonds |
| Rival | player-named (default KAI) | rival | brash → respectful arc; takes the starter strong vs player's |
| Mom | — | mom | heals party at home |
| Villain team | **Team Eclipse** | grunt_m/grunt_f | want to drain Mt. Prism's light to force an endless eclipse ("a world without harsh light") |
| Eclipse admins | Corvin (m), Vesper (f) | admin | Corvin: Dark/Poison; Vesper: Psychic/Ghost |
| Eclipse boss | Umbra | boss | Necrozma obsession; ace Honchkrow → later Mega Absol/Tera Dark |
| Mega researcher | Dr. Mira | scientist | gives Key Stone after Gym 3 (Voltara) |
| Sol Warden | Warden Ilima-like original: "Warden Talo" | sage | gives Z-Ring after Gym 4 (Ruins of Sol) |
| Crystal researcher | Quartz | scientist | gives Tera Orb after Gym 6 (Crystal Cavern) |
| Power Spot researcher | Hollis | ranger | gives Dynamax Band before Gym 7 (Frostmere) |

### Gyms (badge = Gym N; level caps from world/campaign.json)
| # | City | Leader | Sprite | Type | Badge | Ace lvl | Gimmick | Puzzle idea |
|---|---|---|---|---|---|---|---|---|
| 1 | Brindle City | Hazel | leader1 | Grass | Sprout | 13-14 | — | hedge maze, cut-free |
| 2 | Port Tidewater | Marina | leader2 | Water | Tide | 20-21 | — | floating platforms (switch flags + tileif) |
| 3 | Voltara City | Volta | leader3 | Electric | Spark | 27-28 | — | electric gates toggled by switches |
| 4 | Quarrytown | Garnet | leader4 | Rock | Stone | 33-34 | Mega (Aerodactyl/Tyranitar-style ace) | boulder (Strength-free) sliding mine carts → just switches |
| 5 | Seraphine Town | Elowen | leader5 | Fairy | Mist | 39-40 | Z-Move (Fairium Z) | mist corridors with hidden paths |
| 6 | Ashfall City | Blaise | leader6 | Fire | Ember | 45-46 | Mega or Tera | lava stepping-stone path |
| 7 | Frostmere Town | Isolde | leader7 | Ice | Frost | 51-52 | Dynamax (powerspot) | ice sliding floor |
| 8 | Dragonspire City | Ryuga | leader8 | Dragon | Dusk | 57-58 | Tera Dragon + Dynamax (powerspot) | dark tower with switch bridges |

Elite Four (Prism Plateau, `powerspot`, all gimmicks): Morrow (Ghost, elite1, 60-62), Ferrum (Steel, elite2, 61-63),
Sibyl (Psychic, elite3, 62-64), Nox (Dark, elite4, 63-65). Champion **Celeste** (champion, mixed, 65-68: Mega
Gardevoir, Z-Move, Tera, one gimmick per battle chosen by the AI). Rival appears for a final battle on Victory Road.

### Gimmick introduction (do not require gimmicks for ordinary encounters)
| When | Who/where | Item | Flag (script `unlock X`) | Notes |
|---|---|---|---|---|
| After Gym 3 | Dr. Mira, Voltara Lab | `keystone` + one Mega Stone gift (e.g. `ampharosite` with a gift Mareep line) | `unlock_mega` | Mega Stones then appear as hidden items/rewards |
| After Gym 4 | Warden Talo, Ruins of Sol | `zring` + `normaliumz` and the type Z-Crystal of Gym 4 | `unlock_zmove` | Z-Crystals as rewards from Gyms 4–8 and trials |
| After Gym 6 | Quartz, Crystal Cavern | `teraorb` | `unlock_tera` | Tera Shards (`*terashard` items, if present) change Tera Type at Quartz's lab |
| Before Gym 7 | Hollis, Frostmere | `dynamaxband` | `unlock_dynamax` | works only on maps tagged `powerspot` (Gyms 7–8, League, Max Dens) |
Ultra Burst: post-game (Solgaleo/Lunala + Ultranecrozium Z) for Necrozma.

### Rival battles (rival team: their starter + Pidgey line → later Gengar/Honchkrow etc.)
Lab L5 · Route 2 L11 · Port Tidewater L19 · Voltara (vs Eclipse together, then battle) L26 · Mt. Prism L37 ·
Ashfall L44 · Victory Road L59 · Post-game rematches L70+.

### Story beats
1. Part 1: starter, Pokédex, Team Eclipse grunts steal "light shards" from Whisperwood's shrine; Gym 1; Rock Smash
   gift; Cobblecrest Cave; Port Tidewater docks heist; Gym 2.
2. Part 2: Lumen Ruins (Unown, mural of the Prism Pokémon); Voltara blackout caused by Eclipse siphoning the power
   plant → player + rival clear Eclipse; Gym 3; Key Stone. Quarrytown fossils (revive gift); Gym 4; Ruins of Sol →
   Z-Ring. Mt. Prism interior (Strength puzzles) → Eclipse admin Corvin battle; Seraphine Town; Gym 5 (Fly after).
3. Part 3: Ashfall volcano; Ember Tower (Ho-Oh shrine, sealed until post-game); Gym 6; Crystal Cavern → Tera Orb;
   Surf routes; Frostmere; Dynamax Band; Gym 7; Dragonspire; Gym 8; Eclipse raid on Prism Summit: admins Vesper +
   Corvin, boss Umbra (L58-60), Necrozma awakens → catch or defeat (re-appears after League if defeated).
   Victory Road (Waterfall), final rival, Prism Plateau League, Champion, credits, Hall of Fame.
4. Post-game: Coral Isles (new routes/islands), Battle Tower (rematch-style trainer gauntlet), legendary dens:
   Solgaleo/Lunala altars (Ultra Burst unlock), Ho-Oh (Ember Tower), Lugia (Tempest Isle), Kyogre/Groudon (with
   orbs → Primal), Rayquaza, Mewtwo, legendary beasts & birds (static), Terapagos (Crystal Cavern depths),
   Eternatus Max Den. Gym leader & Elite Four rematches (L70-80).

## Geography and map ids
Region map grid: 32 × 28 cells of 8 px on the 256 × 224 region map (`region x y` in map files).

| Part | Map id | Kind | Region cell | Connections / notes |
|---|---|---|---|---|
| 1 | hometown | town | 6,22 | N: route1 |
| 1 | route1 | route | 6,19 | S: hometown, N: fernwick_town |
| 1 | fernwick_town | town | 6,16 | center, mart; N: route2 |
| 1 | route2 | route | 6,13 | trees/cut later; W door: whisperwood (forest interior map) |
| 1 | whisperwood | forest | 4,12 | shrine; Eclipse first appearance; exits to route2 north part |
| 1 | brindle_city | city | 8,10 | Gym 1, bike shop; E: route3 |
| 1 | route3 | route | 11,10 | E: route4 |
| 1 | route4 | route | 13,11 | cave entrance: cobblecrest_cave (1–2 floors, Rock Smash rocks) |
| 1 | port_tidewater | city | 16,12 | Gym 2, docks; **S boundary → route5** |
| 2 | route5 | route | 17,15 | **N boundary → port_tidewater**; S: lumen_village |
| 2 | lumen_village / lumen_ruins | town / dungeon | 15,16 | Unown ruins |
| 2 | route6 | route | 18,18 | → voltara_city |
| 2 | voltara_city | city | 20,17 | Gym 3, power plant, Dr. Mira lab |
| 2 | route7, route8 | routes | 23,16 / 25,13 | → quarrytown |
| 2 | quarrytown | town | 26,11 | Gym 4, fossil lab; ruins_of_sol nearby (east) |
| 2 | mt_prism_1f… | cave | 23,9 | multi-floor, Strength boulders, Eclipse admin |
| 2 | seraphine_town | town | 20,5 | Gym 5; **W boundary → route9** |
| 3 | route9 | route | 16,5 | **E boundary → seraphine_town** |
| 3 | ashfall_city | city | 12,4 | Gym 6, Ember Tower |
| 3 | crystal_cavern | cave | 11,7 | Tera Orb |
| 3 | route10, route11 (surf) | routes | 9,6 / 5,7 | → frostmere_town |
| 3 | frostmere_town | town | 3,4 | Gym 7 (powerspot) |
| 3 | route12 | route | 6,2 | mountain path |
| 3 | dragonspire_city | city | 9,1 | Gym 8 (powerspot) |
| 3 | prism_summit | dungeon | 18,8 | Necrozma climax |
| 3 | route13, victory_road_* | route/cave | 12,1 / 15,3 | Waterfall |
| 3 | prism_plateau (+ e4 rooms) | league | 18,7 | powerspot |
| 3 | coral_isles…, battle_tower, legendary dens | post-game | 26,22 … | via ferry from port_tidewater (script warp) |

### Boundary contracts (exact — both sides must match)
* **B1 port_tidewater ↔ route5** (seamless): `port_tidewater` has `connect south route5 5`; its bottom row is open
  (walkable path) exactly at x = 14,15. `route5` is **20 tiles wide**, has `connect north port_tidewater -5`, and its
  top row is open exactly at x = 9,10 (all other top-row tiles solid). Route 5 is gated: a trigger near its north end
  `unless` badge flag prevents going south before Gym 2 — implement it on route5 with `if badges >= 2`-based script
  owned by Part 2 (Part 1 does not need to do anything except the open tiles).
* **B2 seraphine_town ↔ route9** (seamless): `seraphine_town` has `connect west route9 3`; its west column is open
  exactly at y = 11,12. `route9` is **20 tiles tall**, has `connect east seraphine_town -3`, and its east column is
  open exactly at y = 8,9.
* **Ferry**: Part 3 adds `ferry_coral` script; Part 1 places an NPC `sailor` in port_tidewater's dock with
  `script=tidewater_ferry` and Part 1 writes that script as: if `flag hall_of_fame` then `call ferry_to_coral`
  else a "ferry not running" line. Part 3 defines script `ferry_to_coral`.
* Fly: every town/city map sets `fly x y` and `tags town`; the game records visits automatically.
* **Part 3 split** (3A / 3B / 3C, parallel authors). 3A: route9 → ashfall_city (Gym 6, Ember Tower) →
  crystal_cavern (Tera Orb) → route10/route11 (Surf, Lapras gift) → frostmere_town (Gym 7, Dynamax Band) → route12.
  3A also owns the post-game encounters inside its own maps (Ho-Oh on ember_tower_roof, Terapagos in crystal_cavern
  depths), gated on `flag hall_of_fame`. 3B: dragonspire_city (Gym 8) → prism_summit (Eclipse climax, Necrozma) →
  route13 → victory_road_* → prism_plateau + E4/Champion rooms, Hall of Fame + credits (sets `hall_of_fame`).
  3C: post-game (`ferry_to_coral` script, coral_isles…, battle_tower, legendary dens except Ho-Oh/Terapagos,
  Necrozma reappearance if defeated, leader/E4 rematches hosted on 3C maps). Prefixes: `p3a_`, `p3b_`, `p3c_` for
  flags and files (`world/trainers/p3a_*.txt`, `world/scripts/p3b_*.pks`, `world/prefabs/p3c.txt`, …).
* **B3 route12 ↔ dragonspire_city** (seamless, 3A ↔ 3B): `route12` is **16 tiles tall**, has
  `connect east dragonspire_city -4`, and its east column is open exactly at y = 6,7. `dragonspire_city` has
  `connect west route12 4` and its west column is open exactly at y = 10,11. Gym 8 requires 7 badges; route12's
  east end has no gate (3B gates inside Dragonspire).
* **B4 prism_summit access** (3B only): from dragonspire_city via a 3B-owned route/cave; prism_plateau is reached
  from route13 → victory_road. Dragonspire has a Pokémon Center whose map id is `dragonspire_center` (respawn point).
* **B5 post-game** (3B → 3C): the Champion victory script sets `hall_of_fame` and warps to `player_house_2f` 3 4
  after credits. 3C content is reached only via `ferry_to_coral` (called by Part 1's tidewater ferry sailor) and
  must not edit 3A/3B maps; rematches with gym leaders/E4 happen on 3C maps (e.g. a "Champions' Hall" on Coral Isles).

## File ownership (parallel authors must not edit each other's files)
| Owner | Files |
|---|---|
| Part 1 | maps for Part 1 ids (incl. existing hometown, route1, player_house_*, rival_house, lab — may rewrite) and their interiors (`*_center`, `*_mart`, houses, gym), `world/trainers/p1_*.txt`, `world/encounters/p1_*.txt`, `world/scripts/p1_*.pks` (existing hometown.pks/lab.pks may be renamed/rewritten), `world/shops/p1.txt`, `world/quests/p1.txt`, `world/prefabs/p1.txt` |
| Part 2 | Part 2 map ids + interiors, `p2_*` files, `world/shops/p2.txt`, `world/quests/p2.txt`, `world/prefabs/p2.txt` |
| Part 3 | Part 3 + post-game map ids, `p3_*` files, `world/shops/p3.txt`, `world/quests/p3.txt`, `world/prefabs/p3.txt` |
| Lead | `world/legend.txt`, `world/prefabs.txt`, `world/campaign.json`, `world/trainers/classes.txt`, `world/shops.txt`, `world/quests.txt` |
Trainer classes: add new classes in your own `p<N>_classes.txt` with ids prefixed `p<N>_` only if a class is
missing from classes.txt; prefer the shared ones. Flags: prefix story flags with `p1_`, `p2_`, `p3_` (except the
documented shared ones: `got_starter`, `got_pokedex`, `starter_grass|fire|water`, `hall_of_fame`, `unlock_*`).
Interior maps use unique ids per town (e.g. `fernwick_center`), exits with explicit warps (or `-> @back`).

## Level curve & encounters
Wild levels: Route 1 2-4 · Route 2 / Whisperwood 4-8 · Route 3-4 8-12 · Cobblecrest 10-14 · Route 5-6 16-20 ·
Voltara area 20-24 · Route 7-8 24-28 · Mt. Prism 28-34 · Route 9 34-38 · Ashfall area 38-42 · Surf routes 40-46 ·
Frostmere/Route 12 44-50 · Dragonspire/Victory Road 50-56 · post-game 55-75.
Trainer levels ramp so each gym leader's ace = that badge's level cap; route trainers 2-6 below the next cap.
Every route/dungeon: 4–10 trainers, 1–4 visible items, 1–3 hidden items, at least one optional detour.
Healing: Pokémon Center in every town/city; rest NPC or heal tile before every dungeon boss.

**Roster integration:** aim for 450+ species catchable by the end of post-game across all generations, chosen by
habitat (forest/water/cave/mountain/volcano/ice/urban/ruins/sea), with day/night differences and rods/surf tables;
regional forms appear where thematically apt (Alolan in Coral Isles, Galarian/Hisuian/Paldean in specific areas).
Avoid legendaries/mythicals in random tables. Starter lines of other generations: one gift per part (e.g. Part 1:
Eevee gift in Brindle; Part 2: fossil revival + a Kanto starter gift; Part 3: Lapras gift on surf route).
Trade evolutions: the **Linking Cord** (`linkingcord`, official item) is sold in Voltara's department store (Part 2).
Evolution items appear progressively (stones from Part 1 mart/hidden items onward).

## Field moves
Unlocked by badges and usable if any party Pokémon can learn the move (no HM slots):
Cut (badge 1), Flash/Rock Smash (badge 2), Strength (badge 3), Surf (badge 4), Fly (badge 5), Waterfall (badge 7).
Map obstacles: `obstacle x y cut|smash|boulder flag=<unique>`; water needs Surf. Each part must ensure a Pokémon able
to learn the required field move is obtainable before its first mandatory use (document in your part's notes).

## Writing style
GSC tone: short, warm, slightly humorous lines; ≤ 3 text boxes per NPC; signs give directions; no profanity.
All NPCs have a purpose (hint, lore, item, trade, battle). Avoid long unskippable cutscenes.

## Part 1 notes
**Maps (29):** hometown, player_house_1f/2f, rival_house, lab, route1, fernwick_town (+ fernwick_center/mart/house),
route2, whisperwood, brindle_city (+ brindle_center/mart/gym/house/bikeshop), route3, route4, cobblecrest_1f,
cobblecrest_b1f, port_tidewater (+ tidewater_center/mart/gym/house/warehouse). Route 2 is split by a tree line: the
south half's gate leads into Whisperwood, whose north exit leads to the north half (rival L11) and Brindle. Route 4 is
split by a ridge; Cobblecrest 1F is the critical path between its halves. Connections: brindle_city `south route2 4`,
`east route3 2`; route3 `east route4 6`; route4 `east port_tidewater 6`.

**Merge placeholders (delete/replace on merge):** `world/maps/route5.map` is a stub honouring B1 (take Part 2's file);
`world/scripts/p1_stub_ferry.pks` defines a stub `ferry_to_coral` (delete when Part 3 lands — duplicate id error).
B1 is implemented exactly (port_tidewater bottom row open only at x=14,15, `connect south route5 5`). Ferry sailor
`ferry_sailor` on the north pier uses script `tidewater_ferry` per contract. Generated `assets/` were not committed.

**Flags others may test:** `p1_got_expshare`, `p1_got_oldrod`, `p1_got_goodrod`, `p1_got_bicycle`, `p1_got_eevee`,
`p1_ww_grunts_fled` (Whisperwood Eclipse event), `p1_rival_route2`, `p1_rival_port`, `p1_heist_foiled` (docks heist;
also opens Gym 2), `p1_marina_left`, `p1_beat_hazel`, `p1_beat_marina`, `p1_got_rocksmash`, `p1_found_hiker`,
gym switches `p1_gym1_sw1/2`, `p1_gym2_sw1/2`, obstacles `p1_cut_route1`, `p1_cut_route2`, `p1_cut_brindle`,
`p1_smash_cc1..3`. Quests (world/quests/p1.txt): `p1_light_shards` (main, done at badge 2), `p1_lost_doll`,
`p1_mushroom_survey`, `p1_pen_pal`, `p1_cave_hiker`.

**Field moves:** Cut is optional in Part 1 (route1 item nook, route2 shortcut, Brindle garden). Rock Smash TM is a gift
on Route 4; smash rocks gate only optional Cobblecrest pockets. Early
learners: Rattata, Bidoof (Route 1) and Bunnelby (Route 2) learn both Cut and Rock Smash; Geodude/Machop (Route 4/cave) learn Rock
Smash. TM Flash is given by the Whisperwood sage.

**Rewards:** Hazel → badge 1 + `tm bulletseed`; Marina → badge 2 + `tm waterpulse`. Gifts: Exp. Share (Fernwick aide),
Old Rod (Hollowbrook fisherman), Bicycle (Brindle bike shop), Eevee L10 (Brindle), Good Rod (Tidewater sailor's house),
Shellos L15 (pen pal, for showing a Wooper). Stone seller: `tidewater_stones` (Fire/Water/Thunder/Leaf/Moon/Everstone).

**Deviations:** no real trade — the "trade" NPC (pen pal) gives a Shellos when a Wooper is in the party, since the
`trade` host semantics are unspecified. campaign.json badge leaders/cities are still "TBD" (lead-owned): Gym 1 Hazel /
Brindle City, Gym 2 Marina / Port Tidewater. Gym puzzles rely on runtime `tileif` + step triggers on `puzzle_switch`
tiles (one-way switches). Ladders in Cobblecrest land on the ladder tile (GSC style).

## Part 2 notes (Route 5 → Seraphine Town)
**Maps (39):** route5, lumen_village (+lumen_center, lumen_mart, lumen_elder, lumen_researcher, lumen_house), lumen_ruins,
lumen_ruins_chamber, route6, voltara_city (+voltara_center, voltara_dept_1f/2f, voltara_house, voltara_house2, voltara_lab,
voltara_gym, voltara_powerplant), route7, route8, quarrytown (+quarrytown_center, quarrytown_mart, quarrytown_house,
quarrytown_house2, quarrytown_fossil_lab, quarrytown_gym), ruins_of_sol, ruins_of_sol_temple, mt_prism_1f/2f/3f,
seraphine_town (+seraphine_center, seraphine_mart, seraphine_house, seraphine_house2, seraphine_gym).
Stubs `zz_stub_port_tidewater.map` / `zz_stub_route9.map` exist only for isolated compiles — **delete on merge**.
Connections: route5 S→lumen_village (offset -2); lumen_village E→route6 (-5); route6 E→voltara_city (-6);
voltara_city E→route7 (-6); route7 E→route8 (-30); route8 N→quarrytown (-3); quarrytown E→ruins_of_sol (0);
quarrytown cave (3,3) → mt_prism_1f → 2f → 3f → seraphine_town cave (29,12). B1/B2 exactly as contracted.

**Story flags:** `p2_route5_open` (badge-2 gate passed), `p2_voltara_visited`, `p2_rival_at_plant`, `p2_plant_rival_in`,
`p2_power_restored` (Voltara Gym opens), `p2_badge3`, `p2_got_keystone` (Route 7 opens; also `unlock_mega`), `p2_got_mira_gift`,
`p2_badge4`, `p2_got_zring` (Mt. Prism opens; also `unlock_zmove`), `p2_beat_corvin`, `p2_rival_prism_done`, `p2_badge5`.
Puzzle flags: `p2_lumen_sw_a/b`, `p2_lumen_door`, `p2_vg_1..3`, `p2_qg_a/b/c` (b toggles), `p2_sg_a/b/c`.
Side-quest flags: `p2_saw_mural`, `p2_kanto_gift`, `p2_kanto_bulbasaur|charmander|squirtle`, `p2_kanto_stone`,
`p2_found_toolbox`, `p2_found_pickaxe`, `p2_fossil_given`, `p2_quarry_traded`, `p2_togepi_egg`, `p2_weaver_tm`,
`p2_got_goodrod`, `p2_dept_gift`. Quests (world/quests/p2.txt): `p2_story` (main), `p2_kanto_legacy`, `p2_ruins_research`,
`p2_live_wire`, `p2_fossil_dig`, `p2_sun_and_moon`, `p2_mist_song`.

**Gating:** Route 5 trigger pushes back without 2 badges. Voltara Gym door guarded until the power plant is cleared;
Route 7 exit guarded until Dr. Mira gives the Key Stone (after Gym 3). Mt. Prism entrance guarded until Warden Talo's
Z-Ring (after Gym 4). Mt. Prism main path needs Strength (boulder gaps, pushable; both sides are open rooms so pushes
cannot soft-lock); Rock Smash rocks and the 1F lake (Surf) only guard optional items. All Part 2 water is optional.
Strength users obtainable before Mt. Prism: Machop/Geodude/Rhyhorn (Route 7), Drilbur/Mudbray (Route 8). Surf users:
Psyduck/Marill/Buizel/Wooper (Route 6), Tentacool (Route 5).

**Gifts:** Good Rod (Route 5), Kanto starter L18 choice (Elder Ione, after the mural) + its Mega Stone once Mega is
unlocked, Flaaffy L25 + Ampharosite (Dr. Mira), fossil revival (Jaw/Sail from the foreman quest, Old Amber on Mt. Prism
1F lake islet → Tyrunt/Amaura/Aerodactyl L30), Togepi egg (Seraphine), Onix trade for Drilbur (Quarrytown).
Mega Stones: ampharosite, venusaurite/charizarditex/blastoisinite, manectite, aerodactylite, mawilite, sablenite,
gardevoirite, altarianite. Z-Crystals: normaliumz+rockiumz (Talo), fairiumz (Elowen), electriumz, psychiumz, buginiumz,
steeliumz, firiumz. TMs: voltswitch (Volta), rockslide (Garnet), dazzlinggleam (Elowen), calmmind (Lumen weaver).
Tutors: `movetutor voltara_tutor`, `movetutor seraphine_tutor`; move relearner in Quarrytown.

**Deviations / engine expectations:** the in-game trade uses `trade drilbur onix` (want, give) — the host must implement
the `trade` special. Shops in `world/shops/p2.txt` (`p2_voltara_evo` sells `linkingcord`). Rival battles use `canlose`.
Mt. Prism 3F exit leads straight into Seraphine Town (no extra route map). Seraphine Town has a Mist Grove grass patch
and `weather fog`. Generated `assets/` output is not committed by Part 2 (run `compileMaps` after merge).
