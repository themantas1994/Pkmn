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
