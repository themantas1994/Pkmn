package pkmn.game.gfx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The three map draw layers, in draw order: overhang is drawn above the player (docs/gba-graphics-spec.md section 4). */
enum class MapLayer { GROUND, OBJECTS, OVERHANG }

/**
 * Which two of the three layers a metatile holds: its `ground` cells and its `top` cells map to
 * NORMAL = ground + objects, COVERED = ground + overhang, SPLIT = objects + overhang.
 */
enum class MetatileType(val json: String, val bottom: MapLayer, val top: MapLayer) {
    NORMAL("normal", MapLayer.GROUND, MapLayer.OBJECTS),
    COVERED("covered", MapLayer.GROUND, MapLayer.OVERHANG),
    SPLIT("split", MapLayer.OBJECTS, MapLayer.OVERHANG);

    companion object { fun parse(s: String): MetatileType = entries.firstOrNull { it.json == s } ?: error("unknown metatile type '$s'") }
}

/**
 * One row of the `world.json` metatile table. A layer is four 8x8 tile references (top-left, top-right, bottom-left,
 * bottom-right); [EMPTY] is a transparent cell. [bottomFrames] has more than one entry for animated metatiles.
 */
class MetatileDef(val type: MetatileType, val bottomFrames: List<IntArray>, val top: IntArray?, val frameMs: Int, val palette: String?)

class MetatileTable(val tileSize: Int, val metatileSize: Int, val columns: Int, val metatiles: Map<String, MetatileDef>) {
    companion object {
        const val EMPTY = -1
        const val FLIP_H = 0x400
        const val FLIP_V = 0x800
        const val INDEX_MASK = 0x3FF

        fun parse(text: String): MetatileTable {
            val root = Json.parseToJsonElement(text).jsonObject
            fun cells(a: JsonArray): IntArray = IntArray(a.size) { a[it].jsonPrimitive.int }.also { require(it.size == 4) { "a layer needs four 8x8 cells" } }
            val defs = LinkedHashMap<String, MetatileDef>()
            for ((name, v) in root["metatiles"]!!.jsonObject) {
                val o = v.jsonObject
                val frames = o["frames"]?.jsonArray?.map { cells(it.jsonArray) } ?: listOf(cells(o["ground"]!!.jsonArray))
                defs[name] = MetatileDef(
                    MetatileType.parse(o["type"]?.jsonPrimitive?.content ?: "normal"), frames, o["top"]?.jsonArray?.let { cells(it) },
                    o["frameMs"]?.jsonPrimitive?.intOrNull ?: 250, o["palette"]?.jsonPrimitive?.content,
                )
            }
            return MetatileTable(root["tileSize"]?.jsonPrimitive?.int ?: 8, root["metatileSize"]?.jsonPrimitive?.int ?: 16, root["columns"]!!.jsonPrimitive.int, defs)
        }

        /**
         * Composes one metatile layer from the tile sheet (packed pixels, [sheetWidth] wide) into a size x size pixel array;
         * EMPTY cells stay 0 (transparent). Flip bits mirror the 8x8 cell, as in GBA tilemap entries.
         */
        fun compose(sheet: IntArray, sheetWidth: Int, columns: Int, tileSize: Int, cells: IntArray): IntArray {
            val size = tileSize * 2
            val out = IntArray(size * size)
            for (c in 0 until 4) {
                val ref = cells[c]
                if (ref == EMPTY) continue
                val sx = (ref and INDEX_MASK) % columns * tileSize
                val sy = (ref and INDEX_MASK) / columns * tileSize
                val dx = (c % 2) * tileSize
                val dy = (c / 2) * tileSize
                for (y in 0 until tileSize) for (x in 0 until tileSize) {
                    val fx = if (ref and FLIP_H != 0) tileSize - 1 - x else x
                    val fy = if (ref and FLIP_V != 0) tileSize - 1 - y else y
                    out[(dy + y) * size + dx + x] = sheet[(sy + fy) * sheetWidth + sx + fx]
                }
            }
            return out
        }
    }
}
