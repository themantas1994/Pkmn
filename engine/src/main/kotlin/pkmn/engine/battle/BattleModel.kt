package pkmn.engine.battle

import pkmn.engine.data.*
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.MoveSlot
import pkmn.engine.mon.Pokemon
import pkmn.engine.util.toId

/** Battle gimmicks. A side may use each enabled gimmick at most once per battle; see [BattleRules]. */
enum class Gimmick { MEGA, ZMOVE, DYNAMAX, TERA, ULTRA_BURST }

/**
 * Battle format rules. The default ruleset is the unified "Crystalline" ruleset documented in docs/BATTLE_RULES.md:
 * Generation IX mechanics with every earlier official gimmick available when the format enables it.
 */
data class BattleRules(
    val allowedGimmicks: Set<Gimmick> = emptySet(),
    /** When true, a side may use only one gimmick of any kind per battle (Mega OR Z OR Dynamax OR Tera). */
    val oneGimmickPerBattle: Boolean = true,
    val canRun: Boolean = true,
    val canCatch: Boolean = true,
    val canUseItems: Boolean = true,
    val expEnabled: Boolean = true,
    /** Party-wide experience (modern Exp. Share). */
    val expShareAll: Boolean = true,
    val levelCap: Int = 100,
    val isTrainerBattle: Boolean = false,
    /** "Set" style: the player is not told which Pokémon the foe will send next. */
    val setMode: Boolean = true,
    val inverse: Boolean = false,
    /** The player's trainer id; Pokémon with another OT id (traded) earn 1.5× experience. 0 = no traded bonus. */
    val playerOtId: Int = 0,
    /** Capture settings (badge penalty, low-level bonus, Pokédex for Repeat Ball and critical captures). */
    val capture: CaptureRules = CaptureRules(),
    /**
     * Badge obedience level cap for the player's Pokémon ([Obedience]); null = every Pokémon obeys (8 badges, or a
     * battle without experience). Only checked when [expEnabled].
     */
    val obedienceCap: Int? = null,
) {
    companion object {
        val ALL_GIMMICKS = Gimmick.entries.toSet()
    }
}

enum class Controller { PLAYER, AI, WILD }

/** Per-effect persistent state attached to a holder (duration counters, stored data). */
class EffectState(var duration: Int = -1) {
    var source: BattleMon? = null
    var counter: Int = 0
    var turns: Int = 0
    var moveId: String? = null
    var hp: Int = 0
    var type: String? = null
    var flag: Boolean = false
    val data: MutableMap<String, Any?> = HashMap()
}

class Side(
    val index: Int,
    val name: String,
    team: List<Pokemon>,
    val controller: Controller,
    val dex: Dex,
    val trainerClass: String = "",
    val aiLevel: Int = 1,
    val gimmicks: Set<Gimmick> = emptySet(),
) {
    val mons: List<BattleMon> = team.map { BattleMon(it, this, dex) }
    var active: BattleMon? = null
    lateinit var foe: Side
    val conditions = LinkedHashMap<String, EffectState>()
    /** Slot conditions (Wish, Healing Wish, ...) keyed by id. */
    val slotConditions = LinkedHashMap<String, EffectState>()
    val usedGimmicks = HashSet<Gimmick>()
    var faintedLastTurn: Boolean = false
    var faintedThisTurn: Boolean = false
    /** Total number of times a Pokémon on this side fainted (Last Respects, Supreme Overlord). */
    var totalFainted: Int = 0

    val isPlayer get() = controller == Controller.PLAYER
    val isWild get() = controller == Controller.WILD
    fun hasCondition(id: String) = id in conditions
    fun alive() = mons.filter { !it.fainted }
    fun canSwitchTo() = mons.filter { !it.fainted && it !== active }

    fun canUseGimmick(g: Gimmick, rules: BattleRules): Boolean {
        if (g !in gimmicks || g !in rules.allowedGimmicks) return false
        if (g in usedGimmicks) return false
        // Ultra Burst is an independent transformation in its original games and does not consume another gimmick.
        if (rules.oneGimmickPerBattle && g != Gimmick.ULTRA_BURST && usedGimmicks.any { it != Gimmick.ULTRA_BURST }) return false
        return true
    }
}

/** Battle-time state for a Pokémon. Persistent changes (HP, status, PP, item use) write through to [mon]. */
class BattleMon(val mon: Pokemon, val side: Side, val dex: Dex) {
    /** Species when the battle started (restored afterwards). */
    val originalSpecies: String = mon.speciesId
    var species: SpeciesData = dex.species(mon.speciesId)
        private set
    var types: List<String> = species.types
    var addedType: String? = null
    var ability: String = mon.ability(dex)
    var baseAbility: String = ability
    var abilityState = EffectState()
    var item: String? = mon.heldItem
    var itemState = EffectState()
    val originalItem: String? = mon.heldItem
    var lastItem: String? = null
    var itemKnockedOff: Boolean = false
    var usedItemThisBattle = false
    /** Turn on which the held item was last consumed (Pickup). */
    var itemConsumedTurn = -1

    val boosts = IntArray(7) // atk, def, spa, spd, spe, accuracy, evasion
    val volatiles = LinkedHashMap<String, EffectState>()
    var statusState = EffectState()

    /** Move slots used in battle. Normally the persistent slots; Transform/Mimic replace them with temporary copies. */
    var moveSlots: MutableList<MoveSlot> = mon.moves
    var transformed = false

    var lastMove: String? = null
    var lastMoveTarget: BattleMon? = null
    var lastMoveFailed = false
    var movedThisTurn = false
    var hurtThisTurn = false
    var activeTurns = 0
    var activeMoveActions = 0
    var isStarted = false
    var switchFlag: String? = null
    var forceSwitchFlag = false
    var lastDamage = 0
    var lastDamagedBy: DamageRecord? = null
    /**
     * Attacker credited if this Pokémon faints now: set by move damage, cleared by any indirect HP loss (poison, weather,
     * hazards, recoil...), so only a direct attack KO triggers Moxie, Beast Boost, Destiny Bond or Grudge.
     */
    var koCredit: BattleMon? = null
    /** Move or effect id of the last HP loss (see [koCredit]). */
    var lastHpLossEffect: String? = null
    var timesAttacked = 0
    var statsRaisedThisTurn = false
    var statsLoweredThisTurn = false
    var critsThisBattle = 0

    var dynamaxTurns = 0
    var isGigantamax = false
    var terastallized: String? = null
    var stellarBoostedTypes = HashSet<String>()
    /** Species to revert to when Dynamax / Mega-style battle formes end. */
    var preTransformSpecies: SpeciesData? = null

    /** Foe Pokémon this one has faced while active (experience distribution). */
    val facedFoes = HashSet<Long>()

    private var statCache: IntArray = computeStats()

    val name: String get() = mon.nickname ?: dex.species(mon.speciesId).name
    /** Party member this Pokémon is disguised as (Illusion); null when undisguised. */
    var illusion: BattleMon? = null
    /** Name shown to the players (the Illusion disguise if any). */
    val shownName: String get() = illusion?.name ?: name
    val fainted: Boolean get() = mon.hp <= 0
    var hp: Int
        get() = mon.hp
        set(v) { mon.hp = v.coerceIn(0, maxHp) }
    val baseMaxHp: Int get() = mon.maxHp(dex)
    val maxHp: Int get() = if (dynamaxTurns > 0) baseMaxHp * (150 + 5 * mon.dynamaxLevel) / 100 else baseMaxHp
    val level get() = mon.level
    val status: MajorStatus? get() = mon.status
    val isActive get() = side.active === this
    val isDynamaxed get() = dynamaxTurns > 0
    val hpFraction get() = hp.toDouble() / maxHp

    fun hasType(t: String) = t in effectiveTypes()

    fun effectiveTypes(): List<String> {
        terastallized?.let { if (it != "Stellar") return listOf(it) }
        var base = types.ifEmpty { listOf("???") }
        if ("roost" in volatiles && "Flying" in base) base = (base - "Flying").ifEmpty { listOf("Normal") }
        return if (addedType != null && addedType !in base) base + addedType!! else base
    }

    fun hasVolatile(id: String) = id in volatiles
    fun hasAbility(id: String) = ability == id
    fun hasItem(id: String) = item == id

    /** Unboosted stat (from current species, with nature/IVs/EVs). */
    fun rawStat(stat: Stat): Int {
        if (stat == Stat.HP) return maxHp
        var s = stat
        // Power Trick and Power Shift each swap the raw Attack and Defense stats (using both cancels out).
        if (("powertrick" in volatiles) != ("powershift" in volatiles)) s = when (s) { Stat.ATK -> Stat.DEF; Stat.DEF -> Stat.ATK; else -> s }
        volatiles["split_${s.id}"]?.let { return it.counter }
        return statCache[s.ordinal - 1]
    }

    fun recomputeStats() { statCache = computeStats() }

    private fun computeStats(): IntArray = IntArray(5) { i ->
        val st = Stat.NON_HP[i]
        Pokemon.calcStat(species, st, mon.level, mon.ivs[st], mon.evs[st], mon.statNature)
    }

    /** Changes the current (battle) species, updating types, ability (optionally) and stats. */
    fun setSpecies(sp: SpeciesData, changeAbility: Boolean = true, keepHpRatio: Boolean = false) {
        val ratio = hpFraction
        species = sp
        types = sp.types
        if (changeAbility) {
            val slot = mon.abilitySlot
            ability = sp.abilities[slot] ?: sp.abilities["0"] ?: ability
            abilityState = EffectState()
        }
        recomputeStats()
        if (keepHpRatio) hp = (maxHp * ratio).toInt().coerceAtLeast(1)
    }

    fun boost(stat: String): Int = boosts[boostIndex(stat)]

    fun moveSlot(id: String) = moveSlots.firstOrNull { it.id == id }

    override fun toString() = "${side.name}'s $name"

    companion object {
        val BOOST_IDS = listOf("atk", "def", "spa", "spd", "spe", "accuracy", "evasion")
        fun boostIndex(id: String) = BOOST_IDS.indexOf(id).also { require(it >= 0) { "bad boost $id" } }
        fun boostLabel(id: String) = when (id) {
            "atk" -> "Attack"; "def" -> "Defense"; "spa" -> "Sp. Atk"; "spd" -> "Sp. Def"; "spe" -> "Speed"
            "accuracy" -> "accuracy"; "evasion" -> "evasiveness"; else -> id
        }
    }
}

data class DamageRecord(val source: BattleMon, val amount: Int, val category: String, val turn: Int)

class FieldState {
    var weather: String? = null
    var weatherState = EffectState()
    var terrain: String? = null
    var terrainState = EffectState()
    val pseudoWeather = LinkedHashMap<String, EffectState>()
    fun has(id: String) = id in pseudoWeather
}

/** A move as it is being executed. Starts as a copy of [MoveData] and may be modified by effects. */
class ActiveMove(val data: MoveData) {
    val id get() = data.id
    var name: String = data.name
    var type: String = data.type
    var category: String = data.category
    var basePower: Int = data.basePower
    var accuracy: Int = data.accuracy
    var priority: Int = data.priority
    var target: String = data.target
    var flags: MutableSet<String> = data.flags.toMutableSet()
    var secondaries: MutableList<SecondaryEffect> = data.secondaries.toMutableList()
    var critRatio: Int = data.critRatio
    var multihit: List<Int>? = data.multihit
    var hitCount = 0
    var ignoreAbility = data.ignoreAbility
    var hasSheerForce = false
    var isZ = false
    var isMax = false
    var zBaseMove: String? = null
    var willCrit = data.willCrit
    var infiltrates = false
    var sourceEffect: String? = null
    /** Total damage dealt by this use (Shell Bell, Parental Bond, drain). */
    var totalDamage = 0
    var spreadHit = false
    val isStatus get() = category == "Status"
    fun hasFlag(f: String) = f in flags
    /** Ad-hoc per-use data for move scripts. */
    val data2: MutableMap<String, Any?> = HashMap()
}

/** Context of a single hit of a move against a target, shared by all damage-related hooks. */
class MoveCtx(val user: BattleMon, val target: BattleMon, val move: ActiveMove) {
    var crit = false
    var effectiveness = 1.0
    var hitIndex = 0
    var damage = 0
    var substituteHit = false
}

/** Multiplier accumulator using the 4096-based fixed-point chaining used by the official games. */
class Mod {
    var value: Int = 4096
        private set

    fun chain(multiplier: Double) {
        val m = Math.round(multiplier * 4096).toInt()
        value = ((value.toLong() * m + 2048) shr 12).toInt()
    }

    fun apply(base: Int): Int = pokeRound(base.toLong() * value / 4096.0)

    companion object {
        /** Round half up (JavaScript Math.round): Showdown's recoil and Gen V+ drain amounts. */
        fun roundHalfUp(x: Double): Int = Math.floor(x + 0.5).toInt()

        /** Round half down (official "pokeRound"). */
        fun pokeRound(x: Double): Int {
            val f = Math.floor(x)
            return if (x - f > 0.5) (f + 1).toInt() else f.toInt()
        }
    }
}

// ------------------------------------------------------------------- decisions

sealed class Choice {
    data class UseMove(val moveIndex: Int, val gimmick: Gimmick? = null) : Choice()
    data class Switch(val teamIndex: Int) : Choice()
    data class UseItem(val itemId: String, val teamIndex: Int = -1, val moveIndex: Int = -1) : Choice()
    object Run : Choice()
    /** Used for Struggle when no move is usable, and for locked moves (Outrage, charge turns). */
    object Pass : Choice()
}

/** What a side must decide next. */
sealed class Request {
    data class Move(
        val active: BattleMon,
        val usableMoves: List<Boolean>,
        val canSwitch: Boolean,
        val gimmicks: Set<Gimmick>,
        /** Non-null when the Pokémon is locked into an action (recharge, charge turn, rampage). */
        val lockedMove: String?,
    ) : Request()
    data class SwitchIn(val forced: Boolean) : Request()
    object Wait : Request()
}

enum class BattleOutcome { ONGOING, WIN, LOSE, DRAW, RAN, CAUGHT, FOE_FLED }

fun moveIdOf(name: String) = toId(name)
