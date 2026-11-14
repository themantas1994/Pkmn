package pkmn.game.link

import pkmn.engine.battle.Gimmick
import pkmn.engine.link.*
import kotlin.test.*

class LobbySessionTest {
    private fun pair(hostGim: Set<Gimmick> = setOf(Gimmick.MEGA, Gimmick.TERA), guestGim: Set<Gimmick> = setOf(Gimmick.MEGA, Gimmick.ZMOVE),
                     guestVersion: String = "1", guestHash: String = "h", guestParty: Int = 3): Triple<LobbySession, LobbySession, LoopbackLink> {
        val l = LoopbackLink({ 0 })
        val h = LobbySession(LinkRole.HOST, l.a, "HOST", "1", "h", hostGim, 6)
        val g = LobbySession(LinkRole.GUEST, l.b, "GUEST", guestVersion, guestHash, guestGim, guestParty)
        return Triple(h, g, l)
    }
    private fun run(h: LobbySession, g: LobbySession, n: Int = 6) = repeat(n) { h.pump(0); g.pump(0) }

    @Test
    fun `host picks the rules, guest readies, host starts with the common gimmicks`() {
        val (h, g, _) = pair()
        run(h, g)
        assertTrue(h.connected && g.connected)
        assertEquals("GUEST", h.remoteName); assertEquals("HOST", g.remoteName)
        assertEquals(setOf(Gimmick.MEGA), h.commonGimmicks)
        assertEquals(listOf("MEGA"), g.rules?.gimmicks); assertEquals(100, g.rules?.levelCap)
        assertFalse(h.startBattle(), "the guest is not ready yet")
        g.setReady(true); run(h, g)
        assertTrue(h.guestReady)
        h.setFlat(true); run(h, g) // changing the rules withdraws the guest's ready
        assertFalse(h.guestReady); assertFalse(g.guestReady); assertEquals(50, g.rules?.levelCap); assertTrue(g.flat)
        g.setReady(true); run(h, g)
        h.setGimmicks(false); run(h, g)
        assertEquals(emptyList(), g.rules?.gimmicks); assertFalse(g.guestReady)
        g.setReady(true); run(h, g)
        assertTrue(h.startBattle()); run(h, g)
        assertEquals(h.start!!.rules, g.start!!.rules); assertTrue(g.start!!.flat)
        assertNull(h.error); assertNull(g.error)
    }

    @Test
    fun `different versions, content or an empty party end the lobby with a message`() {
        for ((mk, text) in listOf(
            pair(guestVersion = "2") to "version", pair(guestHash = "x") to "content", pair(guestParty = 0) to "no Pokémon")) {
            val (h, g) = mk.first to mk.second
            run(h, g)
            assertNotNull(h.error, text); assertNotNull(g.error)
            assertTrue(h.error!!.contains(text, ignoreCase = true) || g.error!!.contains(text, ignoreCase = true), "${h.error} / ${g.error}")
            assertNull(h.start); assertNull(g.start)
        }
    }

    @Test
    fun `leaving tells the other side and a dropped link ends the lobby`() {
        val (h, g, _) = pair(); run(h, g)
        g.leave(); run(h, g)
        assertTrue(h.error!!.contains("left")); assertTrue(h.finished)
        val (h2, g2, l2) = pair(); run(h2, g2)
        l2.b.close(); run(h2, g2)
        assertNotNull(h2.error)
    }

    @Test
    fun `a host cannot use a gimmick the guest lacks and strangers cannot start the guest`() {
        val l = LoopbackLink({ 0 })
        val g = LobbySession(LinkRole.GUEST, l.b, "GUEST", "1", "h", setOf(Gimmick.MEGA), 3)
        // A hand-made host that offers TERA, which the guest does not have.
        val send = { m: LobbyMsg -> l.a.send(LobbyCodec.encode(m)) }
        send(LobbyHi(LinkProtocol.VERSION, "1", "h", "HOST", listOf("MEGA", "TERA"), 3))
        send(LobbyRules(LinkLobbyRules.rules(false, setOf(Gimmick.TERA)), false))
        g.pump(0); g.pump(0)
        assertNotNull(g.error)
        val l2 = LoopbackLink({ 0 })
        val g2 = LobbySession(LinkRole.GUEST, l2.b, "GUEST", "1", "h", setOf(Gimmick.MEGA), 3)
        l2.a.send(LobbyCodec.encode(LobbyHi(LinkProtocol.VERSION, "1", "h", "HOST", emptyList(), 3)))
        l2.a.send(LobbyCodec.encode(LobbyStart(LinkLobbyRules.rules(false, emptySet()), false))) // no rules, not ready
        g2.pump(0); g2.pump(0)
        assertNotNull(g2.error); assertNull(g2.start)
        val l3 = LoopbackLink({ 0 })
        val g3 = LobbySession(LinkRole.GUEST, l3.b, "GUEST", "1", "h", emptySet(), 3)
        l3.a.send(FrameCodec.encode(Frame(0, Heartbeat))) // a battle frame in the lobby
        g3.pump(0)
        assertNotNull(g3.error)
    }

    @Test
    fun `a ready that crosses a rules change is ignored`() {
        val (h, g, _) = pair(); run(h, g)
        g.setReady(true)      // in flight
        h.setFlat(true)       // host changes the rules before the Ready arrives
        run(h, g)
        assertFalse(h.guestReady, "stale ready must not count")
        assertNull(h.error); assertNull(g.error)
        g.setReady(true); run(h, g)
        assertTrue(h.guestReady); assertTrue(h.startBattle()); run(h, g)
        assertNull(g.error); assertNotNull(g.start)
    }

    @Test
    fun `a peer that connects and never says hello is dropped after the deadline`() {
        val l = LoopbackLink({ 0 })
        val h = LobbySession(LinkRole.HOST, l.a, "HOST", "1", "h", emptySet(), 6, helloTimeoutMs = 1_000)
        h.pump(5_000)
        h.pump(5_999)
        assertNull(h.error, "still within the deadline")
        h.pump(6_000)
        assertFalse(h.connected)
        assertEquals("The other device didn't answer.", h.error)
        assertTrue(l.a.isClosed)
    }

    @Test
    fun `a hello inside the deadline keeps the lobby open past it`() {
        val l = LoopbackLink({ 0 })
        val h = LobbySession(LinkRole.HOST, l.a, "HOST", "1", "h", emptySet(), 6, helloTimeoutMs = 1_000)
        val g = LobbySession(LinkRole.GUEST, l.b, "GUEST", "1", "h", emptySet(), 3, helloTimeoutMs = 1_000)
        h.pump(0); g.pump(500); h.pump(900); g.pump(900)
        assertTrue(h.connected && g.connected)
        h.pump(60_000); g.pump(60_000)
        assertNull(h.error); assertNull(g.error)
    }

    @Test
    fun `the host can choose a trade, which withdraws the guest's ready and starts the trade exchange`() {
        val (h, g, _) = pair(); run(h, g)
        assertFalse(h.trade); assertFalse(g.trade)
        g.setReady(true); run(h, g)
        assertTrue(h.guestReady)
        h.setTrade(true); run(h, g)
        assertTrue(h.trade); assertTrue(g.trade)
        assertFalse(h.guestReady); assertFalse(g.guestReady, "a mode change withdraws the ready")
        assertFalse(h.startBattle())
        g.setReady(true); run(h, g)
        assertTrue(h.startBattle()); run(h, g)
        assertTrue(h.start!!.trade); assertTrue(g.start!!.trade)
        assertNull(h.error); assertNull(g.error)
        // Back to a battle: the flag follows.
        val (h2, g2, _) = pair(); run(h2, g2)
        h2.setTrade(true); run(h2, g2); h2.setTrade(false); run(h2, g2)
        g2.setReady(true); run(h2, g2)
        assertTrue(h2.startBattle()); run(h2, g2)
        assertFalse(h2.start!!.trade); assertFalse(g2.start!!.trade)
    }

    @Test
    fun `a ready that crosses a mode change is ignored, and a guest cannot set the mode`() {
        val (h, g, _) = pair(); run(h, g)
        g.setReady(true)     // in flight
        h.setTrade(true)
        run(h, g)
        assertFalse(h.guestReady, "stale ready must not count")
        g.setTrade(true); run(h, g)
        assertTrue(g.trade, "the guest follows the host")
        h.setTrade(false); run(h, g)
        g.setTrade(true); assertFalse(g.trade, "the guest's own choice is ignored")
    }

    @Test
    fun `a guest refuses a start whose trade flag differs from the last rules`() {
        for (startTrade in listOf(true, false)) {
            val l = LoopbackLink({ 0 })
            val g = LobbySession(LinkRole.GUEST, l.b, "GUEST", "1", "h", emptySet(), 3)
            val send = { m: LobbyMsg -> l.a.send(LobbyCodec.encode(m)) }
            val rules = LinkLobbyRules.rules(false, emptySet())
            send(LobbyHi(LinkProtocol.VERSION, "1", "h", "HOST", emptyList(), 3))
            send(LobbyRules(rules, false, trade = !startTrade))
            g.pump(0); g.pump(0)
            g.setReady(true)
            send(LobbyStart(rules, false, trade = startTrade))
            g.pump(0)
            assertNotNull(g.error, "trade=$startTrade after rules with trade=${!startTrade}"); assertNull(g.start)
        }
        // The same flag is accepted.
        val l = LoopbackLink({ 0 })
        val g = LobbySession(LinkRole.GUEST, l.b, "GUEST", "1", "h", emptySet(), 3)
        val rules = LinkLobbyRules.rules(false, emptySet())
        l.a.send(LobbyCodec.encode(LobbyHi(LinkProtocol.VERSION, "1", "h", "HOST", emptyList(), 3)))
        l.a.send(LobbyCodec.encode(LobbyRules(rules, false, trade = true)))
        g.pump(0); g.pump(0); g.setReady(true)
        l.a.send(LobbyCodec.encode(LobbyStart(rules, false, trade = true)))
        g.pump(0)
        assertNull(g.error); assertTrue(g.start!!.trade)
    }
}
