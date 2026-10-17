package pkmn.engine.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntranceTest {
    private fun map(
        id: String, name: String = id, environment: String = "plain", indoor: Boolean = false, music: String = "route_early",
        tags: List<String> = emptyList(), zoneClass: String? = null,
    ) = MapData(id, name, 1, 1, "world", listOf("grass"), listOf(0), music = music, environment = environment, indoor = indoor, tags = tags, zoneClass = zoneClass)

    private val route = map("route1", "Route 1", "grass")
    private val town = map("hometown", "Hollowbrook Town", "plain", tags = listOf("town"))
    private val house = map("home_1f", "Home", "indoor", indoor = true)
    private val cave = map("glimmer_cave", "Glimmer Cave", "cave")
    private val forest = map("whisperwood", "Whisperwood", "grass", music = "forest")

    @Test
    fun kindOfTileOnlyClassifiesWarpTiles() {
        assertEquals(EntranceKind.HOUSE, Entrances.kindOfTile("door"))
        assertEquals(EntranceKind.CENTER, Entrances.kindOfTile("center_door"))
        assertEquals(EntranceKind.MART, Entrances.kindOfTile("mart_door"))
        assertEquals(EntranceKind.GYM, Entrances.kindOfTile("gym_door"))
        assertEquals(EntranceKind.BUILDING, Entrances.kindOfTile("building_door"))
        assertEquals(EntranceKind.CAVE, Entrances.kindOfTile("cave_entrance"))
        assertEquals(EntranceKind.FOREST, Entrances.kindOfTile("forest_entrance"))
        assertEquals(EntranceKind.RUINS, Entrances.kindOfTile("ruins_entrance"))
        assertEquals(EntranceKind.MINE, Entrances.kindOfTile("mine_entrance"))
        assertEquals(EntranceKind.TOWER, Entrances.kindOfTile("tower_door"))
        assertEquals(EntranceKind.GATE, Entrances.kindOfTile("gate_door"))
        assertEquals(EntranceKind.STAIRS, Entrances.kindOfTile("stairs_in_down"))
        assertEquals(EntranceKind.STAIRS, Entrances.kindOfTile("stairs_up"))
        assertEquals(EntranceKind.LADDER, Entrances.kindOfTile("cave_ladder"))
        assertEquals(EntranceKind.EXIT_MAT, Entrances.kindOfTile("rug_mat"))
        // Not warp tiles: walls and scenery are never entrances.
        assertNull(Entrances.kindOfTile("ruins_wall"))
        assertNull(Entrances.kindOfTile("puzzle_wall"))
        assertNull(Entrances.kindOfTile("tree"))
        assertNull(Entrances.kindOfTile("grass"))
    }

    @Test
    fun everyEntranceTileIsAWarpTile() {
        for (t in listOf("door", "building_door", "center_door", "mart_door", "gym_door", "cave_entrance", "forest_entrance", "ruins_entrance",
            "mine_entrance", "tower_door", "gate_door", "stairs_up", "stairs_down", "cave_ladder", "rug_mat")) {
            assertEquals(TileBehavior.WARP, TileRules.behavior(t), t)
            assertFalse(TileRules.isSolid(TileRules.behavior(t)), "$t must be walkable")
        }
    }

    @Test
    fun destinationKindIsInferred() {
        assertEquals(EntranceKind.OTHER, Entrances.destinationKind(route))
        assertEquals(EntranceKind.OTHER, Entrances.destinationKind(town))
        assertEquals(EntranceKind.HOUSE, Entrances.destinationKind(house))
        assertEquals(EntranceKind.CAVE, Entrances.destinationKind(cave))
        assertEquals(EntranceKind.FOREST, Entrances.destinationKind(forest))
        assertEquals(EntranceKind.CENTER, Entrances.destinationKind(map("c", "Pokémon Center", "indoor", indoor = true, tags = listOf("pokecenter"))))
        assertEquals(EntranceKind.MART, Entrances.destinationKind(map("m", "Poké Mart", "indoor", indoor = true, music = "mart")))
        assertEquals(EntranceKind.GYM, Entrances.destinationKind(map("g", "Gym", "building", indoor = true, tags = listOf("gym"))))
        assertEquals(EntranceKind.LAB, Entrances.destinationKind(map("l", "Linden Pokémon Lab", "indoor", indoor = true)))
        assertEquals(EntranceKind.BUILDING, Entrances.destinationKind(map("w", "Harbor Warehouse", "building", indoor = true)))
        assertEquals(EntranceKind.TOWER, Entrances.destinationKind(map("t", "Ember Tower 1F", "building", indoor = true)))
        assertEquals(EntranceKind.MINE, Entrances.destinationKind(map("old_mine_1f", "Old Shaft 1F", "cave")))
        assertEquals(EntranceKind.RUINS, Entrances.destinationKind(map("r", "Sol Catacombs", "cave")))
        assertEquals(EntranceKind.HOUSE, Entrances.destinationKind(map("rr", "Ruins Researcher's House", "indoor", indoor = true)))
    }

    @Test
    fun explicitClassWins() {
        assertEquals(EntranceKind.FOREST, Entrances.destinationKind(map("x", environment = "cave", zoneClass = "forest")))
        assertEquals(EntranceKind.BUILDING, Entrances.destinationKind(map("league", "Pokémon League", "indoor", indoor = true, tags = listOf("pokecenter"), zoneClass = "building")))
        // An unknown class name falls back to inference.
        assertEquals(EntranceKind.CAVE, Entrances.destinationKind(map("x", environment = "cave", zoneClass = "nonsense")))
    }

    @Test
    fun houseDoorsOnlyLeadIntoHouses() {
        assertTrue(Entrances.compatible("door", town, house))
        assertFalse(Entrances.compatible("door", route, forest), "a house door into a forest is the bug this guards against")
        assertFalse(Entrances.compatible("door", route, cave))
        assertFalse(Entrances.compatible("cave_entrance", route, house))
        assertFalse(Entrances.compatible("center_door", town, house))
    }

    @Test
    fun zoneEntrancesMatchTheirDestination() {
        assertTrue(Entrances.compatible("cave_entrance", route, cave))
        assertTrue(Entrances.compatible("forest_entrance", route, forest))
        assertFalse(Entrances.compatible("cave_entrance", route, forest))
        val tower = map("ember_tower_1f", "Ember Tower 1F", "building", indoor = true)
        assertTrue(Entrances.compatible("tower_door", town, tower))
        assertFalse(Entrances.compatible("gym_door", town, tower))
        val ruins = map("r", "Sol Catacombs", "cave")
        assertTrue(Entrances.compatible("ruins_entrance", route, ruins))
        assertFalse(Entrances.compatible("cave_entrance", route, ruins))
        assertFalse(Entrances.compatible("stairs_down", route, ruins), "stairs in the open do not lead into ruins")
        val gym = map("g", "Gym", "building", indoor = true, tags = listOf("gym"))
        assertTrue(Entrances.compatible("gym_door", town, gym))
        assertFalse(Entrances.compatible("door", town, gym))
    }

    @Test
    fun exitsAndInnerTransitions() {
        // Out of a building: a door mat. Out of a cave or forest: the opening the zone is entered by.
        assertTrue(Entrances.compatible("rug_mat", house, town))
        assertTrue(Entrances.compatible("cave_entrance", cave, route))
        assertFalse(Entrances.compatible("rug_mat", cave, route))
        assertTrue(Entrances.compatible("forest_entrance", forest, route))
        assertFalse(Entrances.compatible("rug_mat", forest, route))
        // Inside a complex: stairs and ladders, but never into or out of a forest.
        val cave2 = map("glimmer_cave_b1", "Glimmer Cave B1F", "cave")
        assertTrue(Entrances.compatible("cave_ladder", cave, cave2))
        assertTrue(Entrances.compatible("stairs_in_up", house, map("home_2f", "Home 2F", "indoor", indoor = true)))
        assertFalse(Entrances.compatible("cave_ladder", cave, forest))
        // Forest to forest uses the forest opening, not a cave mouth.
        val forest2 = map("mossgrove", "Mossgrove Thicket", "grass", music = "forest")
        assertTrue(Entrances.compatible("forest_entrance", forest, forest2))
        assertFalse(Entrances.compatible("cave_entrance", forest, forest2))
        assertFalse(Entrances.compatible("rug_mat", forest, forest2))
        // A pass between two open-air maps may be a cave mouth or a gate, never a house door.
        assertTrue(Entrances.compatible("cave_entrance", town, route))
        assertTrue(Entrances.compatible("gate_door", town, route))
        assertFalse(Entrances.compatible("door", town, route))
    }

    @Test
    fun notWarpTilesAreNeverCompatible() {
        assertFalse(Entrances.compatible("ruins_wall", cave, cave))
        assertFalse(Entrances.compatible("grass", route, route))
    }

    @Test
    fun gatedWarpsAreRecognisedByTheirFlagTile() {
        val m = MapData("a", "a", 2, 1, "world", listOf("ruins_wall", "ruins_floor"), listOf(0, 1),
            warps = listOf(Warp(0, 0, "b")), flagTiles = listOf(FlagTile("open", 0, 0, "stairs_down")))
        assertEquals(listOf("stairs_down"), Entrances.warpTilesAt(m, 0, 0))
        assertEquals(emptyList(), Entrances.warpTilesAt(m, 1, 0))
    }

    @Test
    fun zoneClassSurvivesJsonAndDefaultsToNull() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = false; explicitNulls = false }
        val m = map("x", environment = "cave", zoneClass = "ruins")
        assertEquals("ruins", json.decodeFromString(MapData.serializer(), json.encodeToString(MapData.serializer(), m)).zoneClass)
        // Maps compiled before the field existed still load.
        val old = """{"id":"o","name":"o","width":1,"height":1,"tilePalette":["grass"],"ground":[0]}"""
        assertNull(json.decodeFromString(MapData.serializer(), old).zoneClass)
    }
}
