package pkmn.tools

import pkmn.engine.world.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The progression checks must be able to fail: each test builds a small world with one defect and expects the matching error. */
class ProgressionCheckerTest {
    private val legend = mapOf(
        '.' to "grass", '#' to "tree", 'i' to "ice", '~' to "water", 'v' to "ledge_s", 'D' to "door", 'c' to "cave_floor", 'W' to "cave_wall",
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

    private class World(override val maps: Map<String, MapData>, override val start: WorldAnalysis.Node, override val scriptRefs: List<WorldAnalysis.ScriptRef> = emptyList(),
                        private val scripts: Map<String, List<Instr>> = emptyMap()) : ProgressWorld {
        override fun code(scriptId: String): List<Instr> = scripts[scriptId].orEmpty()
        override fun commands(scriptId: String): List<Instr.Cmd> = code(scriptId).filterIsInstance<Instr.Cmd>()
        override fun connectionTarget(m: MapData, nx: Int, ny: Int, dx: Int, dy: Int): WorldAnalysis.Node? = null
        override fun activatable(r: WorldAnalysis.ScriptRef, reach: WorldAnalysis.Reach) = WorldAnalysis.activatableFrom(r, reach, maps[r.map])
        override val badgeRefs: Map<Int, WorldAnalysis.ScriptRef> = buildMap {
            for (r in scriptRefs) for (c in commands(r.script)) if (c.name == "badge") c.args[0].toIntOrNull()?.let { putIfAbsent(it, r) }
        }
        override val hallOfFameRef: WorldAnalysis.ScriptRef? = null
    }

    private fun cmd(name: String, vararg args: String) = Instr.Cmd(name, args.toList(), 0)
    private fun awardBadge(n: Int) = listOf(cmd("badge", n.toString()))
    private fun codes(w: World) = ProgressionChecker.analyze(w).issues.errors.map { it.code }
    private fun errors(w: World, code: String) = ProgressionChecker.analyze(w).issues.errors.filter { it.code == code }

    private fun single(m: MapData, badge: Int, x: Int, y: Int, start: Int = 0, scripts: Map<String, List<Instr>> = emptyMap(), extraRefs: List<WorldAnalysis.ScriptRef> = emptyList()) =
        World(mapOf(m.id to m), WorldAnalysis.Node(m.id, start, 0), listOf(WorldAnalysis.ScriptRef(m.id, "gym", "npc", x, y)) + extraRefs, scripts + ("gym" to awardBadge(badge)))

    @Test
    fun `a gym behind water needs Surf and is flagged as a softlock for badge 1`() {
        val w = single(map("a", "..~..", "..~.."), 1, 4, 0)
        assertEquals(1, errors(w, "gym.softlock").size, codes(w).toString())
        assertTrue(errors(w, "gym.softlock").single().message.contains("badge 1 needs 4 badges"))
        // Same gym as badge 5: Surf is available after four badges, so the water is fine.
        assertEquals(emptyList(), errors(single(map("a", "..~..", "..~.."), 5, 4, 0), "gym.softlock"))
    }

    @Test
    fun `a gym that no field move reaches is unreachable`() {
        val w = single(map("a", "..#..", "..#.."), 1, 4, 0)
        assertEquals(1, errors(w, "gym.unreachable").size, codes(w).toString())
        assertEquals(emptyList(), errors(single(map("a", ".....", "....."), 1, 4, 0), "gym.unreachable"))
    }

    @Test
    fun `a map nothing leads to is an orphan`() {
        val a = map("a", "..", "..")
        val island = map("island", "..", "..")
        val w = World(mapOf("a" to a, "island" to island), WorldAnalysis.Node("a", 0, 0))
        assertEquals(listOf("island"), errors(w, "map.orphan").map { it.subject })
    }

    @Test
    fun `a boulder pushed in front of a door is a softlock`() {
        // 5x5 room, 3x3 floor, doors at (0,2) and (4,3), boulder in the centre (same room as BoulderPuzzlesTest).
        val ground = MutableList(25) { 1 }
        for (y in 1..3) for (x in 1..3) ground[y * 5 + x] = 0
        val t = MapData("t", "t", 5, 5, "world", listOf("cave_floor", "cave_wall"), ground,
            warps = listOf(Warp(0, 2, "o", 1, 1), Warp(4, 3, "o", 1, 1)), obstacles = listOf(ObstacleDef(2, 2, "boulder", "b")))
        val o = MapData("o", "o", 3, 3, "world", listOf("cave_floor"), List(9) { 0 }, warps = listOf(Warp(0, 1, "t", 1, 2)))
        val w = World(mapOf("t" to t, "o" to o), WorldAnalysis.Node("o", 1, 1))
        assertTrue(errors(w, "boulder.softlock").isNotEmpty(), codes(w).toString())
    }

    @Test
    fun `ice that overshoots the only way to a gym makes it unreachable`() {
        // Sliding east from (0,1) stops at (4,1), sliding west stops at (0,1): the middle ice tile below the gym can never be stood on.
        val ice = map("a", "##.##", ".iii.", "#####")
        assertEquals(1, errors(single(ice, 1, 2, 0, start = 0).let { World(it.maps, WorldAnalysis.Node("a", 0, 1), it.scriptRefs, mapOf("gym" to awardBadge(1))) }, "gym.unreachable").size)
        // The same room with plain ground in the middle is crossable.
        val plain = map("a", "##.##", ".....", "#####")
        val ok = single(plain, 1, 2, 0).let { World(it.maps, WorldAnalysis.Node("a", 0, 1), it.scriptRefs, mapOf("gym" to awardBadge(1))) }
        assertEquals(emptyList(), errors(ok, "gym.unreachable"))
    }

    private fun pitWorld(withExit: Boolean): World {
        val a = map("a", "D.") { it.copy(warps = listOf(Warp(0, 0, "b", 1, 0))) }
        // Jumping the ledge lands in a pocket; only with a door in the pocket (beside the ice) can the player come back.
        val b = map("b", "#.#", "#v#", if (withExit) "#iD" else "#i#", "###") { it.copy(warps = if (withExit) listOf(Warp(2, 2, "a", 1, 0)) else emptyList()) }
        return World(mapOf("a" to a, "b" to b), WorldAnalysis.Node("a", 1, 0))
    }

    @Test
    fun `a pocket with no way back to a safe map is a trap`() {
        assertEquals(listOf("b"), errors(pitWorld(withExit = false), "trap").map { it.subject })
        assertEquals(emptyList(), errors(pitWorld(withExit = true), "trap"))
    }

    // ------------------------------------------------------------------ turn-back gates and guards

    private fun jumpIfBadgesBelow(n: Int, target: Int) = Instr.JumpIfNot(Cond.Atom(listOf("badges", ">=", n.toString())), target, 0)

    /** A vertical turn-back trigger at x=2 (all rows) between the start and a gym; it lets the player through from [need] badges on. */
    private fun gateWorld(need: Int, badge: Int, tiles: Int = 3, gap: Boolean = false): World {
        val rows = if (gap) arrayOf(".....", ".....", ".....") else arrayOf(".....", ".....", ".....")
        val gate = listOf(jumpIfBadgesBelow(need, 3), cmd("setflag", "gate_open"), Instr.Jump(4, 0), cmd("move", "player", "left"))
        val m = map("a", *rows) { it.copy(triggers = listOf(TriggerDef(2, if (gap) 1 else 0, 1, if (gap) 1 else tiles, "gate", unlessFlag = "gate_open"))) }
        return single(m, badge, 4, 1, scripts = mapOf("gate" to gate),
            extraRefs = listOf(WorldAnalysis.ScriptRef("a", "gate", "trigger", 2, if (gap) 1 else 0, 1, if (gap) 1 else tiles)))
    }

    @Test
    fun `a gate that holds until 5 badges makes badge 3 unreachable in time, one that holds until 2 does not`() {
        val late = errors(gateWorld(need = 5, badge = 3), "gym.softlock")
        assertEquals(1, late.size)
        assertTrue(late.single().message.contains("needs 5 badges"), late.single().message)
        assertEquals(emptyList(), errors(gateWorld(need = 2, badge = 3), "gym.softlock"))
    }

    @Test
    fun `a gate trigger with a gap around it is reported as leaky, a sealed one is not`() {
        assertEquals(emptyList(), errors(gateWorld(need = 5, badge = 6), "gate.leaky"))
        val leaky = errors(gateWorld(need = 5, badge = 6, gap = true), "gate.leaky")
        assertEquals(1, leaky.size, codes(gateWorld(need = 5, badge = 6, gap = true)).toString())
    }

    private fun guardWorld(need: Int, badge: Int): World {
        val guardScript = listOf(jumpIfBadgesBelow(need, 2), cmd("hide", "guard"))
        val m = map("a", ".....") { it.copy(npcs = listOf(NpcDef("guard", 2, 0, "x", movement = "static", script = "guard"))) }
        return single(m, badge, 4, 0, scripts = mapOf("guard" to guardScript), extraRefs = listOf(WorldAnalysis.ScriptRef("a", "guard", "npc", 2, 0)))
    }

    @Test
    fun `a guard NPC blocks the corridor until its script can hide it`() {
        val late = errors(guardWorld(need = 5, badge = 3), "gym.softlock")
        assertEquals(1, late.size, "the guard only moves with 5 badges")
        assertEquals(emptyList(), errors(guardWorld(need = 2, badge = 3), "gym.softlock"))
    }

    @Test
    fun `a script command behind a badge condition does nothing before that many badges`() {
        // A flag tile opens the way east, but only a script run with 5 badges sets the flag.
        val door = listOf(jumpIfBadgesBelow(5, 2), cmd("setflag", "door_open"))
        val m = map("a", "..#..") { it.copy(flagTiles = listOf(FlagTile("door_open", 2, 0, "grass"))) }
        val w = World(mapOf("a" to m), WorldAnalysis.Node("a", 0, 0), listOf(WorldAnalysis.ScriptRef("a", "door", "sign", 1, 0)), mapOf("door" to door))
        val east = WorldAnalysis.Node("a", 3, 0)
        val stages = ProgressionChecker.analyze(w).stages
        assertEquals(List(9) { it >= 5 }, stages.map { east in it.reach.nodes })
    }
}
