package pkmn.engine.campaign

import pkmn.engine.battle.BagItemEffects
import pkmn.engine.data.ItemCategory
import pkmn.engine.data.Stat
import pkmn.engine.mon.Nature
import pkmn.engine.mon.Pokemon

/** Outcome of using a bag item on a Pokémon outside battle. */
data class FieldItemResult(
    val ok: Boolean,
    val message: String,
    val consumed: Boolean = ok,
    /** Species to evolve into (evolution items, Linking Cord, Rare Candy level-ups). */
    val evolveTo: String? = null,
    /** Moves to offer after a level gain (Rare Candy). */
    val newMoves: List<String> = emptyList(),
    /** TM move to teach. */
    val teach: String? = null,
    /**
     * Evolution items (stones, Linking Cord) are spent only when the evolution actually completes: the caller passes
     * [evolveTo] on to the evolution screen and calls [GameContext.completeItemEvolution]; a cancelled evolution keeps the item.
     */
    val consumeOnEvolve: Boolean = false,
)

/** Rules for items used from the bag in the overworld. */
class FieldItems(private val ctx: GameContext) {
    private val dex = ctx.dex

    private val vitamins = mapOf("hpup" to Stat.HP, "protein" to Stat.ATK, "iron" to Stat.DEF, "calcium" to Stat.SPA, "zinc" to Stat.SPD, "carbos" to Stat.SPE)
    /** EV-reducing berries (Generation VIII+: -10 EVs) and they raise friendship. */
    private val evBerries = mapOf("pomegberry" to Stat.HP, "kelpsyberry" to Stat.ATK, "qualotberry" to Stat.DEF, "hondewberry" to Stat.SPA,
        "grepaberry" to Stat.SPD, "tamatoberry" to Stat.SPE)

    private fun evBerry(mon: Pokemon, stat: Stat): FieldItemResult {
        if (mon.evs[stat] <= 0 && mon.friendship >= 255) return FieldItemResult(false, "It won't have any effect.")
        val hpBefore = mon.maxHp(dex)
        val before = mon.evs[stat]
        mon.evs[stat] = maxOf(0, before - 10)
        keepDamage(mon, hpBefore)
        mon.friendship = minOf(255, mon.friendship + when { mon.friendship < 100 -> 10; mon.friendship < 200 -> 5; else -> 2 })
        val name = mon.displayName(dex)
        return FieldItemResult(true, if (before > 0) "$name became more friendly. Its base ${stat.label} fell!" else "$name became more friendly. Its base ${stat.label} can't fall any further!")
    }

    private val feathers = mapOf("healthfeather" to Stat.HP, "musclefeather" to Stat.ATK, "resistfeather" to Stat.DEF, "geniusfeather" to Stat.SPA,
        "cleverfeather" to Stat.SPD, "swiftfeather" to Stat.SPE)

    /** Whether the item is used on a party member (vs. used directly, like Repel). */
    fun needsTarget(item: String): Boolean = item.startsWith("tm:") || item in BagItemEffects.healing || item in vitamins || item in feathers || item in evBerries ||
        item in setOf("ether", "maxether", "elixir", "maxelixir", "ppup", "ppmax", "rarecandy", "linkingcord", "abilitycapsule", "abilitypatch") ||
        item.endsWith("mint") || dex.itemOrNull(item)?.category == ItemCategory.EVOLUTION || isEvolutionItem(item)

    private fun isEvolutionItem(item: String) = dex.species.values.any { sp -> sp.evolutions.any { it.item == item && it.method == pkmn.engine.data.EvolutionMethod.USE_ITEM } }

    /** Items used without a target. Returns null if the item is not usable this way. */
    fun useDirect(item: String): FieldItemResult? = when (item) {
        "repel" -> repel(100); "superrepel" -> repel(200); "maxrepel" -> repel(250)
        else -> null
    }

    private fun repel(steps: Int): FieldItemResult {
        if (ctx.state.repelSteps > 0) return FieldItemResult(false, "The REPEL's effect still lingers.")
        ctx.state.repelSteps = steps
        return FieldItemResult(true, "${ctx.state.playerName} used the REPEL. Weak wild POKéMON will stay away.")
    }

    fun useOn(mon: Pokemon, item: String, moveIndex: Int = -1): FieldItemResult {
        val name = mon.displayName(dex)
        if (item.startsWith("tm:")) {
            val move = item.removePrefix("tm:")
            if (!ctx.progression.canLearnMachine(mon, move)) return FieldItemResult(false, "$name can't learn ${dex.move(move).name}.", false)
            return FieldItemResult(true, "Booted up the TM.", consumed = false, teach = move)
        }
        BagItemEffects.healing[item]?.let { h ->
            val max = mon.maxHp(dex)
            if (h.revive > 0) {
                if (!mon.isFainted) return FieldItemResult(false, "It won't have any effect.")
                mon.hp = maxOf(1, (max * h.revive).toInt()); mon.fainted = false; mon.status = null
                return FieldItemResult(true, "$name is revitalized!")
            }
            if (mon.isFainted) return FieldItemResult(false, "It won't have any effect.")
            val amount = if (h.fraction > 0) (max * h.fraction).toInt() else h.hp
            var did = false
            var msg = ""
            if (amount > 0 && mon.hp < max) { val before = mon.hp; mon.hp = minOf(max, mon.hp + amount); msg = "$name's HP was restored by ${mon.hp - before} points."; did = true }
            val st = mon.status
            if (st != null && st in h.cures) { mon.status = null; mon.sleepTurns = 0; if (msg.isEmpty()) msg = "$name was cured."; did = true }
            return if (did) FieldItemResult(true, msg) else FieldItemResult(false, "It won't have any effect.")
        }
        when (item) {
            "ether", "maxether" -> {
                val slot = mon.moves.getOrNull(moveIndex) ?: mon.moves.minByOrNull { it.pp - it.maxPp(dex) } ?: return FieldItemResult(false, "It won't have any effect.")
                val max = slot.maxPp(dex)
                if (slot.pp >= max) return FieldItemResult(false, "It won't have any effect.")
                slot.pp = if (item == "maxether") max else minOf(max, slot.pp + 10)
                return FieldItemResult(true, "PP was restored.")
            }
            "elixir", "maxelixir" -> {
                var did = false
                for (s in mon.moves) { val max = s.maxPp(dex); if (s.pp < max) { s.pp = if (item == "maxelixir") max else minOf(max, s.pp + 10); did = true } }
                return if (did) FieldItemResult(true, "PP was restored.") else FieldItemResult(false, "It won't have any effect.")
            }
            "ppup", "ppmax" -> {
                val slot = mon.moves.getOrNull(moveIndex) ?: mon.moves.firstOrNull { it.ppUps < 3 && dex.moveOrNull(it.id)?.noPPBoosts != true }
                    ?: return FieldItemResult(false, "It won't have any effect.")
                if (slot.ppUps >= 3) return FieldItemResult(false, "It won't have any effect.")
                val before = slot.maxPp(dex)
                slot.ppUps = if (item == "ppmax") 3 else slot.ppUps + 1
                slot.pp += slot.maxPp(dex) - before
                return FieldItemResult(true, "${dex.move(slot.id).name}'s PP increased.")
            }
            "rarecandy" -> {
                // Never past the level cap (Challenge difficulty: the next leader's ace level), nor 100.
                if (mon.level >= 100 || mon.level >= ctx.levelCap() || mon.isFainted) return FieldItemResult(false, "It won't have any effect.")
                val needed = mon.species(dex).growthRate.expForLevel(mon.level + 1) - mon.exp
                val ups = ctx.progression.addExp(mon, needed)
                mon.friendship = minOf(255, mon.friendship + 3)
                val moves = ups.flatMap { it.newMoves }
                val evo = ctx.pendingEvolutions(listOf(ctx.state.party.indexOf(mon)), null).firstOrNull()?.second
                return FieldItemResult(true, "$name grew to Lv. ${mon.level}!", evolveTo = evo, newMoves = moves)
            }
            "linkingcord" -> {
                val target = ctx.evolutionByItem(mon, "linkingcord", null) ?: return FieldItemResult(false, "It won't have any effect.")
                return FieldItemResult(true, "", consumed = false, evolveTo = target, consumeOnEvolve = true)
            }
            "abilitycapsule" -> {
                val sp = mon.species(dex)
                if ("1" !in sp.abilities || mon.abilitySlot == "H") return FieldItemResult(false, "It won't have any effect.")
                mon.abilitySlot = if (mon.abilitySlot == "0") "1" else "0"
                return FieldItemResult(true, "$name's Ability changed to ${dex.ability(mon.ability(dex)).name}!")
            }
            "abilitypatch" -> {
                val sp = mon.species(dex)
                if ("H" !in sp.abilities) return FieldItemResult(false, "It won't have any effect.")
                mon.abilitySlot = if (mon.abilitySlot == "H") "0" else "H"
                return FieldItemResult(true, "$name's Ability changed to ${dex.ability(mon.ability(dex)).name}!")
            }
        }
        vitamins[item]?.let { stat -> return evItem(mon, stat, 10, 252) }
        feathers[item]?.let { stat -> return evItem(mon, stat, 1, 252) }
        evBerries[item]?.let { stat -> return evBerry(mon, stat) }
        if (item.endsWith("mint") && item != "mint") {
            val nature = Nature.entries.firstOrNull { it.name.lowercase() + "mint" == item } ?: return FieldItemResult(false, "It won't have any effect.")
            if (mon.statNature == nature) return FieldItemResult(false, "It won't have any effect.")
            // Generation VIII+: a Mint changes which stats the nature affects; the original nature is kept.
            val hpBefore = mon.maxHp(dex)
            mon.mintNature = nature.takeIf { it != mon.nature }
            keepDamage(mon, hpBefore)
            return FieldItemResult(true, "$name's stats may have changed due to the effects of the ${dex.itemOrNull(item)?.name ?: "mint"}!")
        }
        // Evolution stones and other use-item evolutions.
        ctx.evolutionByItem(mon, item, null)?.let { return FieldItemResult(true, "", consumed = false, evolveTo = it, consumeOnEvolve = true) }
        return FieldItemResult(false, "It won't have any effect.", consumed = false)
    }

    /** When max HP changes outside battle, current HP moves by the same amount (damage taken is kept). */
    private fun keepDamage(mon: Pokemon, maxBefore: Int) {
        if (mon.isFainted) return
        val max = mon.maxHp(dex)
        mon.hp = (mon.hp + max - maxBefore).coerceIn(1, max)
    }

    /** Generation VIII+ vitamins (+10, up to 252) and feathers (+1). Never beyond 252 per stat or 510 in total. */
    private fun evItem(mon: Pokemon, stat: Stat, amount: Int, cap: Int): FieldItemResult {
        val room = minOf(cap - mon.evs[stat], 510 - mon.evs.total)
        if (room <= 0) return FieldItemResult(false, "It won't have any effect.")
        val hpBefore = mon.maxHp(dex)
        mon.evs[stat] = mon.evs[stat] + minOf(room, amount)
        keepDamage(mon, hpBefore)
        mon.friendship = minOf(255, mon.friendship + 2)
        return FieldItemResult(true, "${mon.displayName(dex)}'s base ${stat.label} rose.")
    }
}
