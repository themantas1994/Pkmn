package pkmn.engine.mon

import kotlinx.serialization.Serializable
import pkmn.engine.data.*
import pkmn.engine.util.Rng
import pkmn.engine.util.toId

enum class Nature(val plus: Stat?, val minus: Stat?) {
    HARDY(null, null), LONELY(Stat.ATK, Stat.DEF), BRAVE(Stat.ATK, Stat.SPE), ADAMANT(Stat.ATK, Stat.SPA), NAUGHTY(Stat.ATK, Stat.SPD),
    BOLD(Stat.DEF, Stat.ATK), DOCILE(null, null), RELAXED(Stat.DEF, Stat.SPE), IMPISH(Stat.DEF, Stat.SPA), LAX(Stat.DEF, Stat.SPD),
    TIMID(Stat.SPE, Stat.ATK), HASTY(Stat.SPE, Stat.DEF), SERIOUS(null, null), JOLLY(Stat.SPE, Stat.SPA), NAIVE(Stat.SPE, Stat.SPD),
    MODEST(Stat.SPA, Stat.ATK), MILD(Stat.SPA, Stat.DEF), QUIET(Stat.SPA, Stat.SPE), BASHFUL(null, null), RASH(Stat.SPA, Stat.SPD),
    CALM(Stat.SPD, Stat.ATK), GENTLE(Stat.SPD, Stat.DEF), SASSY(Stat.SPD, Stat.SPE), CAREFUL(Stat.SPD, Stat.SPA), QUIRKY(null, null);

    fun multiplier(stat: Stat): Double = when (stat) { plus -> if (plus == minus) 1.0 else 1.1; minus -> 0.9; else -> 1.0 }
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

enum class Gender { MALE, FEMALE, NONE }

/** Persistent (non-volatile) status conditions that survive switching and the end of battle. */
enum class MajorStatus(val id: String, val label: String) {
    BURN("brn", "BRN"), PARALYSIS("par", "PAR"), POISON("psn", "PSN"), TOXIC("tox", "PSN"), SLEEP("slp", "SLP"), FREEZE("frz", "FRZ");
    companion object { fun byId(id: String?): MajorStatus? = entries.firstOrNull { it.id == id } }
}

@Serializable
data class StatSpread(var hp: Int = 0, var atk: Int = 0, var def: Int = 0, var spa: Int = 0, var spd: Int = 0, var spe: Int = 0) {
    operator fun get(s: Stat) = when (s) { Stat.HP -> hp; Stat.ATK -> atk; Stat.DEF -> def; Stat.SPA -> spa; Stat.SPD -> spd; Stat.SPE -> spe }
    operator fun set(s: Stat, v: Int) {
        when (s) { Stat.HP -> hp = v; Stat.ATK -> atk = v; Stat.DEF -> def = v; Stat.SPA -> spa = v; Stat.SPD -> spd = v; Stat.SPE -> spe = v }
    }
    val total get() = hp + atk + def + spa + spd + spe
    fun copySpread() = StatSpread(hp, atk, def, spa, spd, spe)
}

@Serializable
data class MoveSlot(val id: String, var pp: Int, var ppUps: Int = 0) {
    fun maxPp(dex: Dex): Int {
        val base = dex.moveOrNull(id)?.pp ?: 5
        return if (dex.moveOrNull(id)?.noPPBoosts == true) base else base + base * ppUps / 5
    }
}

/**
 * A Pokémon owned by a trainer or encountered in the wild. This is the persistent model that is saved;
 * battle-only state lives in [pkmn.engine.battle.BattleMon].
 */
@Serializable
class Pokemon(
    var speciesId: String,
    var level: Int,
    var nature: Nature = Nature.HARDY,
    val ivs: StatSpread = StatSpread(31, 31, 31, 31, 31, 31),
    val evs: StatSpread = StatSpread(),
    /** Ability slot: "0", "1", "H" or "S". */
    var abilitySlot: String = "0",
    val moves: MutableList<MoveSlot> = mutableListOf(),
    var heldItem: String? = null,
    var gender: Gender = Gender.NONE,
    var shiny: Boolean = false,
    var nickname: String? = null,
    var exp: Int = 0,
    var hp: Int = -1,
    var status: MajorStatus? = null,
    /** Remaining sleep turns while asleep (persists out of battle for save fidelity). */
    var sleepTurns: Int = 0,
    var friendship: Int = 70,
    var teraType: String? = null,
    var gmaxFactor: Boolean = false,
    var dynamaxLevel: Int = 10,
    var ball: String = "pokeball",
    var otName: String = "",
    var otId: Int = 0,
    var metLocation: String = "",
    var metLevel: Int = 1,
    /** Unique id for this individual (used for party/box identity and evolution tracking). */
    val uid: Long = 0,
    /** Counters used by special evolution conditions (critical hits landed, specific move uses, ...). */
    val counters: MutableMap<String, Int> = mutableMapOf(),
    var fainted: Boolean = false,
) {
    fun species(dex: Dex): SpeciesData = dex.species(speciesId)

    fun displayName(dex: Dex): String = nickname ?: species(dex).name

    fun ability(dex: Dex): String {
        val sp = species(dex)
        return sp.abilities[abilitySlot] ?: sp.abilities["0"] ?: "noability"
    }

    fun stat(dex: Dex, stat: Stat): Int = calcStat(species(dex), stat, level, ivs[stat], evs[stat], nature)

    fun maxHp(dex: Dex): Int = stat(dex, Stat.HP)

    fun ensureHp(dex: Dex) { if (hp < 0) hp = maxHp(dex) }

    val isFainted get() = hp == 0

    fun healFully(dex: Dex) {
        hp = maxHp(dex); status = null; sleepTurns = 0; fainted = false
        for (m in moves) m.pp = m.maxPp(dex)
    }

    fun expToNextLevel(dex: Dex): Int =
        if (level >= 100) 0 else species(dex).growthRate.expForLevel(level + 1) - exp

    fun copy(): Pokemon = kotlinx.serialization.json.Json.decodeFromString(serializer(),
        kotlinx.serialization.json.Json.encodeToString(serializer(), this))

    companion object {
        fun calcStat(sp: SpeciesData, stat: Stat, level: Int, iv: Int, ev: Int, nature: Nature): Int {
            val base = sp.baseStats[stat]
            if (stat == Stat.HP) {
                sp.maxHP?.let { return it }
                return (2 * base + iv + ev / 4) * level / 100 + level + 10
            }
            val raw = (2 * base + iv + ev / 4) * level / 100 + 5
            return (raw * nature.multiplier(stat)).toInt()
        }
    }
}

/** Creates Pokémon with legal randomised attributes. */
class PokemonFactory(private val dex: Dex) {
    private var uidCounter = System.nanoTime()

    fun nextUid(): Long = ++uidCounter

    fun create(
        speciesId: String,
        level: Int,
        rng: Rng,
        shinyOdds: Int = 4096,
        perfectIvs: Int = 0,
        moves: List<String>? = null,
        abilitySlot: String? = null,
        item: String? = null,
        nature: Nature? = null,
        hiddenAbilityChance: Double = 0.0,
    ): Pokemon {
        val sp = dex.species(speciesId)
        val ivs = StatSpread()
        for (s in Stat.entries) ivs[s] = rng.nextInt(32)
        if (perfectIvs > 0) Stat.entries.shuffled(rng.asKotlinRandom()).take(perfectIvs).forEach { ivs[it] = 31 }
        val slot = abilitySlot ?: run {
            if (hiddenAbilityChance > 0 && "H" in sp.abilities && rng.nextDouble() < hiddenAbilityChance) "H"
            else if ("1" in sp.abilities && rng.nextBoolean()) "1" else "0"
        }
        val gender = when {
            sp.genderless -> Gender.NONE
            rng.nextDouble() < sp.maleRatio -> Gender.MALE
            else -> Gender.FEMALE
        }
        val mon = Pokemon(
            speciesId = sp.id, level = level, nature = nature ?: Nature.entries[rng.nextInt(25)], ivs = ivs,
            abilitySlot = slot, heldItem = item, gender = gender, shiny = rng.nextInt(shinyOdds) == 0,
            exp = sp.growthRate.expForLevel(level), friendship = sp.baseFriendship,
            teraType = sp.requiredTeraType ?: sp.types[rng.nextInt(sp.types.size)], uid = nextUid(), metLevel = level,
        )
        val moveIds = moves ?: defaultMoves(sp.id, level)
        for (m in moveIds.take(4)) dex.moveOrNull(m)?.let { mon.moves.add(MoveSlot(it.id, it.pp)) }
        if (mon.moves.isEmpty()) mon.moves.add(MoveSlot("tackle", 35))
        mon.hp = mon.maxHp(dex)
        return mon
    }

    /** The last four level-up moves learnable at or below [level] (standard wild Pokémon moveset rule). */
    fun defaultMoves(speciesId: String, level: Int): List<String> {
        val ls = dex.learnset(speciesId) ?: return listOf("tackle")
        val learned = ls.levelUp.filter { it.level <= level }.map { it.move }.filter { dex.moves[it]?.isNonstandard != "LGPE" || true }
        val distinct = LinkedHashSet<String>()
        for (m in learned.asReversed()) { if (distinct.size >= 4) break; distinct.add(m) }
        return distinct.toList().asReversed().ifEmpty { listOf("tackle") }
    }
}

/** Experience, level-up move learning and evolution checks. */
class Progression(private val dex: Dex) {
    data class LevelUpResult(val newLevel: Int, val newMoves: List<String>, val statsBefore: Map<Stat, Int>, val statsAfter: Map<Stat, Int>)

    /** Gen 5+/7+ scaled experience formula. [participants] splits nothing (modern Exp. Share gives everyone full exp). */
    fun expYield(defeated: Pokemon, victorLevel: Int, trainerBattle: Boolean, luckyEgg: Boolean = false, traded: Boolean = false): Int {
        val sp = defeated.species(dex)
        val b = sp.baseExp.toDouble()
        val l = defeated.level.toDouble()
        val lp = victorLevel.toDouble()
        val base = b * l / 5.0
        val scale = Math.pow((2 * l + 10) / (l + lp + 10), 2.5)
        var exp = base * scale + 1
        if (trainerBattle) exp *= 1.5
        if (luckyEgg) exp *= 1.5
        if (traded) exp *= 1.5
        return exp.toInt().coerceAtLeast(1)
    }

    /** Adds experience, returning one result per level gained. HP is raised by the max-HP increase. */
    fun addExp(mon: Pokemon, amount: Int): List<LevelUpResult> {
        val results = ArrayList<LevelUpResult>()
        val sp = mon.species(dex)
        mon.exp = (mon.exp + amount).coerceAtMost(sp.growthRate.expForLevel(100))
        while (mon.level < 100 && mon.exp >= sp.growthRate.expForLevel(mon.level + 1)) {
            val before = Stat.entries.associateWith { mon.stat(dex, it) }
            mon.level++
            val after = Stat.entries.associateWith { mon.stat(dex, it) }
            if (!mon.isFainted) mon.hp = (mon.hp + (after.getValue(Stat.HP) - before.getValue(Stat.HP))).coerceAtMost(after.getValue(Stat.HP))
            results.add(LevelUpResult(mon.level, movesLearnedAt(mon.speciesId, mon.level), before, after))
        }
        return results
    }

    fun movesLearnedAt(speciesId: String, level: Int): List<String> =
        dex.learnset(speciesId)?.levelUp?.filter { it.level == level }?.map { it.move }?.distinct() ?: emptyList()

    /** Moves learned upon evolving (level 0 entries) plus moves at the current level. */
    fun movesOnEvolution(speciesId: String, level: Int): List<String> =
        dex.learnset(speciesId)?.levelUp?.filter { it.level == 0 || it.level == level }?.map { it.move }?.distinct() ?: emptyList()

    fun canLearnMachine(mon: Pokemon, moveId: String): Boolean {
        val id = toId(moveId)
        var sp: SpeciesData? = mon.species(dex)
        while (sp != null) {
            val ls = dex.learnset(sp.id)
            if (ls != null && (id in ls.machine || ls.levelUp.any { it.move == id })) return true
            sp = sp.prevo?.let { dex.speciesOrNull(it) }
        }
        return false
    }

    /** Move-relearner list: every level-up move at or below the current level not already known. */
    fun relearnableMoves(mon: Pokemon): List<String> {
        val ls = dex.learnset(mon.speciesId) ?: return emptyList()
        val known = mon.moves.map { it.id }.toSet()
        return ls.levelUp.filter { it.level <= mon.level }.map { it.move }.distinct().filter { it !in known }
    }

    data class EvolutionContext(
        val isNight: Boolean = false,
        val isDusk: Boolean = false,
        val raining: Boolean = false,
        val party: List<Pokemon> = emptyList(),
        val usedItem: String? = null,
        val linkingCord: Boolean = false,
        val locationTags: Set<String> = emptySet(),
        val region: String = "Tessera",
        val coins: Int = 0,
        val deviceUpsideDown: Boolean = false,
    )

    /** Returns the species this Pokémon would evolve into now, or null. Unusable region-specific evolutions are skipped. */
    fun evolutionTarget(mon: Pokemon, ctx: EvolutionContext): String? {
        val sp = mon.species(dex)
        if (mon.heldItem == "everstone" && ctx.usedItem == null && !ctx.linkingCord) return null
        for (evo in sp.evolutions) {
            val target = dex.speciesOrNull(evo.target) ?: continue
            if (evo.gender != null && (evo.gender == "M") != (mon.gender == Gender.MALE)) continue
            if (!conditionMet(mon, evo, ctx)) continue
            val ok = when (evo.method) {
                EvolutionMethod.LEVEL -> ctx.usedItem == null && !ctx.linkingCord && mon.level >= (evo.level ?: 1)
                EvolutionMethod.LEVEL_FRIENDSHIP -> ctx.usedItem == null && !ctx.linkingCord && mon.friendship >= 160
                EvolutionMethod.LEVEL_MOVE -> ctx.usedItem == null && !ctx.linkingCord && mon.moves.any { it.id == evo.move }
                EvolutionMethod.LEVEL_HOLD -> ctx.usedItem == null && !ctx.linkingCord && mon.heldItem == evo.item
                EvolutionMethod.LEVEL_EXTRA -> ctx.usedItem == null && !ctx.linkingCord && mon.level >= (evo.level ?: 1)
                EvolutionMethod.USE_ITEM -> ctx.usedItem != null && ctx.usedItem == evo.item
                // Trade evolutions use the official Linking Cord item (from Legends: Arceus) in single-player.
                EvolutionMethod.TRADE -> ctx.linkingCord && (evo.item == null || mon.heldItem == evo.item)
                EvolutionMethod.OTHER -> ctx.usedItem == null && !ctx.linkingCord && otherMet(mon, evo, ctx)
            }
            if (ok) return target.id
        }
        return null
    }

    private fun conditionMet(mon: Pokemon, evo: EvolutionData, ctx: EvolutionContext): Boolean {
        val code = evo.conditionCode ?: return true
        return code.split('&').all { c ->
            when {
                c == "night" -> ctx.isNight
                c == "day" -> !ctx.isNight
                c == "dusk" -> ctx.isDusk
                c == "rain" -> ctx.raining
                c == "atk>def" -> mon.stat(dex, Stat.ATK) > mon.stat(dex, Stat.DEF)
                c == "atk<def" -> mon.stat(dex, Stat.ATK) < mon.stat(dex, Stat.DEF)
                c == "atk=def" -> mon.stat(dex, Stat.ATK) == mon.stat(dex, Stat.DEF)
                c.startsWith("party:") -> ctx.party.any { it !== mon && it.speciesId == c.removePrefix("party:") }
                c.startsWith("partytype:") -> ctx.party.any { it !== mon && c.removePrefix("partytype:") in it.species(dex).types }
                c.startsWith("tradewith:") -> true // Linking Cord replaces the trade partner requirement.
                c.startsWith("location:") -> c.removePrefix("location:") in ctx.locationTags
                c == "movetype:Fairy+friendship" -> mon.friendship >= 160 && mon.moves.any { dex.moveOrNull(it.id)?.type == "Fairy" }
                // Inkay: hold the device upside-down; on desktop/tests the context flag substitutes.
                c == "upsidedown" -> ctx.deviceUpsideDown
                c.startsWith("region:") -> c.removePrefix("region:") == ctx.region
                else -> true // remaining codes are evaluated by otherMet
            }
        }
    }

    private fun otherMet(mon: Pokemon, evo: EvolutionData, ctx: EvolutionContext): Boolean {
        val code = evo.conditionCode ?: return false
        return when {
            code.startsWith("moveuses:") -> {
                val (_, move, n) = code.split(':'); (mon.counters["use:$move"] ?: 0) >= n.toInt() && mon.level >= (evo.level ?: 1)
            }
            code.startsWith("crits:") -> (mon.counters["crits"] ?: 0) >= code.removePrefix("crits:").toInt()
            code.startsWith("recoil:") -> (mon.counters["recoil"] ?: 0) >= code.removePrefix("recoil:").toInt()
            code.startsWith("hplost:") -> (mon.counters["hplost"] ?: 0) >= code.removePrefix("hplost:").toInt() && "dustybowl" in ctx.locationTags
            code.startsWith("steps:") -> (mon.counters["steps"] ?: 0) >= code.removePrefix("steps:").toInt()
            code.startsWith("coins:") -> ctx.coins >= code.removePrefix("coins:").toInt()
            code.startsWith("defeat:") -> (mon.counters["defeat:${code.split(':')[1]}"] ?: 0) >= code.split(':')[2].toInt()
            code == "fullmoon" -> ctx.usedItem == null && ctx.isNight && "fullmoon" in ctx.locationTags
            code == "spin" -> false
            code.startsWith("tower:") -> false
            else -> false
        }
    }
}
