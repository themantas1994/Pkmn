package pkmn.tools

import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.world.MapData
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression tests on the compiled maps for the B7 findings (W5-1, W4-1, W3-1, W2-1). */
class WorldMapFixesTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val content by lazy { WorldContent(DirectoryContentSource(File(repo, "assets"))) }
    private fun map(id: String): MapData = content.map(id)

    private fun beh(m: MapData, x: Int, y: Int) = TileRules.behavior(m.tileAt(x, y))

    /** Tiles a player on foot can stand on from [starts], with [blocked] tiles treated as solid. Boulders count as pushed away. */
    private fun walk(m: MapData, starts: List<Pair<Int, Int>>, blocked: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
        val seen = HashSet<Pair<Int, Int>>()
        val queue = ArrayDeque(starts)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val (x, y) = p
            if (!m.inBounds(x, y) || p in seen || p in blocked) continue
            val b = beh(m, x, y)
            if (b == TileBehavior.WATER) continue
            if (TileRules.isSolid(b) && b != TileBehavior.BOULDER && b != TileBehavior.CUT_TREE && b != TileBehavior.ROCK_SMASH) continue
            seen += p
            for ((dx, dy) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) queue += (x + dx) to (y + dy)
        }
        return seen
    }

    @Test
    fun `route 5 south half cannot be reached from the north while the badge gate triggers are solid`() {
        val m = map("route5")
        val gate = m.triggers.filter { it.script == "p2_route5_gate" }
        assertTrue(gate.isNotEmpty())
        val blocked = HashSet<Pair<Int, Int>>()
        for (t in gate) for (dx in 0 until t.w) for (dy in 0 until t.h) blocked += (t.x + dx) to (t.y + dy)
        for (n in m.npcs) blocked += n.x to n.y
        for (s in m.signs) blocked += s.x to s.y
        val reach = walk(m, listOf(9 to 0, 10 to 0), blocked)
        val gateY = gate.minOf { it.y }
        val leaked = reach.filter { it.second > gateY + 1 }.sortedWith(compareBy({ it.second }, { it.first }))
        assertEquals(emptyList(), leaked.take(5), "a 0-badge player can walk around the Route 5 checkpoint")
    }

    @Test
    fun `lumen catacombs clue sign is drawn as a sign tile and can be read from the corridor`() {
        val m = map("p4a_lumen_catacombs")
        val s = m.signs.single { it.script == "p4a_lc_clue" }
        assertEquals(TileBehavior.SIGN, beh(m, s.x, s.y), "the clue sign sits on a wall tile that is not drawn as a sign")
        val reach = walk(m, listOf(12 to 14), emptySet())
        assertTrue(listOf(s.x + 1 to s.y, s.x to s.y + 1).any { it in reach }, "a floor tile next to the sign is reachable")
    }

    @Test
    fun `crystal cavern ladder room is reachable on foot once the strength boulder is moved`() {
        val m = map("crystal_cavern_1f")
        val reach = walk(m, listOf(13 to 20), emptySet())
        assertTrue((18 to 4) in reach, "ladder down to Geode Hollow")
        assertTrue((24 to 4) in reach, "Tera Shard in the same room")
    }

    @Test
    fun `sight-triggered trainers face an open tile`() {
        for ((mapId, trainer) in listOf("p4b_prism_grotto" to "p4b_grotto_scientist_pryce", "p4c_frozen_falls_depths" to "p4c_ffd_cool_ingrid",
            "p4c_vr_deep_b2" to "p4c_vd2_sage_ondrej")) {
            val m = map(mapId)
            val n = m.npcs.single { it.id == trainer }
            val (dx, dy) = when (n.facing) { "up" -> 0 to -1; "down" -> 0 to 1; "left" -> -1 to 0; else -> 1 to 0 }
            val fx = n.x + dx
            val fy = n.y + dy
            assertTrue(m.inBounds(fx, fy) && !TileRules.isSolid(beh(m, fx, fy)), "$trainer faces ${n.facing} into ${m.tileAt(fx, fy)} at $fx,$fy")
        }
    }
}
