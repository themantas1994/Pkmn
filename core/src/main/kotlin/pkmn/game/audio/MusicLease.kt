package pkmn.game.audio

import pkmn.game.GameAudio

/**
 * Temporarily takes over the background music (e.g. the evolution theme) and puts the previous track back afterwards.
 *
 * Leases nest: while at least one is held the temporary [track] keeps playing, so back-to-back users do not restart it.
 * The previous track is remembered by the first [acquire] and restored by [restoreIfIdle], which the owner should call
 * one step *after* [release] (so a follower acquired in the meantime cancels the restore). The restore is skipped if
 * something else started other music in the meantime.
 */
class MusicLease(private val track: String) {
    private var depth = 0
    private var saved: String? = null
    private var hasSaved = false

    val held: Boolean get() = depth > 0

    fun acquire(audio: GameAudio) {
        if (depth == 0 && !hasSaved) { saved = audio.currentMusic; hasSaved = true }
        depth++
        audio.playMusic(track, 0)
    }

    fun release() { if (depth > 0) depth-- }

    fun restoreIfIdle(audio: GameAudio) {
        if (depth > 0 || !hasSaved) return
        val prev = saved
        saved = null; hasSaved = false
        if (audio.currentMusic != track) return // something else already took over
        if (prev != null) audio.playMusic(prev) else audio.stopMusic()
    }
}
