package pkmn.engine.battle

import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.factory
import pkmn.engine.data.FormKind
import pkmn.engine.data.ItemCategory
import pkmn.engine.mon.Pokemon
import pkmn.engine.util.Rng
import kotlin.test.Test
import kotlin.test.fail

/**
 * Plays many AI-vs-AI battles with random species, moves, abilities and items drawn from the whole catalogue.
 * Guards against crashes, infinite loops and invalid states across all data-driven content.
 */
class BattleFuzzTest {
    private val pool by lazy { dex.species.values.filter { !it.isBattleOnly && it.formKind != FormKind.TOTEM && it.isNonstandard != "Future" || it.formKind == FormKind.BASE } }
    private val items by lazy { dex.items.values.filter { it.hasBattleData && it.category != ItemCategory.Z_CRYSTAL || it.category == ItemCategory.Z_CRYSTAL }.map { it.id } }
    private val allMoves by lazy { dex.moves.values.filter { it.isZ == null && it.isMax == null && it.id != "struggle" }.map { it.id } }

    private fun randomMon(rng: Rng, level: Int): Pokemon {
        val sp = pool[rng.nextInt(pool.size)]
        val ls = dex.learnset(sp.id)
        val candidates = (ls?.levelUp?.map { it.move }.orEmpty() + ls?.machine.orEmpty()).distinct().ifEmpty { allMoves }
        val moves = (0 until 4).map { if (rng.nextInt(10) == 0) allMoves[rng.nextInt(allMoves.size)] else candidates[rng.nextInt(candidates.size)] }.distinct()
        val m = factory.create(sp.id, level, rng, moves = moves, item = if (rng.nextInt(3) > 0) items[rng.nextInt(items.size)] else null,
            abilitySlot = sp.abilities.keys.toList()[rng.nextInt(sp.abilities.size)])
        m.gmaxFactor = rng.nextInt(4) == 0
        return m
    }

    @Test
    fun `random battles complete without errors`() {
        val battles = (System.getProperty("fuzz.battles") ?: "400").toInt()
        var turnsTotal = 0
        for (i in 0 until battles) {
            val seed = 1000L + i
            val rng = Rng(seed)
            val level = 5 + rng.nextInt(96)
            val t1 = List(1 + rng.nextInt(6)) { randomMon(rng, level) }
            val t2 = List(1 + rng.nextInt(6)) { randomMon(rng, level) }
            val s0 = Side(0, "A", t1, Controller.PLAYER, dex, aiLevel = 2, gimmicks = BattleRules.ALL_GIMMICKS)
            val s1 = Side(1, "B", t2, if (i % 5 == 0) Controller.WILD else Controller.AI, dex, aiLevel = 1 + i % 3, gimmicks = BattleRules.ALL_GIMMICKS)
            val rules = BattleRules(allowedGimmicks = BattleRules.ALL_GIMMICKS, oneGimmickPerBattle = i % 2 == 0, isTrainerBattle = i % 5 != 0)
            val b = Battle(dex, listOf(s0, s1), rules, seed)
            val ai = BattleAI()
            try {
                b.start()
                var guard = 0
                while (b.outcome == BattleOutcome.ONGOING && guard++ < 500) {
                    when (val r = b.request(s0)) {
                        is Request.SwitchIn -> b.choose(s0, Choice.Switch(ai.chooseReplacement(b, s0)))
                        is Request.Move -> { val c = ai.chooseAction(b, s0); if (b.choose(s0, c) != null) b.choose(s0, Choice.UseMove(0)) }
                        Request.Wait -> {}
                    }
                    b.advance(ai)
                    for (m in s0.mons + s1.mons) {
                        check(m.mon.hp in 0..m.maxHp) { "hp out of range for ${m.name}: ${m.mon.hp}/${m.maxHp}" }
                        check(m.boosts.all { it in -6..6 }) { "boost out of range" }
                    }
                }
                turnsTotal += b.turn
                if (b.outcome == BattleOutcome.ONGOING && guard >= 500 && b.turn < 300) error("battle stalled without progressing turns")
            } catch (e: Throwable) {
                val desc = (t1 + t2).joinToString { "${it.speciesId}@${it.heldItem}[${it.moves.joinToString("/") { m -> m.id }}]" }
                throw AssertionError("Battle seed=$seed failed at turn ${b.turn}: $e\nTeams: $desc\nLog tail:\n${b.log.takeLast(15).joinToString("\n")}", e)
            }
        }
        println("Fuzz: $battles battles, $turnsTotal turns")
    }
}
