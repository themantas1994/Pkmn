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
registerTool("checkProgression", "Check badge-order reachability, softlocks and orphaned content", "progression")
registerTool("auditEntrances", "Audit every warp: entrance tile vs destination class, traversal, reverse transition -> reports/entrances.*", "entrances")
registerTool("auditItems", "Strict item reachability audit per badge stage and placement defects -> reports/items.*", "items")
registerTool("auditLearnsets", "Learnset legality audit (importer vs raw Showdown, forms, world moves, TMs, tutors) -> reports/learnsets.*", "learnsets")
registerTool("auditMoves", "Merge engine/build/reports/move-execution.json (run :engine:test --tests '*MoveExecutionTest*' first) with BattleCoverage -> reports/moves-audit.*", "moves")
registerTool("benchmark", "Measure engine performance (loading, battle turns, AI, save/load)", "perf")
registerTool("estimatePlaytime", "Estimate campaign playtime from content", "playtime")
registerTool("importSprites", "Import Pokémon sprites into git-ignored assets-local/ for personal builds (-Pargs=\"--only=1-151\")", "sprites")

tasks.test {
    useJUnitPlatform()
    systemProperty("pkmn.assets", rootProject.file("assets").absolutePath)
    workingDir = rootProject.projectDir
}
