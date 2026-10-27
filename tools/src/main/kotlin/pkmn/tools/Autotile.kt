package pkmn.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Compile-time autotiling (docs/gba-graphics-spec.md §4). A family is declared in `world/autotiles.json`; a map paints the
 * family's `auto` tile (for example `water@auto`) and the compiler picks the metatile from the 8-neighbour mask.
 *
 * Mask bits: N=1 NE=2 E=4 SE=8 S=16 SW=32 W=64 NW=128, set when that neighbour is a family member. A diagonal bit only counts
 * if both adjacent orthogonals are set, which leaves 47 distinct masks.
 */
class Autotile(val name: String, val auto: String, val members: Set<String>, val center: String,
               val edge: Map<String, String>, val outer: Map<String, String>, val inner: Map<String, String>) {

    fun isMember(tile: String) = tile == auto || tile in members

    /** Every tile name this family can produce, for validation against the tile sheet. */
    fun outputs(): Set<String> = setOf(center) + edge.values + outer.values + inner.values

    fun resolve(mask: Int): String {
        val n = mask and N != 0; val e = mask and E != 0; val s = mask and S != 0; val w = mask and W != 0
        val pick = when {
            !n && !w -> outer["nw"]; !n && !e -> outer["ne"]; !s && !w -> outer["sw"]; !s && !e -> outer["se"]
            !n -> edge["n"]; !s -> edge["s"]; !w -> edge["w"]; !e -> edge["e"]
            mask and NW == 0 -> inner["nw"]; mask and NE == 0 -> inner["ne"]
            mask and SW == 0 -> inner["sw"]; mask and SE == 0 -> inner["se"]
            else -> center
        }
        return pick ?: center
    }

    companion object {
        const val N = 1; const val NE = 2; const val E = 4; const val SE = 8
        const val S = 16; const val SW = 32; const val W = 64; const val NW = 128
        private val DIRS = listOf(N to (0 to -1), NE to (1 to -1), E to (1 to 0), SE to (1 to 1), S to (0 to 1), SW to (-1 to 1), W to (-1 to 0), NW to (-1 to -1))

        fun mask(isMember: (Int, Int) -> Boolean, x: Int, y: Int): Int {
            var raw = 0
            for ((bit, d) in DIRS) if (isMember(x + d.first, y + d.second)) raw = raw or bit
            fun both(diag: Int, a: Int, b: Int) = if (raw and diag != 0 && raw and a != 0 && raw and b != 0) diag else 0
            return (raw and (N or E or S or W)) or both(NE, N, E) or both(SE, S, E) or both(SW, S, W) or both(NW, N, W)
        }

        /** The distinct normalised masks (47). */
        fun uniqueMasks(): List<Int> = (0..255).map { m ->
            fun both(diag: Int, a: Int, b: Int) = if (m and diag != 0 && m and a != 0 && m and b != 0) diag else 0
            (m and (N or E or S or W)) or both(NE, N, E) or both(SE, S, E) or both(SW, S, W) or both(NW, N, W)
        }.distinct().sorted()

        fun load(f: File): List<Autotile> {
            if (!f.exists()) return emptyList()
            fun strs(o: kotlinx.serialization.json.JsonElement?) = o?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            return Json.parseToJsonElement(f.readText()).jsonObject["families"]!!.jsonObject.map { (name, v) ->
                val o = v.jsonObject
                Autotile(name, o["auto"]!!.jsonPrimitive.content, o["members"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
                    o["center"]!!.jsonPrimitive.content, strs(o["edge"]), strs(o["outer"]), strs(o["inner"]))
            }
        }
    }
}
