package pkmn.tools

import pkmn.engine.world.FieldMove
import pkmn.engine.world.Instr
import pkmn.engine.world.ItemPickup
import pkmn.engine.world.MapData
import pkmn.engine.world.NpcDef
import pkmn.engine.world.Warp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemAuditTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val legend = mapOf('.' to "grass", '#' to "tree", '~' to "water", 'D' to "door", 'f' to "floor_wood")

    private fun map(id: String, rows: List<String>, items: List<ItemPickup>, npcs: List<NpcDef> = emptyList(), warps: List<Warp> = emptyList()): MapData {
        val palette = ArrayList<String>()
        val ground = ArrayList<Int>()
        for (r in rows) for (ch in r) {
            val t = legend.getValue(ch)
            if (t !in palette) palette.add(t)
            ground.add(palette.indexOf(t))
        }
        return MapData(id, id, rows[0].length, rows.size, "world", palette, ground, items = items, npcs = npcs, warps = warps)
    }

    private class World(override val maps: Map<String, MapData>) : ReachWorld {
        override val start = WorldAnalysis.Node(maps.keys.first(), 0, 0)
        override val scriptRefs = emptyList<WorldAnalysis.ScriptRef>()
        override fun commands(scriptId: String) = emptyList<Instr.Cmd>()
        override fun activatable(r: WorldAnalysis.ScriptRef, reach: WorldAnalysis.Reach) = false
        override fun connectionTarget(m: MapData, nx: Int, ny: Int, dx: Int, dy: Int): WorldAnalysis.Node? = null
    }

    private fun audit(vararg maps: MapData): ItemAudit.Result {
        val stageMoves = (0..8).map { if (it >= 4) setOf(FieldMove.SURF) else emptySet() }
        val none = List(9) { WorldAnalysis.Reach(emptySet(), emptyMap(), emptyMap(), emptyMap()) }
        return ItemAudit(repo, write = false).audit(World(maps.associateBy { it.id }), stageMoves, none)
    }

    private fun ItemAudit.Result.row(id: String) = rows.single { it.id == id }

    @Test
    fun classifiesReachableGatedAndEnclosedItems() {
        val m = map("a", listOf(".....", ".###.", ".#.#.", ".###.", "....."), listOf(
            ItemPickup("open", 4, 0, "potion"),
            ItemPickup("ring", 2, 2, "potion"),
        ))
        val res = audit(m)
        assertEquals("reachable", res.row("open").verdict)
        assertEquals("unreachable", res.row("ring").verdict)
        assertTrue(res.row("ring").errors.any { it.code == "unreachable" })
        val moat = map("b", listOf("..~..", "..~.."), listOf(ItemPickup("far", 4, 0, "potion"), ItemPickup("near", 0, 1, "potion")))
        val r2 = audit(moat)
        assertEquals("gated", r2.row("far").verdict)
        assertEquals(4, r2.row("far").stage, "Surf arrives with the fourth badge in this model")
        assertEquals("reachable", r2.row("near").verdict)
    }

    @Test
    fun hiddenItemsThatInteractCannotFindAreDefects() {
        val m = map("a", listOf("....", ".~..", "...."), listOf(
            ItemPickup("wet", 1, 1, "potion", hidden = true),
            ItemPickup("npcd", 3, 2, "potion", hidden = true),
            ItemPickup("door", 0, 2, "potion"),
            ItemPickup("ok", 2, 0, "potion", hidden = true),
        ), npcs = listOf(NpcDef("guy", 3, 2, "x")), warps = listOf(Warp(0, 2, "a", 0, 0)))
        val res = audit(m)
        assertTrue(res.row("wet").errors.any { it.code == "hidden-on-water" }, res.row("wet").toString())
        assertTrue(res.row("npcd").errors.any { it.code == "hidden-under-npc" })
        assertTrue(res.row("door").errors.any { it.code == "item-on-warp" })
        assertTrue(res.row("ok").errors.isEmpty())
        assertEquals("unreachable", res.row("wet").verdict, "never obtainable by interacting")
    }

    @Test
    fun duplicatePickupIdsAcrossMapsAreDefects() {
        val a = map("a", listOf("..."), listOf(ItemPickup("same", 1, 0, "potion")))
        val b = map("b", listOf("..."), listOf(ItemPickup("same", 1, 0, "potion")))
        assertTrue(audit(a, b).rows.all { r -> r.errors.any { it.code == "duplicate-id" } })
    }

    @Test
    fun staticNpcInFrontOfAnItemInADeadEndMakesItUnreachable() {
        val m = map("a", listOf(".#.", ".#.", "..."), listOf(ItemPickup("tucked", 2, 0, "potion")), npcs = listOf(NpcDef("guard", 2, 1, "x")))
        // The only neighbour of the item that is not a tree or the map edge is occupied by a static NPC.
        assertEquals("unreachable", audit(m).row("tucked").verdict)
    }

    @Test
    fun noItemInTheWorldIsUnreachableOrMisplaced() {
        val res = ItemAudit(repo, write = false).audit(WorldAnalysis(repo))
        assertEquals(emptyList(), res.withErrors.map { r -> "${r.map}:${r.id} ${r.verdict} ${r.errors.map { it.code }}" })
        assertTrue(res.rows.size > 500, "all ${res.rows.size} items are audited")
        assertEquals(0, res.count("unreachable"))
    }
}
