package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

/** Side-quest chain B (PKM-77): Kai's Notebook runs end to end through the compiled scripts. */
class KaiNotebookTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))
    private val quest = "p5b_kai_notebook"

    private fun badges(ctx: GameContext, n: Int) { ctx.state.badges.clear(); for (i in 1..n) ctx.state.badges.add(i) }
    private fun step(ctx: GameContext) = ctx.state.quests[quest]?.step ?: -1
    private fun at(ctx: GameContext, step: Int) { ctx.state.quests.getValue(quest).step = step }
    private fun item(ctx: GameContext, id: String) = ctx.state.bag[id] ?: 0

    /** Runs a script; battles are won unless [win] is false. */
    private fun run(ctx: GameContext, id: String, win: Boolean = true): String {
        val vm = ScriptVM(ctx, content.scripts.getValue(id), { content.scripts[it] })
        val said = ArrayList<String>()
        while (true) {
            val r = vm.next() ?: break
            if (r is ScriptRequest.Say) said.add(r.text)
            if (!r.blocking) continue
            when (r) {
                is ScriptRequest.TrainerBattle -> vm.resume(if (win) 1 else 0)
                else -> vm.resume(1)
            }
        }
        return said.joinToString(" ")
    }

    private fun started(ctx: GameContext) { badges(ctx, 1); run(ctx, "p5b_page") }
    private fun party(ctx: GameContext, vararg species: String) {
        ctx.state.party.clear()
        species.forEach { ctx.receivePokemon(ctx.factory.create(it, 20, ctx.rng)) }
    }

    @Test
    fun `page needs a badge and starts the quest once`() {
        val ctx = newCtx()
        badges(ctx, 0)
        run(ctx, "p5b_page"); assertEquals(-1, step(ctx))
        badges(ctx, 1)
        run(ctx, "p5b_page"); assertEquals(0, step(ctx))
        at(ctx, 3)
        run(ctx, "p5b_page"); assertEquals(3, step(ctx), "second talk must not restart")
    }

    @Test
    fun `cache pays once at step 0 and stays shut otherwise`() {
        val ctx = newCtx()
        run(ctx, "p5b_cache"); assertEquals(0, item(ctx, "ppmax"))
        started(ctx)
        run(ctx, "p5b_cache")
        assertEquals(1, step(ctx)); assertEquals(1, item(ctx, "ppmax")); assertTrue(ctx.state.hasFlag("p5b_cache_taken"))
        run(ctx, "p5b_cache"); assertEquals(1, item(ctx, "ppmax"))
    }

    @Test
    fun `judges need the badges and one matching party member each`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 1); badges(ctx, 1)
        party(ctx, "squirtle", "pikachu", "bulbasaur")
        run(ctx, "p5b_judge_1"); assertFalse(ctx.state.hasFlag("p5b_j1"), "needs badge 2")
        badges(ctx, 2)
        party(ctx, "rattata")
        run(ctx, "p5b_judge_1"); run(ctx, "p5b_judge_2"); run(ctx, "p5b_judge_3")
        assertFalse(ctx.state.hasFlag("p5b_j1") || ctx.state.hasFlag("p5b_j2") || ctx.state.hasFlag("p5b_j3"))
        party(ctx, "squirtle", "pikachu", "bulbasaur")
        run(ctx, "p5b_judge_1"); run(ctx, "p5b_judge_2")
        assertEquals(1, step(ctx), "two of three is not enough"); assertEquals(0, item(ctx, "focussash"))
        run(ctx, "p5b_judge_3")
        assertEquals(2, step(ctx)); assertEquals(1, item(ctx, "focussash"))
        run(ctx, "p5b_judge_3"); assertEquals(1, item(ctx, "focussash"), "paid once")
    }

    @Test
    fun `terminals only accept B then A then C and a wrong press resets`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 2); badges(ctx, 3)
        run(ctx, "p5b_term_a"); run(ctx, "p5b_term_c")
        assertEquals(2, step(ctx))
        run(ctx, "p5b_term_b"); run(ctx, "p5b_term_c")
        assertEquals(2, step(ctx), "C out of order resets")
        run(ctx, "p5b_term_b"); run(ctx, "p5b_term_a"); assertEquals(2, step(ctx))
        run(ctx, "p5b_term_c")
        assertEquals(3, step(ctx)); assertEquals(1, item(ctx, "tm:voltswitch"))
    }

    @Test
    fun `terminals stay idle below the badge gate`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 2); badges(ctx, 2)
        run(ctx, "p5b_term_b"); run(ctx, "p5b_term_a"); run(ctx, "p5b_term_c")
        assertEquals(2, step(ctx))
    }

    @Test
    fun `rails then the foreman pay a TM once`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 3); badges(ctx, 4)
        run(ctx, "p5b_qt_foreman"); assertEquals(3, step(ctx), "rails first")
        run(ctx, "p5b_rail_2"); run(ctx, "p5b_rail_1"); run(ctx, "p5b_rail_3")
        assertFalse(ctx.state.hasFlag("p5b_rails_done"), "wrong order resets")
        run(ctx, "p5b_rail_1"); run(ctx, "p5b_rail_2"); run(ctx, "p5b_rail_3")
        assertTrue(ctx.state.hasFlag("p5b_rails_done"))
        run(ctx, "p5b_qt_foreman")
        assertEquals(4, step(ctx)); assertEquals(1, item(ctx, "tm:rockslide"))
        run(ctx, "p5b_qt_foreman"); assertEquals(1, item(ctx, "tm:rockslide"))
    }

    @Test
    fun `spar needs five badges and a win`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 4); badges(ctx, 4)
        run(ctx, "p5b_st_kai"); assertEquals(4, step(ctx))
        badges(ctx, 5)
        run(ctx, "p5b_st_kai", win = false); assertEquals(4, step(ctx)); assertEquals(0, item(ctx, "choicescarf"))
        run(ctx, "p5b_st_kai"); assertEquals(5, step(ctx)); assertEquals(1, item(ctx, "choicescarf"))
        run(ctx, "p5b_st_kai"); assertEquals(1, item(ctx, "choicescarf"))
    }

    @Test
    fun `apology needs seven badges and pays once`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 5); badges(ctx, 6)
        run(ctx, "p5b_fm_kai"); assertEquals(5, step(ctx))
        badges(ctx, 7)
        run(ctx, "p5b_fm_kai"); assertEquals(6, step(ctx)); assertEquals(1, item(ctx, "luxuryball"))
        run(ctx, "p5b_fm_kai"); assertEquals(1, item(ctx, "luxuryball"))
    }

    @Test
    fun `capstone needs eight badges, a loss does not count and a win completes the quest`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 6); badges(ctx, 7)
        run(ctx, "p5b_kai_cap"); assertEquals(6, step(ctx))
        badges(ctx, 8)
        run(ctx, "p5b_kai_cap", win = false)
        assertFalse(ctx.state.quests.getValue(quest).completed)
        run(ctx, "p5b_kai_cap")
        assertTrue(ctx.state.quests.getValue(quest).completed)
        assertEquals(1, item(ctx, "tm:earthpower")); assertEquals(2, item(ctx, "ppmax"))
        run(ctx, "p5b_kai_cap"); assertEquals(2, item(ctx, "ppmax"), "paid once")
    }

    @Test
    fun `every rival variant exists for the spar and the capstone`() {
        for (v in listOf("typhlosion", "feraligatr", "meganium")) {
            assertNotNull(content.trainers["p5b_spar_$v"], v)
            assertNotNull(content.trainers["p5b_cap_$v"], v)
        }
    }
}
