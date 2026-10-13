package pkmn.game.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioEngineTest {
    private val songs = mapOf(
        "a" to "#tempo 120\n#inst 1 duty=2 env=12,0\nA @1 $ c2 e2 g2 e2",
        "b" to "#tempo 140\nC o2 $ c4 g4 c4 g4",
        "bad" to "A c4 ]",
        "jingle" to "#tempo 240\nA c8 e8 g4",
    )
    private val logs = mutableListOf<String>()

    private fun engine() = AudioEngine(
        loader = { songs[it] }, output = object : PcmOutput { override fun write(samples: ShortArray, offset: Int, count: Int) {} },
        log = { synchronized(logs) { logs.add(it) } }, startThread = false,
    )

    private fun peak(e: AudioEngine, blocks: Int): Int {
        val buf = ShortArray(e.blockFrames * 2)
        var p = 0
        repeat(blocks) { e.renderBlock(buf); for (s in buf) p = maxOf(p, abs(s.toInt())) }
        return p
    }

    @Test
    fun silentUntilMusicStarts() {
        val e = engine()
        assertEquals(0, peak(e, 10))
        e.playMusic("a")
        assertTrue(peak(e, 20) > 1000)
        assertEquals("a", e.currentMusicId)
    }

    @Test
    fun missingAndInvalidSongsPlaySilenceAndLog() {
        val e = engine()
        e.playMusic("nope")
        assertEquals(0, peak(e, 20))
        e.playMusic("bad")
        assertEquals(0, peak(e, 20))
        assertTrue(logs.any { "nope" in it && "not found" in it }, logs.toString())
        assertTrue(logs.any { "bad" in it && "parse" in it }, logs.toString())
        e.playMusic("a")
        assertTrue(peak(e, 20) > 1000, "recovers with a valid song")
    }

    @Test
    fun fadeSwitchesTracks() {
        val e = engine()
        e.playMusic("a")
        peak(e, 10)
        e.playMusic("b", fadeMs = 200)
        peak(e, 4)
        assertEquals("b", e.currentMusicId)
        peak(e, 20) // 200 ms = ~9 blocks of 512 at 22050
        e.stopMusic(100)
        peak(e, 10)
        assertEquals(null, e.currentMusicId)
        assertEquals(0, peak(e, 5))
    }

    @Test
    fun sfxMixAndStealSlots() {
        val e = engine()
        e.playSfx(Sfx.MENU_SELECT)
        assertTrue(peak(e, 3) > 500)
        e.playSfx(Sfx.FAINT); e.playSfx(Sfx.HIT_NORMAL); e.playSfx(Sfx.SAVE)
        e.renderBlock(ShortArray(e.blockFrames * 2))
        assertEquals(2, e.activeSfxCount)
        peak(e, 200)
        assertEquals(0, e.activeSfxCount, "effects finish and free their slots")
    }

    @Test
    fun volumeAndMute() {
        val e = engine()
        e.playMusic("a", 0)
        val full = peak(e, 20)
        e.setMusicVolume(0.25f)
        val quiet = peak(e, 20)
        assertTrue(quiet < full * 0.4, "full=$full quiet=$quiet")
        e.muted = true
        assertEquals(0, peak(e, 5))
    }

    @Test
    fun jinglePausesAndResumesMusic() {
        val e = engine()
        e.playMusic("a", 0)
        peak(e, 5)
        e.playJingle("jingle")
        assertTrue(peak(e, 40) > 500)
        assertEquals("a", e.currentMusicId)
        assertTrue(peak(e, 40) > 500, "music resumes after the jingle")
    }

    @Test
    fun threadedEngineStreamsAndDisposes() {
        val written = AtomicLong()
        val latch = CountDownLatch(20)
        val out = object : PcmOutput {
            override fun write(samples: ShortArray, offset: Int, count: Int) {
                written.addAndGet(count.toLong()); latch.countDown(); Thread.sleep(2)
            }
        }
        val e = AudioEngine({ songs[it] }, output = out, log = {})
        e.playMusic("a")
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        e.pause()
        Thread.sleep(50)
        val atPause = written.get()
        Thread.sleep(100)
        assertTrue(written.get() - atPause <= 1024 * 2, "paused engine should stop writing")
        e.resume()
        Thread.sleep(100)
        assertTrue(written.get() > atPause)
        e.dispose()
    }
}
