# Gameplay fidelity matrix

**Rules.** Pokémon Crystalline follows Gold/Silver/Crystal (GSC) **presentation and flow**. Battle **mechanics** follow the project's Gen IX ruleset (damage, abilities, gimmicks).
- **Bug**: the game did something no reference game does, or broke its own rules.
- **Design difference**: a deliberate choice that stays.
- **Unverified**: the reference behaviour could not be confirmed from a primary source.

**Sources.** Most GSC and Gen 3 behaviour was read from the pret disassemblies:
- [pokecrystal](https://github.com/pret/pokecrystal): `engine/battle/core.asm`, `data/text/battle.asm`, `engine/battle/trainer_huds.asm`, `constants/misc_constants.asm`, `data/maps/setup_scripts.asm`.
- [pokeemerald](https://github.com/pret/pokeemerald): `data/battle_scripts_1.s`, `src/battle_message.c`.

Other sources:
- HGSS text: the [projectpokemon HGSS text dump](https://projectpokemon.org/rawdb/heartgold/msg/197.php).
- Capture: [Serebii, capture mechanics](https://www.serebii.net/games/capture.shtml) and [Serebii, Scarlet/Violet catch mechanics](https://www.serebii.net/scarletviolet/catchmechanics.shtml).
- Experience: [Serebii, experience](https://www.serebii.net/games/exp.shtml).
- Sword/Shield battle style: [Hypertext, 2019](https://htxt.co.za/2019/11/pokemon-sword-shield-too-easy-change-battle-style-from-switch-to-set/).

Bulbapedia was not reachable from the build machine, so nothing here relies on it unless it is marked unverified.

| # | Mechanic | Before this change | Reference | Difference found | Intended behaviour | Pri | Status | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | Wild battle start/end | Wild Pokémon appears instantly; victory music at the very end | GSC: slides in, cry, "Wild X appeared!". Victory music starts when the foe faints, before EXP | Missing slide; late music | Slide-in, cry, text. Victory theme on the faint. No money from wild battles | M | **Fixed** | screenshots `w00`–`w02`; `BattleFlowTest` (core) |
| 2 | Trainer intro | Portrait slid in; "would like to battle!" (Gen 3 wording) | GSC: "<CLASS NAME> wants to battle!", then "sent out", then the player's "Go!" | Wording | GSC wording, foe first, trainer slides out before the throw | L | **Fixed** | screenshots `b00`/`c00` |
| 3 | Party indicators | Not drawn (`ball_icons` asset unused) | GSC: 6-slot rows (normal/status/fainted/empty), player lower right, enemy upper left; redrawn each send-out | Missing | Both rows in trainer battles, updated as each faint or status is *shown*; player row in the wild intro. Never shows species | **H** | **Fixed** | `BattleFlowTest` (indicator count at the Shift prompt); screenshots `zoom1`/`zoom2` |
| 4 | Foe faint and voluntary switch | **Bug.** The fainted foe stayed until the player attacked again: the AI replacement ran inside the next `advance()`, after the player had already picked a move | GSC: faint, "Enemy X fainted!", EXP, level-up, then the Shift prompt ("<ENEMY> is about to use X. Will <PLAYER> change POKéMON?"), then "sent out". No prompt in Set mode, wild battles, or when the player's Pokémon also fainted. Cancelling the menu counts as No | Bug | Engine replacement phase before the next move request. Shift by default, Set option. Cancel = No | **H** | **Fixed** | engine `BattleFlowTest` (9), core `BattleFlowTest` (7) |
| 5 | Forced switch | Party opened; trainer and wild handled alike | GSC wild: "Use next POKéMON?" Yes = party, No = run attempt, failure = "Can't escape!" then a forced pick. Trainer: forced pick, cannot cancel | No wild question | As GSC | M | **Fixed** | engine `wild battle lets the player flee…`, core `wild battle asks…`, `forced switch…`; screenshots `f00`–`f04` |
| 6 | Send-out / recall text | Always "Go! X!" and "X, come back!" | GSC send-out line depends on the foe's HP (≥70/40/10 %). Recall line depends on damage dealt since entry | Text | GSC lines (with Gen 3 commas) | L | **Fixed** | `BattleFlowTest` logs |
| 7 | EXP, level-up, evolution | EXP right after the faint; evolution after battle | Same in GSC and Gen 3+ | None (engine order verified) | Kept. EXP is awarded once per faint | M | Correct | engine `the last foe ends the battle exactly once…` |
| 8 | Move feedback, HP bar | Flash and tint for every move, **including misses and failures**; HP drained at a fixed rate | GSC: animation only when the move connects. HP bar about 1 px per 2 frames (full 48 px ≈ 1.6 s) | Animations on failed moves | `MoveEffect` event only when the move took effect. Elapsed-time drain, 0.25 s + 0.9 s × fraction (slightly faster than GSC, set in `assets/anim/battle.json`) | M | **Fixed** | `AnimationPlayerTest`; screenshots `c10`–`c20` |
| 9 | Status and battle messages | Modern "The opposing X" / "The wild X" | GSC: "Enemy X" for wild and trainer foes | Wording | GSC "Enemy X" | L | **Fixed** | all battle tests (logs) |
| 10 | Capture | Ball arc, shakes, messages by shake count. **Bug:** full-HP captures in the opening routes were 95–100% certain | Scarlet/Violet formula (reverse-engineered, [docs/CAPTURE.md](CAPTURE.md)); GSC-style animation and messages | The low-level bonus made early captures near-certain; badge penalty missing; several ball errors | Scarlet/Violet rules in 1/4096 steps. Low-level bonus only on Casual. Then Pokédex entry (first registration), nickname offer, PC message | **H** | **Fixed** | `CaptureTest`, `AcquisitionTest`; `:tools:auditCapture`; screenshots `capture_*`, `starter_*` |
| 11 | Entrance appearance and transitions | Black fade; some entrances drawn as house doors; no door feedback | GSC: warps fade to **white**; route connections scroll with no fade; no door-open animation | Black fade; mismatched entrance art (see `reports/entrances.md`) | White warp fade. Short door-open on door tiles (Gen 3-style, skipped with Reduce Motion). Entrance tile must match the destination class | M | **Fixed** (fade, door). Entrance data: see `docs/WORLD_VALIDATION.md` | `EntranceTest`, `:tools:auditEntrances` |
| 12 | Items | "put the X in the BAG"; checker optimistic | GSC: "put the X in the <POCKET>". Hidden items via A on the tile, once | Wording; unreachable items | Pocket names; strict reachability audit | M | **Fixed** (text). Audit: `reports/items.md` | `:tools:auditItems` |
| 13 | Day/night presentation | Translucent blue overlay at night; no evening; no lights | GSC: palette swap per period (morning/day/night) | Overlay lowers contrast | Multiplicative tint per phase (adds a cosmetic dusk hour 17:00–17:59), lit windows at night, battle-independent | M | **Fixed** | `TimeAndEventsTest`; screenshots `n00`–`n03` |
| 14 | Time and weekday encounters | Period tables existed. Empty tables gave no encounters; clock read `LocalTime.now()` | GSC: 04/10/18 h boundaries; grass by time, water ignores time; weekday events (Friday Lapras, siblings) | Fragile fallback; no injectable clock; no weekday events | `GameClock` (device zone, DST-safe). Period + "any" slots, safe fallback. Data-driven weekly events | **H** | **Fixed** | `TimeAndEventsTest` (11) |
| 15 | Trainer AI and difficulty | AI levels 0–3; Normal is easy late (STATUS) | GSC: AI items only on the highest-level Pokémon; switching tiers | See `docs/PLAYTIME.md` | See `docs/PLAYTIME.md` | M | Reviewed | `:tools:estimatePlaytime` |
| 16 | Saves and resume | Autosave on pause; options persisted | GSC: manual save only. Modern games autosave | Design difference | Keep autosave (Android can kill the app). Battles are never saved mid-fight, so a reload never restores a fainted foe. Clock and events re-read on resume | M | Correct + **resume refresh added** | `TimeAndEventsTest` (save/load), `WorldTest` saves |
| 17 | Android controls/layout | Touch pad, portrait/landscape | — | Not verified on a device | — | M | Not device-tested (see STATUS) | — |
| 18 | Pacing and progression | 24 h estimate vs 30 h target | — | — | See `docs/PLAYTIME.md` | M | Reviewed | `:tools:estimatePlaytime` |

## Deliberate differences

| Behaviour | GSC | Crystalline | Why |
|---|---|---|---|
| Battle mechanics | Gen 2 | Gen IX (abilities, Fairy, gimmicks) | Project ruleset |
| Grass | Grass tile drawn over the walker's lower half, no rustle | Both: lower half covered **and** a short rustle (Gen 3+) | The brief asks for grass feedback. Cosmetic only |
| Door entry | No animation | 0.18 s door opening, then a white fade | Clearer entrances. Skipped with Reduce Motion |
| Running | No running shoes | Hold B to run (Gen 3+) | Existing quality-of-life choice |
| EXP Share | Held item, one holder | Party-wide (Gen VI+), option to turn off | Existing modern choice; see `docs/PLAYTIME.md` |
| Save | Manual | Manual plus autosave on pause | Android process death |
| Shift prompt text | One five-line box | Two boxes ("…is about to use X." / "Will ARI change POKéMON?") | Fits the 2-line Yes/No prompt at 256×224 |
| Night period ownership | Weekday changes at midnight | Weekly events treat 00:00–03:59 as the previous evening ("Thursday night" = Thu 18:00 to Fri 03:59) | Matches what players mean by "Thursday night". Documented in `docs/EVENTS.md` |

## Open or unverified

- **Trainer-battle EXP bonus (×1.5).** The engine applies it, as Gens V–VI do (Serebii). Some sources say Gen VII+ removed it. No primary source was reachable, so the formula is unchanged. If removed, trainer EXP falls by a third, which bears on the late-game over-levelling.
- **Scarlet/Violet Set option.** Sources disagree on whether it still exists. Irrelevant here, since Crystalline follows GSC (Shift and Set both offered).
- **Gen 4 ball-icon states and Gen 5+ Shift wording.** Not used; GSC is the target.
