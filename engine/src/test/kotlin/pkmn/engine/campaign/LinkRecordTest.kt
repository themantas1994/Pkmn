package pkmn.engine.campaign

import pkmn.engine.battle.BattleOutcome
import pkmn.engine.link.LinkResult
import pkmn.engine.link.LinkFaults
import pkmn.engine.link.LoopbackLink
import pkmn.engine.link.TradeTestSupport
import pkmn.engine.link.TradeTestSupport.Clock
import pkmn.engine.link.TradeTestSupport.Device
import pkmn.engine.link.VoidReason
import pkmn.engine.save.LinkRecord
import kotlin.test.*

/** The link record on the trainer card: battles won, lost, drawn and void, and trades done, stored in the save. */
class LinkRecordTest {
    @Test
    fun `battle results count from each device's side`() {
        val host = Device("ARI", 1, listOf("pidgey")); val guest = Device("MIO", 2, listOf("zubat"))
        val hostWon = LinkResult.Completed(BattleOutcome.WIN, 0, "h")
        host.ctx.recordLinkBattle(hostWon, localSide = 0); guest.ctx.recordLinkBattle(hostWon, localSide = 1)
        val guestWon = LinkResult.Completed(BattleOutcome.LOSE, 1, "h")
        host.ctx.recordLinkBattle(guestWon, 0); guest.ctx.recordLinkBattle(guestWon, 1)
        host.ctx.recordLinkBattle(guestWon, 0)
        val void = LinkResult.Voided(VoidReason.LINK_LOST, "")
        host.ctx.recordLinkBattle(void, 0); guest.ctx.recordLinkBattle(void, 1)
        host.ctx.recordLinkBattle(LinkResult.Completed(BattleOutcome.DRAW, null, "h"), 0)
        assertEquals(LinkRecord(won = 1, lost = 2, drawn = 1, voided = 1, trades = 0), host.ctx.state.linkRecord)
        assertEquals(LinkRecord(won = 1, lost = 1, drawn = 0, voided = 1, trades = 0), guest.ctx.state.linkRecord)
        // Stored in the save.
        assertEquals(SaveResultOk, host.store.let { pkmn.engine.save.SaveManager(host.dir, TradeTestSupport.dex).save(host.ctx.state) })
        assertEquals(host.ctx.state.linkRecord, host.load().state.linkRecord)
    }

    @Test
    fun `a trade counts once on each device, in the save that applies the swap`() {
        val a = Device("ARI", 101, listOf("pidgey", "rattata")); val b = Device("MIO", 202, listOf("kadabra", "geodude"))
        val clock = Clock()
        TradeTestSupport.run(LoopbackLink({ clock.t }), clock, a, b)
        assertEquals(1, a.ctx.state.linkRecord.trades); assertEquals(1, b.ctx.state.linkRecord.trades)
        assertEquals(1, a.load().state.linkRecord.trades); assertEquals(1, b.load().state.linkRecord.trades)
    }

    @Test
    fun `a failed commit does not count, and a trade finished after a reconnect counts once`() {
        val a = Device("ARI", 101, listOf("pidgey", "rattata")); val b = Device("MIO", 202, listOf("kadabra", "geodude"))
        val clock = Clock()
        // The one-message window: only the host swaps.
        TradeTestSupport.interrupt(a, b, committed = "host", clock = clock)
        assertEquals(1, a.load().state.linkRecord.trades); assertEquals(0, b.load().state.linkRecord.trades)
        // The guest's finishing save fails once: nothing counted; the next link finishes and counts it once.
        b.failSaves = 1
        TradeTestSupport.run(LoopbackLink({ clock.t }), clock, a, b, offer = false, nonces = "c3" to "d4")
        assertEquals(0, b.ctx.state.linkRecord.trades); assertEquals(0, b.load().state.linkRecord.trades)
        TradeTestSupport.run(LoopbackLink({ clock.t }), clock, a, b, offer = false, nonces = "e5" to "f6")
        assertEquals(1, b.load().state.linkRecord.trades); assertEquals(1, a.load().state.linkRecord.trades)
    }

    private companion object { val SaveResultOk = pkmn.engine.save.SaveResult.Saved }
}
