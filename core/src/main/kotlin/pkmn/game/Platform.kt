package pkmn.game

import java.io.File

/** Platform services supplied by the desktop and Android launchers. */
interface Platform {
    val name: String
    val isMobile: Boolean
    /** Writable directory for saves and settings. */
    val saveDir: File
    /** Directory holding locally imported, non-redistributable assets (official sprites), or null. */
    val importedAssetsDir: File?
    fun vibrate(ms: Int) {}
    /** Device held upside-down (used for Inkay's evolution), if a sensor is available. */
    fun isUpsideDown(): Boolean = false
    fun log(msg: String) = println(msg)
    /** Quits the app (desktop) or moves it to the background (Android). */
    fun exit() {}
}

/** Audio abstraction so the game runs with or without the synthesizer (tests, missing devices). */
interface GameAudio {
    fun playMusic(id: String, fadeMs: Int = 300) {}
    fun stopMusic(fadeMs: Int = 300) {}
    fun playSfx(id: String) {}
    fun setVolumes(music: Float, sfx: Float) {}
    fun pause() {}
    fun resume() {}
    fun dispose() {}
    val currentMusic: String? get() = null
}

object NoAudio : GameAudio
