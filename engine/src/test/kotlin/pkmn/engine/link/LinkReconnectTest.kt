package pkmn.engine.link

import pkmn.engine.link.LinkTestSupport.Session
import kotlin.test.*

/** Link play Phase 5: a lost link pauses a started battle for the grace period; the same peer resumes it at the same point. */
class LinkReconnectTest {
    private fun completed(r: LinkResult?) = assertIs<LinkResult.Completed>(r, "$r")

    /** The frame number of the first numbered frame after [turn] was reached, for a cut inside a running battle. */
    private fun frameAtTurn(i: Int, turn: Int): Int {
        val s = Session(i)
        s.run(until = { (s.host.battle?.turn ?: 0) >= turn })
        return s.link.framesSent
    }

    @Test
    fun `a transport dropped mid-battle and restored within the grace period ends with equal state hashes, as if nothing happened`() {
        var resumed = 0
        for (i in 0 until 12) for (silent in listOf(false, true)) {
            val reference = Session(i).run()
            val ref = reference.host.result as? LinkResult.Completed ?: continue
            if (reference.host.battle!!.turn < 3) continue
            val cut = frameAtTurn(i, 2) + i % 3
            val what = "battle $i, ${if (silent) "silent" else "reset"} cut at frame $cut"
            val s = Session(i, LinkFaults(cutAt = cut, silentCut = silent))
            // Play until both notice the loss (a silent one after silenceNoticeMs), then a few seconds more.
            s.run(stepMs = 500, until = { s.host.reconnecting && s.guest.reconnecting })
            assertTrue(s.host.reconnecting && s.guest.reconnecting, "$what: both paused (${s.outcomes()})")
            assertFalse(s.host.isOver || s.guest.isOver, what)
            repeat(10) { s.tick(500) }
            assertTrue(s.host.reconnectLeftMs() > 0, "$what: inside the grace period")
            // The owners connect again: a new link, the same two devices.
            val again = LoopbackLink({ s.now })
            s.host.resume(again.a); s.guest.resume(again.b)
            s.run()
            val h = completed(s.host.result); val g = completed(s.guest.result)
            assertEquals(h.hash, g.hash, "$what: same final state on both")
            assertEquals(ref.hash, h.hash, "$what: same battle as without the cut")
            assertEquals(1, s.host.resumes, what); assertEquals(1, s.guest.resumes, what)
            resumed++
        }
        assertTrue(resumed >= 10, "resumed battles: $resumed")
    }

    @Test
    fun `without a reconnect the battle is void once the grace period runs out`() {
        val cut = frameAtTurn(3, 2)
        val s = Session(3, LinkFaults(cutAt = cut))
        s.run(stepMs = 500, until = { s.host.reconnecting })
        val lostAt = s.now
        s.run(stepMs = 500)
        for (lb in listOf(s.host, s.guest)) {
            val v = assertIs<LinkResult.Voided>(lb.result)
            assertEquals(VoidReason.LINK_LOST, v.reason)
            assertEquals(0, lb.resumes)
        }
        assertTrue(s.now - lostAt in LinkTimeouts().graceMs..LinkTimeouts().graceMs + 1500, "void after the grace period: ${s.now - lostAt} ms")
        // A late reconnect changes nothing.
        val late = LoopbackLink({ s.now })
        s.host.resume(late.a); s.guest.resume(late.b); s.tick()
        assertIs<LinkResult.Voided>(s.host.result)
    }

    @Test
    fun `a stranger or another session on the new connection is dropped and the battle keeps waiting for its peer`() {
        val cut = frameAtTurn(4, 2)
        val reference = Session(4).run()
        val s = Session(4, LinkFaults(cutAt = cut))
        s.run(stepMs = 500, until = { s.host.reconnecting && s.guest.reconnecting })
        // Someone else connects to the host: a Resume with the wrong token, then garbage.
        for (bad in listOf(FrameCodec.encode(Frame(-1, Resume("0".repeat(64), 0))), byteArrayOf(0, 0, 0, 2, '{'.code.toByte(), '}'.code.toByte()))) {
            val stranger = LoopbackLink({ s.now })
            s.host.resume(stranger.a)
            stranger.b.send(bad)
            s.tick(500)
            assertTrue(s.host.reconnecting && !s.host.isOver && s.host.needsTransport, "refused, still waiting")
            assertNull(stranger.b.receive(), "the host revealed nothing to the stranger")
        }
        // The real peer then gets through.
        val again = LoopbackLink({ s.now })
        s.host.resume(again.a); s.guest.resume(again.b)
        s.run()
        assertEquals((reference.host.result as LinkResult.Completed).hash, completed(s.host.result).hash)
        assertEquals(completed(s.host.result).hash, completed(s.guest.result).hash)
    }

    @Test
    fun `before the battle has started a lost link still voids at once`() {
        val s = Session(5, LinkFaults(cutAt = 2))
        s.run()
        assertEquals(VoidReason.LINK_LOST, assertIs<LinkResult.Voided>(s.host.result).reason)
        assertEquals(0, s.host.resumes)
    }

    @Test
    fun `a stranger who connects and stays silent is dropped in time for the real peer`() {
        // A battle that lasts beyond turn 3, so the cut lands inside it.
        val i = (0 until 40).first { Session(it).run().host.battle!!.turn >= 4 }
        val cut = frameAtTurn(i, 2)
        val reference = Session(i).run()
        val s = Session(i, LinkFaults(cutAt = cut))
        s.run(stepMs = 500, until = { s.host.reconnecting && s.guest.reconnecting })
        val stranger = LoopbackLink({ s.now })
        s.host.resume(stranger.a)
        assertFalse(s.host.needsTransport, "holding the stranger's connection")
        repeat(8) { s.tick(500) } // 4 s: longer than resumeAnswerMs
        assertTrue(s.host.needsTransport && s.host.reconnecting && !s.host.isOver, "the silent connection was dropped: needs=${s.host.needsTransport} rec=${s.host.reconnecting} over=${s.host.result} left=${s.host.reconnectLeftMs()}")
        val again = LoopbackLink({ s.now })
        s.host.resume(again.a); s.guest.resume(again.b)
        s.run()
        assertEquals((reference.host.result as LinkResult.Completed).hash, completed(s.guest.result).hash)
    }

    @Test
    fun `a link lost while the players pick pauses and resumes too`() {
        val rules = LinkTestSupport.rules().copy(pick = 3)
        val teams = pkmn.engine.util.Rng(99).let { r -> List(4) { LinkTestSupport.randomMon(r, 40) } to List(4) { LinkTestSupport.randomMon(r, 40) } }
        val ref = Session(8, cfgHost = LinkTestSupport.config(rules), cfgGuest = LinkTestSupport.config(rules), fixedTeams = teams, hostPick = listOf(0, 1, 2), guestPick = listOf(3, 2, 1)).run()
        // Cut right after both teams: the guest has not picked yet.
        val s = Session(8, LinkFaults(cutAt = 4), cfgHost = LinkTestSupport.config(rules), cfgGuest = LinkTestSupport.config(rules), fixedTeams = teams, hostPick = listOf(0, 1, 2))
        s.run(stepMs = 500, until = { s.host.reconnecting && s.guest.reconnecting })
        assertNull(s.host.battle); assertTrue(s.host.canResume && s.guest.canResume)
        s.guestPick = listOf(3, 2, 1)
        repeat(4) { s.tick(500) }
        val again = LoopbackLink({ s.now })
        s.host.resume(again.a); s.guest.resume(again.b)
        s.run()
        assertEquals(completed(ref.host.result).hash, completed(s.host.result).hash)
        assertEquals(completed(s.host.result).hash, completed(s.guest.result).hash)
    }
}

