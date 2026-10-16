package pkmn.engine.battle

import pkmn.engine.data.FormKind
import pkmn.engine.data.ItemCategory
import pkmn.engine.data.MoveData
import pkmn.engine.util.toId

/**
 * Official battle gimmicks. Eligibility, transformation and reversion live here; the per-battle usage limit is
 * enforced by [Side.canUseGimmick] according to [BattleRules].
 */
object Gimmicks {

    // ------------------------------------------------------------------ Mega Evolution

    /** The Mega forme [mon] would become, or null if it cannot Mega Evolve. */
    fun megaTarget(b: Battle, mon: BattleMon): String? {
        if (mon.species.formKind == FormKind.MEGA || mon.isDynamaxed || mon.terastallized != null) return null
        val base = toId(mon.species.baseSpecies)
        // Rayquaza Mega Evolves by knowing Dragon Ascent instead of holding a stone.
        if (base == "rayquaza" && mon.moveSlots.any { it.id == "dragonascent" } && b.dex.itemOrNull(mon.item)?.category != ItemCategory.Z_CRYSTAL) return "rayquazamega"
        val item = b.dex.itemOrNull(mon.item) ?: return null
        val target = item.megaStone[mon.species.id] ?: item.megaStone[base] ?: return null
        val sp = b.dex.speciesOrNull(target) ?: return null
        return if (sp.formKind == FormKind.MEGA) sp.id else null
    }

    fun megaEvolve(b: Battle, mon: BattleMon) {
        val target = megaTarget(b, mon) ?: return
        val sp = b.dex.species(target)
        val stone = b.dex.itemOrNull(mon.item)?.name ?: "Dragon Ascent"
        b.message("${b.nm(mon)}'s $stone is reacting to ${if (mon.side.isPlayer) "your" else "the"} Key Stone!")
        b.formeChange(mon, sp.id, "mega", "${b.nm(mon)} has Mega Evolved into ${sp.name}!")
        mon.baseAbility = mon.ability
        b.events.add(BattleEvent.Animation(mon.side.index, "megaevolution"))
        // Mega abilities activate immediately (e.g. Intimidate, Drought).
        Effects.ability(mon.ability)?.onStart(EffectCtx(b, Effects.ability(mon.ability)!!, mon, null, mon.abilityState), null)
    }

    // ------------------------------------------------------------------ Primal Reversion

    fun checkPrimal(b: Battle, mon: BattleMon) {
        val base = toId(mon.species.baseSpecies)
        val target = when {
            base == "kyogre" && mon.item == "blueorb" -> "kyogreprimal"
            base == "groudon" && mon.item == "redorb" -> "groudonprimal"
            else -> return
        }
        if (mon.species.id == target) return
        val sp = b.dex.speciesOrNull(target) ?: return
        b.formeChange(mon, sp.id, "primal", "${b.nm(mon)}'s Primal Reversion! It reverted to its primal state!")
        mon.baseAbility = mon.ability
        b.events.add(BattleEvent.Animation(mon.side.index, "primal"))
        Effects.ability(mon.ability)?.onStart(EffectCtx(b, Effects.ability(mon.ability)!!, mon, null, mon.abilityState), null)
    }

    // ------------------------------------------------------------------ Ultra Burst

    fun ultraBurstTarget(b: Battle, mon: BattleMon): String? {
        if (mon.item != "ultranecroziumz") return null
        return if (mon.species.id in setOf("necrozmaduskmane", "necrozmadawnwings")) "necrozmaultra" else null
    }

    fun ultraBurst(b: Battle, mon: BattleMon) {
        val target = ultraBurstTarget(b, mon) ?: return
        b.message("Bright light is about to burst out of ${b.nmL(mon)}!")
        b.formeChange(mon, target, "ultraburst", "${b.nm(mon)} regained its true power through Ultra Burst!")
        b.events.add(BattleEvent.Animation(mon.side.index, "ultraburst"))
        Effects.ability(mon.ability)?.onStart(EffectCtx(b, Effects.ability(mon.ability)!!, mon, null, mon.abilityState), null)
    }

    // ------------------------------------------------------------------ Z-Moves

    private val zMoveByType = mapOf(
        "Normal" to "breakneckblitz", "Fighting" to "alloutpummeling", "Flying" to "supersonicskystrike", "Poison" to "aciddownpour",
        "Ground" to "tectonicrage", "Rock" to "continentalcrush", "Bug" to "savagespinout", "Ghost" to "neverendingnightmare",
        "Steel" to "corkscrewcrash", "Fire" to "infernooverdrive", "Water" to "hydrovortex", "Grass" to "bloomdoom",
        "Electric" to "gigavolthavoc", "Psychic" to "shatteredpsyche", "Ice" to "subzeroslammer", "Dragon" to "devastatingdrake",
        "Dark" to "blackholeeclipse", "Fairy" to "twinkletackle",
    )

    /** Builds the Z-Move [mon] would use in place of [base], or null if not possible. */
    fun zMoveFor(b: Battle, mon: BattleMon, base: MoveData): ActiveMove? {
        val item = b.dex.itemOrNull(mon.item) ?: return null
        val z = item.zMove ?: return null
        if (mon.isDynamaxed) return null
        if (z != "true") {
            // Signature Z-Move: requires the right species and base move.
            if (item.itemUser.isNotEmpty() && toId(mon.species.name) !in item.itemUser && mon.species.id !in item.itemUser) return null
            if (item.zMoveFrom.isNotEmpty() && base.id !in item.zMoveFrom) return null
            val zm = b.dex.moveOrNull(toId(z)) ?: return null
            return ActiveMove(zm).apply { isZ = true; zBaseMove = base.id }
        }
        if (item.zMoveType != base.type) return null
        if (base.isStatus) {
            // Status Z-Moves keep the base move and gain a Z-Power effect.
            return ActiveMove(base).apply { isZ = true; zBaseMove = base.id; name = "Z-${base.name}" }
        }
        val zId = zMoveByType[base.type] ?: return null
        val zm = b.dex.moveOrNull(zId) ?: return null
        return ActiveMove(zm).apply {
            isZ = true
            zBaseMove = base.id
            basePower = base.zMove?.basePower ?: zPower(base.basePower)
            category = base.category
        }
    }

    fun zPower(bp: Int) = when {
        bp <= 55 -> 100; bp <= 65 -> 120; bp <= 75 -> 140; bp <= 85 -> 160; bp <= 95 -> 175
        bp <= 100 -> 180; bp <= 110 -> 185; bp <= 125 -> 190; bp <= 130 -> 195; else -> 200
    }

    fun applyZStatusEffect(b: Battle, mon: BattleMon, base: MoveData) {
        val z = base.zMove ?: return
        if (z.boost.isNotEmpty()) b.boost(mon, z.boost, mon, "zpower")
        when (z.effect) {
            "heal" -> { b.heal(mon, mon.maxHp, "zpower"); b.message("${b.nm(mon)}'s HP was restored by Z-Power!") }
            "clearnegativeboost" -> {
                for (i in mon.boosts.indices) if (mon.boosts[i] < 0) mon.boosts[i] = 0
                b.message("${b.nm(mon)} returned its decreased stats to normal using its Z-Power!")
            }
            "crit2" -> { b.addVolatile(mon, "focusenergy", mon, "zpower") }
            "redirect" -> {}
            "healreplacement" -> { b.addSlotCondition(mon.side, "healingwish", mon) }
            "curse" -> if (mon.hasType("Ghost")) { b.heal(mon, mon.maxHp, "zpower") } else b.boost(mon, mapOf("atk" to 1), mon, "zpower")
        }
    }

    // ------------------------------------------------------------------ Dynamax / Gigantamax

    private val maxMoveByType = mapOf(
        "Normal" to "maxstrike", "Fighting" to "maxknuckle", "Flying" to "maxairstream", "Poison" to "maxooze", "Ground" to "maxquake",
        "Rock" to "maxrockfall", "Bug" to "maxflutterby", "Ghost" to "maxphantasm", "Steel" to "maxsteelspike", "Fire" to "maxflare",
        "Water" to "maxgeyser", "Grass" to "maxovergrowth", "Electric" to "maxlightning", "Psychic" to "maxmindstorm", "Ice" to "maxhailstorm",
        "Dragon" to "maxwyrmwind", "Dark" to "maxdarkness", "Fairy" to "maxstarfall",
    )

    fun dynamax(b: Battle, mon: BattleMon) {
        val oldMax = mon.maxHp
        mon.dynamaxTurns = 3
        val gmax = mon.mon.gmaxFactor && mon.species.canGigantamax != null
        val ratio = mon.hp.toDouble() / oldMax
        mon.hp = maxOf(1, Math.round(mon.maxHp * ratio).toInt())
        mon.volatiles.remove("substitute"); mon.volatiles.remove("encore"); mon.volatiles.remove("torment"); mon.volatiles.remove("disable")
        if (gmax) {
            val gmaxId = b.dex.speciesOrNull(mon.species.id + "gmax")?.id
            if (gmaxId != null) {
                mon.preTransformSpecies = mon.species
                mon.isGigantamax = true
                b.events.add(BattleEvent.FormeChange(mon.side.index, b.teamIndex(mon), gmaxId, "gigantamax", "${b.nm(mon)} Gigantamaxed!"))
                b.log.add("Gigantamax")
            }
        } else {
            b.events.add(BattleEvent.FormeChange(mon.side.index, b.teamIndex(mon), mon.species.id, "dynamax", "${b.nm(mon)} Dynamaxed!"))
            b.log.add("Dynamax")
        }
        b.events.add(BattleEvent.HpChange(mon.side.index, b.teamIndex(mon), mon.hp, mon.maxHp, 0, "dynamax"))
    }

    fun endDynamax(b: Battle, mon: BattleMon, silent: Boolean) {
        if (!mon.isDynamaxed && !mon.isGigantamax) return
        val ratio = if (mon.maxHp > 0) mon.hp.toDouble() / mon.maxHp else 0.0
        mon.dynamaxTurns = 0
        val wasGmax = mon.isGigantamax
        mon.isGigantamax = false
        if (wasGmax) mon.preTransformSpecies = null
        if (mon.hp > 0) mon.hp = maxOf(1, Math.round(mon.baseMaxHp * ratio).toInt())
        if (!silent) {
            b.events.add(BattleEvent.FormeChange(mon.side.index, b.teamIndex(mon), mon.species.id, "undynamax", "${b.nm(mon)} returned to normal!"))
            b.log.add("returned to normal")
            b.events.add(BattleEvent.HpChange(mon.side.index, b.teamIndex(mon), mon.hp, mon.maxHp, 0, "dynamax"))
        }
    }

    /** Converts [base] into the Max Move (or G-Max Move) used by a Dynamaxed [mon]. */
    fun maxMoveFor(b: Battle, mon: BattleMon, base: MoveData): ActiveMove {
        if (base.isStatus) return ActiveMove(b.dex.move("maxguard")).apply { isMax = true }
        var id = maxMoveByType[base.type] ?: "maxstrike"
        if (mon.isGigantamax) {
            val gmaxName = mon.species.canGigantamax
            val gmaxMove = gmaxName?.let { b.dex.moveOrNull(toId(it)) }
            if (gmaxMove != null && gmaxMove.type == base.type) id = gmaxMove.id
        }
        val mm = b.dex.move(id)
        return ActiveMove(mm).apply {
            isMax = true
            category = base.category
            basePower = when {
                mm.isMax != "true" && mm.basePower > 10 && mm.id in setOf("gmaxdrumsolo", "gmaxfireball", "gmaxhydrosnipe") -> 160
                else -> base.maxMovePower ?: maxPower(base)
            }
            flags.remove("protect")
        }
    }

    fun maxPower(base: MoveData): Int {
        val bp = base.basePower
        val weak = base.type == "Fighting" || base.type == "Poison"
        return if (weak) when {
            bp <= 40 -> 70; bp <= 50 -> 75; bp <= 60 -> 80; bp <= 70 -> 85; bp <= 100 -> 90; bp <= 140 -> 95; else -> 100
        } else when {
            bp <= 40 -> 90; bp <= 50 -> 100; bp <= 60 -> 110; bp <= 70 -> 120; bp <= 100 -> 130; bp <= 140 -> 140; else -> 150
        }
    }

    // ------------------------------------------------------------------ Terastallization

    fun terastallize(b: Battle, mon: BattleMon) {
        val type = mon.mon.teraType ?: return
        mon.terastallized = type
        b.events.add(BattleEvent.FormeChange(mon.side.index, b.teamIndex(mon), mon.species.id, "tera:$type", "${b.nm(mon)} has Terastallized into the $type type!"))
        b.log.add("Terastallized $type")
        val base = toId(mon.species.baseSpecies)
        val before = mon.species.id
        if (base == "terapagos" && type == "Stellar") b.formeChange(mon, "terapagosstellar", "battle", null)
        if (base == "ogerpon") {
            val teraForme = b.dex.speciesOrNull(mon.species.id + "tera")
            if (teraForme != null) b.formeChange(mon, teraForme.id, "battle", null)
        }
        // A Tera forme's Ability (Embody Aspect, Teraform Zero) activates immediately.
        if (mon.species.id != before && b.abilityActive(mon)) Effects.ability(mon.ability)?.let { it.onStart(EffectCtx(b, it, mon, null, mon.abilityState), null) }
    }
}
