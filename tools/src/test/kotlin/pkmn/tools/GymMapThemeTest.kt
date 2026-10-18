package pkmn.tools

import pkmn.engine.world.FieldMove
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Gyms 1-4 use their own themed tiles (art/gym_tiles.py), none from another gym's set, and the leader and every trainer can
 * still be reached from the door (strict reachability with the gym's own switch/gate flags, no field moves).
 */
class GymMapThemeTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val world by lazy { WorldAnalysis(repo) }

    private val sets = mapOf(
        "sprout" to setOf("gym_floor_sprout", "gym_floor_sprout_stones", "gym_wall_hedge", "puzzle_wall_hedge", "bush_blossom", "plant_sprout", "statue_sprout"),
        "tide" to setOf("gym_floor_tide", "gym_wall_tide", "dock_plank", "dock_plank_v", "float_platform", "pillar_mooring", "puzzle_wall_crate", "statue_tide"),
        "spark" to setOf("gym_floor_spark", "circuit_panel", "circuit_cable", "gym_wall_spark", "machine_generator", "machine_coil", "puzzle_wall_spark"),
        "stone" to setOf("gym_floor_stone", "gym_wall_stone", "rock_ore", "rock_rubble", "rail_h", "rail_v", "lamp_mine", "puzzle_wall_stone"),
        "mist" to setOf("gym_floor_mist", "gym_floor_mist_deep", "gym_floor_mist_path", "gym_wall_mist", "statue_mist", "plant_mist", "puzzle_wall_mist"),
        "ember" to setOf("gym_floor_ember", "gym_floor_ember_hot", "gym_wall_ember", "lava", "gym_floor_ember_crust", "lamp_brazier", "puzzle_wall_ember"),
        "frost" to setOf("gym_floor_frost", "gym_floor_frost_cracked", "gym_floor_frost_snow", "gym_wall_frost", "pillar_ice", "statue_frost", "puzzle_wall_frost"),
        "dusk" to setOf("gym_floor_dusk", "gym_floor_dusk_rune", "gym_wall_dusk", "pillar_dusk", "statue_dragon", "lamp_dusk", "puzzle_wall_dusk"),
    )
    private val gyms = mapOf("brindle_gym" to "sprout", "tidewater_gym" to "tide", "voltara_gym" to "spark", "quarrytown_gym" to "stone")
    private val leaders = mapOf("brindle_gym" to "hazel", "tidewater_gym" to "marina", "voltara_gym" to "volta", "quarrytown_gym" to "garnet")

    @Test fun gymsUseOnlyTheirOwnThemedTiles() {
        for ((id, theme) in gyms) {
            val m = world.maps.getValue(id)
            val used = (m.tilePalette + m.flagTiles.map { it.tile }).toSet()
            val own = used intersect sets.getValue(theme)
            assertTrue(own.size >= 3, "$id uses only ${own.size} $theme tiles: $own")
            for ((other, tiles) in sets) if (other != theme) {
                val foreign = used intersect tiles
                assertTrue(foreign.isEmpty(), "$id uses $other tiles $foreign")
            }
        }
    }

    @Test fun leaderAndTrainersStayReachableFromTheDoor() {
        for (id in gyms.keys) {
            val m = world.maps.getValue(id)
            val door = m.warps.first()
            val gymWorld = object : ReachWorld by world {
                override val start = WorldAnalysis.Node(id, door.x, door.y)
            }
            val res = StrictReach(gymWorld).reach(emptySet<FieldMove>(), false)
            val targets = m.npcs.filter { it.trainer != null || it.id == leaders.getValue(id) }
            assertTrue(targets.any { it.id == leaders.getValue(id) }, "$id: leader ${leaders.getValue(id)} missing")
            assertTrue(m.npcs.count { it.trainer != null } >= 3, "$id: trainers missing")
            for (n in targets) {
                val adjacent = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).any { (dx, dy) -> res.canStand(id, n.x + dx, n.y + dy) }
                assertTrue(adjacent, "$id: ${n.id} at ${n.x},${n.y} cannot be reached from the door")
            }
        }
    }
}
