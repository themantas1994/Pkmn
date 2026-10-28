package pkmn.engine.battle

import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.factory
import pkmn.engine.data.FormKind
import pkmn.engine.data.ItemCategory
import pkmn.engine.mon.Pokemon
import pkmn.engine.util.Rng
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Golden log for singles: plays seeded battles with random teams from the whole catalogue and checks that every
 * event, log line, request and choice result is byte-identical to the recorded run. It guards engine refactors that
 * must not change singles behaviour (the doubles work in Stage 3).
 *
 * Each battle is stored as one line (seed, turns, outcome, SHA-256 of its full transcript) in
 * `src/test/resources/golden/singles-battles.txt`. Full transcripts are written to `build/golden/singles/` so a
 * mismatch can be diffed against a run of the previous commit. Regenerate (only for an intended behaviour change)
 * with `./gradlew :engine:test --tests '*SinglesGoldenLogTest*' -Dgolden.update=true`.
 */
class SinglesGoldenLogTest {
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

    /** Request as text, without object identities, so it stays comparable across refactors of the request types. */
    private fun describe(r: Request): String = when (r) {
        is Request.Move -> "Move(${r.active} usable=${r.usableMoves} switch=${r.canSwitch} gimmicks=${r.gimmicks.sorted()} locked=${r.lockedMove})"
        is Request.SwitchIn -> "SwitchIn(forced=${r.forced} self=${r.selfSwitch})"
        Request.Wait -> "Wait"
    }

    /** Choice as text from its fields only (objects such as Run have no stable toString). */
    private fun describe(c: Choice): String = when (c) {
        is Choice.UseMove -> "UseMove(${c.moveIndex}, ${c.gimmick})"
        is Choice.Switch -> "Switch(${c.teamIndex})"
        is Choice.UseItem -> "UseItem(${c.itemId}, ${c.teamIndex}, ${c.moveIndex})"
        Choice.Run -> "Run"
        Choice.Pass -> "Pass"
    }

    /** Plays battle [i] and returns its full transcript. */
    private fun play(i: Int): Pair<String, String> {
        val seed = 50_000L + i
        val rng = Rng(seed)
        val drive = Rng(seed * 31 + 7) // player-side decisions, independent of the battle RNG
        val level = 5 + rng.nextInt(96)
        val t1 = List(1 + rng.nextInt(6)) { randomMon(rng, level) }
        val t2 = List(1 + rng.nextInt(6)) { randomMon(rng, level) }
        val wild = i % 4 == 0
        val s0 = Side(0, "A", t1, Controller.PLAYER, dex, aiLevel = 2, gimmicks = BattleRules.ALL_GIMMICKS)
        val s1 = Side(1, "B", t2, if (wild) Controller.WILD else Controller.AI, dex, aiLevel = 1 + i % 3, gimmicks = BattleRules.ALL_GIMMICKS)
        val rules = BattleRules(allowedGimmicks = BattleRules.ALL_GIMMICKS, oneGimmickPerBattle = i % 2 == 0, isTrainerBattle = !wild,
            setMode = i % 3 != 0, inverse = i % 17 == 0, expEnabled = i % 5 != 1, obedienceCap = if (i % 7 == 3) 10 else null)
        val b = Battle(dex, listOf(s0, s1), rules, seed)
        val ai = BattleAI()
        val out = StringBuilder()
        var consumed = 0
        fun flush() {
            while (consumed < b.events.size) out.append("E ").append(b.events[consumed++]).append('\n')
        }
        fun choose(c: Choice): String? = b.choose(s0, c).also { out.append("C ").append(describe(c)).append(" -> ").append(it).append('\n') }
        b.start()
        flush()
        var guard = 0
        while (b.outcome == BattleOutcome.ONGOING && guard++ < 400) {
            if (b.shiftAvailable() && drive.nextInt(2) == 0) {
                val idx = s0.mons.indexOf(s0.canSwitchTo().first())
                out.append("S shift ").append(idx).append(" -> ").append(b.chooseShift(idx)).append('\n')
            }
            val r = b.request(s0)
            out.append("R ").append(describe(r)).append('\n')
            when (r) {
                is Request.SwitchIn -> {
                    if (wild && !r.selfSwitch && drive.nextInt(3) == 0) out.append("F flee -> ").append(b.fleeInsteadOfReplacing()).append('\n')
                    if (b.outcome == BattleOutcome.ONGOING && b.request(s0) is Request.SwitchIn) choose(Choice.Switch(ai.chooseReplacement(b, s0)))
                }
                is Request.Move -> {
                    val roll = drive.nextInt(40)
                    val special: Choice? = when {
                        wild && roll == 0 -> Choice.Run
                        wild && roll in 1..3 -> Choice.UseItem(listOf("pokeball", "greatball", "ultraball", "quickball")[roll % 4])
                        roll == 4 -> Choice.UseItem("potion", s0.mons.indexOf(r.active))
                        roll == 5 -> Choice.UseItem("fullheal", s0.mons.indexOf(r.active))
                        else -> null
                    }
                    if (special == null || choose(special) != null) {
                        val c = ai.chooseAction(b, s0)
                        if (choose(c) != null) choose(Choice.UseMove(0))
                    }
                }
                Request.Wait -> {}
            }
            out.append("A ").append(b.advance(ai)).append('\n')
            flush()
        }
        out.append("END turn=").append(b.turn).append(" outcome=").append(b.outcome).append(" caught=").append(b.caughtMon?.speciesId)
            .append(" money=").append(b.moneyEarned).append(" payday=").append(b.payDay).append(" items=").append(b.itemsUsed)
            .append(" learns=").append(b.pendingMoveLearns).append(" leveled=").append(b.leveledUp).append('\n')
        for (line in b.log) out.append("L ").append(line).append('\n')
        for (m in s0.mons + s1.mons) out.append("M ").append(m.mon.speciesId).append(' ').append(m.mon.hp).append(' ').append(m.mon.status)
            .append(' ').append(m.mon.heldItem).append(' ').append(m.mon.level).append(' ').append(m.mon.exp).append(' ')
            .append(m.mon.moves.joinToString("/") { "${it.id}:${it.pp}" }).append('\n')
        val text = out.toString()
        return "seed=$seed turns=${b.turn} outcome=${b.outcome} sha256=${sha256(text)}" to text
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `seeded singles battles replay byte-identically`() {
        val dir = File("build/golden/singles").also { it.mkdirs() }
        val lines = (0 until BATTLES).map { i ->
            val (line, text) = try { play(i) } catch (e: Throwable) { throw AssertionError("golden battle $i crashed: $e", e) }
            File(dir, "battle-%03d.log".format(i)).writeText(text)
            line
        }
        val golden = File("src/test/resources/golden/singles-battles.txt")
        if (System.getProperty("golden.update") == "true" || !golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(lines.joinToString("\n", postfix = "\n"))
            println("Golden log written: ${golden.path} ($BATTLES battles)")
            if (System.getenv("CI") != null) fail("golden file was missing on CI")
            return
        }
        val expected = golden.readLines().filter { it.isNotBlank() }
        assertEquals(BATTLES, expected.size, "golden file battle count")
        val diffs = lines.indices.filter { lines[it] != expected[it] }
        if (diffs.isNotEmpty()) fail("${diffs.size} singles battle(s) differ from the golden log (first: battle ${diffs.first()}):\n" +
            diffs.take(5).joinToString("\n") { "  expected ${expected[it]}\n  actual   ${lines[it]}" } +
            "\nTranscripts: ${dir.absolutePath}")
    }

    companion object { const val BATTLES = 160 }
}
