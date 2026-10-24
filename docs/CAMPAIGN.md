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
Unlocked by badges and always performed by God Bidoof (invisible, party-independent; no HM items or slots):
Cut (badge 1), Flash/Rock Smash (badge 2), Strength (badge 3), Surf (badge 4), Fly (badge 5), Waterfall (badge 7).
Map obstacles: `obstacle x y cut|smash|boulder flag=<unique>`; water needs Surf. Each field move is available once its badge is earned.

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
on Route 4; smash rocks gate only optional Cobblecrest pockets. No party member is ever needed: God Bidoof performs every field
move once its badge is earned, so NPC and sign text must never ask for a Pokémon that knows or can learn one
(`ContentValidator.validateFieldMoveWording`, error `fieldmove-text-obsolete`). TM Flash is given by the Whisperwood sage.

**Rewards:** Hazel → badge 1 + `tm bulletseed`; Marina → badge 2 + `tm waterpulse`. Gifts: Exp. Share (Fernwick aide),
Old Rod (Hollowbrook fisherman), Bicycle (Brindle bike shop), Eevee L10 (Brindle), Good Rod (Tidewater sailor's house),
Shellos L15 (pen pal, for showing a Wooper). Stone seller: `tidewater_stones` (Fire/Water/Thunder/Leaf/Moon/Everstone).

**Deviations:** no real trade — the "trade" NPC (pen pal) gives a Shellos when a Wooper is in the party, since the
`trade` host semantics are unspecified. campaign.json lists all eight badges with their leaders and cities (Gym 1 Hazel / Brindle City, Gym 2 Marina /
Port Tidewater, ...). Gym puzzles rely on runtime `tileif` + step triggers on `puzzle_switch`
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

## Part 3A notes (Route 9 → Route 12)
**Maps (22 + 1 stub):** `route9`, `ashfall_city` (+ `ashfall_center`, `ashfall_mart`, `ashfall_gym`, `ashfall_house`,
`ashfall_spa`), `ember_tower_1f`, `ember_tower_roof`, `route10`, `crystal_cavern_1f`, `crystal_cavern_b1`,
`crystal_cavern_depths`, `route11`, `route11_cottage`, `frostmere_town` (+ `frostmere_center`, `frostmere_mart`,
`frostmere_gym`, `hollis_lodge`, `frostmere_house`), `route12`. **Stub to delete on merge:**
`world/maps/zz_stub_dragonspire_city.map` (honours B3 only).

**Connections:** seraphine_town ⇄ route9 (B2, exact) · route9 west ⇄ ashfall_city east (`connect west ashfall_city -4`) ·
ashfall_city south ⇄ route10 (offset 0) · route10 cave → crystal_cavern_1f → ladder → crystal_cavern_b1 → west tunnel →
route11 (cliff cave at 31,3) · route11 north ⇄ frostmere_town south (`-6`/`6`) · frostmere_town east ⇄ route12 west
(`2`/`-2`) · route12 east ⇄ dragonspire_city (B3, exact: 16 tall, open y=6,7, `connect east dragonspire_city -4`).
Ember Tower door is in Ashfall's north plaza; crystal_cavern_depths is behind a crystal wall in b1 (`tileif hall_of_fame`).

**Story & gating:** rival battle at the Ashfall east entrance (L44, `p3a_rival_ashfall`, variant by `starter_*`).
Gym 6 Blaise (lava stepping stones: switches `p3a_ash_s1..3`), badge 6, `tm overheat` (+ `firiumz` if Z unlocked),
ace Mega Houndoom L46 → `p3a_beat_blaise`. Route 10's cavern entrance is guarded until `p3a_beat_blaise`. Team Eclipse
grunts mine the cavern; the two in b1 block the west tunnel (hidden by `p3a_got_teraorb`). Quartz (after both grunts
are beaten) gives `teraorb` + `unlock tera` → `p3a_got_teraorb`, then exchanges 3 `<type>terashard` for a Tera Type change
via `special set_tera_type <Type>` (pick a party member, set its Tera Type). A camp nurse in b1
heals. Route 11 needs Surf (badge 4 / Part 2 surfers). Frostmere Gym door is sealed (trigger) until Hollis gives
`dynamaxband` + `unlock dynamax` (`p3a_got_dynamaxband`). Gym 7 Isolde (ice-sliding floor, solver-verified), badge 7,
`tm blizzard` (+ `iciumz`), ace Gigantamax Lapras L52 with `gimmicks dynamax` → `p3a_beat_isolde`. `frostmere_town` and
`frostmere_gym` are tagged `powerspot`. Main quest `p3a_story` (start on entering Route 9; `next` at Blaise, Quartz,
Hollis, Isolde).

**Side quests (world/quests/p3a.txt):** `p3a_lapras` (Big Pearl hidden on the bay islet → Lapras L40 gift,
`p3a_got_lapras`), `p3a_lost_pip` (find Pip on Route 12's ice slope → Snom egg + Exp. Candy L), `p3a_letter` (Ashfall spa
gentleman → Hollis's Lodge scientist), `p3a_carbink` (show a Carbink to Quartz's aide → shards + Rare Candy). In-game
trade: Delibird → Sneasel "Frosty" L44 holding Razor Claw (`trade delibird sneasel 44 ...`, `p3a_traded_sneasel`).
Other gifts: Fresh Water (gym guide), Charcoal, Lava Cookies, Dive Balls, Sacred Ash (after Ho-Oh). Heals: Ashfall hot
spring attendant, cavern camp nurse, centers in Ashfall/Frostmere.

**Post-game (gated on `hall_of_fame`):** Ember Tower sages step aside; roof has 2 sage trainers and Ho-Oh L70
(`p3a_hooh_done` on capture). Crystal Cavern depths open: 2 trainers, Terapagos L70 (`p3a_terapagos_done`). Both stay
if defeated/fled (only capture hides them). Gym leader rematches are left to 3C (Blaise/Isolde just comment on them).

**Content:** 51 trainers (classes `p3a_kindler`, `p3a_skier`, `p3a_boarder`, `p3a_birdkeeper` in
`p3a_classes.txt`), 9 encounter tables (Route 9/10/11/12 with surf/rods/rock smash, cavern + depths), shops
`p3a_ashfall`/`p3a_frostmere`, prefab `p3a_tower` (Ember Tower).
**Deviations / notes:** Lapras is the Part 3 starter-line gift. The compiler's `water@auto` autotiling currently reads
already-replaced neighbours, so most water ends up `water_nw` (visual only; lead-owned code).

## Part 3B notes (Dragonspire → Prism Summit → Victory Road → League)
**Maps (27 + 2 stubs):** dragonspire_city (+dragonspire_center, dragonspire_mart, dragonspire_house [Elder lore],
dragonspire_house2 [trader + scale collector], dragonspire_house3 [move relearner], dragonspire_shrine [Dragon Trial],
dragonspire_gym, dragonspire_gym_2f), umbral_pass, prism_summit (1F), prism_summit_2f, prism_summit_3f,
prism_summit_peak, route13 (+route13_lodge), victory_road_1f/2f/3f, prism_plateau, prism_league (lobby: nurse, mart,
gate guard), league_morrow, league_ferrum, league_sibyl, league_nox, league_champion. New prefabs in
`world/prefabs/p3b.txt` (`p3b_tower`, `p3b_league`); extra trainer classes in `world/trainers/p3b_classes.txt`
(`p3b_dragontamer`, `p3b_veteran_m/f`, `p3b_eclipse_sci`).
**Stubs:** none left. The isolated-compile stubs (zz_stub_route12, zz_stub_route9) and 3A's zz_stub_dragonspire_city were deleted when the lead branch (with Part 3A) was merged into this branch.

**Connections:** B3 exactly: dragonspire_city `connect west route12 4`, west column open only at y=10,11.
dragonspire_city `connect east route13 2` (route13 `connect west dragonspire_city -2`, open y=8,9) and
`connect south umbral_pass 6` (umbral_pass `connect north dragonspire_city -6`, top row open x=11,12).
B4: umbral_pass cave (12,40) → prism_summit → 2f → 3f → prism_summit_peak (all ladders/cave mouths). route13 cave
(38,8) → victory_road_1f → 2f → 3f → exit (3,0) → prism_plateau (11,17); the league door → prism_league; the lobby
guard warps into league_morrow; each E4 room's stairs (5,1) lead to the next room; league_nox → league_champion.
Respawn/heal: dragonspire_center, prism_league nurse (both `healcenter`); extra heals: Highland Lodge keeper,
Clan scout on summit 2F, rival on summit 3F.

**Gating:** Gym door trigger (17,7) needs 7 badges (sets `p3b_gym_open`). South gate trigger (17..18,26) needs the
Dusk Badge (`p3b_badge8`). East gate trigger (33,10..11) blocks Route 13 until `p3b_eclipse_defeated`. Victory Road
officer checks 8 badges (`p3b_vr_checked`). Summit: Vesper's (2F) and Corvin's (3F) corridors and the peak are sealed
by triggers until each boss is beaten.
**Field moves:** Victory Road 1F requires Surf + Waterfall (badge 7): surf the lake, climb the falls at x=14 to the
upper shelf/ladder; a ledge on the east side drops back to the entrance (no soft-lock). 2F requires Strength: boulders
plug two 1-tile gaps, each opening into an open chamber. Waterfall learners available by then: Seaking/Goldeen,
Gyarados (Super Rod given in Dragonspire), Politoed, Golduck/Azumarill (Umbral Pass surf), Whiscash, 3A's Lapras.
Cut/Rock Smash/Surf only guard optional items (Umbral nooks, tarn islets, summit pool, VR pockets).
**Gym 8 puzzle:** dark platforms over a `void` chasm. `void` is walkable per TileRules, so falling is done with step
triggers (`p3b_dgym_fall`, back to the entrance); switches set `p3b_dg_b1..b6` and `tileif` turns the void into
`bridge_v/h` (the matching fall triggers use `unless=`). Two trap switches send you back to the entrance.

**Story flags:** `p3b_arrived`, `p3b_gym_open`, `p3b_badge8`, `p3b_beat_vesper`, `p3b_summit_rival_met`,
`p3b_beat_corvin`, `p3b_beat_umbra`, **`p3b_necrozma_caught`** / **`p3b_necrozma_defeated`** (exactly one is set;
3C makes Necrozma reappear when `p3b_necrozma_defeated`), `p3b_necrozma_done`, `p3b_eclipse_defeated` (Route 13 open),
`p3b_vr_checked`, `p3b_rival_vr_done`, `p3b_e4_1..4` and `p3b_champion_beaten` (cleared by the lobby guard on every
entry, so a loss means restarting from Morrow), `hall_of_fame` (shared). Side flags: `p3b_got_superrod`,
`p3b_traded_seadra`, `p3b_lodge_gift`; var `p3b_trial_score`. Quests (`world/quests/p3b.txt`): `p3b_story` (main),
`p3b_dragon_trial`, `p3b_scale_trade`, `p3b_skyfall_trade`.

**Battles:** Ryuga (`p3b_leader_ryuga`, L54–58, cap 58) uses `gimmicks tera dynamax`: every mon has `tera=Dragon`,
and the ace Dragonite Dynamaxes (both gym floors are tagged `gym powerspot`). Vesper L56–58, Corvin L56–58, Umbra
L58–60 (Mega Absol + Tera Dark Honchkrow). Necrozma: `wildbattle necrozma 60 norun` at the altar; if you black out it
stays there (Umbra stays beaten). Rival L57–59 with Mega Pidgeot, `canlose`, three variants by starter flag.
Elite Four, one gimmick each: Morrow (Ghost, L60–61, Z-Move Gengar), Ferrum (Steel, L61–62, Mega Metagross), Sibyl
(Psychic, L62–63, G-Max Hatterene; rooms tagged `powerspot`), Nox (Dark, L63–64, Terastal). Champion Celeste
L65–66, six mixed types; the room script picks one of three trainer variants at random (`…_mega`, `…_zmove`, `…_tera`),
so she uses exactly one gimmick per battle. Victory (B5): `halloffame`, `credits`, `setflag hall_of_fame`,
`warp player_house_2f 3 4`.

**Rewards:** Ryuga → badge 8 + `tm dragonpulse` + `dragoniumz`. Super Rod (Dragonspire fisherman), Dragonair L50 with
Extreme Speed (Dragon Trial quiz), Choice Scarf (3 Dragon Scales to the collector: Dragonspire hidden, Umbral islet,
gym 2F hidden, plus the one TIDES holds), Seadra "TIDES" L50 @ Dragon Scale via
`trade seaking seadra 50 nick=TIDES item=dragonscale`, `tm earthquake` (Highland Lodge), Absolite (summit 1F), Draco and
Spooky Plates, PP Max, Gold Bottle Cap, rare candies and the usual endgame consumables. Shops (`world/shops/p3b.txt`):
`p3b_dragonspire`, `p3b_dragonspire_tm`, `p3b_league`.

**Deviations:** Champion is L65–66 (brief said 65–66; the table above says 65–68), and "one gimmick chosen by the AI"
is a random choice between three variants, since the engine lets a trainer use each listed gimmick once. E4 levels
are 60–64 (brief), not 60–65. Credits play only on the first Hall of Fame; later wins still record the Hall of Fame.
New map ids not in the geography table: umbral_pass (region 13,4), prism_summit_2f/3f/peak, route13_lodge,
prism_league, league_* rooms. Nox's backstory (ex-Eclipse) is new lore. TMs are handed out only by scripts, because a
map `item … tm:x` line loses its `:` when the compiler applies toId. Music ids are real tracks from assets/audio/music.
Generated `assets/` and `reports/` output is not committed.

## Part 3C notes (post-game: Coral Isles)
**Access:** `ferry_to_coral` (`world/scripts/p3c_ferry.pks`) is called by Part 1's `tidewater_ferry` after `hall_of_fame`;
it warps to `coral_town` 11 22 (pier). The Coral pier sailor (`p3c_ferry_return`) warps back to `port_tidewater` 27 6
(north pier, next to the ferry sailor). Coral Town has a fly point, so later visits can use Fly.
**Stubs to delete:** none left — `world/scripts/p1_stub_ferry.pks` was deleted on this branch (the lead branch still has
it; keep the deletion). The temporary route9 stub was removed after merging 3A.

**Maps (29):** `coral_town` (town, fly 12 12, region 26,22; + `coral_center`, `coral_mart`, `coral_house` (Nalu),
`coral_house2` (trader/scholar), `champions_hall`, `champions_hall_league`), `coral_route14` (+ `max_den`),
`tower_isle` (+ `battle_tower`, `battle_tower_room`), `coral_route17` (+ `silent_grotto_1f`, `silent_grotto_b1f`),
`tempest_isle` (+ `tempest_cave`, `tempest_cave_depths`), `cinder_isle` (+ `magma_vent`), `coral_route15`,
`eclipse_atoll` (+ `altar_of_sun`, `altar_of_moon`, `light_crater`), `coral_route16` (+ `seafloor_cavern`),
`pillar_isle` (+ `sky_pillar_1f`, `sky_pillar_top`). Region cells 24..30 × 19..26.
**Connections (all offset 0):** coral_town N→coral_route17 N→tempest_isle; coral_town E→coral_route14 E→tower_isle
(land path y=11,12, sea elsewhere); coral_town W→cinder_isle (sea); coral_town S→coral_route15 S→eclipse_atoll;
coral_route15 E→coral_route16 E→pillar_isle (sea). Surf is needed for everything except Route 14 / Tower Isle.
Powerspot maps: `max_den`, `battle_tower_room`, `champions_hall`, `champions_hall_league`. Prefabs in
`world/prefabs/p3c.txt`: `p3c_hall`, `p3c_tower`, `p3c_shrine`.

**Legendaries** (`p3c_legends.pks`, all `wildbattle … norun`, NPC sprite `legendary_shadow`): Articuno (Route 17 iceberg),
Zapdos (Tower Isle islet), Moltres (Cinder summit), Raikou (Tempest Isle), Entei (Cinder Isle), Suicune (Route 16 spring)
L70; Lugia L72 (Tempest depths, gale gate needs `silverwing` from the storm sage after all three birds are faced);
Kyogre/Groudon L72 (Seafloor Cavern / Magma Vent; first capture gives `blueorb`/`redorb` → Primal Reversion);
Rayquaza L75 with Dragon Ascent (Sky Pillar, doors open after both titans are faced); Solgaleo/Lunala L72 (altars;
catching either lets the atoll warden give `ultranecroziumz` and run `unlock ultraburst`); Necrozma L72 in
`light_crater` (`showif=p3b_necrozma_defeated`, hidden if `caught necrozma`); Mewtwo L75 (Silent Grotto, ranger moves
after the Champion rematch); Eternatus L75 (`max_den`, needs `gimmick dynamax`). Caught → `p3c_caught_<species>`
(hidden for good); defeated → `p3c_met_<species>` and it returns on the next map entry. The Prism Pedestal fuses
Necrozma via `trade necrozma necrozmaduskmane|necrozmadawnwings 75` (and back), since the engine has no fusion items.
**Battle Tower** (`p3c_tower.pks`, trainers `p3c_tower.txt`): 7 scripted `battle … canlose` rounds in
`battle_tower_room` with `heal` between (tier A L70 ×3, tier B L73 ×3, Tower Ace L75 or Tycoon Aurelio L80 at streak
20 and 48). Vars `p3c_tower_streak`, `p3c_tower_won`, `p3c_tower_sets`, `p3c_bp` (1 BP/win, +5 per set, +10 Tycoon).
BP exchange: rare candy, bottle caps, Ability Capsule/Patch, mints, competitive hold items, four Mega Stones.
**Champions' Hall** (`p3c_hall.pks`, `p3c_hall.txt`): repeatable rematches with the 8 leaders (L70-74; first win →
signature TM + Bottle Cap, flags `p3c_beat_<leader>`); all 8 open the Rematch Chamber (`p3c_league_open`): Morrow,
Ferrum, Sibyl, Nox (L74-77) in any order without healing (per-visit flags `p3c_e4v_*`, cleared on entry/loss), then
Celeste (L78-80); first Champion win → Master Ball + Gold Bottle Cap and `p3c_champion_rematch` (opens the grotto).
Rival rematch L72-75 in Coral Town (`p3c_rival_<starter>`, first win → Exp. Candy XL ×3 + Ability Capsule).
**Side content:** Alolan starter gift L50 (Nalu, `p3c_nalu_gift`), Super Rod (fisher), Exeggutor → Alolan Exeggutor
trade (`p3c_traded`), Big Pearl ×3 → Ability Patch, six-guardians survey → Master Ball, Max raids (`p3c_raid_wins`, 8
Gigantamax-factor species L65). Quests in `world/quests/p3c.txt`: `p3c_isles`, `p3c_legends`, `p3c_birds`,
`p3c_primal`, `p3c_ultra`, `p3c_tower`, `p3c_shells`, `p3c_trade`, `p3c_raids`. Shops: `p3c_coral_mart`, `p3c_coral_tms`.
**Content:** 84 trainers (`p3c_routes.txt` 52, `p3c_tower.txt` 16, `p3c_hall.txt` 16 incl. rival; classes `p3c_tycoon`, `p3c_towerace` in
`p3c_classes.txt`), 15 encounter tables (mostly L55-70; rods from L40; Alolan forms on Route 14/Cinder Isle), 77 visible/hidden items.
**Deviations:** none for gifts: `result` survives the gift's message box (1 stored, 0 no room).
E4/Champion rematch names follow the CAMPAIGN cast (3B may rename). `checkProgression` counts the ferry as an edge
without the `hall_of_fame` gate, so 3C maps appear reachable from 4 badges (Surf) in its report.

## Expansion E3 notes (legs 7–9: Ember Badge → Hall of Fame)
**Scope:** 19 new maps, 96 new trainers (`world/trainers/p4c_leg7/8/9.txt`, shared + 3A/3B classes only), 11 encounter
tables (`world/encounters/p4c_leg7/8/9.txt`), scripts `world/scripts/p4c_leg7/8/9.pks`, 9 quests in `world/quests/p4c.txt`.
No leader, rival, admin, boss, E4 or Champion team was touched; campaign.json, legend.txt, prefabs.txt and
classes.txt are unchanged. Existing flags, script ids, warps and connections are kept. B3 still holds: route12 is
16 tall, its east column is open only at y=6,7, and the connect offsets are the same. Every ice field was checked with a
slide simulator that copies the engine's ice and ledge rules: each is solvable and none can trap the player. Waterfalls
can only be climbed, so every upper level that a waterfall leads to has one-way ledges back down.

**Leg 7 (cap 52; trainers 44–50).**
* `p4c_rime_hollow_1f` / `_b1` / `p4c_rime_den`: ice cave behind Frostmere (cave mouth at frostmere_town 26,0).
  It has two ice fields (11 and 13 moves) and a `powerspot` den with a one-time G-Max Snorlax L50 (`p4c_snorlax_done`).
  Quest `p4c_power_survey`: Brynn (new NPC in hollis_lodge, after `p3a_got_dynamaxband`) sends you to step on 3 glowing
  vents (`p4c_hollis_survey`, `p4c_rime_read1..3`), then gives a Weakness Policy and 3 Dynamax Candy (`p4c_hollis_done`).
* `p4c_tide_grotto` → `p4c_wreck_cabins` → `p4c_wreck_hold` (dark, flooded; Surf inside): a sea cave in the Route 11
  islet cliff (route11 6,24) holding the wreck of the S.S. Halcyon. Quest `p4c_halcyon`: Captain Morrel (new NPC in
  route11_cottage) → the strongbox in the hold (`p4c_got_halcyon_log`, Relic Gold) → TM Liquidation and Leftovers
  (`p4c_halcyon_quest`, `p4c_halcyon_done`). The anchor in the hold is a static Dhelmise L47 (`p4c_dhelmise_done`).
* `p4c_geode_hollow`: Crystal Cavern's lowest floor, reached by a ladder in 1F's east room behind the Strength boulder
  (crystal_cavern_1f 18,4). It holds Tera Shards of five types. Quest `p4c_crystal_thief`: the porter at Quartz's camp
  (b1) → beat Grunt Dorran (`p4c_straggler_beaten`) → TM Tera Blast and 3 Steel Tera Shards (`p4c_crystal_thief_done`).
* Quest `p4c_heart_tutor`: Margit in Frostmere takes one Heart Scale as tuition, then tutors for free from then on
  (`movetutor p4c_margit`; her ice/coverage list is in `world/tutors.txt`). Heart Scales are hidden in Rime Hollow, Tide
  Grotto, the wreck and Skyfall Grotto.
* New trainers: Route 10 +3, Route 11 +3, Crystal Cavern 1F +2. New NPCs: a geologist on Route 10, an aurora-watching
  kid in Frostmere.

**Leg 8 (cap 58; trainers 49–57).**
* Route 12 is wider (40 → 60) with **Hailstone Ridge**, 20 new columns at x=21; existing objects east of it moved +20.
  The ridge has 5 trainers and a frozen tarn. Its waterfall (badge 7) climbs to a ledge with the Frost Tablet, TM
  Avalanche and the cave mouth to `p4c_frozen_falls`. Inside are an ice floor, a cave lake and a second waterfall up to
  an upper gallery. A ladder in the gallery leads to `p4c_frozen_falls_depths`, an ice field (11 moves) with a Choice
  Band at the end.
* `p4c_roost_trail` → `p4c_dragon_roost` → `p4c_roost_shrine`: a switchback mountain shrine above Dragonspire (cave mouth
  at dragonspire_city 30,3). Quest `p4c_roost_trial`: after Draca's `p3b_dragon_trial`, beat Hermit Sorin (5 dragons,
  L55–57) for TM Draco Meteor.
* `p4c_dragonspire_archive`: the Clan Archive (a house at dragonspire_city 26,21). Quest `p4c_prism_tablets` (legendary
  lore): make rubbings of the Frost, Sky and Pond tablets (`p4c_tablet_frost/sky/pond`). The Pond Tablet stands on a rock
  in Scalemirror Pond (Surf). Archivist Wen then pieces the legend together: the Dark Noon of forty years ago happened
  when the Halcyon took crystals off the mountain. Reward: TM Meteor Beam and a Comet Shard.
* `p4c_eclipse_workshop` → `p4c_eclipse_lab`: an Eclipse lens workshop off Umbral Pass (cave mouth at umbral_pass 1,30).
  Two floor switches (`p4c_ws_a/b`, `tileif`) lower the gates to the lab. Quest `p4c_second_lens`: Clan scout Haru →
  beat Chief Halloran (`p4c_ws_chief_beaten`) → tip over the backup lens (`p4c_lens_destroyed`) → TM Dark Pulse and an
  Exp. Candy XL. In-game this is reached after the Dusk Badge, but the estimator counts it in leg 8, as it does Umbral Pass.
* New trainers: Umbral Pass +2.

**Leg 9 (E4 60–64; trainers 55–62).**
* Route 13 +3 trainers and a painter. Surf to the river's north end to reach the cave mouth at route13 15,1 →
  `p4c_skyfall_grotto` (waterfall up, ledges down) → `p4c_skyfall_basin`, a hidden valley with Dratini/Dragonair on the
  Super Rod.
* `p4c_vr_deep_b1` / `_b2` (**Victory Road Depths**): an optional branch down a ladder on Victory Road 2F (5,21), with
  10 strong trainers (L59–61, mostly 4 Pokémon) and rare items: Rocky Helmet, Choice Specs, Assault Vest, TMs Stone
  Edge and Focus Blast, a Gold Bottle Cap and a PP Max. A Rock Smash pocket uses `p4c_vd1_smash`.
* Quest `p4c_remnants` (Eclipse remnant hunt): Agent Calloway (new NPC in route13_lodge) sends you after three cells.
  They are a grunt on the Route 13 lake islet (`p4c_remnant_islet_done`), Dr. Vexley in the Depths
  (`p4c_remnant_depths_done`) and Captain Sable (admin class, L59–60) in Skyfall Basin (`p4c_remnant_leader_done`).
  Reward: an Ability Patch and TM Knock Off.
* Victory Road +3 Ace Trainers (1F +1, 3F +2). The E4/Champion rooms and prism_league are untouched.

**Estimator (`./gradlew :tools:estimatePlaytime`), minutes critical / with optional:**
| Leg | Before | After | Δ with optional |
|---|---|---|---|
| 7 gym 6 → gym 7 | 66 / 75 | 82 / 146 | +71 |
| 8 gym 7 → gym 8 | 47 / 109 | 58 / 179 | +70 |
| 9 gym 8 → Hall of Fame | 107 / 108 | 123 / 178 | +70 |
| Whole game | 9.3 h / 12.5 h | 10.1 h / 16.4 h | (E3 only) |

**Deviations / notes:** map ids also carry the `p4c_` prefix. Tutor lists come from `assets/world/tutors.json`, which
the compiler doesn't generate, so Margit teaches the engine's default list. Wild battles in the new dens use the
`gmax`/`norun` options as 3C does. Every waterfall-reached area has its own ledge exit because the engine cannot
descend waterfalls. Generated `assets/` and `reports/` output is not committed.

## Expansion E2 notes (legs 4–6: Gym 3 → Gym 6)
**Scope:** 26 new maps, 124 new trainers (`world/trainers/p4b_leg4/5/6.txt`; classes `p4b_worker`, `p4b_ruinmaniac`,
`p4b_hexmaniac`, `p4b_picnicker`, `p4b_fairytale`, `p4b_diver(_f)`, `p4b_firebreather`, `p4b_glassblower` in
`p4b_classes.txt`), 16 encounter tables (`world/encounters/p4b_leg4/5/6.txt`), 9 side quests (`world/quests/p4b.txt`),
scripts in `world/scripts/p4b_leg4/5/6.pks`, shop `p4b_sweets` (`world/shops/p4b.txt`). No gym leader, rival, admin,
campaign.json, legend or prefab changes. Every new area is optional and a dead end, so the critical path is unchanged
except Route 9 (see below). Boulders always sit in a gap between two open rooms (no soft-locks); Rock Smash/Cut
obstacles only guard shortcuts, items and side rooms.

**Leg 4 (3 badges: Cut, Flash, Rock Smash, Strength).**
* Route 7 (+2 trainers) gains a north edge opening (`tile` overrides at x=12,13, `connect north p4b_pylon_ridge -2`) to
  **Pylon Ridge** (`p4b_pylon_ridge`, 6 trainers, Electric table) with the **Lineman's Hut** (`p4b_pylon_hut`: Hugo heals;
  Nell trades Plusle "Sparky" for a Minun, `p4b_traded_plusle`).
* Route 8 (+2 trainers): a crack at (2,17) leads to **Strata Gulch** (`p4b_strata_gulch`, 6 trainers, ledged terraces,
  boulder gap to the west valley, spring pool, hidden Dome Fossil; Dr. Fenna) and on to the **Gulch Caves**
  (`p4b_gulch_caves`, 5 trainers, boulder to the TM room, Rock Smash to a hidden Claw Fossil).
* Quarrytown: Shaft No. 3 entrance at (25,0) and old **Bryn**. **Old Shaft** `p4b_old_mine_1f` (4 trainers, boulder storage
  room) → `p4b_old_mine_b1f` (dark, 4 trainers, two boulder gaps, flooded reservoir with rods; islet item needs Surf
  later) → `p4b_old_mine_b2f` Fossil Vault (3 trainers): take the Skull **or** Armor Fossil (`p4b_mine_fossil`), and
  Tam's lamp behind a smash rock.
* Ruins of Sol (+2 trainers): stairs at (12,17) into the **Sol Catacombs** (`p4b_sol_catacombs`, dark, 4 trainers): three
  sun dials (`p4b_sol_dial1..3`, trap dial at 27,22 warps back) open the gate (`p4b_sol_open`, `tileif`) to the **Sun
  Sanctum** (`p4b_sol_sanctum`): Keeper Ilia (scripted `canlose` battle, `p4b_beat_sol_keeper`) → Hisuian Growlithe L30
  (`p4b_got_sol_gift`). Four lore tablets (`p4b_tablet1..4`) tell the Prism beast / Sun & Moon guardians / Ring of Light /
  ancient Eclipse story.
* Fossil Lab: the aide (`fossil_aide`, now script `p4b_fossil_aide`) revives Skull, Armor, Dome, Helix, Claw and Root
  fossils at L30. Temple of Sol: the acolyte (`temple_acolyte`) is now the tablet scholar (`p4b_temple_scholar`).
* Quests: `p4b_canary_lamp` (Tam's lamp → TM Stealth Rock + 2 Exp. Candy M), `p4b_sol_tablets` (→ TM Solar Beam + Heat
  Rock), `p4b_strata_survey` (catch Klawf, Silicobra, Timburr → Expert Belt).

**Leg 5 (4 badges: + Surf).**
* Mt. Prism 1F (+1 trainer): the lake's east column floods; a landing at (21,5) and cave at (22,5) lead to the **Prism
  Grotto** (`p4b_prism_grotto`, Surf, 6 trainers incl. 3 divers on the water, hermit, 3 prism shards `p4b_shard1..3`,
  hidden Helix Fossil, boulder to the crystal chamber) and its ladder to the **Crystal Depths** (`p4b_crystal_depths`,
  dark, 6 trainers, smash shortcut, boulder-sealed heart chamber). A west tunnel at (1,19) opens onto the **Prism
  Foothills** (`p4b_prism_foothills`, 7 trainers, ledged terraces, Axew/Bagon rare).
* Mt. Prism 2F (+1 trainer): ladder at (7,3) to the **Eclipse Dig Site** (`p4b_prism_dig`, dark, 5 grunts + scientist Nyle
  `p4b_dig_sci_nyle` scripted, `p4b_dig_cleared`; caged Carbink L36 gift `p4b_got_carbink`; surveyor Maeve). 3F +1 grunt.
* Seraphine Town: north path (x=15,16; `connect north p4b_mistwood -1`) to **Mistwood** (`p4b_mistwood`, fog, 7 trainers,
  moon pond with shrine islet: at night a Clefairy L36 `wildbattle` once, `p4b_moon_clefairy`; Cut tree shortcuts;
  **Ranger Cabin** `p4b_mistwood_cabin`: Elm heals, Ivo trades Impidimp "Puck" for a Hatenna `p4b_traded_impidimp`;
  hidden **Mist Hollow** `p4b_mist_hollow` behind a Cut tree with a static Mr. Mime L37 `p4b_mime_done`) and north to
  **Mistwood Depths** (`p4b_mistwood_depths`, 8 trainers, Granny Moss, Elowen's teacher: Pixie Plate after badge 5).
* **Sweets for Milcery:** Pâtissier Mille in the Seraphine Mart (`p4b_patissier`) sells all seven Sweets (shop
  `p4b_sweets`, 500 each) and explains the hold-a-Sweet-and-spin evolution.
* Quests: `p4b_prism_choir` (3 shards → TM Power Gem + Lucky Egg), `p4b_stolen_light` (stop Nyle → Carbink, Exp. Candy L,
  Max Revive), `p4b_lost_poppy` (find Poppy in Mistwood → TM Play Rough).

**Leg 6 (5 badges).**
* **Route 9 extended** west by 24 columns (40 → 64 wide; existing entities shifted +24 x; still 20 tall, east column open
  only at y=8,9 → B2 unchanged; west column open only at y=10,11 → Ashfall link unchanged). The new **Cinder Pass**
  section has 4 trainers, an ash plateau and a hot spring, plus entrances to **Cinder Hollow** (`p4b_cinder_hollow_1f`
  5 trainers, boulder niche with TM Will-O-Wisp; `_b1f` dark, 5 trainers, hurt hiker, boulder pocket with a hidden Root
  Fossil; `_b2f` Vent Core, 4 trainers) and the **Steam Grotto** (`p4b_steam_grotto`, 5 trainers, hot pools, attendant
  heals).
* Ashfall City: west gate (x=0, y=14,15; `connect west p4b_ashen_trail 0`) to the **Ashen Trail** (`p4b_ashen_trail`, sand
  weather, 6 trainers incl. Kaen's three Fire Breathers) → **Obsidian Grotto** (`p4b_obsidian_grotto`, Master Kaen,
  scripted `canlose`) and → **Magma Tunnel** (`p4b_magma_tunnel`, 5 trainers) → **Ashfall Caldera** (`p4b_ashfall_caldera`,
  7 trainers, crater lake islet with the rainbow-bird shrine, Ho-Oh lore sage). New **Glass Workshop** (`p4b_ashfall_glass`):
  Vitra and her son, who trades Charcadet "Kindle" for a Magcargo (`p4b_traded_charcadet`).
* Quests: `p4b_ember_escort` (Burn Heal for the hurt hiker → TM Flare Blitz + Exp. Candy L), `p4b_trial_by_fire` (beat
  Brand, Blaze, Coal, then Kaen → TM Heat Wave + Larvesta egg), `p4b_glass_furnace` (Heat Rock → Life Orb).

**Flags:** quest/gift flags above; puzzle/obstacle flags `p4b_mine1_*`, `p4b_mine2_boulder1/2`, `p4b_mine3_smash`,
`p4b_gulch_*`, `p4b_gcave_*`, `p4b_grotto_boulder`, `p4b_cdepth_*`, `p4b_dig_*`, `p4b_depths_*`, `p4b_mist_cut1/2`,
`p4b_ch1_*`, `p4b_ch2_boulder`, `p4b_tunnel_smash`; others `p4b_aide_revived`, `p4b_found_lamp`, `p4b_tablets_read`,
`p4b_poppy_found`, `p4b_hiker_rescued`, `p4b_moss_gift`.

**New species families** (wild, gift or trade): Timburr, Klawf, Orthworm, Durant, Alolan Diglett, Honedge, Sinistea,
Greavard, Gimmighoul, Solosis, Silicobra, Rockruff, Cubone, Yamper, Tadbulb, Wattrel, Minun/Plusle, Minior, Dewpider,
Clefairy, Jigglypuff, Petilil, Fomantis, Phantump, Foongus, Impidimp, Axew, Bagon, Darumaka, Sizzlipede, Litleo,
Pansear, Capsakid, Cacnea, Skorupi; fossils Cranidos, Shieldon, Kabuto, Omanyte,
Anorith, Lileep; gifts Hisuian Growlithe, Carbink, Larvesta egg; trade-only Charcadet.

**Estimator** (`./gradlew :tools:estimatePlaytime`, minutes critical / with optional):
| Leg | Before | After |
|---|---|---|
| 4: Gym 3 → Gym 4 | 48 / 60 | 55 / 136 |
| 5: Gym 4 → Gym 5 | 58 / 61 | 61 / 136 |
| 6: Gym 5 → Gym 6 | 40 / 48 | 48 / 121 |
Whole game (with E1/E3 not merged): critical 9.3 h → 9.7 h, with optional 12.5 h → 16.6 h.

## Expansion E1 notes (legs 1–3: start → Gym 3)
Prefix `p4a_` for all new maps, flags, scripts, trainers and encounter tables. Files: `world/maps/p4a_*.map` (33),
`world/scripts/p4a_leg1..3.pks`, `world/trainers/p4a_leg1..3.txt` (137 trainer entries incl. 12 rematch tiers and 2 scripted bosses; shared classes only),
`world/encounters/p4a_leg1..3.txt` (18 tables), `world/quests/p4a.txt` (9 quests), `world/prefabs/p4a.txt`
(`p4a_lighthouse` tower, `p4a_wreck` hull). No gym leader/rival teams, campaign.json, legend, prefabs.txt or
classes.txt were touched. B1 (port_tidewater ↔ route5) is unchanged: port's bottom row and route5's top row are as before.

**New areas (all optional, all leg-local field moves only):**
| Leg | Area (map ids) | Access | Content |
|---|---|---|---|
| 1 | Brookside Trail `p4a_brook_trail` + Angler's Cottage `p4a_brook_cottage` | hometown east edge (`connect east … -2`) | fishing spot, 4 trainers, the odd tree (Sudowoodo L12), nook TM Rock Tomb |
| 1 | Brook Falls `p4a_brook_falls` | Brookside Trail north edge | waterfall basin, ledges, 5 trainers, fishing |
| 1 | Brookspring Grotto `p4a_brook_grotto` | hidden cave mouth in the trail's NE tree pocket | **hidden grotto**: Water Stone, Ralts/Azurill, Surf-only Big Pearl |
| 1 | Mossgrove Thicket `p4a_mossgrove` → Glade `p4a_mossgrove_glade` → Root Hollow `p4a_root_hollow` | Whisperwood east rug (27,5) | **side dungeon**: one-way ledge puzzle to two mossy stones (`p4a_mg_stone_a/b` → `p4a_mg_gate` opens the bushes); Root Hollow switch puzzle (`p4a_rh_wall1/2`) to TM Seed Bomb; 15 trainers |
| 1 | Petal Path `p4a_petal_path` + Berry Cottage `p4a_petal_cottage` | Fernwick east edge | flower meadow, pond fishing, 6 trainers, berry grower (gifts per badge), Combee show-off reward |
| 1 | Lantern Tower `p4a_lantern_tower_1f/2f/3f`, Flower Shop `p4a_brindle_florist` | Brindle City | Sprout-Tower homage: 4 sages + Elder Rowan (scripted battle, TM Swift, light-shard lore) |
| 2 | Sunberry Orchard `p4a_orchard` + `p4a_orchard_house` | Route 3 north, Cut tree `p4a_cut_orchard` | 8 trainers, pond fishing, rest stop (heal) |
| 2 | Tidewater Cape `p4a_tidewater_cape`, Lighthouse `p4a_lighthouse_1f/2f/top` | port_tidewater north edge (`connect north … -4`) | cliffs + beach, 7 trainers, lighthouse (4 trainers); lamp room behind `tileif p4a_lh_unlocked` |
| 2 | Sea Grotto `p4a_cape_grotto` | cave mouth on the cape beach | **hidden grotto**: keeper's key (`basementkey`), King's Rock, 2 trainers |
| 2 | S.S. Driftwood `p4a_wreck_deck`, `p4a_wreck_hold` | wreck on the cape's south beach | 6 trainers, captain's log lore (crystal cargo), TM Hex |
| 2 | Cobblecrest B2F `p4a_cobblecrest_b2f` | new ladder in cobblecrest_b1f (20,3) | **side dungeon**: underground lake, switch puzzle (`p4a_cc2_gate1/2`) to the miners' vault (TM Bulldoze), 5 trainers |
| 2 | Riptide Dojo `p4a_riptide_dojo` | Port Tidewater | 4 students + Master Kaito → Tyrogue L15 gift |
| 3 | Glimmer Cave `p4a_glimmer_cave_1f/b1f` | cave mouth on route6's top row (6,0) | **side dungeon**: `dark` (Flash), Rock Smash pockets, 9 trainers, Machine Part, TM Power Gem |
| 3 | Lumen Catacombs `p4a_lumen_catacombs` | stairs in lumen_ruins (18,16), revealed by `tileif p2_lumen_door` | order puzzle (dawn E → noon N → dusk W → night S; var `p4a_cat_seq`, flag `p4a_cat_open`), TM Ancient Power, 4 trainers |
| 3 | Solar Ridge `p4a_solar_ridge` + Ridge Station `p4a_ridge_station` | voltara_city north edge | Electric plateau, reservoir fishing, 8 trainers |
| 3 | Sparkstone Grotto `p4a_ridge_grotto` | behind Rock Smash rock `p4a_sr_smash` on the ridge | **hidden grotto**: Minior/Carbink, Electric Seed, Full Restore |
| 3 | Seabreeze Cove `p4a_seabreeze_cove` + Beach Hut `p4a_cove_hut` | route5 east edge (`connect east … 15`) | beach, tide-pool fishing, 7 trainers |
| 3 | Voltmire Marsh `p4a_voltmire_marsh` | route6 bottom edge (`connect south … 10`) | wetland, boardwalks, 6 trainers, hermit lore; Surf-only islet TM Mud Shot |

**Existing maps edited (additions only):** hometown, fernwick_town (east exits + signs), route1 (2 trainers + Pip),
route2 (2), whisperwood (3 + Mossgrove rug), brindle_city (flower shop, Lantern Tower), brindle/fernwick/tidewater/
voltara centers (aides, traders), route3 (Cut tree + 3), route4 (3 + Teo), cobblecrest_1f/b1f (1 each + ladder),
port_tidewater (north exit, dojo, trader), route5 (east exit + 3), lumen_ruins (catacombs stair), route6 (cave
mouth, south exit, 2 + Dot), voltara_city (north exit).

**Side quests (`world/quests/p4a.txt`):** `p4a_odd_tree` (Squirt Bottle in Mossgrove Glade → Brindle florist gives
TM Magical Leaf → water the odd tree → Sudowoodo L12; flags `p4a_florist_thanked`, `p4a_sudowoodo_done`),
`p4a_fishing_log` (catch Magikarp/Poliwag/Goldeen → Mystic Water + 3 Lure Balls), `p4a_feathered_trade`
(`trade pidgey rookidee 8 nick=Corvo`, `p4a_traded_rookidee`), `p4a_lamp_key` (Basement Key from the Sea Grotto →
TM Charge Beam, `p4a_lh_unlocked`, `p4a_lh_reward`), `p4a_orchard_fertilizer` (Big Mushroom → Leftovers),
`p4a_inky_trade` (`trade machop clobbopus 15 nick=Inky`), `p4a_machine_part` (Machine Part from Glimmer B1F →
Pawmi L20), `p4a_zappy_trade` (`trade electrike blitzle 22 nick=Zappy`), `p4a_shell_bell` (Big Pearl → Shell Bell).
**Pokédex rewards:** Linden's aides `p4a_dex_aide_1/2/3` (Brindle/Tidewater/Voltara centers) at 10/20/30 species
caught → Great Balls + Exp. Candy S, Amulet Coin, Ability Capsule + Exp. Candy M (`p4a_dex_reward_1..3`).
**Phone-style rematch NPCs:** Pip (Route 1; `p4a_pip_1..4`, tiers at 0/2/4/6 badges), Teo (Route 4; `p4a_teo_1..4`,
1/3/5/7), Dot (Route 6; `p4a_dot_1..4`, 2/4/6/8); each rematch pays out an item. Other gifts: Tyrogue (dojo, `p4a_dojo_gift`),
berry grower (`p4a_berry_gift`, `_b1`, `_b3`), Honey (`p4a_honey_shown`).

**Roster:** obtainable species (incl. evolutions) 568 → 691 before E2/E3 (e.g. Rookidee, Surskit, Lotad, Taillow,
Zigzagoon, Combee, Cherubi, Applin, Phantump, Ralts, Whismur, Tyrogue, Clobbopus, Wattrel, Skrelp, Clamperl,
Mareep, Plusle/Minun, Blitzle, Pawmi, Tadbulb, Minior, Beldum (rare), Sandygast, Paldean Wooper, Stunfisk).

**Field moves:** leg 1 areas need none; leg 2 uses Cut only for the orchard gate; leg 3 uses Flash (Glimmer Cave is
`dark` but walkable) and Rock Smash for optional pockets. Surf-only islets hold bonus items for later visits.

**Estimator (minutes, critical / with optional):**
| Leg | Before | After |
|---|---|---|
| 1: start → Gym 1 | 43 / 64 | 50 / 135 |
| 2: Gym 1 → Gym 2 | 51 / 75 | 65 / 147 |
| 3: Gym 2 → Gym 3 | 50 / 83 | 61 / 153 |
Whole game after merging E1+E2+E3: critical 11.1 h, with optional content 24.4 h.

**Note:** inline `text="…"` on npc/sign lines now compiles to the full multi-word text (fixed in 2a087fd).
