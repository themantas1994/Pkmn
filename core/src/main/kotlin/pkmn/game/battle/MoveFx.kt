package pkmn.game.battle

import com.badlogic.gdx.graphics.Color
import kotlinx.serialization.json.*
import pkmn.engine.data.ContentSource
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import kotlin.math.cos
import kotlin.math.sin

/** Battle animation timings and move-effect styles, loaded from assets/anim/battle.json (defaults if missing). */
class BattleAnimData(
    private val clips: Map<String, Float> = emptyMap(),
    private val styleDurations: Map<String, Float> = emptyMap(),
    private val typeStyles: Map<String, String> = emptyMap(),
    private val specialStyles: Map<String, String> = emptyMap(),
    private val moveStyles: Map<String, String> = emptyMap(),
    private val statusStyle: String = "aura",
    private val statusFoeStyle: String = "rings",
) {
    fun clip(name: String, default: Float): Float = clips[name] ?: default
    fun styleDuration(style: String): Float = styleDurations[style] ?: 0.4f

    /** Effect style for a move that took effect. */
    fun styleFor(moveId: String, type: String, category: String, selfTarget: Boolean): String = moveStyles[moveId] ?: when {
        category == "Status" -> if (selfTarget) statusStyle else statusFoeStyle
        category == "Special" -> specialStyles[type] ?: typeStyles[type] ?: "beam"
        else -> typeStyles[type] ?: "impact"
    }

    companion object {
        val STYLES = setOf("impact", "slash", "burst", "bolt", "beam", "bubbles", "leaves", "shards", "rocks", "quake", "gust", "rings", "sparkle", "wave", "aura", "heal")

        fun parse(text: String?): BattleAnimData {
            if (text == null) return BattleAnimData()
            val o = Json.parseToJsonElement(text).jsonObject
            fun floats(k: String) = o[k]?.jsonObject?.mapValues { it.value.jsonPrimitive.float } ?: emptyMap()
            fun strings(k: String) = o[k]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            return BattleAnimData(floats("clips"), floats("styleDurations"), strings("typeStyles"), strings("specialStyles"), strings("moveStyles"),
                o["statusStyle"]?.jsonPrimitive?.content ?: "aura", o["statusFoeStyle"]?.jsonPrimitive?.content ?: "rings")
        }

        fun load(source: ContentSource): BattleAnimData = try { parse(source.read("anim/battle.json")) } catch (e: Exception) { BattleAnimData() }
    }
}

/** Four-colour effect ramp for a type, derived as in docs/ART_STYLE.md: highlight 75% and light 40% towards white, dark = main x 0.5. */
class TypeRamp(val highlight: Color, val light: Color, val main: Color, val dark: Color) {
    companion object {
        private fun q(v: Float) = (Math.rint(Math.round(v * 255.0) / 8.0).toFloat() * 8f / 255f).coerceIn(0f, 1f)
        private fun mix(c: Color, k: Float) = Color(q(c.r + (248f / 255f - c.r) * k), q(c.g + (248f / 255f - c.g) * k), q(c.b + (248f / 255f - c.b) * k), 1f)
        fun of(main: Color) = TypeRamp(mix(main, 0.75f), mix(main, 0.4f), main, Color(q(main.r * 0.5f), q(main.g * 0.5f), q(main.b * 0.5f), 1f))
    }
}

/** A running move effect: a procedural pixel-particle pattern between the user and the target. */
class MoveFx(val style: String, val color: Color, val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) {
    var t = 0f
    val ramp = TypeRamp.of(color)

    fun draw(r: Renderer) {
        val c = ramp.main
        val light = ramp.light
        val hi = ramp.highlight
        val dark = ramp.dark
        fun dot(x: Float, y: Float, s: Float = 2f, col: Color = c) = r.rect(x - s / 2, y - s / 2, s, s, col)
        val (x, y) = toX to toY
        when (style) {
            "impact" -> {
                val rad = 4 + 14 * t
                for (i in 0 until 8) { val a = i * Math.PI / 4; dot(x + (cos(a) * rad).toFloat(), y + (sin(a) * rad).toFloat(), 3f, if (i % 2 == 0) c else dark) }
                if (t < 0.4f) dot(x, y, 8f * (1 - t), hi)
            }
            "slash" -> for (k in 0 until 3) {
                val len = (28 * (t * 1.6f - k * 0.2f).coerceIn(0f, 1f)).toInt()
                for (i in 0 until len step 2) dot(x - 14 + i + k * 5, y - 14 + i, 2f, if (k == 1) light else dark)
            }
            "burst" -> for (i in 0 until 14) {
                val a = i * 2 * Math.PI / 14 + t * 2
                val rad = 22 * t + (i % 3) * 2
                dot(x + (cos(a) * rad).toFloat(), y + (sin(a) * rad * 0.8).toFloat(), if (i % 2 == 0) 4f else 3f, if (i % 3 == 0) hi else c)
            }
            "bolt" -> {
                var px = x; var py = y - 50f
                val segs = (8 * minOf(1f, t * 1.6f)).toInt()
                for (s in 0 until segs) {
                    val nx = x + (if (s % 2 == 0) 8f else -8f); val ny = py + 7f
                    for (i in 0..6) dot(px + (nx - px) * i / 6, py + (ny - py) * i / 6, 2f, if ((s + i) % 3 == 0) hi else c)
                    px = nx; py = ny
                }
                if (t > 0.6f) for (i in 0 until 6) { val a = i * Math.PI / 3; dot(x + (cos(a) * 12 * t).toFloat(), y + (sin(a) * 12 * t).toFloat(), 3f, c) }
            }
            "beam" -> {
                val k = minOf(1f, t * 1.5f)
                val n = 18
                for (i in 0..(n * k).toInt()) {
                    val f = i / n.toFloat()
                    val bx = fromX + (toX - fromX) * f; val by = fromY + (toY - fromY) * f
                    dot(bx, by, 4f, if (i % 2 == 0) c else light)
                }
            }
            "bubbles", "shards", "leaves", "rocks" -> for (i in 0 until 6) {
                val f = (t * 1.4f - i * 0.08f).coerceIn(0f, 1f)
                if (f <= 0f || f >= 1f) continue
                val wob = sin((f * 6 + i) * 2).toFloat() * 4
                val bx = fromX + (toX - fromX) * f + if (style == "leaves") wob * 2 else 0f
                val by = fromY + (toY - fromY) * f + if (style == "bubbles") wob else 0f
                when (style) {
                    "bubbles" -> { r.outline((bx - 3).toInt(), (by - 3).toInt(), 6, 6, dark); dot(bx, by, 4f, light); dot(bx - 1, by - 1, 1f, hi) }
                    "shards" -> { dot(bx, by, 3f, light); dot(bx + 2, by - 2, 2f, c) }
                    "leaves" -> { dot(bx, by, 4f, c); dot(bx + 2, by + 1, 2f, dark) }
                    else -> { dot(bx, by - (1 - f) * 20, 5f, c); dot(bx + 1, by - (1 - f) * 20 + 1, 2f, dark) }
                }
            }
            "quake" -> for (i in 0 until 10) {
                val bx = x - 40 + i * 9 + sin(t * 30 + i).toFloat() * 2
                val by = y + 18 - Ease4.pulse(((t * 2 + i * 0.1f) % 1f)) * 10
                dot(bx, by, 3f, c)
            }
            "gust" -> for (k in 0 until 3) for (i in 0 until 12) {
                val bx = x - 30 + i * 5 + t * 20
                val by = y - 12 + k * 10 + sin(i * 0.8 + t * 12).toFloat() * 3
                if ((i + k) % 2 == 0) dot(bx, by, 2f, light)
            }
            "rings" -> for (k in 0 until 3) {
                val rad = ((t + k * 0.25f) % 1f) * 26
                for (i in 0 until 16) { val a = i * Math.PI / 8; dot(x + (cos(a) * rad).toFloat(), y + (sin(a) * rad * 0.7).toFloat(), 2f, c) }
            }
            "sparkle", "aura", "heal" -> for (i in 0 until 7) {
                val a = i * 2 * Math.PI / 7 + t * 4
                val rad = if (style == "aura") 18f else 14f + 6 * sin(t * 6 + i).toFloat()
                val sx = x + (cos(a) * rad).toFloat()
                val sy = y + (sin(a) * rad * 0.8).toFloat() - if (style == "heal") t * 16 else 0f
                val col = if (style == "heal") Palette.HP_GREEN else c
                dot(sx, sy, 2f, col); dot(sx - 2, sy, 1f, hi); dot(sx + 2, sy, 1f, hi)
                dot(sx, sy - 2, 1f, hi); dot(sx, sy + 2, 1f, hi)
            }
            "wave" -> {
                val top = y + 24 - 50 * minOf(1f, t * 1.3f)
                for (i in 0 until 26) dot(x - 36 + i * 3, top + sin(i * 0.7 + t * 10).toFloat() * 3, 3f, if (i % 3 == 0) light else c)
            }
            else -> dot(x, y, 6f * (1 - t), c)
        }
    }
}

private object Ease4 { fun pulse(t: Float) = sin(t * Math.PI).toFloat() }
