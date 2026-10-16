package pkmn.tools

import pkmn.engine.data.*
import pkmn.engine.mon.Gender
import pkmn.engine.mon.MoveSlot
import pkmn.engine.mon.Nature
import pkmn.engine.mon.Pokemon
import pkmn.engine.mon.Progression

/**
 * Checks every evolution against the real in-game rules in [Progression.evolutionTarget] by searching for a
 * "witness": a Pokémon state plus [Progression.EvolutionContext] under which the evolution actually triggers.
 * Nothing about the rules is duplicated here; the search only supplies the inputs the game can provide
 * (time of day, weather, items, Linking Cord, party members, map location tags, counters, ...).
 * The game's region is never changed: it stays at the [Progression.EvolutionContext] default.
 */
class EvolutionAudit(private val dex: Dex) {
    data class Result(
        val from: String,
        val evo: EvolutionData,
        val satisfiable: Boolean,
        /** Non-default inputs the witness needed (e.g. "night", "useItem:thunderstone", "locationTag:magnetic"). */
        val requires: List<String>,
        val reason: String?,
    )

    private val progression = Progression(dex)
    val gameRegion: String = Progression.EvolutionContext().region

    fun auditAll(): List<Result> = dex.species.values.sortedWith(compareBy({ it.num }, { it.id }))
        .flatMap { sp -> sp.evolutions.map { audit(sp, it) } }

    fun audit(sp: SpeciesData, evo: EvolutionData): Result {
        if (dex.speciesOrNull(evo.target) == null) return Result(sp.id, evo, false, emptyList(), "target species '${evo.target}' does not exist")
        val codes = evo.conditionCode?.split('&').orEmpty()
        val counters = HashMap<String, Int>()
        val locationTags = LinkedHashSet<String>()
        val partySpecies = ArrayList<String>()
        for (c in codes) {
            val p = c.split(':')
            when {
                c.startsWith("moveuses:") -> counters["use:${p[1]}"] = p[2].toInt()
                c.startsWith("defeat:") -> counters["defeat:${p[1]}"] = p[2].toInt()
                c.startsWith("crits:") || c.startsWith("recoil:") || c.startsWith("hplost:") || c.startsWith("steps:") ->
                    counters[p[0]] = p[1].toInt()
                c.startsWith("location:") -> locationTags += p[1]
                c.startsWith("party:") -> partySpecies += p[1]
                c.startsWith("partytype:") -> dex.nationalDex.firstOrNull { p[1] in it.types }?.let { partySpecies += it.id }
            }
            if (c.startsWith("hplost:")) locationTags += "dustybowl"
            if (c == "fullmoon") locationTags += "fullmoon"
        }
        val moves = buildList {
            evo.move?.let { add(it) }
            if (codes.any { it.startsWith("movetype:") }) dex.moves.values.firstOrNull { it.type == "Fairy" && it.isNonstandard == null }?.let { add(it.id) }
        }
        val gender = when (evo.gender) { "M" -> Gender.MALE; "F" -> Gender.FEMALE; else -> if (sp.genderless) Gender.NONE else Gender.MALE }
        val heldItem = evo.item?.takeIf { evo.method == EvolutionMethod.LEVEL_HOLD || evo.method == EvolutionMethod.TRADE }

        // Stat spreads for Tyrogue-style Atk/Def comparisons: neutral, Atk-favoured, Def-favoured.
        val spreads = listOf(Nature.HARDY to null, Nature.LONELY to Stat.ATK, Nature.BOLD to Stat.DEF)
        val chosenInstead = sortedSetOf<String>()
        for (night in listOf(false, true)) for (dusk in listOf(false, true)) for (rain in listOf(false, true))
            for (useItem in listOf(null) + listOfNotNull(evo.item)) for (cord in listOf(false, true))
                for (upside in listOf(false, true)) for ((nature, evStat) in spreads) {
                    val mon = Pokemon(speciesId = sp.id, level = 100, nature = nature, friendship = 255, gender = gender,
                        heldItem = heldItem, moves = moves.map { MoveSlot(it, 10) }.toMutableList(), counters = HashMap(counters))
                    evStat?.let { mon.evs[it] = 252 }
                    val party = listOf(mon) + partySpecies.map { Pokemon(it, 50) }
                    val ctx = Progression.EvolutionContext(isNight = night, isDusk = dusk, raining = rain, party = party,
                        usedItem = useItem, linkingCord = cord, locationTags = locationTags, coins = 9999, deviceUpsideDown = upside)
                    val chosen = progression.evolutionTarget(mon, ctx)
                    if (chosen != null && chosen != evo.target) chosenInstead += chosen
                    if (chosen == evo.target) {
                        val req = buildList {
                            if (night) add("night"); if (dusk) add("dusk"); if (rain) add("rain")
                            useItem?.let { add("useItem:$it") }; heldItem?.let { add("holdItem:$it") }
                            if (cord) add("linkingCord"); if (upside) add("deviceUpsideDown")
                            evStat?.let { add("statSpread:${it.id}") }
                            locationTags.forEach { add("locationTag:$it") }
                            partySpecies.forEach { add("partyMember:$it") }
                            counters.forEach { (k, v) -> add("counter:$k>=$v") }
                            moves.forEach { add("knowsMove:$it") }
                            if (codes.any { it.startsWith("coins:") }) add("coins")
                        }
                        return Result(sp.id, evo, true, req, null)
                    }
                }
        return Result(sp.id, evo, false, emptyList(), explain(codes, evo, chosenInstead))
    }

    private fun explain(codes: List<String>, evo: EvolutionData, chosenInstead: Set<String>): String {
        codes.firstOrNull { it.startsWith("region:") && it.removePrefix("region:") != gameRegion }?.let {
            return "regional evolution limited to ${it.removePrefix("region:")}; the game's region is '$gameRegion' (Progression.EvolutionContext.region)"
        }
        if (evo.method == EvolutionMethod.OTHER) {
            val c = evo.conditionCode ?: return "OTHER evolution without a condition code is never satisfied by Progression"
            return "Progression has no in-game input for condition '$c' (otherMet returns false)"
        }
        if (evo.method == EvolutionMethod.USE_ITEM && evo.item == null) return "item evolution without an item id"
        if (chosenInstead.isNotEmpty()) return "shadowed: whenever its requirements are met, Progression.evolutionTarget returns " +
            "${chosenInstead.joinToString()} first (first matching entry wins; the official branch-selection rule is not modelled)"
        return "no game state found under which Progression.evolutionTarget selects this evolution"
    }
}
