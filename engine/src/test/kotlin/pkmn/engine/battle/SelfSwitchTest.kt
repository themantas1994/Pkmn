package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.mon
import kotlin.test.*

/**
 * Self-switching moves (U-turn, Volt Switch, Baton Pass, Shed Tail, Teleport...): the player picks the replacement
 * mid-turn and the rest of the turn runs afterwards; AI sides pick on their own. Also covers invalid AI decisions.
 */
class SelfSwitchTest {
    /** Fast player lead with every self-switch move, two bench Pokémon; a slow foe that only Tackles. */
    private fun setup(benchHp: Int? = null, foeCount: Int = 1, playerCount: Int = 3): Battle {
        val lead = mon("jolteon", 50, listOf("uturn", "batonpass", "shedtail", "teleport"))
        val bench = List(playerCount - 1) { i -> mon(listOf("snorlax", "vaporeon")[i], 50, listOf("tackle")) }
        val foes = List(foeCount) { i -> mon(listOf("slowbro", "slowking")[i], 50, listOf("tackle")) }
        val b = battle(listOf(lead) + bench, foes)
        if (benchHp != null) b.player.mons[2].hp = benchHp
        return b
    }

    private fun useMove(b: Battle, playerMove: Int) {
        assertNull(b.choose(b.player, Choice.UseMove(playerMove)))
        assertNull(b.choose(b.opponent, Choice.UseMove(0)))
        b.advance()
    }

    @Test
    fun `u-turn asks the player for the replacement and sends in the chosen slot`() {
        val b = setup()
        useMove(b, 0)
        // The turn is suspended after U-turn: the slow foe has not moved yet.
        assertEquals(Request.SwitchIn(forced = true, selfSwitch = true), b.request(b.player))
        assertEquals(Request.Wait, b.request(b.opponent))
        assertTrue(b.events.none { it is BattleEvent.MoveUsed && it.side == 1 }, "foe acts after the replacement")
        // Only a switch to a healthy benched Pokémon is accepted; the move cannot be cancelled.
        assertNotNull(b.choose(b.player, Choice.UseMove(0)))
        assertNotNull(b.choose(b.player, Choice.Switch(0)), "the user itself")
        assertNotNull(b.choose(b.player, Choice.Switch(5)), "an empty slot")
        assertNotNull(b.choose(b.opponent, Choice.UseMove(0)), "the other side waits")
        assertFalse(b.advance())
        assertNull(b.choose(b.player, Choice.Switch(2)))
        assertEquals("vaporeon", b.player.active!!.species.id)
        // The foe's action ran afterwards against the new Pokémon, then a normal move request follows.
        val sendOut = b.events.indexOfLast { it is BattleEvent.SwitchIn && it.side == 0 }
        val foeMove = b.events.indexOfFirst { it is BattleEvent.MoveUsed && it.side == 1 }
        assertTrue(sendOut in 0 until foeMove, "foe moved after the switch")
        assertTrue(b.player.active!!.hp < b.player.active!!.maxHp)
        assertEquals(1, b.events.count { it is BattleEvent.SwitchIn && it.side == 0 && it.teamIndex == 2 })
        assertTrue(b.request(b.player) is Request.Move)
        assertEquals(1, b.turn)
    }

    @Test
    fun `self-switch ignores trapping`() {
        val b = setup()
        b.opponent.active!!.ability = "shadowtag"
        assertFalse(b.canSwitchOut(b.player.active!!))
        useMove(b, 0)
        assertTrue((b.request(b.player) as Request.SwitchIn).selfSwitch)
        assertNull(b.choose(b.player, Choice.Switch(1)))
        assertEquals("snorlax", b.player.active!!.species.id)
    }

    @Test
    fun `no valid replacement - the move still works and the turn completes`() {
        val b = setup(playerCount = 1)
        val foe = b.opponent.active!!
        useMove(b, 0)
        assertTrue(foe.hp < foe.maxHp, "U-turn dealt damage")
        assertEquals("jolteon", b.player.active!!.species.id)
        assertTrue(b.request(b.player) is Request.Move)
        assertTrue(b.events.any { it is BattleEvent.MoveUsed && it.side == 1 })
    }

    @Test
    fun `fainted bench Pokémon are not valid replacements`() {
        val b = setup()
        b.player.mons[1].hp = 0
        b.player.mons[1].mon.fainted = true
        useMove(b, 0)
        assertNotNull(b.choose(b.player, Choice.Switch(1)))
        assertNull(b.choose(b.player, Choice.Switch(2)))
        assertEquals("vaporeon", b.player.active!!.species.id)
    }

    @Test
    fun `baton pass passes boosts and substitute to the chosen Pokémon`() {
        val b = setup()
        val lead = b.player.active!!
        lead.boosts[0] = 2; lead.boosts[4] = 1
        lead.volatiles["substitute"] = EffectState().also { it.hp = 30 }
        useMove(b, 1)
        assertNull(b.choose(b.player, Choice.Switch(2)))
        val vap = b.player.active!!
        assertEquals("vaporeon", vap.species.id)
        assertEquals(2, vap.boosts[0])
        assertEquals(1, vap.boosts[4])
        assertTrue(vap.hasVolatile("substitute"))
        assertEquals(0, lead.boosts[0], "the user's boosts are cleared on switching out")
    }

    @Test
    fun `shed tail passes a substitute to the chosen Pokémon`() {
        val b = setup()
        val lead = b.player.active!!
        useMove(b, 2)
        assertTrue(lead.hp < lead.maxHp, "Shed Tail costs HP")
        assertNull(b.choose(b.player, Choice.Switch(1)))
        val snor = b.player.active!!
        assertEquals("snorlax", snor.species.id)
        assertTrue(snor.hasVolatile("substitute"), "decoy passed")
    }

    @Test
    fun `teleport in a trainer battle switches to the chosen Pokémon`() {
        val b = setup()
        useMove(b, 3)
        assertTrue((b.request(b.player) as Request.SwitchIn).selfSwitch)
        assertNull(b.choose(b.player, Choice.Switch(2)))
        assertEquals("vaporeon", b.player.active!!.species.id)
    }

    @Test
    fun `hazard KO on the self-switch replacement faints once and asks for a forced replacement`() {
        val b = setup(benchHp = 1)
        b.addSideCondition(b.player, "stealthrock", null)
        useMove(b, 0)
        assertNull(b.choose(b.player, Choice.Switch(2)))
        val vap = b.player.mons[2]
        assertTrue(vap.fainted)
        assertEquals(1, b.events.count { it is BattleEvent.Faint && it.side == 0 && it.teamIndex == 2 })
        assertEquals(BattleOutcome.ONGOING, b.outcome)
        // End of turn: the empty slot is filled by a normal replacement (not another self-switch).
        assertEquals(Request.SwitchIn(true), b.request(b.player))
        assertNull(b.choose(b.player, Choice.Switch(0)))
        b.advance()
        assertEquals("jolteon", b.player.active!!.species.id)
        assertTrue(b.request(b.player) is Request.Move)
        assertEquals(1, b.events.count { it is BattleEvent.Faint && it.side == 0 && it.teamIndex == 2 })
    }

    @Test
    fun `the AI picks its own self-switch replacement without prompting the player`() {
        val p = listOf(mon("slowbro", 50, listOf("tackle")))
        val f = listOf(mon("jolteon", 50, listOf("uturn")), mon("snorlax", 50, listOf("tackle")), mon("vaporeon", 50, listOf("tackle")))
        val b = battle(p, f, rules = BattleRules(isTrainerBattle = true))  // no Dynamax: the AI must use U-turn itself
        assertNull(b.choose(b.player, Choice.UseMove(0)))
        assertTrue(b.advance())
        assertNotEquals("jolteon", b.opponent.active!!.species.id)
        assertTrue(b.request(b.player) is Request.Move)
        assertTrue(b.events.any { it is BattleEvent.MoveUsed && it.side == 0 }, "player's move still resolved")
    }

    // ------------------------------------------------------------ B14: invalid AI decisions

    /** An AI that always asks for something the engine rejects. */
    private class InvalidAI(val c: Choice) : BattleAI() {
        override fun chooseAction(b: Battle, side: Side): Choice = c
    }

    @Test
    fun `an invalid AI choice falls back to a legal move instead of stalling the turn`() {
        val p = listOf(mon("mewtwo", 50, listOf("tackle")))
        val f = listOf(mon("rattata", 50, listOf("tackle", "growl")))
        val b = battle(p, f)
        b.opponent.active!!.moveSlots[0].pp = 0
        for (bad in listOf(Choice.UseMove(0), Choice.Switch(4), Choice.UseMove(9))) {
            val turn = b.turn
            assertNull(b.choose(b.player, Choice.UseMove(0)))
            assertTrue(b.advance(InvalidAI(bad)), "turn ran despite $bad")
            assertEquals(turn + 1, b.turn)
            if (b.outcome != BattleOutcome.ONGOING) break
        }
        assertTrue(b.log.any { "Growl" in it || "growl" in it.lowercase() }, "fell back to the usable move")
    }

    @Test
    fun `an AI with no usable move struggles`() {
        val p = listOf(mon("snorlax", 50, listOf("splash")))
        val f = listOf(mon("rattata", 50, listOf("tackle")))
        val b = battle(p, f)
        b.opponent.active!!.moveSlots[0].pp = 0
        assertNull(b.choose(b.player, Choice.UseMove(0)))
        assertTrue(b.advance(InvalidAI(Choice.Switch(3))))
        assertTrue(b.log.any { "Struggle" in it })
    }
}
