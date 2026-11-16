package pkmn.tools

import kotlinx.serialization.json.Json
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.world.CompiledScript
import pkmn.engine.world.FlagTile
import pkmn.engine.world.Instr
import pkmn.engine.world.MapData
import pkmn.engine.world.NpcDef
import pkmn.engine.world.ObstacleDef
import pkmn.engine.world.SignDef
import pkmn.engine.world.TriggerDef
import pkmn.engine.world.Warp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B8a: one failing fixture per placement rule, the Brindle City fixture from the review commit, and the real world against its baseline. */
class WorldPlacementChecksTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile

    private val legend = mapOf('.' to "grass", '#' to "tree", 'W' to "wall_l", 'S' to "statue", 'c' to "counter", 'g' to "tall_grass", 'D' to "door",
        'v' to "ledge_s", '>' to "ledge_e", 's' to "sign", '~' to "water", 'r' to "rock")

    private fun grid(id: String, vararg rows: String, npcs: List<NpcDef> = emptyList(), signs: List<SignDef> = emptyList(),
                     triggers: List<TriggerDef> = emptyList(), warps: List<Warp> = emptyList(), flagTiles: List<FlagTile> = emptyList(),
                     obstacles: List<ObstacleDef> = emptyList()): MapData {
        val palette = ArrayList<String>()
        val ground = ArrayList<Int>()
        for (r in rows) for (ch in r) {
            val t = legend.getValue(ch)
            if (t !in palette) palette.add(t)
            ground.add(palette.indexOf(t))
        }
        return MapData(id, id, rows[0].length, rows.size, "world", palette, ground, npcs = npcs, signs = signs, triggers = triggers, warps = warps,
            flagTiles = flagTiles, obstacles = obstacles)
    }

    private fun npc(id: String, x: Int, y: Int, move: String = "static", face: String = "down", trainer: String? = null, sight: Int = 0) =
        NpcDef(id, x, y, "hiker", facing = face, movement = move, trainer = trainer, sight = sight, text = "hi")

    private fun keys(vararg maps: MapData, scripts: Map<String, CompiledScript> = emptyMap()) =
        WorldPlacementChecks.check(maps.toList(), scripts).map { it.key }

    private fun script(id: String, vararg cmds: Pair<String, List<String>>) =
        CompiledScript(id, "$id.pks", cmds.mapIndexed { i, (n, a) -> Instr.Cmd(n, a, i + 1) })

    // ---- npc / sign enclosed

    @Test
    fun `npc inside a wall block with no open neighbour is flagged`() {
        val m = grid("m", ".....", ".WWW.", ".WWW.", ".WWW.", ".....", npcs = listOf(npc("page", 2, 2, face = "down")))
        assertEquals(listOf("npc.enclosed m 2,2"), keys(m))
    }

    @Test
    fun `npc on a solid tile with an open neighbour, a counter npc and a wanderer are fine`() {
        val m = grid("m", ".....", ".WWW.", ".WWW.", ".WWW.", ".....", npcs = listOf(npc("ball", 2, 1)))
        val counter = grid("c", ".ccc.", ".ccc.", ".ccc.", npcs = listOf(npc("clerk", 2, 1)))
        val wander = grid("w", "WWW", "WWW", "WWW", npcs = listOf(npc("wanderer", 1, 1, move = "wander")))
        assertEquals(emptyList(), keys(m, counter, wander))
    }

    @Test
    fun `a tileif that opens the wall counts as an open neighbour`() {
        val m = grid("m", "WWW", "WWW", "WWW", npcs = listOf(npc("guard", 1, 1)), flagTiles = listOf(FlagTile("open", 1, 0, "grass")))
        assertEquals(emptyList(), keys(m))
    }

    @Test
    fun `sign on a solid tile with no open neighbour is flagged as enclosed`() {
        val w = grid("w", "WWW", "WWW", "WWW", signs = listOf(SignDef(1, 1, "x")))
        assertTrue("sign.enclosed w 1,1" in keys(w), keys(w).toString())
        val open = grid("o", "WWW", "W.W", "WWW", signs = listOf(SignDef(1, 0, "x")))
        assertEquals(emptyList(), keys(open).filter { it.startsWith("sign.enclosed") })
    }

    // ---- bounds

    @Test
    fun `sign and trigger out of bounds are flagged`() {
        val m = grid("m", "...", "...", signs = listOf(SignDef(5, 0, "x")), triggers = listOf(TriggerDef(2, 1, 2, 1, "s")))
        val k = keys(m)
        assertTrue("sign.out-of-bounds m 5,0" in k, k.toString())
        assertTrue("trigger.out-of-bounds m 2,1" in k, k.toString())
        assertEquals(emptyList(), keys(grid("ok", "...", "...", triggers = listOf(TriggerDef(1, 0, 2, 2, "s")))))
    }

    // ---- sign tile

    @Test
    fun `sign on a wall, rock or statue without a sign tile is flagged and the override fixes it`() {
        val m = grid("m", "...", ".S.", "...", signs = listOf(SignDef(1, 1, "x")))
        assertEquals(listOf("sign.no-tile m 1,1"), keys(m))
        val drawn = grid("m", "...", ".s.", "...", signs = listOf(SignDef(1, 1, "x")))
        assertEquals(emptyList(), keys(drawn))
        val onGrass = grid("g", "...", "...", signs = listOf(SignDef(1, 1, "x")))
        assertEquals(emptyList(), keys(onGrass))
    }

    // ---- trainer sight

    @Test
    fun `trainer facing a wall or one open tile is flagged, two open tiles are fine`() {
        val facingWall = grid("a", ".....", ".....", "WWWWW", npcs = listOf(npc("t", 2, 1, trainer = "t", sight = 4)))
        val oneTile = grid("b", ".....", ".....", ".....", "WWWWW", npcs = listOf(npc("t", 2, 1, trainer = "t", sight = 4)))
        val twoTiles = grid("c", ".....", ".....", ".....", ".....", "WWWWW", npcs = listOf(npc("t", 2, 1, trainer = "t", sight = 4)))
        assertEquals(listOf("trainer.short-sight a 2,1"), keys(facingWall))
        assertEquals(listOf("trainer.short-sight b 2,1"), keys(oneTile))
        assertEquals(emptyList(), keys(twoTiles))
    }

    @Test
    fun `trainer line is cut by an obstacle, but water and wanderers and sight 0 are not flagged`() {
        val boulder = grid("a", ".....", ".....", ".....", ".....", npcs = listOf(npc("t", 2, 0, trainer = "t", sight = 4)),
            obstacles = listOf(ObstacleDef(2, 2, "boulder", "f")))
        assertEquals(listOf("trainer.short-sight a 2,0"), keys(boulder))
        val water = grid("w", ".....", "~~~~~", "~~~~~", "~~~~~", npcs = listOf(npc("t", 2, 0, trainer = "t", sight = 4)))
        val wander = grid("x", "..", "WW", npcs = listOf(npc("t", 0, 0, move = "wander", trainer = "t", sight = 4)))
        val calm = grid("y", "..", "WW", npcs = listOf(npc("t", 0, 0, trainer = "t", sight = 0)))
        assertEquals(emptyList(), keys(water, wander, calm))
    }

    // ---- warp arrival

    @Test
    fun `warp that lands on a wall, in tall grass or away from the door back is flagged`() {
        val town = grid("town", "..D..", ".....", warps = listOf(Warp(2, 0, "room", 1, 1), Warp(0, 1, "room", 0, 0), Warp(4, 1, "cave", 0, 0)))
        val room = grid("room", "WWW", ".D.", ".g.", warps = listOf(Warp(1, 1, "town", 2, 1)))
        val cave = grid("cave", "g..", "...", warps = emptyList())
        val k = keys(town, room, cave)
        assertTrue(k.any { it.startsWith("warp.arrival town 0,1->room") }, "wall: $k")
        assertTrue(k.any { it.startsWith("warp.arrival town 4,1->cave") }, "tall grass: $k")
        assertEquals(2, k.count { it.startsWith("warp.arrival") }, k.toString())
    }

    @Test
    fun `warp landing diagonal to the door back is flagged`() {
        val deck = grid("deck", "...D", ".....", warps = listOf(Warp(3, 0, "hold", 2, 1)))
        val hold = grid("hold", "...D", ".....", ".....", warps = listOf(Warp(3, 0, "deck", 2, 0)))
        assertEquals(listOf("warp.arrival deck 3,0->hold"), keys(deck, hold))
        val fixed = deck.copy(warps = listOf(Warp(3, 0, "hold", 2, 0)))
        assertEquals(emptyList(), keys(fixed, hold))
    }

    // ---- ledge

    @Test
    fun `ledge with a solid tile above or below is flagged`() {
        val deadSource = grid("a", "WWW", ".v.", "...")
        val deadLanding = grid("b", "...", ".v.", "WWW")
        val good = grid("c", "...", ".v.", "...")
        val side = grid("d", "W>.", "...", "...")
        assertEquals(listOf("ledge.dead a 1,1"), keys(deadSource))
        assertEquals(listOf("ledge.dead b 1,1"), keys(deadLanding))
        assertEquals(emptyList(), keys(good))
        assertEquals(listOf("ledge.dead d 1,0"), keys(side))
    }

    // ---- choice

    @Test
    fun `choice without a cancel last option that gives something or sets a flag is flagged`() {
        val gift = script("gift", "choice" to listOf("Which?", "A", "B"), "giveitem" to listOf("potion"))
        val flag = script("flag", "choice" to listOf("Which?", "A", "B"), "setflag" to listOf("done"))
        val safe = script("safe", "choice" to listOf("Which?", "A", "B", "Not yet"), "giveitem" to listOf("potion"))
        val no = script("no", "choice" to listOf("Sure?", "Yes", "No"), "setflag" to listOf("done"))
        val harmless = script("harmless", "choice" to listOf("Which?", "A", "B"), "say" to listOf("ok"))
        val scripts = listOf(gift, flag, safe, no, harmless).associateBy { it.id }
        assertEquals(listOf("choice.no-cancel flag line 1", "choice.no-cancel gift line 1"), keys(scripts = scripts))
    }

    // ---- fixture: Brindle City as it was reviewed (95042ac)

    @Test
    fun `brindle city from the review commit fails on the notebook page npc`() {
        val text = javaClass.getResourceAsStream("/placement/brindle_city_95042ac.json")!!.bufferedReader().readText()
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(MapData.serializer(), text)
        val page = m.npcs.single { it.id == "p5b_page" }
        assertEquals(13 to 4, page.x to page.y)
        assertTrue("npc.enclosed brindle_city 13,4" in keys(m), keys(m).toString())
    }

    // ---- baseline and the real world

    @Test
    fun `baseline parsing ignores comments and stale entries are reported`() {
        val b = WorldPlacementChecks.parseBaseline("# header\n\nledge.dead a 1,1  # why\nnpc.enclosed m 2,2\n")
        assertEquals(setOf("ledge.dead a 1,1", "npc.enclosed m 2,2"), b)
        val v = WorldPlacementChecks.check(listOf(grid("a", "WWW", ".v.", "...")), emptyMap())
        assertEquals(setOf("npc.enclosed m 2,2"), WorldPlacementChecks.stale(b, v))
    }

    @Test
    fun `the real world has no placement violation outside the baseline and no stale baseline entry`() {
        val content = WorldContent(DirectoryContentSource(File(repo, "assets")))
        val maps = content.index.maps.map { content.map(it) }
        val violations = WorldPlacementChecks.check(maps, content.scripts)
        val baseline = WorldPlacementChecks.loadBaseline(File(repo, WorldPlacementChecks.BASELINE_PATH))
        val fresh = violations.filter { it.key !in baseline }
        assertEquals(emptyList(), fresh.map { it.toString() }, "new violations: fix the map (or, for a known issue, never add to the baseline)")
        assertEquals(emptySet(), WorldPlacementChecks.stale(baseline, violations), "stale baseline lines: remove them (the baseline only shrinks)")
    }
}

/** B8a: `activatable` reaches two tiles only across a counter, never through a wall. */
class ActivatableAcrossCounterTest {
    private fun map(vararg rows: String): MapData {
        val legend = mapOf('.' to "grass", 'W' to "wall_l", 'c' to "counter")
        val palette = ArrayList<String>(); val ground = ArrayList<Int>()
        for (r in rows) for (ch in r) { val t = legend.getValue(ch); if (t !in palette) palette.add(t); ground.add(palette.indexOf(t)) }
        return MapData("m", "m", rows[0].length, rows.size, "world", palette, ground)
    }

    private fun reach(vararg at: Pair<Int, Int>) =
        WorldAnalysis.Reach(at.map { WorldAnalysis.Node("m", it.first, it.second) }.toSet(), emptyMap(), emptyMap(), emptyMap())

    private val ref = WorldAnalysis.ScriptRef("m", "s", "npc", 2, 1)

    @Test
    fun `an npc behind a wall cannot be talked to from the other side`() {
        val wall = map(".....", "..WW.", ".....")
        // The NPC at 2,1 stands in the wall; 4,1 is two tiles away with wall tile 3,1 in between.
        assertFalse(WorldAnalysis.activatableFrom(ref, reach(4 to 1), wall))
        assertTrue(WorldAnalysis.activatableFrom(ref, reach(1 to 1), wall))
    }

    @Test
    fun `an npc behind a counter can be talked to across it`() {
        val m = map(".....", "..cc.", ".....")
        assertTrue(WorldAnalysis.activatableFrom(ref, reach(4 to 1), m))
    }
}
