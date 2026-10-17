package pkmn.engine.data

import kotlinx.serialization.json.Json
import pkmn.engine.util.toId

/** Supplies raw text for bundled content files (e.g. "data/species.json"). Implemented per platform. */
fun interface ContentSource {
    fun read(path: String): String?
}

/** File-system content source used by tools, tests and the desktop launcher. */
class DirectoryContentSource(private val root: java.io.File) : ContentSource {
    override fun read(path: String): String? = java.io.File(root, path).takeIf { it.isFile }?.readText()
}

val ContentJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

class TypeChart(data: TypeChartData) {
    val types: List<String> = data.types
    private val index = types.withIndex().associate { it.value to it.index }
    private val table = Array(types.size) { a -> DoubleArray(types.size) { d -> data.chart[types[a]]?.get(types[d]) ?: 1.0 } }

    fun isType(name: String) = name in index

    /** Multiplier of an attack of [attackType] against a single defending type. */
    fun effectiveness(attackType: String, defendType: String): Double {
        val a = index[attackType] ?: return 1.0
        val d = index[defendType] ?: return 1.0
        return table[a][d]
    }

    fun effectiveness(attackType: String, defendTypes: List<String>): Double {
        var m = 1.0
        for (t in defendTypes) m *= effectiveness(attackType, t)
        return m
    }
}

/**
 * Central, immutable registry of all Pokémon content.
 * Learnsets are parsed lazily because they are only needed when moves are learned.
 */
class Dex(
    speciesList: List<SpeciesData>,
    moveList: List<MoveData>,
    abilityList: List<AbilityData>,
    itemList: List<ItemData>,
    val typeChart: TypeChart,
    private val learnsetLoader: () -> List<LearnsetData>,
    val manifest: ContentManifest? = null,
    private val dexTextLoader: () -> List<DexEntryText> = { emptyList() },
) {
    val species: Map<String, SpeciesData> = speciesList.associateBy { it.id }
    val moves: Map<String, MoveData> = moveList.associateBy { it.id }
    val abilities: Map<String, AbilityData> = abilityList.associateBy { it.id }
    val items: Map<String, ItemData> = itemList.associateBy { it.id }

    /** One entry per National Dex number (base forms only), sorted by number. */
    val nationalDex: List<SpeciesData> = speciesList.filter { it.formKind == FormKind.BASE }.sortedBy { it.num }

    val learnsets: Map<String, LearnsetData> by lazy { learnsetLoader().associateBy { it.species } }

    fun species(id: String): SpeciesData = species[toId(id)] ?: error("Unknown species '$id'")
    fun move(id: String): MoveData = moves[toId(id)] ?: error("Unknown move '$id'")
    fun ability(id: String): AbilityData = abilities[toId(id)] ?: error("Unknown ability '$id'")
    fun item(id: String): ItemData = items[toId(id)] ?: error("Unknown item '$id'")

    fun speciesOrNull(id: String?) = id?.let { species[toId(it)] }
    fun moveOrNull(id: String?) = id?.let { moves[toId(it)] }
    fun itemOrNull(id: String?) = id?.let { items[toId(it)] }
    fun abilityOrNull(id: String?) = id?.let { abilities[toId(it)] }

    /** Learnset for a species, falling back through forme -> base species. */
    fun learnset(speciesId: String): LearnsetData? {
        learnsets[speciesId]?.let { if (it.levelUp.isNotEmpty()) return it }
        val sp = species[speciesId] ?: return null
        sp.changesFrom?.let { learnsets[toId(it)]?.let { l -> return l } }
        return learnsets[toId(sp.baseSpecies)]
    }

    fun baseForm(sp: SpeciesData): SpeciesData = species[toId(sp.baseSpecies)] ?: sp

    /** Pokédex category and description by National Dex number (empty when the data file is absent). */
    val dexText: Map<Int, DexEntryText> by lazy { dexTextLoader().associateBy { it.num } }

    companion object {
        fun load(source: ContentSource): Dex {
            fun text(path: String) = source.read(path) ?: error("Missing content file '$path'")
            val species = ContentJson.decodeFromString<List<SpeciesData>>(text("data/species.json"))
            val moves = ContentJson.decodeFromString<List<MoveData>>(text("data/moves.json"))
            val abilities = ContentJson.decodeFromString<List<AbilityData>>(text("data/abilities.json"))
            val items = ContentJson.decodeFromString<List<ItemData>>(text("data/items.json"))
            val chart = TypeChart(ContentJson.decodeFromString<TypeChartData>(text("data/typechart.json")))
            val manifest = source.read("data/manifest.json")?.let { ContentJson.decodeFromString<ContentManifest>(it) }
            return Dex(species, moves, abilities, items, chart, {
                ContentJson.decodeFromString<List<LearnsetData>>(text("data/learnsets.json"))
            }, manifest, { source.read("data/dextext.json")?.let { ContentJson.decodeFromString<List<DexEntryText>>(it) } ?: emptyList() })
        }

        /** Loads from the directory given by the `pkmn.assets` system property (tests/tools) or ./assets. */
        fun loadDefault(): Dex {
            val dir = System.getProperty("pkmn.assets")?.let { java.io.File(it) } ?: java.io.File("assets")
            return load(DirectoryContentSource(dir))
        }
    }
}
