package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

class TrainerReturnTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    private fun grid(vararg rows: String): (Int, Int) -> Boolean = { x, y -> rows.getOrNull(y)?.getOrNull(x) == '.' }

    @Test
    fun `path finder walks around walls and reports unreachable or blocked targets`() {
        val g = grid(
            ".....",
            ".###.",
            ".....",
        )
        assertEquals(emptyList(), Pathing.findPath(0, 0, 0, 0, passable = g))
        assertEquals(List(4) { Facing.RIGHT }, Pathing.findPath(0, 0, 4, 0, passable = g))
        val around = Pathing.findPath(1, 0, 1, 2, passable = g)!!
        assertEquals(4, around.size)
        var x = 1; var y = 0
        for (d in around) { x += d.dx; y += d.dy; assertTrue(g(x, y)) }
        assertEquals(1 to 2, x to y)
        assertNull(Pathing.findPath(0, 0, 2, 1, passable = g), "target not passable")
        assertNull(Pathing.findPath(0, 0, 4, 2, passable = grid(".#.", "###", "..."))) // walled off
        assertNull(Pathing.findPath(0, 0, 40, 0, maxNodes = 5, passable = { _, _ -> true }), "search is bounded")
    }

    private fun map(vararg rows: String, npcs: List<NpcDef> = emptyList()): MapData {
        val palette = listOf("floor", "wall")
        return MapData("m", "m", rows[0].length, rows.size, tilePalette = palette, ground = rows.flatMap { r -> r.map { if (it == '#') 1 else 0 } }, npcs = npcs)
    }

    @Test
    fun `only defeated static or look trainers away from home need to walk back`() {
        val ctx = newCtx()
        val rules = OverworldRules(ctx)
        val t = NpcDef("t", 3, 3, "s", "left", trainer = "t", sight = 3)
        assertFalse(rules.trainerNeedsHome(t, 5, 3), "undefeated trainer stays where it is")
        ctx.state.defeatedTrainers.add("t")
        assertTrue(rules.trainerNeedsHome(t, 5, 3))
        assertFalse(rules.trainerNeedsHome(t, 3, 3), "already home")
        assertTrue(rules.trainerNeedsHome(t.copy(movement = "look"), 4, 3))
        assertFalse(rules.trainerNeedsHome(t.copy(movement = "wander"), 5, 3), "wanderers roam by themselves")
        assertFalse(rules.trainerNeedsHome(NpcDef("n", 3, 3, "s"), 5, 3), "plain npcs never")
    }

    @Test
    fun `terrain for npc walks excludes walls, warps, water, ledges and active obstacles`() {
        val ctx = newCtx()
        val rules = OverworldRules(ctx)
        val m = map("....", "..#.").copy(obstacles = listOf(ObstacleDef(1, 0, "boulder", "b")), warps = listOf(Warp(3, 0, "x")))
        assertTrue(rules.walkableTerrain(m, 0, 0))
        assertFalse(rules.walkableTerrain(m, 2, 1), "wall")
        assertFalse(rules.walkableTerrain(m, 1, 0), "boulder")
        ctx.state.setFlag("b")
        assertTrue(rules.walkableTerrain(m, 1, 0), "cleared boulder")
        assertFalse(rules.walkableTerrain(m, -1, 0))
        val route = content.map("route1")
        // route1 has a ledge row; a trainer must never be walked over it.
        val ledge = (0 until route.width).flatMap { x -> (0 until route.height).map { y -> x to y } }.first { (x, y) -> rules.behavior(route, x, y) == TileBehavior.LEDGE_S }
        assertFalse(rules.walkableTerrain(route, ledge.first, ledge.second))
    }

    private fun gymBlocked(ctx: GameContext, gym: MapData, extra: Set<Pair<Int, Int>>): (Int, Int) -> Boolean {
        val rules = OverworldRules(ctx)
        val npcTiles = gym.npcs.map { it.x to it.y }.toSet()
        return { x, y -> rules.walkableTerrain(gym, x, y) && (x to y) !in npcTiles && (x to y) !in extra }
    }

    @Test
    fun `first gym trainers walk straight back home after reaching the player`() {
        val ctx = newCtx()
        val gym = content.map("brindle_gym")
        val rules = OverworldRules(ctx)
        for (t in gym.npcs.filter { it.trainer != null }) {
            // The player stands on the last tile the trainer can see; the trainer walks up to the tile before it.
            val f = Facing.parse(t.facing)
            var sx = t.x; var sy = t.y
            var seen = 0
            for (i in 1..t.sight) {
                val nx = sx + f.dx; val ny = sy + f.dy
                if (rules.sightBlockedAt(gym, nx, ny)) break
                sx = nx; sy = ny; seen = i
            }
            assertTrue(seen >= 1, "${t.id} sees at least the next tile")
            val px = sx; val py = sy
            val tx = px - f.dx; val ty = py - f.dy // one tile in front of the player
            ctx.state.defeatedTrainers.add(t.trainer!!)
            assertEquals(seen > 1, rules.trainerNeedsHome(t, tx, ty), t.id)
            val others = gym.npcs.filter { it.id != t.id }.map { it.x to it.y }.toSet()
            val path = Pathing.findPath(tx, ty, t.x, t.y) { x, y -> rules.walkableTerrain(gym, x, y) && (x to y) !in others && (x to y) != (px to py) }
            assertNotNull(path, t.id)
            var x = tx; var y = ty
            for (d in path) { x += d.dx; y += d.dy }
            assertEquals(t.x to t.y, x to y, t.id)
            assertEquals(seen - 1, path.size, "${t.id} retraces its approach")
        }
    }

    @Test
    fun `gym leader stays reachable with every trainer standing on its home tile`() {
        val ctx = newCtx()
        val gym = content.map("brindle_gym")
        // Gym switches open the hedge gates; with both flipped (the puzzle solved) the leader must be reachable around the trainers.
        val opened = gym.flagTiles.map { it.x to it.y }.toSet()
        val passable = gymBlocked(ctx, gym, emptySet())
        val hazel = gym.npcs.first { it.id == "hazel" }
        val door = gym.warps.first()
        val path = Pathing.findPath(door.x, door.y - 1, hazel.x, hazel.y + 1) { x, y -> passable(x, y) || (x to y) in opened }
        assertNotNull(path, "no route from the gym door to the leader while the trainers stand at home")
    }
}
