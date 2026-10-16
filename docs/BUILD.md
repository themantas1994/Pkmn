# Build, test and install

Everything builds offline once Gradle has fetched its dependencies. The game itself never uses the network.

## Requirements

| What | Version | Notes |
|---|---|---|
| JDK | 17 or newer (developed on 21) | `JAVA_HOME` or `java` on `PATH` |
| Gradle | wrapper included (8.14.3) | always use `./gradlew` |
| Android SDK | platform 35, build-tools 35.0.0 | only for the Android build; set `sdk.dir` in `local.properties` or `ANDROID_HOME` |

Without an Android SDK the `:android` module is skipped automatically, and everything else still builds.

`settings.gradle.kts` lists Google's Maven Central mirror first, because Maven Central rate-limits CI machines.

## Modules

| Module | What it is |
|---|---|
| `engine` | Pure Kotlin game rules: data model, battle engine, overworld rules, scripting VM, saves. No LibGDX. |
| `core` | The LibGDX game: rendering, UI, screens, audio synthesizer, input. |
| `desktop` | LWJGL3 launcher for development and automation. |
| `android` | Android launcher (`io.github.pkmncrystalline`, minSdk 24, targetSdk 35). |
| `tools` | Command-line content pipeline: data import, map compiler, validators, reports, benchmarks. |

## Content pipeline

Generated content in `assets/` is committed, so a fresh clone builds without running the pipeline. Regenerate it after editing sources:

```sh
./gradlew :tools:importData          # data-src/ (Showdown + PokeAPI) -> assets/data/*.json
./gradlew :tools:compileMaps         # world/ (maps, scripts, trainers, encounters...) -> assets/maps, assets/world, assets/scripts
./gradlew :tools:validateContent     # data validation + coverage reports -> reports/*.json, reports/COVERAGE.md
./gradlew :tools:buildAssetManifest  # asset manifest + licence check -> assets/manifest
./gradlew :tools:checkProgression    # badge-order reachability / softlock check -> reports/progression.json
./gradlew :tools:estimatePlaytime    # content-derived playtime estimate -> reports/playtime.json
./gradlew :tools:benchmark           # engine performance -> reports/performance.json
```

`compileMaps`, `validateContent`, `buildAssetManifest` and `checkProgression` exit non-zero on errors, so they can gate CI.

## Tests

```sh
./gradlew :engine:test :tools:test                          # unit, battle, world, save and tool tests
./gradlew :engine:test --tests '*BattleFuzzTest*' -Dfuzz.battles=3000 --rerun   # long AI-vs-AI fuzz run
```

## Desktop

```sh
./gradlew :desktop:run                               # play (keys: arrows/WASD, Z/J/Space = A, X/K/Esc = B, Enter = Start, Tab/Right Shift = Select)
./gradlew :desktop:run --args="--portrait"           # phone-shaped window with the touch controls
./gradlew :desktop:dist                              # fat jar: desktop/build/libs/pkmn-crystalline-desktop.jar
```

Launcher options:
- `--saves=<dir>`
- `--mute`
- `--scale=N`
- `--portrait`
- `--quickstart[=NAME]`: skips the title screen.
- `--script=<file> --shots=<dir>`: automation.

An automation script is a list of commands, one per line:
- `wait N`
- `press BUTTON [frames]`
- `mash BUTTON N`
- `goto X Y`: walks there using the real movement rules.
- `log`
- `perf`: prints frame-time statistics.
- `shot NAME`
- `quit`

On a headless Linux machine, wrap the command in `xvfb-run -a -s "-screen 0 1280x1024x24"`.

## Android

```sh
./gradlew :android:assembleDebug     # android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:assembleRelease   # android/build/outputs/apk/release/android-release.apk
./gradlew :android:bundleRelease     # android/build/outputs/bundle/release/android-release.aab
```

**Release signing.** Put a keystore at `release.keystore` in the repository root (git-ignored) and export:
- `PKMN_KEYSTORE_PASSWORD`
- `PKMN_KEY_ALIAS` (default `pkmn`)
- `PKMN_KEY_PASSWORD`

Without a keystore, release builds are signed with the debug key, so they stay installable. Never publish those.

**Install** on a device with USB debugging enabled:

```sh
adb install -r android/build/outputs/apk/debug/android-debug.apk
```

Or copy the APK to the phone and open it, after allowing installs from that source. The AAB is for Play Console uploads; to test it locally, use `bundletool build-apks --connected-device`.

## Official sprites (optional, personal builds only)

The repository ships only original art plus generated placeholder Pokémon sprites. To see official sprites in your own build, import them locally as described in [ASSET_IMPORT.md](ASSET_IMPORT.md):

```sh
./gradlew :tools:importSprites -Pargs="--from=/path/to/your/sprites.zip"   # -> assets-local/ (git-ignored)
```

There are two ways to get them into the game:
- **Desktop:** reads `assets-local/` automatically.
- **Android:** use Options → IMPORT SPRITES in the game to pick the zip. For a personal APK, build with `-Ppkmn.bundleLocalAssets=true` to bake `assets-local/sprites` into it. Such an APK contains non-redistributable files and must not be shared.
