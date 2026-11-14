package pkmn.engine.campaign

import pkmn.engine.battle.BattleOutcome
import pkmn.engine.battle.Choice
import pkmn.engine.battle.Obedience
import pkmn.engine.link.LinkLobbyRules
import pkmn.engine.link.LoopbackLink
import pkmn.engine.link.TradeTestSupport
import pkmn.engine.link.TradeTestSupport.Clock
import pkmn.engine.link.TradeTestSupport.Device
import pkmn.engine.link.TradeTransaction
import pkmn.engine.mon.Pokemon
import pkmn.engine.save.PendingTrade
import pkmn.engine.save.TradeJournalState
import kotlin.test.*

/** What a link trade does to the Pokémon and the player's game: origin, Pokédex, experience, obedience, evolution. */
class LinkTradeCampaignTest {
    private val dex get() = TradeTestSupport.dex

    private fun journal(given: Pokemon, received: Pokemon) = PendingTrade("t1", TradeJournalState.PREPARED, "MIO", given.uid, 0, given.speciesId, received = received)

    /** A Pokémon of another trainer, as it would arrive. */
    private fun foreign(species: String, level: Int = 30, item: String? = null, metLevel: Int = 7) = Device("MIO", 909, listOf(species), level).ctx.state.party[0].also {
        it.heldItem = item; it.metLevel = metLevel; it.friendship = 200
    }

    private fun trade(a: Device, b: Device): TradeTransaction.Result {
        val clock = Clock()
        return TradeTestSupport.run(LoopbackLink({ clock.t }), clock, a, b).host.result!!
    }

    @Test
    fun `a received Pokémon takes the slot, is an outsider met at its level and is registered`() {
        val a = Device("ARI", 1, listOf("pidgey", "rattata", "sentret"))
        val m = foreign("growlithe")
        assertFalse("growlithe" in a.ctx.state.caught)
        val r = a.ctx.performLinkTrade(journal(a.ctx.state.party[1], m))
        assertEquals(1, r.slot); assertSame(r.received, a.ctx.state.party[1])
        assertEquals(listOf("pidgey", "growlithe", "sentret"), a.ctx.state.party.map { it.speciesId })
        val got = a.ctx.state.party[1]
        assertTrue(got.outsider); assertEquals(30, got.metLevel); assertEquals(7, got.originalMetLevel)
        assertEquals(got.species(dex).baseFriendship, got.friendship)
        assertEquals("MIO", got.otName)
        assertTrue("growlithe" in a.ctx.state.caught && "growlithe" in a.ctx.state.seen)
        assertTrue(got.uid !in a.ctx.state.pendingNicknames, "no nickname prompt")
        assertNull(a.ctx.nextPendingNickname())
        assertEquals("rattata", r.given.speciesId)
    }

    @Test
    fun `held items travel with the Pokémon`() {
        val a = Device("ARI", 1, listOf("pidgey", "rattata")); val b = Device("MIO", 2, listOf("geodude", "zubat"))
        a.ctx.state.party[0].heldItem = "oranberry"; b.ctx.state.party[0].heldItem = "charcoal"
        assertIs<TradeTransaction.Result.Completed>(trade(a, b))
        assertEquals("charcoal", a.ctx.state.party[0].heldItem); assertEquals("oranberry", b.ctx.state.party[0].heldItem)
        assertEquals(0, a.ctx.state.itemCount("charcoal") + a.ctx.state.itemCount("oranberry"), "nothing lands in the bag")
    }

    @Test
    fun `a uid this save already uses is replaced, a new one is kept`() {
        val a = Device("ARI", 1, listOf("pidgey", "rattata"))
        val clash = foreign("growlithe").also { it.uid = a.ctx.state.party[1].uid }
        val r = a.ctx.performLinkTrade(journal(a.ctx.state.party[0], clash))
        assertNotEquals(a.ctx.state.party[1].uid, r.received.uid)
        val fresh = foreign("vulpix")
        val keep = fresh.uid
        assertEquals(keep, a.ctx.performLinkTrade(journal(a.ctx.state.party[0], fresh)).received.uid)
    }

    @Test
    fun `the last Pokémon that can battle cannot be traded away`() {
        val a = Device("ARI", 1, listOf("pidgey", "rattata"))
        a.ctx.state.party[1].hp = 0
        assertFailsWith<IllegalStateException> { a.ctx.performLinkTrade(journal(a.ctx.state.party[0], foreign("growlithe"))) }
        assertEquals(listOf("pidgey", "rattata"), a.ctx.state.party.map { it.speciesId })
    }

    @Test
    fun `a link-traded Pokémon earns 1_5x experience`() {
        fun gain(linkTraded: Boolean): Int {
            val a = Device("ARI", 1, listOf("pidgey", "rattata"))
            val m = foreign("cyndaquil", level = 10, metLevel = 5)
            if (linkTraded) a.ctx.performLinkTrade(journal(a.ctx.state.party[0], m))
            else { m.otName = "ARI"; m.otId = a.ctx.state.trainerId; a.ctx.state.party[0] = m }
            val mon = a.ctx.state.party[0]
            a.ctx.state.addItem("masterball", 5)
            val before = mon.exp
            val b = a.ctx.createWildBattle(a.ctx.factory.create("pidgey", 10, a.ctx.rng), null); b.start()
            while (b.outcome == BattleOutcome.ONGOING) { assertNull(b.choose(b.player, Choice.UseItem("masterball"))); b.advance() }
            return mon.exp - before
        }
        val own = gain(false); val traded = gain(true)
        assertTrue(own > 10)
        assertTrue(traded in (own * 3 / 2 - 1)..(own * 3 / 2 + 1), "own=$own traded=$traded")
    }

    @Test
    fun `obedience compares the received level, and a trade back restores the player's own Pokémon`() {
        val a = Device("ARI", 1, listOf("pidgey", "rattata")); val b = Device("MIO", 2, listOf("zubat", "geodude"), level = 30)
        val pidgey = a.ctx.state.party[0]
        val home = pidgey.metLevel; val homeUid = pidgey.uid
        pidgey.level = 33; pidgey.exp = pidgey.species(dex).growthRate.expForLevel(33)
        // A receives B's level-30 Zubat: with no badge the cap is 20, so it may disobey; reference = received level.
        assertIs<TradeTransaction.Result.Completed>(trade(a, b))
        val zubat = a.ctx.state.party[0]
        assertEquals(30, Obedience.referenceLevel(zubat))
        assertFalse(Obedience.alwaysObeys(zubat, a.ctx.obedienceCap()))
        // At B, ARI's Pidgey is an outsider met at 33.
        val away = b.ctx.state.party[0]
        assertEquals("pidgey", away.speciesId); assertTrue(away.outsider); assertEquals(33, away.metLevel); assertEquals(home, away.originalMetLevel)
        // B trades it back for the Zubat.
        assertIs<TradeTransaction.Result.Completed>(trade(a, b))
        val back = a.ctx.state.party[0]
        assertEquals("pidgey", back.speciesId); assertEquals("ARI", back.otName); assertEquals(a.ctx.state.trainerId, back.otId)
        assertFalse(back.outsider); assertEquals(home, back.metLevel); assertNull(back.originalMetLevel)
        assertEquals(home, Obedience.referenceLevel(back))
        assertEquals(homeUid, back.uid, "its uid was free again")
        assertEquals("zubat", b.ctx.state.party[0].speciesId); assertFalse(b.ctx.state.party[0].outsider)
    }

    @Test
    fun `trade evolutions happen after a link trade and an Everstone stops them`() {
        fun evolveTo(species: String, partner: String, item: String? = null): String? {
            val a = Device("ARI", 1, listOf(partner, "rattata"))
            return a.ctx.performLinkTrade(journal(a.ctx.state.party[0], foreign(species, item = item))).evolveTo
        }
        assertEquals("alakazam", evolveTo("kadabra", "pidgey"))
        assertNull(evolveTo("kadabra", "pidgey", item = "everstone"))
        assertEquals("escavalier", evolveTo("karrablast", "shelmet"))
        assertEquals("accelgor", evolveTo("shelmet", "karrablast"))
        assertNull(evolveTo("karrablast", "pidgey"), "tradewith needs the right partner")
        assertNull(evolveTo("karrablast", "shelmet", item = "everstone"))
        assertEquals("steelix", evolveTo("onix", "pidgey", item = "metalcoat"))
        assertNull(evolveTo("onix", "pidgey"))
        assertNull(evolveTo("pidgey", "rattata"), "no level-up evolution from a trade")
    }

    @Test
    fun `both sides of a Karrablast and Shelmet link trade evolve`() {
        val a = Device("ARI", 1, listOf("karrablast", "rattata")); val b = Device("MIO", 2, listOf("shelmet", "zubat"))
        assertIs<TradeTransaction.Result.Completed>(trade(a, b))
        assertEquals("accelgor", a.store.lastTrade?.evolveTo); assertEquals("escavalier", b.store.lastTrade?.evolveTo)
    }

    @Test
    fun `a trade evolution that needs a held item uses it up`() {
        val a = Device("ARI", 1, listOf("pidgey", "rattata"))
        val r = a.ctx.performLinkTrade(journal(a.ctx.state.party[0], foreign("onix", item = "metalcoat")))
        a.ctx.completeTradeEvolution(r.received, r.evolveTo!!)
        assertEquals("steelix", r.received.speciesId); assertNull(r.received.heldItem)
        assertTrue("steelix" in a.ctx.state.caught)
    }

    @Test
    fun `the Linking Cord still evolves without a partner`() {
        val a = Device("ARI", 1, listOf("kadabra", "karrablast"))
        assertEquals("alakazam", a.ctx.evolutionByItem(a.ctx.state.party[0], "linkingcord", null))
        assertEquals("escavalier", a.ctx.evolutionByItem(a.ctx.state.party[1], "linkingcord", null))
        assertNull(a.ctx.pendingEvolutions(listOf(0, 1), null).firstOrNull(), "no trade evolution on a plain check")
    }

    @Test
    fun `flat battle copies keep met levels within the level`() {
        val a = Device("ARI", 1, listOf("pidgey"), level = 70)
        a.ctx.state.party[0].metLevel = 65; a.ctx.state.party[0].originalMetLevel = 60
        val t = LinkLobbyRules.team(dex, a.ctx.state.party, flat = true)
        assertEquals(50, t[0].metLevel); assertEquals(50, t[0].originalMetLevel)
        assertEquals(65, a.ctx.state.party[0].metLevel, "the party is untouched")
    }
}
