package pkmn.game.gfx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import pkmn.engine.data.Dex
import pkmn.engine.util.toId
import java.io.File

/** Animated or static tile from the world tileset. */
class TileGfx(val frames: Array<TextureRegion>, val frameMs: Int) {
    fun frame(timeMs: Long): TextureRegion = if (frames.size == 1) frames[0] else frames[((timeMs / frameMs) % frames.size).toInt()]
}

/**
 * A map tile: [bottom] and [top] are its two layers; [type] says which of ground / objects / overhang each one is
 * (see [MetatileType]). Existing single-layer art has no [top].
 */
class Metatile(val type: MetatileType, val bottom: TileGfx, val top: TileGfx?) {
    fun layer(l: MapLayer): TileGfx? = when (l) {
        type.bottom -> bottom
        type.top -> top
        else -> null
    }
}

/**
 * Loads and caches all textures. Every lookup has a generated fallback so missing art never crashes the game
 * (missing assets are logged once and reported by the asset manifest tool).
 */
class Assets(private val importedDir: File?, private val dex: Dex?) {
    private val textures = HashMap<String, Texture?>()
    private val missing = HashSet<String>()
    private val generated = ArrayList<Texture>()
    /** Pixmaps behind [generated] textures: kept so they can be re-uploaded after a GL context loss (see [managedTexture]). */
    private val retainedPixmaps = ArrayList<Pixmap>()

    val whitePixel: TextureRegion = TextureRegion(solidTexture(Color.WHITE))
    val font8: PixelFont = PixelFont.load("fonts/pixel8") ?: PixelFont.fallback(this)
    val font6: PixelFont = PixelFont.load("fonts/pixel6") ?: font8

    val frame: TextureRegion? = region("gfx/ui/frame.png")
    val frameAlt: TextureRegion? = region("gfx/ui/frame_alt.png") ?: frame
    private val frameSplits = HashMap<Boolean, Array<TextureRegion>>()
    val cursor: TextureRegion? = region("gfx/ui/cursor.png")
    val downArrow: TextureRegion? = region("gfx/ui/down_arrow.png")

    // ------------------------------------------------------------------ tiles

    private val metatiles = HashMap<String, Metatile>()
    private val tiles = HashMap<String, TileGfx>()
    private val tileFallback = HashMap<String, TileGfx>()

    init { loadMetatiles() }

    /**
     * Reads the 8x8 tile sheet and the metatile table, and bakes every distinct 16x16 layer into one atlas so a map
     * tile is still one draw per layer. Flipped and shared 8x8 tiles are resolved here (see [MetatileTable.compose]).
     */
    private fun loadMetatiles() {
        val png = file("gfx/tiles/world.png") ?: return
        val meta = file("gfx/tiles/world.json") ?: return
        val table = MetatileTable.parse(meta.readString())
        val src = Pixmap(png).let { p -> if (p.format == Pixmap.Format.RGBA8888) p else Pixmap(p.width, p.height, Pixmap.Format.RGBA8888).also { it.drawPixmap(p, 0, 0); p.dispose() } }
        val sheet = IntArray(src.width * src.height) { src.getPixel(it % src.width, it / src.width) }
        val sheetWidth = src.width
        src.dispose()
        val size = table.metatileSize
        val unique = LinkedHashMap<String, IntArray>()
        fun key(cells: IntArray) = cells.joinToString(",")
        for (d in table.metatiles.values) {
            d.bottomFrames.forEach { unique.getOrPut(key(it)) { it } }
            d.top?.let { unique.getOrPut(key(it)) { it } }
        }
        val cols = 16
        val atlas = Pixmap(cols * size, ((unique.size + cols - 1) / cols).coerceAtLeast(1) * size, Pixmap.Format.RGBA8888)
        atlas.blending = Pixmap.Blending.None
        val slots = HashMap<String, Int>()
        for ((k, cells) in unique) {
            val slot = slots.size
            slots[k] = slot
            val px = MetatileTable.compose(sheet, sheetWidth, table.columns, table.tileSize, cells)
            for (i in px.indices) atlas.drawPixel((slot % cols) * size + i % size, (slot / cols) * size + i / size, px[i])
        }
        val texture = managedTexture(atlas, retainedPixmaps).also { generated.add(it); it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        fun region(cells: IntArray): TextureRegion { val slot = slots.getValue(key(cells)); return TextureRegion(texture, (slot % cols) * size, (slot / cols) * size, size, size) }
        for ((name, d) in table.metatiles) {
            val ground = TileGfx(d.bottomFrames.map { region(it) }.toTypedArray(), d.frameMs)
            val top = d.top?.let { TileGfx(arrayOf(region(it)), d.frameMs) }
            metatiles[name] = Metatile(d.type, ground, top)
            tiles[name] = ground
        }
    }

    /** The layered metatile for a map tile name, or a flat ground-only fallback tile when the name is unknown. */
    fun metatile(name: String): Metatile = metatiles[name] ?: metatiles[name.substringBefore('@')] ?: Metatile(MetatileType.NORMAL, tile(name), null)

    fun tile(name: String): TileGfx = tiles[name] ?: tiles[name.substringBefore('@')] ?: tileFallback.getOrPut(name) {
        noteMissing("tile:$name")
        TileGfx(arrayOf(TextureRegion(generatedTile(name))), 250)
    }

    // ------------------------------------------------------------------ characters / objects / trainers

    private val charFrames = HashMap<String, Array<Array<TextureRegion>>>()

    /**
     * Character sheet frames [direction][frame]; directions down, up, left, right; frames stand, stepA, stepB.
     * Sheet formats (art/chars.py): 48x96 = 3x3 cells of 16x32 and 96x96 = 3x3 cells of 32x32 (rows down, up, left; the
     * right row is the left row mirrored here); legacy 48x64 = 4x3 cells of 16x16; anything else is a single 16x16 object.
     */
    fun character(id: String): Array<Array<TextureRegion>> = charFrames.getOrPut(id) {
        val t = texture("gfx/chars/$id.png") ?: texture("gfx/objects/$id.png")
        if (t != null && t.width % 3 == 0 && t.height == 96 && (t.width == 48 || t.width == 96)) {
            val cw = t.width / 3
            val rows = Array(3) { d -> Array(3) { f -> TextureRegion(t, f * cw, d * 32, cw, 32) } }
            val right = Array(3) { f -> TextureRegion(rows[2][f]).also { it.flip(true, false) } }
            arrayOf(rows[0], rows[1], rows[2], right)
        } else if (t != null && t.width >= 48 && t.height >= 64) {
            Array(4) { d -> Array(3) { f -> TextureRegion(t, f * 16, d * 16, 16, 16) } }
        } else if (t != null) {
            val r = TextureRegion(t, 0, 0, minOf(16, t.width), minOf(16, t.height))
            Array(4) { Array(3) { r } }
        } else {
            noteMissing("char:$id")
            val g = TextureRegion(generatedCharacter(id))
            Array(4) { Array(3) { g } }
        }
    }

    fun trainerPortrait(id: String?): TextureRegion? = id?.let { region("gfx/trainers/$it.png") }

    fun ui(name: String): TextureRegion? = region("gfx/ui/$name.png")

    /**
     * Central ball item id -> icon mapping: `gfx/balls/<item id>.png` (16x16, generated by art/balls.py; a test checks
     * that every isPokeball item has one). Unknown ids fall back to the plain Poké Ball icon.
     */
    fun ball(itemId: String?): TextureRegion? = ballIcons.getOrPut(itemId ?: "") {
        (itemId?.let { region("gfx/balls/$it.png") }) ?: ui("pokeball_icon") ?: return null
    }
    private val ballIcons = HashMap<String, TextureRegion>()

    /** Gym badge art (24x24) for badge number 1..8, or null if missing. */
    fun badge(number: Int): TextureRegion? = badgeIcons.getOrPut(number) { region("gfx/badges/badge_$number.png") ?: return null }
    private val badgeIcons = HashMap<Int, TextureRegion>()

    fun frameParts(alt: Boolean): Array<TextureRegion> = frameSplits.getOrPut(alt) {
        val f = (if (alt) frameAlt else frame)!!
        val c = f.regionWidth / 3
        Array(9) { i -> TextureRegion(f, (i % 3) * c, (i / 3) * c, c, c) }
    }

    // ------------------------------------------------------------------ Pokémon sprites

    private val monSprites = HashMap<String, TextureRegion>()

    /** Party/menu icon: bundled icons are a 32x(32*n) sheet of stacked frames; [frame] picks one (wrapping). Placeholders are single-frame. */
    fun icon(speciesId: String, frame: Int = 0): TextureRegion {
        val sheet = pokemon(speciesId, "icon")
        val w = sheet.regionWidth; val h = sheet.regionHeight
        if (h <= w) return sheet
        val n = h / w
        return TextureRegion(sheet.texture, sheet.regionX, sheet.regionY + Math.floorMod(frame, n) * w, w, w)
    }

    /** kind: front, back, front-shiny, back-shiny, icon. */
    fun pokemon(speciesId: String, kind: String = "front"): TextureRegion = monSprites.getOrPut("$kind/$speciesId") {
        val candidates = buildList {
            add(speciesId)
            dex?.speciesOrNull(speciesId)?.let { sp ->
                sp.changesFrom?.let { add(it) }
                if (sp.formKind == pkmn.engine.data.FormKind.GIGANTAMAX || sp.formKind == pkmn.engine.data.FormKind.TOTEM) add(toId(sp.baseSpecies))
            }
        }
        for (id in candidates) {
            for (path in listOf("sprites/pokemon/$kind/$id.png", "gfx/pokemon/$kind/$id.png")) {
                importedTexture(path)?.let { return@getOrPut TextureRegion(it) }
                texture(path, quiet = true)?.let { return@getOrPut TextureRegion(it) }
            }
            if (kind.endsWith("shiny")) return@getOrPut pokemon(speciesId, kind.removeSuffix("-shiny"))
        }
        noteMissing("pokemon:$kind/$speciesId")
        TextureRegion(PlaceholderSprites.generate(speciesId, kind, dex, retainedPixmaps).also { generated.add(it) })
    }

    fun hasOfficialSprites(): Boolean = importedDir?.let { File(it, "sprites/pokemon/front").isDirectory } == true

    // ------------------------------------------------------------------ low level

    fun file(path: String): FileHandle? = Gdx.files.internal(path).takeIf { it.exists() }

    fun texture(path: String, quiet: Boolean = false): Texture? = textures.getOrPut(path) {
        val f = Gdx.files.internal(path)
        if (!f.exists()) { if (!quiet) noteMissing(path); null } else Texture(f).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
    }

    private fun importedTexture(path: String): Texture? {
        val dir = importedDir ?: return null
        val key = "imported:$path"
        if (textures.containsKey(key)) return textures[key]
        val f = File(dir, path)
        val t = if (f.isFile) try {
            Texture(Gdx.files.absolute(f.absolutePath)).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        } catch (e: Exception) { null } else null
        textures[key] = t
        return t
    }

    fun region(path: String): TextureRegion? = texture(path)?.let { TextureRegion(it) }

    private fun noteMissing(what: String) { if (missing.add(what)) Gdx.app?.log("Assets", "missing $what (using placeholder)") }

    fun missingAssets(): Set<String> = missing

    private fun solidTexture(c: Color): Texture {
        val p = Pixmap(1, 1, Pixmap.Format.RGBA8888); p.setColor(c); p.fill()
        return managedTexture(p, retainedPixmaps).also { generated.add(it) }
    }

    private fun generatedTile(name: String): Texture {
        val p = Pixmap(16, 16, Pixmap.Format.RGBA8888)
        val b = pkmn.engine.world.TileRules.behavior(name)
        val c = when (b) {
            pkmn.engine.world.TileBehavior.TALL_GRASS -> Color.valueOf("3c8c38")
            pkmn.engine.world.TileBehavior.WATER -> Color.valueOf("4878d0")
            pkmn.engine.world.TileBehavior.SOLID -> if (name.startsWith("tree")) Color.valueOf("206020") else Color.valueOf("806850")
            pkmn.engine.world.TileBehavior.WARP -> Color.valueOf("503020")
            else -> if (name.contains("floor") || name.contains("path") || name.contains("sand")) Color.valueOf("d8c8a0") else Color.valueOf("88c070")
        }
        p.setColor(c); p.fill()
        p.setColor(c.cpy().mul(0.8f, 0.8f, 0.8f, 1f)); p.drawRectangle(0, 0, 16, 16)
        return managedTexture(p, retainedPixmaps).also { generated.add(it) }
    }

    private fun generatedCharacter(id: String): Texture {
        val p = Pixmap(16, 16, Pixmap.Format.RGBA8888)
        val h = id.hashCode()
        p.setColor(Color(((h shr 16) and 255) / 255f, ((h shr 8) and 255) / 255f, (h and 255) / 255f, 1f))
        p.fillCircle(8, 9, 6)
        p.setColor(Color.valueOf("f8d8b0")); p.fillCircle(8, 5, 4)
        p.setColor(Color.BLACK); p.drawCircle(8, 5, 4)
        return managedTexture(p, retainedPixmaps).also { generated.add(it) }
    }

    fun dispose() {
        textures.values.forEach { it?.dispose() }
        generated.forEach { it.dispose() }
        retainedPixmaps.forEach { it.dispose() }
        retainedPixmaps.clear()
    }
}
