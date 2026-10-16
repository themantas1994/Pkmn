package pkmn.game.gfx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Monospace bitmap font from a glyph grid (white glyphs, tinted at draw time). */
class PixelFont(private val glyphs: Map<Char, TextureRegion>, val cellW: Int, val cellH: Int) {
    val lineHeight get() = cellH

    fun glyph(c: Char): TextureRegion? = glyphs[c]

    fun width(s: String): Int = s.count { it != '\n' } * cellW

    fun draw(r: Renderer, s: String, x: Int, y: Int, color: Color): Int {
        var cx = x
        var cy = y
        r.tint(color) {
            for (ch in s) {
                if (ch == '\n') { cx = x; cy += cellH; continue }
                val g = glyphs[ch] ?: glyphs[mapFallback(ch)] ?: glyphs['?']
                if (g != null && ch != ' ') r.draw(g, cx, cy)
                cx += cellW
            }
        }
        return cx - x
    }

    private fun mapFallback(c: Char): Char = when (c) {
        'é', 'è', 'ê' -> 'é'; 'É' -> 'É'; '’', '‘' -> '\''; '“', '”' -> '"'; '–', '—' -> '-'
        else -> c
    }

    /** Splits text into lines no wider than [maxChars] characters, respecting explicit newlines. */
    fun wrap(text: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = StringBuilder()
            for (word in para.split(' ')) {
                if (line.isEmpty()) line.append(word)
                else if (line.length + 1 + word.length <= maxChars) line.append(' ').append(word)
                else { out.add(line.toString()); line = StringBuilder(word) }
                while (line.length > maxChars) { out.add(line.substring(0, maxChars)); line = StringBuilder(line.substring(maxChars)) }
            }
            out.add(line.toString())
        }
        return out
    }

    companion object {
        /** Loads `<base>.json` + the white variant texture (falls back to the base png). */
        fun load(base: String): PixelFont? {
            val meta = Gdx.files.internal("$base.json")
            if (!meta.exists()) return null
            val o = Json.parseToJsonElement(meta.readString()).jsonObject
            val white = Gdx.files.internal("${base}_white.png").takeIf { it.exists() } ?: Gdx.files.internal("$base.png")
            if (!white.exists()) return null
            val tex = Texture(white).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
            val cw = o["cellW"]!!.jsonPrimitive.int
            val ch = o["cellH"]!!.jsonPrimitive.int
            val cols = o["columns"]!!.jsonPrimitive.int
            val chars = o["chars"]!!.jsonPrimitive.content
            val map = HashMap<Char, TextureRegion>()
            chars.forEachIndexed { i, c -> map[c] = TextureRegion(tex, (i % cols) * cw, (i / cols) * ch, cw, ch) }
            return PixelFont(map, cw, ch)
        }

        /** Minimal generated font used only if the font asset is missing (draws boxes for glyphs). */
        fun fallback(assets: Assets): PixelFont {
            val map = HashMap<Char, TextureRegion>()
            for (c in ' '..'~') map[c] = TextureRegion(assets.whitePixel.texture, 0, 0, 1, 1)
            return PixelFont(map, 8, 8)
        }
    }
}
