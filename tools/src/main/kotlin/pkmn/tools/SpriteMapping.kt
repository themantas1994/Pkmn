package pkmn.tools

import pkmn.engine.data.FormKind
import pkmn.engine.data.SpeciesData
import pkmn.engine.util.toId

/**
 * Maps our (Showdown-style) species ids to file stems in the PokeAPI sprites repository, e.g.
 * `charizardmegax` -> `10034`, `arceusfire` -> `493-fire`, `bulbasaur` -> `1`.
 *
 * Order: explicit alias -> base species by National Dex number -> exact identifier in pokemon.csv ->
 * exact identifier in pokemon_forms.csv -> token match of our forme name against PokeAPI identifiers of the same
 * species (fewest extra tokens, then lowest id). Anything else is reported as unmapped, never guessed.
 */
class SpriteMapping(
    pokemon: List<Map<String, String>>,
    forms: List<Map<String, String>>,
    species: List<Map<String, String>>,
) {
    data class Ref(val stem: String, val method: String, val pokeapiIdentifier: String)

    private data class Row(val id: Int, val identifier: String, val speciesId: Int)
    private data class FormRow(val identifier: String, val formIdentifier: String, val pokemonId: Int, val isDefault: Boolean)

    private val pokemonRows = pokemon.mapNotNull { r ->
        Row(r["id"]?.toIntOrNull() ?: return@mapNotNull null, r["identifier"] ?: return@mapNotNull null, r["species_id"]?.toIntOrNull() ?: return@mapNotNull null)
    }
    private val pokemonById = pokemonRows.associateBy { it.id }
    private val pokemonByIdent = pokemonRows.associateBy { toId(it.identifier) }
    private val pokemonBySpecies = pokemonRows.groupBy { it.speciesId }
    private val formRows = forms.mapNotNull { r ->
        FormRow(r["identifier"] ?: return@mapNotNull null, r["form_identifier"].orEmpty(),
            r["pokemon_id"]?.toIntOrNull() ?: return@mapNotNull null, r["is_default"] == "1")
    }
    private val formsByIdent = formRows.associateBy { toId(it.identifier) }
    private val speciesIdent = species.mapNotNull { r -> r["id"]?.toIntOrNull()?.let { it to r["identifier"].orEmpty() } }.toMap()

    fun map(sp: SpeciesData): Ref? {
        ALIASES[sp.id]?.let { ident -> pokemonByIdent[toId(ident)]?.let { return Ref(it.id.toString(), "alias", it.identifier) } }
        if (sp.formKind == FormKind.BASE) {
            val row = pokemonById[sp.num]
            return Ref(sp.num.toString(), "species-number", row?.identifier ?: sp.id)
        }
        pokemonByIdent[sp.id]?.let { return Ref(it.id.toString(), "pokemon.csv", it.identifier) }
        formsByIdent[sp.id]?.let { f ->
            val stem = if (f.isDefault || f.formIdentifier.isEmpty()) f.pokemonId.toString() else "${f.pokemonId}-${f.formIdentifier}"
            return Ref(stem, "pokemon_forms.csv", f.identifier)
        }
        val wanted = (sp.forme ?: return null).lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            .map { SYNONYMS[it] ?: it }.toSet()
        if (wanted.isEmpty()) return null
        val prefix = speciesIdent[sp.num]?.let { "$it-" } ?: return null
        val best = pokemonBySpecies[sp.num].orEmpty().mapNotNull { r ->
            val tokens = r.identifier.removePrefix(prefix).split('-').toSet()
            if (r.identifier.startsWith(prefix) && tokens.containsAll(wanted)) r to tokens.size else null
        }.minWithOrNull(compareBy({ it.second }, { it.first.id }))?.first ?: return null
        return Ref(best.id.toString(), "token-match", best.identifier)
    }

    companion object {
        private val SYNONYMS = mapOf("f" to "female", "m" to "male")

        /** Cases where our forme name and PokeAPI's identifier share no tokens, or the default form differs. */
        val ALIASES = mapOf(
            "rockruffdusk" to "rockruff-own-tempo",
            "necrozmaduskmane" to "necrozma-dusk",
            "necrozmadawnwings" to "necrozma-dawn",
            "marowakalolatotem" to "marowak-totem",
            // Showdown's base Maushold is the Family of Three; PokeAPI's default (#925) is the Family of Four.
            "maushold" to "maushold-family-of-three",
            "mausholdfour" to "maushold-family-of-four",
            // Showdown's base Minior is the (Red) Core form; PokeAPI's default (#774) is Red Meteor.
            "minior" to "minior-red",
        )
    }
}
