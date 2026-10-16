# Project status

Every number in this document was produced by the commands listed next to it. Nothing here is estimated by hand unless it says so.

## Summary

| Area | State |
|---|---|
| Builds | Desktop (LWJGL3) and Android APK/AAB build reproducibly with `./gradlew`; release builds fall back to the debug key without a keystore |
| Content data | 1025 species + 354 forms, 951 moves, 318 abilities, 839 items, imported from Showdown and PokeAPI and validated |
| Battle engine | Singles, Gen IX rules, all gimmicks; every move, ability and held item classified (0 unimplemented) |
| Campaign | Complete from new game to the Hall of Fame, plus post-game: 145 maps, 327 trainers, 46 encounter tables, 294 scripts |
| Saves | Atomic, checksummed, versioned, with backup/repair; corruption tests pass |
| Tests | 73 engine tests and 17 tool tests, all passing (about 8 s) |
| Playtime | Content-derived estimate: 9.3 h critical path, 12.5 h with optional content, plus 4.5 h post-game. Below the ~30 h target; an expansion pass is in progress |

## Builds

| Artifact | Command | Result |
|---|---|---|
| Debug APK | `./gradlew :android:assembleDebug` | `android-debug.apk`, ~6.1 MB |
| Release APK | `./gradlew :android:assembleRelease` | `android-release.apk`, ~4.7 MB (debug-key signed unless `release.keystore` is provided) |
| App bundle | `./gradlew :android:bundleRelease` | `android-release.aab`, ~4.3 MB |
| Desktop jar | `./gradlew :desktop:dist` | `pkmn-crystalline-desktop.jar` |

- **APK metadata** (`aapt2 dump badging`): package `io.github.pkmncrystalline`, minSdk 24, targetSdk 35.
- **Native libraries:** for armeabi-v7a, arm64-v8a, x86 and x86_64.
- **Not tested on a device:** the APK has not been run on hardware or an emulator. The build machine has no KVM, so no emulator could run, and no device was attached. Android-specific code (launcher, touch input, sprite import through the document picker) compiles and is packaged, but has not been exercised at runtime.

See [docs/BUILD.md](docs/BUILD.md) for full build, test, signing and install instructions.

## Tests

`./gradlew :engine:test :tools:test`, all passing.

**Engine:**
- **Battle core:** damage formula against hand-computed values, immunities, abilities, items, status, hazards, protection, choice lock, experience.
- **Edge cases:** Focus Sash, Skill Link, Substitute, weather, Prankster vs Dark, priority and Trick Room, Mold Breaker, Wonder Guard, crits vs boosts, Disguise, Life Orb, Rocky Helmet, Struggle, Assault Vest and Taunt, Explosion, Perish Song, Gravity, Snatch, Sky Drop, Illusion, Mega Sol.
- **Gimmicks:** Mega, Z-Move (including signature moves), Dynamax and Gigantamax, Tera, Primal, Ultra Burst, one-gimmick rule, disabled gimmicks.
- **Fuzz:** AI-vs-AI battles with random species, moves, abilities and items. 400 per default run; 2,000 and 3,000-battle runs were also clean. Every turn checks HP and stat-stage invariants.
- **Evolution:** branch selection (Sylveon, Wurmple, regional variants), stones, Everstone, Linking Cord.
- **World:**
  - Script parser.
  - Starter and Pokédex scripts.
  - Every map loads.
  - Movement rules.
  - Wild encounter tables.
  - Trades.
  - Script results surviving message boxes.
  - Every campaign script executed on its yes and no paths.
  - Every trainer battle in the campaign built and played to completion.
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

## Content and coverage

From `./gradlew :tools:validateContent`; details in [reports/COVERAGE.md](reports/COVERAGE.md).

**Validation:** 0 errors, 16 warnings. All warnings are evolutions that cannot happen in this game:
- **12 region-locked forms:** Alolan, Galarian and Hisuian evolutions, by design, since the game is set in Tessera.
- **Event-only:** Vivillon Fancy Pattern.
- **No in-game input:** Alcremie (spinning) and Kubfu's two tower evolutions.

**Moves (951):**
- 517 data-driven.
- 416 scripted.
- 18 that need no effect.
- 0 unimplemented.

**Abilities (318):** 299 scripted, 19 that need no battle effect, 0 unimplemented.

**Held items:** every held item is classified as implemented (371) or no battle effect (468).

**Sprites:** the repository bundles original art only. Pokémon use generated placeholder sprites, each tagged with its dex number. Official sprites can be imported locally for personal builds; see [docs/ASSET_IMPORT.md](docs/ASSET_IMPORT.md).

**Asset manifest:** `./gradlew :tools:buildAssetManifest` lists every bundled file with its licence; it reports 0 errors.

**World:** `./gradlew :tools:compileMaps` reports 145 maps, 327 trainers, 46 encounter tables, 294 scripts; 0 errors, 0 warnings. It validates every reference, tile name and sprite.

**Progression:** `./gradlew :tools:checkProgression` reports 0 errors and 0 warnings. It checks four things:
- Every gym can be reached with the badges earned before it. A field move counts only with its badge and an obtainable Pokémon that can learn it.
- The Hall of Fame can be reached with eight badges.
- No map is orphaned.
- No tile traps the player away from a Pokémon Center.

## Performance

**Engine.** `./gradlew :tools:benchmark` writes `reports/performance.json`. Measured on the 4-core Linux build machine, JVM 21:

| Metric | Value |
|---|---|
| Species/moves data load (all learnsets) | ~0.7 s |
| World load (145 maps, 294 scripts) | ~0.2 s |
| Battle turn resolution | mean 0.52 ms, p95 1.6 ms |
| AI decision | mean 0.16 ms, p95 0.46 ms |
| Save (full party + 8 full boxes, 149 KB) | median 17 ms |
| Load | median 10 ms |
| Heap after GC | ~16 MB |

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
- Critical path: **9.3 h**.
- With optional content: **12.5 h**.
- Post-game: **4.5 h**.

That is below the ~30 h target. Three expansion passes, one per third of the game, are adding optional areas, trainers and side quests. Their results will update this section.

## Known issues and limitations

- **Android is untested on hardware or an emulator.** Touch layout, audio latency, the lifecycle (pause/resume autosave) and gamepad mapping have not been verified on a device.
- **Pokémon sprites are placeholders.** Official sprites are only available through the local import path. Trainer, overworld and UI art is original and complete.
- **Singles only:** no double battles.
- **Playtime is below target.** See the Playtime section.
- **Missing evolution methods:** Alcremie, Kubfu/Urshifu and Vivillon Fancy cannot evolve in-game, and regional-variant evolutions are disabled by design.
- **Necrozma fusion is simplified.** The engine has no N-Solarizer/N-Lunarizer fusion. The post-game "Prism Pedestal" swaps a Necrozma for a fresh level-75 Dusk Mane or Dawn Wings, so the player's own Necrozma (level, EVs) is replaced.
- **Static checks are optimistic.** The progression checker assumes story gates open once their flags are set. It proves nothing is unreachable, but it does not prove every scripted gate opens.
- **Automated play coverage is limited.** It covers the opening hour of the game and every menu. Later areas are covered by the script and battle tests and by the progression checker, not by full playthroughs.
