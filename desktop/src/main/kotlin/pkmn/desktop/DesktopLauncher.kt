package pkmn.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import pkmn.game.GameAudio
import pkmn.game.NoAudio
import pkmn.game.PkmnGame
import pkmn.game.Platform
import pkmn.game.SynthAudio
import pkmn.game.input.Button
import java.io.File

class DesktopPlatform(override val saveDir: File) : Platform {
    override val name = "desktop"
    override val isMobile = false
    override val importedAssetsDir: File? = listOf(File("../assets-local"), File("assets-local")).firstOrNull { it.isDirectory }
    override fun exit() { Gdx.app.exit() }
}

/**
 * Desktop launcher. Options:
 *   --saves=<dir>        save directory (default ~/.pkmn-crystalline)
 *   --script=<file>      automation script: lines "wait N", "press BUTTON [frames]", "shot NAME", "quit"
 *   --shots=<dir>        where screenshots go
 *   --mute               no audio
 *   --scale=N            window scale (default 3)
 *   --portrait           phone-like portrait window with touch controls
 */
fun main(args: Array<String>) {
    val opts = args.associate { a -> a.removePrefix("--").substringBefore('=') to a.substringAfter('=', "true") }
    val saves = File(opts["saves"] ?: (System.getProperty("user.home") + "/.pkmn-crystalline"))
    val platform = DesktopPlatform(saves)
    val audio: (() -> GameAudio)? = if (opts["mute"] == "true") ({ NoAudio }) else ({ SynthAudio() })
    val game = PkmnGame(platform, audio)
    opts["script"]?.let { Automation(File(it).readLines(), File(opts["shots"] ?: "shots")).attach(game) }
    val scale = opts["scale"]?.toIntOrNull() ?: 3
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Pokémon Crystalline")
        if (opts["portrait"] == "true") setWindowedMode(540, 1080) else setWindowedMode(256 * scale, 224 * scale)
        setForegroundFPS(60)
        useVsync(true)
        if (opts.containsKey("script")) setIdleFPS(60)
    }
    Lwjgl3Application(game, config)
}

/** Feeds scripted input and captures screenshots (used for headless verification under Xvfb). */
class Automation(private val lines: List<String>, private val shots: File) {
    private var pc = 0
    private var wait = 0
    private var hold: Button? = null

    fun attach(game: PkmnGame) {
        shots.mkdirs()
        game.frameHook = { g -> tick(g) }
    }

    private fun tick(g: PkmnGame) {
        if (wait > 0) { wait--; if (wait == 0) hold?.let { g.input.inject(it, false); hold = null }; return }
        while (pc < lines.size) {
            val parts = lines[pc++].trim().split(Regex("\\s+"))
            when (parts[0]) {
                "", "#" -> continue
                "wait" -> { wait = parts[1].toInt(); return }
                "press" -> {
                    val b = Button.valueOf(parts[1].uppercase())
                    val frames = parts.getOrNull(2)?.toInt() ?: 2
                    g.input.inject(b, true); hold = b; wait = frames; return
                }
                "shot" -> { screenshot(File(shots, parts[1] + ".png")); continue }
                "quit" -> { Gdx.app.exit(); return }
                else -> if (parts[0].startsWith("#")) continue
            }
        }
    }

    private fun screenshot(f: File) {
        val w = Gdx.graphics.backBufferWidth; val h = Gdx.graphics.backBufferHeight
        val pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true)
        for (i in 4 until pixels.size step 4) pixels[i - 1] = 255.toByte()
        val pm = Pixmap(w, h, Pixmap.Format.RGBA8888)
        BufferUtils.copy(pixels, 0, pm.pixels, pixels.size)
        PixmapIO.writePNG(Gdx.files.absolute(f.absolutePath), pm)
        pm.dispose()
    }
}
