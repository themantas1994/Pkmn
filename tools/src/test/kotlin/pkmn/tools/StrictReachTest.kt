package pkmn.tools

import pkmn.engine.world.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The strict reachability model against hand-built maps, one rule of OverworldRules / OverworldScreen at a time. */
class StrictReachTest {
    private val legend = mapOf(
        '.' to "grass", '#' to "tree", 'i' to "ice", '~' to "water", 'v' to "ledge_s", 'D' to "door", 'f' to "floor_wood", 'T' to "tall_grass",
        'c' to "cave_floor", 'W' to "cave_wall",
    )

    private fun map(id: String, vararg rows: String, extra: (MapData) -> MapData = { it }): MapData {
        val palette = ArrayList<String>()
        val ground = ArrayList<Int>()
        for (r in rows) for (ch in r) {
            val t = legend.getValue(ch)
            if (t !in palette) palette.add(t)
            ground.add(palette.indexOf(t))
        }
        return extra(MapData(id, id, rows[0].length, rows.size, "world", palette, ground))
    }

    private class World(override val maps: Map<String, MapData>, override val start: WorldAnalysis.Node,
                        override val scriptRefs: List<WorldAnalysis.ScriptRef> = emptyList(),
                        private val scripts: Map<String, List<Instr.Cmd>> = emptyMap()) : ReachWorld {
        override fun commands(scriptId: String) = scripts[scriptId].orEmpty()
        override fun connectionTarget(m: MapData, nx: Int, ny: Int, dx: Int, dy: Int): WorldAnalysis.Node? = null
        override fun activatable(r: WorldAnalysis.ScriptRef, reach: WorldAnalysis.Reach) =
            listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0).any { (dx, dy) -> WorldAnalysis.Node(r.map, r.x + dx, r.y + dy) in reach.nodes }
    }

    private fun world(vararg maps: MapData, start: WorldAnalysis.Node = WorldAnalysis.Node(maps[0].id, 0, 0), scriptRefs: List<WorldAnalysis.ScriptRef> = emptyList(),
                      scripts: Map<String, List<Instr.Cmd>> = emptyMap()) = World(maps.associateBy { it.id }, start, scriptRefs, scripts)

    private fun StrictReach.Result.at(map: String, x: Int, y: Int) = canStand(map, x, y)

    private fun reach(w: World, moves: Set<FieldMove> = emptySet(), gone: Boolean = false) = StrictReach(w).reach(moves, gone)

    @Test
    fun walksAroundSolidTilesAndStopsAtEnclosures() {
        val m = map("a", "..#..", "..#..", "..#..")
        val r = reach(world(m))
        assertTrue(r.at("a", 1, 2))
        assertFalse(r.at("a", 3, 0))
    }

    @Test
    fun iceSlidesUntilBlocked() {
        val m = map("a", ".iii#", "#####")
        val r = reach(world(m))
        assertTrue(r.at("a", 0, 0))
        assertTrue(r.at("a", 3, 0), "slide ends on the last ice tile before the wall")
        assertFalse(r.at("a", 1, 0), "ice tiles mid-slide are never stood on")
        assertFalse(r.at("a", 2, 0))
    }

    @Test
    fun iceSlideKeepsGoingOntoPlainGroundAndStopsThere() {
        val m = map("a", ".ii..#", "######")
        val r = reach(world(m))
        assertTrue(r.at("a", 3, 0), "the slide ends on the first non-ice tile")
        assertFalse(r.at("a", 1, 0))
    }

    @Test
    fun iceSlideStopsInFrontOfASolidNpc() {
        val base = map("a", ".iiii.", "######")
        val m = base.copy(npcs = listOf(NpcDef("guard", 4, 0, "x", movement = "static")))
        val r = reach(world(m))
        assertTrue(r.at("a", 3, 0))
        assertFalse(r.at("a", 5, 0), "the guard blocks the way past the ice")
    }

    @Test
    fun ledgesAreOneWay() {
        val m = map("a", "#.#", "#v#", "#.#", "#.#")
        // From the north the player hops down over the ledge; from the south it cannot be climbed.
        assertTrue(reach(world(m)).at("a", 1, 2))
        val up = reach(world(m, start = WorldAnalysis.Node("a", 1, 3)))
        assertTrue(up.at("a", 1, 2))
        assertFalse(up.at("a", 1, 0))
    }

    @Test
    fun surfNeedsTheFieldMove() {
        val m = map("a", ".~.", ".~.")
        assertFalse(reach(world(m)).at("a", 2, 0))
        assertTrue(reach(world(m), setOf(FieldMove.SURF)).at("a", 2, 0))
    }

    @Test
    fun staticNpcsBlockCorridorsUnlessPossiblyGone() {
        val base = map("a", "...#.", "##.#.", "##...")
        val guard = NpcDef("guard", 2, 1, "x", movement = "static")
        val blocked = base.copy(npcs = listOf(guard))
        assertFalse(reach(world(blocked)).at("a", 4, 2), "a static NPC in a one-tile corridor is solid")
        // A flag that no script sets leaves the NPC solid; the optimistic scenario removes it.
        val flagged = base.copy(npcs = listOf(guard.copy(hideIf = "never_set")))
        assertFalse(reach(world(flagged)).at("a", 4, 2))
        assertTrue(reach(world(flagged), gone = true).at("a", 4, 2))
        // Wandering NPCs are not solid.
        val wanderer = base.copy(npcs = listOf(guard.copy(movement = "wander")))
        assertTrue(reach(world(wanderer)).at("a", 4, 2))
    }

    @Test
    fun npcRemovedByAReachableScriptStopsBlocking() {
        val base = map("a", "...#.", "##.#.", "##...")
        val m = base.copy(
            npcs = listOf(NpcDef("guard", 2, 1, "x", movement = "static", script = "guard_script", hideIf = "guard_gone")),
        )
        val refs = listOf(WorldAnalysis.ScriptRef("a", "guard_script", "npc", 2, 1))
        val scripts = mapOf("guard_script" to listOf(Instr.Cmd("setflag", listOf("guard_gone"), 1)))
        assertTrue(reach(world(m, scriptRefs = refs, scripts = scripts)).at("a", 4, 2))
    }

    @Test
    fun obstaclesNeedTheirFieldMove() {
        val m = map("a", "...", "...").copy(obstacles = listOf(ObstacleDef(1, 0, "cut_tree", "f1"), ObstacleDef(1, 1, "cut_tree", "f2")))
        assertFalse(reach(world(m)).at("a", 2, 0))
        assertTrue(reach(world(m), setOf(FieldMove.CUT)).at("a", 2, 0))
    }

    @Test
    fun boulderCanBePushedOnlyWhenTheTileBehindIsFree() {
        val free = map("a", "....").copy(obstacles = listOf(ObstacleDef(1, 0, "boulder", "b1")))
        assertFalse(reach(world(free)).at("a", 2, 0))
        assertTrue(reach(world(free), setOf(FieldMove.STRENGTH)).at("a", 2, 0))
        val wedged = map("a", "..#.").copy(obstacles = listOf(ObstacleDef(1, 0, "boulder", "b1")))
        assertFalse(reach(world(wedged), setOf(FieldMove.STRENGTH)).at("a", 3, 0), "a boulder against a wall cannot move")
    }

    @Test
    fun warpsLeadToTheLandingTileAndBackReturnsToTheDoor() {
        val outside = map("out", "...D.", ".....").copy(warps = listOf(Warp(3, 0, "house", 1, 1)))
        val house = map("house", "fff", "fff").copy(warps = listOf(Warp(1, 1, Warp.BACK)))
        val r = reach(World(mapOf("out" to outside, "house" to house), WorldAnalysis.Node("out", 0, 0)))
        assertTrue(r.at("house", 1, 1), "arrives on the landing tile")
        assertTrue(r.at("house", 2, 0))
        assertTrue(r.at("out", 3, 0), "@back puts the player back on the door tile (OverworldScreen.loadMap stores the position of the warp)")
        assertTrue(r.at("out", 4, 0))
    }

    @Test
    fun flagTilesOnlyOpenOnceTheirFlagCanBeSet() {
        val wall = map("a", "..#.")
        val gated = wall.copy(flagTiles = listOf(FlagTile("opened", 2, 0, "grass")), npcs = listOf(NpcDef("opener", 0, 0, "x", script = "opener_script")))
        val refs = listOf(WorldAnalysis.ScriptRef("a", "opener_script", "npc", 0, 0))
        val sets = mapOf("opener_script" to listOf(Instr.Cmd("setflag", listOf("opened"), 1)))
        assertTrue(reach(world(gated, scriptRefs = refs, scripts = sets)).at("a", 3, 0))
        assertFalse(reach(world(gated)).at("a", 3, 0), "nothing ever sets the flag")
    }

    @Test
    fun entranceWarpsToSolidTilesAreNotWalkable() {
        // TileRules sanity for the tiles the item audit relies on.
        assertTrue(TileRules.isSolid(TileRules.behavior("gym_window")))
        assertEquals(TileBehavior.WARP, TileRules.behavior("forest_entrance"))
        assertEquals(TileBehavior.WARP, TileRules.behavior("tower_door"))
    }
}
