package pkmn.game.audio

/** Ticks per quarter note used by every song (192 per whole note: allows 1/64 notes and triplets). */
const val TICKS_PER_QUARTER = 48
const val TICKS_PER_WHOLE = TICKS_PER_QUARTER * 4

/**
 * Timbre/envelope snapshot attached to every note. Immutable; produced by the parser from an
 * `#inst` definition plus inline overrides (`v`, `w`, `p`).
 */
data class Patch(
    val duty: Int = 2,
    val wave: Int = 0,
    val volume: Int = 12,
    val envStep: Int = 0,
    val releaseStep: Int = 0,
    val vibDelay: Double = 0.0,
    val vibDepth: Double = 0.0,
    val vibRate: Double = 5.5,
    val pan: Float = 0f,
    val slideSpeed: Double = 60.0,
    val gain: Float = 1f,
)

/** One step of a drum program, held for [frames] 1/64-s frames (the final step lasts until note end). */
data class DrumStep(
    val hz: Double?,
    val volume: Int?,
    val envStep: Int,
    val shortNoise: Boolean?,
    val frames: Int,
    val sweep: Double,
)

/** A noise-channel drum: a short program of [DrumStep]s. */
class DrumDef(val name: Char, val steps: List<DrumStep>)

/** A sequencer event. [type] is one of [NOTE], [REST], [TEMPO]. */
class Event(
    val type: Int,
    /** Absolute start tick within the track. */
    val tick: Int,
    val ticks: Int,
    val gate: Int = ticks,
    /** MIDI note number for pitched notes; -1 for drums/rests. */
    val pitch: Int = -1,
    val patch: Patch? = null,
    val drum: DrumDef? = null,
    /** Slur from the previous note: change pitch without retriggering the envelope. */
    val legato: Boolean = false,
    /** Glide from the previous pitch at the patch's slide speed. */
    val portamento: Boolean = false,
    /** Pitch sweep in semitones over the note's gate time (0 = none). */
    val sweep: Int = 0,
    /** Drum volume scale 0..15 (noise channel). */
    val volume: Int = 15,
    /** Tempo in BPM for [TEMPO] events. */
    val bpm: Double = 0.0,
    val line: Int = 0,
    val col: Int = 0,
) {
    companion object {
        const val NOTE = 0
        const val REST = 1
        const val TEMPO = 2
    }
}

/** One channel's flattened event list. [loopIndex] is the event index to jump to when looping (-1 = none). */
class Track(val channel: Int, val events: List<Event>, val loopIndex: Int, val lengthTicks: Int) {
    val isEmpty: Boolean get() = events.none { it.type == Event.NOTE }
}

/** A parsed song, ready for [SongPlayer]. */
class Song(
    val id: String,
    val title: String,
    val keys: List<String>,
    val beatsPerBar: Int,
    val beatUnit: Int,
    val initialTempo: Double,
    val tracks: List<Track>,
    /** Global loop tick, or -1 for a one-shot song (jingles). */
    val loopTick: Int,
    val lengthTicks: Int,
    val waves: List<ByteArray>,
    val gain: Float = 1f,
) {
    val loops: Boolean get() = loopTick >= 0

    /** (tick, bpm) tempo changes, sorted, always starting with tick 0. */
    val tempoMap: List<Pair<Int, Double>> by lazy {
        val m = sortedMapOf(0 to initialTempo)
        for (t in tracks) for (e in t.events) if (e.type == Event.TEMPO) m[e.tick] = e.bpm
        m.entries.map { it.key to it.value }
    }

    fun tempoAt(tick: Int): Double = tempoMap.lastOrNull { it.first <= tick }?.second ?: initialTempo

    /** Seconds from tick [from] to tick [to] honouring tempo changes. */
    fun secondsBetween(from: Int, to: Int): Double {
        var s = 0.0
        val map = tempoMap
        for (i in map.indices) {
            val segStart = maxOf(from, map[i].first)
            val segEnd = minOf(to, if (i + 1 < map.size) map[i + 1].first else Int.MAX_VALUE)
            if (segEnd > segStart) s += (segEnd - segStart) * 60.0 / (map[i].second * TICKS_PER_QUARTER)
        }
        return s
    }

    /** Length of the intro (before the loop point) in seconds. */
    val introSeconds: Double get() = if (loops) secondsBetween(0, loopTick) else 0.0
    /** Length of the looping body (or the whole song for one-shots) in seconds. */
    val loopSeconds: Double get() = secondsBetween(if (loops) loopTick else 0, lengthTicks)
    /** Time until the song first reaches its end/loop point. */
    val totalSeconds: Double get() = secondsBetween(0, lengthTicks)
}
