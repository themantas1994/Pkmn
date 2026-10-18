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
        val palette = listOf("floor", "wall", "bush", "tall_grass", "water")
        val ground = MutableList(121) { 0 }
        for ((p, t) in overrides) ground[p.second * 11 + p.first] = palette.indexOf(t)
        return MapData("t", "t", 11, 11, tilePalette = palette, ground = ground, obstacles = obstacles, npcs = npcs)
    }

    private fun trainer(id: String, x: Int, y: Int, face: String, sight: Int) = NpcDef(id, x, y, "youngster", face, trainer = id, sight = sight)
    private fun spot(ctx: GameContext, m: MapData, px: Int, py: Int, npcs: List<NpcDef>, blocked: (Int, Int) -> Boolean = { _, _ -> false }): NpcDef? =
        OverworldRules(ctx).spottingTrainer(m, px, py, npcs.map { it to (it.x to it.y) }, { Facing.parse(it.facing) }, blocked = blocked)

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
    fun `an on-screen trainer spots the player beyond its old sight range, and sight 0 never spots`() {
        val ctx = newCtx()
        val t = trainer("t", 2, 5, "right", 3)
        val m = map(npcs = listOf(t))
        assertEquals(t, spot(ctx, m, 3, 5, listOf(t)), "adjacent")
        assertEquals(t, spot(ctx, m, 5, 5, listOf(t)), "exactly the old sight range away")
        assertEquals(t, spot(ctx, m, 9, 5, listOf(t)), "well beyond the old range")
        assertNull(spot(ctx, m, 5, 5, listOf(t.copy(sight = 0))), "sight 0 never spots")
    }

    @Test
    fun `the viewport is the outer boundary`() {
        val ctx = newCtx()
        val t = trainer("t", 0, 5, "right", 3)
        val m = map(npcs = listOf(t))
        fun spotIn(px: Int) = OverworldRules(ctx).spottingTrainer(m, px, 5, listOf(t to (0 to 5)), { Facing.RIGHT },
            inView = { x, y -> OverworldRules.inViewport(px, 5, x, y) }, blocked = { _, _ -> false })
        // 256x224 px camera centred on the player: 8 tiles to the left of the player's tile are fully visible, a 9th is off screen.
        assertEquals(t, spotIn(8), "left edge of the viewport")
        assertNull(spotIn(9), "one tile past the edge: not rendered")
        assertTrue(OverworldRules.inViewport(8, 5, 0, 5)); assertFalse(OverworldRules.inViewport(9, 5, 0, 5))
        assertTrue(OverworldRules.inViewport(5, 5, 5, 12)); assertFalse(OverworldRules.inViewport(5, 5, 5, 13))
        assertTrue(OverworldRules.inViewport(5, 5, 5, -2)); assertFalse(OverworldRules.inViewport(5, 5, 5, -3))
    }

    @Test
    fun `a trainer only spots when its whole approach is tiles it can legally walk`() {
        val ctx = newCtx()
        val t = trainer("t", 1, 5, "right", 3)
        assertNotNull(spot(ctx, map(), 6, 5, listOf(t)))
        assertNull(spot(ctx, map(mapOf((3 to 5) to "water")), 6, 5, listOf(t)), "water is not walkable for an NPC")
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
