package pkmn.engine.link

import pkmn.engine.link.TradeTestSupport.Clock
import pkmn.engine.link.TradeTestSupport.Device
import pkmn.engine.link.TradeTestSupport.interrupt
import pkmn.engine.link.TradeTestSupport.run
import pkmn.engine.save.TradeJournalState
import kotlin.test.*

/**
 * What an open trade journal locks while the player keeps playing, and the manual way out of the LINK menu. The host
 * ARI offers its Pidgey (party slot 0) and the guest MIO its Kadabra.
 */
class TradeJournalLockTest {
    private fun devices() = Device("ARI", 101, listOf("pidgey", "rattata", "pidgey")) to Device("MIO", 202, listOf("kadabra", "geodude"))
    private val lock = "Pidgey is promised to MIO in an unfinished link trade."

    /** ARI holds a PREPARED journal for its Pidgey and MIO a COMMITTED one (MIO received ARI's PREPARED, ARI never got MIO's), after a restart of both. */
    private fun preparedHost(): Pair<Device, Device> {
        val (a, b) = devices()
        interrupt(a, b, committed = "guest")
        assertEquals(TradeJournalState.PREPARED, a.ctx.state.pendingTrade?.state)
        assertEquals(TradeJournalState.COMMITTED, b.ctx.state.pendingTrade?.state)
        a.restart(); b.restart()
        return a to b
    }

    @Test
    fun `the given Pokémon cannot be deposited`() {
        val (a, _) = preparedHost()
        assertEquals(lock, a.ctx.depositError(0))
        assertEquals(lock, a.ctx.depositToPc(0))
        assertEquals("pidgey", a.ctx.state.party[0].speciesId); assertEquals(3, a.ctx.state.party.size)
        assertNull(a.ctx.depositToPc(1), "the others still can")
    }

    @Test
    fun `the given Pokémon cannot be released`() {
        val (a, _) = preparedHost()
        // The given Pokémon cannot reach a box while the journal is open (deposit is locked); put it there anyway, as a
        // save edited by hand or an older build could have.
        val given = a.ctx.state.party.removeAt(0)
        a.ctx.state.boxes[0][0] = given
        a.ctx.state.boxes[0][1] = a.ctx.state.party.removeAt(1)
        assertEquals(lock, a.ctx.releaseError(0, 0))
        assertEquals(lock, a.ctx.releaseFromPc(0, 0))
        assertSame(given, a.ctx.state.boxes[0][0])
        assertNull(a.ctx.releaseFromPc(0, 1), "another Pokémon can be released"); assertNull(a.ctx.state.boxes[0][1])
    }

    @Test
    fun `the given Pokémon cannot go to an in-game trade`() {
        val (a, _) = preparedHost()
        assertEquals(listOf(0, 2), a.ctx.tradeCandidates("pidgey"))
        assertEquals(lock, a.ctx.linkTradeLock(a.ctx.state.party[0]))
        val offer = a.ctx.factory.create("rattata", 10, a.ctx.rng)
        val e = assertFailsWith<IllegalStateException> { a.ctx.performTrade(0, "pidgey", offer) }
        assertEquals(lock, e.message)
        assertEquals("pidgey", a.ctx.state.party[0].speciesId)
        // The other Pidgey is free.
        assertNull(a.ctx.linkTradeLock(a.ctx.state.party[2]))
        a.ctx.performTrade(2, "pidgey", offer)
        assertEquals("rattata", a.ctx.state.party[2].speciesId)
    }

    @Test
    fun `the given Pokémon cannot be offered in a link trade, and a trade with someone else is refused`() {
        val (a, _) = preparedHost()
        assertEquals(lock, a.ctx.linkTradeRefusal(0))
        val c = Device("KAI", 303, listOf("zubat", "geodude"))
        val r = run(LoopbackLink({ 0 }), Clock(), a, c, nonces = "s1" to "t2")
        assertIs<TradeTransaction.Result.Cancelled>(r.host.result); assertIs<TradeTransaction.Result.Cancelled>(r.guest.result)
        assertNull(r.host.localOffer, "nothing was offered")
        assertEquals(TradeJournalState.PREPARED, a.load().tradeJournal?.state)
        assertEquals("zubat", c.ctx.state.party[0].speciesId)
    }

    @Test
    fun `the given Pokémon can still battle and gain levels, and the trade still finishes`() {
        val (a, b) = preparedHost()
        val pidgey = a.ctx.state.party[0]
        pidgey.hp = 0
        a.ctx.healParty()
        val gained = a.ctx.progression.addExp(pidgey, 20_000)
        assertTrue(pidgey.level > 20, "levels: $gained")
        assertNull(a.ctx.linkTradeLock(a.ctx.state.party[1]), "only the given Pokémon is locked")
        run(LoopbackLink({ 0 }), Clock(), a, b, offer = false, nonces = "u3" to "v4")
        assertEquals("kadabra", a.ctx.state.party[0].speciesId); assertNull(a.load().tradeJournal)
        assertNull(a.ctx.depositError(1), "nothing is locked any more")
    }

    @Test
    fun `nothing is locked by a COMMITTED journal and link battles do not look at journals`() {
        val (a, b) = devices()
        interrupt(a, b, committed = "host")
        assertEquals(TradeJournalState.COMMITTED, a.ctx.state.pendingTrade?.state)
        assertTrue(a.ctx.state.party.all { a.ctx.linkTradeLock(it) == null })
        assertNull(a.ctx.depositError(0))
        // A link battle team is built from the party as it is, the given Pokémon included.
        val team = LinkLobbyRules.team(TradeTestSupport.dex, b.ctx.state.party, flat = false)
        assertEquals(b.ctx.state.party.size, team.size)
    }

    @Test
    fun `manual way out of a PREPARED journal keeps the given Pokémon and warns what the partner may keep`() {
        val (a, b) = preparedHost()
        val warning = assertNotNull(a.ctx.linkTradeAbandonWarning())
        assertEquals("If you cancel it, you keep Pidgey and won't get Kadabra. If MIO's game already finished the trade, MIO keeps Pidgey too and Kadabra is lost. Only do this if MIO will never link with you again.", warning)
        assertEquals("Cancel the trade with MIO?", a.ctx.linkTradeAbandonQuestion())
        assertNotNull(a.ctx.linkTradeSummary())
        // A failed save leaves it open.
        a.failSaves = 1
        assertFails { a.store.abandon() }
        assertEquals(TradeJournalState.PREPARED, a.ctx.state.pendingTrade?.state)
        assertEquals("The trade with MIO was cancelled. You kept Pidgey.", a.store.abandon())
        assertNull(a.ctx.state.pendingTrade); assertNull(a.load().tradeJournal); assertTrue(a.load().notices.isEmpty())
        assertEquals("pidgey", a.ctx.state.party[0].speciesId)
        assertNull(a.ctx.linkTradeAbandonWarning()); assertNull(a.ctx.linkTradeSummary()); assertNull(a.ctx.depositError(0))
        // The consequence the warning names: MIO had finished, so MIO keeps the Pidgey too once it links again.
        run(LoopbackLink({ 0 }), Clock(), a, b, offer = false, nonces = "w5" to "x6")
        assertNull(b.load().tradeJournal)
        assertEquals("pidgey", b.ctx.state.party[0].speciesId); assertEquals("pidgey", a.ctx.state.party[0].speciesId)
    }

    @Test
    fun `manual way out of a COMMITTED journal keeps the received Pokémon and warns what the partner may keep`() {
        val (a, b) = devices()
        interrupt(a, b, committed = "host")
        a.restart(); b.restart()
        assertEquals("End the trade with MIO?", a.ctx.linkTradeAbandonQuestion())
        assertEquals("If you end it, you keep Kadabra. If MIO's game didn't finish the trade, MIO keeps Kadabra too and your Pidgey is lost. Only do this if MIO will never link with you again.",
            a.ctx.linkTradeAbandonWarning())
        assertEquals("The trade with MIO was ended. You keep Kadabra.", a.store.abandon())
        assertNull(a.load().tradeJournal); assertEquals("kadabra", a.ctx.state.party[0].speciesId)
        // The consequence: MIO was PREPARED and now finds no journal, so it cancels and keeps its Kadabra.
        val r = run(LoopbackLink({ 0 }), Clock(), a, b, offer = false, nonces = "y7" to "z8")
        assertEquals("The interrupted trade with ARI didn't happen. You kept Kadabra.", r.guest.notice)
        assertEquals("kadabra", b.ctx.state.party[0].speciesId); assertNull(b.load().tradeJournal)
    }

    @Test
    fun `the partner is recognised by name and by trainer id when the journal has one`() {
        val mon = Device("X", 1, listOf("pidgey")).ctx.state.party[0]
        val j = pkmn.engine.save.PendingTrade("t", TradeJournalState.PREPARED, "MIO", 1, 0, "pidgey", received = mon, partnerTrainerId = 7)
        assertTrue(pkmn.engine.save.TradeJournal.isPartner(j, "MIO", 7))
        assertFalse(pkmn.engine.save.TradeJournal.isPartner(j, "MIO", 8))
        assertFalse(pkmn.engine.save.TradeJournal.isPartner(j, "MIO", null), "a peer that sends no id is not this partner")
        assertFalse(pkmn.engine.save.TradeJournal.isPartner(j, "MIA", 7))
        // A journal written before ids were stored (link play Phase 4) matches nobody: a name alone could be someone else.
        val old = j.copy(partnerTrainerId = null)
        assertFalse(pkmn.engine.save.TradeJournal.isPartner(old, "MIO", 7)); assertFalse(pkmn.engine.save.TradeJournal.isPartner(old, "MIO", null))
    }
}
