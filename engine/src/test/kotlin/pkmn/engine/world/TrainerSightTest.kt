package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

class TrainerSightTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    /** 11x11 map: floor everywhere except the given (x,y) -> tile overrides. */
    private fun map(overrides: Map<Pair<Int, Int>, String> = emptyMap(), obstacles: List<ObstacleDef> = emptyList(), npcs: List<NpcDef> = emptyList()): MapData {
        val palette = listOf("floor", "wall", "bush", "tall_grass")
        val ground = MutableList(121) { 0 }
        for ((p, t) in overrides) ground[p.second * 11 + p.first] = palette.indexOf(t)
        return MapData("t", "t", 11, 11, tilePalette = palette, ground = ground, obstacles = obstacles, npcs = npcs)
    }

    private fun trainer(id: String, x: Int, y: Int, face: String, sight: Int) = NpcDef(id, x, y, "youngster", face, trainer = id, sight = sight)
    private fun spot(ctx: GameContext, m: MapData, px: Int, py: Int, npcs: List<NpcDef>, blocked: (Int, Int) -> Boolean = { _, _ -> false }): NpcDef? =
        OverworldRules(ctx).spottingTrainer(m, px, py, npcs.map { it to (it.x to it.y) }, { Facing.parse(it.facing) }, blocked)

    @Test
    fun `trainer sees the player in all four directions and only in front`() {
        val ctx = newCtx()
        for ((face, dx, dy) in listOf(Triple("up", 0, -1), Triple("down", 0, 1), Triple("left", -1, 0), Triple("right", 1, 0))) {
            val t = trainer("t", 5, 5, face, 3)
            val m = map(npcs = listOf(t))
            assertEquals(t, spot(ctx, m, 5 + dx * 2, 5 + dy * 2, listOf(t)), face)
            assertNull(spot(ctx, m, 5 - dx * 2, 5 - dy * 2, listOf(t)), "behind $face")
            assertNull(spot(ctx, m, 5 + dy * 2 + dx, 5 + dx * 2 + dy, listOf(t)), "off-axis $face")
        }
    }

    @Test
    fun `sight range is inclusive and adjacent trainers spot`() {
        val ctx = newCtx()
        val t = trainer("t", 2, 5, "right", 3)
        val m = map(npcs = listOf(t))
        assertEquals(t, spot(ctx, m, 3, 5, listOf(t)), "adjacent")
        assertEquals(t, spot(ctx, m, 5, 5, listOf(t)), "exactly sight tiles away")
        assertNull(spot(ctx, m, 6, 5, listOf(t)), "one tile out of range")
        assertNull(spot(ctx, m, 5, 5, listOf(t.copy(sight = 0))), "sight 0 never spots")
    }

    @Test
    fun `walls, hedges, obstacles and npcs block line of sight`() {
        val ctx = newCtx()
        val t = trainer("t", 1, 5, "right", 5)
        assertNotNull(spot(ctx, map(), 5, 5, listOf(t)))
        assertNull(spot(ctx, map(mapOf((3 to 5) to "wall")), 5, 5, listOf(t)), "wall")
        assertNull(spot(ctx, map(mapOf((3 to 5) to "bush")), 5, 5, listOf(t)), "hedge")
        assertNotNull(spot(ctx, map(mapOf((3 to 5) to "tall_grass")), 5, 5, listOf(t)), "grass does not block")
        for (kind in listOf("boulder", "cut_tree", "rock_smash")) {
            val o = ObstacleDef(3, 5, kind, "flag_$kind")
            assertNull(spot(ctx, map(obstacles = listOf(o)), 5, 5, listOf(t)), kind)
            ctx.state.setFlag(o.flag) // cleared: sight is open again
            assertNotNull(spot(ctx, map(obstacles = listOf(o)), 5, 5, listOf(t)), "$kind cleared")
        }
        assertNull(spot(ctx, map(), 5, 5, listOf(t)) { x, y -> x == 2 && y == 5 }, "npc or item between")
        // An obstacle behind the player or beyond range does not matter.
        assertNotNull(spot(ctx, map(obstacles = listOf(ObstacleDef(6, 5, "boulder", "f"))), 5, 5, listOf(t)))
    }

    @Test
    fun `defeated trainers never spot and two aligned trainers spot in list order until the first is beaten`() {
        val ctx = newCtx()
        val a = trainer("a", 1, 5, "right", 6)
        val b = trainer("b", 9, 5, "left", 6)
        val m = map(npcs = listOf(a, b))
        assertEquals(a, spot(ctx, m, 5, 5, listOf(a, b)))
        ctx.state.defeatedTrainers.add("a")
        assertEquals(b, spot(ctx, m, 5, 5, listOf(a, b)), "second trainer takes over once the first is beaten")
        ctx.state.defeatedTrainers.add("b")
        assertNull(spot(ctx, m, 5, 5, listOf(a, b)))
        // An NPC without a trainer id never spots.
        assertNull(spot(newCtx(), m, 5, 5, listOf(NpcDef("n", 1, 5, "x", "right", sight = 6))))
    }
}
