package pkmn.game.audio

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in, machine-dependent timing benchmarks. They are skipped by default (and in CI) so a slow or busy machine cannot
 * fail the build; the deterministic synth output checks live in [ChipSynthTest]. Run with `./gradlew :core:test -Pbenchmarks`.
 */
class ChipSynthBenchmarkTest {
    @Test
    fun throughputIsWellAboveRealtime() {
        assumeTrue(System.getProperty("pkmn.benchmarks") == "true", "machine-dependent timing benchmark; enable with -Dpkmn.benchmarks=true (./gradlew :core:test -Pbenchmarks)")
        val sr = 22050
        val s = ChipSynth(sr, stereo = true)
        val buf = FloatArray(512 * 2)
        fun busy() {
            s.pulse1.vibDepth = 20.0; s.pulse1.duty = 1
            if (s.pulse1.volume == 0) s.pulse1.noteOn(72.0, 12, 0)
            if (s.pulse2.volume == 0) s.pulse2.noteOn(76.0, 12, 0)
            if (s.wave.volume == 0) s.wave.noteOn(40.0, 15, 0)
            if (s.noise.volume == 0) s.noise.playDrum(MmlParser.DEFAULT_KIT.getValue('O'))
        }
        // warm up the JIT
        repeat(2000) { busy(); s.mixInto(buf, 0, 512) }
        val seconds = 60
        val blocks = seconds * sr / 512
        val t0 = System.nanoTime()
        for (b in 0 until blocks) {
            if (b % 8 == 0) busy()
            java.util.Arrays.fill(buf, 0f)
            s.mixInto(buf, 0, 512)
        }
        val dt = (System.nanoTime() - t0) / 1e9
        val framesPerSec = blocks * 512 / dt
        val cpuPercent = 100.0 * sr / framesPerSec
        println("ChipSynth throughput: %.0f stereo frames/s (%.1fx realtime at %d Hz, %.2f%% of one desktop core)"
            .format(framesPerSec, framesPerSec / sr, sr, cpuPercent))
        assertTrue(framesPerSec > sr * 20, "synth too slow: $framesPerSec frames/s")
    }
}
