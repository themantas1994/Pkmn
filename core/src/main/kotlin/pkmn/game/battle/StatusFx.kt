package pkmn.game.battle

import com.badlogic.gdx.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Presentation-only looks for a battler's persistent status (poison, burn, paralysis, sleep, freeze). No rules. */
object StatusFx {
    /** One pixel-sized particle, positions relative to the sprite box (0..1 on both axes, y down). */
    class Spark(val x: Float, val y: Float, val size: Int, val color: Color)

    private val PSN = Color(0.72f, 0.4f, 0.85f, 1f)
    private val BRN = Color(1f, 0.55f, 0.3f, 1f)
    private val PAR = Color(1f, 0.95f, 0.45f, 1f)
    private val SLP = Color(0.6f, 0.65f, 0.85f, 1f)
    private val FRZ = Color(0.6f, 0.9f, 1f, 1f)

    private fun key(status: String?) = if (status == "tox") "psn" else status

    private val WITH_EFFECT = setOf("psn", "brn", "par", "slp", "frz")

    fun hasEffect(status: String?) = key(status) in WITH_EFFECT

    /** Multiplicative sprite tint at [ms]; white (no change) for no status. */
    fun tint(status: String?, ms: Long): Color {
        val t = ms / 1000f
        val pulse = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 0.9f)
        return when (key(status)) {
            "psn" -> Color.WHITE.cpy().lerp(PSN, 0.25f + 0.2f * pulse)
            "brn" -> Color.WHITE.cpy().lerp(BRN, 0.15f + 0.3f * (0.5f + 0.5f * sin(t * 11f)))
            "par" -> if ((ms / 120) % 5 == 0L) PAR.cpy() else Color.WHITE.cpy().lerp(PAR, 0.12f)
            "slp" -> Color.WHITE.cpy().lerp(SLP, 0.3f + 0.1f * pulse)
            "frz" -> Color.WHITE.cpy().lerp(FRZ, 0.55f)
            else -> Color.WHITE.cpy()
        }
    }

    /** Particles at [ms]: poison bubbles and burn embers rise, paralysis sparks jitter, sleep Zs drift, ice glints twinkle. */
    fun sparks(status: String?, ms: Long): List<Spark> {
        val out = ArrayList<Spark>()
        val t = ms / 1000f
        when (key(status)) {
            "psn" -> for (i in 0 until 3) {
                val p = (t * 0.7f + i / 3f) % 1f
                out += Spark(0.3f + 0.2f * i + 0.04f * sin(t * 4f + i), 0.85f - 0.6f * p, 2, PSN.cpy().lerp(Color.WHITE, 0.3f))
            }
            "brn" -> for (i in 0 until 4) {
                val p = (t * 1.6f + i * 0.25f) % 1f
                out += Spark(0.25f + 0.17f * i + 0.03f * sin(t * 9f + i), 0.8f - 0.7f * p, 1, if (i % 2 == 0) Color(1f, 0.85f, 0.2f, 1f) else Color(1f, 0.35f, 0.1f, 1f))
            }
            "par" -> if ((ms / 90) % 2 == 0L) for (i in 0 until 3) {
                val s = ((ms / 90) * 7 + i * 13) % 100 / 100f
                out += Spark(0.15f + 0.7f * s, 0.2f + 0.6f * (((ms / 90) * 11 + i * 29) % 100 / 100f), 2, PAR)
            }
            "slp" -> {
                val p = (t * 0.5f) % 1f
                out += Spark(0.6f + 0.15f * p, 0.3f - 0.25f * p, 2, Color.WHITE)
            }
            "frz" -> for (i in 0 until 3) {
                val on = abs(sin(t * 3f + i * 2.1f))
                if (on > 0.7f) out += Spark(0.2f + 0.3f * i, 0.25f + 0.2f * ((i * 3) % 4), 2, Color.WHITE)
            }
        }
        return out
    }

    /** Sleep also shows a floating "Z" glyph; kept separate so the text is drawn by the screen. */
    fun showsZ(status: String?) = key(status) == "slp"
}
