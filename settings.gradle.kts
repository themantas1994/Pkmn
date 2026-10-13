// Google's Maven Central mirror is listed first: it is faster and avoids Central's rate limits.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
    }
}
rootProject.name = "pkmn-crystalline"
include(":engine", ":tools", ":core", ":desktop")
// The Android module needs an SDK; skip it gracefully when none is configured.
val sdkConfigured = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (sdkConfigured) include(":android")
