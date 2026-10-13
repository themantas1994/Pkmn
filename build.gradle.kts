plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("com.android.application") version "8.13.2" apply false
}

allprojects {
    group = "pkmn"
    version = "0.1.0"
}
