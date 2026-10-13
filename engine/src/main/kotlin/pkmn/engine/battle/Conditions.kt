package pkmn.engine.battle

import pkmn.engine.data.Stat
import pkmn.engine.mon.MajorStatus
import kotlin.math.max

/** Status conditions, volatile conditions, side/slot conditions, weather, terrain and pseudo-weather. */
object Conditions {

    private fun reg(e: Effect) = Effects.registerCondition(e)

    private fun Battle.hasMagicGuard(m: BattleMon) = m.ability == "magicguard" && abilityActive(m)

    fun registerAll() {
        registerStatuses()
        registerVolatiles()
        registerProtection()
        registerSideConditions()
        registerWeather()
        registerTerrain()
        registerPseudoWeather()
    }

    // ================================================================= major status

    private fun registerStatuses() {
        reg(object : Effect("brn", EffectKind.STATUS) {
            override val residualOrder = 10
            override fun onResidual(c: EffectCtx) {
                val m = c.mon
                val dmg = max(1, m.baseMaxHp / 16)
                if (c.b.directDamage(m, dmg, null, "brn") > 0) c.b.message("${c.b.nm(m)} was hurt by its burn!")
            }
        })
        reg(object : Effect("par", EffectKind.STATUS) {
            override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) {
                if (c.holder === mon && !(mon.ability == "quickfeet" && c.b.abilityActive(mon))) mod.chain(0.5)
            }
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                if (c.b.rng.nextInt(4) == 0) {
                    c.b.message("${c.b.nm(c.mon)} is paralyzed! It can't move!")
                    return false
                }
                return true
            }
        })
        reg(object : Effect("psn", EffectKind.STATUS) {
            override val residualOrder = 9
            override fun onResidual(c: EffectCtx) {
                val m = c.mon
                if (c.b.directDamage(m, max(1, m.baseMaxHp / 8), null, "psn") > 0) c.b.message("${c.b.nm(m)} was hurt by poison!")
            }
        })
        reg(object : Effect("tox", EffectKind.STATUS) {
            override val residualOrder = 9
            override fun onResidual(c: EffectCtx) {
                val m = c.mon
                c.state.counter = (c.state.counter + 1).coerceAtMost(15)
                if (c.b.directDamage(m, max(1, m.baseMaxHp * c.state.counter / 16), null, "psn") > 0) c.b.message("${c.b.nm(m)} was hurt by poison!")
            }
        })
        reg(object : Effect("slp", EffectKind.STATUS) {
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                val m = c.mon
                val dec = if (m.ability == "earlybird" && c.b.abilityActive(m)) 2 else 1
                m.mon.sleepTurns -= dec
                if (m.mon.sleepTurns <= 0) {
                    c.b.cureStatus(m)
                    return true
                }
                c.b.message("${c.b.nm(m)} is fast asleep.")
                return mc.move.data.sleepUsable
            }
        })
        reg(object : Effect("frz", EffectKind.STATUS) {
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                val m = c.mon
                if (mc.move.hasFlag("defrost") || c.b.rng.nextInt(5) == 0) {
                    c.b.cureStatus(m)
                    return true
                }
                c.b.message("${c.b.nm(m)} is frozen solid!")
                return false
            }
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && (mc.move.type == "Fire" && mc.move.category != "Status" || mc.move.data.thawsTarget)) c.b.cureStatus(c.mon)
            }
        })
    }

    // ================================================================= volatiles

    private fun registerVolatiles() {
        reg(object : Effect("confusion", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                c.state.counter = 2 + c.b.rng.nextInt(4)
                c.b.message(if (c.state.moveId == "lockedmove") "${c.b.nm(c.mon)} became confused due to fatigue!" else "${c.b.nm(c.mon)} became confused!")
            }
            override fun onEnd(c: EffectCtx) { c.b.message("${c.b.nm(c.mon)} snapped out of its confusion!") }
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                val m = c.mon
                c.state.counter--
                if (c.state.counter <= 0) { c.b.removeVolatile(m, "confusion"); return true }
                c.b.message("${c.b.nm(m)} is confused!")
                if (c.b.rng.nextInt(3) != 0) return true
                // 40 BP typeless physical self-hit.
                val atk = c.b.applyBoost(m.rawStat(Stat.ATK), m.boost("atk"))
                val def = c.b.applyBoost(m.rawStat(Stat.DEF), m.boost("def"))
                var dmg = ((2 * m.level / 5 + 2) * 40 * atk / max(1, def)) / 50 + 2
                dmg = dmg * (85 + c.b.rng.nextInt(16)) / 100
                c.b.message("It hurt itself in its confusion!")
                c.b.directDamage(m, max(1, dmg), m, "confusion")
                return false
            }
        })
        reg(object : Effect("flinch", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.mon.movedThisTurn || c.mon.isDynamaxed) c.mon.volatiles.remove("flinch")
            }
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                c.b.message("${c.b.nm(c.mon)} flinched and couldn't move!")
                if (c.mon.ability == "steadfast" && c.b.abilityActive(c.mon)) c.b.boost(c.mon, mapOf("spe" to 1), c.mon, "steadfast")
                return false
            }
        })
        reg(object : Effect("attract", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val m = c.mon
                val ok = source != null && m.mon.gender != pkmn.engine.mon.Gender.NONE && source.mon.gender != pkmn.engine.mon.Gender.NONE && m.mon.gender != source.mon.gender
                if (!ok) { m.volatiles.remove("attract"); c.b.message("But it failed!"); return }
                c.b.message("${c.b.nm(m)} fell in love!")
                if (m.item == "destinyknot" && c.b.itemActive(m) && source != null) c.b.addVolatile(source, "attract", m, "destinyknot")
            }
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                val src = c.state.source
                if (src == null || !src.isActive) { c.b.removeVolatile(c.mon, "attract"); return true }
                c.b.message("${c.b.nm(c.mon)} is in love with ${c.b.nmL(src)}!")
                if (c.b.rng.nextBoolean()) { c.b.message("${c.b.nm(c.mon)} is immobilized by love!"); return false }
                return true
            }
        })
        reg(object : Effect("leechseed", EffectKind.VOLATILE) {
            override val residualOrder = 8
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.mon.hasType("Grass")) { c.mon.volatiles.remove("leechseed"); c.b.message("It doesn't affect ${c.b.nmL(c.mon)}..."); return }
                c.b.message("${c.b.nm(c.mon)} was seeded!")
            }
            override fun onResidual(c: EffectCtx) {
                val m = c.mon
                val receiver = m.side.foe.active ?: return
                if (receiver.fainted) return
                val dmg = c.b.directDamage(m, max(1, m.baseMaxHp / 8), receiver, "leechseed")
                if (dmg > 0) {
                    c.b.message("${c.b.nm(m)}'s health is sapped by Leech Seed!")
                    if (m.ability == "liquidooze" && c.b.abilityActive(m)) c.b.directDamage(receiver, dmg, m, "liquidooze")
                    else c.b.heal(receiver, if (receiver.item == "bigroot") dmg * 13 / 10 else dmg, "leechseed")
                }
            }
        })
        reg(object : Effect("curse", EffectKind.VOLATILE) {
            override val residualOrder = 12
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${source?.let { c.b.nm(it) } ?: "It"} cut its own HP and put a curse on ${c.b.nmL(c.mon)}!") }
            override fun onResidual(c: EffectCtx) {
                if (c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / 4), null, "curse") > 0) c.b.message("${c.b.nm(c.mon)} is afflicted by the curse!")
            }
        })
        reg(object : Effect("nightmare", EffectKind.VOLATILE) {
            override val residualOrder = 11
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.mon.status != MajorStatus.SLEEP) { c.mon.volatiles.remove("nightmare"); c.b.message("But it failed!"); return }
                c.b.message("${c.b.nm(c.mon)} began having a nightmare!")
            }
            override fun onResidual(c: EffectCtx) {
                if (c.mon.status != MajorStatus.SLEEP) { c.b.removeVolatile(c.mon, "nightmare"); return }
                if (c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / 4), null, "nightmare") > 0) c.b.message("${c.b.nm(c.mon)} is locked in a nightmare!")
            }
        })
        reg(object : Effect("partiallytrapped", EffectKind.VOLATILE) {
            override val residualOrder = 13
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) =
                if (source?.item == "gripclaw") 8 else 5 + b.rng.nextInt(2)
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                c.b.message("${c.b.nm(c.mon)} was trapped!")
                c.state.data["bandmult"] = source?.item == "bindingband"
            }
            override fun onResidual(c: EffectCtx) {
                val src = c.state.source
                if (src == null || !src.isActive || src.fainted) { c.b.removeVolatile(c.mon, "partiallytrapped"); return }
                val frac = if (c.state.data["bandmult"] == true) 6 else 8
                if (c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / frac), src, "partiallytrapped") > 0)
                    c.b.message("${c.b.nm(c.mon)} is hurt by ${c.b.dex.moveOrNull(c.state.moveId)?.name ?: "the trap"}!")
            }
            override fun onEnd(c: EffectCtx) { if (!c.mon.fainted) c.b.message("${c.b.nm(c.mon)} was freed from ${c.b.dex.moveOrNull(c.state.moveId)?.name ?: "the trap"}!") }
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = c.holder === mon && c.state.source?.isActive == true
        })
        reg(object : Effect("substitute", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val m = c.mon
                val cost = m.baseMaxHp / 4
                if (m.hp <= cost || cost == 0) {
                    m.volatiles.remove("substitute")
                    c.b.message(if (m.hp <= cost) "But it does not have enough HP left to make a substitute!" else "But it failed!")
                    return
                }
                c.b.directDamage(m, cost, m, "substitute")
                c.state.hp = cost
                c.b.message("${c.b.nm(m)} put in a substitute!")
                m.volatiles.remove("partiallytrapped")
            }
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean { c.b.message("${c.b.nm(c.mon)} already has a substitute!"); return false }
        })
        reg(object : Effect("taunt", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = if (target?.movedThisTurn == true) 4 else 3
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} fell for the taunt!") }
            override fun onEnd(c: EffectCtx) { c.b.message("${c.b.nm(c.mon)} shook off the taunt!") }
            override fun onDisableMove(c: EffectCtx, moveId: String) = c.b.dex.moveOrNull(moveId)?.isStatus == true
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                if (mc.move.isStatus && !mc.move.isZ && !mc.move.isMax) { c.b.message("${c.b.nm(c.mon)} can't use ${mc.move.name} after the taunt!"); return false }
                return true
            }
        })
        reg(object : Effect("encore", EffectKind.VOLATILE) {
            override val residualOrder = 16
            // 3 turns; one more when the target has already moved this turn (Showdown: duration++ unless willMove).
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = if (target?.movedThisTurn == true) 4 else 3
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val last = c.mon.lastMove
                val data = c.b.dex.moveOrNull(last)
                if (last == null || data == null || c.mon.isDynamaxed || data.hasFlag("failencore") || data.isZ != null || data.isMax != null ||
                    (c.mon.moveSlot(last)?.pp ?: 0) <= 0) {
                    c.mon.volatiles.remove("encore"); return
                }
                c.state.moveId = last
                c.b.message("${c.b.nm(c.mon)} must do an encore!")
            }
            override fun onResidual(c: EffectCtx) {
                if ((c.mon.moveSlot(c.state.moveId ?: "")?.pp ?: 0) <= 0) c.b.removeVolatile(c.mon, "encore")
            }
            override fun onEnd(c: EffectCtx) { c.b.message("${c.b.nm(c.mon)}'s encore ended!") }
            override fun onDisableMove(c: EffectCtx, moveId: String) = c.mon.moveSlot(c.state.moveId ?: "") != null && moveId != c.state.moveId
        })
        reg(object : Effect("disable", EffectKind.VOLATILE) {
            // 4 turns of prevented use: Showdown's duration 5, minus one if the target has yet to move this turn.
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = if (target?.movedThisTurn == true) 5 else 4
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val last = c.mon.lastMove
                val data = c.b.dex.moveOrNull(last)
                if (last == null || last == "struggle" || c.mon.isDynamaxed || data?.isZ != null || data?.isMax != null || (c.mon.moveSlot(last)?.pp ?: 0) <= 0) {
                    c.mon.volatiles.remove("disable"); return
                }
                c.state.moveId = last
                c.b.message("${c.b.nm(c.mon)}'s ${c.b.dex.moveOrNull(last)?.name} was disabled!")
            }
            override fun onEnd(c: EffectCtx) { c.b.message("${c.b.nm(c.mon)}'s move is no longer disabled!") }
            override fun onDisableMove(c: EffectCtx, moveId: String) = moveId == c.state.moveId
        })
        reg(object : Effect("torment", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.mon.isDynamaxed) { c.mon.volatiles.remove("torment"); return }
                c.b.message("${c.b.nm(c.mon)} was subjected to torment!")
            }
            override fun onDisableMove(c: EffectCtx, moveId: String) = moveId == c.mon.lastMove && moveId != "struggle"
        })
        reg(object : Effect("healblock", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} was prevented from healing!") }
            override fun onEnd(c: EffectCtx) { c.b.message("${c.b.nm(c.mon)}'s Heal Block wore off!") }
            override fun onDisableMove(c: EffectCtx, moveId: String) = c.b.dex.moveOrNull(moveId)?.hasFlag("heal") == true
            override fun onTryHeal(c: EffectCtx, target: BattleMon, amount: Int, sourceEffect: String) = if (target === c.holder) 0 else amount
        })
        reg(object : Effect("embargo", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} can't use items anymore!") }
            override fun onEnd(c: EffectCtx) { c.b.message("${c.b.nm(c.mon)} can use items again!") }
        })
        reg(object : Effect("yawn", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.mon.status != null) { c.mon.volatiles.remove("yawn"); c.b.message("But it failed!"); return }
                c.b.message("${c.b.nm(c.mon)} grew drowsy!")
            }
            override fun onEnd(c: EffectCtx) { if (!c.mon.fainted) c.b.trySetStatus(c.mon, MajorStatus.SLEEP, c.state.source, "yawn", primary = false) }
        })
        reg(object : Effect("perishsong", EffectKind.VOLATILE) {
            override val residualOrder = 24
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.state.counter = 4 }
            override fun onResidual(c: EffectCtx) {
                c.state.counter--
                c.b.message("${c.b.nm(c.mon)}'s perish count fell to ${c.state.counter}.")
                if (c.state.counter <= 0) c.b.directDamage(c.mon, c.mon.hp, null, "perishsong")
            }
        })
        reg(object : Effect("focusenergy", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} is getting pumped!") }
            override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc)) ratio + 2 else ratio
        })
        reg(object : Effect("dragoncheer", EffectKind.VOLATILE) {
            override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc)) ratio + if (c.mon.hasType("Dragon")) 2 else 1 else ratio
        })
        reg(object : Effect("laserfocus", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} concentrated intensely!") }
            override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc)) 5 else ratio
        })
        reg(object : Effect("aquaring", EffectKind.VOLATILE) {
            override val residualOrder = 6
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} surrounded itself with a veil of water!") }
            override fun onResidual(c: EffectCtx) {
                val amt = max(1, c.mon.baseMaxHp / 16).let { if (c.mon.item == "bigroot") it * 13 / 10 else it }
                if (c.b.heal(c.mon, amt, "aquaring") > 0) c.b.message("A veil of water restored ${c.b.nmL(c.mon)}'s HP!")
            }
        })
        reg(object : Effect("ingrain", EffectKind.VOLATILE) {
            override val residualOrder = 7
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} planted its roots!") }
            override fun onResidual(c: EffectCtx) {
                val amt = max(1, c.mon.baseMaxHp / 16).let { if (c.mon.item == "bigroot") it * 13 / 10 else it }
                if (c.b.heal(c.mon, amt, "ingrain") > 0) c.b.message("${c.b.nm(c.mon)} absorbed nutrients with its roots!")
            }
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = c.holder === mon
            override fun onDragOut(c: EffectCtx, mon: BattleMon) = c.holder !== mon
        })
        reg(object : Effect("magnetrise", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.b.field.has("gravity") || c.mon.hasVolatile("ingrain") || c.mon.hasVolatile("smackdown")) { c.mon.volatiles.remove("magnetrise"); c.b.message("But it failed!"); return }
                c.b.message("${c.b.nm(c.mon)} levitated with electromagnetism!")
            }
            override fun onEnd(c: EffectCtx) { c.b.message("${c.b.nm(c.mon)}'s electromagnetism wore off!") }
        })
        reg(object : Effect("telekinesis", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 3
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} was hurled into the air!") }
            override fun onAccuracyOverride(c: EffectCtx, mc: MoveCtx): Boolean? = if (c.isTarget(mc) && mc.move.data.ohko == null) true else null
        })
        reg(object : Effect("smackdown", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val m = c.mon
                val wasAirborne = m.hasType("Flying") || m.ability == "levitate" || m.hasVolatile("magnetrise") || m.hasVolatile("telekinesis")
                m.volatiles.remove("magnetrise"); m.volatiles.remove("telekinesis")
                val tt = m.volatiles["twoturnmove"]
                if (tt != null && tt.moveId in setOf("fly", "bounce", "skydrop")) m.volatiles.remove("twoturnmove")
                if (wasAirborne) c.b.message("${c.b.nm(m)} fell straight down!")
            }
        })
        reg(object : Effect("roost", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
        })
        reg(object : Effect("minimize", EffectKind.VOLATILE) {
            override fun onAccuracyOverride(c: EffectCtx, mc: MoveCtx): Boolean? = if (c.isTarget(mc) && mc.move.hasFlag("minimize")) true else null
            override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && mc.move.hasFlag("minimize")) mod.chain(2.0) }
        })
        reg(object : Effect("defensecurl", EffectKind.VOLATILE) {})
        reg(object : Effect("charge", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} began charging power!") }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (c.isUser(mc) && mc.move.type == "Electric") { mod.chain(2.0); if (!c.b.previewing) c.state.flag = true }
            }
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && c.state.flag) c.mon.volatiles.remove("charge") }
        })
        reg(object : Effect("destinybond", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} is hoping to take its attacker down with it!") }
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean { if (mc.move.id != "destinybond") c.mon.volatiles.remove("destinybond"); return true }
            override fun onFaint(c: EffectCtx, source: BattleMon?) {
                if (source != null && !source.fainted && source.side !== c.mon.side && !source.isDynamaxed) {
                    c.b.message("${c.b.nm(c.mon)} took its attacker down with it!")
                    c.b.directDamage(source, source.hp, c.mon, "destinybond")
                }
            }
        })
        reg(object : Effect("grudge", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} wants its target to bear a grudge!") }
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean { if (mc.move.id != "grudge") c.mon.volatiles.remove("grudge"); return true }
            override fun onFaint(c: EffectCtx, source: BattleMon?) {
                val mv = source?.lastMove ?: return
                source.moveSlot(mv)?.let { it.pp = 0; c.b.message("${c.b.nm(source)}'s ${c.b.dex.moveOrNull(mv)?.name} lost all its PP due to the grudge!") }
            }
        })
        reg(object : Effect("lockedmove", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2 + b.rng.nextInt(2)
            override fun onEnd(c: EffectCtx) {
                if (!c.mon.fainted && c.mon.isActive) c.b.addVolatile(c.mon, "confusion", c.mon, "lockedmove")
            }
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean = true
        })
        reg(object : Effect("mustrecharge", EffectKind.VOLATILE) {})
        reg(object : Effect("twoturnmove", EffectKind.VOLATILE) {
            override fun onAccuracyOverride(c: EffectCtx, mc: MoveCtx): Boolean? {
                if (!c.isTarget(mc)) return null
                val semi = c.state.moveId
                return when (semi) {
                    "fly", "bounce", "skydrop" -> if (mc.move.id in setOf("gust", "twister", "thunder", "hurricane", "skyuppercut", "smackdown", "thousandarrows")) null else false
                    "dig" -> if (mc.move.id in setOf("earthquake", "magnitude", "fissure")) null else false
                    "dive" -> if (mc.move.id in setOf("surf", "whirlpool")) null else false
                    "phantomforce", "shadowforce" -> false
                    else -> null
                }.let { r -> if (r == false && (mc.user.ability == "noguard" || c.mon.ability == "noguard")) true else r }
            }
            override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (!c.isTarget(mc)) return
                val semi = c.state.moveId
                if (semi == "dig" && mc.move.id in setOf("earthquake", "magnitude")) mod.chain(2.0)
                if (semi == "dive" && mc.move.id in setOf("surf", "whirlpool")) mod.chain(2.0)
                if (semi in setOf("fly", "bounce") && mc.move.id in setOf("gust", "twister")) mod.chain(2.0)
            }
        })
        reg(object : Effect("stall", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean { c.state.counter++; c.state.duration = 2; return true }
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.state.counter = 1 }
        })
        reg(object : Effect("foresight", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} was identified!") }
        })
        reg(object : Effect("miracleeye", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} was identified!") }
        })
        reg(object : Effect("gastroacid", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (!c.b.canChangeAbility(c.mon)) { c.mon.volatiles.remove("gastroacid"); return }
                c.b.message("${c.b.nm(c.mon)}'s Ability was suppressed!")
            }
        })
        reg(object : Effect("magiccoat", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} shrouded itself with Magic Coat!") }
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTarget(mc) || !c.b.reflects(mc, c.mon)) return true
                if (!c.b.previewing) c.b.reflectMove(mc, c.mon)
                return false
            }
        })
        reg(object : Effect("beakblast", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.user.side !== c.mon.side && mc.move.hasFlag("contact") && !(mc.user.item == "protectivepads" && c.b.itemActive(mc.user)))
                    c.b.trySetStatus(mc.user, MajorStatus.BURN, c.mon, "beakblast", primary = false)
            }
        })
        reg(object : Effect("shelltrap", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.user.side !== c.mon.side && mc.move.category == "Physical") c.state.flag = true
            }
        })
        reg(object : Effect("electrify", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)}'s moves have been electrified!") }
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && mc.move.id != "struggle") mc.move.type = "Electric" }
        })
        reg(object : Effect("rage", EffectKind.VOLATILE) {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.user !== c.mon && !c.mon.fainted) { c.b.message("${c.b.nm(c.mon)}'s rage is building!"); c.b.boost(c.mon, mapOf("atk" to 1), c.mon, "rage") }
            }
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean { if (mc.move.id != "rage") c.mon.volatiles.remove("rage"); return true }
        })
        reg(object : Effect("bide", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} is storing energy!") }
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean): Int {
                if (target === c.holder && isMove && source != null && source !== target) c.state.hp += amount
                return amount
            }
        })
        reg(object : Effect("stockpile", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                c.state.counter = 1
                c.b.message("${c.b.nm(c.mon)} stockpiled 1!")
                c.b.boost(c.mon, mapOf("def" to 1, "spd" to 1), c.mon, "stockpile")
            }
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean {
                if (c.state.counter >= 3) return false
                c.state.counter++
                c.b.message("${c.b.nm(c.mon)} stockpiled ${c.state.counter}!")
                c.b.boost(c.mon, mapOf("def" to 1, "spd" to 1), c.mon, "stockpile")
                return true
            }
            override fun onEnd(c: EffectCtx) {
                c.b.boost(c.mon, mapOf("def" to -c.state.counter, "spd" to -c.state.counter), c.mon, "stockpile", secondary = true)
            }
        })
        reg(object : Effect("powertrick", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} switched its Attack and Defense!") }
        })
        reg(object : Effect("powershift", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} switched its offensive and defensive stats!") }
        })
        reg(object : Effect("noretreat", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} can no longer escape because it used No Retreat!") }
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = c.holder === mon
        })
        reg(object : Effect("octolock", EffectKind.VOLATILE) {
            override val residualOrder = 14
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} can no longer escape because of Octolock!") }
            override fun onResidual(c: EffectCtx) {
                val src = c.state.source
                if (src == null || !src.isActive) { c.b.removeVolatile(c.mon, "octolock"); return }
                c.b.boost(c.mon, mapOf("def" to -1, "spd" to -1), src, "octolock", secondary = true)
            }
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = c.holder === mon && c.state.source?.isActive == true
        })
        reg(object : Effect("saltcure", EffectKind.VOLATILE) {
            override val residualOrder = 13
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} is being salt cured!") }
            override fun onResidual(c: EffectCtx) {
                val frac = if (c.mon.hasType("Water") || c.mon.hasType("Steel")) 4 else 8
                if (c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / frac), c.state.source, "saltcure") > 0) c.b.message("${c.b.nm(c.mon)} is hurt by Salt Cure!")
            }
        })
        reg(object : Effect("tarshot", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.mon.terastallized != null) { c.mon.volatiles.remove("tarshot"); return }
                c.b.message("${c.b.nm(c.mon)} became weaker to fire!")
            }
            override fun onEffectiveness(c: EffectCtx, mc: MoveCtx, type: String, multiplier: Double): Double =
                if (c.isTarget(mc) && mc.move.type == "Fire" && type == c.mon.effectiveTypes().first()) multiplier * 2 else multiplier
        })
        reg(object : Effect("syrupbomb", EffectKind.VOLATILE) {
            override val residualOrder = 14
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 4
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} got covered in sticky candy syrup!") }
            override fun onResidual(c: EffectCtx) {
                if (c.state.source?.isActive != true) { c.b.removeVolatile(c.mon, "syrupbomb"); return }
                c.b.boost(c.mon, mapOf("spe" to -1), c.state.source, "syrupbomb", secondary = true)
            }
        })
        reg(object : Effect("glaiverush", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2
            override fun onAccuracyOverride(c: EffectCtx, mc: MoveCtx): Boolean? = if (c.isTarget(mc)) true else null
            override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc)) mod.chain(2.0) }
        })
        reg(object : Effect("throatchop", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 3
            override fun onDisableMove(c: EffectCtx, moveId: String) = c.b.dex.moveOrNull(moveId)?.hasFlag("sound") == true
        })
        reg(object : Effect("uproar", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 3
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                c.b.message("${c.b.nm(c.mon)} caused an uproar!")
                for (m in c.b.activeMons()) if (m.status == MajorStatus.SLEEP) c.b.cureStatus(m)
            }
            override fun onEnd(c: EffectCtx) { c.b.message("${c.b.nm(c.mon)} calmed down.") }
        })
        reg(object : Effect("imprison", EffectKind.VOLATILE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} sealed any moves its target shares with it!") }
        })
        reg(object : Effect("powder", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                if (mc.move.type != "Fire") return true
                c.b.message("When the flame touched the powder on the Pokémon, it exploded!")
                c.b.directDamage(c.mon, max(1, Mod.pokeRound(c.mon.baseMaxHp / 4.0)), null, "powder")
                return false
            }
        })
        reg(object : Effect("sparklingaria", EffectKind.VOLATILE) {
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) { c.mon.volatiles.remove("sparklingaria") }
        })
        reg(object : Effect("flashfire", EffectKind.VOLATILE) {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.type == "Fire" && c.b.abilityActive(c.mon)) mod.chain(1.5) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.type == "Fire" && c.b.abilityActive(c.mon)) mod.chain(1.5) }
        })
        reg(object : Effect("trapped", EffectKind.VOLATILE) {
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = c.holder === mon && c.state.source?.isActive == true
        })
        reg(object : Effect("lockon", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2
            override fun onAccuracyOverride(c: EffectCtx, mc: MoveCtx): Boolean? = if (c.isUser(mc)) true else null
        })
        reg(object : Effect("micleberry", EffectKind.VOLATILE) {
            override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc)) mod.chain(1.2) }
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc)) c.mon.volatiles.remove("micleberry") }
        })
        // Marker volatiles whose behaviour lives in the engine or in move scripts (they carry state only).
        for (id in listOf("ateberry", "autotomize", "furycutter", "rollout", "pendingmove", "split_atk", "split_spa", "split_def", "split_spd")) {
            if (Effects.condition(id) == null) reg(object : Effect(id, EffectKind.VOLATILE) {})
        }
    }

    // ================================================================= protection

    /** Protect-like volatiles: block moves with the "protect" flag; some punish contact. */
    private open class ProtectEffect(id: String, private val maxGuard: Boolean = false) : Effect(id, EffectKind.VOLATILE) {
        override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
        override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} protected itself!") }
        open fun blocks(mc: MoveCtx) = mc.move.hasFlag("protect")
        open fun punish(c: EffectCtx, mc: MoveCtx) {}
        override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
            if (!c.isTarget(mc) || mc.user === mc.target) return true
            if (!blocks(mc)) return true
            if (mc.move.data.breaksProtect && !maxGuard) return true
            if ((mc.move.isZ || mc.move.isMax) && !maxGuard) return true // Z/Max moves deal 25% through protection
            if (mc.user.ability == "unseenfist" && mc.move.hasFlag("contact") && c.b.abilityActive(mc.user) && !maxGuard) return true
            c.b.message("${c.b.nm(c.mon)} protected itself!")
            if (mc.move.hasFlag("contact") && !(mc.user.item == "protectivepads" && c.b.itemActive(mc.user))) punish(c, mc)
            mc.user.volatiles.remove("lockedmove")
            if (mc.move.data.hasCrashDamage) {
                c.b.message("${c.b.nm(mc.user)} kept going and crashed!")
                c.b.directDamage(mc.user, mc.user.baseMaxHp / 2, mc.user, "crash")
            }
            return false
        }
    }

    private fun registerProtection() {
        reg(ProtectEffect("protect"))
        reg(object : ProtectEffect("maxguard", maxGuard = true) {
            override fun blocks(mc: MoveCtx) = mc.move.id !in setOf("feint", "gmaxoneblow", "gmaxrapidflow") && !(mc.move.isStatus && mc.move.target == "all")
        })
        reg(object : ProtectEffect("kingsshield") {
            override fun blocks(mc: MoveCtx) = mc.move.hasFlag("protect") && !mc.move.isStatus
            override fun punish(c: EffectCtx, mc: MoveCtx) { c.b.boost(mc.user, mapOf("atk" to -1), c.mon, "kingsshield", secondary = true) }
        })
        reg(object : ProtectEffect("spikyshield") {
            override fun punish(c: EffectCtx, mc: MoveCtx) {
                if (c.b.directDamage(mc.user, max(1, mc.user.baseMaxHp / 8), c.mon, "spikyshield") > 0) c.b.message("${c.b.nm(mc.user)} was hurt!")
            }
        })
        reg(object : ProtectEffect("banefulbunker") {
            override fun punish(c: EffectCtx, mc: MoveCtx) { c.b.trySetStatus(mc.user, MajorStatus.POISON, c.mon, "banefulbunker", primary = false) }
        })
        reg(object : ProtectEffect("burningbulwark") {
            override fun blocks(mc: MoveCtx) = mc.move.hasFlag("protect") && !mc.move.isStatus
            override fun punish(c: EffectCtx, mc: MoveCtx) { c.b.trySetStatus(mc.user, MajorStatus.BURN, c.mon, "burningbulwark", primary = false) }
        })
        reg(object : ProtectEffect("obstruct") {
            override fun blocks(mc: MoveCtx) = mc.move.hasFlag("protect") && !mc.move.isStatus
            override fun punish(c: EffectCtx, mc: MoveCtx) { c.b.boost(mc.user, mapOf("def" to -2), c.mon, "obstruct", secondary = true) }
        })
        reg(object : ProtectEffect("silktrap") {
            override fun blocks(mc: MoveCtx) = mc.move.hasFlag("protect") && !mc.move.isStatus
            override fun punish(c: EffectCtx, mc: MoveCtx) { c.b.boost(mc.user, mapOf("spe" to -1), c.mon, "silktrap", secondary = true) }
        })
        reg(object : Effect("endure", EffectKind.VOLATILE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} braced itself!") }
        })
    }

    // ================================================================= side & slot conditions

    private fun hazardImmune(b: Battle, m: BattleMon) = m.item == "heavydutyboots" && b.itemActive(m)

    private fun registerSideConditions() {
        fun screen(id: String, label: String, applies: (MoveCtx) -> Boolean) = object : Effect(id, EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = if (source?.item == "lightclay") 8 else 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("$label made ${sideName(c)} stronger against ${if (id == "reflect") "physical moves" else if (id == "lightscreen") "special moves" else "physical and special moves"}!") }
            override fun onEnd(c: EffectCtx) { c.b.message("${sideNameCap(c)}'s $label wore off!") }
            override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (!c.isTargetSide(mc) || mc.crit || !applies(mc) || mc.user.side === c.side) return
                if (mc.user.ability == "infiltrator" && c.b.abilityActive(mc.user)) return
                if (id != "auroraveil" && c.side!!.hasCondition("auroraveil")) return
                mod.chain(0.5)
            }
        }
        reg(screen("reflect", "Reflect") { it.move.category == "Physical" })
        reg(screen("lightscreen", "Light Screen") { it.move.category == "Special" })
        reg(screen("auroraveil", "Aurora Veil") { !it.move.isStatus })
        reg(object : Effect("safeguard", EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${sideNameCap(c)} became cloaked in a mystical veil!") }
            override fun onEnd(c: EffectCtx) { c.b.message("${sideNameCap(c)} is no longer protected by Safeguard!") }
            override fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?): Boolean {
                if (target.side !== c.side || source == null || source === target || sourceEffect == "toxicorb" || sourceEffect == "flameorb") return true
                if (source.ability == "infiltrator" && c.b.abilityActive(source)) return true
                c.b.message("${c.b.nm(target)} is protected by Safeguard!")
                return false
            }
            override fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?): Boolean {
                if (target.side !== c.side || source == null || source === target) return true
                if (volatile == "confusion" || volatile == "yawn") { c.b.message("${c.b.nm(target)} is protected by Safeguard!"); return false }
                return true
            }
        })
        reg(object : Effect("mist", EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${sideNameCap(c)} became shrouded in mist!") }
            override fun onEnd(c: EffectCtx) { c.b.message("${sideNameCap(c)} is no longer protected by mist!") }
            override fun onTryBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>, source: BattleMon?, sourceEffect: String?) {
                if (target.side !== c.side || source == null || source.side === c.side) return
                if (source.ability == "infiltrator" && c.b.abilityActive(source)) return
                var blocked = false
                for (k in boosts.keys.toList()) if (boosts[k]!! < 0) { boosts.remove(k); blocked = true }
                if (blocked) c.b.message("${c.b.nm(target)} is protected by the mist!")
            }
        })
        reg(object : Effect("tailwind", EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 4
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("The Tailwind blew from behind ${sideName(c)}!") }
            override fun onEnd(c: EffectCtx) { c.b.message("${sideNameCap(c)}'s Tailwind petered out!") }
            override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon.side === c.side) mod.chain(2.0) }
        })
        reg(object : Effect("luckychant", EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("Lucky Chant shielded ${sideName(c)} from critical hits!") }
            override fun onCriticalHit(c: EffectCtx, mc: MoveCtx) = !c.isTargetSide(mc)
        })
        reg(object : Effect("spikes", EffectKind.SIDE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.state.counter = 1; c.b.message("Spikes were scattered on the ground all around ${sideName(c)}!") }
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean {
                if (c.state.counter >= 3) return false
                c.state.counter++; c.b.message("Spikes were scattered on the ground all around ${sideName(c)}!"); return true
            }
            override fun onSwitchIn(c: EffectCtx) {
                val m = c.holder ?: return
                if (!c.b.isGrounded(m) || hazardImmune(c.b, m)) return
                val frac = when (c.state.counter) { 1 -> 8; 2 -> 6; else -> 4 }
                if (c.b.directDamage(m, max(1, m.baseMaxHp / frac), null, "spikes") > 0) c.b.message("${c.b.nm(m)} was hurt by the spikes!")
            }
        })
        reg(object : Effect("stealthrock", EffectKind.SIDE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("Pointed stones float in the air around ${sideName(c)}!") }
            override fun onSwitchIn(c: EffectCtx) {
                val m = c.holder ?: return
                if (hazardImmune(c.b, m)) return
                val eff = c.b.dex.typeChart.effectiveness("Rock", m.effectiveTypes())
                val dmg = max(1, (m.baseMaxHp * eff / 8).toInt())
                if (c.b.directDamage(m, dmg, null, "stealthrock") > 0) c.b.message("Pointed stones dug into ${c.b.nmL(m)}!")
            }
        })
        reg(object : Effect("toxicspikes", EffectKind.SIDE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.state.counter = 1; c.b.message("Poison spikes were scattered on the ground all around ${sideName(c)}!") }
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean {
                if (c.state.counter >= 2) return false
                c.state.counter++; c.b.message("Poison spikes were scattered on the ground all around ${sideName(c)}!"); return true
            }
            override fun onSwitchIn(c: EffectCtx) {
                val m = c.holder ?: return
                if (!c.b.isGrounded(m)) return
                if (m.hasType("Poison")) { c.b.removeSideCondition(c.side!!, "toxicspikes"); c.b.message("The poison spikes disappeared from the ground around ${sideName(c)}!"); return }
                if (hazardImmune(c.b, m) || m.hasType("Steel")) return
                c.b.trySetStatus(m, if (c.state.counter >= 2) MajorStatus.TOXIC else MajorStatus.POISON, null, "toxicspikes", primary = false)
            }
        })
        reg(object : Effect("stickyweb", EffectKind.SIDE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("A sticky web has been laid out on the ground around ${sideName(c)}!") }
            override fun onSwitchIn(c: EffectCtx) {
                val m = c.holder ?: return
                if (!c.b.isGrounded(m) || hazardImmune(c.b, m)) return
                c.b.message("${c.b.nm(m)} was caught in a sticky web!")
                c.b.boost(m, mapOf("spe" to -1), null, "stickyweb", secondary = true)
            }
        })
        reg(object : Effect("quickguard", EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("Quick Guard protected ${sideName(c)}!") }
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTargetSide(mc) || mc.user.side === c.side) return true
                if (mc.move.priority > 0 && !mc.move.isZ && !mc.move.isMax) { c.b.message("Quick Guard protected ${c.b.nmL(mc.target)}!"); return false }
                return true
            }
        })
        reg(object : Effect("wideguard", EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTargetSide(mc) || mc.user.side === c.side) return true
                if (mc.move.target in setOf("allAdjacent", "allAdjacentFoes") && !mc.move.isZ && !mc.move.isMax) { c.b.message("Wide Guard protected ${c.b.nmL(mc.target)}!"); return false }
                return true
            }
        })
        reg(object : Effect("craftyshield", EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTargetSide(mc) || mc.user.side === c.side) return true
                if (mc.move.isStatus && mc.move.target != "all") { c.b.message("Crafty Shield protected ${c.b.nmL(mc.target)}!"); return false }
                return true
            }
        })
        reg(object : Effect("matblock", EffectKind.SIDE) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTargetSide(mc) || mc.user.side === c.side || mc.move.isStatus) return true
                c.b.message("${mc.move.name} was blocked by the kicked-up mat!"); return false
            }
        })
        // G-Max residual side conditions.
        for ((id, type) in listOf("gmaxvinelash" to "Grass", "gmaxwildfire" to "Fire", "gmaxcannonade" to "Water", "gmaxvolcalith" to "Rock")) {
            reg(object : Effect(id, EffectKind.SIDE) {
                override val residualOrder = 5
                override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 4
                override fun onResidual(c: EffectCtx) {
                    val m = c.side?.active ?: return
                    if (m.fainted || m.hasType(type)) return
                    if (c.b.directDamage(m, max(1, m.baseMaxHp / 6), null, id) > 0) c.b.message("${c.b.nm(m)} is hurt by the G-Max effect!")
                }
            })
        }
        reg(object : Effect("gmaxsteelsurge", EffectKind.SIDE) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("Sharp-pointed pieces of steel started floating around ${sideName(c)}!") }
            override fun onSwitchIn(c: EffectCtx) {
                val m = c.holder ?: return
                if (hazardImmune(c.b, m)) return
                val eff = c.b.dex.typeChart.effectiveness("Steel", m.effectiveTypes())
                if (c.b.directDamage(m, max(1, (m.baseMaxHp * eff / 8).toInt()), null, "gmaxsteelsurge") > 0) c.b.message("The sharp steel bit into ${c.b.nmL(m)}!")
            }
        })

        // Slot conditions.
        reg(object : Effect("wish", EffectKind.SLOT) {
            override val residualOrder = 4
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.state.hp = (source?.baseMaxHp ?: 0) / 2; c.state.data["name"] = source?.name }
            override fun onEnd(c: EffectCtx) {
                val m = c.side?.active ?: return
                if (m.fainted) return
                c.b.message("${c.state.data["name"]}'s wish came true!")
                c.b.heal(m, c.state.hp, "wish")
            }
        })
        for (id in listOf("healingwish", "lunardance")) reg(object : Effect(id, EffectKind.SLOT) {
            override fun onSwitchIn(c: EffectCtx) {
                val m = c.holder ?: return
                if (m.hp >= m.maxHp && m.status == null) return
                c.side!!.slotConditions.remove(id)
                c.b.heal(m, m.maxHp, id)
                c.b.cureStatus(m, silent = true)
                if (id == "lunardance") for (s in m.moveSlots) s.pp = s.maxPp(c.b.dex)
                c.b.message("${c.b.nm(m)} became cloaked in mystical moonlight!".takeIf { id == "lunardance" } ?: "The healing wish came true for ${c.b.nmL(m)}!")
            }
        })
        reg(object : Effect("revivalblessing", EffectKind.SLOT) {})
        reg(object : Effect("futuremove", EffectKind.SLOT) {
            override val residualOrder = 3
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 3
            override fun onEnd(c: EffectCtx) {
                val target = c.side?.active ?: return
                val src = c.state.source ?: return
                if (target.fainted) return
                val move = c.b.dex.moveOrNull(c.state.moveId) ?: return
                c.b.message("${c.b.nm(target)} took the ${move.name} attack!")
                val mc = MoveCtx(src, target, ActiveMove(move))
                mc.move.flags.remove("futuremove")
                if (c.b.dex.typeChart.effectiveness(move.type, target.effectiveTypes()) == 0.0) { c.b.message("It doesn't affect ${c.b.nmL(target)}..."); return }
                val dmg = c.b.calcDamage(mc) ?: return
                c.b.directDamage(target, dmg, src, move.id)
            }
        })
    }

    private fun sideName(c: EffectCtx): String {
        val s = c.side ?: return "the field"
        return if (s.isPlayer) "your team" else "the opposing team"
    }
    private fun sideNameCap(c: EffectCtx) = sideName(c).replaceFirstChar { it.uppercase() }

    // ================================================================= weather

    private fun registerWeather() {
        fun weather(id: String, start: String, end: String, upkeep: String?, rockDuration: Int = 5, body: WeatherBody = WeatherBody()) =
            object : Effect(id, EffectKind.WEATHER) {
                override val residualOrder = 1
                override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = if (id in setOf("desolateland", "primordialsea", "deltastream")) -1 else rockDuration
                override fun onStart(c: EffectCtx, source: BattleMon?) {
                    c.b.events.add(BattleEvent.FieldChange("weather", id, start)); c.b.log.add(start)
                }
                override fun onEnd(c: EffectCtx) { c.b.events.add(BattleEvent.FieldChange("weather", null, end)); c.b.log.add(end) }
                override fun onResidual(c: EffectCtx) {
                    if (upkeep != null && c.state.duration != 1) c.b.message(upkeep)
                    body.residual(c)
                }
                override fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) = body.modDef(c, mc, mod)
                override fun onModifySpD(c: EffectCtx, mc: MoveCtx, mod: Mod) = body.modSpD(c, mc, mod)
                override fun onTryMove(c: EffectCtx, mc: MoveCtx) = body.tryMove(c, mc)
            }
        reg(weather("sunnyday", "The sunlight turned harsh!", "The harsh sunlight faded.", "The sunlight is strong."))
        reg(weather("raindance", "It started to rain!", "The rain stopped.", "Rain continues to fall."))
        reg(weather("sandstorm", "A sandstorm kicked up!", "The sandstorm subsided.", "The sandstorm is raging.", body = object : WeatherBody() {
            override fun residual(c: EffectCtx) {
                for (m in c.b.activeMonsBySpeed()) {
                    if (m.hasType("Rock") || m.hasType("Ground") || m.hasType("Steel")) continue
                    if (m.ability in setOf("sandveil", "sandrush", "sandforce", "overcoat", "magicguard") && c.b.abilityActive(m)) continue
                    if (m.item == "safetygoggles" && c.b.itemActive(m)) continue
                    if (c.b.directDamage(m, max(1, m.baseMaxHp / 16), null, "sandstorm") > 0) c.b.message("${c.b.nm(m)} is buffeted by the sandstorm!")
                }
            }
            override fun modSpD(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (mc.target.hasType("Rock")) mod.chain(1.5) }
        }))
        reg(weather("snowscape", "It started to snow!", "The snow stopped.", "Snow continues to fall.", body = object : WeatherBody() {
            override fun modDef(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (mc.target.hasType("Ice")) mod.chain(1.5) }
        }))
        reg(weather("hail", "It started to hail!", "The hail stopped.", "The hail is crashing down.", body = object : WeatherBody() {
            override fun residual(c: EffectCtx) {
                for (m in c.b.activeMonsBySpeed()) {
                    if (m.hasType("Ice")) continue
                    if (m.ability in setOf("icebody", "snowcloak", "overcoat", "magicguard") && c.b.abilityActive(m)) continue
                    if (m.item == "safetygoggles" && c.b.itemActive(m)) continue
                    if (c.b.directDamage(m, max(1, m.baseMaxHp / 16), null, "hail") > 0) c.b.message("${c.b.nm(m)} is buffeted by the hail!")
                }
            }
        }))
        reg(weather("desolateland", "The sunlight turned extremely harsh!", "The extremely harsh sunlight faded!", null, body = object : WeatherBody() {
            override fun tryMove(c: EffectCtx, mc: MoveCtx): Boolean {
                if (mc.move.type == "Water" && !mc.move.isStatus) { c.b.message("The Water-type attack evaporated in the extremely harsh sunlight!"); mc.move.data2["silentFail"] = true; return false }
                return true
            }
        }))
        reg(weather("primordialsea", "A heavy rain began to fall!", "The heavy rain has lifted!", null, body = object : WeatherBody() {
            override fun tryMove(c: EffectCtx, mc: MoveCtx): Boolean {
                if (mc.move.type == "Fire" && !mc.move.isStatus) { c.b.message("The Fire-type attack fizzled out in the heavy rain!"); mc.move.data2["silentFail"] = true; return false }
                return true
            }
        }))
        reg(weather("deltastream", "Mysterious strong winds are protecting Flying-type Pokémon!", "The mysterious strong winds have dissipated!", null))
    }

    private open class WeatherBody {
        open fun residual(c: EffectCtx) {}
        open fun modDef(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
        open fun modSpD(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
        open fun tryMove(c: EffectCtx, mc: MoveCtx): Boolean = true
    }

    // ================================================================= terrain

    private fun registerTerrain() {
        open class Terrain(id: String, val type: String, val start: String, val end: String) : Effect(id, EffectKind.TERRAIN) {
            override val residualOrder = 2
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.events.add(BattleEvent.FieldChange("terrain", id, start)); c.b.log.add(start) }
            override fun onEnd(c: EffectCtx) { c.b.events.add(BattleEvent.FieldChange("terrain", null, end)); c.b.log.add(end) }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (mc.move.type == type && c.b.isGrounded(mc.user)) mod.chain(1.3)
            }
        }
        reg(object : Terrain("electricterrain", "Electric", "An electric current ran across the battlefield!", "The electricity disappeared from the battlefield.") {
            override fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?): Boolean {
                if (status == MajorStatus.SLEEP && c.b.isGrounded(target) && !target.hasVolatile("twoturnmove")) {
                    if (sourceEffect != null) c.b.message("${c.b.nm(target)} is protected by the Electric Terrain!")
                    return false
                }
                return true
            }
            override fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?) =
                !(volatile == "yawn" && c.b.isGrounded(target))
        })
        reg(object : Terrain("grassyterrain", "Grass", "Grass grew to cover the battlefield!", "The grass disappeared from the battlefield.") {
            override fun onResidual(c: EffectCtx) {
                for (m in c.b.activeMonsBySpeed()) if (c.b.isGrounded(m) && !m.hasVolatile("twoturnmove")) {
                    if (c.b.heal(m, max(1, m.baseMaxHp / 16), "grassyterrain") > 0) c.b.message("${c.b.nm(m)}'s HP was restored.")
                }
            }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                super.onBasePower(c, mc, mod)
                if (mc.move.id in setOf("earthquake", "bulldoze", "magnitude") && c.b.isGrounded(mc.target)) mod.chain(0.5)
            }
        })
        reg(object : Terrain("mistyterrain", "Fairy", "Mist swirled around the battlefield!", "The mist disappeared from the battlefield.") {
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (mc.move.type == "Dragon" && c.b.isGrounded(mc.target)) mod.chain(0.5)
            }
            override fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?): Boolean {
                if (c.b.isGrounded(target) && !target.hasVolatile("twoturnmove")) {
                    if (sourceEffect != null && source !== target) c.b.message("${c.b.nm(target)} surrounds itself with a protective mist!")
                    return false
                }
                return true
            }
            override fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?) =
                !(volatile == "confusion" && c.b.isGrounded(target) && source !== target)
        })
        reg(object : Terrain("psychicterrain", "Psychic", "The battlefield got weird!", "The weirdness disappeared from the battlefield.") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (mc.user.side === mc.target.side || !c.b.isGrounded(mc.target)) return true
                if ((mc.move.data2["priority"] as? Int ?: mc.move.priority) > 0) {
                    c.b.message("${c.b.nm(mc.target)} is protected by the Psychic Terrain!")
                    return false
                }
                return true
            }
        })
    }

    // ================================================================= pseudo-weather

    private fun registerPseudoWeather() {
        reg(object : Effect("trickroom", EffectKind.PSEUDO_WEATHER) {
            override val residualOrder = 23
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.events.add(BattleEvent.FieldChange("pseudo", "trickroom", "${source?.let { c.b.nm(it) } ?: "Someone"} twisted the dimensions!")) ; c.b.log.add("twisted the dimensions") }
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean { c.b.removePseudoWeather("trickroom"); return true }
            override fun onEnd(c: EffectCtx) { c.b.events.add(BattleEvent.FieldChange("pseudo", null, "The twisted dimensions returned to normal!")); c.b.log.add("dimensions normal") }
        })
        reg(object : Effect("gravity", EffectKind.PSEUDO_WEATHER) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                c.b.message("Gravity intensified!")
                for (m in c.b.activeMons()) { m.volatiles.remove("magnetrise"); m.volatiles.remove("telekinesis") }
            }
            override fun onEnd(c: EffectCtx) { c.b.message("Gravity returned to normal!") }
            override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { mod.chain(5.0 / 3.0) }
            override fun onDisableMove(c: EffectCtx, moveId: String) = c.b.dex.moveOrNull(moveId)?.hasFlag("gravity") == true
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                if (mc.move.hasFlag("gravity") && c.holder === mc.user) { c.b.message("${c.b.nm(mc.user)} can't use ${mc.move.name} because of gravity!"); return false }
                return true
            }
        })
        reg(object : Effect("magicroom", EffectKind.PSEUDO_WEATHER) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("It created a bizarre area in which Pokémon's held items lose their effects!") }
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean { c.b.removePseudoWeather("magicroom"); return true }
            override fun onEnd(c: EffectCtx) { c.b.message("Magic Room wore off, and held items' effects returned to normal!") }
        })
        reg(object : Effect("wonderroom", EffectKind.PSEUDO_WEATHER) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("It created a bizarre area in which Defense and Sp. Def stats are swapped!") }
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean { c.b.removePseudoWeather("wonderroom"); return true }
            override fun onEnd(c: EffectCtx) { c.b.message("Wonder Room wore off, and Defense and Sp. Def stats returned to normal!") }
        })
        reg(object : Effect("echoedvoice", EffectKind.PSEUDO_WEATHER) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.state.counter = 1 }
            override fun onRestart(c: EffectCtx, source: BattleMon?): Boolean {
                if (c.state.duration != 2) { c.state.duration = 2; if (c.state.counter < 5) c.state.counter++ }
                return true
            }
        })
        reg(object : Effect("fairylock", EffectKind.PSEUDO_WEATHER) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 2
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("No one will be able to run away during the next turn!") }
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = true
        })
        reg(object : Effect("mudsport", EffectKind.PSEUDO_WEATHER) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("Electricity's power was weakened!") }
            override fun onEnd(c: EffectCtx) { c.b.message("The effects of Mud Sport have faded.") }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (mc.move.type == "Electric") mod.chain(1.0 / 3) }
        })
        reg(object : Effect("watersport", EffectKind.PSEUDO_WEATHER) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 5
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("Fire's power was weakened!") }
            override fun onEnd(c: EffectCtx) { c.b.message("The effects of Water Sport have faded.") }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (mc.move.type == "Fire") mod.chain(1.0 / 3) }
        })
        reg(object : Effect("iondeluge", EffectKind.PSEUDO_WEATHER) {
            override fun duration(b: Battle, target: BattleMon?, source: BattleMon?) = 1
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("A deluge of ions showers the battlefield!") }
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) { if (mc.move.type == "Normal") mc.move.type = "Electric" }
        })
    }
}
