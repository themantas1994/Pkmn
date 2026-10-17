package pkmn.engine.battle

import pkmn.engine.TestSupport
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.mon
import pkmn.engine.mon.MajorStatus
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

/**
 * Capture mechanics against [CaptureReference] (an independent transcription of a published Gen IX calculator) and
 * hand-checked values. Every probability below is computed by the reference, never by the engine under test.
 */
class CaptureTest {
    private fun rules(capture: CaptureRules = CaptureRules()) = BattleRules(isTrainerBattle = false, capture = capture)

    /** A wild battle with [target] (species, level) against a level-50 Pikachu. */
    private fun wild(species: String, level: Int, capture: CaptureRules = CaptureRules(), seed: Long = 1, env: String = "plain", night: Boolean = false): Battle {
        val s0 = Side(0, "Player", listOf(mon("pikachu", 50, listOf("thundershock"))), Controller.PLAYER, dex)
        val s1 = Side(1, "Wild", listOf(mon(species, level, listOf("tackle"))), Controller.WILD, dex)
        return Battle(dex, listOf(s0, s1), rules(capture), seed, environment = env, isNight = night).also { it.start() }
    }

    private fun refInput(b: Battle, ball: String, capture: CaptureRules = b.rules.capture): CaptureReference.Input {
        val t = b.opponent.active!!
        return CaptureReference.Input(
            maxHp = t.baseMaxHp, curHp = t.hp, catchRate = t.species.catchRate, level = t.level, ball = ball,
            status = t.status?.id?.let { if (it == "tox") "psn" else it } ?: "none", badges = capture.badges,
            pokedex = capture.caughtSpecies.size, catchingCharm = capture.catchingCharm, lowLevelBonus = capture.lowLevelBonus,
            weightKg = t.species.weightkg, waterOrBug = t.hasType("Water") || t.hasType("Bug"), ultraBeast = "Ultra Beast" in t.species.tags,
        )
    }

    private fun engineP(b: Battle, ball: String): Double {
        val f = CatchCalc.factors(b, b.player.active!!, b.opponent.active!!, ball)
        return CatchCalc.successProbability(f, b.rules.capture)
    }

    private fun engineX(b: Battle, ball: String) = CatchCalc.finalRate(CatchCalc.factors(b, b.player.active!!, b.opponent.active!!, ball))

    private fun setHp(b: Battle, fraction: Double) { val t = b.opponent.active!!; t.hp = maxOf(1, (t.baseMaxHp * fraction).toInt()) }

    /** Empirical success rate of [n] real throws (fresh battle per throw so state never leaks). */
    private fun simulate(species: String, level: Int, ball: String, n: Int, capture: CaptureRules = CaptureRules(), prep: (Battle) -> Unit = {}): Double {
        var ok = 0
        for (i in 0 until n) {
            val b = wild(species, level, capture, seed = 1000L + i)
            prep(b)
            if (CatchCalc.attempt(b, b.player.active!!, b.opponent.active!!, ball).caught) ok++
        }
        return ok.toDouble() / n
    }

    private fun assertClose(expected: Double, actual: Double, n: Int, what: String) {
        val sigma = sqrt(maxOf(expected * (1 - expected), 1e-4) / n)
        assertTrue(abs(expected - actual) <= 4.5 * sigma, "$what: expected ${"%.4f".format(expected)}, simulated ${"%.4f".format(actual)} (n=$n)")
    }

    // 1-3 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `1 full HP Poke Ball no status matches the reference and is far from guaranteed`() {
        val b = wild("pidgey", 20, CaptureRules(lowLevelBonus = false))
        val ref = CaptureReference.success(refInput(b, "pokeball"))
        assertEquals(ref, engineP(b, "pokeball"), 1e-12)
        // Hand check: X = floor-ish((3M-2M) x 255 / 3M) = 85, P ~ (85/255)^0.75 ~ 0.44.
        assertEquals(85.0, engineX(b, "pokeball"), 0.01)
        assertTrue(ref in 0.40..0.48, "full-HP Pidgey $ref")
        assertClose(ref, simulate("pidgey", 20, "pokeball", 6000, CaptureRules(lowLevelBonus = false)), 6000, "full HP Pidgey")
    }

    @Test
    fun `2 half HP is easier than full HP`() {
        val full = wild("rattata", 20, CaptureRules(lowLevelBonus = false))
        val half = wild("rattata", 20, CaptureRules(lowLevelBonus = false)).also { setHp(it, 0.5) }
        assertEquals(CaptureReference.success(refInput(half, "pokeball")), engineP(half, "pokeball"), 1e-12)
        assertTrue(engineP(half, "pokeball") > engineP(full, "pokeball"))
    }

    @Test
    fun `3 one HP approaches but does not reach the maximum`() {
        val b = wild("pidgeotto", 30).also { it.opponent.active!!.hp = 1 }
        val ref = CaptureReference.success(refInput(b, "pokeball"))
        assertEquals(ref, engineP(b, "pokeball"), 1e-12)
        // Catch rate 120: X ~ 120 x (3M-2)/3M ~ 119 -> P ~ 0.57; a 1-HP target is still not certain.
        assertTrue(ref in 0.5..0.7, "1 HP Pidgeotto $ref")
        assertClose(ref, simulate("pidgeotto", 30, "pokeball", 5000) { it.opponent.active!!.hp = 1 }, 5000, "1 HP Pidgeotto")
    }

    // 4 --------------------------------------------------------------------------------------------------------------

    @Test
    fun `4 Great and Ultra Balls at full and low HP`() {
        for (frac in listOf(1.0, 0.1)) for (ball in listOf("pokeball", "greatball", "ultraball")) {
            val b = wild("growlithe", 25).also { setHp(it, frac) }
            assertEquals(CaptureReference.success(refInput(b, ball)), engineP(b, ball), 1e-12, "$ball at $frac")
        }
        val b = wild("growlithe", 25)
        assertTrue(engineP(b, "pokeball") < engineP(b, "greatball") && engineP(b, "greatball") < engineP(b, "ultraball"))
        assertEquals(1.5 * engineX(b, "pokeball"), engineX(b, "greatball"), 0.01)
        assertEquals(2.0 * engineX(b, "pokeball"), engineX(b, "ultraball"), 0.01)
    }

    // 5 --------------------------------------------------------------------------------------------------------------

    @Test
    fun `5 every status condition applies its modifier`() {
        val expected = mapOf(null to 1.0, MajorStatus.POISON to 1.5, MajorStatus.TOXIC to 1.5, MajorStatus.PARALYSIS to 1.5,
            MajorStatus.BURN to 1.5, MajorStatus.SLEEP to 2.5, MajorStatus.FREEZE to 2.5)
        val base = engineX(wild("snorlax", 30), "pokeball")
        for ((st, mult) in expected) {
            val b = wild("snorlax", 30)
            b.opponent.active!!.mon.status = st
            assertEquals(CaptureReference.success(refInput(b, "pokeball")), engineP(b, "pokeball"), 1e-12, "$st")
            assertEquals(base * mult, engineX(b, "pokeball"), 0.01, "$st")
        }
    }

    // 6-8 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `6 7 8 catch rate tiers stay ordered and legendaries stay hard`() {
        val common = wild("pidgey", 30)       // 255
        val uncommon = wild("growlithe", 30)  // 190
        val moderate = wild("eevee", 30)      // 45
        val legend = wild("lugia", 70)        // 3
        for (b in listOf(common, uncommon, moderate, legend)) assertEquals(CaptureReference.success(refInput(b, "ultraball")), engineP(b, "ultraball"), 1e-12)
        val p = listOf(common, uncommon, moderate, legend).map { engineP(it, "pokeball") }
        assertEquals(p.sortedDescending(), p)
        // Lugia at full HP with a Poké Ball: X = 1, ~1.6% per ball; even at 1 HP, asleep, with an Ultra Ball it is under 20%.
        assertTrue(p[3] < 0.02, "full HP Lugia ${p[3]}")
        val weak = wild("lugia", 70).also { it.opponent.active!!.hp = 1; it.opponent.active!!.mon.status = MajorStatus.SLEEP }
        val pw = engineP(weak, "ultraball")
        assertEquals(CaptureReference.success(refInput(weak, "ultraball")), pw, 1e-12)
        assertTrue(pw in 0.05..0.2, "weakened Lugia $pw")
        // A common Pokémon can still be caught at full HP sometimes (no artificial full-HP rule).
        assertTrue(p[0] > 0.3)
    }

    // 9 --------------------------------------------------------------------------------------------------------------

    @Test
    fun `9 Master Ball always catches, even legendaries at full HP and without badges`() {
        repeat(200) { i ->
            val b = wild("mewtwo", 70, CaptureRules(badges = 0), seed = i.toLong())
            assertTrue(CatchCalc.attempt(b, b.player.active!!, b.opponent.active!!, "masterball").caught)
        }
        assertEquals(1.0, engineP(wild("mewtwo", 70), "masterball"))
    }

    // 10 -------------------------------------------------------------------------------------------------------------

    @Test
    fun `10 level bonus, badge penalty and blocked captures`() {
        // Low-level bonus (Scarlet/Violet): level 5 -> x2.6; level 13+ -> none. Off in Crystalline's default difficulty.
        val on = wild("pidgey", 5, CaptureRules(lowLevelBonus = true))
        val off = wild("pidgey", 5, CaptureRules(lowLevelBonus = false))
        assertEquals(CaptureReference.success(refInput(on, "pokeball")), engineP(on, "pokeball"), 1e-12)
        assertEquals(CaptureReference.success(refInput(off, "pokeball")), engineP(off, "pokeball"), 1e-12)
        assertTrue(engineP(on, "pokeball") > 0.85, "with the Gen IX bonus a level-5 Pidgey is nearly certain")
        assertTrue(engineP(off, "pokeball") < 0.5, "without it, full HP is a coin flip at best")
        assertEquals(engineX(wild("pidgey", 13, CaptureRules(lowLevelBonus = true)), "pokeball"), engineX(wild("pidgey", 13, CaptureRules(lowLevelBonus = false)), "pokeball"))
        // Badge penalty: level 46 with 2 badges is 3 badges short -> 0.8^3.
        for (badges in 0..8) {
            val b = wild("growlithe", 46, CaptureRules(badges = badges))
            assertEquals(CaptureReference.success(refInput(b, "pokeball")), engineP(b, "pokeball"), 1e-12, "badges=$badges")
        }
        assertEquals(0.512, CatchCalc.badgePenalty(CaptureRules(badges = 2), 46), 0.001)
        assertEquals(1.0, CatchCalc.badgePenalty(CaptureRules(badges = 5), 46))
        // Trainer battles and uncatchable scripted Pokémon block the ball without a roll.
        val tb = TestSupport.battle(listOf(mon("pikachu")), listOf(mon("rattata")))
        TestSupport.turn(tb, Choice.UseItem("ultraball"), Choice.UseMove(0))
        assertTrue(tb.log.any { "blocked the Ball" in it }); assertNull(tb.caughtMon)
        val s0 = Side(0, "Player", listOf(mon("pikachu", 50, listOf("thundershock"))), Controller.PLAYER, dex)
        val s1 = Side(1, "Wild", listOf(mon("rattata", 5)), Controller.WILD, dex)
        val nb = Battle(dex, listOf(s0, s1), BattleRules(canCatch = false), 3).also { it.start() }
        assertNull(nb.choose(nb.player, Choice.UseItem("masterball"))); nb.advance()
        assertNull(nb.caughtMon); assertEquals(BattleOutcome.ONGOING, nb.outcome)
    }

    @Test
    fun `special balls match the reference`() {
        val cases = listOf(
            Triple("magikarp", 20, "netball"), Triple("pidgey", 20, "netball"), Triple("pidgey", 15, "nestball"),
            Triple("pidgey", 30, "nestball"), Triple("snorlax", 30, "heavyball"), Triple("pidgey", 30, "heavyball"),
            Triple("groudon", 70, "heavyball"), Triple("pidgey", 20, "timerball"), Triple("pidgey", 20, "quickball"),
            Triple("nihilego", 50, "ultraball"), Triple("nihilego", 50, "beastball"), Triple("pidgey", 20, "beastball"),
        )
        for ((sp, lv, ball) in cases) {
            val b = wild(sp, lv)
            assertEquals(CaptureReference.success(refInput(b, ball)), engineP(b, ball), 1e-12, "$sp $lv $ball")
        }
        val dusk = wild("pidgey", 20, night = true)
        assertEquals(3.0, CatchCalc.ballModifier(dusk, dusk.player.active!!, dusk.opponent.active!!, "duskball"))
        val dive = wild("magikarp", 20, env = "water")
        assertEquals(3.5, CatchCalc.ballModifier(dive, dive.player.active!!, dive.opponent.active!!, "diveball"))
        val rep = wild("pidgey", 20, CaptureRules(caughtSpecies = setOf("pidgey")))
        assertEquals(3.5, CatchCalc.ballModifier(rep, rep.player.active!!, rep.opponent.active!!, "repeatball"))
        // Level Ball uses floor(user / 4) >= target (Pikachu 50 vs level 12 -> x8, vs 13 -> x4).
        val lv12 = wild("pidgey", 12); val lv13 = wild("pidgey", 13)
        assertEquals(8.0, CatchCalc.ballModifier(lv12, lv12.player.active!!, lv12.opponent.active!!, "levelball"))
        assertEquals(4.0, CatchCalc.ballModifier(lv13, lv13.player.active!!, lv13.opponent.active!!, "levelball"))
    }

    @Test
    fun `critical captures follow the Pokedex count and match the reference`() {
        val dex600 = (1..601).map { "s$it" }.toSet()
        for (n in listOf(0, 31, 151, 301, 451, 601)) {
            val cap = CaptureRules(caughtSpecies = dex600.take(n).toSet())
            val b = wild("growlithe", 30, cap)
            assertEquals(CaptureReference.success(refInput(b, "pokeball")), engineP(b, "pokeball"), 1e-12, "dex=$n")
        }
        val withDex = CaptureRules(caughtSpecies = dex600)
        val p = CaptureReference.success(refInput(wild("growlithe", 30, withDex), "pokeball"))
        assertClose(p, simulate("growlithe", 30, "pokeball", 6000, withDex), 6000, "critical captures with 601 caught")
    }

    @Test
    fun `engine matches the reference over a broad grid`() {
        var checked = 0
        for (sp in listOf("pidgey", "growlithe", "eevee", "snorlax", "dratini", "lugia", "magikarp"))
            for (lv in listOf(2, 8, 12, 13, 25, 46, 70))
                for (frac in listOf(1.0, 0.66, 0.33, 0.01))
                    for (ball in listOf("pokeball", "greatball", "ultraball", "nestball"))
                        for (badges in listOf(0, 4, 8)) for (low in listOf(true, false)) {
                            val cap = CaptureRules(badges = badges, lowLevelBonus = low)
                            val b = wild(sp, lv, cap).also { setHp(it, frac) }
                            val ri = refInput(b, ball)
                            assertEquals(CaptureReference.x(ri), engineX(b, ball), 0.0, "X $sp $lv $frac $ball $badges $low")
                            assertEquals(CaptureReference.success(ri), engineP(b, ball), 1e-12)
                            checked++
                        }
        assertTrue(checked > 4000)
    }

    // 11-12 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `11 a failed throw keeps the battle going and a retry can succeed`() {
        // Find a seed whose first throw fails (Lugia at full HP makes that near certain), then weaken and use a Master Ball.
        val b = wild("lugia", 70, seed = 5)
        assertNull(b.choose(b.player, Choice.UseItem("pokeball"))); b.advance()
        val first = b.events.filterIsInstance<BattleEvent.CatchAttempt>().single()
        assertFalse(first.caught)
        assertEquals(BattleOutcome.ONGOING, b.outcome)
        assertNull(b.caughtMon)
        assertTrue(b.log.any { it.contains("broke free") || it.contains("appeared to be caught") || it.contains("Almost") || it.contains("so close") })
        assertNull(b.choose(b.player, Choice.UseItem("masterball"))); b.advance()
        assertEquals(BattleOutcome.CAUGHT, b.outcome)
        assertEquals(2, b.events.filterIsInstance<BattleEvent.CatchAttempt>().size)
        // The foe acts no more: the battle is over and further choices are refused.
        assertFalse(b.advance())
        assertEquals(1, b.events.count { it is BattleEvent.End })
    }

    @Test
    fun `12 a capture awards experience and effort values exactly once`() {
        val b = wild("pidgey", 30, seed = 2)
        val pika = b.player.active!!.mon
        val evBefore = pika.evs.total
        assertNull(b.choose(b.player, Choice.UseItem("masterball"))); b.advance()
        assertEquals(BattleOutcome.CAUGHT, b.outcome)
        assertSame(b.opponent.mons[0].mon, b.caughtMon)
        assertEquals("masterball", b.caughtMon!!.ball)
        assertEquals(1, b.events.count { it is BattleEvent.ExpGain }, "Gen VI+: catching gives EXP once")
        assertEquals(evBefore + dex.species("pidgey").evYield.total, pika.evs.total, "Pidgey yields 1 Speed EV")
        assertFalse(b.advance())
        assertEquals(1, b.events.count { it is BattleEvent.ExpGain })
    }
}
