package pkmn.engine.battle

import pkmn.engine.data.Dex
import pkmn.engine.data.FormKind
import pkmn.engine.data.ItemCategory
import pkmn.engine.mon.Pokemon
import pkmn.engine.mon.PokemonFactory
import pkmn.engine.util.Rng
import java.security.MessageDigest

/**
 * Plays seeded AI-vs-AI battles and condenses every one into a SHA-256, then condenses those into one combined hash.
 * Link play (docs/MULTIPLAYER_LAN_PLAN.md, Phase 0) needs two runtimes to compute identical battles, so the same
 * hash must come out on the desktop JVM and on Android. Entry points: the engine test `DeterminismSelfTest`, the desktop flag
 * `--selftest=determinism` and the Android debug hook (log tag `PkmnDeterminism`).
 *
 * Battle setup mirrors BattleFuzzTest (seeds 1000..), except the species pool is sorted by id so the result does not depend
 * on the order the content files list species. Nothing here reads the clock: team uids come from [PokemonFactory] (a
 * `System.nanoTime()` counter) but are never hashed or used by the battle logic that decides outcomes.
 */
object DeterminismSelfTest {
    const val DEFAULT_BATTLES = 400
    private const val MAX_STEPS = 500

    class Result(val battles: Int, val turns: Int, val perBattle: List<String>, val combined: String)

    /** [onBattle] is called with (index, hash of that battle) as each finishes. */
    fun run(dex: Dex, battles: Int = DEFAULT_BATTLES, onBattle: ((Int, String) -> Unit)? = null): Result {
        val factory = PokemonFactory(dex)
        val pool = dex.species.values.filter { !it.isBattleOnly && it.formKind != FormKind.TOTEM && it.isNonstandard != "Future" || it.formKind == FormKind.BASE }.sortedBy { it.id }
        val items = dex.items.values.filter { it.hasBattleData && it.category != ItemCategory.Z_CRYSTAL || it.category == ItemCategory.Z_CRYSTAL }.map { it.id }.sorted()
        val allMoves = dex.moves.values.filter { it.isZ == null && it.isMax == null && it.id != "struggle" }.map { it.id }.sorted()

        fun randomMon(rng: Rng, level: Int): Pokemon {
            val sp = pool[rng.nextInt(pool.size)]
            val ls = dex.learnset(sp.id)
            val candidates = (ls?.levelUp?.map { it.move }.orEmpty() + ls?.machine.orEmpty()).distinct().ifEmpty { allMoves }
            val moves = (0 until 4).map { if (rng.nextInt(10) == 0) allMoves[rng.nextInt(allMoves.size)] else candidates[rng.nextInt(candidates.size)] }.distinct()
            val m = factory.create(sp.id, level, rng, moves = moves, item = if (rng.nextInt(3) > 0) items[rng.nextInt(items.size)] else null,
                abilitySlot = sp.abilities.keys.toList()[rng.nextInt(sp.abilities.size)])
            m.gmaxFactor = rng.nextInt(4) == 0
            return m
        }

        val perBattle = ArrayList<String>(battles)
        var turns = 0
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
            val md = MessageDigest.getInstance("SHA-256")
            fun feed(s: String) { md.update(s.toByteArray(Charsets.UTF_8)); md.update(0) }
            try {
                b.start()
                feed(b.stateHash())
                var guard = 0
                while (b.outcome == BattleOutcome.ONGOING && guard++ < MAX_STEPS) {
                    when (b.request(s0)) {
                        is Request.SwitchIn -> b.choose(s0, Choice.Switch(ai.chooseReplacement(b, s0)))
                        is Request.Move -> { val c = ai.chooseAction(b, s0); if (b.choose(s0, c) != null) b.choose(s0, Choice.UseMove(0)) }
                        Request.Wait -> {}
                    }
                    b.advance(ai)
                    feed(b.stateHash())
                }
            } catch (e: Throwable) {
                throw IllegalStateException("determinism battle $i (seed $seed) failed at turn ${b.turn}: $e", e)
            }
            feed(b.outcome.name); feed(b.turn.toString())
            for (line in b.log) feed(line)
            turns += b.turn
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            perBattle.add(hash)
            onBattle?.invoke(i, hash)
        }
        val all = MessageDigest.getInstance("SHA-256")
        for (h in perBattle) { all.update(h.toByteArray(Charsets.UTF_8)); all.update(10) }
        return Result(battles, turns, perBattle, all.digest().joinToString("") { "%02x".format(it) })
    }

    /** One-line summary used by every entry point so outputs can be compared by eye or with grep. */
    fun summary(r: Result) = "determinism battles=${r.battles} turns=${r.turns} hash=${r.combined}"
}
