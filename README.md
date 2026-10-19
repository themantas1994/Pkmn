# Pokémon Crystalline: an offline Gen 2-style Pokémon fan game for Android and desktop

**Pokémon Crystalline** is a free, open-source, offline Pokémon fan RPG in the style of Pokémon Gold, Silver and Crystal, with all 1025 Pokémon, modern Gen IX battle rules and a complete original campaign. It is written in Kotlin with LibGDX, runs on **Android** (APK) and **desktop** (LWJGL3), and needs no internet connection to play.

Keywords: Pokémon fan game, Pokémon Android game, offline Pokémon RPG, Gen 2 Pokémon engine, Kotlin LibGDX game, Pokémon battle engine, open-source Pokémon, Mega Evolution, Z-Moves, Dynamax, Terastallization, no HM slots.

It is an original adventure in the region of **Tessera**:
- 8 gyms, a rival and the villain team Eclipse.
- An Elite Four and Champion, followed by a post-game on the Coral Isles.
- Battles use the modern (Gen IX) rules, including every battle gimmick: Mega Evolution, Primal Reversion, Z-Moves, Ultra Burst, Dynamax/Gigantamax and Terastallization.

> Fan project. Pokémon is © Nintendo / Creatures / GAME FREAK. This repository contains no ROMs and no official art or audio. Game data comes from open datasets (see [assets/ATTRIBUTION.json](assets/ATTRIBUTION.json)). All bundled graphics, music and maps are original.

## Highlights

**Full national roster: #1–#1025** (through Scarlet/Violet's Indigo Disk), including:
- 354 forms: regional, Mega (including Legends Z-A), Gigantamax, Primal, fusion and battle-only.
- 951 moves, 318 abilities and 839 items.
- Data is imported from Pokémon Showdown (MIT) and PokeAPI (BSD-3), validated, and summarised in coverage reports.

**Battle engine** modelled on Showdown's event architecture:
- Accurate damage formula, abilities, items, field effects and AI.
- Every move, ability and held item is classified as implemented, data-driven or no effect needed (see [reports/COVERAGE.md](reports/COVERAGE.md)).
- Deterministic, seeded battles, fuzz-tested over thousands of AI-vs-AI battles.

**No HM slots: God Bidoof:**
- An invisible, permanent helper called **God Bidoof** performs every unlocked field move, so no party slot is spent on HMs and no suitable Pokémon is needed.
- Cut, Flash, Rock Smash, Strength, Surf, Fly and Waterfall each unlock independently with their gym badge (1, 2, 2, 3, 4, 5 and 7); the game has no HM items to find.
- When you use one, the game says, for example, `God Bidoof answered your prayers and used CUT!`. Bidoof never joins your party or PC, and TMs are unaffected.
- Details: [docs/CAMPAIGN.md](docs/CAMPAIGN.md#field-moves).

**Campaign:**
- Hand-authored world with GSC-style towns, routes, caves, gym puzzles, side quests, in-game trades and legendary encounters.
- Maps are written as text and compiled; the scripting language is documented in [docs/SCRIPTING.md](docs/SCRIPTING.md).

**Presentation:**
- 256×224 pixel-perfect framebuffer with an original GSC-style tileset, characters and UI.
- Built-in chiptune synthesizer playing original MML music.
- Eight themed gyms, each with its own tiles and original music.
- Touch controls in portrait or landscape, keyboard on desktop, and Android gamepads (D-pad, A/B, Start/Select key events).

**Difficulty and fairness:**
- The whiteout penalty scales with level and badges, and trainers can spot you anywhere on screen.
- Wild battles support RUN (fail, retry, escape); RUN is rejected in trainer battles.

**Saves:**
- Versioned and checksummed, written atomically (temp file, fsync, rename).
- Backup rotation, migration and repair; corrupt files are preserved, never deleted, and never replace a good backup.
- An interrupted save recovers the newest complete copy; a save from a newer game version is left untouched.

**Tooling:**
- Map compiler with reference and tile validation.
- Progression/softlock checker and a content-derived playtime estimator.
- Asset licence manifest and an engine benchmark.

## Quick start

```sh
./gradlew :desktop:run                 # play on desktop
./gradlew :engine:test :tools:test     # run the tests
./gradlew :android:assembleDebug       # build the APK (needs an Android SDK)
```

Full instructions, including Android signing and installation, are in [docs/BUILD.md](docs/BUILD.md).

### Official sprites and cries (personal builds only)

The repository ships original art and generated placeholder Pokémon sprites. To see official sprites and hear official cries in a build **for your own device**, import them into the git-ignored `assets-local/` folder (see [docs/ASSET_IMPORT.md](docs/ASSET_IMPORT.md)) and bake them into the APK:

```sh
./gradlew :android:assembleDebug -Ppkmn.bundleLocalAssets=true         # bundles existing assets-local/ sprites and cries
./gradlew :android:assemblePersonalDebugWithSprites                    # download, style and bundle in one command
```

Such an APK contains non-redistributable files: install it yourself and never share or publish it.

## Contributing

Run the content gates and tests before opening a pull request (the same checks as CI):

```sh
./gradlew :tools:compileMaps :tools:checkProgression :tools:validateContent :tools:buildAssetManifest
git diff --exit-code -- assets/        # assets/ is generated; change data-src/ or world/ and regenerate
./gradlew :engine:test :core:test :tools:test
```

## Documentation

| Document | Contents |
|---|---|
| [STATUS.md](STATUS.md) | What works, measured test, coverage and performance results, playtime estimate, known issues |
| [docs/BUILD.md](docs/BUILD.md) | Build, test, install, content pipeline |
| [docs/CAMPAIGN.md](docs/CAMPAIGN.md) | Story, cast, gyms, map plan, per-part design notes |
| [docs/WORLD_FORMAT.md](docs/WORLD_FORMAT.md) / [docs/SCRIPTING.md](docs/SCRIPTING.md) | Map and script source formats |
| [docs/ASSET_IMPORT.md](docs/ASSET_IMPORT.md) | Using your own official sprites in a personal build |
| [docs/ART_STYLE.md](docs/ART_STYLE.md) / [docs/AUDIO.md](docs/AUDIO.md) | Art and audio guidelines |
| [reports/COVERAGE.md](reports/COVERAGE.md) | Generated content and mechanics coverage |
| [docs/FIDELITY.md](docs/FIDELITY.md) | Gameplay fidelity matrix against the official games (sourced), deliberate differences |
| [docs/EVENTS.md](docs/EVENTS.md) | Device clock, day/night, time-dependent encounters, weekly events and their test results |
| [docs/WORLD_VALIDATION.md](docs/WORLD_VALIDATION.md) | Entrance and item reachability audits ([reports/entrances.md](reports/entrances.md), [reports/items.md](reports/items.md)) |
| [docs/PLAYTIME.md](docs/PLAYTIME.md) | Playtime model, difficulty findings and the Exp. Share default |
| [docs/SCREENSHOTS.md](docs/SCREENSHOTS.md) | Reproducible visual-regression scenes in the real game |
