package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.mon
import kotlin.test.*

/**
 * Doubles step B: spread-move damage, the four-Pokémon action queue, win/loss with two actives.
 *
 * Fixture arithmetic (level 50, 31 IVs, no EVs, neutral nature): stat = floor((2*base+31)/2)+5.
 * Machamp SpA 65 -> 85, Snorlax SpD 110 -> 130, Blissey SpD 135 -> 155.
 * Dazzling Gleam (80 BP Fairy, 100% accuracy, all adjacent foes), no STAB, neutral on both targets, roll 100%, no crit:
 *   base = floor(floor(22 * 80 * SpA / SpD) / 50) + 2
 *   vs Snorlax: floor(22*80*85/130) = 1150 -> 23 -> 25;  spread: pokeRound(25 * 0.75 = 18.75) = 19
 *   vs Blissey: floor(22*80*85/155) = 965  -> 19 -> 21;  spread: pokeRound(21 * 0.75 = 15.75) = 16
 */
class DoublesBattleTest {
    private val doubles = BattleRules(isTrainerBattle = true, format = BattleFormat.DOUBLES)

    private fun choose(b: Battle, side: Side, slot: Int, c: Choice) = assertNull(b.choose(side, c, slot), "choice $c for slot $slot")

    private fun gleamBattle(foeBench: List<String> = emptyList()): Battle {
        val foes = listOf(mon("snorlax", moves = listOf("splash")), mon("blissey", moves = listOf("splash"))) + foeBench.map { mon(it, moves = listOf("splash")) }
        val b = battle(listOf(mon("machamp", moves = listOf("dazzlinggleam")), mon("eevee", moves = listOf("splash"))), foes, rules = doubles)
        b.forcedCrit = false
        b.forcedRoll = 100
        return b
    }

    @Test
    fun `a spread move hits both foes for 0_75x`() {
        val b = gleamBattle()
        val (snorlax, blissey) = b.opponent.actives.map { it!! }
        val s0 = snorlax.hp; val b0 = blissey.hp
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        assertTrue(b.advance())
        assertEquals(19, s0 - snorlax.hp, "Snorlax")
        assertEquals(16, b0 - blissey.hp, "Blissey")
        // One move use, two damage events.
        assertEquals(1, b.events.count { it is BattleEvent.MoveUsed && it.text.contains("Dazzling Gleam") })
        assertEquals(2, b.events.count { it is BattleEvent.HpChange && it.side == 1 && it.delta < 0 })
    }

    @Test
    fun `a spread move with one target left is not reduced`() {
        val b = gleamBattle()
        val (snorlax, blissey) = b.opponent.actives.map { it!! }
        blissey.hp = 0
        b.checkFaints()
        val s0 = snorlax.hp
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0))
        assertTrue(b.advance())
        assertEquals(25, s0 - snorlax.hp)
    }

    @Test
    fun `a single target move is not reduced`() {
        val b = battle(listOf(mon("machamp", moves = listOf("moonblast")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("snorlax", moves = listOf("splash")), mon("blissey", moves = listOf("splash"))), rules = doubles)
        b.forcedCrit = false; b.forcedRoll = 100
        val snorlax = b.opponent.actives[0]!!
        val s0 = snorlax.hp
        choose(b, b.player, 0, Choice.UseMove(0, target = 1))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        b.advance()
        // Moonblast: 95 BP, floor(floor(22*95*85/130)/50)+2 = floor(1366/50)+2 = 29 (no STAB, neutral).
        assertEquals(29, s0 - snorlax.hp)
    }

    @Test
    fun `an all-adjacent move also hits the user's ally`() {
        val b = battle(listOf(mon("machamp", moves = listOf("bulldoze")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("snorlax", moves = listOf("splash")), mon("blissey", moves = listOf("splash"))), rules = doubles)
        b.forcedCrit = false
        val ally = b.player.actives[1]!!
        val a0 = ally.hp
        val foes = b.opponent.actives.map { it!!.hp }
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        b.advance()
        assertTrue(ally.hp < a0, "ally hit")
        assertTrue(b.opponent.actives.mapIndexed { i, m -> m!!.hp < foes[i] }.all { it }, "both foes hit")
    }

    @Test
    fun `Wide Guard blocks the spread move on its side only`() {
        val b = gleamBattle()
        val (snorlax, blissey) = b.opponent.actives.map { it!! }
        b.addSideCondition(b.opponent, "wideguard", snorlax)
        val s0 = snorlax.hp; val b0 = blissey.hp
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        b.advance()
        assertEquals(s0, snorlax.hp)
        assertEquals(b0, blissey.hp)
    }

    @Test
    fun `four Pokemon act in speed order`() {
        // Base speeds: diglett 95, pikachu 90, rattata 72, snorlax 30.
        val b = battle(listOf(mon("pikachu", moves = listOf("splash")), mon("snorlax", moves = listOf("splash"))),
            listOf(mon("diglett", moves = listOf("splash")), mon("rattata", moves = listOf("splash"))), rules = doubles)
        for (s in listOf(b.player, b.opponent)) for (slot in 0..1) choose(b, s, slot, Choice.UseMove(0))
        assertTrue(b.advance())
        val order = b.events.filterIsInstance<BattleEvent.MoveUsed>().map { it.side to it.text.substringBefore(" used") }
        assertEquals(listOf(1 to "Enemy Diglett", 0 to "Pikachu", 1 to "Enemy Rattata", 0 to "Snorlax"),
            order)
    }

    @Test
    fun `a move aimed at a foe that fainted earlier in the turn retargets the survivor`() {
        val b = battle(listOf(mon("machamp", level = 100, moves = listOf("closecombat")), mon("pikachu", level = 100, moves = listOf("tackle"))),
            listOf(mon("rattata", level = 2, moves = listOf("splash")), mon("snorlax", level = 100, moves = listOf("splash"))), rules = doubles)
        b.forcedCrit = false
        val snorlax = b.opponent.actives[1]!!
        val s0 = snorlax.hp
        choose(b, b.player, 0, Choice.UseMove(0, target = 1))
        choose(b, b.player, 1, Choice.UseMove(0, target = 1)) // Pikachu is slower; its target already fainted
        choose(b, b.opponent, 0, Choice.UseMove(0))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        b.advance()
        assertTrue(b.opponent.actives[0]!!.fainted)
        assertTrue(snorlax.hp < s0, "the second attacker hit the surviving foe")
    }

    @Test
    fun `both slots fainting in one turn are both replaced`() {
        // Foe L1 Rattata and Pidgey both die to one Hyper Voice; two more are on the bench.
        val b = battle(listOf(mon("machamp", level = 100, moves = listOf("hypervoice")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("rattata", level = 1, moves = listOf("splash")), mon("pidgey", level = 1, moves = listOf("splash")),
                mon("snorlax", moves = listOf("splash")), mon("blissey", moves = listOf("splash"))), rules = doubles)
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        b.advance()
        assertEquals(BattleOutcome.ONGOING, b.outcome)
        assertTrue(b.needsReplacement(b.opponent))
        b.advance()
        assertEquals(setOf("snorlax", "blissey"), b.opponent.actives.map { it!!.species.id }.toSet())
        assertFalse(b.needsReplacement(b.opponent))
    }

    @Test
    fun `the battle is won when both foe slots are down and nothing is left`() {
        val b = battle(listOf(mon("machamp", level = 100, moves = listOf("hypervoice")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("rattata", level = 1, moves = listOf("splash")), mon("pidgey", level = 1, moves = listOf("splash"))), rules = doubles)
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        b.advance()
        assertEquals(BattleOutcome.WIN, b.outcome)
    }

    @Test
    fun `the battle continues while one foe slot is alive and is lost when both player slots are down`() {
        val b = battle(listOf(mon("rattata", level = 1, moves = listOf("splash")), mon("pidgey", level = 1, moves = listOf("splash"))),
            listOf(mon("machamp", level = 100, moves = listOf("hypervoice")), mon("snorlax", level = 100, moves = listOf("splash"))), rules = doubles)
        // Foe Machamp is faster than L1 mons, so it faints both before they act.
        b.player.actives[0]!!.hp = 1
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        b.advance()
        assertEquals(BattleOutcome.LOSE, b.outcome)
    }

    @Test
    fun `losing one slot with a bench Pokemon is not a loss`() {
        val b = battle(listOf(mon("rattata", level = 1, moves = listOf("splash")), mon("snorlax", level = 100, moves = listOf("splash")),
            mon("pidgey", moves = listOf("splash"))),
            listOf(mon("machamp", level = 100, moves = listOf("closecombat")), mon("blissey", moves = listOf("splash"))), rules = doubles)
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        choose(b, b.opponent, 0, Choice.UseMove(0, target = 1))
        choose(b, b.opponent, 1, Choice.UseMove(0))
        b.advance()
        assertEquals(BattleOutcome.ONGOING, b.outcome)
        assertTrue(b.needsReplacement(b.player))
    }
}
