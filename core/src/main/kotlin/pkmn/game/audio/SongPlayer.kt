package pkmn.game.audio

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Drives a [ChipSynth] from a parsed [Song]: advances the sequencer tick by tick and renders the
 * synth between ticks. Looping songs wrap seamlessly to their loop point; one-shot songs end and
 * let their tails ring out. No allocation during [render].
 */
class SongPlayer(val synth: ChipSynth) {
    var song: Song? = null
        private set
    /** True once a one-shot song has reached its end (tails may still be sounding). */
    var ended = true
        private set
    /** Number of times the song wrapped to its loop point. */
    var loopsCompleted = 0
        private set
    /** Current sequencer tick. */
    var tick = 0
        private set

    private val idx = IntArray(4)
    private val remaining = IntArray(4)
    private val gateLeft = IntArray(4)
    private var bpm = 120.0
    private var samplesPerTick = 0.0
    private var samplesLeft = 0.0
    private var started = false

    /** True while the song is still sequencing or its last notes are still audible. */
    val isActive: Boolean get() = song != null && (!ended || !synth.isSilent)

    fun load(s: Song?) {
        synth.reset()
        song = s
        ended = s == null
        loopsCompleted = 0
        tick = 0
        started = false
        samplesLeft = 0.0
        idx.fill(0); remaining.fill(0); gateLeft.fill(0)
        setTempo(s?.initialTempo ?: 120.0)
    }

    fun stop() = load(null)

    private fun setTempo(b: Double) {
        bpm = b
        samplesPerTick = synth.sampleRate * 60.0 / (b * TICKS_PER_QUARTER)
    }

    /** Renders [frames] frames, adding them (times [gain]) into [out] at [frameOffset]. */
    fun render(out: FloatArray, frameOffset: Int, frames: Int, gain: Float = 1f) {
        val s = song
        if (s == null) return
        val g = gain * s.gain
        var pos = 0
        while (pos < frames) {
            if (!ended && samplesLeft <= 0.0) {
                if (started) endTick(s)
                if (!ended) { startTick(s); samplesLeft += samplesPerTick }
                started = true
            }
            val n = if (ended) frames - pos else minOf(frames - pos, maxOf(1, ceil(samplesLeft).toInt()))
            if (!(ended && synth.isSilent)) synth.mixInto(out, frameOffset + pos, n, g)
            samplesLeft -= n
            pos += n
        }
    }

    private fun startTick(s: Song) {
        for (ch in 0 until 4) {
            val t = s.tracks[ch]
            val ev = t.events
            while (remaining[ch] == 0 && idx[ch] < ev.size) {
                val e = ev[idx[ch]++]
                when (e.type) {
                    Event.TEMPO -> setTempo(e.bpm)
                    Event.REST -> if (e.ticks > 0) {
                        if (ch != 3) synth.voices[ch].release()
                        remaining[ch] = e.ticks; gateLeft[ch] = 0
                    }
                    else -> {
                        startNote(s, ch, e)
                        remaining[ch] = e.ticks
                        gateLeft[ch] = if (e.drum != null) 0 else e.gate
                    }
                }
            }
        }
    }

    private fun startNote(s: Song, ch: Int, e: Event) {
        val v = synth.voices[ch]
        val d = e.drum
        if (d != null) { v.playDrum(d, e.volume); return }
        val p = e.patch ?: return
        v.duty = p.duty
        v.waveTable = s.waves[p.wave]
        v.releaseStep = p.releaseStep
        v.vibDelay = p.vibDelay
        v.vibDepth = p.vibDepth
        v.vibRate = p.vibRate
        v.pan = p.pan
        v.gain = p.gain
        val from = v.pitch
        val sounding = v.gateOn
        val retrigger = !((e.legato || e.portamento) && sounding)
        v.noteOn(e.pitch.toDouble(), p.volume, p.envStep, retrigger)
        if (e.portamento && sounding) {
            v.pitch = from
            v.slideTarget = e.pitch.toDouble()
            v.slideSpeed = p.slideSpeed
        }
        if (e.sweep != 0) {
            val secs = maxOf(1, e.gate) * samplesPerTick / synth.sampleRate
            v.slideTarget = e.pitch.toDouble() + e.sweep
            v.slideSpeed = abs(e.sweep) / secs
        }
    }

    private fun endTick(s: Song) {
        for (ch in 0 until 4) {
            if (remaining[ch] > 0) {
                remaining[ch]--
                if (gateLeft[ch] > 0) {
                    gateLeft[ch]--
                    if (gateLeft[ch] == 0 && remaining[ch] > 0) synth.voices[ch].release()
                }
            }
        }
        tick++
        if (tick >= s.lengthTicks) {
            if (s.loops) {
                tick = s.loopTick
                for (ch in 0 until 4) {
                    val t = s.tracks[ch]
                    idx[ch] = if (t.loopIndex >= 0) t.loopIndex else t.events.size
                    remaining[ch] = 0
                }
                setTempo(s.tempoAt(s.loopTick))
                loopsCompleted++
            } else {
                ended = true
                for (ch in 0 until 3) synth.voices[ch].release()
            }
        }
    }
}
