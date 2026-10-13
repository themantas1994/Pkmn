plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":engine"))
    api("com.badlogicgames.gdx:gdx:${property("gdxVersion")}")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.badlogicgames.gdx:gdx-backend-headless:${property("gdxVersion")}")
    testImplementation("com.badlogicgames.gdx:gdx-platform:${property("gdxVersion")}:natives-desktop")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("pkmn.assets", rootProject.file("assets").absolutePath)
    workingDir = rootProject.file("assets")
}
