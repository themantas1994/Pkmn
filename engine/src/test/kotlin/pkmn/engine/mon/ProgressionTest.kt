package pkmn.engine.mon

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

class ProgressionTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun ctxWith(mon: Pokemon): GameContext {
        val ctx = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))
        ctx.state.party.clear(); ctx.state.party.add(mon)
        return ctx
    }

    @Test
    fun `inkay evolves only when the device is upside down`() {
        val c = ctxWith(TestSupport.mon("inkay", level = 30))
        assertTrue(c.pendingEvolutions(listOf(0), null).isEmpty(), "default: not upside down")
        assertEquals("malamar", c.pendingEvolutions(listOf(0), null, upsideDown = true).firstOrNull()?.second)
    }

    @Test
    fun `a Rare Candy level-up evolves inkay when the device is upside down`() {
        val mon = TestSupport.mon("inkay", level = 29)
        val c = ctxWith(mon)
        c.state.addItem("rarecandy", 1)
        val r = pkmn.engine.campaign.FieldItems(c).useOn(mon, "rarecandy", upsideDown = true)
        assertTrue(r.ok, r.message)
        assertEquals("malamar", r.evolveTo)
    }
}
