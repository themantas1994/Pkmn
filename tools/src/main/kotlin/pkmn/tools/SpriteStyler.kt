package pkmn.tools

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Converts locally sourced Pokémon PNGs to the game's GBC-like pixel style: the sprite is cropped to its visible
 * pixels, fitted to a 64x64 battle canvas (bottom-aligned, not enlarged), 32x32 two-frame icon sheets, binary alpha, at most sixteen opaque colours (GBA 4bpp), channels snapped to multiples
 * of 8, optional one-pixel outline in the darkest palette colour.
 *
 * Kotlin port of `sprite_style.py` from the user-supplied "pkmn-sprite-style-kit" (the origin of the algorithm).
 * Differences from the kit: no Pillow dependency (own area-averaging downscale and median cut), a sprite without any
 * opaque pixel is an error instead of an empty image, the output is deterministic byte for byte, and (after reviewing
 * the full roster) sprites are cropped to their content and use a 64-pixel canvas instead of the kit's uncropped 32-pixel
 * grid, which shrank small Pokémon to a few pixels and lost most detail.
 */
object SpriteStyler {
    /** Battle sprites: a 64-pixel canvas as on the GBA (docs/gba-graphics-spec.md P6), drawn without enlargement. */
    const val BATTLE_LOGICAL = 64
    const val ICON_SIZE = 32
    /** Icons are a sheet of [ICON_FRAMES] stacked frames; frame 1 is frame 0 bobbed down one pixel. */
    const val ICON_FRAMES = 2
    const val UPSCALE = 1
    const val ALPHA_THRESHOLD = 112
    const val MAX_COLOURS = 16
    const val RGB_STEP = 8
    /** Refuse absurd inputs (decompression bombs) before decoding them. */
    const val MAX_INPUT_DIMENSION = 4096

    fun logicalSize(kind: String) = if (kind == "icon") ICON_SIZE else BATTLE_LOGICAL
    fun outputWidth(kind: String) = if (kind == "icon") ICON_SIZE else BATTLE_LOGICAL * UPSCALE
    fun outputHeight(kind: String) = if (kind == "icon") ICON_SIZE * ICON_FRAMES else BATTLE_LOGICAL * UPSCALE

    /** Parameters recorded in STYLE_REPORT.json. */
    fun parameters(outline: Boolean): Map<String, Any> = linkedMapOf(
        "battleLogicalCanvas" to "${BATTLE_LOGICAL}x$BATTLE_LOGICAL",
        "battleOutput" to "${BATTLE_LOGICAL * UPSCALE}x${BATTLE_LOGICAL * UPSCALE}",
        "iconOutput" to "${ICON_SIZE}x${ICON_SIZE * ICON_FRAMES} ($ICON_FRAMES frames)",
        "opaqueColorLimit" to MAX_COLOURS,
        "alpha" to "binary, threshold $ALPHA_THRESHOLD/255",
        "rgbStep" to RGB_STEP,
        "downscale" to "area averaging on premultiplied alpha, fit without cropping",
        "quantiser" to "median cut (weighted median split, widest channel), nearest palette colour, no dithering",
        "filter" to "nearest-neighbour enlargement",
        "outline" to outline,
    )

    /** Decodes PNG (or any ImageIO-readable) bytes. Throws [IllegalArgumentException] for corrupt or oversized images. */
    fun decode(bytes: ByteArray): BufferedImage {
        val image = try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) throw IllegalArgumentException("not a readable image")
                val reader = readers.next()
                try {
                    reader.input = stream
                    val w = reader.getWidth(0); val h = reader.getHeight(0)
                    if (w < 1 || h < 1 || w > MAX_INPUT_DIMENSION || h > MAX_INPUT_DIMENSION)
                        throw IllegalArgumentException("unsupported image size ${w}x$h")
                    reader.read(0)
                } finally { reader.dispose() }
            }
        } catch (e: IllegalArgumentException) { throw e
        } catch (e: Exception) { throw IllegalArgumentException("corrupt image: ${e.message ?: e::class.simpleName}") }
        return image ?: throw IllegalArgumentException("not a readable image")
    }

    fun encodePng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", out)) throw IllegalStateException("no PNG writer available")
        return out.toByteArray()
    }

    /** Styles one image. Throws [IllegalArgumentException] when the sprite has no visible pixels. */
    fun style(source: BufferedImage, kind: String, outline: Boolean = true): BufferedImage {
        val size = logicalSize(kind)
        val argb = containOnCanvas(cropToContent(source), size, bottom = kind != "icon")
        val n = size * size
        val opaque = BooleanArray(n) { (argb[it] ushr 24) >= ALPHA_THRESHOLD }
        val firstOpaque = opaque.indexOfFirst { it }
        require(firstOpaque >= 0) { "sprite has no opaque pixels" }

        // Transparent pixels take the colour of one real opaque pixel so they do not invent a palette entry.
        val seed = argb[firstOpaque] and 0xFFFFFF
        val rgb = IntArray(n) { if (opaque[it]) argb[it] and 0xFFFFFF else seed }

        // Quantise first (nearest palette colour, no dithering), then snap each result to the 8-step grid.
        val palette = medianCut(rgb, MAX_COLOURS)
        val mapped = IntArray(n) { snap(nearest(palette, rgb[it])) }

        val visible = LinkedHashSet<Int>()
        for (i in 0 until n) if (opaque[i]) visible += mapped[i]
        if (outline) {
            val darkest = visible.minWith(compareBy<Int>({ luma(it) }, { it }))
            val edge = IntArray(n)
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                if (!opaque[i]) continue
                val isEdge = x == 0 || y == 0 || x == size - 1 || y == size - 1 ||
                    !opaque[i - 1] || !opaque[i + 1] || !opaque[i - size] || !opaque[i + size]
                if (isEdge) edge[i] = 1
            }
            for (i in 0 until n) if (edge[i] == 1) mapped[i] = darkest
        }

        val scale = if (kind == "icon") 1 else UPSCALE
        val out = size * scale
        val frames = if (kind == "icon") ICON_FRAMES else 1
        val result = BufferedImage(out, out * frames, BufferedImage.TYPE_INT_ARGB)
        val row = IntArray(out)
        for (f in 0 until frames) for (y in 0 until out) {
            // Frame 1 of an icon is frame 0 moved down one pixel (the bottom row falls off).
            val sy = y - f
            for (x in 0 until out) {
                val i = if (sy < 0) -1 else (sy / scale) * size + (x / scale)
                row[x] = if (i >= 0 && opaque[i]) (0xFF shl 24) or mapped[i] else 0
            }
            result.setRGB(0, f * out + y, out, 1, row, 0, out)
        }
        return result
    }

    /** Returns an error message, or null when [image] is a valid styled sprite for [kind]. */
    fun validate(image: BufferedImage, kind: String): String? {
        val w = outputWidth(kind); val h = outputHeight(kind)
        if (image.width != w || image.height != h) return "unexpected dimensions ${image.width}x${image.height}, expected ${w}x$h"
        val colours = HashSet<Int>()
        var visible = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            val a = p ushr 24
            if (a != 0 && a != 255) return "alpha is not binary (found $a)"
            if (a == 255) { visible++; colours += p and 0xFFFFFF }
        }
        if (visible == 0) return "image has no opaque pixels"
        if (colours.size > MAX_COLOURS) return "output exceeds $MAX_COLOURS opaque colours (found ${colours.size})"
        return null
    }

    fun opaqueColours(image: BufferedImage): Set<Int> {
        val colours = HashSet<Int>()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            if ((p ushr 24) >= 128) colours += p and 0xFFFFFF
        }
        return colours
    }

    fun snapChannel(v: Int): Int = ((v + 4) / RGB_STEP * RGB_STEP).coerceIn(0, 248)

    private fun snap(c: Int) = (snapChannel(c shr 16 and 255) shl 16) or (snapChannel(c shr 8 and 255) shl 8) or snapChannel(c and 255)

    private fun luma(c: Int) = 0.2126 * (c shr 16 and 255) + 0.7152 * (c shr 8 and 255) + 0.0722 * (c and 255)

    // ------------------------------------------------------------------ downscale

    /**
     * Fits the source (already cropped to its content) into a [size] x [size] canvas: the image is scaled so its longer side
     * equals [size] (area averaging on premultiplied alpha, so transparent pixels never bleed colour) and centred.
     * Returns non-premultiplied ARGB pixels.
     */
    /**
     * Crops [source] to the bounding box of its visible pixels, so a small Pokémon drawn in the
     * middle of a large transparent canvas fills the frame as Gold/Silver/Crystal sprites do.
     */
    fun cropToContent(source: BufferedImage): BufferedImage {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in 0 until source.height) for (x in 0 until source.width) {
            if ((source.getRGB(x, y) ushr 24) >= ALPHA_THRESHOLD) {
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return source
        return source.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun containOnCanvas(source: BufferedImage, size: Int, bottom: Boolean = false): IntArray {
        val sw = source.width; val sh = source.height
        val scale = minOf(size.toDouble() / sw, size.toDouble() / sh)
        val nw = (sw * scale).roundToInt().coerceIn(1, size)
        val nh = (sh * scale).roundToInt().coerceIn(1, size)
        val src = IntArray(sw * sh)
        source.getRGB(0, 0, sw, sh, src, 0, sw)
        val resized = areaResize(src, sw, sh, nw, nh)
        val canvas = IntArray(size * size)
        // Battle sprites stand on the bottom edge (the battle layout puts them on their platform); icons are centred.
        val ox = (size - nw) / 2; val oy = if (bottom) size - nh else (size - nh) / 2
        for (y in 0 until nh) for (x in 0 until nw) canvas[(oy + y) * size + ox + x] = resized[y * nw + x]
        return canvas
    }

    private fun areaResize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        val xs = sw.toDouble() / dw; val ys = sh.toDouble() / dh
        for (dy in 0 until dh) {
            val y0 = dy * ys; val y1 = y0 + ys
            for (dx in 0 until dw) {
                val x0 = dx * xs; val x1 = x0 + xs
                var wSum = 0.0; var aSum = 0.0; var rSum = 0.0; var gSum = 0.0; var bSum = 0.0
                var sy = y0.toInt()
                while (sy < y1 && sy < sh) {
                    val wy = minOf(y1, sy + 1.0) - maxOf(y0, sy.toDouble())
                    var sx = x0.toInt()
                    while (sx < x1 && sx < sw) {
                        val w = wy * (minOf(x1, sx + 1.0) - maxOf(x0, sx.toDouble()))
                        if (w > 0) {
                            val p = src[sy * sw + sx]
                            val a = (p ushr 24).toDouble()
                            wSum += w; aSum += w * a
                            rSum += w * a * (p shr 16 and 255); gSum += w * a * (p shr 8 and 255); bSum += w * a * (p and 255)
                        }
                        sx++
                    }
                    sy++
                }
                if (wSum <= 0 || aSum <= 0) continue
                val a = (aSum / wSum).roundToInt().coerceIn(0, 255)
                val r = (rSum / aSum).roundToInt().coerceIn(0, 255)
                val g = (gSum / aSum).roundToInt().coerceIn(0, 255)
                val b = (bSum / aSum).roundToInt().coerceIn(0, 255)
                out[dy * dw + dx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    // ------------------------------------------------------------------ median cut

    private class Box(val colours: List<Pair<Int, Int>>) { // (rgb, pixel count), sorted deterministically
        val total = colours.sumOf { it.second.toLong() }
        private fun range(shift: Int): Int {
            var lo = 255; var hi = 0
            for ((c, _) in colours) { val v = c shr shift and 255; if (v < lo) lo = v; if (v > hi) hi = v }
            return hi - lo
        }
        /** Channel shift (16 = R, 8 = G, 0 = B) with the widest range; ties prefer green, red, blue. */
        val axis: Int = listOf(8, 16, 0).maxByOrNull { range(it) }!!
        val extent: Int = range(axis)
        fun average(): Int {
            var r = 0L; var g = 0L; var b = 0L
            for ((c, n) in colours) { r += (c shr 16 and 255).toLong() * n; g += (c shr 8 and 255).toLong() * n; b += (c and 255).toLong() * n }
            fun avg(v: Long) = ((v + total / 2) / total).toInt()
            return (avg(r) shl 16) or (avg(g) shl 8) or avg(b)
        }
        fun split(): Pair<Box, Box> {
            val sorted = colours.sortedWith(compareBy<Pair<Int, Int>>({ it.first shr axis and 255 }, { it.first }))
            var acc = 0L; var idx = 0
            for ((i, c) in sorted.withIndex()) { acc += c.second; if (acc * 2 >= total) { idx = i; break } }
            val cut = (idx + 1).coerceIn(1, sorted.size - 1)
            return Box(sorted.subList(0, cut)) to Box(sorted.subList(cut, sorted.size))
        }
    }

    /** Deterministic median cut over the colour histogram of [pixels]; returns at most [maxColours] RGB colours. */
    internal fun medianCut(pixels: IntArray, maxColours: Int): List<Int> {
        val histogram = HashMap<Int, Int>()
        for (p in pixels) histogram.merge(p and 0xFFFFFF, 1, Int::plus)
        val boxes = arrayListOf(Box(histogram.entries.map { it.key to it.value }.sortedBy { it.first }))
        while (boxes.size < maxColours) {
            val candidate = boxes.withIndex().filter { it.value.colours.size > 1 }
                .maxWithOrNull(compareBy<IndexedValue<Box>>({ it.value.extent }, { it.value.total }, { -it.index })) ?: break
            val (a, b) = candidate.value.split()
            boxes[candidate.index] = a
            boxes.add(candidate.index + 1, b)
        }
        return boxes.map { it.average() }
    }

    private fun nearest(palette: List<Int>, c: Int): Int {
        var best = palette[0]; var bestD = Long.MAX_VALUE
        for (p in palette) {
            val dr = (p shr 16 and 255) - (c shr 16 and 255); val dg = (p shr 8 and 255) - (c shr 8 and 255); val db = (p and 255) - (c and 255)
            val d = (dr * dr + dg * dg + db * db).toLong()
            if (d < bestD) { bestD = d; best = p }
        }
        return best
    }
}
