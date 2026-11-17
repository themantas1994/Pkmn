package pkmn.tools

import pkmn.engine.world.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RequiredRouteTest {
    private class World(override val maps: Map<String, MapData>, override val start: WorldAnalysis.Node, override val scriptRefs: List<WorldAnalysis.ScriptRef>,
                        private val scripts: Map<String, List<Instr>>) : ProgressWorld {
        override fun code(scriptId: String): List<Instr> = scripts[scriptId].orEmpty()
        override fun commands(scriptId: String): List<Instr.Cmd> = code(scriptId).filterIsInstance<Instr.Cmd>()
        override fun connectionTarget(m: MapData, nx: Int, ny: Int, dx: Int, dy: Int): WorldAnalysis.Node? = null
        override fun activatable(r: WorldAnalysis.ScriptRef, reach: WorldAnalysis.Reach) = WorldAnalysis.activatableFrom(r, reach, maps[r.map])
        override val badgeRefs: Map<Int, WorldAnalysis.ScriptRef> = emptyMap()
        override val hallOfFameRef: WorldAnalysis.ScriptRef? = null
    }

    private fun room(id: String, vararg rows: String, extra: (MapData) -> MapData = { it }): MapData {
        val palette = listOf("grass", "door")
        val ground = rows.flatMap { r -> r.map { if (it == 'D') 1 else 0 } }
        return extra(MapData(id, id, rows[0].length, rows.size, "world", palette, ground))
    }

    /**
     * Corridor `a`: door to `b` at x=0, a turn-back trigger at x=2 and the door to the goal `c` at x=4. Only the NPC in `b` sets the
     * flag `key` that the trigger needs, so the required route is a -> b -> a -> c, not the plain a -> c.
     */
    private fun world(gated: Boolean = true): World {
        val a = room("a", "D...D") { it.copy(warps = listOf(Warp(0, 0, "b", 0, 1), Warp(4, 0, "c", 0, 1)),
            triggers = if (gated) listOf(TriggerDef(2, 0, 1, 1, "gate", unlessFlag = "key")) else emptyList()) }
        val b = room("b", "D.", "..") { it.copy(npcs = listOf(NpcDef("keeper", 1, 1, "x", movement = "static", script = "give_key")), warps = listOf(Warp(0, 0, "a", 1, 0))) }
        val c = room("c", "D.", "..") { it.copy(warps = listOf(Warp(0, 0, "a", 3, 0))) }
        val gate = listOf(Instr.JumpIfNot(Cond.Atom(listOf("flag", "key")), 2, 0), Instr.Jump(3, 0), Instr.Cmd("move", listOf("player", "left"), 0))
        return World(mapOf("a" to a, "b" to b, "c" to c), WorldAnalysis.Node("a", 1, 0),
            listOf(WorldAnalysis.ScriptRef("b", "give_key", "npc", 1, 1)) + if (gated) listOf(WorldAnalysis.ScriptRef("a", "gate", "trigger", 2, 0)) else emptyList(),
            mapOf("give_key" to listOf(Instr.Cmd("setflag", listOf("key"), 0)), "gate" to gate))
    }

    @Test
    fun `the route visits the map that sets the flag opening the gate`() {
        val r = RequiredRoute(world()).route(0, "a", "c")!!
        assertEquals(listOf("key"), r.requiredFlags)
        assertEquals(listOf("a", "b", "a", "c"), r.maps)
    }

    @Test
    fun `a goal with no gate in the way keeps the plain shortest route`() {
        val r = RequiredRoute(world(gated = false)).route(0, "a", "c")!!
        assertEquals(emptyList(), r.requiredFlags)
        assertEquals(listOf("a", "c"), r.maps)
    }
}
