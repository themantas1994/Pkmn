package pkmn.game.gfx

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * Drawing helpers in the game's logical coordinate space (256×224, origin top-left, y down).
 * Wraps a SpriteBatch whose projection is y-up over the logical screen.
 */
class Renderer(val batch: SpriteBatch, val assets: Assets) {
    val width = W
    val height = H
    private val white: TextureRegion = assets.whitePixel

    fun draw(region: TextureRegion, x: Float, y: Float, w: Float = region.regionWidth.toFloat(), h: Float = region.regionHeight.toFloat()) {
        batch.draw(region, x, H - y - h, w, h)
    }

    fun draw(region: TextureRegion, x: Int, y: Int) = draw(region, x.toFloat(), y.toFloat())

    fun drawFlipped(region: TextureRegion, x: Float, y: Float, flipX: Boolean) {
        val w = region.regionWidth.toFloat(); val h = region.regionHeight.toFloat()
        if (flipX) batch.draw(region, x + w, H - y - h, -w, h) else batch.draw(region, x, H - y - h, w, h)
    }

    fun drawTexture(t: Texture, x: Float, y: Float, w: Float = t.width.toFloat(), h: Float = t.height.toFloat()) {
        batch.draw(t, x, H - y - h, w, h)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Color) {
        val old = batch.packedColor
        batch.color = color
        batch.draw(white, x, H - y - h, w, h)
        batch.packedColor = old
    }

    fun rect(x: Int, y: Int, w: Int, h: Int, color: Color) = rect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), color)

    fun outline(x: Int, y: Int, w: Int, h: Int, color: Color) {
        rect(x, y, w, 1, color); rect(x, y + h - 1, w, 1, color); rect(x, y, 1, h, color); rect(x + w - 1, y, 1, h, color)
    }

    fun tint(color: Color, block: () -> Unit) {
        val old = batch.packedColor
        batch.color = color
        block()
        batch.packedColor = old
    }

    /** Draws text with the pixel font. Returns the width drawn. */
    fun text(s: String, x: Int, y: Int, color: Color = Palette.TEXT, small: Boolean = false): Int =
        (if (small) assets.font6 else assets.font8).draw(this, s, x, y, color)

    fun textWidth(s: String, small: Boolean = false) = (if (small) assets.font6 else assets.font8).width(s)

    /** GSC-style framed window using the 9-slice frame texture (or a drawn fallback). */
    fun frame(x: Int, y: Int, w: Int, h: Int, alt: Boolean = false) {
        val f = if (alt) assets.frameAlt else assets.frame
        if (f == null) {
            rect(x, y, w, h, Palette.WINDOW)
            outline(x, y, w, h, Palette.TEXT)
            outline(x + 2, y + 2, w - 4, h - 4, Palette.TEXT)
            return
        }
        val c = f.regionWidth / 3
        val parts = assets.frameParts(alt)
        // corners
        draw(parts[0], x.toFloat(), y.toFloat()); draw(parts[2], (x + w - c).toFloat(), y.toFloat())
        draw(parts[6], x.toFloat(), (y + h - c).toFloat()); draw(parts[8], (x + w - c).toFloat(), (y + h - c).toFloat())
        // edges
        draw(parts[1], (x + c).toFloat(), y.toFloat(), (w - 2 * c).toFloat(), c.toFloat())
        draw(parts[7], (x + c).toFloat(), (y + h - c).toFloat(), (w - 2 * c).toFloat(), c.toFloat())
        draw(parts[3], x.toFloat(), (y + c).toFloat(), c.toFloat(), (h - 2 * c).toFloat())
        draw(parts[5], (x + w - c).toFloat(), (y + c).toFloat(), c.toFloat(), (h - 2 * c).toFloat())
        draw(parts[4], (x + c).toFloat(), (y + c).toFloat(), (w - 2 * c).toFloat(), (h - 2 * c).toFloat())
    }

    fun cursor(x: Int, y: Int) {
        val c = assets.cursor
        if (c != null) draw(c, x, y) else text("▶", x, y)
    }

    companion object {
        const val W = 256
        const val H = 224
    }
}

/** GBC-inspired colour palette used by UI code. */
object Palette {
    val TEXT = Color.valueOf("181010")
    val WINDOW = Color.valueOf("f8f8f0")
    val SHADE = Color.valueOf("a8a8a0")
    val HP_GREEN = Color.valueOf("38b848")
    val HP_YELLOW = Color.valueOf("f8c020")
    val HP_RED = Color.valueOf("e83820")
    val EXP_BLUE = Color.valueOf("3890f8")
    val BLACK = Color.valueOf("000000")
    val WHITE = Color.valueOf("ffffff")
    val NIGHT = Color(0.10f, 0.12f, 0.35f, 0.38f)
    val DUSK = Color(0.55f, 0.25f, 0.20f, 0.18f)
    val HIGHLIGHT = Color.valueOf("f8e8a8")
    val RED = Color.valueOf("d83020")
    val BLUE = Color.valueOf("3060d8")
    val GREY = Color.valueOf("787878")

    val TYPE: Map<String, Color> = mapOf(
        "Normal" to "a8a878", "Fire" to "f08030", "Water" to "6890f0", "Electric" to "f8d030", "Grass" to "78c850", "Ice" to "98d8d8",
        "Fighting" to "c03028", "Poison" to "a040a0", "Ground" to "e0c068", "Flying" to "a890f0", "Psychic" to "f85888", "Bug" to "a8b820",
        "Rock" to "b8a038", "Ghost" to "705898", "Dragon" to "7038f8", "Dark" to "705848", "Steel" to "b8b8d0", "Fairy" to "ee99ac",
        "Stellar" to "40b5a5", "???" to "68a090",
    ).mapValues { Color.valueOf(it.value) }

    fun type(t: String): Color = TYPE[t] ?: GREY
}
