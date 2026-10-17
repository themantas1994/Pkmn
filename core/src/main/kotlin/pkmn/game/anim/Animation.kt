package pkmn.game.anim

/**
 * Elapsed-time animation primitives shared by the battle and overworld presentation.
 *
 * Animations never own game state: a [Clip] only moves presentation values between a start and an end state.
 * Every clip's [Clip.onEnd] must leave the presentation exactly where the logical state says it should be, so
 * [AnimationPlayer.skipAll] (fast-forward, cancelled battle, disabled animations) is always safe.
 */
class Clip(
    val name: String,
    /** Seconds at speed 1. */
    val duration: Float,
    /** Essential clips still play (shortened by the speed setting) when cosmetic animations are off. */
    val essential: Boolean = false,
    val onStart: () -> Unit = {},
    /** Progress 0..1, eased by the caller if needed. Always called with 1 before [onEnd]. */
    val onUpdate: (Float) -> Unit = {},
    val onEnd: () -> Unit = {},
)

/** Plays clips one after another (a timeline) plus independent background clips. Pure logic; no rendering. */
class AnimationPlayer {
    /** Playback speed multiplier from the options (1 = normal). */
    var speed: Float = 1f
    /** When false, non-essential clips complete instantly (their end state is still applied). */
    var cosmetic: Boolean = true

    private val queue = ArrayDeque<Clip>()
    private var current: Clip? = null
    private var started = false
    private var elapsed = 0f
    private val background = ArrayList<Pair<Clip, FloatArray>>()

    /** True while sequential clips are pending; the battle flow waits for this before advancing. */
    val busy: Boolean get() = current != null || queue.isNotEmpty()
    val currentName: String? get() = current?.name
    /** Number of clips completed, for tests. */
    var completed = 0
        private set

    fun enqueue(c: Clip) { queue.addLast(c); if (current == null) startNext() }

    /** A clip that runs alongside the timeline (idle bobbing, sparkles) and never blocks progress. */
    fun spawn(c: Clip) { c.onStart(); background.add(c to floatArrayOf(0f)) }

    private fun effectiveDuration(c: Clip): Float = when {
        !cosmetic && !c.essential -> 0f
        !cosmetic -> c.duration * 0.5f / speed.coerceAtLeast(0.01f)
        else -> c.duration / speed.coerceAtLeast(0.01f)
    }

    private fun startNext() {
        current = queue.removeFirstOrNull()
        started = false
        elapsed = 0f
    }

    /** Advances by [dt] seconds of real time. Zero-length clips finish in the same call. */
    fun update(dt: Float) {
        var left = dt
        var guard = 0
        while (current != null && guard++ < 256) {
            val c = current!!
            if (!started) { started = true; c.onStart() }
            val d = effectiveDuration(c)
            elapsed += left
            if (elapsed < d) { c.onUpdate((elapsed / d).coerceIn(0f, 1f)); break }
            left = elapsed - d
            finish(c)
            startNext()
            if (left <= 0f && current != null && effectiveDuration(current!!) > 0f) break
        }
        val it = background.iterator()
        while (it.hasNext()) {
            val (c, t) = it.next()
            t[0] += dt
            val d = effectiveDuration(c)
            if (d <= 0f || t[0] >= d) { c.onUpdate(1f); c.onEnd(); it.remove() } else c.onUpdate(t[0] / d)
        }
    }

    private fun finish(c: Clip) { c.onUpdate(1f); c.onEnd(); completed++ }

    /** Completes every pending clip immediately, applying their end states in order. */
    fun skipAll() {
        var guard = 0
        while (current != null && guard++ < 10_000) {
            val c = current!!
            if (!started) { started = true; c.onStart() }
            finish(c)
            startNext()
        }
        for ((c, _) in background) { c.onUpdate(1f); c.onEnd() }
        background.clear()
    }

    fun clear() { queue.clear(); current = null; background.clear() }
}

object Ease {
    fun outQuad(t: Float) = 1f - (1f - t) * (1f - t)
    fun inQuad(t: Float) = t * t
    fun inOutQuad(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2
    /** 0 -> 1 -> 0. */
    fun pulse(t: Float) = kotlin.math.sin(t * Math.PI).toFloat()
}
