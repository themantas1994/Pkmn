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
    this.args = args.toList()
    maxHeapSize = "2g"
}

registerTool("importData", "Convert data-src/ raw datasets into assets/data/*.json", "import")
registerTool("compileMaps", "Compile maps/*.map sources into assets/maps/*.json", "maps")
registerTool("validateContent", "Validate all content and write reports/*.json", "validate")
registerTool("buildAssetManifest", "Scan assets and write the asset manifest", "manifest")
registerTool("estimatePlaytime", "Estimate campaign playtime from content", "playtime")

tasks.test {
    useJUnitPlatform()
    systemProperty("pkmn.assets", rootProject.file("assets").absolutePath)
    workingDir = rootProject.projectDir
}
