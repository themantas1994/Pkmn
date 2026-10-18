package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

/** Runs the lab intro scripts headlessly with a host that walks the player along the real lab map. */
class LabIntroTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private val lab by lazy { content.map("lab") }

    private fun newCtx(): GameContext = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99)).also {
        it.state.map = "lab"; it.state.x = 4; it.state.y = 9
    }

    private fun walkable(ctx: GameContext, x: Int, y: Int): Boolean {
        val rules = OverworldRules(ctx)
        val hidden = { n: NpcDef -> !rules.npcVisible(lab, n) }
        return rules.walkableTerrain(lab, x, y) && lab.npcs.none { it.x == x && it.y == y && !hidden(it) }
    }

    /** Plays [id]; WalkTo moves the player step by step through the same pathfinding the game uses. Returns every request. */
    private fun play(ctx: GameContext, id: String, answers: MutableList<Int> = mutableListOf()): List<ScriptRequest> {
        val vm = ScriptVM(ctx, content.scripts.getValue(id), { content.scripts[it] })
        val out = ArrayList<ScriptRequest>()
        while (true) {
            val r = vm.next() ?: break
            out.add(r)
            if (r is ScriptRequest.WalkTo) {
                assertEquals("player", r.target)
                val path = Pathing.findPath(ctx.state.x, ctx.state.y, r.x, r.y) { x, y -> walkable(ctx, x, y) }
                assertNotNull(path, "no route to ${r.x},${r.y} from ${ctx.state.x},${ctx.state.y}")
                for (d in path) { ctx.state.x += d.dx; ctx.state.y += d.dy }
                assertEquals(r.x to r.y, ctx.state.x to ctx.state.y)
            }
            if (r.blocking) vm.resume(when (r) { is ScriptRequest.Ask, is ScriptRequest.Choice -> answers.removeFirstOrNull() ?: 1; else -> 0 })
        }
        return out
    }

    private fun says(reqs: List<ScriptRequest>) = reqs.filterIsInstance<ScriptRequest.Say>().map { it.text }
    private fun scriptAt(ctx: GameContext, x: Int, y: Int) = OverworldRules(ctx).triggerAt(lab, x, y)?.script

    @Test
    fun `entering the lab before the starter walks to the professor and plays the intro once`() {
        val ctx = newCtx()
        val first = play(ctx, "lab_enter")
        assertEquals(ScriptRequest.WalkTo("player", 4, 3), first.first { it is ScriptRequest.WalkTo })
        assertEquals(4 to 3, ctx.state.x to ctx.state.y)
        assertEquals(2, says(first).count { it.startsWith("PROF. LINDEN") })
        assertTrue(ctx.state.hasFlag("lab_intro_done"))
        assertFalse(ctx.state.hasFlag("got_starter"))
        assertTrue(ctx.state.quests.containsKey("main_story"))
        // Walking path is the plain straight route, no teleport: 6 steps from the door mat.
        assertEquals(6, Pathing.findPath(4, 9, 4, 3) { x, y -> walkable(ctx, x, y) }!!.size)
        // Re-running the entry hook (a reload inside the lab) neither repeats the intro nor moves the player.
        val again = play(ctx, "lab_enter")
        assertTrue(again.isEmpty(), again.toString())
    }

    @Test
    fun `the exit is closed until a starter is chosen and cannot be bypassed`() {
        val ctx = newCtx()
        play(ctx, "lab_enter")
        for (x in 0..9) assertEquals("lab_need_starter", scriptAt(ctx, x, 8), "x=$x")
        assertNull(scriptAt(ctx, 4, 7))
        // Every route from the room to the exit tiles (row 9) crosses the trigger row.
        val reach = HashSet<Pair<Int, Int>>()
        val queue = ArrayDeque(listOf(4 to 3)); reach.add(4 to 3)
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            for (d in Facing.entries) {
                val n = (x + d.dx) to (y + d.dy)
                if (n.second == 8 || n in reach || !walkable(ctx, n.first, n.second)) continue
                reach.add(n); queue.add(n)
            }
        }
        assertTrue(reach.none { it.second >= 8 })
        // The push-back keeps the player inside the room on a free tile.
        ctx.state.x = 1; ctx.state.y = 8
        val out = play(ctx, "lab_need_starter")
        assertEquals(4 to 7, ctx.state.x to ctx.state.y)
        assertTrue(says(out).single().contains("choose"))
        assertFalse(ctx.state.hasFlag("got_starter"))
    }

    @Test
    fun `choosing a starter walks back to the professor and finishes the hand-off, then the rival runs once`() {
        val ctx = newCtx()
        play(ctx, "lab_enter")
        ctx.state.x = 7; ctx.state.y = 4 // in front of the ball table
        val out = play(ctx, "lab_ball_fire", mutableListOf(1))
        assertTrue(ctx.state.hasFlag("got_starter") && ctx.state.hasFlag("starter_fire"))
        assertTrue(ctx.state.hasFlag("got_pokedex") && ctx.state.hasFlag("has_pokedex"))
        assertEquals(5, ctx.state.itemCount("pokeball"))
        assertEquals(1, ctx.state.party.size)
        assertEquals(4 to 3, ctx.state.x to ctx.state.y)
        assertEquals(1, out.count { it is ScriptRequest.WalkTo })
        assertEquals(1, out.filterIsInstance<ScriptRequest.Say>().count { it.text.contains("received the Pokédex") })
        // No second starter, no second hand-off, no duplicate gifts.
        val again = play(ctx, "lab_ball_water", mutableListOf(1))
        assertEquals(1, ctx.state.party.size)
        assertEquals(listOf("That's the Professor's last Pokémon."), says(again))
        play(ctx, "lab_enter"); play(ctx, "lab_professor")
        assertEquals(5, ctx.state.itemCount("pokeball"))
        // The exit is open; the rival challenge fires on the way out exactly once.
        for (x in 0..9) assertEquals("lab_rival_challenge", scriptAt(ctx, x, 8))
        play(ctx, "lab_rival_challenge")
        assertTrue(ctx.state.hasFlag("rival_battled"))
        for (x in 0..9) assertNull(scriptAt(ctx, x, 8))
    }

    @Test
    fun `declining a starter changes nothing and does not walk`() {
        val ctx = newCtx()
        play(ctx, "lab_enter")
        ctx.state.x = 7; ctx.state.y = 4
        val out = play(ctx, "lab_ball_water", mutableListOf(0))
        assertTrue(out.none { it is ScriptRequest.WalkTo })
        assertFalse(ctx.state.hasFlag("got_starter"))
        assertEquals(7 to 4, ctx.state.x to ctx.state.y)
    }

    @Test
    fun `an interrupted sequence resumes from its flags after a reload`() {
        // Starter taken but the hand-off never finished (e.g. the app was killed): entering resumes it once.
        val ctx = newCtx()
        play(ctx, "lab_enter")
        ctx.state.x = 8; ctx.state.y = 6
        ctx.state.setFlag("got_starter")
        val resumed = play(ctx, "lab_enter")
        assertTrue(ctx.state.hasFlag("got_pokedex"))
        assertEquals(4 to 3, ctx.state.x to ctx.state.y)
        assertEquals(1, resumed.count { it is ScriptRequest.WalkTo })
        assertEquals(5, ctx.state.itemCount("pokeball"))
        assertTrue(play(ctx, "lab_enter").isEmpty())
        // Intro done, no starter, reloaded in the middle of the room: nothing happens.
        val mid = newCtx().also { it.state.setFlag("lab_intro_done"); it.state.x = 7; it.state.y = 5 }
        assertTrue(play(mid, "lab_enter").isEmpty())
        // ...but coming back in through the door without a starter leads to the professor again, with no new intro.
        val door = newCtx().also { it.state.setFlag("lab_intro_done") }
        val back = play(door, "lab_enter")
        assertEquals(4 to 3, door.state.x to door.state.y)
        assertEquals(1, says(back).size)
        assertFalse(door.state.hasFlag("got_starter"))
    }

    @Test
    fun `talking to the professor before choosing only repeats the instruction`() {
        val ctx = newCtx()
        play(ctx, "lab_enter")
        val out = play(ctx, "lab_professor")
        assertEquals(1, says(out).size)
        assertFalse(ctx.state.hasFlag("got_pokedex"))
        assertEquals(0, ctx.state.itemCount("pokeball"))
    }
}
