package pkmn.tools

import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.world.MapData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoulderPuzzlesTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val maps: Map<String, MapData> by lazy {
        val c = WorldContent(DirectoryContentSource(File(repo, "assets")))
        c.index.maps.associateWith { c.map(it) }
    }

    @Test
    fun `no boulder push can permanently lose a route or reward in the bundled maps`() {
        val reports = BoulderPuzzles(maps).analyzeAll()
        assertTrue(reports.size >= 15, "all boulder maps are analysed: ${reports.map { it.map }}")
        assertEquals(emptyList(), reports.flatMap { r -> r.softlocks.map { "${r.map}: lost ${it.lost} after ${it.pushes}" } })
    }

    @Test
    fun `two-boulder maps are tagged to reset on entry`() {
        // Persistent pushes in these maps can lose a warp or reward for good (found by the exhaustive search; the other
        // boulder maps are proven safe with persistence by pushing rules alone), so each visit must start fresh.
        for (id in listOf("mt_prism_1f", "mt_prism_2f", "victory_road_2f")) assertTrue("boulder_reset" in maps.getValue(id).tags, id)
    }

    private fun room(tags: List<String> = emptyList()): Map<String, MapData> {
        // 5x5 with a 3x3 floor; entrance door at (0,2), a second door at (4,3) reachable only from the corner (3,3); boulder in the centre.
        val ground = MutableList(25) { 1 }
        for (y in 1..3) for (x in 1..3) ground[y * 5 + x] = 0
        val m = MapData(id = "t", name = "t", width = 5, height = 5, tilePalette = listOf("cave_floor", "cave_wall"), ground = ground, tags = tags,
            warps = listOf(pkmn.engine.world.Warp(0, 2, "o", 1, 1), pkmn.engine.world.Warp(4, 3, "o", 1, 1)),
            obstacles = listOf(pkmn.engine.world.ObstacleDef(2, 2, "boulder", "b")))
        val o = MapData(id = "o", name = "o", width = 3, height = 3, tilePalette = listOf("cave_floor"), ground = List(9) { 0 },
            warps = listOf(pkmn.engine.world.Warp(0, 1, "t", 1, 2)))
        return mapOf("t" to m, "o" to o)
    }

    @Test
    fun `a boulder pushed into the corner in front of a door is a persistent softlock`() {
        val maps = room()
        val r = BoulderPuzzles(maps).analyze(maps.getValue("t"))
        assertTrue(r.softlocks.any { "warp 4,3" in it.lost }, r.softlocks.joinToString { it.lost.toString() })
    }

    @Test
    fun `reset-on-entry maps are checked per visit and a push that walls the player in is refused`() {
        val maps = room(listOf("boulder_reset"))
        val r = BoulderPuzzles(maps).analyze(maps.getValue("t"))
        assertEquals(emptyList(), r.softlocks.map { it.lost })
        // Boulder in the doorway column: pushing it so the player is boxed away from every door is not allowed.
        val m = maps.getValue("t")
        assertTrue(pkmn.engine.world.BoulderRules.reachesExit(m, setOf(2 * 4096 + 2), 1, 2))
        assertTrue(!pkmn.engine.world.BoulderRules.reachesExit(m, setOf(1 * 4096 + 2, 1 * 4096 + 1, 1 * 4096 + 3, 3 * 4096 + 3), 2, 2), "both doors covered")
    }
}
