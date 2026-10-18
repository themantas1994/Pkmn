package pkmn.game.audio

import pkmn.game.GameAudio
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicLeaseTest {
    private class Rec(start: String?) : GameAudio {
        override var currentMusic: String? = start
        val calls = ArrayList<String>()
        override fun playMusic(id: String, fadeMs: Int) { if (id == currentMusic) return; currentMusic = id; calls += "play:$id" }
        override fun stopMusic(fadeMs: Int) { currentMusic = null; calls += "stop" }
    }

    @Test fun restoresPreviousTrack() {
        val a = Rec("route_mid"); val l = MusicLease("evolution")
        l.acquire(a); assertEquals("evolution", a.currentMusic)
        l.release(); l.restoreIfIdle(a)
        assertEquals("route_mid", a.currentMusic)
        assertEquals(listOf("play:evolution", "play:route_mid"), a.calls)
    }

    @Test fun restoreIsIdempotent() {
        val a = Rec("cave"); val l = MusicLease("evolution")
        l.acquire(a); l.release(); l.restoreIfIdle(a); l.restoreIfIdle(a); l.release()
        assertEquals(listOf("play:evolution", "play:cave"), a.calls)
    }

    @Test fun consecutiveEvolutionsDoNotRestart() {
        val a = Rec("forest"); val l = MusicLease("evolution")
        l.acquire(a)
        l.release()            // first screen exits
        l.acquire(a)           // second screen enters in the same batch
        l.restoreIfIdle(a)     // deferred restore sees the new lease and does nothing
        assertEquals(listOf("play:evolution"), a.calls)
        l.release(); l.restoreIfIdle(a)
        assertEquals(listOf("play:evolution", "play:forest"), a.calls)
        assertEquals("forest", a.currentMusic)
    }

    @Test fun silenceBeforeMeansStopAfter() {
        val a = Rec(null); val l = MusicLease("evolution")
        l.acquire(a); l.release(); l.restoreIfIdle(a)
        assertEquals(null, a.currentMusic)
    }

    @Test fun doesNotClobberMusicStartedByOthers() {
        val a = Rec("route_early"); val l = MusicLease("evolution")
        l.acquire(a); l.release()
        a.playMusic("ocean_surf")
        l.restoreIfIdle(a)
        assertEquals("ocean_surf", a.currentMusic)
    }
}
