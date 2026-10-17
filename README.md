# Pokémon Crystalline

An offline, Android-first Pokémon RPG in the style of Gold/Silver/Crystal. It is written in Kotlin with LibGDX, and the desktop build is used for development.

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

**Campaign:**
- Hand-authored world with GSC-style towns, routes, caves, gym puzzles, side quests, in-game trades and legendary encounters.
- Maps are written as text and compiled; the scripting language is documented in [docs/SCRIPTING.md](docs/SCRIPTING.md).

**Presentation:**
- 256×224 pixel-perfect framebuffer with an original GSC-style tileset, characters and UI.
- Built-in chiptune synthesizer playing original MML music.
- Touch controls in portrait or landscape, keyboard on desktop, and Android gamepads (D-pad, A/B, Start/Select key events).

**Saves:**
- Versioned and checksummed, written atomically (temp file, fsync, rename).
- Backup rotation, migration and repair; corrupt files are preserved, never deleted.

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
