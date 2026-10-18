package pkmn.tools

import pkmn.engine.world.MapData
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gyms 5-8 (Seraphine, Ashfall, Frostmere, Dragonspire 1F/2F) use their own themed tiles (art/gym_tiles.py), none from another gym's
 * set, and their leader and every trainer can be reached from the game start (strict model: ice slides, switches and gates count).
 */
class GymMapThemesLateTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile

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

    /** map id -> theme, and the leader npc id of that map (null: no leader there) */
    private val gyms = listOf(
        Triple("seraphine_gym", "mist", "elowen"),
        Triple("ashfall_gym", "ember", "blaise"),
        Triple("frostmere_gym", "frost", "isolde"),
        Triple("dragonspire_gym", "dusk", null),
        Triple("dragonspire_gym_2f", "dusk", "ryuga"),
    )

    private val world by lazy { WorldAnalysis(repo) }

    private fun usedTiles(m: MapData): Set<String> = (m.tilePalette + m.flagTiles.map { it.tile }).toSet()

    @Test fun eachGymUsesAtLeastThreeOfItsOwnThemedTilesAndNoOtherGymsTiles() {
        for ((id, theme, _) in gyms) {
            val used = usedTiles(world.maps.getValue(id))
            val own = themes.getValue(theme).toSet()
            assertTrue((used intersect own).size >= 3, "$id uses only ${used intersect own} of the $theme set")
            for ((other, tiles) in themes) if (other != theme) {
                assertEquals(emptySet(), used intersect tiles.toSet(), "$id uses $other tiles")
            }
        }
    }

    @Test fun themedCollisionKeepsTheRoutesOpen() {
        // lava is solid (routes go over crust floor), crust, path and rune floors walk
        for (t in listOf("lava", "statue_dragon", "pillar_dusk", "lamp_brazier")) assertTrue(TileRules.isSolid(TileRules.behavior(t)), t)
        for (t in listOf("gym_floor_ember_crust", "gym_floor_mist_path", "gym_floor_dusk_rune")) assertEquals(TileBehavior.FLOOR, TileRules.behavior(t), t)
    }

    /** The strict model run from the gym's own front door (the world-wide model does not reach Frostmere or Dragonspire at all). */
    private class FromDoor(w: WorldAnalysis, override val start: WorldAnalysis.Node) : ReachWorld by w

    @Test fun leaderAndEveryTrainerAreReachableFromTheDoor() {
        val front = listOf("seraphine_gym", "ashfall_gym", "frostmere_gym", "dragonspire_gym")
        for (id in front) {
            val entry = world.maps.values.filter { it.id != id }
                .flatMap { m -> m.warps.filter { it.toMap == id }.map { WorldAnalysis.Node(id, it.toX, it.toY) } }.first()
            val reach = StrictReach(FromDoor(world, entry)).reach(world.stage(8).moves, false)
            for ((gymId, _, leader) in gyms.filter { it.first == id || (id == "dragonspire_gym" && it.first == "dragonspire_gym_2f") }) {
                val people = world.maps.getValue(gymId).npcs.filter { it.trainer != null || it.id == leader }
                if (leader != null) assertTrue(people.any { it.id == leader }, "$gymId has leader $leader")
                assertTrue(people.isNotEmpty(), "$gymId has people")
                for (n in people) {
                    val adjacent = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).any { (dx, dy) -> reach.canStand(gymId, n.x + dx, n.y + dy) }
                    assertTrue(adjacent, "$gymId: ${n.id} at ${n.x},${n.y} cannot be reached from the door")
                }
            }
        }
    }
}
