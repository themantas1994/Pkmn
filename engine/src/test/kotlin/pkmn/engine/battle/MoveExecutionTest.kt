package pkmn.engine.battle

import kotlinx.serialization.json.*
import pkmn.engine.TestSupport
import pkmn.engine.TestSupport.dex
import pkmn.engine.battle.BattleCoverage.Status
import pkmn.engine.data.FormKind
import pkmn.engine.data.ItemCategory
import pkmn.engine.data.MoveData
import pkmn.engine.mon.Gender
import pkmn.engine.mon.MajorStatus
import pkmn.engine.util.toId
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Move execution audit: every move of the dex is executed in a deterministic singles battle and checked against
 * expectations derived from its data (damage, boosts, status, volatile/side/field conditions, heal, drain, recoil,
 * multi-hit, OHKO, fixed damage, forced switches, priority...) plus generic invariants (no exception, HP and stat
 * stages within bounds, PP spent). Accuracy below 100% and secondary-effect chances are handled by retrying over
 * fixed seeds; Serene Grace doubles secondary chances so rare secondaries show up within the retries.
 *
 * Z-Moves and Max/G-Max Moves are executed through their gimmick path (Z-Crystal / Dynamax with a base move).
 *
 * Each move ends in exactly one class, written to `build/reports/move-execution.json` (merged with
 * [BattleCoverage] into `reports/moves-audit.*` by `./gradlew :tools:auditMoves`):
 *  - implemented-and-tested: [BattleCoverage] says IMPLEMENTED_SCRIPT and the move was executed and checked;
 *  - data-driven-and-tested: DATA_DRIVEN (or a NO_EFFECT_NEEDED move with a data effect in singles), executed and checked;
 *  - no-battle-effect: the move has no singles effect and executing it changed nothing;
 *  - unsupported-or-incomplete: it threw, broke an invariant, or its expected effect was not observed.
 *
 * Set the environment variable DEBUG_MOVES=id1,id2 to print the repro and battle log of single moves.
 */
class MoveExecutionTest {

    // ------------------------------------------------------------------ scenario description

    private enum class Kind { PASS, FAIL, NA }
    private class CheckResult(val name: String, val kind: Kind, val text: String)

    private class Snap(val b: Battle, val user: BattleMon, val foe: BattleMon, foeMoveIndex: Int) {
        val values = LinkedHashMap<String, String>()
        init {
            for ((label, m) in listOf("user" to user, "foe" to foe)) {
                values["$label.hp"] = m.hp.toString()
                values["$label.status"] = m.status?.id ?: "-"
                BattleMon.BOOST_IDS.forEachIndexed { i, id -> values["$label.boost.$id"] = m.boosts[i].toString() }
                values["$label.volatiles"] = m.volatiles.keys.filter { it != "pendingmove" }.sorted().joinToString(",")
                values["$label.types"] = m.types.joinToString("/") + (m.addedType?.let { "+$it" } ?: "")
                values["$label.ability"] = m.ability
                values["$label.item"] = m.item ?: "-"
                values["$label.species"] = m.species.id
                values["$label.moves"] = m.moveSlots.joinToString(",") { it.id }
                values["$label.pp"] = m.moveSlots.filterIndexed { i, _ -> if (label == "user") i != 0 else i != foeMoveIndex }.joinToString(",") { it.pp.toString() }
                values["$label.dynamax"] = m.dynamaxTurns.toString()
            }
            for ((label, s) in listOf("userSide" to user.side, "foeSide" to foe.side)) {
                values["$label.conditions"] = s.conditions.keys.sorted().joinToString(",")
                values["$label.slots"] = s.slotConditions.keys.sorted().joinToString(",")
                values["$label.active"] = s.mons.indexOf(s.active).toString()
                values["$label.bench"] = s.mons.filter { it !== user && it !== foe }.joinToString(",") { it.hp.toString() }
            }
            values["field.weather"] = b.field.weather ?: "-"
            values["field.terrain"] = b.field.terrain ?: "-"
            values["field.pseudo"] = b.field.pseudoWeather.keys.sorted().joinToString(",")
        }
        operator fun get(k: String) = values.getValue(k)
        fun int(k: String) = get(k).toInt()
        fun set(k: String) = get(k).split(',').filter { it.isNotEmpty() }.toSet()
        fun diff(o: Snap) = values.keys.filter { values[it] != o.values[it] }.map { "$it: ${values[it]} -> ${o.values[it]}" }
    }

    private class Ctx(
        val m: MoveData, val s: Setup, val b: Battle, val user: BattleMon, val foe: BattleMon,
        val ev: List<BattleEvent>, val log: List<String>, val before: Snap, val after: Snap,
    ) {
    }

    /** Everything that differs from the default duel (Mew L100 vs Snorlax L100, both with an inert ability). */
    private class Setup {
        var userSpecies = "mew"
        var foeSpecies = "snorlax"
        var userLevel = 100
        var foeLevel = 100
        var userItem: String? = null
        var foeItem: String? = null
        var userAbility = "ballfetch"
        var foeAbility = "ballfetch"
        /** Moves after the tested one (slot 1..). */
        var userMoves: List<String> = emptyList()
        var foeMoves: List<String> = listOf("splash")
        var foeMoveIndex = 0
        var userHp = 1.0
        var foeHp = 1.0
        var userStatus: MajorStatus? = null
        var foeStatus: MajorStatus? = null
        var userGender: Gender = Gender.NONE
        var foeGender: Gender = Gender.NONE
        /** Turns played before the measured window: (user slot, foe slot). */
        var prelude: List<Pair<Int, Int>> = emptyList()
        /** Length of the measured window; turns after the first use slot [laterUserMove]. */
        var turns = 1
        var laterUserMove = 0
        var gimmick: Gimmick? = null
        var prep: (Battle, BattleMon, BattleMon) -> Unit = { _, _, _ -> }
        var note = ""
        var expectedPpCost = 1
        /** Move placed in slot 0 when the audited move is a Z-/Max move produced from a base move. */
        var baseMove: String? = null
        var gmax = false
        var userSpeedStage = 0
        var foeSpeedStage = 0
        /** The move has no effect in singles: only check that it executes and changes nothing. */
        var noExpectations = false
        /** Data-derived checks that cannot be observed in this scenario, with the reason. */
        val exempt = HashMap<String, String>()
        /** Checks specific to what this move's script does. */
        var extra: ((Ctx) -> List<CheckResult>)? = null
    }

    // ------------------------------------------------------------------ helpers

    private fun hpOf(m: BattleMon, fraction: Double) { if (fraction < 1.0) m.hp = max(1, (m.maxHp * fraction).toInt()) }

    private fun pickFoe(m: MoveData, s: Setup): String {
        val eff = dex.typeChart.effectiveness(m.type, dex.species(s.foeSpecies).types)
        // Ghost-type moves cannot touch the default Normal-type target; use a neutral Water type instead.
        return if (eff == 0.0 && s.foeSpecies == "snorlax") "vaporeon" else s.foeSpecies
    }

    private fun buildBattle(m: MoveData, s: Setup, seed: Long): Triple<Battle, BattleMon, BattleMon> {
        val u = TestSupport.mon(s.userSpecies, s.userLevel, listOf(s.baseMove ?: m.id) + s.userMoves, item = s.userItem)
        u.gmaxFactor = s.gmax
        val f = TestSupport.mon(pickFoe(m, s), s.foeLevel, s.foeMoves, item = s.foeItem)
        u.gender = s.userGender; f.gender = s.foeGender
        val b = TestSupport.battle(
            listOf(u, TestSupport.mon("pidgey", 50, listOf("tackle"))),
            listOf(f, TestSupport.mon("pidgey", 50, listOf("tackle"))), seed,
        )
        val user = b.player.active!!
        val foe = b.opponent.active!!
        user.ability = s.userAbility; user.baseAbility = s.userAbility; user.abilityState = EffectState()
        foe.ability = s.foeAbility; foe.baseAbility = s.foeAbility; foe.abilityState = EffectState()
        hpOf(user, s.userHp); hpOf(foe, s.foeHp)
        s.userStatus?.let { u.status = it; if (it == MajorStatus.SLEEP) u.sleepTurns = 6 }
        s.foeStatus?.let { f.status = it; if (it == MajorStatus.SLEEP) f.sleepTurns = 6 }
        user.boosts[4] = s.userSpeedStage; foe.boosts[4] = s.foeSpeedStage
        s.prep(b, user, foe)
        return Triple(b, user, foe)
    }

    /** Sends in a replacement when a Pokémon fainted or switched out, so the next turn can be played. */
    private fun settle(b: Battle) {
        var guard = 0
        while (b.outcome == BattleOutcome.ONGOING && guard++ < 4) {
            val r = b.request(b.player)
            if (r is Request.SwitchIn) {
                val idx = b.player.mons.indexOfFirst { !it.fainted && it !== b.player.active }
                if (idx < 0) break
                b.choose(b.player, Choice.Switch(idx))
                b.advance()
            } else if (b.needsReplacement(b.opponent)) b.advance() else break
        }
    }

    private fun play(b: Battle, userMove: Int, foeMove: Int, gimmick: Gimmick? = null): String? {
        b.choose(b.player, Choice.UseMove(userMove, gimmick))?.let { return "user choice rejected: $it" }
        b.choose(b.opponent, Choice.UseMove(foeMove))?.let { return "foe choice rejected: $it" }
        b.advance()
        settle(b)
        return null
    }

    private val failurePatterns = listOf("But it failed", "But nothing happened", "avoided the attack", "attack missed", "doesn't affect", "no target")

    /** Messages produced by the user's action (between its "used X!" line and the foe's next action) that are not failure notices. */
    private fun userWindow(c: Ctx): List<String> {
        val used = c.ev.filterIsInstance<BattleEvent.MoveUsed>().firstOrNull { it.side == c.user.side.index } ?: return emptyList()
        val i = c.log.indexOf(used.text)
        if (i < 0) return emptyList()
        val rest = c.log.drop(i + 1)
        val end = rest.indexOfFirst { it.contains(" used ") }
        return (if (end < 0) rest else rest.take(end)).filter { line -> failurePatterns.none { line.contains(it) } }
    }

    // ------------------------------------------------------------------ data-derived expectations

    private fun targetsSelf(m: MoveData) = m.target in setOf("self", "adjacentAllyOrSelf", "allies", "allySide", "allyTeam", "adjacentAlly")
    private fun fieldMove(m: MoveData) = m.target in setOf("all", "allySide", "foeSide", "allyTeam")

    private val weatherAliases = setOf("snowscape", "snow", "hail")

    private fun evaluate(c: Ctx): List<CheckResult> {
        val m = c.m; val s = c.s; val ev = c.ev; val log = c.log; val before = c.before; val after = c.after
        val user = c.user; val foe = c.foe
        val out = ArrayList<CheckResult>()
        fun add(name: String, kind: Kind, text: String) { if (name !in s.exempt) out.add(CheckResult(name, kind, text)) }
        val self = targetsSelf(m)
        val tookEffect = ev.any { it is BattleEvent.MoveEffect && it.moveId == m.id }
        val foeSide = foe.side.index
        val userSide = user.side.index
        val perHit = ev.filter { it is BattleEvent.HpChange && it.side == foeSide && it.cause == "move" && it.delta < 0 }.map { -(it as BattleEvent.HpChange).delta }
        val hits = perHit.size
        val dealt = perHit.sum()
        val missed = log.any { it.contains("avoided the attack") || it.contains("attack missed") }
        val damaging = !m.isStatus
        val applied = tookEffect && (!damaging || hits > 0)
        val label = if (self) "user" else "foe"
        val target = if (self) user else foe
        val window = userWindow(c)
        val actives = listOfNotNull(user.side.active, foe.side.active)
        fun statusSeen(want: String): Boolean {
            val side = target.side.index
            return ev.any { it is BattleEvent.StatusChange && it.side == side && it.status == want } || after["$label.status"] == want
        }

        if (damaging) {
            if (hits > 0) add("damage", Kind.PASS, "hit $hits time(s) for $dealt HP")
            else add("damage", Kind.NA, if (missed) "missed" else "no damage dealt: " + log.takeLast(3).joinToString(" | "))
        }
        if (m.boosts.isNotEmpty()) for ((stat, amount) in m.boosts) {
            if (!applied) { add("boosts:$label:$stat", Kind.NA, "move did not take effect"); continue }
            val d = after.int("$label.boost.$stat") - before.int("$label.boost.$stat")
            add("boosts:$label:$stat", if (d == max(-6, min(6, amount))) Kind.PASS else Kind.FAIL, "$stat changed by $d (data says $amount)")
        }
        m.self?.boosts?.forEach { (stat, amount) ->
            if (!applied) { add("self.boosts:$stat", Kind.NA, "move did not take effect"); return@forEach }
            val d = after.int("user.boost.$stat") - before.int("user.boost.$stat")
            add("self.boosts:$stat", if (d == max(-6, min(6, amount))) Kind.PASS else Kind.FAIL, "user $stat changed by $d (data says $amount)")
        }
        m.status?.let { st ->
            when {
                statusSeen(st) -> add("status:$st", Kind.PASS, "$label got $st")
                applied -> add("status:$st", Kind.FAIL, "move took effect but $label status is ${after["$label.status"]} and no status event was emitted")
                else -> add("status:$st", Kind.NA, "move did not take effect")
            }
        }
        m.volatileStatus?.let { v0 ->
            val v = toId(v0)
            val present = actives.any { v in it.volatiles.keys.map(::toId) } || (v == "flinch" && log.any { it.contains("flinched") })
            when {
                present -> add("volatile:$v", Kind.PASS, "'$v' is active")
                applied && window.isNotEmpty() -> add("volatile:$v", Kind.PASS, "transient condition (already gone at the end of the turn); message: ${window.first()}")
                applied -> add("volatile:$v", Kind.FAIL, "move took effect but volatile '$v' is not active (volatiles: ${after["$label.volatiles"]})")
                else -> add("volatile:$v", Kind.NA, "move did not take effect")
            }
        }
        m.sideCondition?.let { sc0 ->
            val sc = toId(sc0)
            val lab = if (m.target == "foeSide") "foeSide" else "userSide"
            when {
                sc in after.set("$lab.conditions") -> add("sideCondition:$sc", Kind.PASS, "$lab has $sc")
                applied && window.isNotEmpty() -> add("sideCondition:$sc", Kind.PASS, "transient condition (gone at the end of the turn); message: ${window.first()}")
                applied -> add("sideCondition:$sc", Kind.FAIL, "$lab conditions: ${after["$lab.conditions"]}")
                else -> add("sideCondition:$sc", Kind.NA, "move did not take effect")
            }
        }
        m.slotCondition?.let { sc0 ->
            val sc = toId(sc0)
            val has = sc in after.set("userSide.slots") || sc in after.set("foeSide.slots") || after["userSide.bench"] != before["userSide.bench"]
            add("slotCondition:$sc", if (has) Kind.PASS else if (applied) Kind.FAIL else Kind.NA, "slot conditions: ${after["userSide.slots"]}/${after["foeSide.slots"]}")
        }
        m.weather?.let { w ->
            val ok = if (w.lowercase() in weatherAliases) after["field.weather"] in weatherAliases else after["field.weather"] == toId(w)
            add("weather:${toId(w)}", if (ok) Kind.PASS else if (applied) Kind.FAIL else Kind.NA, "weather is ${after["field.weather"]}")
        }
        m.terrain?.let { t ->
            val ok = after["field.terrain"] == toId(t)
            add("terrain:${toId(t)}", if (ok) Kind.PASS else if (applied) Kind.FAIL else Kind.NA, "terrain is ${after["field.terrain"]}")
        }
        m.pseudoWeather?.let { p0 ->
            val p = toId(p0)
            when {
                p in after.set("field.pseudo") -> add("pseudoWeather:$p", Kind.PASS, "pseudo-weather: ${after["field.pseudo"]}")
                applied && window.isNotEmpty() -> add("pseudoWeather:$p", Kind.PASS, "transient condition (gone at the end of the turn); message: ${window.first()}")
                applied -> add("pseudoWeather:$p", Kind.FAIL, "pseudo-weather: ${after["field.pseudo"]}")
                else -> add("pseudoWeather:$p", Kind.NA, "move did not take effect")
            }
        }
        m.heal?.let { h ->
            val missingBefore = target.maxHp - before.int("$label.hp")
            val gain = after.int("$label.hp") - before.int("$label.hp")
            val exp = min(missingBefore, Math.round(target.maxHp * h[0].toDouble() / h[1]).toInt())
            when {
                !tookEffect -> add("heal", Kind.NA, "move did not take effect")
                abs(gain - exp) <= 1 -> add("heal", Kind.PASS, "restored $gain HP (expected about $exp)")
                else -> add("heal", Kind.FAIL, "restored $gain HP, expected about $exp (${h[0]}/${h[1]} of ${target.maxHp})")
            }
        }
        m.drain?.let { d ->
            val drained = ev.filter { it is BattleEvent.HpChange && it.side == userSide && it.cause == "drain" && it.delta > 0 }.sumOf { (it as BattleEvent.HpChange).delta }
            val exp = perHit.sumOf { max(1, Math.round(it.toDouble() * d[0] / d[1]).toInt()) }
            val room = user.maxHp - before.int("user.hp")
            when {
                hits == 0 -> add("drain", Kind.NA, "no damage dealt")
                abs(drained - min(exp, room)) <= hits -> add("drain", Kind.PASS, "user regained $drained HP for $dealt damage (${d[0]}/${d[1]})")
                else -> add("drain", Kind.FAIL, "user regained $drained HP, expected about ${min(exp, room)} for $dealt damage (${d[0]}/${d[1]})")
            }
        }
        m.recoil?.let { r ->
            val lost = ev.filter { it is BattleEvent.HpChange && it.side == userSide && it.cause == "recoil" && it.delta < 0 }.sumOf { -(it as BattleEvent.HpChange).delta }
            val exp = max(1, Math.round(dealt.toDouble() * r[0] / r[1]).toInt())
            when {
                hits == 0 -> add("recoil", Kind.NA, "no damage dealt")
                abs(lost - min(exp, before.int("user.hp"))) <= 1 -> add("recoil", Kind.PASS, "user lost $lost HP for $dealt damage (${r[0]}/${r[1]})")
                else -> add("recoil", Kind.FAIL, "user lost $lost HP, expected about $exp for $dealt damage (${r[0]}/${r[1]})")
            }
        }
        if (m.struggleRecoil) {
            val lost = ev.filter { it is BattleEvent.HpChange && it.side == userSide && it.cause == "recoil" && it.delta < 0 }.sumOf { -(it as BattleEvent.HpChange).delta }
            add("struggleRecoil", if (hits == 0) Kind.NA else if (lost > 0) Kind.PASS else Kind.FAIL, "user lost $lost HP")
        }
        if (m.mindBlownRecoil) {
            val lost = before.int("user.hp") - after.int("user.hp")
            add("mindBlownRecoil", if (!tookEffect) Kind.NA else if (lost > 0) Kind.PASS else Kind.FAIL, "user lost $lost HP")
        }
        m.multihit?.let { mh ->
            val lo = if (m.multiaccuracy) 1 else mh[0]
            when {
                hits == 0 -> add("multihit", Kind.NA, "no hits")
                hits in lo..mh[1] || (foe.hp == 0 && hits < lo) -> add("multihit", Kind.PASS, "$hits hits (data ${mh[0]}-${mh[1]})")
                else -> add("multihit", Kind.FAIL, "$hits hits, outside data range ${mh[0]}-${mh[1]}")
            }
        }
        m.fixedDamage?.let { fd ->
            val exp = if (fd == "level") user.level else fd.toInt()
            add("fixedDamage", if (perHit.isEmpty()) Kind.NA else if (perHit.all { it == exp }) Kind.PASS else Kind.FAIL, "damage per hit $perHit, expected $exp")
        }
        if (m.ohko != null) add("ohko", if (foe.hp == 0) Kind.PASS else Kind.NA, if (foe.hp == 0) "target fainted" else "target survived (${if (missed) "missed" else "no effect"})")
        if (m.willCrit && damaging) add("willCrit", if (hits == 0) Kind.NA else if (log.any { it == "A critical hit!" }) Kind.PASS else Kind.FAIL, "critical hit message present: ${log.any { it == "A critical hit!" }}")
        if (m.accuracy == 0 && !self && !fieldMove(m)) add("neverMisses", if (missed) Kind.FAIL else Kind.PASS, if (missed) "an always-hit move missed" else "did not miss")
        if (m.selfdestruct == "always") add("selfdestruct", if (user.hp == 0) Kind.PASS else Kind.FAIL, "user HP ${user.hp}")
        if (m.selfdestruct == "ifHit") add("selfdestruct", if (!applied) Kind.NA else if (user.hp == 0) Kind.PASS else Kind.FAIL, "user HP ${user.hp}")
        if (m.selfSwitch != null) {
            val switched = after["userSide.active"] != before["userSide.active"]
            add("selfSwitch", if (switched) Kind.PASS else if (applied) Kind.FAIL else Kind.NA, "user side active slot ${before["userSide.active"]} -> ${after["userSide.active"]}")
        }
        if (m.forceSwitch) {
            val dragged = after["foeSide.active"] != before["foeSide.active"]
            add("forceSwitch", if (dragged) Kind.PASS else if (applied) Kind.FAIL else Kind.NA, "foe side active slot ${before["foeSide.active"]} -> ${after["foeSide.active"]}")
        }
        // Secondary effects: chance based, retried over seeds (flinch is checked through its message).
        for ((i, sec) in m.secondaries.withIndex()) {
            val ok = tookEffect && hits > 0
            sec.status?.let { st -> val got = statusSeenFoe(ev, foeSide, st) || after["foe.status"] == st; add("secondary#$i:status:$st", if (got) Kind.PASS else Kind.NA, if (got) "foe got $st (${sec.chance}%)" else "not triggered in this run (${sec.chance}%)") }
            sec.volatileStatus?.let { v0 ->
                val v = toId(v0)
                val got = v in foe.volatiles.keys.map(::toId) || (v == "flinch" && log.any { it.contains("flinched") })
                add("secondary#$i:volatile:$v", if (got) Kind.PASS else Kind.NA, if (got) "foe has $v (${sec.chance}%)" else "not triggered in this run (${sec.chance}%)")
            }
            for ((stat, amount) in sec.boosts) {
                val d = after.int("foe.boost.$stat") - before.int("foe.boost.$stat")
                add("secondary#$i:boost:$stat", if (ok && d == max(-6, min(6, amount))) Kind.PASS else if (ok && d != 0) Kind.FAIL else Kind.NA, "foe $stat changed by $d (data $amount, ${sec.chance}%)")
            }
            sec.self?.let { se ->
                for ((stat, amount) in se.boosts) {
                    val d = after.int("user.boost.$stat") - before.int("user.boost.$stat")
                    add("secondary#$i:self:$stat", if (ok && d == max(-6, min(6, amount))) Kind.PASS else if (ok && d != 0) Kind.FAIL else Kind.NA, "user $stat changed by $d (data $amount, ${sec.chance}%)")
                }
                se.volatileStatus?.let { v -> add("secondary#$i:self:volatile:$v", if (v in after.set("user.volatiles")) Kind.PASS else Kind.NA, "user volatiles: ${after["user.volatiles"]}") }
            }
        }
        m.self?.volatileStatus?.let { v0 ->
            val v = toId(v0)
            val present = v in user.volatiles.keys.map(::toId)
            add("self.volatile:$v", if (present) Kind.PASS else if (applied && window.isNotEmpty()) Kind.PASS else if (applied) Kind.FAIL else Kind.NA,
                if (present) "user volatiles: ${after["user.volatiles"]}" else "transient condition; message: ${window.firstOrNull()}")
        }
        s.extra?.let { out.addAll(it(c)) }
        return out
    }

    private fun statusSeenFoe(ev: List<BattleEvent>, foeSide: Int, st: String) = ev.any { it is BattleEvent.StatusChange && it.side == foeSide && it.status == st }

    // ------------------------------------------------------------------ one execution

    private class Run(val seed: Long, val before: Snap, val after: Snap, val events: List<BattleEvent>, val log: List<String>, val checks: List<CheckResult>, val violations: List<String>, val fullLog: List<String>)

    private fun runOnce(m: MoveData, s: Setup, seed: Long): Run {
        val (b, user, foe) = buildBattle(m, s, seed)
        for ((u, f) in s.prelude) play(b, u, f)?.let { error("prelude: $it") }
        val slot = user.mon.moves.first()
        val ppBefore = slot.pp
        val before = Snap(b, user, foe, s.foeMoveIndex)
        val evStart = b.events.size
        val logStart = b.log.size
        for (t in 0 until s.turns) {
            if (b.outcome != BattleOutcome.ONGOING) break
            play(b, if (t == 0) 0 else s.laterUserMove, s.foeMoveIndex, if (t == 0) s.gimmick else null)?.let { error("turn $t: $it") }
        }
        // Two-turn moves finish on the following turn.
        var extra = 0
        while (s.turns == 1 && b.outcome == BattleOutcome.ONGOING && user.volatiles.containsKey("twoturnmove") && extra++ < 2) {
            play(b, 0, s.foeMoveIndex)?.let { error("charge turn: $it") }
        }
        val after = Snap(b, user, foe, s.foeMoveIndex)
        val ev = b.events.drop(evStart)
        val log = b.log.drop(logStart)
        val violations = ArrayList<String>()
        for (bm in b.player.mons + b.opponent.mons) {
            if (bm.mon.hp !in 0..bm.maxHp) violations += "HP out of bounds for ${bm.name}: ${bm.mon.hp}/${bm.maxHp}"
            if (bm.boosts.any { it !in -6..6 }) violations += "stat stage out of bounds for ${bm.name}: ${bm.boosts.toList()}"
            if (bm.mon.moves.any { it.pp < 0 }) violations += "negative PP on ${bm.name}"
        }
        if (m.id != "struggle" && slot in user.mon.moves) {
            val expected = max(0, ppBefore - s.expectedPpCost)
            if (slot.pp != expected) violations += "PP is ${slot.pp} after using ${s.baseMove ?: m.id}, expected $expected (was $ppBefore)"
        }
        val checks = if (s.noExpectations) emptyList() else evaluate(Ctx(m, s, b, user, foe, ev, log, before, after))
        return Run(seed, before, after, ev, log, checks, violations, b.log.toList())
    }

    // ------------------------------------------------------------------ per-move scenarios

    private val berry = "oranberry"

    private fun chk(name: String, ok: Boolean, text: String) = CheckResult(name, if (ok) Kind.PASS else Kind.FAIL, text)

    private fun defaultSetup(m: MoveData): Setup = Setup().apply {
        if (m.drain != null) userHp = 0.5
        m.heal?.let { if (targetsSelf(m)) userHp = 0.5 else foeHp = 0.5 }
        if (m.secondaries.any { it.chance < 100 }) userAbility = "serenegrace"
        if (m.volatileStatus == "attract" || m.id == "captivate") { userGender = Gender.MALE; foeGender = Gender.FEMALE }
    }

    private fun setupFor(m: MoveData): Setup {
        val s = defaultSetup(m)
        if (m.id in BattleCoverage.MOVES_NO_SINGLES_EFFECT) s.noExpectations = true
        SETUPS[m.id]?.invoke(s, m)
        return s
    }

    private val SETUPS: Map<String, Setup.(MoveData) -> Unit> = buildMap {
        fun on(vararg ids: String, f: Setup.(MoveData) -> Unit) { for (id in ids) put(id, f) }
        // ---- needs a status / item / state on one of the sides
        on("dreameater", "nightmare") { foeStatus = MajorStatus.SLEEP }
        on("snore") { userStatus = MajorStatus.SLEEP }
        on("sleeptalk") { userStatus = MajorStatus.SLEEP; userMoves = listOf("tackle") }
        on("fling") { userItem = "ironball" }
        on("naturalgift", "stuffcheeks") { userItem = berry }
        on("belch") { prep = { _, u, _ -> u.volatiles["ateberry"] = EffectState() } }
        on("poltergeist", "knockoff", "trick", "switcheroo", "bugbite", "pluck", "incinerate", "thief", "covet") { foeItem = berry }
        on("corrosivegas") { foeItem = "leftovers" }
        on("teatime") { userItem = berry; foeItem = berry }
        on("recycle") { prep = { _, u, _ -> u.lastItem = berry } }
        on("bestow") { userItem = berry }
        on("synchronoise") { foeSpecies = "slowbro" }
        on("lastresort") { userMoves = listOf("tackle"); prep = { _, u, _ -> u.moveSlots[1].pp -= 1 } }
        on("steelroller") { prep = { b, _, _ -> b.setTerrain("grassyterrain", null) } }
        on("auroraveil") { prep = { b, _, _ -> b.setWeather("snowscape", null) } }
        on("burnup") { userSpecies = "charizard" }
        on("doubleshock") { userSpecies = "pikachu" }
        on("hyperspacefury") { userSpecies = "hoopaunbound" }
        on("darkvoid") { userSpecies = "darkrai" }
        on("aurawheel") { userSpecies = "morpeko" }
        on("skydrop") { foeSpecies = "pikachu" }
        on("curse") { userSpecies = "gengar" }
        // ---- the foe must act first / must attack
        on("suckerpunch", "thunderclap") { foeMoves = listOf("tackle") }
        on("upperhand") { foeMoves = listOf("quickattack") }
        on("counter", "shelltrap") { foeMoves = listOf("tackle") }
        on("metalburst", "comeuppance") { foeMoves = listOf("tackle"); userSpeedStage = -6; foeSpeedStage = 6 }
        on("mirrorcoat") { foeMoves = listOf("watergun") }
        on("bide") {
            foeMoves = listOf("tackle"); turns = 3
            exempt["damage"] = "Bide's unleash is not a normal hit; checked below"
            exempt["volatile:bide"] = "the volatile ends when the energy is unleashed; checked below"
            extra = { c ->
                val stored = c.before.int("user.hp") - c.after.int("user.hp")
                val dealtToFoe = c.before.int("foe.hp") - c.after.int("foe.hp")
                listOf(chk("bideUnleashesDamage", c.log.any { it.contains("unleashed its energy") } && dealtToFoe > 0, "unleash message ${c.log.any { it.contains("unleashed") }}, user lost $stored over 3 turns, foe lost $dealtToFoe"))
            }
        }
        on("mefirst") { foeMoves = listOf("tackle") }
        // ---- needs the foe's last move
        on("copycat", "mirrormove", "instruct") { userMoves = listOf("splash"); foeMoves = listOf("tackle"); prelude = listOf(1 to 0) }
        on("encore", "disable", "spite") { userMoves = listOf("splash"); foeMoves = listOf("tackle", "splash"); foeMoveIndex = 1; prelude = listOf(1 to 0) }
        on("mimic", "sketch", "conversion2") { userMoves = listOf("splash"); foeMoves = listOf("tackle"); prelude = listOf(1 to 0) }
        on("psychup", "heartswap", "guardswap", "powerswap", "haze", "clearsmog", "topsyturvy", "spectralthief", "punishment") {
            prep = { _, _, f -> f.boosts[0] = 2; f.boosts[1] = 2; f.boosts[2] = 2; f.boosts[3] = 2 }
        }
        on("revivalblessing") {
            prep = { b, _, _ -> b.player.mons[1].hp = 0 }
            exempt["selfSwitch"] = "Showdown models the party prompt of Revival Blessing as a self-switch flag; the user stays on the field"
        }
        on("healingwish", "lunardance") { prep = { b, _, _ -> b.player.mons[1].hp = 5 } }
        on("wish") {
            turns = 2; laterUserMove = 1; userHp = 0.5; userMoves = listOf("splash")
            exempt["slotCondition:wish"] = "the slot condition is consumed when Wish heals; checked below"
            extra = { c -> listOf(chk("wishHealsHalf", c.after.int("user.hp") - c.before.int("user.hp") >= c.user.maxHp / 2 - 1, "HP ${c.before["user.hp"]} -> ${c.after["user.hp"]} of ${c.user.maxHp} after the delay")) }
        }
        on("futuresight", "doomdesire") {
            turns = 3; laterUserMove = 1; userMoves = listOf("splash")
            exempt["damage"] = "the delayed hit is dealt by the foe slot condition; checked below"
            extra = { c -> listOf(chk("delayedDamage", c.after.int("foe.hp") < c.before.int("foe.hp"), "foe HP ${c.before["foe.hp"]} -> ${c.after["foe.hp"]} after the delay")) }
        }
        on("yawn") {
            turns = 2; laterUserMove = 1; userMoves = listOf("splash")
            exempt["volatile:yawn"] = "the volatile is consumed when the target falls asleep; checked below"
            extra = { c -> listOf(chk("yawnPutsFoeToSleep", c.ev.any { it is BattleEvent.StatusChange && it.side == c.foe.side.index && it.status == "slp" }, "foe status after two turns: ${c.after["foe.status"]}")) }
        }
        // ---- state-changing moves that need something to change
        on("aromatherapy", "healbell", "refresh") {
            userStatus = MajorStatus.BURN
            extra = { c -> listOf(chk("curesStatus", c.after["user.status"] == "-", "user status ${c.before["user.status"]} -> ${c.after["user.status"]}")) }
        }
        on("psychoshift") {
            userStatus = MajorStatus.BURN
            extra = { c -> listOf(chk("movesStatus", c.after["user.status"] == "-" && c.after["foe.status"] == "brn", "status user ${c.after["user.status"]}, foe ${c.after["foe.status"]}")) }
        }
        on("rest") {
            userHp = 0.5
            extra = { c -> listOf(chk("restHealsAndSleeps", c.after.int("user.hp") == c.user.maxHp && c.after["user.status"] == "slp", "HP ${c.after["user.hp"]}/${c.user.maxHp}, status ${c.after["user.status"]}")) }
        }
        on("moonlight", "morningsun", "synthesis", "shoreup") {
            userHp = 0.5
            extra = { c -> listOf(chk("healsHalf", abs((c.after.int("user.hp") - c.before.int("user.hp")) - c.user.maxHp / 2) <= 1, "HP ${c.before["user.hp"]} -> ${c.after["user.hp"]} of ${c.user.maxHp}")) }
        }
        on("junglehealing", "lunarblessing") {
            userHp = 0.5; userStatus = MajorStatus.BURN
            extra = { c -> listOf(chk("healsAndCures", c.after.int("user.hp") > c.before.int("user.hp") && c.after["user.status"] == "-", "HP ${c.before["user.hp"]} -> ${c.after["user.hp"]}, status ${c.after["user.status"]}")) }
        }
        on("swallow") {
            userHp = 0.5; userMoves = listOf("stockpile"); prelude = listOf(1 to 0)
            extra = { c -> listOf(chk("swallowHeals", c.after.int("user.hp") > c.before.int("user.hp"), "HP ${c.before["user.hp"]} -> ${c.after["user.hp"]}")) }
        }
        on("spitup") {
            userMoves = listOf("stockpile"); prelude = listOf(1 to 0)
            extra = { c -> listOf(chk("spitUpConsumesStockpile", "stockpile" !in c.after.set("user.volatiles"), "volatiles ${c.after["user.volatiles"]}")) }
        }
        on("diamondstorm") {
            userAbility = "serenegrace"
            exempt["self.boosts:def"] = "raw data is self.chance 50: the script rolls it (Serene Grace makes it certain); checked below"
            extra = { c -> listOf(chk("diamondStormRaisesDefByTwo", c.after.int("user.boost.def") - c.before.int("user.boost.def") == 2, "Def stage ${c.before["user.boost.def"]} -> ${c.after["user.boost.def"]}")) }
        }
        on("knockoff") {
            foeItem = berry
            extra = { c -> listOf(chk("knocksOffItem", c.after["foe.item"] == "-", "foe item ${c.before["foe.item"]} -> ${c.after["foe.item"]}")) }
        }
        on("burnup") {
            userSpecies = "charizard"
            extra = { c -> listOf(chk("losesFireType", "Fire" !in c.after["user.types"].split("/", "+"), "types ${c.before["user.types"]} -> ${c.after["user.types"]}")) }
        }
        on("doodle") { foeAbility = "levitate"; extra = { c -> listOf(chk("copiesAbility", c.after["user.ability"] == "levitate", "user ability ${c.after["user.ability"]}")) } }
        on("entrainment") { userAbility = "levitate"; extra = { c -> listOf(chk("givesAbility", c.after["foe.ability"] == "levitate", "foe ability ${c.after["foe.ability"]}")) } }
        on("gearup", "magneticflux") { userAbility = "plus" }
        on("flowershield", "rototiller") { userSpecies = "venusaur" }
        on("venomdrench") { foeStatus = MajorStatus.POISON }
        on("courtchange") {
            prep = { b, u, f -> b.addSideCondition(u.side, "reflect", u); b.addSideCondition(f.side, "lightscreen", f) }
            extra = { c -> listOf(chk("swapsSides", "reflect" in c.after.set("foeSide.conditions") && "lightscreen" in c.after.set("userSide.conditions"), "sides now ${c.after["userSide.conditions"]} | ${c.after["foeSide.conditions"]}")) }
        }
        on("sparklingaria") {
            foeStatus = MajorStatus.BURN
            exempt["secondary#0:volatile:sparklingaria"] = "script-defined secondary: cures the target's burn (checked below)"
            extra = { c -> listOf(chk("curesBurn", c.after["foe.status"] == "-", "foe status ${c.before["foe.status"]} -> ${c.after["foe.status"]}")) }
        }
        // ---- protection: the foe attacks into it
        fun protect(effect: (Ctx) -> CheckResult?) = { s: Setup, _: MoveData ->
            s.foeMoves = listOf("tackle")
            s.extra = { c ->
                val blocks = c.log.count { it.contains("protected itself") }
                buildList {
                    add(chk("blocksAttack", blocks >= 2 && c.after["user.hp"] == c.before["user.hp"], "'protected itself' printed $blocks times, user HP ${c.before["user.hp"]} -> ${c.after["user.hp"]}"))
                    effect(c)?.let { add(it) }
                }
            }
        }
        put("protect", protect { null }); put("detect", protect { null })
        put("kingsshield", protect { c -> chk("kingsShieldLowersAtk", c.after.int("foe.boost.atk") < 0, "foe Atk stage ${c.after["foe.boost.atk"]}") })
        put("spikyshield", protect { c -> chk("spikyShieldHurts", c.after.int("foe.hp") < c.before.int("foe.hp"), "foe HP ${c.before["foe.hp"]} -> ${c.after["foe.hp"]}") })
        put("banefulbunker", protect { c -> chk("banefulBunkerPoisons", c.after["foe.status"] == "psn", "foe status ${c.after["foe.status"]}") })
        put("obstruct", protect { c -> chk("obstructLowersDef", c.after.int("foe.boost.def") < 0, "foe Def stage ${c.after["foe.boost.def"]}") })
        put("silktrap", protect { c -> chk("silkTrapLowersSpe", c.after.int("foe.boost.spe") < 0, "foe Spe stage ${c.after["foe.boost.spe"]}") })
        put("burningbulwark", protect { c -> chk("burningBulwarkBurns", c.after["foe.status"] == "brn", "foe status ${c.after["foe.status"]}") })
        on("endure") {
            userHp = 0.001; foeMoves = listOf("tackle")
            extra = { c -> listOf(chk("endures", c.after.int("user.hp") == 1 && c.log.any { it.contains("endured the hit") }, "user HP ${c.after["user.hp"]}, endure message ${c.log.any { it.contains("endured the hit") }}")) }
        }
        for ((id, foeMove, check) in listOf(Triple("quickguard", "quickattack", "blocksPriority"), Triple("wideguard", "earthquake", "blocksSpread"), Triple("craftyshield", "leer", "blocksStatus"), Triple("matblock", "tackle", "blocksDamage")))
            put(id) { _ ->
                foeMoves = listOf(foeMove)
                exempt["sideCondition:$id"] = "lasts only for the turn it was used; verified through the blocked attack"
                extra = { c ->
                    val blockedMsg = c.log.any { it.contains("protected") || it.contains("blocked by") }
                    val unchanged = c.after["user.hp"] == c.before["user.hp"] && c.after["user.boost.def"] == c.before["user.boost.def"]
                    listOf(chk(check, blockedMsg && unchanged, "block message $blockedMsg, user HP ${c.before["user.hp"]} -> ${c.after["user.hp"]}, Def stage ${c.after["user.boost.def"]}"))
                }
            }
        on("magiccoat") {
            foeMoves = listOf("leer")
            extra = { c -> listOf(chk("reflects", c.log.any { it.contains("bounced") } && c.after.int("user.boost.def") == 0, "bounce message ${c.log.any { it.contains("bounced") }}, user Def stage ${c.after["user.boost.def"]}")) }
        }
        on("snatch") {
            foeMoves = listOf("swordsdance")
            extra = { c -> listOf(chk("steals", c.after.int("user.boost.atk") == 2 && c.after.int("foe.boost.atk") == 0, "user Atk ${c.after["user.boost.atk"]}, foe Atk ${c.after["foe.boost.atk"]}")) }
        }
        on("powder") {
            foeMoves = listOf("ember")
            extra = { c -> listOf(chk("explodesFire", c.log.any { it.contains("exploded") } && c.after.int("foe.hp") < c.before.int("foe.hp"), "explosion message ${c.log.any { it.contains("exploded") }}, foe HP ${c.before["foe.hp"]} -> ${c.after["foe.hp"]}")) }
        }
        on("electrify") {
            foeMoves = listOf("tackle")
            extra = { c -> listOf(chk("electrifies", c.log.any { it.contains("electrified") }, "electrified message ${c.log.any { it.contains("electrified") }}")) }
        }
        on("ragepowder", "followme", "spotlight", "helpinghand", "dragoncheer", "coaching", "aromaticmist") { }
    }

    // ------------------------------------------------------------------ move bookkeeping

    private class MoveRecord(val id: String, val name: String) {
        var coverage = ""
        var coverageReason = ""
        var classification = ""
        var path = "singles"
        val evidence = ArrayList<String>()
        val failures = ArrayList<String>()
        var seeds = 0
        var repro = ""
        val checks = ArrayList<String>()
    }

    private val SEEDS = 48

    private fun audit(m: MoveData, cov: BattleCoverage.Entry): MoveRecord {
        val rec = MoveRecord(m.id, m.name)
        rec.coverage = cov.status.name; rec.coverageReason = cov.reason
        val s = when {
            m.isZ != null -> zSetup(m, rec)
            m.isMax != null -> maxSetup(m, rec)
            else -> setupFor(m)
        }
        if (s == null) { rec.classification = "unsupported-or-incomplete"; return rec }
        rec.repro = "${s.userSpecies} L${s.userLevel} [${(listOf(s.baseMove ?: m.id) + s.userMoves).joinToString(",")}] vs ${pickFoe(m, s)} L${s.foeLevel} [${s.foeMoves.joinToString(",")}]" +
            (s.gimmick?.let { " gimmick=$it" } ?: "") + (s.userItem?.let { " userItem=$it" } ?: "") + (s.foeItem?.let { " foeItem=$it" } ?: "") + (if (s.note.isNotEmpty()) " (${s.note})" else "")
        val pass = LinkedHashMap<String, String>()
        val fail = LinkedHashMap<String, String>()
        val na = LinkedHashMap<String, String>()
        val diffs = LinkedHashSet<String>()
        var anyEffect = false
        var executed = 0
        for (i in 0 until SEEDS) {
            val seed = 1000L + i
            val run = try { runOnce(m, s, seed) } catch (e: Throwable) {
                val where = e.stackTrace.filter { it.className.startsWith("pkmn.engine") }.take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                rec.failures += "seed $seed threw ${e.javaClass.simpleName}: ${e.message} at $where"
                break
            }
            executed++
            rec.seeds = executed
            for (v in run.violations) rec.failures += "seed $seed invariant: $v"
            for (c in run.checks) when (c.kind) {
                Kind.PASS -> pass.putIfAbsent(c.name, c.text)
                Kind.FAIL -> fail.putIfAbsent(c.name, "seed $seed: ${c.text}")
                Kind.NA -> na[c.name] = c.text
            }
            val d = run.before.diff(run.after)
            diffs += d
            if (d.isNotEmpty()) anyEffect = true
            if (rec.failures.isNotEmpty()) break
            if (i >= 3 && run.checks.all { it.name in pass }) break
        }
        // Priority: honoured relative to speed (separate run with exaggerated speed stages).
        if (rec.failures.isEmpty() && fail.isEmpty() && m.priority != 0 && m.isZ == null && m.isMax == null && !s.noExpectations) {
            val ps = setupFor(m)
            if (m.priority > 0) { ps.userSpeedStage = -6; ps.foeSpeedStage = 6 } else { ps.userSpeedStage = 6; ps.foeSpeedStage = -6 }
            var verdict: String? = null
            for (i in 0 until 12) {
                val r = try { runOnce(m, ps, 5000L + i) } catch (e: Throwable) { rec.failures += "priority run threw ${e.javaClass.simpleName}: ${e.message}"; break }
                val used = r.events.filterIsInstance<BattleEvent.MoveUsed>()
                val u = used.indexOfFirst { it.side == 0 && (it.moveId == m.id || it.moveId == s.baseMove) }
                val f = used.indexOfFirst { it.side == 1 }
                if (u < 0) continue
                if (f < 0) { // the much faster foe never got to act (flinch, Powder...): only possible if the user went first
                    if (m.priority > 0) { verdict = "ok"; break }
                    continue
                }
                verdict = if ((m.priority > 0) == (u < f)) "ok" else "user used ${m.id} ${if (u < f) "before" else "after"} the foe although its priority is ${m.priority} and its speed stage is ${ps.userSpeedStage} (foe ${ps.foeSpeedStage})"
                break
            }
            when (verdict) {
                "ok" -> pass["priority"] = "priority ${m.priority} honoured against a ${if (m.priority > 0) "much faster" else "much slower"} foe"
                null -> na["priority"] = "move never reached the point of being used in the priority run"
                else -> fail["priority"] = verdict
            }
        }
        val unobserved = na.keys.filter { it !in pass && it !in fail }
        for ((k, v) in fail) rec.failures += "check '$k' failed: $v"
        for (k in unobserved) rec.failures += (if (k.startsWith("secondary#")) "secondary effect never observed in $executed seeds: " else "expected effect not observed in $executed seeds: ") + "$k (${na[k]})"
        rec.checks += pass.keys
        for ((k, v) in pass) rec.evidence += "$k: $v"
        val hasChecks = pass.isNotEmpty()
        val scripted = cov.status == Status.IMPLEMENTED_SCRIPT
        when {
            rec.failures.isNotEmpty() -> rec.classification = "unsupported-or-incomplete"
            cov.status == Status.UNIMPLEMENTED -> { rec.classification = "unsupported-or-incomplete"; rec.failures += "BattleCoverage: UNIMPLEMENTED (${cov.reason})" }
            s.noExpectations -> {
                rec.classification = "no-battle-effect"
                rec.evidence += "coverage: ${cov.status} (${cov.reason}); executed $executed seed(s)" + if (anyEffect) "; state changes seen: ${diffs.take(4)}" else " without any state change"
            }
            hasChecks && scripted -> { rec.classification = "implemented-and-tested"; if (diffs.isNotEmpty()) rec.evidence += "state diff: " + diffs.take(6).joinToString("; ") }
            hasChecks -> { rec.classification = "data-driven-and-tested"; if (cov.status == Status.NO_EFFECT_NEEDED) rec.evidence += "coverage reason: ${cov.reason}" }
            anyEffect && scripted -> { rec.classification = "implemented-and-tested"; rec.evidence += "state diff: " + diffs.take(8).joinToString("; ") }
            anyEffect -> { rec.classification = "data-driven-and-tested"; rec.evidence += "state diff: " + diffs.take(8).joinToString("; ") }
            m.id in NO_EFFECT_BY_DESIGN -> {
                rec.classification = "no-battle-effect"
                rec.evidence += NO_EFFECT_BY_DESIGN.getValue(m.id) + "; coverage: ${cov.status} (${cov.reason}); executed $executed seed(s) without any state change"
            }
            cov.status == Status.NO_EFFECT_NEEDED || cov.status == Status.DATA_DRIVEN -> {
                rec.classification = "no-battle-effect"
                rec.evidence += "coverage: ${cov.status} (${cov.reason}); executed $executed seed(s) with no state change"
            }
            else -> { rec.classification = "unsupported-or-incomplete"; rec.failures += "scripted move executed but no observable effect in $executed seeds" }
        }
        return rec
    }

    /** Moves whose effect is a message or lies outside a single battle, so a clean no-op is the correct behaviour. */
    private val NO_EFFECT_BY_DESIGN = mapOf(
        "splash" to "Splash only prints 'But nothing happened!'",
        "celebrate" to "Celebrate only prints a message",
        "happyhour" to "Happy Hour only prints a message (prize money is not modelled)",
        "holdhands" to "Hold Hands needs an ally",
    )

    // ------------------------------------------------------------------ Z-moves and Max moves

    /** One plain damaging move per type to feed Z-/Max conversions (strictest candidates first). */
    private val simpleDamaging: Map<String, MoveData> by lazy {
        val all = dex.moves.values.filter {
            !it.isStatus && it.basePower in 40..120 && it.isNonstandard == null && it.isZ == null && it.isMax == null && !it.hasFlag("charge") && !it.hasFlag("recharge") &&
                it.selfSwitch == null && !it.forceSwitch && it.selfdestruct == null && it.drain == null && it.recoil == null && it.self == null && Effects.move(it.id) == null
        }.sortedBy { it.id }
        fun strict(m: MoveData) = m.secondaries.isEmpty() && m.multihit == null && m.priority == 0 && m.accuracy in listOf(0, 100)
        dex.typeChart.types.mapNotNull { t -> val ofType = all.filter { it.type == t }; (ofType.firstOrNull(::strict) ?: ofType.firstOrNull())?.let { t to it } }.toMap()
    }

    private val zMoveByType = mapOf(
        "Normal" to "breakneckblitz", "Fighting" to "alloutpummeling", "Flying" to "supersonicskystrike", "Poison" to "aciddownpour",
        "Ground" to "tectonicrage", "Rock" to "continentalcrush", "Bug" to "savagespinout", "Ghost" to "neverendingnightmare",
        "Steel" to "corkscrewcrash", "Fire" to "infernooverdrive", "Water" to "hydrovortex", "Grass" to "bloomdoom",
        "Electric" to "gigavolthavoc", "Psychic" to "shatteredpsyche", "Ice" to "subzeroslammer", "Dragon" to "devastatingdrake",
        "Dark" to "blackholeeclipse", "Fairy" to "twinkletackle",
    )

    private fun zSetup(m: MoveData, rec: MoveRecord): Setup? {
        rec.path = "z-move"
        val crystal = dex.items.values.firstOrNull { it.category == ItemCategory.Z_CRYSTAL && it.zMove != null && it.zMove != "true" && toId(it.zMove!!) == m.id }
            ?: dex.items.values.firstOrNull { it.category == ItemCategory.Z_CRYSTAL && it.zMove == "true" && zMoveByType[it.zMoveType] == m.id }
        if (crystal == null) { rec.failures += "no Z-Crystal item in the data produces ${m.id}"; return null }
        val s = Setup()
        s.gimmick = Gimmick.ZMOVE
        s.userItem = crystal.id
        if (crystal.zMove == "true") {
            val base = simpleDamaging[crystal.zMoveType] ?: run { rec.failures += "no plain damaging ${crystal.zMoveType} move to convert"; return null }
            s.baseMove = base.id; s.note = "generic Z-Move from ${base.id}"
        } else {
            s.userSpecies = crystal.itemUser.firstOrNull() ?: "mew"
            s.baseMove = crystal.zMoveFrom.firstOrNull() ?: "tackle"
            s.note = "signature Z-Move from ${s.baseMove} with ${crystal.id}"
        }
        s.extra = { c -> listOf(chk("usedZMove", c.log.any { it.contains("used ${m.name}!") }, "log has 'used ${m.name}!'")) }
        return s
    }

    private fun maxSetup(m: MoveData, rec: MoveRecord): Setup? {
        rec.path = "max-move"
        val s = Setup()
        s.gimmick = Gimmick.DYNAMAX
        if (m.isMax != "true") {
            val sp = dex.species.values.firstOrNull { it.canGigantamax == m.name && dex.speciesOrNull(it.id + "gmax") != null }
                ?: run { rec.failures += "no species can Gigantamax into ${m.name}"; return null }
            val base = simpleDamaging[m.type] ?: run { rec.failures += "no plain damaging ${m.type} move to convert"; return null }
            s.userSpecies = sp.id; s.baseMove = base.id; s.gmax = true
            s.note = "G-Max ${sp.id} using ${base.id}"
        } else if (m.id == "maxguard") {
            s.baseMove = "splash"; s.note = "status move becomes Max Guard"; s.foeMoves = listOf("tackle")
            s.exempt["volatile:maxguard"] = "transient"
            s.extra = { c -> listOf(chk("maxGuardBlocks", c.log.count { it.contains("protected itself") } >= 2 && c.user.hp == c.user.maxHp, "'protected itself' printed ${c.log.count { it.contains("protected itself") }} times, user HP ${c.user.hp}/${c.user.maxHp}")) }
        } else {
            val base = simpleDamaging[m.type] ?: run { rec.failures += "no plain damaging ${m.type} move to convert"; return null }
            s.baseMove = base.id; s.note = "base move ${base.id}"
        }
        if (m.id != "maxguard") s.extra = { c -> listOf(chk("usedMaxMove", c.log.any { it.contains("used ${m.name}!") }, "log has 'used ${m.name}!'")) }
        return s
    }

    // ------------------------------------------------------------------ the audit

    private val results: List<MoveRecord> by lazy {
        val coverage = BattleCoverage.movesDetailed(dex)
        dex.moves.values.sortedBy { it.id }.map { m -> audit(m, coverage.getValue(m.id)) }
    }

    private fun debugPrint() {
        val ids = System.getenv("DEBUG_MOVES")?.split(',')?.filter { it.isNotBlank() } ?: return
        for (id in ids) {
            val m = dex.move(id)
            val s = setupFor(m)
            val r = results.first { it.id == id }
            println("DEBUG $id -> ${r.classification}\n  repro: ${r.repro}\n  failures: ${r.failures}\n  evidence: ${r.evidence}")
            try {
                val run = runOnce(m, s, 1000)
                println("  full log: ${run.fullLog.joinToString(" | ")}\n  log: ${run.log.joinToString(" | ")}\n  diff: ${run.before.diff(run.after)}\n  checks: ${run.checks.map { "${it.name}=${it.kind}(${it.text})" }}")
            } catch (e: Throwable) { println("  threw $e") }
        }
    }

    @Test
    fun `every move is executed and classified exactly once`() {
        val recs = results
        assertEquals(dex.moves.keys, recs.map { it.id }.toSet())
        assertEquals(dex.moves.size, recs.size)
        val valid = setOf("implemented-and-tested", "data-driven-and-tested", "no-battle-effect", "unsupported-or-incomplete")
        for (r in recs) assertTrue(r.classification in valid, "${r.id}: ${r.classification}")
        writeReport(recs)
        println("Move execution: " + recs.groupingBy { it.classification }.eachCount().toSortedMap())
        for (r in recs.filter { it.classification == "unsupported-or-incomplete" }) println("  UNSUPPORTED ${r.id} [${r.coverage}] ${r.repro}\n      ${r.failures.joinToString("\n      ")}")
        debugPrint()
        val unsupported = recs.filter { it.classification == "unsupported-or-incomplete" }
        assertTrue(unsupported.isEmpty(), "moves that threw, broke an invariant or whose expected effect was not observed: " + unsupported.joinToString { "${it.id} (${it.failures.firstOrNull()})" })
    }

    @Test
    fun `Pressure makes targeted moves cost two PP`() {
        val tackle = dex.move("tackle")
        val withPressure = Setup().apply { foeAbility = "pressure"; expectedPpCost = 2 }
        assertTrue(runOnce(tackle, withPressure, 1).violations.isEmpty())
        // The same battle with the default one-PP expectation must be reported as a violation.
        val wrong = Setup().apply { foeAbility = "pressure"; expectedPpCost = 1 }
        assertTrue(runOnce(tackle, wrong, 1).violations.any { it.startsWith("PP is") })
        // Self-targeted moves are not affected by Pressure.
        assertTrue(runOnce(dex.move("swordsdance"), Setup().apply { foeAbility = "pressure"; expectedPpCost = 1 }, 1).violations.isEmpty())
    }

    @Test
    fun `accuracy is honoured for a sample of moves`() {
        for (id in listOf("thunder", "sleeppowder", "tackle", "focusblast")) {
            val m = dex.move(id)
            val s = setupFor(m)
            var effects = 0
            val n = 400
            for (seed in 0 until n) if (runOnce(m, s, 20_000L + seed).events.any { it is BattleEvent.MoveEffect && it.moveId == id }) effects++
            val rate = effects * 100.0 / n
            assertTrue(abs(rate - m.accuracy) <= 8.0, "$id hit rate $rate% vs accuracy ${m.accuracy}%")
        }
    }

    @Test
    fun `priority is honoured against a much faster or slower foe`() {
        val fast = Setup().apply { userSpeedStage = -6; foeSpeedStage = 6 }
        val used = runOnce(dex.move("quickattack"), fast, 1).events.filterIsInstance<BattleEvent.MoveUsed>()
        assertEquals(0, used.first().side, "Quick Attack must go first despite -6 Speed")
        val slow = Setup().apply { userSpeedStage = 6; foeSpeedStage = -6; foeMoves = listOf("tackle") }
        val used2 = runOnce(dex.move("vitalthrow"), slow, 1).events.filterIsInstance<BattleEvent.MoveUsed>()
        assertEquals(1, used2.first().side, "Vital Throw (priority -1) must go last despite +6 Speed")
    }

    private fun writeReport(recs: List<MoveRecord>) {
        val file = File(System.getProperty("pkmn.moveReport") ?: "build/reports/move-execution.json")
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("generatedBy", "engine/src/test/kotlin/pkmn/engine/battle/MoveExecutionTest.kt")
            put("scenario", "Mew L100 vs Snorlax L100 (Vaporeon for Ghost moves), inert abilities, seeds 1000+, up to $SEEDS seeds per move; Z/Max moves through the gimmick path")
            put("moves", JsonArray(recs.map { r ->
                buildJsonObject {
                    put("id", r.id); put("name", r.name); put("coverage", r.coverage); put("coverageReason", r.coverageReason)
                    put("classification", r.classification); put("path", r.path); put("seeds", r.seeds)
                    put("checks", JsonArray(r.checks.map { JsonPrimitive(it) }))
                    put("evidence", JsonArray(r.evidence.map { JsonPrimitive(it) }))
                    put("failures", JsonArray(r.failures.map { JsonPrimitive(it) }))
                    put("repro", r.repro)
                }
            }))
        }
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json) + "\n")
    }
}
