package pkmn.game.battle

import pkmn.engine.battle.BattleEvent
import pkmn.engine.battle.Choice
import pkmn.engine.data.Dex
import pkmn.engine.link.*
import pkmn.engine.mon.PokemonFactory
import pkmn.engine.util.Rng
import kotlin.test.*

/** [BattleFlow] with a remote opponent: two devices over a loopback link, each driven the way the battle screen does. */
class BattleFlowLinkTest {
    companion object {
        val dex: Dex by lazy { Dex.loadDefault() }
        val factory by lazy { PokemonFactory(dex) }
        val hash by lazy { LinkFingerprint.dataHash(dex) }
    }

    private class Device(val lb: LockstepBattle, val forfeitOnTurn: Int? = null, val autoChoose: Boolean = true) {
        var flow: BattleFlow? = null
        val phases = ArrayList<BattleFlow.Phase>()
        var ended = false
        val seen = ArrayList<BattleEvent>()
        val waited get() = phases.contains(BattleFlow.Phase.WAITING_REMOTE)

        /** One screen frame: pump the session, present events instantly, follow the phase. */
        fun frame(now: Long) {
            lb.pump(now)
            if (ended) return
            val b = lb.battle ?: return
            val f = flow ?: BattleFlow(b, link = lb).also { flow = it; it.startPresenting() }
            while (true) { f.nextEvent()?.let { seen.add(it) } ?: break }
            if (phases.lastOrNull() != f.phase) phases.add(f.phase)
            when (f.phase) {
                BattleFlow.Phase.PRESENTING -> f.settle()
                BattleFlow.Phase.CHOOSE_ACTION -> if (autoChoose) assertNull(f.submit(if (forfeitOnTurn != null && b.turn >= forfeitOnTurn) Choice.Forfeit else Choice.UseMove(0)))
                BattleFlow.Phase.FORCED_SWITCH -> assertNull(f.forcedPick(b.sides[f.localSide].mons.indexOfFirst { !it.fainted && !it.isActive }))
                BattleFlow.Phase.WAITING_REMOTE -> f.pollLink()
                BattleFlow.Phase.ENDING -> { f.takeOutcome(); ended = true }
                else -> fail("unexpected phase ${f.phase}")
            }
        }
    }

    private fun team(seed: Int, n: Int) = (0 until n).map { i -> factory.create(listOf("charizard", "blastoise", "venusaur", "pikachu", "snorlax", "gengar")[(i + seed) % 6], 40 + i, Rng(seed + i.toLong()), moves = listOf("tackle", "ember")) }

    private fun run(forfeitGuestOnTurn: Int? = null, fuzz: Int = 0): Pair<Device, Device> {
        val l = LoopbackLink({ 0 }, if (fuzz > 0) LinkFaults(delayMs = { n, _ -> 0 }) else LinkFaults())
        val cfg = LinkConfig("t", LinkLobbyRules.rules(false, emptySet()), dataHash = hash)
        val h = Device(LockstepBattle(LinkRole.HOST, l.a, dex, cfg, team(0, 3), "HOST"))
        val g = Device(LockstepBattle(LinkRole.GUEST, l.b, dex, cfg, team(2, 3), "GUEST"), forfeitGuestOnTurn)
        var t = 0L
        repeat(2000) { if (h.ended && g.ended) return h to g; h.frame(t); g.frame(t); t += 16 }
        fail("did not end: host ${h.phases.takeLast(5)} guest ${g.phases.takeLast(5)}")
    }

    @Test
    fun `each device decides only for its own side and both end with the same result`() {
        val (h, g) = run()
        assertEquals(0, h.flow!!.localSide); assertEquals(1, g.flow!!.localSide)
        val rh = assertIs<LinkResult.Completed>(h.lb.result); val rg = assertIs<LinkResult.Completed>(g.lb.result)
        assertEquals(rh.hash, rg.hash); assertEquals(rh.winnerSide, rg.winnerSide)
        assertTrue(h.waited || g.waited, "somebody waits for the other player's choice")
        assertContains(h.seen.filterIsInstance<BattleEvent.SwitchIn>().first { it.side == 0 }.text, "Go!")
        assertContains(g.seen.filterIsInstance<BattleEvent.SwitchIn>().first { it.side == 1 }.text, "Go!")
        assertContains(h.seen.filterIsInstance<BattleEvent.SwitchIn>().first { it.side == 1 }.text, "sent out")
    }

    @Test
    fun `a forfeit is a normal choice and ends both devices`() {
        val (h, g) = run(forfeitGuestOnTurn = 1)
        val rh = assertIs<LinkResult.Completed>(h.lb.result); val rg = assertIs<LinkResult.Completed>(g.lb.result)
        assertEquals(0, rh.winnerSide); assertEquals(rh.hash, rg.hash)
        assertTrue(h.ended && g.ended)
    }

    @Test
    fun `the session ending while waiting ends the flow`() {
        val l = LoopbackLink({ 0 })
        val cfg = LinkConfig("t", LinkLobbyRules.rules(false, emptySet()), dataHash = hash)
        val h = Device(LockstepBattle(LinkRole.HOST, l.a, dex, cfg, team(0, 3), "HOST"), autoChoose = false)
        val g = LockstepBattle(LinkRole.GUEST, l.b, dex, cfg, team(2, 3), "GUEST")
        var t = 0L
        repeat(20) { h.frame(t); g.pump(t); t += 16 } // the guest never chooses
        assertEquals(BattleFlow.Phase.CHOOSE_ACTION, h.flow!!.phase)
        h.flow!!.submit(Choice.UseMove(0)); h.frame(t)
        assertEquals(BattleFlow.Phase.WAITING_REMOTE, h.flow!!.phase)
        l.b.close(); t += 16; h.frame(t); h.frame(t + 16)
        assertTrue(h.ended)
        assertIs<LinkResult.Voided>(h.lb.result)
    }
}
