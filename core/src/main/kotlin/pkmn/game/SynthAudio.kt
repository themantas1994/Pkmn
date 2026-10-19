package pkmn.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Sound
import pkmn.game.audio.CryBank
import pkmn.game.audio.CryOutcome
import pkmn.game.audio.CryResolver
import pkmn.game.audio.CryVoice
import java.io.File
import pkmn.game.audio.AudioEngine
import pkmn.game.audio.Sfx

/** [GameAudio] backed by the runtime chiptune synthesizer. Maps game sound ids onto [Sfx]. */
class SynthAudio : GameAudio {
    private val engine = AudioEngine({ id -> Gdx.files.internal("audio/music/$id.mml").takeIf { it.exists() }?.readString("UTF-8") })
    private val aliases = mapOf(
        "door" to Sfx.DOOR_ENTER, "ledge" to Sfx.LEDGE_JUMP, "cut" to Sfx.HIT_NORMAL, "rock_smash" to Sfx.HIT_NORMAL,
        "cry" to Sfx.STATUS_INFLICTED, "encounter" to Sfx.TRAINER_SPOTTED, "trade" to Sfx.WARP,
    )
    private var cries: CryBank? = null
    private var sfxVolume = 1f

    override fun configureCries(dirs: List<File>, baseOf: (String) -> String?) {
        cries?.dispose()
        cries = CryBank(CryResolver(dirs, baseOf), { f -> GdxCryVoice(Gdx.audio.newSound(Gdx.files.absolute(f.path))) },
            { Gdx.app.log("PkmnCrystalline", it) })
    }

    override fun playCry(speciesId: String) {
        val bank = cries
        if (bank == null || engine.muted) { playSfx("cry"); return }
        // A sound that is still loading (Android SoundPool) is retried from update() instead of being replaced by the blip.
        when (bank.playOrDefer(speciesId, sfxVolume * CRY_GAIN, nowMs())) {
            CryOutcome.FALLBACK -> playSfx("cry")
            else -> {}
        }
    }

    /** Finishes a deferred cry; if its sound never became ready the generic blip plays instead. */
    override fun update() {
        val bank = cries ?: return
        if (!bank.hasPending) return
        if (bank.poll(nowMs()) == CryOutcome.FALLBACK) playSfx("cry")
    }

    private fun nowMs() = System.nanoTime() / 1_000_000L

    override fun prefetchCry(speciesId: String) { cries?.prefetch(speciesId) }

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

    override fun setVolumes(music: Float, sfx: Float) { sfxVolume = sfx.coerceIn(0f, 1f); engine.setMusicVolume(music); engine.setSfxVolume(sfx) }
    override fun pause() { cries?.stop(); engine.pause() }
    override fun resume() = engine.resume()
    override fun dispose() { cries?.dispose(); cries = null; engine.dispose() }

    private companion object {
        /** Recorded cries are mastered louder than the chiptune effects; this keeps them level with the other SFX. */
        const val CRY_GAIN = 0.6f
    }
}

private class GdxCryVoice(private val sound: Sound) : CryVoice {
    /** LibGDX returns -1 (desktop) or 0 (Android SoundPool, e.g. still loading) when no stream started. */
    override fun play(volume: Float): Boolean = sound.play(volume) > 0L
    override fun stop() { sound.stop() }
    override fun dispose() { sound.dispose() }
}
