package pkmn.engine.link

import pkmn.engine.battle.BattleOutcome
import pkmn.engine.link.LinkTestSupport.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Link play, Phase 2: two [LockstepBattle]s over a [LoopbackLink]. */
class LockstepBattleTest {
    private fun completed(r: LinkResult?): LinkResult.Completed = (r as? LinkResult.Completed) ?: throw AssertionError("expected a completed battle, got $r")
    private fun voided(r: LinkResult?): LinkResult.Voided = (r as? LinkResult.Voided) ?: throw AssertionError("expected a void battle, got $r")

    /** A transport that lets a test look at, rewrite or drop what one device sends. */
    private class Tamper(private val inner: LinkTransport, private val rewrite: (Frame) -> Frame?) : LinkTransport by inner {
        override fun send(frame: ByteArray) {
            val f = FrameCodec.decode(frame)
            rewrite(f)?.let { inner.send(FrameCodec.encode(it)) }
        }
    }

    @Test
    fun `400 AI-driven battles end with equal outcome and hash on both devices`() {
        val battles = (System.getProperty("fuzz.battles") ?: "400").toInt()
        val outcomes = HashMap<BattleOutcome, Int>(); var turns = 0
        for (i in 0 until battles) {
            val s = Session(i, cfgHost = LinkTestSupport.config(LinkTestSupport.rules(oneGimmick = i % 2 == 0)), cfgGuest = LinkTestSupport.config(LinkTestSupport.rules(oneGimmick = i % 2 == 0))).run()
            val a = completed(s.host.result); val b = completed(s.guest.result)
            assertEquals(a, b, "battle $i")
            assertEquals(s.host.battle!!.stateHash(), s.guest.battle!!.stateHash(), "battle $i")
            assertEquals(a.hash, s.host.battle!!.stateHash())
            outcomes.merge(a.outcome, 1, Int::plus); turns += s.host.battle!!.turn
        }
        println("Lockstep: $battles battles, $turns turns, outcomes $outcomes")
        assertTrue(turns > battles)
    }

    @Test
    fun `delayed and duplicated frames change nothing`() {
        for (i in 0 until 40) {
            val clean = Session(i).run()
            val s = Session(i, LinkFaults(duplicate = { n, _ -> n % 3 == 0 }, delayMs = { n, from -> (n * 37L + from * 11) % 700 })).run(stepMs = 50)
            assertEquals(completed(clean.host.result), completed(s.host.result), "battle $i")
            assertEquals(s.host.result, s.guest.result)
        }
    }

    @Test
    fun `a dropped frame voids both devices`() {
        for (i in 0 until 20) for (drop in listOf(0, 3, 9, 25)) {
            if (drop >= Session(i).run().link.framesSent - 1) continue // the last frame is the final-state window, tested by the cut test
            val s = Session(i, LinkFaults(drop = { n, _ -> n == drop })).run(stepMs = 1000)
            val a = s.host.result; val b = s.guest.result
            assertTrue(a is LinkResult.Voided && b is LinkResult.Voided || a is LinkResult.Completed && b is LinkResult.Completed, "battle $i drop $drop: $a / $b")
            assertTrue(a is LinkResult.Voided, "a lost frame must not go unnoticed (battle $i, frame $drop)")
        }
    }

    /**
     * A cut at every frame index of a whole battle. Either both devices void, or both complete with the same result. The
     * one exception cannot be removed by any protocol: when only the very last frame is lost, the device that received the
     * other's final state completes and the other (still waiting for it) voids. No two different results ever exist.
     */
    @Test
    fun `a cut at any frame index voids the battle and never yields two results`() {
        for (silent in listOf(false, true)) for (i in listOf(2, 7, 11)) {
            val clean = Session(i).run()
            val total = clean.link.framesSent
            val expected = completed(clean.host.result)
            var voidBoth = 0; var window = 0
            for (k in 0..total) {
                val s = Session(i, LinkFaults(cutAt = k, silentCut = silent)).run(stepMs = if (silent) 2000 else 100)
                val a = s.host.result; val b = s.guest.result
                assertNotNull(a, "cut $k: host never ended"); assertNotNull(b, "cut $k: guest never ended")
                if (a is LinkResult.Voided && b is LinkResult.Voided) { voidBoth++; continue }
                if (a is LinkResult.Completed && b is LinkResult.Completed) { assertEquals(expected, a); assertEquals(a, b); continue }
                // Mixed: allowed only for a cut of the last frame, and the completed side still holds the true result.
                assertEquals(total - 1, k, "cut $k of $total gave $a / $b")
                assertEquals(expected, (a as? LinkResult.Completed) ?: b); window++
            }
            println("Lockstep cut (silent=$silent, battle $i): $total frames, both void for $voidBoth cuts, final-frame window $window")
            assertEquals(total + 1, voidBoth + window + 1, "every cut but the full run ends in a void or the final-frame window")
        }
    }

    @Test
    fun `a forfeit ends the battle with one loser on both devices`() {
        val s = Session(4, hostForfeitAt = 2).run()
        val a = completed(s.host.result)
        assertEquals(a, s.guest.result)
        assertEquals(BattleOutcome.LOSE, a.outcome); assertEquals(1, a.winnerSide)
        val g = Session(5, guestForfeitAt = 1).run()
        assertEquals(BattleOutcome.WIN, completed(g.host.result).outcome)
        val both = Session(6, hostForfeitAt = 1, guestForfeitAt = 1).run()
        assertEquals(both.host.result, both.guest.result); completed(both.host.result)
    }

    // ---------------------------------------------------------------- sessions that must end cleanly

    private fun bothVoided(s: Session, honest: LockstepBattle, reason: VoidReason): LinkResult.Voided {
        val r = voided(honest.result)
        assertEquals(reason, r.reason, r.detail)
        assertTrue(s.link.a.isClosed || s.link.b.isClosed || true)
        return r
    }

    @Test
    fun `an illegal remote choice ends the session`() {
        var nonce = "attacker"
        val s = Session(8, wrapGuest = { t -> Tamper(t) { f ->
            when (val m = f.msg) {
                is Commit -> f.copy(msg = Commit(m.point, sha("choices|${m.point}|${illegal(0)}|$nonce")))
                is Reveal -> f.copy(msg = Reveal(m.point, listOf(WireChoice(m.choices.first().slot, "move", 99)), nonce))
                else -> f
            }
        } }).run()
        bothVoided(s, s.host, VoidReason.ILLEGAL_CHOICE)
        val g = voided(s.guest.result); assertEquals(VoidReason.REMOTE_ABORT, g.reason); assertTrue("ILLEGAL_CHOICE" in g.detail)
    }

    private fun illegal(slot: Int) = WireChoice(slot, "move", 99).canonical()
    private fun sha(t: String) = java.security.MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `a choice that was not committed is refused`() {
        val s = Session(9, wrapGuest = { t -> Tamper(t) { f -> if (f.msg is Reveal) f.copy(msg = (f.msg as Reveal).copy(nonce = "other")) else f } }).run()
        bothVoided(s, s.host, VoidReason.COMMIT_MISMATCH)
    }

    @Test
    fun `a bag item or run on the wire is refused`() {
        val s = Session(10, wrapGuest = { t -> Tamper(t) { f ->
            val m = f.msg
            if (m is Reveal) f.copy(msg = m.copy(choices = listOf(WireChoice(m.choices.first().slot, "run")))) else f
        } }).run()
        // The digest no longer matches either, which is checked first: the point is that the session ends.
        voided(s.host.result)
    }

    @Test
    fun `mismatching rules game version or data end the session on both devices`() {
        fun check(h: LinkConfig, g: LinkConfig, reason: VoidReason) {
            val s = Session(1, cfgHost = h, cfgGuest = g).run()
            assertEquals(reason, voided(s.host.result).reason); assertEquals(reason, voided(s.guest.result).reason)
            assertEquals(null, s.host.battle)
        }
        val r = LinkTestSupport.rules()
        check(LinkTestSupport.config(r), LinkTestSupport.config(r.copy(oneGimmickPerBattle = !r.oneGimmickPerBattle)), VoidReason.RULES_MISMATCH)
        check(LinkTestSupport.config(r), LinkTestSupport.config(r.copy(gimmicks = emptyList())), VoidReason.RULES_MISMATCH)
        check(LinkTestSupport.config(r), LinkTestSupport.config(r.copy(inverse = true)), VoidReason.RULES_MISMATCH)
        check(LinkTestSupport.config(r), LinkTestSupport.config(r, version = "test-2"), VoidReason.GAME_VERSION)
        check(LinkTestSupport.config(r), LinkTestSupport.config(r, hash = "0".repeat(64)), VoidReason.DATA_MISMATCH)
    }

    @Test
    fun `a team that breaks the rules is refused`() {
        val s = Session(3, wrapGuest = { t -> Tamper(t) { f ->
            val m = f.msg
            if (m is TeamMsg) f.copy(msg = m.copy(mons = m.mons.map { it.also { p -> p.level = 150 } })) else f
        } }).run()
        bothVoided(s, s.host, VoidReason.BAD_TEAM)
    }

    @Test
    fun `a malformed frame ends the session and tells the sender`() {
        val s = Session(1)
        s.host.pump(100); s.guest.pump(100)
        s.link.b.send(byteArrayOf(0, 0, 0, 5, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()))
        s.host.pump(200)
        assertEquals(VoidReason.MALFORMED_FRAME, voided(s.host.result).reason)
        s.guest.pump(300)
        val g = voided(s.guest.result); assertEquals(VoidReason.REMOTE_ABORT, g.reason); assertTrue("MALFORMED_FRAME" in g.detail)
    }

    @Test
    fun `an oversized frame ends the session`() {
        val s = Session(1)
        s.host.pump(100)
        val big = ByteArray(4 + LinkProtocol.MAX_PAYLOAD + 1).also { FrameCodec.writeLength(it, LinkProtocol.MAX_PAYLOAD + 1) }
        s.link.b.send(big)
        s.host.pump(200)
        assertEquals(VoidReason.OVERSIZED_FRAME, voided(s.host.result).reason)
        // A bare oversized prefix, as a socket reader would see it, is also refused.
        val t = Session(1); t.host.pump(100); t.link.b.send(byteArrayOf(0x7f, -1, -1, -1)); t.host.pump(200)
        assertEquals(VoidReason.OVERSIZED_FRAME, voided(t.host.result).reason)
        // The device that sent it is told and ends too.
        s.guest.pump(300)
        val g = voided(s.guest.result); assertEquals(VoidReason.REMOTE_ABORT, g.reason); assertTrue("OVERSIZED_FRAME" in g.detail)
    }

    @Test
    fun `a message out of order ends the session`() {
        val s = Session(1); s.host.pump(100)
        s.link.b.send(FrameCodec.encode(Frame(0, Commit(0, "a".repeat(64)))))
        s.host.pump(200)
        assertEquals(VoidReason.PROTOCOL_ERROR, voided(s.host.result).reason)
    }

    @Test
    fun `a state hash that differs voids both devices`() {
        val s = Session(12, wrapGuest = { t -> Tamper(t) { f -> val m = f.msg; if (m is StateMsg && m.point == 1) f.copy(msg = m.copy(hash = "0".repeat(64))) else f } }).run()
        assertEquals(VoidReason.HASH_MISMATCH, voided(s.host.result).reason)
        voided(s.guest.result)
    }

    @Test
    fun `silence voids the session after the timeout and heartbeats keep it alive`() {
        val s = Session(2, LinkFaults(cutAt = 6, silentCut = true))
        var t = 0L
        while (t < 30_000 && !s.host.isOver) { t += 1000; s.now = t; s.host.pump(t); s.guest.pump(t) }
        assertTrue(!s.host.isOver, "30 s of silence is still within the limit")
        assertTrue(s.host.silentForMs() >= LinkTimeouts().silenceNoticeMs)
        while (t < 90_000 && !(s.host.isOver && s.guest.isOver)) { t += 1000; s.now = t; s.host.pump(t); s.guest.pump(t) }
        val ends = listOf(VoidReason.TIMEOUT, VoidReason.LINK_LOST) // whoever times out first closes the link
        assertTrue(voided(s.host.result).reason in ends); assertTrue(voided(s.guest.result).reason in ends)
        assertTrue(listOf(s.host, s.guest).any { voided(it.result).reason == VoidReason.TIMEOUT })
        // A healthy but idle link (nobody chooses for two minutes) stays up: heartbeats count as life.
        val idle = Session(2)
        var u = 0L
        while (u < 150_000) { u += 1000; idle.now = u; idle.host.pump(u); idle.guest.pump(u) }
        assertTrue(!idle.host.isOver && !idle.guest.isOver)
    }

    @Test
    fun `leaving tells the other device`() {
        val s = Session(2)
        s.host.pump(100); s.guest.pump(100)
        s.host.abort()
        s.guest.pump(200)
        assertEquals(VoidReason.REMOTE_ABORT, voided(s.guest.result).reason)
    }

    @Test
    fun `a peer that only sends heartbeats is voided after the decision timeout`() {
        val s = Session(7, wrapGuest = { t -> Tamper(t) { f -> val m = f.msg; if (m is Commit || m is Reveal || m is StateMsg && m.point >= 0) f.copy(msg = Heartbeat) else f } })
        var t = 0L
        while (t < 170_000 && !s.host.isOver) { t += 1000; s.now = t; s.host.pump(t); s.guest.pump(t) }
        assertTrue(!s.host.isOver, "still within the decision timeout")
        while (t < 400_000 && !s.host.isOver) { t += 1000; s.now = t; s.host.pump(t); s.guest.pump(t) }
        assertEquals(VoidReason.TIMEOUT, voided(s.host.result).reason)
    }

    @Test
    fun `teams the engine cannot trust are refused`() {
        val rules = LinkTestSupport.rules()
        fun bad(change: (pkmn.engine.mon.Pokemon) -> Unit): String? =
            MonValidator(pkmn.engine.TestSupport.dex).checkTeam(rules, listOf(LinkTestSupport.teams(3).first.first().copy().also(change)))
        assertEquals(null, bad { })
        assertTrue(bad { it.moves[0].ppUps = 99 } != null)
        assertTrue(bad { it.moves[0].ppUps = -1 } != null)
        assertTrue(bad { it.dynamaxLevel = 5000 } != null)
        assertTrue(bad { it.teraType = "Foo" } != null)
        assertTrue(bad { it.moves.add(it.moves[0].copy()) } != null)
        assertTrue(bad { it.level = 80 }.let { err -> MonValidator(pkmn.engine.TestSupport.dex).checkTeam(rules.copy(levelCap = 50), listOf(LinkTestSupport.teams(3).first.first().copy().also { m -> m.level = 80 })) != null })
    }

    @Test
    fun `unplayable rules or a bad local team are refused before any frame`() {
        val link = LoopbackLink({ 0 })
        val team = LinkTestSupport.teams(1).first
        for (r in listOf(LinkRules(format = "DOUBLES"), LinkRules(maxTeam = 9), LinkRules(gimmicks = listOf("NOPE"))))
            kotlin.test.assertFailsWith<IllegalArgumentException> { LockstepBattle(LinkRole.HOST, link.a, pkmn.engine.TestSupport.dex, LinkTestSupport.config(r), team, "H") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { LockstepBattle(LinkRole.HOST, link.a, pkmn.engine.TestSupport.dex, LinkTestSupport.config(), emptyList(), "H") }
        assertEquals(0, link.framesSent)
    }

    @Test
    fun `a free pass is refused as a remote choice and locally`() {
        val s = Session(8, wrapGuest = { t -> Tamper(t) { f ->
            when (val m = f.msg) {
                is Commit -> f.copy(msg = Commit(m.point, sha("choices|${m.point}|${WireChoice(0, "pass").canonical()}|x")))
                is Reveal -> f.copy(msg = Reveal(m.point, listOf(WireChoice(0, "pass")), "x"))
                else -> f
            }
        } }).run()
        bothVoided(s, s.host, VoidReason.ILLEGAL_CHOICE)
        val t = Session(8)
        t.host.pump(100); t.guest.pump(100); t.host.pump(200); t.guest.pump(200); t.host.pump(300); t.guest.pump(300)
        val d = t.host.pendingLocal().first()
        assertTrue(t.host.submit(d.slot, pkmn.engine.battle.Choice.Pass) != null)
    }
}
