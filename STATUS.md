# Project status

Every number in this document was produced by the commands listed next to it. Nothing here is estimated by hand unless it says so.

## Summary

| Area | State |
|---|---|
| Builds | Desktop (LWJGL3) and Android APK/AAB build reproducibly with `./gradlew`; release builds fail clearly without a signing key (explicit `-Ppkmn.release.allowDebugSigning=true` gives a non-publishable debug-signed build) |
| Content data | 1025 species + 354 forms, 951 moves, 318 abilities, 839 items, imported from Showdown and PokeAPI and validated |
| Battle engine | Singles, Gen IX rules, all gimmicks; every move, ability and held item classified (0 unimplemented). Replacement phase after a faint; Shift/Set battle styles |
| Presentation | Gold/Silver/Crystal-style battle flow with party Poké Ball indicators. Elapsed-time animation system for battle and overworld (skippable; speed and off settings) |
| Time | Device clock and time zone through an injectable `GameClock`; morning/day/night lighting; time-dependent encounters; data-driven weekly events (Thursday-night Mistwood Clefable) |
| Campaign | Complete from new game to the Hall of Fame, plus post-game: 223 maps, 684 trainers, 89 encounter tables, 459 scripts |
| Saves | Atomic, checksummed, versioned (v3, with migrations from v1 and v2), with backup/repair; corruption and migration tests pass |
| Capture | Scarlet/Violet formula in 1/4096 steps (badge penalty, critical captures, all balls); low-level bonus on Casual only. Pokédex entry on first registration; nickname offer for every Pokémon obtained ([docs/CAPTURE.md](docs/CAPTURE.md)) |
| Mechanics | Natures (incl. Mints), IVs, EVs and the stat formula verified; every core mechanic classified in [docs/MECHANICS.md](docs/MECHANICS.md) |
| Tests | 318 engine, 102 core and 142 tool tests, all passing; CI runs them on every push. 32 reproducible visual-regression screenshots of the real desktop game |
| Learnsets and moves | Every species/form audited (`:tools:auditLearnsets`: 0 errors) and every move executed in battle (`MoveExecutionTest`: 0 unsupported); see [docs/LEARNSETS.md](docs/LEARNSETS.md), [reports/moves-audit.md](reports/moves-audit.md) |
| Playtime | Content-derived estimate: 12.7 h critical path, 24.4 h with optional content, plus 4.5 h post-game. Below the ~30 h-to-the-E4 target; see [docs/PLAYTIME.md](docs/PLAYTIME.md) |

## Mechanics update: capture, Pokédex, nicknames, IVs/EVs, sprites

Every item was reproduced or measured first, then fixed and tested.

| Defect / gap | Root cause | Fix | Evidence |
|---|---|---|---|
| Wild Pokémon far too easy to catch, even at full HP | Scarlet/Violet's low-level bonus `(36 − 2L)/10` below level 13. This campaign's first two hours are level 2–8 with catch-rate-255 species, so full-HP throws were 95–100% certain. Catch-rate data was correct (0 mismatches vs PokeAPI) | Bonus only on Casual difficulty. Exact Scarlet/Violet maths otherwise | `reports/capture.md` (level-2 Pidgey: 100% → 44% at full HP), `CaptureTest` |
| No badge penalty; fractional maths; Timer/Nest/Level/Dream/Dive/Lure Ball errors; no EXP for catching | Implementation errors | Fixed against an independent reference | `CaptureTest` (13, incl. 4,000+ exact comparisons and Monte Carlo) |
| No Pokédex entry on capture; no nickname offer for captures or the starter | Not implemented | `GameContext.acquire` (idempotent), Pokédex entry screen, nickname flow, resume after interruption | `AcquisitionTest` (14); scenes `capture_*`, `starter_*` |
| Pokédex tracked base species only | — | Form and shiny records; seen/caught/unknown states | `AcquisitionTest` |
| Mints overwrote the original nature | Mint set `nature` | Separate stat nature (Gen VIII+) | `StatFormulaTest` |
| Level-100 Pokémon gained no EVs; no EV-reducing berries; vitamins changed max HP without keeping damage | Implementation gaps | Fixed | `StatFormulaTest` (10, hand-calculated fixtures) |
| A capture with every box full was lost | No check | Balls can't be thrown when storage is full | `AcquisitionTest` |
| Shop TMs unusable after purchase (all 32) | `compileShops` ran `toId()` over `tm:x` | `tm:` key kept; old saves converted | `AcquisitionTest`; found by the learnset audit |
| Form signature moves unlearnable (Rotom-Wash Hydro Pump, Zacian Behemoth Blade, ...) | Importer dropped Showdown `R` sources; `Dex.learnset` discarded a form's own TM list | Imported; merged | `:tools:auditLearnsets` 0 errors |
| 23 trainer/gift moves above the Pokémon's level or only legal in older games | Content | Replaced with legal moves of the same role | `:tools:auditLearnsets` |
| Moonlight/Morning Sun/Synthesis/Shore Up healed nothing; Spit Up did no damage; Knock Off, Wake-Up Slap, Smelling Salts, Fury Cutter lost their power changes; Burn Up, Double Shock, Steel Roller lost their failure checks; Diamond Storm gave +4 Def; Plasma Fists never set Ion Deluge | Missing heal data; duplicate script registrations overwrote each other; importer drops `self.chance` | Fixed | `MoveExecutionTest` (all 951 moves executed) |
| Sprite kit integration | — | `./gradlew :android:assemblePersonalDebugWithSprites`, a Kotlin port of the kit's style (cropped, 48px grid ×2, ≤4 colours) | `SpriteStylerTest` (28), full-roster run: 6,492 sprites |

## Post-release update: verified fixes

Each fix below was reproduced first, then checked with tests and in the running desktop game. Screenshots are in [docs/screenshots](docs/screenshots); see [docs/SCREENSHOTS.md](docs/SCREENSHOTS.md).

**Defects found and fixed**

| Defect | Root cause | Fix | Evidence |
|---|---|---|---|
| A fainted foe stayed on screen until the player attacked again | The engine only sent in the AI's replacement inside the *next* `advance()`, after the player had already picked a move against an empty slot | A replacement phase after each faint: `request()` asks nothing until replacements are in; Shift prompt; switch-in effects of simultaneous replacements run by speed | engine `BattleFlowTest` (9), core `BattleFlowTest` (7), screenshots `battle_*` |
| No party indicators | The `ball_icons` asset was never drawn | Ball rows for both sides, updated as each faint or status is *shown* | `battle_ui_one`, `battle_ui_six` |
| Move animations played on misses, failures and Protect | The presentation reacted to `MoveUsed` | New `MoveEffect` event, emitted only when a move took effect | `BattleFlowTest` event order; screenshots |
| Zone entrances drawn as house doors (29 warps), one exit landing on water | Generic `door` stamp reused for forests, towers, ruins, mines; Frozen Falls → Route 12 landing | Explicit entrance classes, 6 new entrance tiles, map data fixed, compiler errors on mismatches | `reports/entrances.md`, [docs/WORLD_VALIDATION.md](docs/WORLD_VALIDATION.md) |
| `gym_window` was walkable | Not in the solid-tile list | Solid | `EntranceTest` |
| TM Stone Edge drawn on a solid rock | Placement | Moved one tile; same id and flag | `reports/items.md` |
| Party 16–23 levels above late bosses with optional content | Party-wide Exp. Share together with the expansions' optional trainers | Exp. Share off by default for **new** players; existing option files keep it on | [docs/PLAYTIME.md](docs/PLAYTIME.md), `TimeAndEventsTest` |
| `java.time` would crash on Android 7.0–7.1 (minSdk 24) | No core-library desugaring | Desugaring enabled (`desugar_jdk_libs` 2.1.5) | APK dex contains `j$.time` |
| Empty or broken time-of-day encounter table gave no encounters | Fallback only when the key was missing | Period + `any` slots, then a documented fallback chain | `TimeAndEventsTest` |

**Also added**
- **Battle flow.** A `BattleFlow` state machine.
  - Shift is the default; Set skips the switch offer.
  - Wild battles ask "Use next POKéMON?": No tries to run; a failed escape forces a pick.
  - Gold/Silver/Crystal battle text: "Enemy X", "wants to battle!", and send-out and recall lines that depend on HP.
- **Animation.** `AnimationPlayer` runs elapsed-time clips; `assets/anim/battle.json` holds the timings and move-effect styles.
  - Battle: send-out, recall, faint, hit, HP/EXP bars, move effects, status, stat changes, transformations, capture and the victory slide.
  - Overworld: battle-start transition, door entry, white warp fade, grass, item pickup, cut/smash, event Pokémon.
  - B skips; an ANIM SPEED option; BATTLE ANIM OFF and REDUCE MOTION. Skipping applies each clip's end state (`AnimationPlayerTest`).
- **Clock, encounters, events.** See [docs/EVENTS.md](docs/EVENTS.md).
- **Fidelity matrix.** See [docs/FIDELITY.md](docs/FIDELITY.md).

## Builds

| Artifact | Command | Result |
|---|---|---|
| Debug APK | `./gradlew :android:assembleDebug` | `android-debug.apk`, 9.73 MB (budget <= 16 MB, `./gradlew :tools:apkBudget`) |
| Release APK | `./gradlew :android:assembleRelease` | `android-release.apk`, 7.54 MB (budget <= 12 MB) (needs a release keystore, see docs/BUILD.md; `-Ppkmn.release.allowDebugSigning=true` for a non-publishable debug-signed build) |
| App bundle | `./gradlew :android:bundleRelease` | `android-release.aab`, 4.94 MB (budget <= 11 MB; `assets/gfx` 2.22 MB of 5 MB) |
| Desktop jar | `./gradlew :desktop:dist` | `pkmn-crystalline-desktop.jar` |

- **APK metadata** (`aapt2 dump badging`): package `io.github.pkmncrystalline`, minSdk 24, targetSdk 35.
- **Native libraries:** for armeabi-v7a, arm64-v8a, x86 and x86_64.
- **Desugaring:** `java.time` is desugared for API 24–25.
- **Permissions:** only `VIBRATE`; no network or location.
- **Rebuilt for this update** with `./gradlew :android:assembleDebug :android:assembleRelease`, using SDK platform 35 and build-tools 35.0.0.
- **Not tested on a device.** The build machine has no KVM, so no emulator could run, and no device was attached. Android-specific behaviour has not been exercised at runtime: launcher, touch input, back button, pause/resume, the clock refresh on resume and process death.
  - It is code-reviewed.
  - Battles are never autosaved mid-fight, so a killed process resumes from the last overworld save.
  - Battle prompts use the same A/B/D-pad buttons as the touch pad.

See [docs/BUILD.md](docs/BUILD.md) for full build, test, signing and install instructions.

## Tests

`./gradlew :engine:test :core:test :tools:test`, all passing (173 + 45 + 78; 148 + 44 + 78 before badge obedience; 106 + 44 + 40 before the mechanics update). GitHub Actions (`.github/workflows/ci.yml`) runs the content gates, checks that generated assets are committed, runs the tests and builds the debug APK and release bundle on every push.

**Engine:**
- **Battle core:** damage formula against hand-computed values, immunities, abilities, items, status, hazards, protection, choice lock, experience.
- **Edge cases:** Focus Sash, Skill Link, Substitute, weather, Prankster vs Dark, priority and Trick Room, Mold Breaker, Wonder Guard, crits vs boosts, Disguise, Life Orb, Rocky Helmet, Struggle, Assault Vest and Taunt, Explosion, Perish Song, Gravity, Snatch, Sky Drop, Illusion, Mega Sol.
- **Gimmicks:** Mega, Z-Move (including signature moves), Dynamax and Gigantamax, Tera, Primal, Ultra Burst, one-gimmick rule, disabled gimmicks.
- **Fuzz:** AI-vs-AI battles with random species, moves, abilities and items. 400 per default run; 2,000 and 3,000-battle runs were also clean. Every turn checks HP and stat-stage invariants.
- **Evolution:** branch selection (Sylveon, Wurmple, regional variants), stones, Everstone, Linking Cord.
- **World:**
  - Script parser and tokenizer, including `key="quoted value"` tokens.
  - Starter and Pokédex scripts.
  - Every map loads.
  - Movement rules.
  - Wild encounter tables.
  - Trades.
  - Script results surviving message boxes.
  - Every campaign script executed on its yes and no paths.
  - Every trainer battle in the campaign built and played to completion.
  - Every inline NPC and sign text compiles as a whole sentence.
- **Saves:** round trip, backup recovery, corrupt files preserved, tampered payload rejected by checksum.
- **Sprite archive import:** extraction, path-traversal rejection.

- **Battle flow and animation (new):**
  - Engine `BattleFlowTest`: replacement before the next choice; Shift/Set; invalid picks; simultaneous faints; exactly one End and one EXP award per faint; party sizes 1–6; fleeing.
  - Core `BattleFlowTest`: the state machine driven like the screen, including indicators at the prompt.
  - `AnimationPlayerTest`: frame-rate independence, skipping, animations off, speed.
- **Time and events (new):** `TimeAndEventsTest` (11), covering boundaries, time zones, DST, Thursday-night rules, save/load, invalid config, encounter fallback and weights.

**Tools:** importer, validators and manifest checks. New: `EntranceAuditTest`, `ItemAuditTest`, `StrictReachTest`.

**Automated play on desktop.** These runs use `--script` under Xvfb, with screenshots reviewed:
- Bedroom → town → lab → starter choice → rival battle → Route 1 → wild battle.
- The start menu: Pokédex, party, bag, town map, quests, trainer card, save, options.
- Battle menus.

These runs found and fixed several real bugs:
- A crash in the actor update loop.
- An infinite recursion that crashed every scripted wild battle.
- Lost script results.
- Money lost in story battles you are allowed to lose.
- Missing font glyphs.
- Battle intro order.
- Town-map cursor position.
- Stair-warp automation.

Running the whole campaign through the new script and battle tests, and reviewing the expansion content, found more:
- All 207 inline NPC and sign texts showed only their first word (a tokenizer bug).
- Move tutors all taught one fallback list because their data file was never generated.
- A stale test assertion and a stale asset manifest, both caught by CI.

## Content and coverage

From `./gradlew :tools:validateContent`; details in [reports/COVERAGE.md](reports/COVERAGE.md).

**Validation:** 0 errors, 15 warnings. All warnings are evolutions that cannot happen in this game:
- **12 region-locked forms:** Alolan, Galarian and Hisuian evolutions, by design, since the game is set in Tessera.
- **Event-only:** Vivillon Fancy Pattern.
- **No in-game input:** Kubfu's two tower evolutions (Kubfu is not obtainable in this campaign).

**Moves (951):**
- 517 data-driven.
- 416 scripted.
- 18 that need no effect.
- 0 unimplemented.

**Abilities (318):** 299 scripted, 19 that need no battle effect, 0 unimplemented.

**Held items:** every held item is classified as implemented (371) or no battle effect (468).

**Sprites:** the repository bundles original art only. Pokémon use generated placeholder sprites, each tagged with its dex number. Official sprites can be imported locally for personal builds; see [docs/ASSET_IMPORT.md](docs/ASSET_IMPORT.md).

**Asset manifest:** `./gradlew :tools:buildAssetManifest` lists every bundled file with its licence; it reports 0 errors.

**World:** `./gradlew :tools:compileMaps` reports 223 maps, 684 trainers, 89 encounter tables, 459 scripts; 0 errors, 0 warnings. It validates every reference, tile name, sprite, shop and move-tutor list.

**Progression:** `./gradlew :tools:checkProgression` reports 0 errors and 0 warnings. It checks four things:
- Every gym can be reached with the badges earned before it. A field move counts only with its badge and an obtainable Pokémon that can learn it.
- The Hall of Fame can be reached with eight badges.
- No map is orphaned.
- No tile traps the player away from a Pokémon Center.

Maps reachable with 0..8 badges: 110, 112, 113, 150, 209, 209, 209, 223, 223.

## Performance

**Engine.** `./gradlew :tools:benchmark` writes `reports/performance.json`. Measured on the 4-core Linux build machine, JVM 21:

| Metric | Value |
|---|---|
| Species/moves data load (all learnsets) | ~0.5 s |
| World load (223 maps, 459 scripts) | ~0.14 s |
| Battle turn resolution | mean 0.22 ms, p95 0.79 ms |
| AI decision | mean 0.09 ms, p95 0.35 ms |
| Save (full party + 8 full boxes, 149 KB) | median 15 ms |
| Load | median 10 ms |
| Heap after GC | ~17 MB |

Timings vary between runs on this shared machine; an earlier run measured a 0.52 ms mean turn and 0.16 ms AI decision.

**Rendering.** Measured with the desktop `perf` automation command, while walking in town under Xvfb with software OpenGL:
- CPU time per frame: mean 2.2 ms, p95 3.5 ms, max 8.5 ms.
- Steady 60 fps.

Android devices were not measured; see Builds.

## Playtime

Updated in this release; the analysis is in [docs/PLAYTIME.md](docs/PLAYTIME.md). The table below is the earlier one, kept for comparison.

`./gradlew :tools:estimatePlaytime` writes `reports/playtime.json`. This is a model, not a measurement. It sums, along the route between gyms:
- Walking.
- Trainer battles.
- Expected wild encounters.
- Dialogue.
- Grinding, from a simulation of the party's experience.

The model's constants are listed in the report. Current result:
- Critical path: **12.7 h** (was 11.1 h; the extra time is optional grinding for players who skip all side content, now that Exp. Share is off by default).
- With optional content: **24.4 h**.
- Post-game: **4.5 h**.

| Leg | Critical (min) | With optional (min) | Party level on arrival vs boss ace |
|---|---|---|---|
| Start → Gym 1 | 50 | 135 | 18 vs 14 |
| Gym 1 → Gym 2 | 65 | 147 | 26 vs 21 |
| Gym 2 → Gym 3 | 61 | 153 | 33 vs 28 |
| Gym 3 → Gym 4 | 55 | 136 | 40 vs 34 |
| Gym 4 → Gym 5 | 61 | 136 | 47 vs 40 |
| Gym 5 → Gym 6 | 48 | 121 | 52 vs 46 |
| Gym 6 → Gym 7 | 82 | 146 | 60 vs 52 |
| Gym 7 → Gym 8 | 58 | 179 | 66 vs 58 |
| Gym 8 → Hall of Fame | 123 | 178 | 77 vs 66 |

Three expansion passes added 78 optional maps, about 357 trainers, 27 side quests, 6 in-game trades and several gifts. That took the with-optional estimate from 12.5 h to 24.4 h. It is still below the ~30 h target to the Elite Four; with the post-game the total is about 29 h.

The model is a content sum, not a measurement of real players. It assumes a typical player reads about half of the optional dialogue and fights a share of the wild encounters, and it does not count puzzle-solving time, deaths or backtracking for items.

## Known issues and limitations

- **Android is untested on hardware or an emulator.** Touch layout, audio latency, the lifecycle (pause/resume autosave, clock refresh on resume) and gamepad mapping have not been verified on a device.
- **The trainer-battle ×1.5 EXP bonus is unverified for Gen VII+.** No primary source was reachable, so the formula is unchanged (see docs/FIDELITY.md).
- **No idle animations for standing NPCs.** Gold/Silver/Crystal has none either. Event Pokémon bob.
- **Visual-regression scenes run in a separate CI job (`visual`, Xvfb)**, added in this remediation and not yet run on a GitHub runner; software-GL output could differ from the committed baselines in `docs/screenshots`. Run `tools/visual/run_scenes.sh` locally.
- **Pokémon sprites are placeholders in public builds.** Official sprites are only available through the local import path and the one-command personal APK (`./gradlew :android:assemblePersonalDebugWithSprites`; never share that APK). PokeAPI has no files for Zygarde-Mega and 379 icons, and the 4 Ogerpon Tera forms have no mapping, so they use the fallback. The styled sprites are an automated approximation, and some need hand touch-ups. Trainer, overworld and UI art is original and complete.
- **Obedience uses the Generation III/IV disobedience formula** with Scarlet/Violet's badge caps and met-level rule, because the Generation V+ formulas are undocumented. Breeding is out of scope (gift Eggs hatch at once). Wild Pokémon hold no items. See [docs/MECHANICS.md](docs/MECHANICS.md).
- **Singles only:** no double battles.
- **Playtime is below target.** About 24 h to the Hall of Fame with optional content (estimate); see the Playtime section.
- **Late-game difficulty depends on Exp. Share.** With it off (the new default) the model has exploring players 4–9 levels above gym aces. With it on, they are 16–23 levels above. Challenge difficulty still caps levels at the next leader's ace.
- **Some checks are approximate.** Ice-sliding puzzles were verified with a separate simulator during authoring, not by `checkProgression`, which treats ice as walkable. Waterfalls can be climbed but not descended, so areas above them have one-way ledges back down.
- **Missing evolution methods:** Kubfu/Urshifu (not obtainable) and Vivillon Fancy (event-only) cannot evolve in-game, and regional-variant evolutions are disabled by design. Milcery evolves by spinning on the spot while it holds a Sweet.
- **Necrozma fusion is simplified.** The engine has no N-Solarizer/N-Lunarizer fusion. The post-game "Prism Pedestal" swaps a Necrozma for a fresh level-75 Dusk Mane or Dawn Wings, so the player's own Necrozma (level, EVs) is replaced.
- **Static checks are optimistic.** The progression checker assumes story gates open once their flags are set. It proves nothing is unreachable, but it does not prove every scripted gate opens.
- **Automated play coverage is limited.** It covers the opening hour of the game, every menu, the battle flows above, and teleported checks of several expansion areas (Mistwood, Quarrytown, Eclipse Atoll, Ashfall City). The expansion areas have not been walked through end to end. Later areas are covered by the script and battle tests and by the progression checker, not by full playthroughs.
