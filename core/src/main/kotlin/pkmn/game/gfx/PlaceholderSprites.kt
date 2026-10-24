package pkmn.game.gfx

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import pkmn.engine.data.Dex

/**
 * Procedural stand-in sprites for species whose official art has not been imported (see docs/ASSET_IMPORT.md).
 * They are deliberately recognisable as placeholders: a pixel silhouette of the species' body plan (see [Silhouettes]) in its type colours
 * with its National Dex number in the corner.
 */
object PlaceholderSprites {
    /** [retained] receives the pixmap, which the caller disposes with the texture (GL context loss safety). */
    fun generate(speciesId: String, kind: String, dex: Dex?, retained: MutableList<Pixmap>): Texture {
        val sp = dex?.speciesOrNull(speciesId)
        val icon = kind == "icon"
        val grid = if (icon) 16 else 32
        val scale = if (icon) 2 else 3
        val size = grid * scale
        val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val c1 = Palette.type(sp?.types?.getOrNull(0) ?: "Normal")
        val c2 = Palette.type(sp?.types?.getOrNull(1) ?: sp?.types?.getOrNull(0) ?: "Normal").cpy().lerp(Color.WHITE, 0.35f)
        val outline = c1.cpy().mul(0.35f, 0.35f, 0.35f, 1f)
        val half = grid / 2
        val mask = Silhouettes.mask(Silhouettes.classify(sp), grid, sp?.heightm ?: 1.0)
        val cy = grid / 2 + 2
        val ry = grid * 0.3
        val back = kind.startsWith("back")
        for (y in 0 until grid) for (x in 0 until grid) {
            if (!mask[y][x]) continue
            val edge = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (a, b) -> a !in 0 until grid || b !in 0 until grid || !mask[b][a] }
            val col = when {
                edge -> outline
                (y + x / 3) % 7 == 0 -> c2
                y > cy + 2 -> c2
                else -> c1
            }
            p.setColor(col)
            p.fillRectangle(x * scale, y * scale, scale, scale)
        }
        if (!back) {
            // Eyes
            p.setColor(Color.WHITE)
            val ey = (cy - ry * 0.3).toInt()
            for (ex in listOf(half - 3, half + 2)) { p.fillRectangle(ex * scale, ey * scale, scale * 2, scale * 2) }
            p.setColor(Color.BLACK)
            for (ex in listOf(half - 2, half + 2)) p.fillRectangle(ex * scale, (ey + 1) * scale, scale, scale)
        }
        if (!icon) drawNumber(p, sp?.num ?: 0, 2, size - 9)
        return managedTexture(p, retained).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
    }

    private val digits = arrayOf("111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
        "111100111001111", "111100111101111", "111001010010010", "111101111101111", "111101111001111")

    /** Tiny 3×5 digits so placeholders show their dex number without needing the font. */
    private fun drawNumber(p: Pixmap, n: Int, x: Int, y: Int) {
        val s = "#$n"
        p.setColor(Color(0f, 0f, 0f, 0.6f)); p.fillRectangle(x - 1, y - 1, s.length * 4 + 1, 7)
        p.setColor(Color.WHITE)
        var cx = x
        for (ch in s) {
            if (ch.isDigit()) {
                val g = digits[ch - '0']
                for (i in 0 until 15) if (g[i] == '1') p.drawPixel(cx + i % 3, y + i / 3)
            } else { p.drawPixel(cx, y + 1); p.drawPixel(cx + 2, y + 1); p.drawPixel(cx, y + 3); p.drawPixel(cx + 2, y + 3) }
            cx += 4
        }
    }
}
