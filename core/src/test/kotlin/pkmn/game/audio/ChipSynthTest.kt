package pkmn.game.audio

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChipSynthTest {
    private fun renderMono(synth: ChipSynth, seconds: Double): FloatArray {
        val n = (synth.sampleRate * seconds).toInt()
        val buf = FloatArray(n * if (synth.stereo) 2 else 1)
        var pos = 0
        while (pos < n) { val c = minOf(333, n - pos); synth.mixInto(buf, pos, c); pos += c }
        return buf
    }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i].toDouble()
        return sqrt(s / maxOf(1, to - from))
    }

    @Test
    fun pulsePitchIsAccurate() {
        for (sr in listOf(22050, 32000)) {
            val s = ChipSynth(sr, stereo = false)
            s.pulse1.duty = 2
            s.pulse1.noteOn(69.0, 15, 0)
            val buf = renderMono(s, 1.0)
            var crossings = 0
            for (i in 1 until buf.size) if (buf[i - 1] < 0f && buf[i] >= 0f) crossings++
            assertTrue(abs(crossings - 440) <= 2, "expected ~440 Hz at $sr Hz, got $crossings crossings")
        }
    }

    @Test
    fun dutyCycleShapesWaveform() {
        for ((duty, expect) in listOf(0 to 0.125, 1 to 0.25, 2 to 0.5, 3 to 0.75)) {
            val s = ChipSynth(22050, stereo = false)
            s.pulse1.duty = duty
            s.pulse1.noteOn(57.0, 15, 0) // 220 Hz
            val buf = renderMono(s, 0.5)
            // measure fraction of each period spent in the "high" half using the median of the signal
            val start = 2000
            val seg = buf.copyOfRange(start, buf.size)
            val max = seg.max(); val min = seg.min()
            val mid = (max + min) / 2
            val frac = seg.count { it > mid }.toDouble() / seg.size
            assertTrue(abs(frac - expect) < 0.04, "duty $duty: high fraction $frac, expected $expect")
        }
    }

    @Test
    fun envelopeDecaysToSilenceOnGameBoyTiming() {
        val s = ChipSynth(22050, stereo = false)
        s.pulse1.noteOn(60.0, 15, -1) // 15 steps of 1/64 s = 0.234 s
        val buf = renderMono(s, 0.4)
        val early = rms(buf, 0, 2205)
        val late = rms(buf, (0.27 * 22050).toInt(), buf.size)
        assertTrue(early > 0.1, "note should start loud (rms=$early)")
        assertTrue(late < 1e-3, "note should have decayed (rms=$late)")
        assertTrue(s.isSilent)
    }

    @Test
    fun releaseEnvelopeAndCut() {
        val s = ChipSynth(22050, stereo = false)
        s.pulse1.releaseStep = -2
        s.pulse1.noteOn(60.0, 12, 0)
        renderMono(s, 0.1)
        s.pulse1.release()
        val tail = renderMono(s, 0.05)
        assertTrue(rms(tail) > 0.01, "release should fade, not cut")
        renderMono(s, 0.5)
        assertTrue(s.isSilent)
        s.pulse1.releaseStep = 0
        s.pulse1.noteOn(60.0, 12, 0)
        renderMono(s, 0.05)
        s.pulse1.release()
        renderMono(s, 0.01)
        assertTrue(s.isSilent, "rel=0 should cut (after de-click)")
    }

    @Test
    fun waveAndNoiseChannelsProduceSound() {
        val s = ChipSynth(22050, stereo = false)
        s.wave.waveTable = ChipSynth.DEFAULT_WAVES[0]
        s.wave.noteOn(45.0, 15, 0)
        val w = renderMono(s, 0.3)
        assertTrue(rms(w, 1000, w.size) > 0.05)
        s.reset()
        s.noise.playDrum(MmlParser.DEFAULT_KIT.getValue('S'))
        val n = renderMono(s, 0.1)
        assertTrue(rms(n) > 0.02)
        val mean = n.average()
        assertTrue(abs(mean) < 0.02, "noise should be roughly zero-mean, was $mean")
    }

    @Test
    fun hardPanningSilencesOtherSide() {
        val s = ChipSynth(22050, stereo = true)
        s.pulse1.pan = -1f
        s.pulse1.noteOn(64.0, 15, 0)
        val buf = renderMono(s, 0.2)
        var l = 0.0; var r = 0.0
        for (i in buf.indices step 2) { l += abs(buf[i]); r += abs(buf[i + 1]) }
        assertTrue(l > 100.0 && r < 1e-6, "left=$l right=$r")
    }

    @Test
    fun vibratoAndSlideMovePitch() {
        val s = ChipSynth(22050, stereo = false)
        s.pulse1.noteOn(60.0, 15, 0)
        s.pulse1.slideTarget = 72.0
        s.pulse1.slideSpeed = 24.0 // one octave in 0.5 s
        renderMono(s, 0.25)
        assertTrue(abs(s.pulse1.pitch - 66.0) < 0.3, "pitch mid-slide was ${s.pulse1.pitch}")
        renderMono(s, 0.5)
        assertEquals(72.0, s.pulse1.pitch, 1e-9)
    }

    @Test
    fun fullScaleMixDoesNotClip() {
        val s = ChipSynth(22050, stereo = true)
        for (v in s.voices.take(3)) v.noteOn(48.0, 15, 0)
        s.noise.playDrum(MmlParser.parse("D O1", "t").tracks[3].events.first { it.drum != null }.drum!!)
        val pcm = ShortArray(22050 * 2)
        s.render(pcm, 0, 22050)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue(peak in 3000..32767, "peak $peak")
    }
}
