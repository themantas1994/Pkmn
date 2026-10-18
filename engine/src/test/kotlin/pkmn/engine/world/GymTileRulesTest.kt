package pkmn.engine.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The themed gym tiles (art/gym_tiles.py) get the right collision from their names alone: no engine mechanic is involved. */
class GymTileRulesTest {
    private val solid = listOf(
        "gym_wall_hedge", "puzzle_wall_hedge", "bush_blossom", "plant_sprout", "statue_sprout",
        "gym_wall_tide", "pillar_mooring", "puzzle_wall_crate", "statue_tide",
        "gym_wall_spark", "machine_generator", "machine_coil", "puzzle_wall_spark",
        "gym_wall_stone", "rock_ore", "rock_rubble", "lamp_mine", "puzzle_wall_stone",
        "gym_wall_mist", "statue_mist", "plant_mist", "puzzle_wall_mist",
        "gym_wall_ember", "lava", "lamp_brazier", "puzzle_wall_ember",
        "gym_wall_frost", "pillar_ice", "statue_frost", "puzzle_wall_frost",
        "gym_wall_dusk", "pillar_dusk", "statue_dragon", "lamp_dusk", "puzzle_wall_dusk",
    )
    private val walkable = listOf(
        "gym_floor_sprout", "gym_floor_sprout_stones", "gym_floor_tide", "dock_plank", "dock_plank_v", "float_platform",
        "gym_floor_spark", "circuit_panel", "circuit_cable", "gym_floor_stone", "rail_h", "rail_v",
        "gym_floor_mist", "gym_floor_mist_deep", "gym_floor_mist_path", "gym_floor_ember", "gym_floor_ember_hot", "gym_floor_ember_crust",
        "gym_floor_frost", "gym_floor_frost_cracked", "gym_floor_frost_snow", "gym_floor_dusk", "gym_floor_dusk_rune",
    )

    @Test fun solidGymTilesBlock() {
        for (t in solid) assertTrue(TileRules.isSolid(TileRules.behavior(t)), "$t must be solid")
    }

    @Test fun floorGymTilesAreWalkablePlainFloor() {
        for (t in walkable) assertEquals(TileBehavior.FLOOR, TileRules.behavior(t), "$t must be plain walkable floor")
    }

    @Test fun lavaIsNotWaterAndIceKeepsItsOnlyTile() {
        assertFalse(TileRules.behavior("lava") == TileBehavior.WATER)
        assertEquals(TileBehavior.ICE, TileRules.behavior("ice"))
        assertEquals(TileBehavior.FLOOR, TileRules.behavior("gym_floor_frost_cracked"))
    }
}
