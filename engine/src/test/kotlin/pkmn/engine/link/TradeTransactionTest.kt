package pkmn.engine.link

import pkmn.engine.link.TradeTestSupport.Clock
import pkmn.engine.link.TradeTestSupport.Device
import pkmn.engine.link.TradeTestSupport.Recording
import pkmn.engine.link.TradeTestSupport.assertUniqueUids
import pkmn.engine.link.TradeTestSupport.dex
import pkmn.engine.link.TradeTestSupport.run
import pkmn.engine.save.TradeJournalState
import kotlin.test.*

/** The trade protocol over a loopback link, with real saves: the happy path, refusals, and a cut at every message. */
class TradeTransactionTest {
    private fun devices() = Device("ARI", 101, listOf("pidgey", "rattata", "sentret")) to Device("MIO", 202, listOf("kadabra", "geodude"))

    /** True when [d]'s party holds the Pokémon [other] offered (same species, other's OT) in slot [slot]. */
    private fun hasReceived(d: Device, otherName: String, species: String, slot: Int = 0) =
        d.ctx.state.party.getOrNull(slot)?.let { it.speciesId == species && it.otName == otherName } == true

    @Test
    fun `a trade swaps both Pokémon in their slots and clears both journals`() {
        val (a, b) = devices()
        val aGiven = a.ctx.state.party[0]; val bGiven = b.ctx.state.party[0]
        val clock = Clock()
        val r = run(LoopbackLink({ clock.t }), clock, a, b)
        val ra = assertIs<TradeTransaction.Result.Completed>(r.host.result); val rb = assertIs<TradeTransaction.Result.Completed>(r.guest.result)
        assertTrue(ra.confirmed && rb.confirmed)
        assertTrue(hasReceived(a, "MIO", "kadabra")); assertTrue(hasReceived(b, "ARI", "pidgey"))
        assertEquals(3, a.ctx.state.party.size); assertEquals(2, b.ctx.state.party.size)
        assertNull(a.ctx.state.findByUid(aGiven.uid)?.takeIf { it.otName == "ARI" && it.speciesId == "pidgey" })
        assertTrue(b.ctx.state.party.none { it === bGiven })
        for (d in listOf(a, b)) {
            assertNull(d.ctx.state.pendingTrade)
            val l = d.load()
            assertTrue(l.warnings.isEmpty(), "${l.warnings}"); assertTrue(l.notices.isEmpty()); assertNull(l.tradeJournal)
            assertUniqueUids(l.state.ownedPokemon(), d.name)
        }
        assertEquals(r.host.tradeId, r.guest.tradeId)
    }

    @Test
    fun `a decline on either side cancels with nothing saved`() {
        for (hostAccepts in listOf(true, false)) {
            val (a, b) = devices()
            val before = a.partyUids to b.partyUids
            val clock = Clock()
            val r = run(LoopbackLink({ clock.t }), clock, a, b, hostAccept = hostAccepts, guestAccept = !hostAccepts)
            assertIs<TradeTransaction.Result.Cancelled>(r.host.result); assertIs<TradeTransaction.Result.Cancelled>(r.guest.result)
            assertEquals(before, a.partyUids to b.partyUids)
            assertNull(a.load().tradeJournal); assertNull(b.load().tradeJournal)
        }
    }

    @Test
    fun `the last Pokémon that can battle cannot be offered`() {
        val a = Device("ARI", 101, listOf("pidgey", "rattata")); val b = Device("MIO", 202, listOf("kadabra"))
        a.ctx.state.party[1].hp = 0
        assertNotNull(a.ctx.linkTradeRefusal(0)); assertNull(a.ctx.linkTradeRefusal(1))
        assertNotNull(b.ctx.linkTradeRefusal(0), "an only Pokémon is always the last")
        val clock = Clock(); val link = LoopbackLink({ clock.t })
        val tx = TradeTransaction(LinkRole.HOST, link.a, dex, a.store, "ARI", "MIO")
        tx.pump(0); link.b.send(TradeCodec.encode(TradeSync(party = listOf(TradePartyEntry("pidgey", 5))))); tx.pump(0)
        assertEquals(TradeTransaction.Stage.OFFER, tx.stage)
        assertEquals("That's your last POKéMON that can battle!", tx.offer(a.ctx.state.party[0]))
        assertNull(tx.offer(a.ctx.state.party[1]), "a fainted one may go while a healthy one stays")
    }

    @Test
    fun `an offer that fails validation ends the trade on both sides`() {
        val (a, b) = devices()
        val clock = Clock(); val link = LoopbackLink({ clock.t })
        val h = TradeTransaction(LinkRole.HOST, link.a, dex, a.store, "ARI", "MIO")
        h.pump(0)
        // A modified client sends a level 250 Pokémon.
        link.b.send(TradeCodec.encode(TradeSync(party = listOf(TradePartyEntry("pidgey", 5)))))
        link.b.send(TradeCodec.encode(TradeOffer(b.ctx.state.party[0].copy().also { it.level = 250 }, "x")))
        h.pump(0)
        assertIs<TradeTransaction.Result.Cancelled>(h.result)
        assertNull(a.load().tradeJournal)
        // The bad device is told why.
        val msgs = generateSequence { link.b.receive() }.map { TradeCodec.decode(it) }.toList()
        assertTrue(msgs.any { it is TradeCancel && it.reason == VoidReason.BAD_TEAM.name }, "$msgs")
    }

    @Test
    fun `a failed save before PREPARED cancels the trade and leaves memory as it was`() {
        val (a, b) = devices()
        a.failSaves = 1
        val clock = Clock()
        val r = run(LoopbackLink({ clock.t }), clock, a, b)
        assertIs<TradeTransaction.Result.Cancelled>(r.host.result); assertIs<TradeTransaction.Result.Cancelled>(r.guest.result)
        assertNull(a.ctx.state.pendingTrade)
        assertEquals("pidgey", a.ctx.state.party[0].speciesId); assertEquals("kadabra", b.ctx.state.party[0].speciesId)
        // The guest may have prepared before it heard; its journal is gone and nothing was swapped.
        assertNull(b.ctx.state.pendingTrade); assertNull(b.load().tradeJournal)
    }

    /** The order of the frames of one complete trade: index -> "host:TradePrepared" and so on. */
    private fun frameOrder(): List<Pair<Int, String>> {
        val (a, b) = devices()
        val clock = Clock(); val link = LoopbackLink({ clock.t }); val log = ArrayList<Pair<Int, String>>()
        val r = run(link, clock, a, b, hostTransport = Recording(link.a, link, "host", log), guestTransport = Recording(link.b, link, "guest", log))
        assertIs<TradeTransaction.Result.Completed>(r.host.result)
        return log
    }

    @Test
    fun `a cut at every message index leaves both saves sound and resolves as in the plan`() {
        val order = frameOrder()
        assertEquals(10, order.size, "sync, offer, confirm, prepared and committed from each side: $order")
        assertEquals((0 until 10).toList(), order.map { it.first })
        val hostPrepared = order.single { it.second == "host:TradePrepared" }.first
        val guestPrepared = order.single { it.second == "guest:TradePrepared" }.first
        for (silent in listOf(false, true)) for (cut in 0..order.size) {
            val what = "cut at $cut (${order.getOrNull(cut)?.second ?: "none"}), ${if (silent) "silent" else "reset"}"
            val (a, b) = devices()
            val sizes = a.ctx.state.party.size to b.ctx.state.party.size
            val clock = Clock()
            val link = LoopbackLink({ clock.t }, LinkFaults(cutAt = cut, silentCut = silent))
            val r = run(link, clock, a, b)
            assertTrue(r.host.isOver && r.guest.isOver, "$what: both sessions end")
            // The plan's table: a device swaps exactly when the other's PREPARED reached it.
            val hostSwapped = guestPrepared < cut; val guestSwapped = hostPrepared < cut
            assertEquals(hostSwapped, hasReceived(a, "MIO", "kadabra"), "$what: host")
            assertEquals(guestSwapped, hasReceived(b, "ARI", "pidgey"), "$what: guest")
            if (hostSwapped != guestSwapped) assertTrue(cut == maxOf(hostPrepared, guestPrepared), "$what: one-sided only in the one-message window")
            for ((d, swapped, size) in listOf(Triple(a, hostSwapped, sizes.first), Triple(b, guestSwapped, sizes.second))) {
                val l = d.load()
                assertTrue(l.warnings.isEmpty(), "$what ${d.name}: ${l.warnings}")
                assertEquals(size, l.state.party.size, "$what ${d.name}: party size")
                assertUniqueUids(l.state.ownedPokemon(), "$what ${d.name}")
                val other = if (d === a) "MIO" else "ARI"
                assertEquals(swapped, l.state.party[0].otName == other, "$what ${d.name}: the saved party matches the outcome")
                when (l.tradeJournal?.state) {
                    TradeJournalState.PREPARED -> { assertFalse(swapped); assertEquals(1, l.notices.size, "$what ${d.name}: the player is told") }
                    TradeJournalState.COMMITTED -> { assertTrue(swapped); assertTrue(l.notices.isEmpty()) }
                    null -> assertTrue(l.notices.isEmpty())
                }
            }
        }
    }

    @Test
    fun `after a cut a reconnect with the same trade id finishes a one-sided trade`() {
        val order = frameOrder()
        val hostPrepared = order.single { it.second == "host:TradePrepared" }.first
        val guestPrepared = order.single { it.second == "guest:TradePrepared" }.first
        for (cut in minOf(hostPrepared, guestPrepared)..order.size) {
            val (a, b) = devices()
            val clock = Clock()
            run(LoopbackLink({ clock.t }, LinkFaults(cutAt = cut)), clock, a, b)
            val anySwapped = hasReceived(a, "MIO", "kadabra") || hasReceived(b, "ARI", "pidgey")
            // Same session, new connection: both sync and back out of a new trade.
            val r = run(LoopbackLink({ clock.t }), clock, a, b, offer = false)
            assertIs<TradeTransaction.Result.Cancelled>(r.host.result)
            // A swap on either device completes on both; otherwise neither changes.
            assertEquals(anySwapped, hasReceived(a, "MIO", "kadabra"), "cut $cut host")
            assertEquals(anySwapped, hasReceived(b, "ARI", "pidgey"), "cut $cut guest")
            for (d in listOf(a, b)) {
                assertNull(d.ctx.state.pendingTrade, "cut $cut ${d.name}: journal resolved")
                val l = d.load()
                assertNull(l.tradeJournal); assertTrue(l.warnings.isEmpty(), "${l.warnings}")
                assertUniqueUids(l.state.ownedPokemon(), d.name)
            }
            if (anySwapped && !(guestPrepared < cut && hostPrepared < cut)) {
                val prepared = if (guestPrepared < cut) b else a
                assertTrue(r.host.notice != null || r.guest.notice != null, "cut $cut: the completing device tells its player")
                assertNotNull((if (prepared === a) r.host else r.guest).notice)
            }
        }
    }

    @Test
    fun `a stale journal from another partner is resolved before a new trade`() {
        val (a, b) = devices()
        val c = Device("KAI", 303, listOf("geodude", "zubat"))
        val clock = Clock()
        // Cut at the guest's PREPARED: the host has saved PREPARED and never hears back.
        val order = frameOrder()
        run(LoopbackLink({ clock.t }, LinkFaults(cutAt = order.single { it.second == "guest:TradePrepared" }.first)), clock, a, b)
        assertEquals(TradeJournalState.PREPARED, a.ctx.state.pendingTrade?.state)
        val r = run(LoopbackLink({ clock.t }), clock, a, c, offer = false)
        assertEquals("The link trade with MIO was not finished. You kept Pidgey.", r.host.notice)
        assertNull(a.ctx.state.pendingTrade); assertEquals("pidgey", a.ctx.state.party[0].speciesId)
    }

    @Test
    fun `each device sees the other's party, and a bad party summary ends the trade`() {
        val (a, b) = devices()
        val clock = Clock()
        val r = run(LoopbackLink({ clock.t }), clock, a, b)
        assertEquals(listOf("kadabra", "geodude"), r.host.remoteParty.map { it.speciesId })
        assertEquals(listOf("pidgey", "rattata", "sentret"), r.guest.remoteParty.map { it.speciesId })
        for (bad in listOf(emptyList(), List(7) { TradePartyEntry("pidgey", 5) }, listOf(TradePartyEntry("notamon", 5)),
            listOf(TradePartyEntry("pidgey", 101)), listOf(TradePartyEntry("pidgey", 5, nickname = "\u0007")))) {
            val (c, _) = devices()
            val link = LoopbackLink({ 0 })
            val tx = TradeTransaction(LinkRole.HOST, link.a, dex, c.store, "ARI", "MIO")
            tx.pump(0); link.b.send(TradeCodec.encode(TradeSync(party = bad))); tx.pump(0)
            assertIs<TradeTransaction.Result.Cancelled>(tx.result, "$bad")
        }
    }

    @Test
    fun `a player who confirmed can still back out while waiting, and the other side cancels too`() {
        val (a, b) = devices()
        val clock = Clock(); val link = LoopbackLink({ clock.t })
        val h = TradeTransaction(LinkRole.HOST, link.a, dex, a.store, "ARI", "MIO")
        val g = TradeTransaction(LinkRole.GUEST, link.b, dex, b.store, "MIO", "ARI")
        repeat(4) { h.pump(0); g.pump(0) }
        assertNull(h.offer(a.ctx.state.party[0])); assertNull(g.offer(b.ctx.state.party[0]))
        repeat(2) { h.pump(0); g.pump(0) }
        h.confirm(true); h.pump(0)
        assertEquals(TradeTransaction.Stage.CONFIRM, h.stage)
        h.cancel()
        assertIs<TradeTransaction.Result.Cancelled>(h.result)
        repeat(2) { g.pump(0) }
        assertEquals("ARI cancelled the trade.", (g.result as TradeTransaction.Result.Cancelled).reason)
        assertNull(a.load().tradeJournal); assertNull(b.load().tradeJournal)
    }

    @Test
    fun `a decline that arrives first ends the trade at once`() {
        val (a, b) = devices()
        val link = LoopbackLink({ 0 })
        val h = TradeTransaction(LinkRole.HOST, link.a, dex, a.store, "ARI", "MIO")
        val g = TradeTransaction(LinkRole.GUEST, link.b, dex, b.store, "MIO", "ARI")
        repeat(4) { h.pump(0); g.pump(0) }
        h.offer(a.ctx.state.party[0]); g.offer(b.ctx.state.party[0])
        repeat(2) { h.pump(0); g.pump(0) }
        g.confirm(false)
        h.pump(0)
        assertEquals("MIO cancelled the trade.", (h.result as TradeTransaction.Result.Cancelled).reason)
    }

    @Test
    fun `a failed commit save leaves the journal PREPARED in memory and on disk`() {
        val (a, b) = devices()
        // Saves: initial (done), PREPARED (ok), commit (fails).
        val clock = Clock(); val link = LoopbackLink({ clock.t })
        val h = TradeTransaction(LinkRole.HOST, link.a, dex, a.store, "ARI", "MIO")
        val g = TradeTransaction(LinkRole.GUEST, link.b, dex, b.store, "MIO", "ARI")
        repeat(4) { h.pump(0); g.pump(0) }
        h.offer(a.ctx.state.party[0]); g.offer(b.ctx.state.party[0])
        repeat(2) { h.pump(0); g.pump(0) }
        a.failOnSave = 2 // PREPARED saves, the commit save fails
        h.confirm(true); g.confirm(true)
        repeat(4) { h.pump(0); g.pump(0) }
        assertIs<TradeTransaction.Result.Interrupted>(h.result)
        assertEquals(TradeJournalState.PREPARED, a.ctx.state.pendingTrade?.state)
        assertEquals("pidgey", a.ctx.state.party[0].speciesId)
        val l = a.load()
        assertEquals(TradeJournalState.PREPARED, l.tradeJournal?.state); assertEquals("pidgey", l.state.party[0].speciesId)
    }

    @Test
    fun `malformed or out of order trade frames end the trade`() {
        val (a, _) = devices()
        val link = LoopbackLink({ 0 })
        val tx = TradeTransaction(LinkRole.HOST, link.a, dex, a.store, "ARI", "MIO")
        tx.pump(0)
        link.b.send(TradeCodec.encode(TradePrepared("x")))
        tx.pump(0)
        assertIs<TradeTransaction.Result.Cancelled>(tx.result)
        assertFailsWith<LinkProtocolException> { TradeCodec.decode(byteArrayOf(0, 0, 0, 2, '{'.code.toByte(), '}'.code.toByte())) }
    }
}
