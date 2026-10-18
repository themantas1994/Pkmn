package pkmn.engine.campaign

import pkmn.engine.TestSupport
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.world.FieldMove
import java.io.File
import kotlin.test.*

/** God Bidoof performs every unlocked field move without any party Pokémon. */
class GodBidoofTest {
    private val content by lazy { WorldContent(DirectoryContentSource(File(System.getProperty("pkmn.assets")))) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    @Test
    fun `an empty party can use exactly the field moves its badges unlock`() {
        val ctx = newCtx()
        assertTrue(ctx.state.party.isEmpty())
        for (fm in FieldMove.entries) assertFalse(ctx.canUseFieldMove(fm), "$fm locked with no badges")
        for (badges in 1..8) {
            ctx.state.badges.clear(); ctx.state.badges.addAll(1..badges)
            for (fm in FieldMove.entries) assertEquals(fm.badgeRequired <= badges, ctx.canUseFieldMove(fm), "$fm at $badges badges")
        }
    }

    @Test
    fun `using a field move leaves the party and save state untouched`() {
        val ctx = newCtx()
        ctx.state.badges.addAll(1..8)
        val before = ctx.state.party.toList()
        FieldMove.entries.forEach { assertTrue(ctx.canUseFieldMove(it)) }
        assertEquals(before, ctx.state.party)
    }

    @Test
    fun `the announcement names the move`() {
        val ctx = newCtx()
        assertEquals("God Bidoof answered your prayers and used CUT!", ctx.bidoofLine(FieldMove.CUT))
        assertEquals("God Bidoof answered your prayers and used ROCK SMASH!", ctx.bidoofLine(FieldMove.ROCK_SMASH))
        assertEquals("God Bidoof answered your prayers and used WATERFALL!", ctx.bidoofLine(FieldMove.WATERFALL))
    }
}
