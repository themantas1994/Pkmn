# Repo map (read this instead of scanning the tree)

| Path | What lives there |
|---|---|
| `engine/src/main/kotlin/pkmn/engine/battle` | battle rules, moves, abilities, AI (largest module; mechanics detail in `docs/MECHANICS.md`) |
| `engine/.../{campaign,data,mon,save,util,world}` | campaign/progress model, data loading, mon model, save format, helpers, world model |
| `core/src/main/kotlin/pkmn/game` | game layer: `screens`, `overworld`, `battle` (UI), `gfx`, `audio`, `input`, `ui`, `anim` |
| `tools/src/main/kotlin/pkmn/tools` | content pipeline and audits; entry `ToolsMain.kt`, tasks registered in `tools/build.gradle.kts` |
| `desktop`, `android` | thin LWJGL3 / Android launchers (1 Kotlin file each) |
| `data-src/` | raw datasets (pokeapi, showdown) -> `:tools:importData` |
| `world/` | map/encounter/campaign sources (`maps`, `prefabs`, `encounters`, `campaign.json`) -> `:tools:compileMaps` |
| `assets/` | GENERATED, never edit; regenerate with the content gates |
| `reports/` | audit outputs written by the tools tasks; `STATUS.md` numbers come from here |
| `art/` | Python pixel-art generators for built-in sprites/tiles |
| `docs/` | formats and mechanics: `WORLD_FORMAT`, `SCRIPTING`, `EVENTS`, `MECHANICS`, `BUILD`, `CAPTURE`, `LEARNSETS` |
| `tools/visual/run_scenes.sh` | desktop screenshot scenes compared with `docs/screenshots` (CI `visual` job) |

Task cheat sheet: `scripts/gates.sh [content|test|android|all]` runs the CI gates, logs to
`build/gates/*.log` and prints one line per step. Other audits: `:tools:auditEntrances`,
`:tools:auditItems`, `:tools:auditLearnsets`, `:tools:auditMoves`, `:tools:checkStatus`,
`:tools:statusCounts`.
