package pkmn.engine.battle

import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.factory
import pkmn.engine.data.FormKind
import pkmn.engine.data.ItemCategory
import pkmn.engine.mon.Pokemon
import pkmn.engine.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Link play, Phase 1 (docs/MULTIPLAYER_LAN_PLAN.md): the two devices of a link battle build the same battle, one with the
 * local player as side 0 (host) and one as side 1 (guest). With the same seed, teams and choices they must reach equal
 * state hashes after every decision point and emit equal events apart from text, whatever order the choices arrive in.
 *
 * Each battle is played twice in lockstep: device A (side 0 = [Controller.PLAYER], side 1 = [Controller.REMOTE]) and
 * device B (the reverse). Choices come from a random-but-legal oracle that does not touch either battle's RNG; each device
 * submits them in its own random order and calls [Battle.advance] after every submission. `-Dfuzz.battles=N` sets the count.
 */
class LinkMirrorTest {
    private val pool by lazy { dex.species.values.filter { !it.isBattleOnly && it.formKind != FormKind.TOTEM && it.isNonstandard != "Future" || it.formKind == FormKind.BASE }.sortedBy { it.id } }
    private val items by lazy { dex.items.values.filter { it.hasBattleData && it.category != ItemCategory.Z_CRYSTAL || it.category == ItemCategory.Z_CRYSTAL }.map { it.id }.sorted() }
    private val allMoves by lazy { dex.moves.values.filter { it.isZ == null && it.isMax == null && it.id != "struggle" }.map { it.id }.sorted() }

    private fun randomMon(rng: Rng, level: Int): Pokemon {
        val sp = pool[rng.nextInt(pool.size)]
        val ls = dex.learnset(sp.id)
        val candidates = (ls?.levelUp?.map { it.move }.orEmpty() + ls?.machine.orEmpty()).distinct().ifEmpty { allMoves }
        val moves = (0 until 4).map { if (rng.nextInt(10) == 0) allMoves[rng.nextInt(allMoves.size)] else candidates[rng.nextInt(candidates.size)] }.distinct()
        val m = factory.create(sp.id, level, rng, moves = moves, item = if (rng.nextInt(3) > 0) items[rng.nextInt(items.size)] else null,
            abilitySlot = sp.abilities.keys.toList()[rng.nextInt(sp.abilities.size)])
        m.gmaxFactor = rng.nextInt(4) == 0
        // Varied friendship so Return and Frustration (and the faint friendship drop) matter.
        m.friendship = rng.nextInt(256)
        return m
    }

    /** One device's copy of battle [i]: the teams are rebuilt from the seed, so both devices hold equal, separate copies. */
    private fun device(i: Int, localSide: Int): Battle {
        val seed = 5000L + i
        val rng = Rng(seed)
        val level = 5 + rng.nextInt(96)
        val t1 = List(1 + rng.nextInt(6)) { randomMon(rng, level) }
        val t2 = List(1 + rng.nextInt(6)) { randomMon(rng, level) }
        fun ctl(side: Int) = if (side == localSide) Controller.PLAYER else Controller.REMOTE
        val s0 = Side(0, "Host", t1, ctl(0), dex, gimmicks = BattleRules.ALL_GIMMICKS)
        val s1 = Side(1, "Guest", t2, ctl(1), dex, gimmicks = BattleRules.ALL_GIMMICKS)
        val rules = BattleRules.link(allowedGimmicks = BattleRules.ALL_GIMMICKS, oneGimmickPerBattle = i % 2 == 0)
        return Battle(dex, listOf(s0, s1), rules, seed)
    }

    /** A request without object references, comparable across the two devices. */
    private fun describe(b: Battle, r: Request): String = when (r) {
        is Request.Move -> "Move(${b.teamIndex(r.active)},${r.usableMoves},${r.canSwitch},${r.gimmicks.sortedBy { it.ordinal }},${r.lockedMove},${r.slot})"
        is Request.SwitchIn -> r.toString()
        Request.Wait -> "Wait"
    }

    /** A random legal choice for [r]; [Choice.Forfeit] rarely, so most battles run to a knockout. */
    private fun decide(b: Battle, side: Side, r: Request, pick: Rng): Choice? = when (r) {
        Request.Wait -> null
        is Request.SwitchIn -> {
            // Replacements are rarer than move choices, so they forfeit more often to cover both forfeit paths.
            if (pick.nextInt(if (r.selfSwitch) 6 else 40) == 0) Choice.Forfeit
            else side.canSwitchTo().let { o -> Choice.Switch(b.teamIndex(o[pick.nextInt(o.size)])) }
        }
        is Request.Move -> when {
            pick.nextInt(300) == 0 -> Choice.Forfeit
            r.lockedMove != null -> Choice.Pass
            r.canSwitch && pick.nextInt(8) == 0 -> side.canSwitchTo().let { o -> Choice.Switch(b.teamIndex(o[pick.nextInt(o.size)])) }
            else -> {
                val usable = r.usableMoves.indices.filter { r.usableMoves[it] }
                val move = if (usable.isEmpty()) 0 else usable[pick.nextInt(usable.size)]
                val g = r.gimmicks.sortedBy { it.ordinal }.takeIf { it.isNotEmpty() && pick.nextInt(4) == 0 }?.let { it[pick.nextInt(it.size)] }
                Choice.UseMove(move, g)
            }
        }
    }

    private class Decision(val side: Int, val slot: Int, val choice: Choice)

    /**
     * Submits [ds] to [b] in [order], calling [Battle.advance] after each one. A rejected choice falls back to the first
     * usable move, then to Pass, as the UI would. Returns the rejection texts by decision index (must match on both devices).
     */
    private fun submit(b: Battle, ds: List<Decision>, order: List<Int>): Map<Int, String?> {
        val results = HashMap<Int, String?>()
        for (i in order) {
            val d = ds[i]
            val s = b.sides[d.side]
            var err = b.choose(s, d.choice, d.slot)
            results[i] = err
            if (err != null && d.choice !is Choice.Switch) {
                val a = s.actives[d.slot]
                val ms = a?.moveSlots?.indexOfFirst { b.canSelectMove(a, it) }?.takeIf { it >= 0 } ?: 0
                err = b.choose(s, Choice.UseMove(ms), d.slot)
                if (err != null) b.choose(s, Choice.Pass, d.slot)
            }
            b.advance()
        }
        return results
    }

    private fun shuffled(n: Int, rng: Rng): List<Int> {
        val l = (0 until n).toMutableList()
        for (i in n - 1 downTo 1) { val j = rng.nextInt(i + 1); val t = l[i]; l[i] = l[j]; l[j] = t }
        return l
    }

    @Test
    fun `host and guest views of a link battle stay identical`() {
        val battles = (System.getProperty("fuzz.battles") ?: "400").toInt()
        var turns = 0; var points = 0; var forfeits = 0; var textDiffers = 0
        val outcomes = HashMap<BattleOutcome, Int>()
        val forfeitPhases = java.util.TreeMap<String, Int>()
        for (i in 0 until battles) {
            val a = device(i, localSide = 0)
            val b = device(i, localSide = 1)
            val oracle = Rng(9000L + i)
            val orderA = Rng(13000L + i)
            val orderB = Rng(17000L + i)
            var seen = 0
            fun compare(where: String) {
                val ctx = "battle $i, turn ${a.turn}, $where"
                if (a.stateHash() != b.stateHash()) {
                    val da = dump(a); val db = dump(b)
                    val diff = da.indices.filter { da[it] != db.getOrNull(it) }.joinToString("\n") { "A: ${da[it]}\nB: ${db.getOrNull(it)}" }
                    throw AssertionError("state hash differs: $ctx\n$diff\n${a.log.takeLast(12).joinToString("\n")}")
                }
                assertEquals(a.outcome, b.outcome, "outcome differs: $ctx")
                assertEquals(a.events.size, b.events.size, "event count differs: $ctx\nA: ${a.log.takeLast(8)}\nB: ${b.log.takeLast(8)}")
                for (k in seen until a.events.size) {
                    val ea = a.events[k]; val eb = b.events[k]
                    assertEquals(withoutText(ea), withoutText(eb), "event $k differs: $ctx")
                    if (ea != eb) textDiffers++
                }
                seen = a.events.size
            }
            try {
                a.start(); b.start()
                compare("start")
                var guard = 0
                while (a.outcome == BattleOutcome.ONGOING && guard++ < 600) {
                    val ds = ArrayList<Decision>()
                    for (si in 0..1) for (slot in a.sides[si].actives.indices) {
                        val ra = a.request(a.sides[si], slot)
                        assertEquals(describe(a, ra), describe(b, b.request(b.sides[si], slot)), "request differs: battle $i side $si turn ${a.turn}")
                        decide(a, a.sides[si], ra, oracle)?.let { ds.add(Decision(si, slot, it)) }
                    }
                    check(ds.isNotEmpty()) { "battle $i: nobody has a decision at turn ${a.turn} but the battle is ongoing" }
                    if (ds.any { it.choice is Choice.Forfeit }) {
                        forfeits++
                        val r = ds.first { it.choice is Choice.Forfeit }.let { a.request(a.sides[it.side], it.slot) }
                        val phase = when { r is Request.SwitchIn && r.selfSwitch -> "selfSwitch"; r is Request.SwitchIn -> "replacement"; else -> "move" }
                        forfeitPhases.merge(phase, 1, Int::plus)
                    }
                    // A UI polls request() as often as it likes, and only on its own device: that must not change the battle.
                    repeat(orderA.nextInt(3)) { for (s in a.sides) for (slot in s.actives.indices) a.request(s, slot) }
                    val oa = shuffled(ds.size, orderA); val ob = shuffled(ds.size, orderB)
                    val ra = submit(a, ds, oa)
                    val rb = submit(b, ds, ob)
                    assertEquals(ra, rb, "choice results differ: battle $i turn ${a.turn}")
                    points++
                    compare("after choices ${ds.map { "side ${it.side}: ${it.choice::class.simpleName}" + ((it.choice as? Choice.Switch)?.teamIndex ?: "") }}, order A $oa, order B $ob, results A $ra, B $rb")
                }
                assertTrue(a.outcome != BattleOutcome.ONGOING || a.turn >= 300, "battle $i stalled at turn ${a.turn}")
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                throw AssertionError("link mirror battle $i failed at turn ${a.turn}: $e\n${a.log.takeLast(15).joinToString("\n")}", e)
            }
            turns += a.turn
            outcomes.merge(a.outcome, 1, Int::plus)
        }
        println("LinkMirror: $battles battles, $turns turns, $points decision points, $forfeits with a forfeit, " +
            "$textDiffers events with device-specific text, outcomes $outcomes, forfeits by phase $forfeitPhases")
        // The two views must really differ in text ("Enemy X" on one device, "X" on the other), or the test proves nothing.
        assertTrue(textDiffers > 0, "no event text differed between the two devices")
        for (phase in listOf("move", "replacement", "selfSwitch"))
            assertTrue((forfeitPhases[phase] ?: 0) > 0, "no battle forfeited during a $phase decision: $forfeitPhases")
    }

    /** Readable copy of most hashed fields, used to show where two devices differ when their hashes do. */
    private fun dump(b: Battle): List<String> {
        val out = arrayListOf("turn=${b.turn} outcome=${b.outcome} rng=${b.rng.state} weather=${b.field.weather} terrain=${b.field.terrain} pseudo=${b.field.pseudoWeather.keys}")
        for (s in b.sides) {
            out.add("side ${s.index} cond=${s.conditions.keys} slot=${s.slotConditions.keys} gimmicks=${s.usedGimmicks} fainted=${s.faintedLastTurn}/${s.faintedThisTurn}/${s.totalFainted} actives=${s.actives.map { it?.let { m -> s.mons.indexOf(m) } }}")
            for (m in s.mons) out.add("  ${m.species.id} hp=${m.mon.hp} st=${m.mon.status} lvl=${m.mon.level} fr=${m.mon.friendship} item=${m.item}/${m.mon.heldItem} last=${m.lastMove} moves=${m.mon.moves.map { "${it.id}:${it.pp}" }} slots=${m.moveSlots.map { "${it.id}:${it.pp}" }} boosts=${m.boosts.toList()} vol=${m.volatiles.keys} ab=${m.ability} types=${m.types} flags=${m.movedThisTurn}/${m.hurtThisTurn}/${m.activeTurns}/${m.lastDamage}/${m.switchFlag}/${m.forceSwitchFlag}")
        }
        return out
    }

    companion object {
        /** [e] with every display-text field blanked; everything else must match between the devices. */
        fun withoutText(e: BattleEvent): BattleEvent = when (e) {
            is BattleEvent.Message -> e.copy(text = "")
            is BattleEvent.SwitchIn -> e.copy(text = "")
            is BattleEvent.SwitchOut -> e.copy(text = "")
            is BattleEvent.MoveUsed -> e.copy(text = "")
            is BattleEvent.Faint -> e.copy(text = "")
            is BattleEvent.StatusChange -> e.copy(text = "")
            is BattleEvent.StatChange -> e.copy(text = "")
            is BattleEvent.FormeChange -> e.copy(text = "")
            is BattleEvent.FieldChange -> e.copy(text = "")
            is BattleEvent.ExpGain -> e.copy(text = "")
            is BattleEvent.LevelUp -> e.copy(text = "")
            else -> e
        }
    }
}
