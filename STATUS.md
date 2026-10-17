# Project status

Every number in this document was produced by the commands listed next to it. Nothing here is estimated by hand unless it says so.

## Summary

| Area | State |
|---|---|
| Builds | Desktop (LWJGL3) and Android APK/AAB build reproducibly with `./gradlew`; release builds fall back to the debug key without a keystore |
| Content data | 1025 species + 354 forms, 951 moves, 318 abilities, 839 items, imported from Showdown and PokeAPI and validated |
| Battle engine | Singles, Gen IX rules, all gimmicks; every move, ability and held item classified (0 unimplemented) |
| Campaign | Complete from new game to the Hall of Fame, plus post-game: 223 maps, 684 trainers, 89 encounter tables, 451 scripts |
| Saves | Atomic, checksummed, versioned, with backup/repair; corruption tests pass |
| Tests | 75 engine tests and 17 tool tests, all passing; CI runs them on every push |
| Playtime | Content-derived estimate: 11.1 h critical path, 24.4 h with optional content, plus 4.5 h post-game. Below the ~30 h-to-the-E4 target |

## Builds

| Artifact | Command | Result |
|---|---|---|
| Debug APK | `./gradlew :android:assembleDebug` | `android-debug.apk`, ~6.4 MB |
| Release APK | `./gradlew :android:assembleRelease` | `android-release.apk`, ~5.0 MB (debug-key signed unless `release.keystore` is provided) |
| App bundle | `./gradlew :android:bundleRelease` | `android-release.aab`, ~4.6 MB |
| Desktop jar | `./gradlew :desktop:dist` | `pkmn-crystalline-desktop.jar` |

- **APK metadata** (`aapt2 dump badging`): package `io.github.pkmncrystalline`, minSdk 24, targetSdk 35.
- **Native libraries:** for armeabi-v7a, arm64-v8a, x86 and x86_64.
- **Not tested on a device:** the APK has not been run on hardware or an emulator. The build machine has no KVM, so no emulator could run, and no device was attached. Android-specific code (launcher, touch input, sprite import through the document picker) compiles and is packaged, but has not been exercised at runtime.

See [docs/BUILD.md](docs/BUILD.md) for full build, test, signing and install instructions.

## Tests

`./gradlew :engine:test :tools:test`, all passing. GitHub Actions (`.github/workflows/ci.yml`) runs the content gates, checks that generated assets are committed, runs the tests and builds the debug APK and release bundle on every push.

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

**Tools:** importer, validators and manifest checks.

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

**World:** `./gradlew :tools:compileMaps` reports 223 maps, 684 trainers, 89 encounter tables, 451 scripts; 0 errors, 0 warnings. It validates every reference, tile name, sprite, shop and move-tutor list.

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
| World load (223 maps, 451 scripts) | ~0.14 s |
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

`./gradlew :tools:estimatePlaytime` writes `reports/playtime.json`. This is a model, not a measurement. It sums, along the route between gyms:
- Walking.
- Trainer battles.
- Expected wild encounters.
- Dialogue.
- Grinding, from a simulation of the party's experience.

The model's constants are listed in the report. Current result:
- Critical path: **11.1 h**.
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

- **Android is untested on hardware or an emulator.** Touch layout, audio latency, the lifecycle (pause/resume autosave) and gamepad mapping have not been verified on a device.
- **Pokémon sprites are placeholders.** Official sprites are only available through the local import path. Trainer, overworld and UI art is original and complete.
- **Singles only:** no double battles.
- **Playtime is below target.** About 24 h to the Hall of Fame with optional content (estimate); see the Playtime section.
- **Normal difficulty is easy late in the game.** With Exp. Share on and no level cap on Normal, the model has the party about 9–11 levels above the late gym and Champion aces. Challenge difficulty caps levels at the next leader's ace. Raising the late bosses or defaulting Exp. Share off would fix this; neither has been done.
- **Some checks are approximate.** Ice-sliding puzzles were verified with a separate simulator during authoring, not by `checkProgression`, which treats ice as walkable. Waterfalls can be climbed but not descended, so areas above them have one-way ledges back down.
- **Missing evolution methods:** Kubfu/Urshifu (not obtainable) and Vivillon Fancy (event-only) cannot evolve in-game, and regional-variant evolutions are disabled by design. Milcery evolves by spinning on the spot while it holds a Sweet.
- **Necrozma fusion is simplified.** The engine has no N-Solarizer/N-Lunarizer fusion. The post-game "Prism Pedestal" swaps a Necrozma for a fresh level-75 Dusk Mane or Dawn Wings, so the player's own Necrozma (level, EVs) is replaced.
- **Static checks are optimistic.** The progression checker assumes story gates open once their flags are set. It proves nothing is unreachable, but it does not prove every scripted gate opens.
- **Automated play coverage is limited.** It covers the opening hour of the game and every menu; none of the expansion areas have been walked through. Later areas are covered by the script and battle tests and by the progression checker, not by full playthroughs.
