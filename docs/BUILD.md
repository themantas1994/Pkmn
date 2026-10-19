# Build, test and install

Everything builds offline once Gradle has fetched its dependencies. The game itself never uses the network.

## Requirements

| What | Version | Notes |
|---|---|---|
| JDK | 17 or newer; CI builds on 17 and also tests on 21 | `JAVA_HOME` or `java` on `PATH`. All modules compile to Java 17 bytecode (`jvmTarget`/`sourceCompatibility` 17) and AGP needs 17+, so 17 is the supported minimum; newer JDKs run the same bytecode |
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
./gradlew :tools:auditEntrances      # every warp vs its destination class -> reports/entrances.*
./gradlew :tools:auditItems          # strict item reachability per badge stage -> reports/items.*
./gradlew :tools:statusCounts        # numbers STATUS.md quotes (world counts; test counts from the last test run)
./gradlew :tools:checkStatus         # fail if STATUS.md world/test counts differ from those (CI runs it after the tests)
scripts/check-generated-assets.sh    # fail if anything under assets/ is modified, deleted or untracked (CI guard)
./gradlew :core:test -Pbenchmarks    # also run the opt-in machine-dependent timing benchmarks (skipped by default)
tools/visual/run_scenes.sh           # capture visual-regression scenes under Xvfb and compare (see docs/SCREENSHOTS.md)
```

`compileMaps`, `validateContent`, `buildAssetManifest` and `checkProgression` exit non-zero on errors, so they can gate CI.

## Tests

```sh
./gradlew :engine:test :core:test :tools:test              # unit, battle, battle-flow, animation, time/event, world, save and tool tests
./gradlew :engine:test --tests '*BattleFuzzTest*' -Dfuzz.battles=3000 --rerun   # long AI-vs-AI fuzz run
```

## Desktop

```sh
./gradlew :desktop:run                               # play (keys: arrows/WASD, Z/J/Space = A, X/K/Esc = B, Enter = Start, Tab/Right Shift = Select)
./gradlew :desktop:run --args="--portrait"           # phone-shaped window with the touch controls
./gradlew :desktop:dist                              # fat jar: desktop/build/libs/pkmn-crystalline-desktop.jar
```

Launcher options:
- `--fixedstep` (every frame advances 1/60 s; reproducible automation and screenshots)
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
./gradlew :android:assemblePersonalDebugWithSprites   # personal only: import + style sprites, then android/build/outputs/apk/personal/pkmn-crystalline-personal-debug.apk (never share it; see ASSET_IMPORT.md)
```

**Release signing.** `assembleRelease` and `bundleRelease` need a real signing key and fail with an explanatory message
otherwise (there is no silent fallback to the debug key). Debug builds are unaffected. Nothing secret is committed; configure it
through the environment or `~/.gradle/gradle.properties`:

| Setting (Gradle property / environment) | Meaning |
|---|---|
| `pkmn.release.storeFile` / `PKMN_KEYSTORE_FILE` | keystore path (default: `release.keystore` in the repository root, git-ignored) |
| `pkmn.release.storePassword` / `PKMN_KEYSTORE_PASSWORD` | keystore password |
| `pkmn.release.keyAlias` / `PKMN_KEY_ALIAS` | key alias (default `pkmn`) |
| `pkmn.release.keyPassword` / `PKMN_KEY_PASSWORD` | key password |

For a smoke test without a key (CI does this, as it has no keystore) pass `-Ppkmn.release.allowDebugSigning=true`: the artifact is
signed with the debug key, a warning is printed, and it must never be published or uploaded as a release.
Release APKs/AABs are also scanned after the build and the build fails if they contain `assets/imported/` (locally imported
official sprites or cries); the local assets are only ever wired into the debug variant.

**Backup.** `android/res/xml/backup_rules.xml` (Android 11 and older) and `data_extraction_rules.xml` (Android 12+, cloud backup and
device transfer) back up `filesDir/saves` (the save, its `.bak` and `options.json`) and nothing else. Imported official
sprites/cries (`filesDir/imported`) are not in the include list, so they never leave the device. The in-flight `save.json.tmp` is
excluded: the loader tries `.tmp`, then the main save, then `.bak` (it does not compare timestamps), so a stale restored `.tmp`
would override newer progress. Android restores a backup only onto a fresh install, so a restore cannot overwrite progress made
on that install; a restore that brings a save from a newer game version is refused by `SaveManager` rather than overwritten.
`AndroidReleaseGuardTest` parses the manifest and both XML files.

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
- **Android:** use Options → IMPORT SPRITES in the game to pick the zip. For a personal APK, run `./gradlew :android:assemblePersonalDebugWithSprites` (downloads, styles and bundles in one go; options in [ASSET_IMPORT.md](ASSET_IMPORT.md#one-command-personal-apk)), or build with `-Ppkmn.bundleLocalAssets=true` to bake an existing `assets-local/sprites/pokemon` into it. Such an APK contains non-redistributable files and must not be shared.
