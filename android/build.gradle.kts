import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    kotlin("android")
}

val gdxVersion: String by project
val natives: Configuration by configurations.creating

// Personal builds only: -Ppkmn.bundleLocalAssets=true copies assets-local/sprites into the APK as assets/imported/.
// Such an APK contains non-redistributable files and must never be published (docs/ASSET_IMPORT.md).
// The one-command pipeline (:android:assemblePersonalDebugWithSprites) switches it on by itself, only for that run;
// assembleDebug, assembleRelease and bundleRelease never bundle local sprites unless the property is set explicitly.
val personalSpritesRequested = gradle.startParameter.taskNames.any { it.contains("PersonalDebugWithSprites") }
val bundleLocalAssets = personalSpritesRequested || (findProperty("pkmn.bundleLocalAssets") as String?)?.toBoolean() == true
val localAssetsDir = layout.buildDirectory.dir("generated/localAssets")

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
        if (bundleLocalAssets) assets.srcDir(localAssetsDir)
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
        // java.time (game clock, saves) needs API 26; desugaring makes it work on minSdk 24-25 devices too.
        isCoreLibraryDesugaringEnabled = true
    }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
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

// Only the live sets (styled assets-local/sprites/pokemon and assets-local/audio/cries) are bundled. The raw cache (assets-local/sprites-raw) and the
// scratch directory (assets-local/sprites-styled.tmp) live outside it, and reports/logs in sprites/ are excluded.
val copyLocalAssets by tasks.registering(Sync::class) {
    from(rootProject.file("assets-local/sprites")) { include("pokemon/**"); into("sprites") }
    // Official cries (personal builds only): the live set assets-local/audio/cries/<speciesId>.ogg. The raw cache
    // (assets-local/audio/cries-raw) and the JSON logs are never bundled.
    from(rootProject.file("assets-local/audio/cries")) { include("*.ogg", "*.mp3"); into("audio/cries") }
    into(localAssetsDir.map { it.dir("imported") })
    doFirst {
        if (!rootProject.file("assets-local/sprites").isDirectory) throw GradleException("pkmn.bundleLocalAssets: assets-local/sprites does not exist")
        logger.warn("pkmn.bundleLocalAssets=true: this APK contains locally imported, non-redistributable sprites. Do not publish it.")
    }
}
if (bundleLocalAssets) tasks.matching { it.name.contains("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyLocalAssets) }

// ---------------------------------------------------------------------------------------------------------------------
// One command: ./gradlew :android:assemblePersonalDebugWithSprites
//   import (raw cache) -> style + validate + coverage (live set) -> debug APK with the live sprites bundled.
// Options: -Ppkmn.sprites.style=gbc|none  -Ppkmn.sprites.only=1-151  -Ppkmn.sprites.from=<dir|zip>  -Ppkmn.sprites.offline=true
// The APK is for the person who built it: it contains non-redistributable artwork and must never be shared or published.
// ---------------------------------------------------------------------------------------------------------------------
val personalApkDir = layout.buildDirectory.dir("outputs/apk/personal")
val personalApkName = "pkmn-crystalline-personal-debug.apk"

val checkPersonalSpritePrereqs by tasks.registering {
    doLast {
        val species = rootProject.file("assets/data/species.json")
        if (!species.isFile) throw GradleException("assets/data/species.json is missing (${species.path}). Generate it first: ./gradlew :tools:importData")
        val style = (findProperty("pkmn.sprites.style") as String?)?.trim().orEmpty().ifEmpty { "gbc" }
        if (style != "gbc" && style != "none") throw GradleException("-Ppkmn.sprites.style must be gbc or none, not '$style'")
        (findProperty("pkmn.sprites.only") as String?)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!Regex("\\d+(-\\d+)?(,\\d+(-\\d+)?)*").matches(it)) throw GradleException("-Ppkmn.sprites.only must look like 1-151 or 1-151,252-386, not '$it'")
        }
        (findProperty("pkmn.sprites.from") as String?)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val f = rootProject.file(it)
            if (!f.exists()) throw GradleException("-Ppkmn.sprites.from=$it does not exist (relative paths are resolved from the repository root)")
        }
    }
}
gradle.projectsEvaluated {
    // Fail on bad options before any download starts.
    project(":tools").tasks.matching { it.name == "importSpritesRaw" || it.name == "styleSprites" }.configureEach { mustRunAfter(checkPersonalSpritePrereqs) }
}
// Cries are on by default for the personal build; -Ppkmn.cries=false skips them (sprites only).
val personalCries = (findProperty("pkmn.cries") as String?)?.toBoolean() != false
if (personalSpritesRequested) copyLocalAssets.configure { dependsOn(":tools:styleSprites"); if (personalCries) dependsOn(":tools:importCriesForApk") }

tasks.register("assemblePersonalDebugWithSprites") {
    group = "pkmn"
    description = "Personal debug APK with official sprites: import (raw cache) -> style -> validate/coverage -> assembleDebug. Never publish the result."
    dependsOn(checkPersonalSpritePrereqs, ":tools:importSpritesRaw", ":tools:styleSprites", "assembleDebug")
    if (personalCries) dependsOn(":tools:importCriesForApk")
    doLast {
        if (!bundleLocalAssets) throw GradleException("Local sprites were not bundled. Run the task by its full name " +
            "(:android:assemblePersonalDebugWithSprites) or add -Ppkmn.bundleLocalAssets=true.")
        val debugDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val built = debugDir.listFiles { f -> f.extension == "apk" }?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("assembleDebug produced no APK in $debugDir")
        val bundled = ZipFile(built).use { z ->
            z.entries().asSequence().map { it.name }.filter { it.startsWith("assets/imported/sprites/pokemon/") && it.endsWith(".png") }.toList()
        }
        if (bundled.isEmpty()) throw GradleException("The APK ${built.path} contains no assets/imported/sprites/pokemon/*.png: refusing to call it a personal sprite build.")
        val cries = ZipFile(built).use { z -> z.entries().asSequence().count { it.name.startsWith("assets/imported/audio/cries/") && (it.name.endsWith(".ogg") || it.name.endsWith(".mp3")) } }
        if (personalCries && cries == 0) throw GradleException("The APK ${built.path} contains no assets/imported/audio/cries/*.ogg|mp3. Run :tools:importCries, or pass -Ppkmn.cries=false for a build without cries.")
        val out = personalApkDir.get().asFile.also { it.mkdirs() }.resolve(personalApkName)
        built.copyTo(out, overwrite = true)

        println()
        println("Personal APK with ${bundled.size} bundled sprite files and $cries cry files:")
        println("  ${out.absolutePath}")
        val coverage = rootProject.file("assets-local/sprites/COVERAGE.json")
        if (coverage.isFile) {
            @Suppress("UNCHECKED_CAST")
            val data = groovy.json.JsonSlurper().parse(coverage) as Map<String, Any?>
            (data["summaryText"] as? List<*>)?.forEach { println(it) }
            println("  Details: ${coverage.absolutePath}")
        }
        println("Install: adb install -r \"${out.absolutePath}\"")
        println("WARNING: this APK contains official sprites and cries (c) Nintendo/Creatures/GAME FREAK. Personal use only; never share or publish it.")
    }
}
