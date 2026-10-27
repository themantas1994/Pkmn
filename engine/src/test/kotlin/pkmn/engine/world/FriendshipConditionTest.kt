package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

class FriendshipConditionTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    private fun passes(ctx: GameContext, cond: String): Boolean {
        val src = "script t\n  if $cond\n    setflag yes\n  end\nend"
        val vm = ScriptVM(ctx, ScriptParser.parseFile(src, "t.pks").single(), { null })
        while (true) { val r = vm.next() ?: break; if (r.blocking) vm.resume() }
        return ctx.state.hasFlag("yes")
    }

    @Test
    fun `met when a party mon of the species reaches the threshold`() {
        val ctx = newCtx()
        ctx.receivePokemon(ctx.factory.create("pikachu", 5, ctx.rng).also { it.friendship = 200 })
        assertTrue(passes(ctx, "friendship pikachu >= 150"))
    }

    @Test
    fun `unmet when friendship is below the threshold`() {
        val ctx = newCtx()
        ctx.receivePokemon(ctx.factory.create("pikachu", 5, ctx.rng).also { it.friendship = 70 })
        assertFalse(passes(ctx, "friendship pikachu >= 150"))
    }

    @Test
    fun `unmet when no party mon of the species exists`() {
        val ctx = newCtx()
        ctx.receivePokemon(ctx.factory.create("rowlet", 5, ctx.rng).also { it.friendship = 255 })
        assertFalse(passes(ctx, "friendship pikachu >= 0"))
    }

    @Test
    fun `any matching party member counts and PC mons do not`() {
        val ctx = newCtx()
        ctx.receivePokemon(ctx.factory.create("pikachu", 5, ctx.rng).also { it.friendship = 10 })
        ctx.receivePokemon(ctx.factory.create("pikachu", 5, ctx.rng).also { it.friendship = 220 })
        assertTrue(passes(ctx, "friendship pikachu >= 220"))
        val ctx2 = newCtx()
        ctx2.state.boxes[0][0] = ctx2.factory.create("pikachu", 5, ctx2.rng).also { it.friendship = 255 }
        assertFalse(passes(ctx2, "friendship pikachu >= 0"))
    }
}
