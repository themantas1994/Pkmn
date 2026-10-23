package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

/** A lost trainer battle that is not `canlose` must end the script before any badge, TM, money or flag reward. */
class BattleLossScriptTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    /** Plays [script]; battles resolve to [battleResult] (1 win, 0 loss). Returns the requests seen. */
    private fun play(ctx: GameContext, script: CompiledScript, battleResult: Int): List<ScriptRequest> {
        val vm = ScriptVM(ctx, script, { content.scripts[it] })
        val out = ArrayList<ScriptRequest>()
        while (true) {
            val r = vm.next() ?: break
            out.add(r)
            if (r.blocking) vm.resume(if (r is ScriptRequest.TrainerBattle) battleResult else 1)
        }
        return out
    }

    private fun lost(id: String, ctx: GameContext = newCtx()) = play(ctx, content.scripts.getValue(id), 0).also { assertTrue(it.any { r -> r is ScriptRequest.TrainerBattle }, id) }

    @Test
    fun `losing to Hazel grants no badge, TM or victory flag`() {
        val ctx = newCtx()
        val reqs = lost("p1_hazel", ctx)
        assertEquals(0, ctx.state.badges.size, "badge")
        assertFalse(ctx.state.hasFlag("p1_beat_hazel"))
        assertTrue(ctx.state.bag.keys.none { "bulletseed" in it }, "TM")
        assertTrue(reqs.last() is ScriptRequest.TrainerBattle, "script ends at the lost battle")
    }

    @Test
    fun `winning against Hazel still grants badge and flag`() {
        val ctx = newCtx()
        play(ctx, content.scripts.getValue("p1_hazel"), 1)
        assertTrue(ctx.state.hasFlag("p1_beat_hazel"))
        assertEquals(1, ctx.state.badges.size)
        assertTrue(ctx.state.bag.keys.any { "bulletseed" in it }, "TM granted on a win")
    }

    @Test
    fun `other gym and story trainer battles also end on a loss`() {
        for ((id, flag) in listOf("p2_volta" to "p2_badge3", "p3a_ash_blaise" to "p3a_beat_blaise", "p4c_ws_chief" to "p4c_ws_chief_beaten",
            "p1_ww_grunt_boss" to "p1_ww_grunts_fled", "p2_plant_captain" to "p2_power_restored", "p3a_ash_rival_battle" to "p3a_rival_ashfall")) {
            val ctx = newCtx()
            ctx.state.setFlag("starter_grass")
            lost(id, ctx)
            assertFalse(ctx.state.hasFlag(flag), "$id must not set $flag after a loss")
            assertEquals(0, ctx.state.badges.size, id)
        }
    }

    @Test
    fun `canlose battles continue and branch on the result`() {
        val ctx = newCtx()
        ctx.state.setFlag("starter_grass")
        val reqs = play(ctx, content.scripts.getValue("p2_plant_captain"), 1)
        assertTrue(ctx.state.hasFlag("p2_power_restored"))
        val src = ScriptParser.parseFile("script t\n  battle x canlose\n  if result lose\n    say \"lost\"\n  end\n  say \"after\"\nend\n", "t.pks").single()
        val seen = play(newCtx(), src, 0).filterIsInstance<ScriptRequest.Say>().map { it.text }
        assertEquals(listOf("lost", "after"), seen)
        assertTrue(reqs.isNotEmpty())
    }

    private fun says(wildResult: Int): List<String> {
        val src = ScriptParser.parseFile("script t\n  wildbattle mrmime 37\n  if result lose\n    say \"lost\"\n  end\n  say \"after\"\nend\n", "t.pks").single()
        val vm = ScriptVM(newCtx(), src, { content.scripts[it] })
        val out = ArrayList<String>()
        while (true) {
            val r = vm.next() ?: break
            if (r is ScriptRequest.Say) out.add(r.text)
            if (r.blocking) vm.resume(if (r is ScriptRequest.WildBattle) wildResult else 1)
        }
        return out
    }

    @Test
    fun `losing a wild battle ends the script after the blackout`() {
        assertEquals(emptyList(), says(0))
    }

    @Test
    fun `wild battles that were won or escaped still continue`() {
        for (result in listOf(1, 2, 3)) assertEquals(listOf("after"), says(result), "result $result")
    }

    @Test
    fun `tm command stays silent when the TM is already owned`() {
        val src = ScriptParser.parseFile("script t\n  tm thunderbolt\n  tm thunderbolt\nend\n", "t.pks").single()
        val said = play(newCtx(), src, 1).filterIsInstance<ScriptRequest.Say>()
        assertEquals(1, said.size)
    }
}
