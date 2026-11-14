package pkmn.engine.link

import pkmn.engine.TestSupport.dex
import pkmn.engine.battle.Gimmick
import pkmn.engine.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkLobbyTest {
    @Test
    fun `address codes round trip for every kind of address`() {
        for (ip in listOf("0.0.0.0", "127.0.0.1", "10.0.2.2", "192.168.1.20", "172.16.254.3", "255.255.255.255")) {
            val code = LinkAddress.encode(ip)!!
            assertEquals(LinkAddress.CODE_LENGTH, code.length)
            assertTrue(code.none { it in "01IO" }, code)
            assertEquals(ip, LinkAddress.decode(code))
            assertEquals(LinkEndpoint(ip, LinkAddress.DEFAULT_PORT), LinkAddress.parse(code.lowercase()))
        }
        val rng = Rng(5)
        repeat(500) { val ip = "${rng.nextInt(256)}.${rng.nextInt(256)}.${rng.nextInt(256)}.${rng.nextInt(256)}"; assertEquals(ip, LinkAddress.decode(LinkAddress.encode(ip)!!)) }
    }

    @Test
    fun `manual addresses and bad input`() {
        assertEquals(LinkEndpoint("192.168.0.7", 47840), LinkAddress.parse("192.168.0.7"))
        assertEquals(LinkEndpoint("192.168.0.7", 5000), LinkAddress.parse(" 192.168.0.7:5000 "))
        for (bad in listOf("", "ABC", "ABCDEFGH", "0000000", "ABCDEF1", "300.1.1.1", "1.2.3", "1.2.3.4:0", "1.2.3.4:99999", "1.2.3.4:x", "1..2.3", "a.b.c.d", "ZZZZZZZ"))
            assertNull(LinkAddress.parse(bad), bad)
        assertNull(LinkAddress.encode("1.2.3"))
    }

    @Test
    fun `only local addresses are accepted`() {
        for (ip in listOf("10.0.2.2", "127.0.0.1", "192.168.43.1", "172.16.0.1", "172.31.255.255", "172.20.10.2", "169.254.1.1")) assertTrue(LinkAddress.isLocal(ip), ip)
        for (ip in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.64.0.1", "1.2.3", "::1", "")) assertTrue(!LinkAddress.isLocal(ip), ip)
    }

    @Test
    fun `lobby frames round trip and reject garbage`() {
        val rules = LinkLobbyRules.rules(true, setOf(Gimmick.TERA, Gimmick.MEGA))
        val msgs = listOf(LobbyHi(LinkProtocol.VERSION, "v", "h", "ARI", listOf("MEGA"), 3), LobbyRules(rules, true), LobbyReady(true), LobbyStart(rules, true), LobbyLeave)
        for (m in msgs) assertEquals(m, LobbyCodec.decode(LobbyCodec.encode(m)))
        // A battle frame is not a lobby frame and the other way round.
        assertFailsWith<LinkProtocolException> { LobbyCodec.decode(FrameCodec.encode(Frame(0, Heartbeat))) }
        assertFailsWith<LinkProtocolException> { FrameCodec.decode(LobbyCodec.encode(LobbyLeave)) }
        assertFailsWith<LinkProtocolException> { LobbyCodec.decode(byteArrayOf(0, 0, 0, 5, 1, 2, 3, 4, 5)) }
        assertFailsWith<LinkProtocolException> { LobbyCodec.decode(byteArrayOf(0x7f, 0, 0, 0)) }
    }

    @Test
    fun `rules use the gimmicks both players have and a flat level cap`() {
        assertEquals(setOf(Gimmick.MEGA), LinkLobbyRules.common(listOf("MEGA", "TERA", "BOGUS"), listOf("MEGA", "ZMOVE", "BOGUS")))
        val flat = LinkLobbyRules.rules(true, setOf(Gimmick.TERA, Gimmick.MEGA))
        assertEquals(listOf("MEGA", "TERA"), flat.gimmicks); assertEquals(50, flat.levelCap); assertNull(flat.error())
        val asIs = LinkLobbyRules.rules(false, emptySet())
        assertEquals(100, asIs.levelCap); assertNull(asIs.error()); asIs.toBattleRules()
    }

    @Test
    fun `team copies are healed, capped at six and leave the party alone`() {
        val rng = Rng(9)
        val party = List(8) { LinkTestSupport.randomMon(rng, 20 + it * 10) }
        party[0].hp = 1; party[0].moves[0].pp = 0
        val hp0 = party[0].hp; val pp0 = party[0].moves[0].pp; val lvl = party.map { it.level }
        val asIs = LinkLobbyRules.team(dex, party, flat = false)
        assertEquals(6, asIs.size)
        assertEquals(lvl.take(6), asIs.map { it.level })
        assertEquals(asIs[0].maxHp(dex), asIs[0].hp)
        assertEquals(asIs[0].moves[0].maxPp(dex), asIs[0].moves[0].pp)
        assertEquals(hp0, party[0].hp); assertEquals(pp0, party[0].moves[0].pp); assertEquals(lvl, party.map { it.level })
        val flat = LinkLobbyRules.team(dex, party, flat = true)
        assertTrue(flat.all { it.level == 50 && it.exp == it.species(dex).growthRate.expForLevel(50) && it.hp == it.maxHp(dex) })
        assertNotEquals(party[7].level, 50)
        assertNull(TeamCheck.error(dex, LinkLobbyRules.rules(true, emptySet()), flat))
        assertNull(TeamCheck.error(dex, LinkLobbyRules.rules(false, emptySet()), asIs))
    }

    @Test
    fun `a pause notice reaches the other device and clears with the next message`() {
        val l = LoopbackLink({ 0 })
        val rules = LinkTestSupport.rules()
        val ta = LinkTestSupport.teams(1)
        val a = LockstepBattle(LinkRole.HOST, l.a, dex, LinkTestSupport.config(rules), ta.first, "A") { 1L }
        val b = LockstepBattle(LinkRole.GUEST, l.b, dex, LinkTestSupport.config(rules), ta.second, "B") { 2L }
        repeat(5) { a.pump(0); b.pump(0) }
        assertTrue(a.battle != null && b.battle != null)
        a.notifyPaused(); b.pump(0)
        assertTrue(b.remotePaused)
        assertTrue(!a.isOver && !b.isOver)
    }
}
