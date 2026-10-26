package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

/** Side-quest chain C (PKM-76): the Umbra Files run end to end through the compiled scripts. */
class UmbraFilesTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))
    private val quest = "p5c_umbra_files"

    private fun badges(ctx: GameContext, n: Int) { ctx.state.badges.clear(); for (i in 1..n) ctx.state.badges.add(i) }
    private fun step(ctx: GameContext) = ctx.state.quests[quest]?.step ?: -1
    private fun at(ctx: GameContext, step: Int) { ctx.state.quests.getValue(quest).step = step }
    private fun item(ctx: GameContext, id: String) = ctx.state.bag[id] ?: 0

    /** Runs a script; [answers] feed `ask`/`choice` prompts in order (ask: 1 = yes), battles are won unless [win] is false. */
    private fun run(ctx: GameContext, id: String, answers: List<Int> = emptyList(), win: Boolean = true): String {
        val vm = ScriptVM(ctx, content.scripts.getValue(id), { content.scripts[it] })
        val said = ArrayList<String>()
        val picks = answers.iterator()
        while (true) {
            val r = vm.next() ?: break
            if (r is ScriptRequest.Say) said.add(r.text)
            if (!r.blocking) continue
            when (r) {
                is ScriptRequest.Choice -> vm.resume(if (picks.hasNext()) picks.next() else 0)
                is ScriptRequest.Ask -> vm.resume(if (picks.hasNext()) picks.next() else 1)
                is ScriptRequest.TrainerBattle -> vm.resume(if (win) 1 else 0)
                else -> vm.resume(1)
            }
        }
        return said.joinToString(" ")
    }

    private fun started(ctx: GameContext) { badges(ctx, 1); run(ctx, "p5c_defector") }

    @Test
    fun `defector needs a field move to open the quest and only starts it once`() {
        val ctx = newCtx()
        badges(ctx, 0)
        assertTrue(run(ctx, "p5c_defector").contains("clear brush"))
        assertEquals(-1, step(ctx))
        badges(ctx, 1)
        run(ctx, "p5c_defector", listOf(0))
        assertEquals(-1, step(ctx), "declining must not start the quest")
        run(ctx, "p5c_defector")
        assertEquals(0, step(ctx))
        assertTrue(ctx.state.hasFlag("p5c_defector_in"))
        run(ctx, "p5c_defector")
        assertEquals(0, step(ctx), "second talk must not restart or advance")
    }

    @Test
    fun `document spots stay shut before their step`() {
        val ctx = newCtx()
        listOf("p5c_wh_crate", "p5c_pp_safe", "p5c_mp2_page", "p5c_cal_vent", "p5c_ff_page", "p5c_pk_page", "p5c_lab_letter", "p5c_desk")
            .forEach { run(ctx, it) }
        assertEquals(-1, step(ctx))
        started(ctx)
        listOf("p5c_wh_crate", "p5c_pp_safe", "p5c_mp2_page", "p5c_ff_page", "p5c_pk_page", "p5c_lab_letter").forEach { run(ctx, it) }
        assertEquals(0, step(ctx))
    }

    @Test
    fun `doc 1 then the warehouse crate needs both switches`() {
        val ctx = newCtx()
        started(ctx)
        run(ctx, "p5c_desk"); assertEquals(1, step(ctx))
        run(ctx, "p5c_wh_switch_a")
        run(ctx, "p5c_wh_crate"); assertEquals(1, step(ctx), "one switch is not enough")
        run(ctx, "p5c_wh_switch_b")
        run(ctx, "p5c_wh_crate"); assertEquals(2, step(ctx))
    }

    @Test
    fun `power plant safe needs the key-card from the middle locker`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 2)
        run(ctx, "p5c_pp_locker_a"); run(ctx, "p5c_pp_locker_c")
        run(ctx, "p5c_pp_safe"); assertEquals(2, step(ctx))
        run(ctx, "p5c_pp_locker_b"); assertTrue(ctx.state.hasFlag("p5c_keycard"))
        run(ctx, "p5c_pp_safe"); assertEquals(3, step(ctx))
    }

    @Test
    fun `boulder room page advances the quest`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 3)
        run(ctx, "p5c_mp2_page"); assertEquals(4, step(ctx))
    }

    @Test
    fun `caldera vent needs a heat-proof party member`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 4)
        ctx.state.party.clear()
        ctx.receivePokemon(ctx.factory.create("rattata", 20, ctx.rng))
        run(ctx, "p5c_cal_vent"); assertEquals(4, step(ctx))
        ctx.receivePokemon(ctx.factory.create("growlithe", 20, ctx.rng))
        run(ctx, "p5c_cal_vent"); assertEquals(5, step(ctx))
    }

    @Test
    fun `falls page and summit page advance and open the gauntlet`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 5)
        run(ctx, "p5c_ff_page"); assertEquals(6, step(ctx))
        assertFalse(ctx.state.hasFlag("p5c_gauntlet_open"))
        run(ctx, "p5c_pk_page"); assertEquals(7, step(ctx))
        assertTrue(ctx.state.hasFlag("p5c_gauntlet_open"))
    }

    @Test
    fun `gauntlet runs in order and the letter needs all three wins`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 7)
        run(ctx, "p5c_lab_corvin"); run(ctx, "p5c_lab_halloran")
        assertFalse(ctx.state.hasFlag("p5c_g_corvin") || ctx.state.hasFlag("p5c_g_halloran"), "out of order does nothing")
        run(ctx, "p5c_lab_vesper", win = false)
        assertFalse(ctx.state.hasFlag("p5c_g_vesper"), "a loss does not count")
        run(ctx, "p5c_lab_vesper"); run(ctx, "p5c_lab_corvin")
        run(ctx, "p5c_lab_letter"); assertEquals(7, step(ctx))
        run(ctx, "p5c_lab_halloran")
        run(ctx, "p5c_lab_letter"); assertEquals(8, step(ctx))
    }

    @Test
    fun `epilogue then the archivist pays once and opens the shop`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 8)
        run(ctx, "p5c_vendor")
        assertFalse(run(ctx, "p5c_vendor").contains("Welcome"))
        run(ctx, "p5c_rook"); assertEquals(9, step(ctx)); assertTrue(ctx.state.hasFlag("p5c_epilogue"))
        val party = ctx.state.party.size
        run(ctx, "p5c_archivist")
        assertTrue(ctx.state.quests.getValue(quest).completed)
        assertEquals(party + 1, ctx.state.party.size, "the Sneasel egg")
        assertEquals(1, item(ctx, "tm:foulplay"))
        assertEquals(1, item(ctx, "blackglasses"))
        assertEquals(1, item(ctx, "darkterashard"))
        run(ctx, "p5c_archivist", listOf(0))
        assertEquals(1, item(ctx, "blackglasses"), "paid once")
        assertTrue(run(ctx, "p5c_vendor").contains("Welcome"))
    }

    @Test
    fun `archivist pays nothing and keeps the step when party and boxes are full`() {
        val ctx = newCtx()
        started(ctx); at(ctx, 9)
        while (ctx.state.party.size < 6) ctx.state.party.add(ctx.factory.create("rattata", 5, ctx.rng))
        for (b in ctx.state.boxes) for (i in b.indices) b[i] = ctx.factory.create("rattata", 5, ctx.rng)
        run(ctx, "p5c_archivist")
        assertEquals(9, step(ctx))
        assertEquals(0, item(ctx, "blackglasses"))
        assertEquals(0, item(ctx, "tm:foulplay"))
    }

    @Test
    fun `every document can be replayed from the archive`() {
        val ctx = newCtx()
        for (n in 1..8) assertTrue(run(ctx, "p5c_doc_$n").startsWith("DOCUMENT $n"))
    }
}
