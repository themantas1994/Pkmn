package pkmn.engine.data

import kotlinx.serialization.Serializable

@Serializable
data class BaseStats(val hp: Int, val atk: Int, val def: Int, val spa: Int, val spd: Int, val spe: Int) {
    operator fun get(stat: Stat): Int = when (stat) {
        Stat.HP -> hp; Stat.ATK -> atk; Stat.DEF -> def; Stat.SPA -> spa; Stat.SPD -> spd; Stat.SPE -> spe
    }
    val total: Int get() = hp + atk + def + spa + spd + spe
    companion object { val ZERO = BaseStats(0, 0, 0, 0, 0, 0) }
}

enum class Stat(val id: String, val label: String) {
    HP("hp", "HP"), ATK("atk", "Attack"), DEF("def", "Defense"), SPA("spa", "Sp. Atk"), SPD("spd", "Sp. Def"), SPE("spe", "Speed");
    companion object {
        fun byId(id: String): Stat? = entries.firstOrNull { it.id == id }
        val NON_HP = listOf(ATK, DEF, SPA, SPD, SPE)
    }
}

/** Classification of species entries. Base species have exactly one entry per National Dex number. */
enum class FormKind { BASE, REGIONAL, ALTERNATE, MEGA, PRIMAL, GIGANTAMAX, BATTLE_ONLY, FUSION, ULTRA_BURST, TOTEM }

enum class EvolutionMethod { LEVEL, LEVEL_FRIENDSHIP, USE_ITEM, TRADE, LEVEL_MOVE, LEVEL_HOLD, LEVEL_EXTRA, OTHER }

@Serializable
data class EvolutionData(
    val target: String,
    val method: EvolutionMethod,
    val level: Int? = null,
    val item: String? = null,
    val move: String? = null,
    val condition: String? = null,
    /** Normalised machine-readable condition (e.g. "day", "night", "atk>def"), null when unconditional. */
    val conditionCode: String? = null,
    val gender: String? = null,
)

@Serializable
data class SpeciesData(
    val id: String,
    val num: Int,
    val name: String,
    val baseSpecies: String,
    val forme: String? = null,
    val formKind: FormKind = FormKind.BASE,
    val types: List<String>,
    val baseStats: BaseStats,
    /** Ability slot ("0", "1", "H", "S") -> ability id. */
    val abilities: Map<String, String>,
    /** Fraction of males (0..1); -1 for genderless. */
    val maleRatio: Double,
    val heightm: Double,
    val weightkg: Double,
    val color: String = "",
    val eggGroups: List<String> = emptyList(),
    val prevo: String? = null,
    val evolutions: List<EvolutionData> = emptyList(),
    val otherFormes: List<String> = emptyList(),
    /** For battle-only formes: the out-of-battle forme(s) it comes from. */
    val battleOnly: List<String> = emptyList(),
    val requiredItems: List<String> = emptyList(),
    val requiredMove: String? = null,
    val requiredAbility: String? = null,
    val changesFrom: String? = null,
    /** Name of the G-Max move if this species can Gigantamax. */
    val canGigantamax: String? = null,
    val cannotDynamax: Boolean = false,
    /** Fixed maximum HP (Shedinja). */
    val maxHP: Int? = null,
    val requiredTeraType: String? = null,
    /** Purely cosmetic variants that share this entry's data (e.g. Vivillon patterns). */
    val cosmeticFormes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val catchRate: Int = 45,
    val baseExp: Int = 64,
    val growthRate: GrowthRate = GrowthRate.MEDIUM_FAST,
    val evYield: BaseStats = BaseStats.ZERO,
    val baseFriendship: Int = 50,
    val hatchCycles: Int = 20,
    val isBaby: Boolean = false,
    val gen: Int = 1,
    val isNonstandard: String? = null,
) {
    val isLegendary get() = "Legendary" in tags || "Sub-Legendary" in tags || "Restricted Legendary" in tags
    val isMythical get() = "Mythical" in tags
    val genderless get() = maleRatio < 0
    val isBattleOnly get() = battleOnly.isNotEmpty() || formKind == FormKind.MEGA || formKind == FormKind.PRIMAL ||
        formKind == FormKind.GIGANTAMAX || formKind == FormKind.ULTRA_BURST
}

@Serializable
data class SelfEffect(
    val boosts: Map<String, Int> = emptyMap(),
    val volatileStatus: String? = null,
)

@Serializable
data class SecondaryEffect(
    val chance: Int = 100,
    val status: String? = null,
    val volatileStatus: String? = null,
    val boosts: Map<String, Int> = emptyMap(),
    val self: SelfEffect? = null,
)

@Serializable
data class ZMoveData(val basePower: Int? = null, val effect: String? = null, val boost: Map<String, Int> = emptyMap())

@Serializable
data class MoveData(
    val id: String,
    val num: Int,
    val name: String,
    val type: String,
    val category: String,
    val basePower: Int,
    /** 0 means the move cannot miss. */
    val accuracy: Int,
    val pp: Int,
    val priority: Int = 0,
    val target: String,
    val flags: Set<String> = emptySet(),
    val secondaries: List<SecondaryEffect> = emptyList(),
    val self: SelfEffect? = null,
    val boosts: Map<String, Int> = emptyMap(),
    val status: String? = null,
    val volatileStatus: String? = null,
    val sideCondition: String? = null,
    val slotCondition: String? = null,
    val weather: String? = null,
    val terrain: String? = null,
    val pseudoWeather: String? = null,
    val drain: List<Int>? = null,
    val recoil: List<Int>? = null,
    val heal: List<Int>? = null,
    val multihit: List<Int>? = null,
    val multiaccuracy: Boolean = false,
    val critRatio: Int = 1,
    val willCrit: Boolean = false,
    val selfSwitch: String? = null,
    val forceSwitch: Boolean = false,
    val selfdestruct: String? = null,
    val ohko: String? = null,
    /** "true" for all types or a comma-separated list of types whose immunity is ignored. */
    val ignoreImmunity: String? = null,
    val ignoreDefensive: Boolean = false,
    val ignoreEvasion: Boolean = false,
    val ignoreAbility: Boolean = false,
    val breaksProtect: Boolean = false,
    val stallingMove: Boolean = false,
    val sleepUsable: Boolean = false,
    val thawsTarget: Boolean = false,
    val hasCrashDamage: Boolean = false,
    val mindBlownRecoil: Boolean = false,
    val struggleRecoil: Boolean = false,
    val overrideOffensivePokemon: String? = null,
    val overrideOffensiveStat: String? = null,
    val overrideDefensiveStat: String? = null,
    /** "level" or a fixed number of HP. */
    val fixedDamage: String? = null,
    /** Z-Crystal id for Z-Moves. */
    val isZ: String? = null,
    /** "true" for generic Max Moves or the species name for G-Max moves. */
    val isMax: String? = null,
    val zMove: ZMoveData? = null,
    val maxMovePower: Int? = null,
    val nonGhostTarget: String? = null,
    val tracksTarget: Boolean = false,
    val smartTarget: Boolean = false,
    val noPPBoosts: Boolean = false,
    val isNonstandard: String? = null,
    val desc: String = "",
    val shortDesc: String = "",
) {
    val isStatus get() = category == "Status"
    val isPhysical get() = category == "Physical"
    val isSpecial get() = category == "Special"
    fun hasFlag(flag: String) = flag in flags
}

@Serializable
data class AbilityData(
    val id: String,
    val num: Int,
    val name: String,
    val rating: Double = 0.0,
    val flags: Set<String> = emptySet(),
    val isNonstandard: String? = null,
    val desc: String = "",
    val shortDesc: String = "",
)

enum class ItemCategory { BALL, MEDICINE, BERRY, HELD, BATTLE, TM, EVOLUTION, KEY, MEGA_STONE, Z_CRYSTAL, VALUABLE, MAIL, OTHER }

enum class BagPocket { ITEMS, MEDICINE, BALLS, BERRIES, TMS, KEY }

@Serializable
data class ItemData(
    val id: String,
    val num: Int,
    val name: String,
    val category: ItemCategory,
    val pocket: BagPocket,
    val price: Int = 0,
    val gen: Int = 0,
    val flingPower: Int = 0,
    /** Base species id -> Mega/Primal forme id. */
    val megaStone: Map<String, String> = emptyMap(),
    val itemUser: List<String> = emptyList(),
    /** "true" for type Z-Crystals, else the name of the exclusive Z-Move. */
    val zMove: String? = null,
    val zMoveType: String? = null,
    val zMoveFrom: List<String> = emptyList(),
    val isBerry: Boolean = false,
    val naturalGiftPower: Int = 0,
    val naturalGiftType: String? = null,
    val onPlate: String? = null,
    val onMemory: String? = null,
    val onDrive: String? = null,
    val forcedForme: String? = null,
    val isPokeball: Boolean = false,
    val isGem: Boolean = false,
    val isNonstandard: String? = null,
    /** Present in the battle simulator data (held/battle effect exists). */
    val hasBattleData: Boolean = false,
    val desc: String = "",
    val shortDesc: String = "",
)

@Serializable
data class LearnsetData(
    val species: String,
    /** Level-up moves (level, move id) from the most recent generation with data. Level 0 = learned on evolution. */
    val levelUp: List<LevelMove> = emptyList(),
    /** Generation the level-up list was taken from. */
    val levelUpGen: Int = 9,
    /** Moves learnable by TM/TR/tutor in any generation. */
    val machine: List<String> = emptyList(),
    val egg: List<String> = emptyList(),
)

@Serializable
data class LevelMove(val level: Int, val move: String)

@Serializable
enum class GrowthRate { SLOW, MEDIUM_FAST, FAST, MEDIUM_SLOW, ERRATIC, FLUCTUATING;
    /** Total experience required to reach [level]. */
    fun expForLevel(level: Int): Int {
        val n = level.coerceIn(1, 100)
        if (n == 1) return 0
        val n3 = n.toLong() * n * n
        val v: Long = when (this) {
            FAST -> 4 * n3 / 5
            MEDIUM_FAST -> n3
            MEDIUM_SLOW -> (6 * n3) / 5 - 15L * n * n + 100L * n - 140
            SLOW -> 5 * n3 / 4
            ERRATIC -> when {
                n < 50 -> n3 * (100 - n) / 50
                n < 68 -> n3 * (150 - n) / 100
                n < 98 -> n3 * ((1911 - 10 * n) / 3) / 500
                else -> n3 * (160 - n) / 100
            }
            FLUCTUATING -> when {
                n < 15 -> n3 * ((n + 1) / 3 + 24) / 50
                n < 36 -> n3 * (n + 14) / 50
                else -> n3 * (n / 2 + 32) / 50
            }
        }
        return v.coerceAtLeast(0).toInt()
    }
}

@Serializable
data class TypeChartData(
    val types: List<String>,
    /** attackingType -> defendingType -> multiplier (0, 0.5, 1, 2). */
    val chart: Map<String, Map<String, Double>>,
)

@Serializable
data class ContentProvenance(
    val name: String,
    val source: String,
    val license: String,
    val files: Map<String, String>,
    val retrieved: String,
    val notes: String = "",
)

@Serializable
data class ContentManifest(
    val contentVersion: Int,
    val rosterCutoff: String,
    val expectedSpeciesCount: Int,
    val provenance: List<ContentProvenance>,
)
