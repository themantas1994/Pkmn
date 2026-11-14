package pkmn.engine.link

import kotlinx.serialization.builtins.ListSerializer
import pkmn.engine.data.AbilityData
import pkmn.engine.data.ContentJson
import pkmn.engine.data.Dex
import pkmn.engine.data.ItemData
import pkmn.engine.data.MoveData
import pkmn.engine.data.SpeciesData
import java.security.MessageDigest

/** The battle-relevant content of a [Dex] as one hash, so two devices with different data refuse to battle. */
object LinkFingerprint {
    /**
     * SHA-256 (hex) over species, moves, abilities, items, the type chart and the content version, each in id order.
     * Learnsets and Pokédex text are left out: no battle decision reads them. Costs a full serialization of the content,
     * so compute it once per session.
     */
    fun dataHash(dex: Dex): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun feed(label: String, text: String) { md.update(label.toByteArray(Charsets.UTF_8)); md.update(0); md.update(text.toByteArray(Charsets.UTF_8)); md.update(0) }
        feed("species", ContentJson.encodeToString(ListSerializer(SpeciesData.serializer()), dex.species.values.sortedBy { it.id }))
        feed("moves", ContentJson.encodeToString(ListSerializer(MoveData.serializer()), dex.moves.values.sortedBy { it.id }))
        feed("abilities", ContentJson.encodeToString(ListSerializer(AbilityData.serializer()), dex.abilities.values.sortedBy { it.id }))
        feed("items", ContentJson.encodeToString(ListSerializer(ItemData.serializer()), dex.items.values.sortedBy { it.id }))
        val types = dex.typeChart.types
        feed("types", types.joinToString(",") + "|" + types.joinToString(";") { a -> types.joinToString(",") { d -> dex.typeChart.effectiveness(a, d).toString() } })
        feed("content", dex.manifest?.contentVersion?.toString() ?: "-")
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
