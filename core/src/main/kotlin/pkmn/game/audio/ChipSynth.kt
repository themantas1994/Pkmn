package pkmn.game.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A pure-Kotlin, allocation-free (after construction) four-channel synthesizer modelled on the
 * Game Boy Color APU:
 *
 *  * channel 0, 1 – pulse (duty 12.5 / 25 / 50 / 75 %), band-limited with PolyBLEP
 *  * channel 2    – wave (32-sample, 4-bit wavetable; typically used for bass)
 *  * channel 3    – noise (15- or 7-bit LFSR; drums)
 *
 * Every channel has a GB-style volume envelope (0..15, step every n/64 s), vibrato, pitch slides and
 * panning. Pitch is expressed in (fractional) MIDI note numbers; the noise channel maps the same
 * number to its LFSR clock via [noiseHzToPitch].
 *
 * Rendering *adds* into a caller-supplied float buffer so several synths (music + SFX) can be mixed
 * cheaply; [render] is a convenience that writes 16-bit PCM directly.
 */
class ChipSynth(val sampleRate: Int = 22050, val stereo: Boolean = true) {
    init {
        require(sampleRate in 8000..96000) { "unsupported sample rate $sampleRate" }
    }

    /** Linear output gain applied after mixing (0..1+). */
    @Volatile var masterVolume: Float = 1f

    val voices: Array<Voice> = Array(4) { Voice(it) }
    val pulse1: Voice get() = voices[0]
    val pulse2: Voice get() = voices[1]
    val wave: Voice get() = voices[2]
    val noise: Voice get() = voices[3]

    private val controlPeriod = sampleRate.toDouble() / CONTROL_HZ
    private var controlCountdown = 0.0
    private val invSr = 1.0 / sampleRate
    private val slewCoef = (1.0 - kotlin.math.exp(-1.0 / (0.0006 * sampleRate))).toFloat()
    private val dcCoef = (1.0 - 2.0 * PI * 20.0 / sampleRate).toFloat() // ~20 Hz high-pass like the GB's output cap

    private var scratch = FloatArray(0)
    private var shortScratch = FloatArray(0)
    private var dcInL = 0f; private var dcOutL = 0f
    private var dcInR = 0f; private var dcOutR = 0f

    /** Silences every channel immediately and resets filter state. */
    fun reset() {
        for (v in voices) v.reset()
        dcInL = 0f; dcOutL = 0f; dcInR = 0f; dcOutR = 0f
        controlCountdown = 0.0
    }

    /** True when every voice is fully silent (lets callers skip rendering). */
    val isSilent: Boolean get() = voices.all { it.isIdle }

    /**
     * Renders [frames] frames and *adds* them (times [gain] and [masterVolume]) into [out], starting at
     * frame index [frameOffset]. [out] is interleaved L/R when [stereo], otherwise mono.
     */
    fun mixInto(out: FloatArray, frameOffset: Int, frames: Int, gain: Float = 1f) {
        if (frames <= 0) return
        val ch = if (stereo) 2 else 1
        val need = frames * ch
        if (scratch.size < need) scratch = FloatArray(need)
        val buf = scratch
        java.util.Arrays.fill(buf, 0, need, 0f)
        var pos = 0
        var remaining = frames
        while (remaining > 0) {
            if (controlCountdown <= 0.0) {
                for (v in voices) v.control()
                controlCountdown += controlPeriod
            }
            val n = min(remaining, max(1, kotlin.math.ceil(controlCountdown).toInt()))
            for (v in voices) if (!v.isIdle) v.render(buf, pos, n, ch)
            controlCountdown -= n
            pos += n
            remaining -= n
        }
        // DC-blocking high-pass + gain, added into the destination.
        val g = gain * masterVolume * MIX_SCALE
        val c = dcCoef
        if (stereo) {
            var o = frameOffset * 2
            var i = 0
            var inL = dcInL; var outL = dcOutL; var inR = dcInR; var outR = dcOutR
            while (i < need) {
                val xl = buf[i]; val xr = buf[i + 1]
                outL = xl - inL + c * outL; inL = xl
                outR = xr - inR + c * outR; inR = xr
                out[o] += outL * g; out[o + 1] += outR * g
                i += 2; o += 2
            }
            dcInL = inL; dcOutL = outL; dcInR = inR; dcOutR = outR
        } else {
            var o = frameOffset
            var inL = dcInL; var outL = dcOutL
            for (i in 0 until need) {
                val x = buf[i]
                outL = x - inL + c * outL; inL = x
                out[o++] += outL * g
            }
            dcInL = inL; dcOutL = outL
        }
    }

    /** Renders [frames] frames of 16-bit PCM into [out] (interleaved when stereo), overwriting it. */
    fun render(out: ShortArray, frameOffset: Int, frames: Int) {
        val ch = if (stereo) 2 else 1
        val need = frames * ch
        if (shortScratch.size < need) shortScratch = FloatArray(need)
        java.util.Arrays.fill(shortScratch, 0, need, 0f)
        mixInto(shortScratch, 0, frames)
        floatToPcm(shortScratch, 0, out, frameOffset * ch, need)
    }

    /**
     * One synth channel. Fields are plain vars so the sequencer can drive them without allocation;
     * call [noteOn] / [release] / [cut] for the usual operations.
     */
    inner class Voice(val index: Int) {
        val kind: Int = when (index) { 0, 1 -> KIND_PULSE; 2 -> KIND_WAVE; else -> KIND_NOISE }

        // --- pitch -------------------------------------------------------------------------------
        /** Current pitch in MIDI note units (fractional). */
        var pitch = 60.0
        /** Pitch the slide is moving toward, and its speed in semitones per second (0 = no slide). */
        var slideTarget = 60.0
        var slideSpeed = 0.0
        /** Vibrato depth in cents, rate in Hz, delay in seconds after note-on. */
        var vibDepth = 0.0
        var vibRate = 5.0
        var vibDelay = 0.0
        /** Per-frame (1/64 s) pitch sweep in semitones, used by drums/SFX; 0 = none. */
        var sweepPerFrame = 0.0

        // --- timbre ------------------------------------------------------------------------------
        /** Pulse duty 0..3 → 12.5, 25, 50, 75 %. */
        var duty = 2
        /** Wave channel table: 32 nibbles (0..15). */
        var waveTable: ByteArray = DEFAULT_WAVES[0]
        /** Noise LFSR width: true = 7-bit ("short", metallic). */
        var shortNoise = false

        // --- volume ------------------------------------------------------------------------------
        /** Current envelope volume 0..15. */
        var volume = 0
        /** Envelope: +n = increase every n/64 s, -n = decrease every n/64 s, 0 = hold. */
        var envStep = 0
        /** Envelope step used after [release]; 0 = cut immediately. */
        var releaseStep = 0
        /** Pan: -1 = left only, 0 = both, +1 = right only (GB-style hard panning, intermediate allowed). */
        var pan = 0f
        /** Extra linear channel gain (used for mixing balance). */
        var gain = 1f

        // --- runtime state -----------------------------------------------------------------------
        var gateOn = false; private set
        private var envCounter = 0
        private var noteTime = 0.0
        private var phase = 0.0
        private var phaseInc = 0.0
        private var amp = 0f
        private var targetAmp = 0f
        private var lfsr = 0x7FFF
        private var noiseAcc = 0.0
        private var noiseOut = 1f
        private var drum: DrumDef? = null
        private var drumStep = 0
        private var drumFrames = 0
        private var drumScale = 15
        private var frameTick = 0

        val isIdle: Boolean get() = targetAmp == 0f && abs(amp) < 1e-4f

        fun reset() {
            gateOn = false; volume = 0; envStep = 0; amp = 0f; targetAmp = 0f
            slideSpeed = 0.0; sweepPerFrame = 0.0; vibDepth = 0.0; phase = 0.0; lfsr = 0x7FFF; drum = null
        }

        /** Starts a drum program on this (noise) voice; [volScale] 0..15 scales the program's volumes. */
        fun playDrum(d: DrumDef, volScale: Int = 15) {
            drum = d
            drumScale = volScale.coerceIn(0, 15)
            slideSpeed = 0.0
            vibDepth = 0.0
            noteTime = 0.0
            frameTick = 0
            lfsr = 0x7FFF
            noiseAcc = 0.0
            gateOn = true
            enterDrumStep(0)
            updatePitch()
        }

        private fun enterDrumStep(i: Int) {
            val d = drum ?: return
            val s = d.steps[i]
            drumStep = i
            s.hz?.let { pitch = noiseHzToPitch(it); slideTarget = pitch }
            s.volume?.let { volume = (it * drumScale + 7) / 15 }
            s.shortNoise?.let { shortNoise = it }
            envStep = s.envStep
            envCounter = 0
            sweepPerFrame = s.sweep
            drumFrames = if (s.frames > 0) s.frames else if (i == d.steps.size - 1) 0 else 1
            targetAmp = volume / 15f
        }

        /**
         * Starts a note. [retrigger] = false keeps the envelope running (legato / slur).
         * Set timbre/envelope fields before calling.
         */
        fun noteOn(pitch: Double, volume: Int, envStep: Int, retrigger: Boolean = true) {
            this.pitch = pitch
            this.slideTarget = pitch
            slideSpeed = 0.0
            sweepPerFrame = 0.0
            drum = null
            if (retrigger || !gateOn) {
                this.volume = volume.coerceIn(0, 15)
                this.envStep = envStep
                envCounter = 0
                noteTime = 0.0
                // restart the waveform so output never depends on how rendering was chunked
                if (kind == KIND_NOISE) { lfsr = 0x7FFF; noiseAcc = 0.0 } else phase = 0.0
            }
            gateOn = true
            updatePitch()
            targetAmp = this.volume / 15f
        }

        /** Gate off: switch to the release envelope (or cut if none). */
        fun release() {
            if (!gateOn) return
            gateOn = false
            if (releaseStep < 0) { envStep = releaseStep; envCounter = 0 } else { volume = 0; targetAmp = 0f }
        }

        /** Immediate silence (with a ~1 ms de-click). */
        fun cut() { gateOn = false; volume = 0; targetAmp = 0f; drum = null }

        internal fun control() {
            if (isIdle && !gateOn) return
            val dt = 1.0 / CONTROL_HZ
            noteTime += dt
            val d = drum
            if (d != null && ++frameTick >= CONTROL_HZ / 64) {
                frameTick = 0
                if (drumFrames > 0 && --drumFrames == 0 && drumStep + 1 < d.steps.size) enterDrumStep(drumStep + 1)
            }
            // envelope: steps every |envStep| frames of 1/64 s = 4 control ticks at 256 Hz
            if (envStep != 0) {
                envCounter++
                if (envCounter >= abs(envStep) * (CONTROL_HZ / 64)) {
                    envCounter = 0
                    volume = (volume + if (envStep > 0) 1 else -1).coerceIn(0, 15)
                }
            }
            targetAmp = volume / 15f
            // slides
            if (slideSpeed > 0.0 && pitch != slideTarget) {
                val d = slideSpeed * dt
                pitch = if (pitch < slideTarget) min(slideTarget, pitch + d) else max(slideTarget, pitch - d)
            }
            if (sweepPerFrame != 0.0) pitch += sweepPerFrame * (64.0 / CONTROL_HZ)
            updatePitch()
        }

        private fun updatePitch() {
            var p = pitch
            if (vibDepth > 0.0 && noteTime >= vibDelay) {
                p += vibDepth / 100.0 * sin(2.0 * PI * vibRate * (noteTime - vibDelay))
            }
            p = p.coerceIn(-24.0, 150.0)
            val hz = 440.0 * 2.0.pow((p - 69.0) / 12.0)
            phaseInc = when (kind) {
                KIND_NOISE -> hz * invSr               // LFSR clocks per sample
                else -> min(hz * invSr, 0.45)          // cycles per sample (keep below Nyquist)
            }
        }

        internal fun render(buf: FloatArray, start: Int, n: Int, ch: Int) {
            val lg: Float; val rg: Float
            if (ch == 2) {
                lg = gain * min(1f, 1f - pan); rg = gain * min(1f, 1f + pan)
            } else { lg = gain; rg = 0f }
            var a = amp
            val ta = targetAmp
            val k = slewCoef
            var ph = phase
            val inc = phaseInc
            var idx = start * ch
            when (kind) {
                KIND_PULSE -> {
                    val d = DUTY_VALUES[duty and 3]
                    for (i in 0 until n) {
                        ph += inc
                        if (ph >= 1.0) ph -= 1.0
                        var s = if (ph < d) 1.0 else -1.0
                        s += polyBlep(ph, inc)
                        var t2 = ph - d
                        if (t2 < 0.0) t2 += 1.0
                        s -= polyBlep(t2, inc)
                        a += (ta - a) * k
                        val v = s.toFloat() * a
                        if (ch == 2) { buf[idx] += v * lg; buf[idx + 1] += v * rg; idx += 2 } else { buf[idx++] += v * lg }
                    }
                }
                KIND_WAVE -> {
                    val tbl = waveTable
                    for (i in 0 until n) {
                        ph += inc
                        if (ph >= 1.0) ph -= 1.0
                        val s = (tbl[(ph * 32.0).toInt() and 31] - 7.5f) * (1f / 7.5f)
                        a += (ta - a) * k
                        val v = s * a
                        if (ch == 2) { buf[idx] += v * lg; buf[idx + 1] += v * rg; idx += 2 } else { buf[idx++] += v * lg }
                    }
                }
                else -> {
                    var acc = noiseAcc
                    var reg = lfsr
                    var outv = noiseOut
                    val short = shortNoise
                    for (i in 0 until n) {
                        acc += inc
                        if (acc >= 1.0) {
                            var steps = acc.toInt()
                            acc -= steps
                            if (steps > 64) steps = 64
                            while (steps-- > 0) {
                                val bit = (reg xor (reg shr 1)) and 1
                                reg = (reg shr 1) or (bit shl 14)
                                if (short) reg = (reg and 0x40.inv()) or (bit shl 6)
                            }
                            outv = if (reg and 1 == 0) 1f else -1f
                        }
                        a += (ta - a) * k
                        val v = outv * a
                        if (ch == 2) { buf[idx] += v * lg; buf[idx + 1] += v * rg; idx += 2 } else { buf[idx++] += v * lg }
                    }
                    noiseAcc = acc; lfsr = reg; noiseOut = outv
                }
            }
            phase = ph
            amp = a
        }
    }

    companion object {
        const val KIND_PULSE = 0
        const val KIND_WAVE = 1
        const val KIND_NOISE = 2
        /** Control (envelope/vibrato/slide) update rate. Multiple of 64 so GB envelope timing is exact. */
        const val CONTROL_HZ = 256
        /** Per-channel headroom: four full-scale channels sum to ~1.0. */
        const val MIX_SCALE = 0.25f
        val DUTY_VALUES = doubleArrayOf(0.125, 0.25, 0.5, 0.75)

        /** Built-in wavetables (32 x 4-bit). Index 0 triangle, 1 saw, 2 square, 3 sine, 4 organ (1st+2nd harmonic), 5 thin pulse, 6 rounded square (punchy bass), 7 bright bass. */
        val DEFAULT_WAVES: List<ByteArray> = listOf(
            hexWave("0123456789ABCDEFFEDCBA9876543210"),
            hexWave("00112233445566778899AABBCCDDEEFF"),
            hexWave("FFFFFFFFFFFFFFFF0000000000000000"),
            synthWave { t -> sin(2.0 * PI * t) },
            synthWave { t -> 0.65 * sin(2.0 * PI * t) + 0.35 * sin(4.0 * PI * t) },
            hexWave("FFFFFFFF000000000000000000000000"),
            synthWave { t -> kotlin.math.tanh(2.5 * sin(2.0 * PI * t)) / kotlin.math.tanh(2.5) },
            synthWave { t -> 0.62 * sin(2.0 * PI * t) + 0.28 * sin(4.0 * PI * t) + 0.16 * sin(6.0 * PI * t) },
        )

        private fun synthWave(f: (Double) -> Double): ByteArray =
            ByteArray(32) { i -> (7.5 + 7.5 * f((i + 0.5) / 32.0)).let { kotlin.math.round(it) }.toInt().coerceIn(0, 15).toByte() }

        /** Parses 32 hex digits into a wavetable. */
        fun hexWave(hex: String): ByteArray {
            val h = hex.filter { !it.isWhitespace() }
            require(h.length >= 32) { "wavetable needs 32 hex digits, got ${h.length}" }
            return ByteArray(32) { Character.digit(h[it], 16).also { d -> require(d >= 0) { "bad hex digit '${h[it]}'" } }.toByte() }
        }

        /** Converts an LFSR clock rate in Hz into the pitch units used by the noise voice. */
        fun noiseHzToPitch(hz: Double): Double = 69.0 + 12.0 * kotlin.math.ln(hz / 440.0) / kotlin.math.ln(2.0)

        /** Converts float samples to 16-bit PCM with a soft knee above 0.9 (no wrap-around). */
        fun floatToPcm(src: FloatArray, srcOff: Int, dst: ShortArray, dstOff: Int, count: Int) {
            for (i in 0 until count) {
                var x = src[srcOff + i]
                val ax = abs(x)
                if (ax > 0.9f) {
                    // soft knee: maps (0.9, inf) smoothly into (0.9, 1.0)
                    val over = ax - 0.9f
                    x = (0.9f + 0.1f * over / (over + 0.1f)) * (if (x < 0) -1f else 1f)
                }
                dst[dstOff + i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        private fun polyBlep(t: Double, dt: Double): Double {
            if (t < dt) { val x = t / dt; return x + x - x * x - 1.0 }
            if (t > 1.0 - dt) { val x = (t - 1.0) / dt; return x * x + x + x + 1.0 }
            return 0.0
        }
    }
}
