package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.mon
import pkmn.engine.mon.Pokemon
import kotlin.test.*

/**
 * Link play, Phase 1: two human sides ([Controller.PLAYER] and [Controller.REMOTE]), [BattleRules.link] and
 * [Choice.Forfeit]. The randomised host/guest comparison is [LinkMirrorTest].
 */
class LinkBattleTest {
    /** A link battle as the device whose local player is side [local] sees it. */
    private fun link(t0: List<Pokemon>, t1: List<Pokemon>, local: Int = 0, seed: Long = 3): Battle {
        fun ctl(i: Int) = if (i == local) Controller.PLAYER else Controller.REMOTE
        val b = Battle(dex, listOf(Side(0, "Host", t0, ctl(0), dex), Side(1, "Guest", t1, ctl(1), dex)), BattleRules.link(), seed)
        b.start()
        return b
    }

    private fun strong() = mon("pikachu", 100, listOf("thunderbolt", "tackle"))
    private fun weak() = mon("magikarp", 2, listOf("splash"))

    @Test
    fun `a remote side needs link rules`() {
        val s0 = Side(0, "Host", listOf(strong()), Controller.PLAYER, dex)
        val s1 = Side(1, "Guest", listOf(weak()), Controller.REMOTE, dex)
        assertFailsWith<IllegalArgumentException> { Battle(dex, listOf(s0, s1), BattleRules(), 1) }
        assertTrue(BattleRules.link().isLinkSafe)
        assertFalse(BattleRules().isLinkSafe)
    }

    @Test
    fun `link rules refuse run, bag items and balls`() {
        val b = link(listOf(strong()), listOf(weak()))
        assertNotNull(b.choose(b.player, Choice.Run))
        assertNotNull(b.choose(b.player, Choice.UseItem("potion", 0)))
        assertNotNull(b.choose(b.player, Choice.UseItem("pokeball")))
    }

    @Test
    fun `advance never decides for either human side`() {
        for (local in 0..1) {
            val b = link(listOf(strong()), listOf(weak(), weak()), local)
            assertNull(b.choose(b.sides[0], Choice.UseMove(1)))
            assertFalse(b.advance(), "local $local: the turn must wait for side 1")
            assertEquals(0, b.turn)
            assertIs<Request.Move>(b.request(b.sides[1]))
            assertNull(b.choose(b.sides[1], Choice.UseMove(0)))
            assertTrue(b.advance())
            assertEquals(1, b.turn)
            // Side 1's Magikarp fainted: its replacement is not planned by the AI either, and nothing moves on without it.
            assertNull(b.plannedReplacement(b.sides[1]))
            assertEquals(Request.SwitchIn(forced = true), b.request(b.sides[1]))
            assertFalse(b.advance())
            assertTrue(b.needsReplacement(b.sides[1]))
        }
    }

    @Test
    fun `a remote u-turn waits for the remote pick`() {
        val b = link(listOf(mon("snorlax", 50, listOf("tackle"))), listOf(mon("jolteon", 50, listOf("uturn")), mon("vaporeon", 50, listOf("tackle"))))
        assertNull(b.choose(b.sides[0], Choice.UseMove(0)))
        assertNull(b.choose(b.sides[1], Choice.UseMove(0)))
        b.advance()
        assertEquals(Request.SwitchIn(forced = true, selfSwitch = true), b.request(b.sides[1]))
        assertEquals(Request.Wait, b.request(b.sides[0]))
        assertNull(b.choose(b.sides[1], Choice.Switch(1)))
        assertEquals("vaporeon", b.sides[1].active!!.species.id)
    }

    /** Plays one move-phase forfeit with the choices submitted in the given order; returns (outcome, events without text). */
    private fun forfeitTurn(forfeiting: Set<Int>, order: List<Int>, local: Int): Pair<BattleOutcome, List<BattleEvent>> {
        val b = link(listOf(strong()), listOf(weak()), local)
        for (i in order) {
            assertNull(b.choose(b.sides[i], if (i in forfeiting) Choice.Forfeit else Choice.UseMove(0)))
            b.advance()
        }
        assertEquals(0, b.turn, "a forfeit ends the battle before the turn runs")
        return b.outcome to b.events.map { LinkMirrorTest.withoutText(it) }
    }

    @Test
    fun `forfeit loses, a double forfeit draws, in any order and on either device`() {
        for ((who, expected) in listOf(setOf(0) to BattleOutcome.LOSE, setOf(1) to BattleOutcome.WIN, setOf(0, 1) to BattleOutcome.DRAW)) {
            val runs = listOf(listOf(0, 1), listOf(1, 0)).flatMap { order -> (0..1).map { local -> forfeitTurn(who, order, local) } }
            for (r in runs) assertEquals(expected, r.first, "forfeit by $who")
            assertEquals(1, runs.map { it.second }.distinct().size, "forfeit by $who: events depend on order or device")
        }
    }

    @Test
    fun `forfeit while replacing a fainted pokemon and during a self-switch`() {
        val b = link(listOf(strong()), listOf(weak(), weak()))
        assertNull(b.choose(b.sides[0], Choice.UseMove(1))); assertNull(b.choose(b.sides[1], Choice.UseMove(0))); b.advance()
        assertNotNull(b.choose(b.sides[0], Choice.Forfeit), "side 0 has nothing to decide while side 1 replaces")
        assertNull(b.choose(b.sides[1], Choice.Forfeit))
        assertEquals(BattleOutcome.WIN, b.outcome)

        val c = link(listOf(mon("snorlax", 50, listOf("tackle"))), listOf(mon("jolteon", 50, listOf("uturn")), mon("vaporeon", 50, listOf("tackle"))))
        assertNull(c.choose(c.sides[0], Choice.UseMove(0))); assertNull(c.choose(c.sides[1], Choice.UseMove(0))); c.advance()
        assertNull(c.choose(c.sides[1], Choice.Forfeit))
        assertEquals(BattleOutcome.WIN, c.outcome)
        assertTrue(c.sides.flatMap { it.mons }.none { it.hasVolatile("pendingmove") })
    }

    @Test
    fun `polling request draws no random numbers in a link battle`() {
        // Equal Speed: the speed sort behind canSwitchOut breaks the tie with the RNG in single-player battles.
        fun team() = listOf(mon("pikachu", 50, listOf("tackle")), mon("eevee", 50, listOf("tackle")))
        val b = link(team(), team())
        val before = b.stateHash()
        repeat(5) { for (s in b.sides) b.request(s) }
        assertEquals(before, b.stateHash())
        // Single-player keeps its old behaviour (and RNG stream): the same poll draws there.
        val sp = battle(team(), team())
        val rng = sp.rng.state
        sp.request(sp.player)
        assertNotEquals(rng, sp.rng.state, "single-player request() is expected to draw on a speed tie, as before")
    }

    @Test
    fun `an ai side cannot forfeit`() {
        val b = battle(listOf(strong()), listOf(weak()))
        assertNotNull(b.choose(b.opponent, Choice.Forfeit))
        assertEquals(BattleOutcome.ONGOING, b.outcome)
    }

    @Test
    fun `fainting lowers friendship on both human sides only`() {
        for (local in 0..1) {
            val b = link(listOf(weak().also { it.friendship = 70 }), listOf(strong().also { it.friendship = 70 }, weak()), local)
            assertNull(b.choose(b.sides[0], Choice.UseMove(0))); assertNull(b.choose(b.sides[1], Choice.UseMove(0))); b.advance()
            assertEquals(69, b.sides[0].mons[0].mon.friendship, "local $local")
        }
        // Single-player: the AI side's Pokémon keep their friendship, as before.
        val sp = battle(listOf(strong()), listOf(weak().also { it.friendship = 70 }))
        assertNull(sp.choose(sp.player, Choice.UseMove(0))); sp.advance()
        assertEquals(70, sp.opponent.mons[0].mon.friendship)
    }

    @Test
    fun `each device names its own side without the Enemy prefix`() {
        val host = link(listOf(strong()), listOf(weak()), local = 0)
        val guest = link(listOf(strong()), listOf(weak()), local = 1)
        assertTrue("Guest sent out Magikarp!" in host.log && "Go! Pikachu!" in host.log, host.log.toString())
        assertTrue("Host sent out Pikachu!" in guest.log && "Go! Magikarp!" in guest.log, guest.log.toString())
        assertEquals(host.stateHash(), guest.stateHash())
    }

    @Test
    fun `stateHash covers script data that changes outcomes, not text-only data`() {
        val b = link(listOf(strong()), listOf(weak()))
        val st = EffectState(3)
        b.sides[1].active!!.volatiles["partiallytrapped"] = st
        val plain = b.stateHash()
        st.data["bandmult"] = true
        assertNotEquals(plain, b.stateHash(), "Binding Band data must be hashed")
        st.data.remove("bandmult")
        st.data["name"] = "Pikachu"
        assertEquals(plain, b.stateHash(), "Wish's name is display text only")
        st.data["bad"] = listOf(1)
        assertFailsWith<IllegalStateException> { b.stateHash() }
    }
}
