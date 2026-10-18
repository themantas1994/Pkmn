package pkmn.engine.battle

import pkmn.engine.TestSupport
import pkmn.engine.TestSupport.dex
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.Pokemon
import kotlin.test.*

/** Badge obedience (docs/MECHANICS.md): caps, the Generation III/IV check, each outcome, skips and no softlocks. */
class ObedienceTest {
    private fun rules(cap: Int?, exp: Boolean = true) =
        BattleRules(isTrainerBattle = true, obedienceCap = cap, expEnabled = exp)

    private fun mine(species: String = "machop", level: Int = 50, metLevel: Int = level, moves: List<String> = listOf("tackle", "growl"),
                     ability: String? = null): Pokemon =
        TestSupport.mon(species, level, moves, ability = ability).also { it.metLevel = metLevel }

    private fun harmlessFoe(level: Int = 50) = TestSupport.mon("magikarp", level, listOf("splash"))

    private fun battle(player: List<Pokemon>, cap: Int?, foe: Pokemon = harmlessFoe(), seed: Long = 1, exp: Boolean = true): Battle =
        TestSupport.battle(player, listOf(foe), seed = seed, rules = rules(cap, exp))

    private fun turn(b: Battle, move: Int = 0, vararg rolls: Int) {
        b.forcedObedienceRolls.clear(); b.forcedObedienceRolls.addAll(rolls.toList())
        TestSupport.turn(b, Choice.UseMove(move), Choice.UseMove(0))
    }

    private fun used(b: Battle, from: Int, name: String) = b.log.drop(from).any { it == "${b.player.active!!.name} used $name!" }

    private val loafed = Obedience.LOAF_LINES.map { it.substringAfter("{name}") }

    private fun loafedIn(b: Battle, from: Int) = b.log.drop(from).any { line -> loafed.any { line.endsWith(it) } }

    // ------------------------------------------------------------------ caps

    @Test
    fun `Paldea caps are 20 plus 5 per badge and everyone obeys with 8 badges`() {
        assertEquals(listOf(20, 25, 30, 35, 40, 45, 50, 55), (0..7).map { Obedience.levelCap(it) })
        assertNull(Obedience.levelCap(8))
        assertNull(Obedience.levelCap(9))
        assertEquals(20, Obedience.levelCap(-1))
    }

    @Test
    fun `just below and at the cap always obey, just above can disobey, for every badge count`() {
        for (badges in 0..8) {
            val cap = Obedience.levelCap(badges)
            val levels = if (cap == null) listOf(99, 100) else listOf(cap - 1, cap, cap + 1)
            for (lv in levels) {
                val p = mine(level = 100, metLevel = lv)
                val b = battle(listOf(p), cap)
                val from = b.log.size
                // The worst possible rolls: if the check ran at all, the Pokémon would loaf.
                turn(b, 0, 255, 255, 255)
                val shouldObey = cap == null || lv <= cap
                assertEquals(shouldObey, Obedience.alwaysObeys(p, cap), "badges $badges level $lv")
                assertEquals(shouldObey, used(b, from, "Tackle"), "badges $badges level $lv: ${b.log.drop(from)}")
                assertEquals(!shouldObey, loafedIn(b, from), "badges $badges level $lv")
                // Always-obedient Pokémon never roll.
                assertEquals(if (shouldObey) 3 else 0, b.forcedObedienceRolls.size, "badges $badges level $lv")
            }
        }
    }

    @Test
    fun `the reference level is the met level, not the current level`() {
        val raised = mine(level = 60, metLevel = 15)
        val b = battle(listOf(raised), 20)
        val from = b.log.size
        turn(b, 0, 255, 255, 255)
        assertTrue(used(b, from, "Tackle"))
        val caughtHigh = mine(level = 25, metLevel = 25)
        val b2 = battle(listOf(caughtHigh), 20)
        val from2 = b2.log.size
        turn(b2, 0, 255, 255, 255)
        assertFalse(used(b2, from2, "Tackle"))
    }

    // ------------------------------------------------------------------ formula

    @Test
    fun `decision follows the Generation III-IV formula exactly`() {
        // L = 30, C = 20: (30 + 20) x R / 256 >= 20 from R = 103 (50 x 102 / 256 = 19, 50 x 103 / 256 = 20).
        fun decide(vararg r: Int, asleep: Boolean = false, canNap: Boolean = true): Pair<Obedience.Outcome, Int> {
            val q = ArrayDeque(r.toList()); var n = 0
            val o = Obedience.decide(30, 20, { n++; q.removeFirst() }, asleep, canNap)
            return o to n
        }
        assertEquals(Obedience.Outcome.OBEY to 1, decide(102))
        assertEquals(Obedience.Outcome.OBEY to 1, decide(0))
        assertEquals(Obedience.Outcome.IGNORE_ASLEEP to 1, decide(103, asleep = true))
        assertEquals(Obedience.Outcome.OTHER_MOVE to 2, decide(103, 102))
        // R3 against L - C = 10: < 10 nap, < 20 self-hit, else nothing.
        assertEquals(Obedience.Outcome.NAP to 3, decide(103, 103, 9))
        assertEquals(Obedience.Outcome.SELF_HIT to 3, decide(103, 103, 9, canNap = false))
        assertEquals(Obedience.Outcome.SELF_HIT to 3, decide(255, 255, 10))
        assertEquals(Obedience.Outcome.SELF_HIT to 3, decide(255, 255, 19))
        assertEquals(Obedience.Outcome.LOAF to 3, decide(255, 255, 20))
        assertEquals(Obedience.Outcome.LOAF to 3, decide(255, 255, 255))
        // At or below the cap no roll is made.
        assertEquals(Obedience.Outcome.OBEY, Obedience.decide(20, 20, { error("rolled") }, false, true))
        assertEquals(19, Obedience.score(30, 20, 102)); assertEquals(20, Obedience.score(30, 20, 103))
    }

    // ------------------------------------------------------------------ outcomes in battle

    @Test
    fun `another move is used instead, spending its own PP`() {
        val p = mine()
        val b = battle(listOf(p), 20)
        val from = b.log.size
        turn(b, 0, 255, 0)
        assertTrue(b.log.drop(from).any { it.endsWith("ignored orders!") }, b.log.drop(from).toString())
        assertTrue(used(b, from, "Growl"))
        assertFalse(used(b, from, "Tackle"))
        assertEquals(dex.move("tackle").pp, p.moves[0].pp, "the ordered move's PP is untouched")
        assertEquals(dex.move("growl").pp - 1, p.moves[1].pp)
    }

    @Test
    fun `with no other usable move it does nothing`() {
        val p = mine(moves = listOf("tackle"))
        val b = battle(listOf(p), 20)
        val from = b.log.size
        turn(b, 0, 255, 0)
        assertTrue(loafedIn(b, from)); assertFalse(used(b, from, "Tackle"))
        assertEquals(dex.move("tackle").pp, p.moves[0].pp)
        // Another move with no PP or disabled is not "usable" either.
        val q = mine(moves = listOf("tackle", "growl")).also { it.moves[1].pp = 0 }
        val b2 = battle(listOf(q), 20)
        val from2 = b2.log.size
        turn(b2, 0, 255, 0)
        assertTrue(loafedIn(b2, from2)); assertFalse(used(b2, from2, "Growl"))
        val r = mine(moves = listOf("tackle", "growl"))
        val b3 = battle(listOf(r), 20)
        b3.player.active!!.volatiles["disable"] = EffectState(4).also { it.moveId = "growl" }
        val from3 = b3.log.size
        turn(b3, 0, 255, 0)
        assertTrue(loafedIn(b3, from3)); assertFalse(used(b3, from3, "Growl"))
    }

    @Test
    fun `it can fall asleep - began to nap`() {
        val p = mine()
        val b = battle(listOf(p), 20)
        val from = b.log.size
        turn(b, 0, 255, 255, 0)
        assertEquals(MajorStatus.SLEEP, p.status)
        assertTrue(b.log.drop(from).any { it == "${p.displayName(dex)} began to nap!" })
        assertFalse(used(b, from, "Tackle"))
    }

    @Test
    fun `no nap with a major status, an uproar, Vital Spirit or Insomnia - it hurts itself instead`() {
        val cases = listOf<Pair<String, (Battle) -> Unit>>(
            "burned" to { b -> b.player.active!!.mon.status = MajorStatus.BURN },
            "uproar" to { b -> b.opponent.active!!.volatiles["uproar"] = EffectState(3) },
            "vital spirit" to {},
            "insomnia" to {},
        )
        for ((name, setup) in cases) {
            val p = when (name) {
                "vital spirit" -> mine(species = "mankey", ability = "vitalspirit")
                "insomnia" -> mine(species = "hoothoot", ability = "insomnia")
                else -> mine()
            }
            val b = battle(listOf(p), 20, foe = TestSupport.mon("magikarp", 50, listOf(if (name == "uproar") "uproar" else "splash")))
            setup(b)
            val hp = p.hp
            val from = b.log.size
            turn(b, 0, 255, 255, 0)
            assertNotEquals(MajorStatus.SLEEP, p.status, name)
            assertTrue(b.log.drop(from).contains("It hurt itself in its confusion!"), "$name: ${b.log.drop(from)}")
            if (name != "burned") assertTrue(p.hp < hp, name)
        }
    }

    @Test
    fun `it can hurt itself as in confusion`() {
        val p = mine()
        val foe = harmlessFoe()
        val b = battle(listOf(p), 20, foe)
        val hp = p.hp
        val from = b.log.size
        turn(b, 0, 255, 255, 30) // gap 30: 30 <= R3 < 60
        assertTrue(b.log.drop(from).any { it == "${p.displayName(dex)} won't obey!" })
        assertTrue(b.log.drop(from).contains("It hurt itself in its confusion!"))
        assertTrue(p.hp < hp)
        assertEquals(foe.maxHp(dex), foe.hp)
        assertEquals(dex.move("tackle").pp, p.moves[0].pp)
    }

    @Test
    fun `it can do nothing`() {
        val p = mine()
        val b = battle(listOf(p), 20)
        val from = b.log.size
        turn(b, 0, 255, 255, 60)
        assertTrue(loafedIn(b, from))
        assertNull(p.status)
        assertEquals(p.maxHp(dex), p.hp)
    }

    @Test
    fun `asleep with Snore or Sleep Talk a disobedient Pokemon does nothing`() {
        for (move in listOf("sleeptalk", "snore")) {
            val p = mine(moves = listOf(move, "tackle"))
            val b = battle(listOf(p), 20)
            p.status = MajorStatus.SLEEP; p.sleepTurns = 5
            val from = b.log.size
            turn(b, 0, 255)
            assertTrue(b.log.drop(from).any { it.endsWith("ignored orders while asleep!") }, "$move: ${b.log.drop(from)}")
            assertFalse(used(b, from, dex.move(move).name)); assertFalse(used(b, from, "Tackle"))
            // An obedient roll lets the move through.
            val from2 = b.log.size
            turn(b, 0, 0)
            assertTrue(used(b, from2, dex.move(move).name), move)
        }
    }

    @Test
    fun `the second turn of a charging move and Bide are not checked`() {
        val p = mine(moves = listOf("solarbeam", "tackle"))
        val b = battle(listOf(p), 20)
        turn(b, 0, 0) // obeys; charges
        assertEquals("solarbeam", b.lockedMove(b.player.active!!))
        val from = b.log.size
        turn(b, 0, 255, 255, 255)
        assertEquals(3, b.forcedObedienceRolls.size, "locked turn rolls nothing")
        assertTrue(used(b, from, "Solar Beam"))

        val q = mine(moves = listOf("bide", "tackle"))
        val b2 = battle(listOf(q), 20, foe = TestSupport.mon("magikarp", 50, listOf("tackle")))
        turn(b2, 0, 0)
        assertEquals("bide", b2.lockedMove(b2.player.active!!))
        turn(b2, 0, 255, 255, 255)
        assertEquals(3, b2.forcedObedienceRolls.size)
    }

    @Test
    fun `disobeying ends Encore and Rage`() {
        val p = mine()
        val b = battle(listOf(p), 20)
        val active = b.player.active!!
        active.volatiles["encore"] = EffectState(3).also { it.moveId = "tackle" }
        active.volatiles["rage"] = EffectState()
        val from = b.log.size
        turn(b, 0, 255, 255, 255) // Encore only allows Tackle
        assertFalse(active.hasVolatile("encore")); assertFalse(active.hasVolatile("rage"))
        assertTrue(b.log.drop(from).any { it.endsWith("encore ended!") })
    }

    @Test
    fun `only the player's Pokemon and only battles with experience are checked`() {
        val p = mine(level = 20, metLevel = 20)
        val foe = TestSupport.mon("machop", 90, listOf("growl")).also { it.metLevel = 90 }
        val b = battle(listOf(p), 20, foe)
        val from = b.log.size
        turn(b, 0, 255, 255, 255)
        assertTrue(b.log.drop(from).any { it == "Enemy ${foe.displayName(dex)} used Growl!" })
        val high = mine(level = 90, metLevel = 90)
        val noExp = battle(listOf(high), 20, exp = false)
        val from2 = noExp.log.size
        turn(noExp, 0, 255, 255, 255)
        assertTrue(used(noExp, from2, "Tackle"))
    }

    // ------------------------------------------------------------------ no softlocks

    @Test
    fun `a self-hit faint leads to a valid forced switch`() {
        val p = mine().also { it.hp = 1 }
        val back = mine(level = 10, metLevel = 10)
        val b = battle(listOf(p, back), 20)
        turn(b, 0, 255, 255, 30)
        assertTrue(p.isFainted)
        assertEquals(BattleOutcome.ONGOING, b.outcome)
        assertEquals(Request.SwitchIn(true), b.request(b.player))
        assertNull(b.choose(b.player, Choice.Switch(1)))
        assertTrue(b.request(b.player) is Request.Move)
    }

    @Test
    fun `a lone Pokemon fainting to a self-hit ends the battle`() {
        val p = mine().also { it.hp = 1 }
        val b = battle(listOf(p), 20)
        turn(b, 0, 255, 255, 30)
        assertEquals(BattleOutcome.LOSE, b.outcome)
        assertEquals(Request.Wait, b.request(b.player))
    }

    @Test
    fun `disobedient battles always terminate in a valid state, including no-PP and disabled moves`() {
        for (seed in 1L..40L) {
            val p = mine(level = 70, metLevel = 70, moves = listOf("tackle", "growl"))
            if (seed % 3 == 0L) p.moves[1].pp = 0
            val foe = TestSupport.mon("rattata", 30, listOf("tackle"))
            val b = battle(listOf(p, mine(level = 70, metLevel = 70, moves = listOf("tackle"))), 20, foe, seed = seed)
            if (seed % 4 == 0L) b.player.active!!.volatiles["disable"] = EffectState(4).also { it.moveId = "tackle" }
            var guard = 0
            while (b.outcome == BattleOutcome.ONGOING && guard++ < 500) {
                when (val r = b.request(b.player)) {
                    is Request.SwitchIn -> assertNull(b.choose(b.player, Choice.Switch(b.player.mons.indexOfFirst { !it.fainted && it !== b.player.active })))
                    is Request.Move -> {
                        val idx = r.usableMoves.indexOfFirst { it }.coerceAtLeast(0)
                        assertNull(b.choose(b.player, Choice.UseMove(idx)), "seed $seed")
                    }
                    Request.Wait -> {}
                }
                b.advance()
                for (m in b.player.mons) assertTrue(m.mon.hp in 0..m.maxHp && m.moveSlots.all { it.pp >= 0 }, "seed $seed")
            }
            assertNotEquals(BattleOutcome.ONGOING, b.outcome, "seed $seed did not finish")
        }
    }
}
