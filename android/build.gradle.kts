plugins {
    id("com.android.application")
    kotlin("android")
}

val gdxVersion: String by project
val natives: Configuration by configurations.creating

android {
    namespace = "pkmn.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pkmncrystalline"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.srcDirs("src/main/kotlin")
        res.srcDirs("res")
        assets.srcDirs(rootProject.file("assets"))
        jniLibs.srcDirs("libs")
    }

    signingConfigs {
        // Release signing reads a local keystore that is never committed.
        // Without one the release build falls back to the debug key so it stays installable.
        val ks = rootProject.file("release.keystore")
        if (ks.exists()) create("release") {
            storeFile = ks
            storePassword = System.getenv("PKMN_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("PKMN_KEY_ALIAS") ?: "pkmn"
            keyPassword = System.getenv("PKMN_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    for (abi in listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-$abi")
}

// Unpack LibGDX native libraries into libs/<abi>/ before packaging.
val copyAndroidNatives by tasks.registering {
    doFirst {
        natives.files.forEach { jar ->
            val abi = Regex("natives-([^.]+)\\.jar").find(jar.name)?.groupValues?.get(1) ?: return@forEach
            val outDir = file("libs/$abi").apply { mkdirs() }
            copy {
                from(zipTree(jar))
                into(outDir)
                include("*.so")
            }
        }
    }
}
tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach { dependsOn(copyAndroidNatives) }
