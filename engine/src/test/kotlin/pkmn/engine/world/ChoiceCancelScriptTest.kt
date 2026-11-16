package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

/** C3-1 / C3-2: backing out of a one-time gift or the Tera exchange gives nothing and costs nothing. */
class ChoiceCancelScriptTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    /**
     * Runs [id]. A `choice` is answered by [choose] (null = press B, which the host maps to the LAST option);
     * `ask` answers yes; `special` answers [specialResult] (0 = the party pick was cancelled or refused).
     */
    private fun run(ctx: GameContext, id: String, choose: Int? = null, specialResult: Int = 1) {
        val vm = ScriptVM(ctx, content.scripts.getValue(id), { content.scripts[it] })
        while (true) {
            val r = vm.next() ?: break
            if (!r.blocking) continue
            when (r) {
                is ScriptRequest.Choice -> vm.resume(choose ?: (r.options.size - 1))
                is ScriptRequest.Special -> vm.resume(specialResult)
                else -> vm.resume(1)
            }
        }
    }

    private fun item(ctx: GameContext, id: String) = ctx.state.bag[id] ?: 0

    @Test
    fun `B on the Kanto choice gives nothing and leaves the gift open`() {
        val ctx = newCtx()
        val before = ctx.state.party.size
        run(ctx, "p2_lumen_kanto_choice")
        assertEquals(before, ctx.state.party.size)
        assertFalse(ctx.state.hasFlag("p2_kanto_gift"))
        run(ctx, "p2_lumen_kanto_choice", choose = 2)
        assertEquals(before + 1, ctx.state.party.size)
        assertTrue(ctx.state.hasFlag("p2_kanto_gift"))
    }

    @Test
    fun `B on the fossil choice gives nothing and leaves the gift open`() {
        val ctx = newCtx()
        ctx.state.setFlag("p2_found_pickaxe")
        run(ctx, "p2_quarry_foreman")
        assertEquals(0, item(ctx, "jawfossil") + item(ctx, "sailfossil"))
        assertFalse(ctx.state.hasFlag("p2_fossil_given"))
        run(ctx, "p2_quarry_foreman", choose = 1)
        assertEquals(1, item(ctx, "sailfossil"))
        assertTrue(ctx.state.hasFlag("p2_fossil_given"))
    }

    @Test
    fun `a cancelled or refused Tera pick keeps all three shards`() {
        val ctx = newCtx()
        ctx.state.bag["fireterashard"] = 3
        run(ctx, "p3a_cc_tera_exchange", choose = 0, specialResult = 0)
        assertEquals(3, item(ctx, "fireterashard"))
    }

    @Test
    fun `a Tera change that happens costs the three shards`() {
        val ctx = newCtx()
        ctx.state.bag["fireterashard"] = 4
        run(ctx, "p3a_cc_tera_exchange", choose = 0, specialResult = 1)
        assertEquals(1, item(ctx, "fireterashard"))
    }
}
