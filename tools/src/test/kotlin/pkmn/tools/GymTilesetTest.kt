package pkmn.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every gym has its own non-empty themed tile set (art/gym_tiles.py); indices are in range and the tile images are pairwise distinct. */
class GymTilesetTest {
    private val assets = File(System.getProperty("pkmn.assets"))

    private val themes = mapOf(
        "sprout" to listOf("gym_floor_sprout", "gym_floor_sprout_stones", "gym_wall_hedge", "puzzle_wall_hedge", "bush_blossom", "plant_sprout", "statue_sprout"),
        "tide" to listOf("gym_floor_tide", "gym_wall_tide", "dock_plank", "dock_plank_v", "float_platform", "pillar_mooring", "puzzle_wall_crate", "statue_tide"),
        "spark" to listOf("gym_floor_spark", "circuit_panel", "circuit_cable", "gym_wall_spark", "machine_generator", "machine_coil", "puzzle_wall_spark"),
        "stone" to listOf("gym_floor_stone", "gym_wall_stone", "rock_ore", "rock_rubble", "rail_h", "rail_v", "lamp_mine", "puzzle_wall_stone"),
        "mist" to listOf("gym_floor_mist", "gym_floor_mist_deep", "gym_floor_mist_path", "gym_wall_mist", "statue_mist", "plant_mist", "puzzle_wall_mist"),
        "ember" to listOf("gym_floor_ember", "gym_floor_ember_hot", "gym_wall_ember", "lava", "gym_floor_ember_crust", "lamp_brazier", "puzzle_wall_ember"),
        "frost" to listOf("gym_floor_frost", "gym_floor_frost_cracked", "gym_floor_frost_snow", "gym_wall_frost", "pillar_ice", "statue_frost", "puzzle_wall_frost"),
        "dusk" to listOf("gym_floor_dusk", "gym_floor_dusk_rune", "gym_wall_dusk", "pillar_dusk", "statue_dragon", "lamp_dusk", "puzzle_wall_dusk"),
    )

    @Test fun everyGymHasADistinctInRangeTileSet() {
        val meta = Json.parseToJsonElement(File(assets, "gfx/tiles/world.json").readText()).jsonObject
        val columns = meta["columns"]!!.jsonPrimitive.int
        assertEquals(16, columns)
        val sheet = ImageIO.read(File(assets, "gfx/tiles/world.png"))
        val rows = sheet.height / 16
        val tiles = meta["tiles"]!!.jsonObject
        fun frames(name: String): List<Int> {
            val e = tiles[name]!!.jsonObject
            return e["frames"]?.jsonArray?.map { it.jsonPrimitive.int } ?: listOf(e["index"]!!.jsonPrimitive.int)
        }
        fun pixels(i: Int): List<Int> {
            val x = (i % columns) * 16
            val y = (i / columns) * 16
            return (0 until 256).map { sheet.getRGB(x + it % 16, y + it / 16) }
        }
        assertEquals(8, themes.size)
        val everyPixelsByTile = HashMap<List<Int>, String>()
        for ((theme, names) in themes) {
            assertTrue(names.size in 5..8, "$theme: ${names.size} tiles")
            for (n in names) {
                assertTrue(n in tiles, "$theme: tile $n missing from world.json")
                for (i in frames(n)) assertTrue(i in 0 until rows * columns, "$theme/$n index $i out of range (sheet has ${rows * columns})")
                val px = pixels(frames(n).first())
                val colours = px.toSet()
                assertTrue(colours.size <= 4, "$theme/$n uses ${colours.size} colours")
                everyPixelsByTile.put(px, n)?.let { other -> throw AssertionError("$theme: tile $n is identical to $other") }
            }
        }
        assertEquals(4, frames("lava").size, "lava animates in 4 frames")
        assertEquals(4, frames("lava").map { pixels(it) }.toSet().size, "lava frames must differ")
    }
}
