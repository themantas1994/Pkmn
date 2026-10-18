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
    /** Show the on-screen controls even without a touch screen (desktop phone preview). */
    val forceTouchControls: Boolean get() = false
    fun vibrate(ms: Int) {}
    /** Device held upside-down (used for Inkay's evolution), if a sensor is available. */
    fun isUpsideDown(): Boolean = false
    fun log(msg: String) = println(msg)
    /** Whether the player can import a sprite archive from inside the game (Android document picker). */
    val canImportAssets: Boolean get() = false
    /** Lets the player pick a sprite archive and extracts it into [importedAssetsDir]; [done] runs on the game thread. */
    fun importAssets(done: (message: String) -> Unit) {}
    /** Quits the app (desktop) or moves it to the background (Android). */
    fun exit() {}
}

/** Audio abstraction so the game runs with or without the synthesizer (tests, missing devices). */
interface GameAudio {
    fun playMusic(id: String, fadeMs: Int = 300) {}
    fun stopMusic(fadeMs: Int = 300) {}
    fun playSfx(id: String) {}
    /** Tells the audio where local cry files live (never bundled in public builds) and how forms map to base species. */
    fun configureCries(dirs: List<java.io.File>, baseOf: (String) -> String?) {}
    /** Plays the cry of [speciesId] (a new cry cuts the previous one); falls back to the generic cry blip when there is no file. */
    fun playCry(speciesId: String) = playSfx("cry")
    /** Loads the cry ahead of [playCry]: Android sounds load asynchronously, so a cry played right after loading is silent. */
    fun prefetchCry(speciesId: String) {}
    fun setVolumes(music: Float, sfx: Float) {}
    fun pause() {}
    fun resume() {}
    fun dispose() {}
    val currentMusic: String? get() = null
}

object NoAudio : GameAudio
