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
}

registerTool("importData", "Convert data-src/ raw datasets into assets/data/*.json", "import")
registerTool("compileMaps", "Compile maps/*.map sources into assets/maps/*.json", "maps")
registerTool("validateContent", "Validate all content and write reports/*.json", "validate")
registerTool("buildAssetManifest", "Scan assets and write the asset manifest", "manifest")
registerTool("checkProgression", "Check badge-order reachability, softlocks and orphaned content", "progression")
registerTool("benchmark", "Measure engine performance (loading, battle turns, AI, save/load)", "perf")
registerTool("estimatePlaytime", "Estimate campaign playtime from content", "playtime")
registerTool("importSprites", "Import Pokémon sprites into git-ignored assets-local/ for personal builds (-Pargs=\"--only=1-151\")", "sprites")

tasks.test {
    useJUnitPlatform()
    systemProperty("pkmn.assets", rootProject.file("assets").absolutePath)
    workingDir = rootProject.projectDir
}
