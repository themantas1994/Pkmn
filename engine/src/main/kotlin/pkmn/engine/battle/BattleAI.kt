package pkmn.engine.battle

import kotlin.math.max
import kotlin.math.min

/**
 * Trainer and wild Pokémon decision making.
 *  - Wild: random usable move.
 *  - aiLevel 0: random usable move.
 *  - aiLevel 1: picks the strongest expected-damage move, uses obvious status moves.
 *  - aiLevel 2: also considers KOs, setup, hazards, recovery, switching out of bad matchups and gimmick timing.
 *  - aiLevel 3 (bosses): level 2 with less randomness and smarter replacements.
 */
open class BattleAI {

    /** Decision for [side]'s Pokémon in slot 0 (the only one in singles). */
    open fun chooseAction(b: Battle, side: Side): Choice = decide(b, side, 0)

    /** Decision for [side]'s Pokémon in [slot]. Slot 0 goes through [chooseAction] so subclasses overriding it still apply. */
    open fun chooseAction(b: Battle, side: Side, slot: Int): Choice = if (slot == 0) chooseAction(b, side) else decide(b, side, slot)

    protected fun decide(b: Battle, side: Side, slot: Int): Choice {
        val me = side.actives.getOrNull(slot) ?: return Choice.Pass
        val req = b.request(side, slot) as? Request.Move ?: return Choice.Pass
        if (req.lockedMove != null) return Choice.UseMove(0)
        val usable = me.moveSlots.indices.filter { req.usableMoves[it] }
        if (usable.isEmpty()) return Choice.UseMove(0)
        if (side.isWild || side.aiLevel <= 0) return Choice.UseMove(usable[b.rng.nextInt(usable.size)])
        val foe = b.opposingActive(side) ?: return Choice.UseMove(usable.first())

        // Consider switching out of a losing matchup.
        if (side.aiLevel >= 2 && req.canSwitch && b.rng.nextInt(100) < (if (side.aiLevel >= 3) 70 else 45)) {
            val myBest = usable.maxOf { scoreMove(b, me, foe, me.moveSlots[it].id, side.aiLevel) }
            val threat = threatTo(b, foe, me)
            if (myBest < 25 && threat >= 0.55) {
                val alt = bestSwitch(b, side, foe)
                if (alt != null && threatTo(b, foe, alt) < threat * 0.6) return Choice.Switch(side.mons.indexOf(alt))
            }
        }

        val scored = usable.map { it to scoreMove(b, me, foe, me.moveSlots[it].id, side.aiLevel) }
        val noise = if (side.aiLevel >= 3) 5.0 else if (side.aiLevel == 2) 12.0 else 25.0
        val pick = scored.maxByOrNull { it.second + b.rng.nextDouble() * noise }!!.first
        val gimmick = chooseGimmick(b, side, me, foe, pick, req.gimmicks)
        // Doubles: aim a foe-targeting move at the foe it was scored against; other moves keep their default target.
        val moveTarget = b.dex.moveOrNull(me.moveSlots[pick].id)?.target
        val aimAtFoe = b.slotsPerSide > 1 && moveTarget !in Battle.FIXED_TARGETS && moveTarget !in Battle.ALLY_ONLY_TARGETS
        return Choice.UseMove(pick, gimmick, target = if (aimAtFoe) foe.slot + 1 else 0)
    }

    /** Approximate fraction of [defender]'s HP that [attacker]'s best damaging move removes. */
    fun threatTo(b: Battle, attacker: BattleMon, defender: BattleMon): Double {
        var best = 0.0
        for (s in attacker.moveSlots) {
            val m = b.dex.moveOrNull(s.id) ?: continue
            if (m.isStatus || s.pp <= 0) continue
            best = max(best, expectedDamage(b, attacker, defender, s.id).toDouble() / max(1, defender.hp))
        }
        return best
    }

    fun expectedDamage(b: Battle, attacker: BattleMon, defender: BattleMon, moveId: String): Int {
        val m = b.dex.moveOrNull(moveId) ?: return 0
        if (m.isStatus) return 0
        val mc = MoveCtx(attacker, defender, ActiveMove(m))
        val dmg = try {
            b.previewing = true
            b.calcDamage(mc, forPreview = true) ?: 0
        } catch (e: Exception) { 0 } finally { b.previewing = false }
        val hits = m.multihit?.let { (it[0] + it[1]) / 2 } ?: 1
        val acc = if (m.accuracy <= 0) 1.0 else m.accuracy / 100.0
        return (dmg * hits * acc).toInt()
    }

    /** Heuristic value of using [moveId] now, roughly "percent of foe HP" scale. */
    open fun scoreMove(b: Battle, me: BattleMon, foe: BattleMon, moveId: String, level: Int): Double {
        val m = b.dex.moveOrNull(moveId) ?: return 0.0
        if (!m.isStatus) {
            val dmg = expectedDamage(b, me, foe, moveId)
            var score = 100.0 * min(dmg, foe.hp) / max(1, foe.maxHp)
            if (dmg >= foe.hp) score += 60 + (if (m.priority > 0) 30 else 0)
            if (m.recoil != null || m.self?.boosts?.values?.any { it < 0 } == true) score -= 5
            if (m.hasFlag("charge") && level >= 2) score *= 0.6
            if (m.hasFlag("recharge")) score *= 0.75
            if (m.selfdestruct != null) score = if (me.hpFraction < 0.25) score else score * 0.2
            return score
        }
        if (level <= 0) return 10.0
        val foeStatused = foe.status != null
        return when {
            m.status != null -> when {
                foeStatused || foe.hasVolatile("substitute") -> -20.0
                m.status == "slp" && foe.side.alive().size > 0 -> 55.0
                m.status == "par" && b.getSpeed(foe) > b.getSpeed(me) -> 45.0
                m.status == "brn" && foe.rawStat(pkmn.engine.data.Stat.ATK) > foe.rawStat(pkmn.engine.data.Stat.SPA) -> 45.0
                m.status == "tox" || m.status == "psn" -> 35.0
                else -> 25.0
            }
            m.heal != null -> if (me.hpFraction < 0.45) 70.0 else if (me.hpFraction < 0.7) 25.0 else -30.0
            m.boosts.isNotEmpty() && m.target == "self" || m.self?.boosts?.isNotEmpty() == true && m.isStatus -> {
                val boosts = if (m.target == "self") m.boosts else m.self!!.boosts
                val total = boosts.values.sum()
                val current = boosts.keys.sumOf { me.boost(it) }
                if (current >= 2 || me.hpFraction < 0.5 || threatTo(b, foe, me) > 0.6) -10.0 else 20.0 + total * 10
            }
            m.boosts.isNotEmpty() -> {
                val current = m.boosts.keys.sumOf { foe.boost(it) }
                if (current <= -2) -10.0 else 15.0
            }
            m.sideCondition in setOf("stealthrock", "spikes", "toxicspikes", "stickyweb") ->
                if (foe.side.hasCondition(m.sideCondition!!) || foe.side.alive().size <= 2) -10.0 else 35.0
            m.sideCondition in setOf("reflect", "lightscreen", "auroraveil", "tailwind", "safeguard") ->
                if (me.side.hasCondition(m.sideCondition!!)) -20.0 else 25.0
            m.volatileStatus == "substitute" -> if (me.hasVolatile("substitute") || me.hpFraction < 0.4) -20.0 else 20.0
            m.volatileStatus == "protect" || m.stallingMove -> if (me.hasVolatile("stall")) -30.0 else 8.0
            m.volatileStatus in setOf("confusion", "attract", "taunt", "encore", "leechseed") -> if (foe.hasVolatile(m.volatileStatus!!)) -20.0 else 25.0
            m.weather != null -> if (b.weather() == b.dex.moveOrNull(moveId)?.weather?.lowercase()) -20.0 else 20.0
            m.terrain != null -> if (b.field.terrain == m.terrain) -20.0 else 20.0
            else -> 10.0
        }
    }

    open fun chooseGimmick(b: Battle, side: Side, me: BattleMon, foe: BattleMon, moveIndex: Int, available: Set<Gimmick>): Gimmick? {
        if (available.isEmpty() || side.aiLevel <= 0) return null
        val isAce = me === side.mons.maxByOrNull { it.level } || side.alive().size == 1
        if (Gimmick.ULTRA_BURST in available) return Gimmick.ULTRA_BURST
        if (Gimmick.MEGA in available) return Gimmick.MEGA
        if (Gimmick.ZMOVE in available && side.aiLevel >= 1) {
            val base = b.dex.move(me.moveSlots[moveIndex].id)
            val z = Gimmicks.zMoveFor(b, me, base)
            if (z != null && !z.isStatus && (isAce || expectedDamage(b, me, foe, base.id) * 1.6 >= foe.hp)) return Gimmick.ZMOVE
        }
        if (Gimmick.DYNAMAX in available && isAce) return Gimmick.DYNAMAX
        if (Gimmick.TERA in available && (isAce || me.hpFraction < 0.5)) return Gimmick.TERA
        return null
    }

    fun bestSwitch(b: Battle, side: Side, foe: BattleMon): BattleMon? =
        side.canSwitchTo().minByOrNull { threatTo(b, foe, it) - 0.5 * threatTo(b, it, foe) }

    open fun chooseReplacementMon(b: Battle, side: Side): BattleMon {
        val options = side.canSwitchTo()
        val foe = b.opposingActive(side)
        if (foe == null || side.aiLevel <= 0 || side.isWild) return options.first()
        // Keep the strongest Pokémon for last when the trainer has several left (classic "ace" behaviour).
        val ace = if (side.aiLevel >= 2 && options.size > 1) options.maxByOrNull { it.level * 10 + it.mon.moves.size } else null
        val pool = if (ace != null) options - ace else options
        return pool.maxByOrNull { threatTo(b, it, foe) - threatTo(b, foe, it) * 0.7 + b.rng.nextDouble() * 0.05 } ?: options.first()
    }

    fun chooseReplacement(b: Battle, side: Side): Int = side.mons.indexOf(chooseReplacementMon(b, side))
}
