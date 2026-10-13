package pkmn.game.audio

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongPlayerTest {
    private fun render(p: SongPlayer, seconds: Double, block: Int = 441): FloatArray {
        val n = (p.synth.sampleRate * seconds).toInt()
        val buf = FloatArray(n)
        var pos = 0
        while (pos < n) { val c = minOf(block, n - pos); p.render(buf, pos, c); pos += c }
        return buf
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i].toDouble()
        return sqrt(s / (to - from))
    }

    @Test
    fun notesAndRestsFollowTempo() {
        val song = MmlParser.parse("#tempo 120\nA @0 c4 r4 c4 r4")
        val p = SongPlayer(ChipSynth(22050, false))
        p.load(song)
        val buf = render(p, 2.05)
        val q = 22050 / 2 // one quarter = 0.5 s
        assertTrue(rms(buf, 200, q - 200) > 0.05)
        assertTrue(rms(buf, q + 300, 2 * q - 200) < 1e-3, "rest should be silent")
        assertTrue(rms(buf, 2 * q + 200, 3 * q - 200) > 0.05)
        assertTrue(p.ended)
    }

    @Test
    fun loopingSongWrapsSeamlessly() {
        val song = MmlParser.parse("#tempo 240\nA c4 $ d4 e4\nD r4 $ K4 S4")
        val p = SongPlayer(ChipSynth(22050, false))
        p.load(song)
        render(p, 0.25 + 0.5 * 4 + 0.01, block = 97) // quarter = 0.25 s
        assertEquals(4, p.loopsCompleted)
        assertFalse(p.ended)
        assertTrue(p.isActive)
    }

    @Test
    fun oneShotEndsAndGoesSilent() {
        val song = MmlParser.parse("#tempo 200\n#inst 1 env=12,-1\nA @1 c8 e8 g4")
        val p = SongPlayer(ChipSynth(22050, true))
        p.load(song)
        val buf = FloatArray(22050 * 2 * 2)
        p.render(buf, 0, 22050 * 2)
        assertTrue(p.ended)
        assertFalse(p.isActive)
    }

    @Test
    fun blockSizeDoesNotChangeOutput() {
        val src = "#tempo 133\n#inst 1 duty=1 env=10,-2 vib=4,20,6\nA @1 l16 $ [c e g > c <]4\nC o2 $ c2 g2\nD $ [K8 H8 S8 H8]2"
        fun go(block: Int): FloatArray {
            val p = SongPlayer(ChipSynth(22050, false)); p.load(MmlParser.parse(src)); return render(p, 3.0, block)
        }
        val a = go(64); val b = go(1000)
        var maxDiff = 0f
        for (i in a.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(a[i] - b[i]))
        assertTrue(maxDiff < 1e-4f, "rendering must not depend on block size (diff $maxDiff)")
    }
}
