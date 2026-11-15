package pkmn.engine.link

import pkmn.engine.TestSupport.dex
import pkmn.engine.link.LinkTestSupport.Session
import pkmn.engine.util.Rng
import kotlin.test.*

/** Link play Phase 5: the "three against three" preset, each player picking three after seeing both teams. */
class LinkPickTest {
    private val pickRules = LinkTestSupport.rules().copy(pick = 3)
    private fun cfg() = LinkTestSupport.config(pickRules)
    private fun sixes(i: Int) = Rng(77000L + i).let { r -> List(6) { LinkTestSupport.randomMon(r, 50) } to List(6) { LinkTestSupport.randomMon(r, 50) } }

    /** Rewrites what a device sends (a modified client). */
    private class Tamper(private val inner: LinkTransport, private val rewrite: (Frame) -> Frame) : LinkTransport by inner {
        override fun send(frame: ByteArray) { inner.send(FrameCodec.encode(rewrite(FrameCodec.decode(frame)))) }
    }

    /** Every frame a device sends, decoded, with the link's frame number. */
    private class Log(private val inner: LinkTransport, private val link: () -> LoopbackLink, private val who: String, private val out: MutableList<Pair<Int, String>>) : LinkTransport by inner {
        override fun send(frame: ByteArray) { out.add(link().framesSent to "$who:${FrameCodec.decode(frame).msg::class.simpleName}"); inner.send(frame) }
    }

    @Test
    fun `three against three plays in lockstep with the picked Pokémon in the order picked`() {
        var played = 0
        for (i in 0 until 30) {
            val (t1, t2) = sixes(i)
            val hp = listOf(i % 6, (i + 2) % 6, (i + 4) % 6); val gp = listOf(5, 0, (i % 4) + 1)
            val s = Session(i, cfgHost = cfg(), cfgGuest = cfg(), fixedTeams = t1 to t2, hostPick = hp, guestPick = gp).run()
            val h = assertIs<LinkResult.Completed>(s.host.result, "battle $i: ${s.outcomes()}")
            val g = assertIs<LinkResult.Completed>(s.guest.result, "battle $i")
            assertEquals(h.hash, g.hash, "battle $i")
            for (lb in listOf(s.host, s.guest)) {
                val b = lb.battle!!
                // Base species: a form can change in battle (Silvally with its memory).
                fun base(id: String) = dex.species(id).baseSpecies
                assertEquals(hp.map { base(t1[it].speciesId) }, b.sides[0].mons.map { it.species.baseSpecies }, "battle $i host side")
                assertEquals(gp.map { base(t2[it].speciesId) }, b.sides[1].mons.map { it.species.baseSpecies }, "battle $i guest side")
            }
            played++
        }
        assertEquals(30, played)
    }

    @Test
    fun `picks are not sent before both players have committed`() {
        val (t1, t2) = sixes(1)
        val log = ArrayList<Pair<Int, String>>()
        lateinit var s: Session
        s = Session(1, cfgHost = cfg(), cfgGuest = cfg(), fixedTeams = t1 to t2,
            wrapHost = { Log(it, { s.link }, "host", log) }, wrapGuest = { Log(it, { s.link }, "guest", log) }, hostPick = listOf(0, 1, 2))
        // The host picks at once; the guest takes its time.
        repeat(50) { s.tick() }
        assertTrue(s.host.awaitingRemotePick && s.guest.awaitingLocalPick)
        val sent = log.map { it.second }
        assertTrue("host:PickCommit" in sent, "the host committed: $sent")
        assertTrue(sent.none { it.endsWith("PickReveal") || it.endsWith("SeedReveal") }, "nothing about the picks or the seed before both committed: $sent")
        assertNull(s.host.battle); assertNull(s.guest.battle)
        // The guest's team view while it picks is the host's whole team, never the host's picks.
        assertEquals(6, s.guest.remoteTeam!!.size)
        assertNotNull(s.guest.pick(listOf(0, 0, 1)), "a pick with a repeat is refused")
        assertNotNull(s.guest.pick(listOf(0, 1)), "too few")
        assertTrue(s.guest.awaitingLocalPick)
        assertNull(s.guest.pick(listOf(3, 4, 5)))
        s.run()
        assertIs<LinkResult.Completed>(s.host.result)
        val firstReveal = log.indexOfFirst { it.second.endsWith("PickReveal") }
        val commits = log.withIndex().filter { it.value.second.endsWith("PickCommit") }.map { it.index }
        assertEquals(2, commits.size); assertEquals(2, log.count { it.second.endsWith("PickReveal") })
        assertTrue(commits.all { it < firstReveal }, "both commitments before the first reveal: $log")
    }

    @Test
    fun `a reveal that does not match its commitment voids the battle`() {
        val (t1, t2) = sixes(2)
        // The guest commits to one set of picks and reveals another.
        val s = Session(2, cfgHost = cfg(), cfgGuest = cfg(), fixedTeams = t1 to t2, hostPick = listOf(0, 1, 2), guestPick = listOf(0, 1, 2),
            wrapGuest = { t -> Tamper(t) { f -> val m = f.msg; if (m is PickReveal) f.copy(msg = m.copy(indices = listOf(3, 4, 5))) else f } }).run()
        assertEquals(VoidReason.COMMIT_MISMATCH, assertIs<LinkResult.Voided>(s.host.result).reason)
    }

    @Test
    fun `a team smaller than the pick is refused`() {
        val rules = pickRules
        assertNotNull(MonValidator(dex).checkTeam(rules, sixes(3).first.take(2)))
        assertNull(MonValidator(dex).checkTeam(rules, sixes(3).first.take(3)))
        assertNotNull(rules.copy(pick = 7).error())
    }
}
