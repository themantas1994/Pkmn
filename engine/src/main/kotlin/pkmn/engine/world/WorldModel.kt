package pkmn.engine.world

import kotlinx.serialization.Serializable

enum class Facing(val dx: Int, val dy: Int) {
    DOWN(0, 1), UP(0, -1), LEFT(-1, 0), RIGHT(1, 0);

    val opposite: Facing get() = when (this) { DOWN -> UP; UP -> DOWN; LEFT -> RIGHT; RIGHT -> LEFT }

    companion object {
        fun parse(s: String): Facing = when (s.lowercase()) {
            "down", "d", "s", "south" -> DOWN
            "up", "u", "n", "north" -> UP
            "left", "l", "w", "west" -> LEFT
            "right", "r", "e", "east" -> RIGHT
            else -> error("Unknown direction '$s'")
        }
    }
}

/** Gameplay behaviour of a tile. Derived from tile names by [TileRules]. */
enum class TileBehavior {
    FLOOR, SOLID, TALL_GRASS, WATER, WATERFALL, LEDGE_S, LEDGE_E, LEDGE_W, WARP, COUNTER, ICE, CUT_TREE, ROCK_SMASH, BOULDER, SIGN, PC,
    HEAL_MACHINE, SAND, CAVE_FLOOR, BOOKSHELF, TV, PUZZLE_SWITCH,
}

/** Field abilities used outside battle. Unlocked by badges; usable when any party Pokémon can learn the move. */
enum class FieldMove(val moveId: String, val badgeRequired: Int) {
    CUT("cut", 1), FLASH("flash", 2), ROCK_SMASH("rocksmash", 2), STRENGTH("strength", 3), SURF("surf", 4), FLY("fly", 5), WATERFALL("waterfall", 7);
}

@Serializable
data class Connection(val direction: String, val map: String, val offset: Int = 0)

/** A warp tile. [toMap] may be [BACK] to return to the tile the player entered the current map from. */
@Serializable
data class Warp(val x: Int, val y: Int, val toMap: String, val toX: Int = 0, val toY: Int = 0, val facing: String? = null) {
    companion object { const val BACK = "@back" }
}

@Serializable
data class NpcDef(
    val id: String,
    val x: Int,
    val y: Int,
    val sprite: String,
    val facing: String = "down",
    /** static | look | wander | path:<dirs> */
    val movement: String = "static",
    val script: String? = null,
    /** Trainer id from trainers.json; makes this NPC a trainer that challenges on sight. */
    val trainer: String? = null,
    val sight: Int = 0,
    /** NPC is hidden while this flag is set. */
    val hideIf: String? = null,
    /** NPC is only shown while this flag is set. */
    val showIf: String? = null,
    /** Inline dialogue used when no script is given. */
    val text: String? = null,
)

@Serializable
data class SignDef(val x: Int, val y: Int, val text: String? = null, val script: String? = null)

@Serializable
data class ItemPickup(val id: String, val x: Int, val y: Int, val item: String, val qty: Int = 1, val hidden: Boolean = false)

@Serializable
data class TriggerDef(
    val x: Int, val y: Int, val w: Int = 1, val h: Int = 1,
    val script: String,
    /** Trigger runs only while this flag is NOT set (scripts usually set it). */
    val unlessFlag: String? = null,
    /** Trigger runs only while this flag IS set. */
    val ifFlag: String? = null,
)

/** Tile replaced at runtime while [flag] is set (gym puzzles, opened gates, drained ponds). */
@Serializable
data class FlagTile(val flag: String, val x: Int, val y: Int, val tile: String)

/** A field obstacle requiring a field move (also drawn by the tile layer). */
@Serializable
data class ObstacleDef(val x: Int, val y: Int, val kind: String, val flag: String)

@Serializable
data class MapData(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val tileset: String = "world",
    /** Distinct tile names used by this map; [ground] and [overlay] index into it. */
    val tilePalette: List<String>,
    /** Row-major tile indices into [tilePalette]. */
    val ground: List<Int>,
    /** Optional row-major indices drawn above characters (-1 = none). */
    val overlay: List<Int> = emptyList(),
    val music: String = "route_early",
    /** Battle environment: plain, grass, cave, water, sand, snow, indoor, building. */
    val environment: String = "plain",
    val weather: String? = null,
    val indoor: Boolean = false,
    val dark: Boolean = false,
    /** Region map coordinates (8×8 px cells on the 256×224 region map). */
    val regionX: Int = -1,
    val regionY: Int = -1,
    /** Location name shown on the region map / popup (towns share one name for all their interiors). */
    val location: String = "",
    /** Fly destination for towns: landing tile. */
    val flyX: Int = -1,
    val flyY: Int = -1,
    val connections: List<Connection> = emptyList(),
    val warps: List<Warp> = emptyList(),
    val npcs: List<NpcDef> = emptyList(),
    val signs: List<SignDef> = emptyList(),
    val items: List<ItemPickup> = emptyList(),
    val triggers: List<TriggerDef> = emptyList(),
    val obstacles: List<ObstacleDef> = emptyList(),
    val flagTiles: List<FlagTile> = emptyList(),
    val encounters: String? = null,
    /** Script run every time the map is entered. */
    val onEnter: String? = null,
    /** Free-form tags (e.g. "pokecenter", "gym", "fullmoon", "magnetic", "dustybowl"). */
    val tags: List<String> = emptyList(),
    /**
     * Explicit destination class used to pick entrance tiles ([Entrances.destinationKind]): the name of an [EntranceKind]
     * in lower case (`forest`, `ruins`, ...). Null = inferred from environment, tags, music and name.
     */
    val zoneClass: String? = null,
) {
    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun tileAt(x: Int, y: Int): String = if (inBounds(x, y)) tilePalette[ground[y * width + x]] else ""
}

// --------------------------------------------------------------------- encounters

@Serializable
data class EncounterSlot(val species: String, val minLevel: Int, val maxLevel: Int, val weight: Int)

@Serializable
data class EncounterTable(
    val id: String,
    /** method -> time ("any", "morning", "day", "night") -> slots. Methods: grass, cave, surf, oldrod, goodrod, superrod, rocksmash, headbutt. */
    val methods: Map<String, Map<String, List<EncounterSlot>>>,
    /** Encounter rate per step (out of 256) by method. */
    val rates: Map<String, Int> = emptyMap(),
)

// --------------------------------------------------------------------- trainers

@Serializable
data class TrainerMon(
    val species: String,
    val level: Int,
    val moves: List<String> = emptyList(),
    val item: String? = null,
    val ability: String? = null,
    val nature: String? = null,
    val ivs: Int = -1,
    val evs: List<Int> = emptyList(),
    val tera: String? = null,
    val gmax: Boolean = false,
    val shiny: Boolean = false,
    val nickname: String? = null,
)

@Serializable
data class TrainerClass(val id: String, val name: String, val payout: Int, val music: String = "battle_trainer", val portrait: String = "", val gender: String = "m")

@Serializable
data class TrainerDef(
    val id: String,
    val trainerClass: String,
    val name: String,
    val team: List<TrainerMon>,
    val ai: Int = 1,
    val intro: String = "",
    val defeat: String = "",
    val after: String = "",
    /** Gimmicks this trainer may use: mega, zmove, dynamax, tera, ultraburst. */
    val gimmicks: List<String> = emptyList(),
    /** Healing items the trainer can use in battle (e.g. "hyperpotion", "fullrestore"), with counts. */
    val items: Map<String, Int> = emptyMap(),
    val music: String? = null,
    val portrait: String? = null,
    /** Level cap / rematch tier tag. */
    val tags: List<String> = emptyList(),
    /** Prize money override (otherwise class payout × highest level). */
    val prize: Int? = null,
)

// --------------------------------------------------------------------- shops & misc

@Serializable
data class ShopDef(val id: String, val items: List<String>, val prices: Map<String, Int> = emptyMap())

@Serializable
data class QuestDef(val id: String, val name: String, val description: String, val steps: List<String> = emptyList(), val main: Boolean = false)

/** Static world content bundle loaded at startup. */
@Serializable
data class WorldIndex(
    val startMap: String,
    val startX: Int,
    val startY: Int,
    val maps: List<String>,
    val version: Int = 1,
)

/** Assigns gameplay behaviour to tile names (data-driven by naming convention). */
object TileRules {
    private val solidPrefixes = listOf(
        "tree", "rock", "wall", "roof", "center_", "mart_", "gym_wall", "gym_roof", "gym_statue", "mountain", "fence", "bush", "table", "chair_back",
        "bed", "plant", "statue", "cave_wall", "cave_rock", "puzzle_wall", "ruins_wall", "window", "gym_window", "mart_shelf", "boulder", "cut_tree",
        "house_", "lab_", "machine", "tv", "pc", "bookshelf", "counter", "healing_machine", "sign", "pillar", "lamp", "cliff", "lava",
    )

    fun behavior(name: String): TileBehavior = when {
        name.isEmpty() -> TileBehavior.SOLID
        name == "tall_grass" || name.startsWith("tall_grass") -> TileBehavior.TALL_GRASS
        name.startsWith("waterfall") -> TileBehavior.WATERFALL
        name == "water" || name.startsWith("water_") || name == "cave_water" || name == "sea" -> TileBehavior.WATER
        name == "ledge_s" -> TileBehavior.LEDGE_S
        name == "ledge_e" -> TileBehavior.LEDGE_E
        name == "ledge_w" -> TileBehavior.LEDGE_W
        name == "door" || name.endsWith("_door") || name == "cave_entrance" || name.endsWith("_entrance") || name.startsWith("stairs") || name.startsWith("rug") ||
            name == "cave_ladder" || name.startsWith("warp") -> TileBehavior.WARP
        name == "counter" || name.startsWith("counter") -> TileBehavior.COUNTER
        name == "ice" -> TileBehavior.ICE
        name == "cut_tree" -> TileBehavior.CUT_TREE
        name == "rock_smash" || name == "smash_rock" -> TileBehavior.ROCK_SMASH
        name == "boulder" -> TileBehavior.BOULDER
        name == "sign" || name.endsWith("_sign") -> TileBehavior.SIGN
        name == "pc" -> TileBehavior.PC
        name == "healing_machine" -> TileBehavior.HEAL_MACHINE
        name == "bookshelf" -> TileBehavior.BOOKSHELF
        name == "tv" -> TileBehavior.TV
        name == "puzzle_switch" -> TileBehavior.PUZZLE_SWITCH
        name == "sand" -> TileBehavior.SAND
        name == "cave_floor" -> TileBehavior.CAVE_FLOOR
        solidPrefixes.any { name.startsWith(it) } -> TileBehavior.SOLID
        else -> TileBehavior.FLOOR
    }

    fun isSolid(b: TileBehavior) = b in setOf(TileBehavior.SOLID, TileBehavior.COUNTER, TileBehavior.CUT_TREE, TileBehavior.ROCK_SMASH,
        TileBehavior.BOULDER, TileBehavior.SIGN, TileBehavior.PC, TileBehavior.HEAL_MACHINE, TileBehavior.BOOKSHELF, TileBehavior.TV, TileBehavior.WATERFALL)
}
