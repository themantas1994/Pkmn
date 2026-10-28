plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Tests read the bundled content from the shared assets directory.
    systemProperty("pkmn.assets", rootProject.file("assets").absolutePath)
    maxHeapSize = "2g"
    // MoveExecutionTest writes this report as a side effect; :tools:auditMoves reads it, so a build-cache hit must restore it.
    outputs.file(layout.buildDirectory.file("reports/move-execution.json"))
    System.getProperty("fuzz.battles")?.let { systemProperty("fuzz.battles", it) }
    System.getProperty("golden.update")?.let { systemProperty("golden.update", it) }
    testLogging { showStandardStreams = System.getProperty("showOutput") != null; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
