package pkmn.engine.campaign

import java.time.LocalTime

/** Multiplicative overworld lighting as RGB in 0..1, interpolated linearly in time between phase keyframes. */
object LightTint {
    class Rgb(val r: Float, val g: Float, val b: Float)

    private val NIGHT = Rgb(0.58f, 0.62f, 0.86f)
    private val MORNING = Rgb(1f, 0.96f, 0.88f)
    private val DAY = Rgb(1f, 1f, 1f)
    private val EVENING = Rgb(1f, 0.84f, 0.72f)

    /** Minute of day to colour, ascending; the last key wraps to the first (04:00 of the next day). */
    private val keys: List<Pair<Int, Rgb>> = listOf(
        4 * 60 to NIGHT, 5 * 60 to MORNING, 9 * 60 to MORNING, 10 * 60 to DAY,
        16 * 60 + 30 to DAY, 17 * 60 to EVENING, 18 * 60 to EVENING, 19 * 60 to NIGHT,
    )

    fun at(t: LocalTime): Rgb {
        val m = t.toSecondOfDay() / 60f
        val day = 24 * 60f
        val shifted = if (m < keys.first().first) m + day else m
        for (i in keys.indices) {
            val (m0, c0) = keys[i]
            val (m1, c1) = if (i + 1 < keys.size) keys[i + 1] else (keys.first().first + day.toInt()) to keys.first().second
            if (shifted >= m0 && shifted < m1) {
                val f = (shifted - m0) / (m1 - m0)
                return Rgb(c0.r + (c1.r - c0.r) * f, c0.g + (c1.g - c0.g) * f, c0.b + (c1.b - c0.b) * f)
            }
        }
        return NIGHT
    }
}
