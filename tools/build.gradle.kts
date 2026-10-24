plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":engine"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("pkmn.tools.ToolsMainKt") }

fun registerTool(name: String, desc: String, vararg args: String) = tasks.register<JavaExec>(name) {
    group = "pkmn"
    description = desc
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pkmn.tools.ToolsMainKt")
    workingDir = rootProject.projectDir
    // Extra arguments: ./gradlew :tools:importSprites -Pargs="--only=1-151 --force"
    this.args = args.toList() + ((project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
    maxHeapSize = "2g"
    (project.findProperty("auditDebug") as String?)?.let { systemProperty("audit.debug", it) }
    (project.findProperty("auditMap") as String?)?.let { systemProperty("audit.map", it) }
}

registerTool("importData", "Convert data-src/ raw datasets into assets/data/*.json", "import")
registerTool("compileMaps", "Compile maps/*.map sources into assets/maps/*.json", "maps")
registerTool("validateContent", "Validate all content and write reports/*.json", "validate")
registerTool("buildAssetManifest", "Scan assets and write the asset manifest", "manifest")
tasks.named("buildAssetManifest") { mustRunAfter("buildArt") } // the manifest hashes the generated art
registerTool("checkProgression", "Check badge-order reachability, softlocks and orphaned content", "progression")
registerTool("auditEntrances", "Audit every warp: entrance tile vs destination class, traversal, reverse transition -> reports/entrances.*", "entrances")
registerTool("auditItems", "Strict item reachability audit per badge stage and placement defects -> reports/items.*", "items")
registerTool("auditLearnsets", "Learnset legality audit (importer vs raw Showdown, forms, world moves, TMs, tutors) -> reports/learnsets.*", "learnsets")
registerTool("auditMoves", "Merge engine/build/reports/move-execution.json (run :engine:test --tests '*MoveExecutionTest*' first) with BattleCoverage -> reports/moves-audit.*", "moves")
registerTool("benchmark", "Measure engine performance (loading, battle turns, AI, save/load)", "perf")
registerTool("statusCounts", "Print the numbers STATUS.md quotes (world counts, test counts from the last test run)", "status")
registerTool("checkStatus", "Fail when STATUS.md world/test counts differ from the build outputs (run after :engine:test :core:test :tools:test)", "status", "--check")
registerTool("apkBudget", "Fail when a built release APK (<=12 MB), debug APK (<=16 MB), release AAB (<=11 MB) or assets/gfx (<=5 MB) exceeds its budget; -Pargs=--require also fails when an artifact was not built", "apk-budget")
registerTool("estimatePlaytime", "Estimate campaign playtime from content", "playtime")
registerTool("spriteCoverage", "Species used by encounters, trainers, scripts, gifts and events per art tier -> reports/sprite-coverage.json", "sprite-coverage")
registerTool("auditCapture", "Capture chance of every wild encounter slot before/after the capture fix -> reports/capture.*", "capture")
registerTool("importSprites", "Import Pokémon sprites into git-ignored assets-local/ for personal builds (-Pargs=\"--only=1-151\")", "sprites")
registerTool("importCries", "Download official cries (PokeAPI/cries) into git-ignored assets-local/audio/cries for personal builds (-Pargs=\"--only=1-151\")", "cries")
// Same, scoped and tolerant like the personal sprite pipeline; used by :android:assemblePersonalDebugWithSprites.
val importCriesForApk = registerTool("importCriesForApk", "Cries for the personal APK (honours -Ppkmn.sprites.only and -Ppkmn.sprites.offline)", *listOfNotNull("cries", "--tolerate-failures",
    (findProperty("pkmn.sprites.only") as String?)?.trim()?.takeIf { it.isNotEmpty() }?.let { "--only=$it" },
    if ((findProperty("pkmn.sprites.offline") as String?)?.toBoolean() == true) "--offline" else null).toTypedArray())

// Personal APK sprite pipeline (docs/ASSET_IMPORT.md). Options come from -P properties so one command drives it all:
//   pkmn.sprites.only=1-151  pkmn.sprites.style=gbc|none  pkmn.sprites.from=<dir|zip>  pkmn.sprites.offline=true
fun spriteProp(name: String) = (findProperty("pkmn.sprites.$name") as String?)?.trim()?.takeIf { it.isNotEmpty() }
val spriteOffline = spriteProp("offline")?.toBoolean() == true
val spriteImportArgs = listOfNotNull("sprites", "--dest=assets-local/sprites-raw/pokemon", "--tolerate-failures", spriteProp("only")?.let { "--only=$it" },
    spriteProp("from")?.let { "--from=$it" }, if (spriteOffline) "--offline" else null)
val spriteStyleArgs = listOfNotNull("sprites-style", "--style=${spriteProp("style") ?: "gbc"}", spriteProp("only")?.let { "--only=$it" })
val importSpritesRaw = registerTool("importSpritesRaw", "Download/import Pokémon sprites into the raw cache assets-local/sprites-raw (never modified afterwards, never bundled)", *spriteImportArgs.toTypedArray())
val styleSprites = registerTool("styleSprites", "Raw cache -> styled live set assets-local/sprites (-Ppkmn.sprites.style=gbc|none), atomic swap, STYLE_REPORT.json + COVERAGE.json", *spriteStyleArgs.toTypedArray())
styleSprites { mustRunAfter(importSpritesRaw) }

// Art generators (art/*.py): run in a venv built from the pinned art/requirements.txt so the PNG bytes are reproducible.
// Override the interpreter with -Ppkmn.art.python=/path/to/python (it must already have the pinned packages). Linux/macOS.
val artVenvDir = layout.buildDirectory.dir("art-venv")
val artVenv = tasks.register<Exec>("artVenv") {
    group = "pkmn"
    description = "Create build/art-venv with the packages pinned in art/requirements.txt"
    val requirements = rootProject.file("art/requirements.txt")
    inputs.file(requirements)
    outputs.dir(artVenvDir)
    commandLine("sh", "-c", "rm -rf \"\$0\" && python3 -m venv \"\$0\" && \"\$0/bin/python\" -m pip install -q --disable-pip-version-check -r \"\$1\"",
        artVenvDir.get().asFile.path, requirements.path)
}
val artPython: Provider<String> = provider {
    (findProperty("pkmn.art.python") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: artVenvDir.get().file("bin/python").asFile.path
}
tasks.register<Exec>("buildArt") {
    group = "pkmn"
    description = "Regenerate assets/gfx and assets/fonts from art/*.py (deterministic; a second run must leave git status clean)"
    if (findProperty("pkmn.art.python") == null) dependsOn(artVenv)
    workingDir = rootProject.projectDir
    inputs.dir(rootProject.file("art")).withPropertyName("artSources")
    outputs.upToDateWhen { false }
    doFirst { commandLine(artPython.get(), "-I", "art/build_all.py", "--no-previews") }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("pkmn.assets", rootProject.file("assets").absolutePath)
    systemProperty("pkmn.art.python", artPython.get())
    workingDir = rootProject.projectDir
    // AssetOverrideRegressionTest reads these sources; without declaring them a change would leave the test UP-TO-DATE.
    inputs.files(rootProject.files(
        "core/src/main/kotlin/pkmn/game/gfx/Assets.kt", "core/src/main/kotlin/pkmn/game/PkmnGame.kt", "core/src/main/kotlin/pkmn/game/audio/CryBank.kt",
        "desktop/src/main/kotlin/pkmn/desktop/DesktopLauncher.kt", "android/build.gradle.kts", ".gitignore",
    )).withPropertyName("overrideContractSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
