package pkmn.engine.world

/**
 * What an entrance (warp) tile looks like, or what a map is when seen as a destination.
 * A house door leading into a forest, or a cave mouth leading into a shop, is a content bug: the tile a player walks
 * through should show the kind of place on the other side. See docs/WORLD_VALIDATION.md.
 */
enum class EntranceKind { HOUSE, BUILDING, CENTER, MART, GYM, LAB, CAVE, FOREST, RUINS, MINE, TOWER, GATE, STAIRS, LADDER, EXIT_MAT, OTHER }

/**
 * Classification of entrance tiles and destination maps, and the compatibility rule between them.
 *
 * Tile -> kind (only warp-behaving tiles are entrance tiles):
 * `door` HOUSE, `building_door` BUILDING, `lab_door` LAB, `center_door` CENTER, `mart_door` MART, `gym_door` GYM,
 * `cave_entrance` CAVE, `forest_entrance` FOREST, `ruins_entrance` RUINS, `mine_entrance` MINE, `tower_door` TOWER,
 * `gate_door` GATE, `stairs*` STAIRS, `cave_ladder`/`ladder*` LADDER, `rug*` EXIT_MAT, any other warp tile OTHER.
 *
 * Map -> kind: the explicit `class` line of the map source (`MapData.zoneClass`) wins. Otherwise the kind is inferred
 * from the environment, tags, music and name: cave maps are CAVE (MINE / RUINS / TOWER by name), indoor maps are
 * HOUSE, or BUILDING for the `building` environment, CENTER / MART / GYM / LAB by tag, music or name, forests
 * (music `forest`, or a wood/forest/thicket/glade/grove name) are FOREST and every other outdoor map is OTHER
 * (open air: towns and routes).
 */
object Entrances {
    fun kindOfTile(tile: String): EntranceKind? {
        if (TileRules.behavior(tile) != TileBehavior.WARP) return null
        return when {
            tile == "door" -> EntranceKind.HOUSE
            tile == "building_door" -> EntranceKind.BUILDING
            tile == "lab_door" -> EntranceKind.LAB
            tile == "center_door" -> EntranceKind.CENTER
            tile == "mart_door" -> EntranceKind.MART
            tile == "gym_door" -> EntranceKind.GYM
            tile == "cave_entrance" -> EntranceKind.CAVE
            tile == "forest_entrance" -> EntranceKind.FOREST
            tile == "ruins_entrance" -> EntranceKind.RUINS
            tile == "mine_entrance" -> EntranceKind.MINE
            tile == "tower_door" -> EntranceKind.TOWER
            tile == "gate_door" -> EntranceKind.GATE
            tile.startsWith("stairs") -> EntranceKind.STAIRS
            tile == "cave_ladder" || tile.startsWith("ladder") -> EntranceKind.LADDER
            tile.startsWith("rug") -> EntranceKind.EXIT_MAT
            else -> EntranceKind.OTHER
        }
    }

    /** Parses a `class` value from a map source (case-insensitive); null when it is not an [EntranceKind] name. */
    fun parseKind(s: String): EntranceKind? = EntranceKind.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }

    private val MINE_WORDS = Regex("""\b(mine|mines|shaft|quarry)\b""")
    private val RUINS_WORDS = Regex("""(ruins|catacomb|sanctum|temple)""")
    private val TOWER_WORDS = Regex("""(tower|lighthouse|pillar)""")
    private val GATE_WORDS = Regex("""\b(gate|gatehouse)\b""")
    private val INDOOR_RUINS_WORDS = Regex("""(catacomb|sanctum|temple)""")
    private val FOREST_WORDS = Regex("""(wood|forest|thicket|glade|grove)""")
    private val LAB_WORDS = Regex("""\blab\b""")

    fun destinationKind(map: MapData): EntranceKind {
        map.zoneClass?.let { parseKind(it)?.let { k -> return k } }
        val words = "${map.id.replace('_', ' ')} ${map.name}".lowercase()
        if (map.environment == "cave") return when {
            MINE_WORDS.containsMatchIn(words) -> EntranceKind.MINE
            RUINS_WORDS.containsMatchIn(words) -> EntranceKind.RUINS
            TOWER_WORDS.containsMatchIn(words) -> EntranceKind.TOWER
            else -> EntranceKind.CAVE
        }
        if (map.indoor || map.environment == "indoor" || map.environment == "building") return when {
            "pokecenter" in map.tags || map.music == "pokecenter" || "pokémon center" in words || "pokemon center" in words -> EntranceKind.CENTER
            map.music == "mart" || map.music == "pokemart" || "mart" in words || "dept" in words -> EntranceKind.MART
            "gym" in map.tags -> EntranceKind.GYM
            LAB_WORDS.containsMatchIn(words) -> EntranceKind.LAB
            TOWER_WORDS.containsMatchIn(words) -> EntranceKind.TOWER
            GATE_WORDS.containsMatchIn(words) -> EntranceKind.GATE
            INDOOR_RUINS_WORDS.containsMatchIn(words) -> EntranceKind.RUINS
            map.environment == "building" -> EntranceKind.BUILDING
            else -> EntranceKind.HOUSE
        }
        return if (map.music == "forest" || FOREST_WORDS.containsMatchIn(words)) EntranceKind.FOREST else EntranceKind.OTHER
    }

    /**
     * Tile names a warp at (x, y) can be seen as: the ground tile and every flag-dependent replacement (`tileif`).
     * Only the warp-behaving ones count: a warp on a wall that a flag turns into stairs is a gated warp, not a broken one.
     */
    fun warpTilesAt(map: MapData, x: Int, y: Int): List<String> =
        (listOf(map.tileAt(x, y)) + map.flagTiles.filter { it.x == x && it.y == y }.map { it.tile })
            .distinct().filter { TileRules.behavior(it) == TileBehavior.WARP }

    /** Open air: a town or route, as opposed to any enclosed or themed zone (building, cave, forest, ruins, tower...). */
    fun isOpenAir(map: MapData): Boolean = !map.indoor && destinationKind(map) == EntranceKind.OTHER

    private val INTERIOR = setOf(EntranceKind.HOUSE, EntranceKind.BUILDING, EntranceKind.CENTER, EntranceKind.MART, EntranceKind.GYM, EntranceKind.LAB)
    private val NATURAL_CAVES = setOf(EntranceKind.CAVE, EntranceKind.MINE, EntranceKind.RUINS)

    private fun isInterior(map: MapData) = map.indoor || destinationKind(map) in INTERIOR

    /** Entrance tile kinds that visually fit a destination of kind [dest] when walking in from the open air. */
    fun acceptedFor(dest: EntranceKind): Set<EntranceKind> = when (dest) {
        EntranceKind.HOUSE -> setOf(EntranceKind.HOUSE)
        EntranceKind.BUILDING -> setOf(EntranceKind.BUILDING, EntranceKind.HOUSE)
        EntranceKind.LAB -> setOf(EntranceKind.LAB, EntranceKind.HOUSE, EntranceKind.BUILDING)
        EntranceKind.CENTER, EntranceKind.MART, EntranceKind.GYM, EntranceKind.CAVE, EntranceKind.FOREST, EntranceKind.RUINS,
        EntranceKind.MINE, EntranceKind.TOWER, EntranceKind.GATE -> setOf(dest)
        EntranceKind.STAIRS, EntranceKind.LADDER, EntranceKind.EXIT_MAT -> setOf(dest)
        EntranceKind.OTHER -> EntranceKind.entries.toSet() - setOf(EntranceKind.OTHER)
    }

    /**
     * Whether an entrance tile [tile] (on map [from]) is an acceptable visual for a warp into [dest].
     * * Open air to a zone (door into a house, cave mouth into a cave...): the tile must match the destination class.
     * * Open air to open air (a pass through a cliff): a cave mouth or a gate.
     * * Zone to open air (an exit): inside buildings a door mat (or the building's own door), inside caves, mines, ruins
     *   and forests the same opening they are entered by.
     * * Zone to zone (stairs, ladders, inner doorways): stairs and ladders (not into or out of a forest), the tile of either zone's
     *   class, door mats inside buildings.
     */
    fun compatible(tile: String, from: MapData, dest: MapData): Boolean {
        val k = kindOfTile(tile) ?: return false
        val fk = destinationKind(from)
        val dk = destinationKind(dest)
        val fromOpen = isOpenAir(from)
        val destOpen = isOpenAir(dest)
        return when {
            fromOpen && destOpen -> k == EntranceKind.CAVE || k == EntranceKind.GATE
            fromOpen -> k in acceptedFor(dk)
            destOpen -> if (isInterior(from)) k == EntranceKind.EXIT_MAT || k in acceptedFor(fk)
            else k in acceptedFor(fk) || (fk in NATURAL_CAVES && k == EntranceKind.CAVE)
            else -> ((k == EntranceKind.STAIRS || k == EntranceKind.LADDER) && EntranceKind.FOREST != fk && EntranceKind.FOREST != dk) ||
                k in acceptedFor(dk) || k in acceptedFor(fk) ||
                (k == EntranceKind.EXIT_MAT && isInterior(from)) || (k == EntranceKind.CAVE && fk in NATURAL_CAVES && dk in NATURAL_CAVES)
        }
    }
}
