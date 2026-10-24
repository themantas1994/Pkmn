package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

/** The Carbink side quest in p3a_cc_quartz_aide must stay startable and completable after the Hall of Fame. */
class CarbinkQuestScriptTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    private fun talk(ctx: GameContext): String {
        val vm = ScriptVM(ctx, content.scripts.getValue("p3a_cc_quartz_aide"), { content.scripts[it] })
        val said = ArrayList<String>()
        while (true) {
            val r = vm.next() ?: break
            if (r is ScriptRequest.Say) said.add(r.text)
            if (r.blocking) vm.resume(1)
        }
        return said.joinToString(" ")
    }

    private fun withCarbink(ctx: GameContext) = ctx.state.party.add(ctx.factory.create("carbink", 30, ctx.rng))
    private fun shards(ctx: GameContext) = ctx.state.bag["normalterashard"] ?: 0
    private fun candies(ctx: GameContext) = ctx.state.bag["rarecandy"] ?: 0

    @Test
    fun `post-game player who never asked gets the quest start`() {
        val ctx = newCtx()
        ctx.state.setFlag("hall_of_fame")
        val said = talk(ctx)
        assertTrue(said.contains("If you catch one"), said)
        assertTrue(ctx.state.hasFlag("p3a_carbink_asked"))
        assertNotNull(ctx.state.quests["p3a_carbink"])
        assertFalse(ctx.state.hasFlag("p3a_carbink_done"))
    }

    @Test
    fun `post-game player with a Carbink completes the quest and is paid once`() {
        val ctx = newCtx()
        ctx.state.setFlag("hall_of_fame")
        talk(ctx)
        withCarbink(ctx)
        assertTrue(talk(ctx).contains("Thank you"))
        assertEquals(3, shards(ctx))
        assertEquals(1, candies(ctx))
        assertTrue(ctx.state.hasFlag("p3a_carbink_done"))
        assertTrue(ctx.state.quests.getValue("p3a_carbink").completed)
        talk(ctx)
        assertEquals(3, shards(ctx), "no second payout")
        assertEquals(1, candies(ctx), "no second payout")
    }

    @Test
    fun `post-game player with a Carbink who never asked completes in one visit`() {
        val ctx = newCtx()
        ctx.state.setFlag("hall_of_fame")
        withCarbink(ctx)
        assertTrue(talk(ctx).contains("Thank you"))
        assertEquals(3, shards(ctx))
    }

    @Test
    fun `pre-game dialogue is unchanged`() {
        val ctx = newCtx()
        assertTrue(talk(ctx).contains("If you catch one"))
        assertTrue(talk(ctx).contains("deep, quiet parts"))
        withCarbink(ctx)
        assertTrue(talk(ctx).contains("Thank you"))
        assertTrue(talk(ctx).contains("These crystals hum"))
        assertEquals(3, shards(ctx))
    }

    @Test
    fun `post-game player who already asked keeps the Champion line until they bring a Carbink`() {
        val ctx = newCtx()
        ctx.state.setFlag("p3a_carbink_asked")
        ctx.state.setFlag("hall_of_fame")
        assertTrue(talk(ctx).contains("The Champion!"))
        withCarbink(ctx)
        assertTrue(talk(ctx).contains("Thank you"))
    }
}
