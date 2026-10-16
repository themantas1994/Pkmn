package pkmn.game

import com.badlogic.gdx.Gdx
import pkmn.game.audio.AudioEngine
import pkmn.game.audio.Sfx

/** [GameAudio] backed by the runtime chiptune synthesizer. Maps game sound ids onto [Sfx]. */
class SynthAudio : GameAudio {
    private val engine = AudioEngine({ id -> Gdx.files.internal("audio/music/$id.mml").takeIf { it.exists() }?.readString("UTF-8") })
    private val aliases = mapOf(
        "door" to Sfx.DOOR_ENTER, "ledge" to Sfx.LEDGE_JUMP, "cut" to Sfx.HIT_NORMAL, "rock_smash" to Sfx.HIT_NORMAL,
        "cry" to Sfx.STATUS_INFLICTED, "encounter" to Sfx.TRAINER_SPOTTED,
    )
    private val jingles = setOf("heal", "item_get", "level_up", "save")
    override var currentMusic: String? = null
        private set

    override fun playMusic(id: String, fadeMs: Int) {
        if (id == currentMusic) return
        currentMusic = id
        engine.playMusic(id, fadeMs)
    }

    override fun stopMusic(fadeMs: Int) { currentMusic = null; engine.stopMusic(fadeMs) }

    override fun playSfx(id: String) {
        if (id in jingles && Gdx.files.internal("audio/music/jingle_$id.mml").exists() && id != "item_get") { engine.playJingle("jingle_$id"); return }
        val sfx = Sfx.fromId(id) ?: aliases[id] ?: return
        engine.playSfx(sfx)
    }

    override fun setVolumes(music: Float, sfx: Float) { engine.setMusicVolume(music); engine.setSfxVolume(sfx) }
    override fun pause() = engine.pause()
    override fun resume() = engine.resume()
    override fun dispose() = engine.dispose()
}
