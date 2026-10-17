package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.mon
import kotlin.test.*

/** Faint -> experience -> (Shift prompt) -> replacement -> next turn, as the battle screen drives it. */
class BattleFlowTest {
    private val shift = BattleRules(allowedGimmicks = BattleRules.ALL_GIMMICKS, isTrainerBattle = true, setMode = false)
    private val set = shift.copy(setMode = true)

    /** Player one-shots the foe's lead; the foe has [foeCount] Pokémon. */
    private fun koSetup(rules: BattleRules, foeCount: Int = 2, playerCount: Int = 2): Battle {
        val p = List(playerCount) { i -> mon(if (i == 0) "mewtwo" else "pikachu", level = 70, moves = listOf("psychic", "thunderbolt")) }
        val f = List(foeCount) { i -> mon(listOf("rattata", "pidgey", "sentret", "zubat", "spearow", "ekans")[i], level = 5, moves = listOf("tackle")) }
        return battle(p, f, rules = rules)
    }

    private fun koFoe(b: Battle) {
        assertNull(b.choose(b.player, Choice.UseMove(0)))
        b.advance()
    }

    @Test
    fun `a fainted foe is replaced before the player chooses again`() {
        val b = koSetup(set)
        koFoe(b)
        assertTrue(b.opponent.active!!.fainted)
        assertTrue(b.needsReplacement(b.opponent))
        // The player is not asked for a move while the foe's slot is empty.
        assertEquals(Request.Wait, b.request(b.player))
        assertNotNull(b.choose(b.player, Choice.UseMove(0)))
        b.advance()
        assertFalse(b.needsReplacement(b.opponent))
        assertEquals("pidgey", b.opponent.active!!.species.id)
        assertTrue(b.request(b.player) is Request.Move)
        // Event order: faint, then experience, then the next Pokémon comes out.
        val ev = b.events
        val faint = ev.indexOfFirst { it is BattleEvent.Faint && it.side == 1 }
        val exp = ev.indexOfFirst { it is BattleEvent.ExpGain }
        val sendOut = ev.indexOfLast { it is BattleEvent.SwitchIn && it.side == 1 }
        assertTrue(faint in 0 until exp, "exp after faint")
        assertTrue(exp < sendOut, "next foe after exp")
    }

    @Test
    fun `set mode never offers the switch`() {
        val b = koSetup(set)
        koFoe(b)
        assertFalse(b.shiftAvailable())
        assertNotNull(b.chooseShift(1))
    }

    @Test
    fun `shift mode offers a free switch and names the next foe`() {
        val b = koSetup(shift)
        koFoe(b)
        assertTrue(b.shiftAvailable())
        val next = b.plannedReplacement(b.opponent)!!
        assertEquals("Pidgey", b.nameOnSendOut(b.opponent, next))
        // Planned choice is stable.
        assertEquals(next, b.plannedReplacement(b.opponent))
        val sent = b.events.size
        assertNull(b.chooseShift(1))
        b.advance()
        assertEquals("pikachu", b.player.active!!.species.id)
        val newEvents = b.events.drop(sent)
        val playerIn = newEvents.indexOfFirst { it is BattleEvent.SwitchIn && it.side == 0 }
        val foeIn = newEvents.indexOfFirst { it is BattleEvent.SwitchIn && it.side == 1 }
        assertTrue(playerIn in 0 until foeIn, "player switches before the foe sends out")
        assertTrue(newEvents.any { it is BattleEvent.SwitchOut && it.side == 0 })
        assertTrue(b.request(b.player) is Request.Move)
    }

    @Test
    fun `shift mode answering no keeps the active pokemon`() {
        val b = koSetup(shift)
        koFoe(b)
        assertTrue(b.shiftAvailable())
        b.advance()
        assertEquals("mewtwo", b.player.active!!.species.id)
        assertEquals("pidgey", b.opponent.active!!.species.id)
    }

    @Test
    fun `invalid shift targets are rejected`() {
        val p = listOf(mon("mewtwo", level = 100, moves = listOf("psychic")), mon("pikachu", level = 5).also { it.hp = 0 })
        val b = battle(p, listOf(mon("rattata", level = 5), mon("pidgey", level = 5)), rules = shift)
        koFoe(b)
        // Only a fainted Pokémon in reserve: no switch is offered at all.
        assertFalse(b.shiftAvailable())
        val b2 = koSetup(shift, playerCount = 3)
        koFoe(b2)
        assertNotNull(b2.chooseShift(0), "already active")
        assertNotNull(b2.chooseShift(9), "out of range")
        b2.player.mons[2].mon.hp = 0
        assertNotNull(b2.chooseShift(2), "fainted")
        assertNull(b2.chooseShift(1))
    }

    @Test
    fun `no shift prompt in wild battles or when the player must replace too`() {
        val wild = battle(listOf(mon("mewtwo", level = 100, moves = listOf("psychic")), mon("pikachu")), listOf(mon("rattata", level = 2)),
            rules = shift.copy(isTrainerBattle = false), wild = true)
        koFoe(wild)
        assertFalse(wild.shiftAvailable())
        assertEquals(BattleOutcome.WIN, wild.outcome)
        // Both actives faint (Explosion): no voluntary switch, both sides replace.
        val p = listOf(mon("electrode", level = 100, moves = listOf("explosion")), mon("pikachu", level = 50))
        val f = listOf(mon("rattata", level = 5), mon("pidgey", level = 5))
        val b = battle(p, f, rules = shift)
        koFoe(b)
        assertTrue(b.player.active!!.fainted && b.opponent.active!!.fainted)
        assertFalse(b.shiftAvailable())
        assertTrue(b.request(b.player) is Request.SwitchIn)
        assertNull(b.choose(b.player, Choice.Switch(1)))
        // The player's choice waits for the foe's; advance resolves both.
        b.advance()
        assertEquals("pikachu", b.player.active!!.species.id)
        assertEquals("pidgey", b.opponent.active!!.species.id)
        assertTrue(b.request(b.player) is Request.Move)
    }

    @Test
    fun `the last foe ends the battle exactly once with one experience award per faint`() {
        val b = koSetup(shift, foeCount = 3)
        repeat(10) {
            if (b.outcome != BattleOutcome.ONGOING) return@repeat
            when (b.request(b.player)) {
                is Request.Move -> koFoe(b)
                else -> b.advance()
            }
        }
        assertEquals(BattleOutcome.WIN, b.outcome)
        assertEquals(1, b.events.count { it is BattleEvent.End })
        assertEquals(3, b.events.count { it is BattleEvent.Faint && it.side == 1 })
        // Exp. Share is on: both party members gain once per fainted foe.
        assertEquals(6, b.events.count { it is BattleEvent.ExpGain })
        // Nothing more happens after the end.
        val n = b.events.size
        assertFalse(b.advance())
        assertNotNull(b.choose(b.player, Choice.UseMove(0)))
        assertEquals(n, b.events.size)
    }

    @Test
    fun `every party size from one to six progresses to victory`() {
        for (size in 1..6) {
            val b = koSetup(set, foeCount = size)
            var guard = 0
            while (b.outcome == BattleOutcome.ONGOING && guard++ < 40) {
                if (b.request(b.player) is Request.Move) koFoe(b) else b.advance()
                val a = b.opponent.active!!
                if (b.outcome == BattleOutcome.ONGOING && b.request(b.player) is Request.Move) assertFalse(a.fainted, "fainted foe left active (size $size)")
            }
            assertEquals(BattleOutcome.WIN, b.outcome, "party of $size")
            assertEquals(size, b.events.count { it is BattleEvent.Faint && it.side == 1 })
            assertEquals(size, b.events.count { it is BattleEvent.SwitchIn && it.side == 1 })
        }
    }

    @Test
    fun `wild battle lets the player flee instead of sending the next pokemon`() {
        var fled = false
        for (seed in 1L..20L) {
            val p = listOf(mon("magikarp", level = 2, moves = listOf("splash")), mon("rattata", level = 50))
            val b = battle(p, listOf(mon("mewtwo", level = 100, moves = listOf("psychic"))), seed = seed,
                rules = BattleRules(isTrainerBattle = false), wild = true)
            b.choose(b.player, Choice.UseMove(0)); b.advance()
            assertTrue(b.player.active!!.fainted)
            assertFalse(b.fleeInsteadOfReplacing() && b.outcome != BattleOutcome.RAN)
            if (b.outcome == BattleOutcome.RAN) fled = true
            else assertTrue(b.request(b.player) is Request.SwitchIn, "failed escape still needs a replacement")
        }
        assertTrue(fled)
    }
}
