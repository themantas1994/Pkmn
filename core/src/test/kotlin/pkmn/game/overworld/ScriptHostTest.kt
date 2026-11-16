package pkmn.game.overworld

import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.Dex
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.world.ScriptParser
import pkmn.engine.world.ScriptVM
import pkmn.game.PkmnGame
import pkmn.game.Platform
import java.io.File
import kotlin.test.*

/**
 * Runs real compiled scripts through [ScriptHost] without a GL context. Only scripts whose requests are all
 * non-blocking can run here: dialogue needs the font atlas, which only exists once libGDX has created the game.
 */
class ScriptHostTest {
    private val platform = object : Platform {
        override val name = "test"
        override val isMobile = false
        override val saveDir: File = File(System.getProperty("java.io.tmpdir"))
        override val importedAssetsDir: File? = null
        override fun log(msg: String) {}
    }
    private val game = PkmnGame(platform)
    private val dex by lazy { Dex.loadDefault() }
    private val content by lazy { WorldContent(DirectoryContentSource(File(System.getProperty("pkmn.assets")))) }
    private fun newCtx() = GameContext(dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    private fun host(ctx: GameContext, source: String, onFinish: () -> Unit): ScriptHost {
        val scripts = ScriptParser.parseFile(source, "test").associateBy { it.id }
        return ScriptHost(game, OverworldScreen(game, ctx), ctx, ScriptVM(ctx, scripts.getValue("t"), { scripts[it] }), onFinish)
    }

    @Test
    fun `a script without blocking requests runs to the end and finishes exactly once`() {
        val ctx = newCtx()
        var finished = 0
        val h = host(ctx, "script t\n  sfx menu_move\n  setflag host_test_done\nend\n") { finished++ }
        h.step()
        assertEquals(1, finished)
        assertTrue(ctx.state.hasFlag("host_test_done"), "the script's state changes are applied before finish")
        h.step() // a late step (e.g. a stale callback) must not finish again
        assertEquals(1, finished)
    }

    @Test
    fun `an empty script still finishes`() {
        var finished = 0
        host(newCtx(), "script t\nend\n") { finished++ }.step()
        assertEquals(1, finished)
    }
}
