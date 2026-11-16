package pkmn.engine.link

import kotlinx.serialization.builtins.ListSerializer
import pkmn.engine.data.AbilityData
import pkmn.engine.data.ContentJson
import pkmn.engine.data.Dex
import pkmn.engine.data.ItemData
import pkmn.engine.data.LearnsetData
import pkmn.engine.data.MoveData
import pkmn.engine.data.SpeciesData
import java.security.MessageDigest

/** The content of a [Dex] that link play depends on, as one hash, so two devices with different data refuse to link. */
object LinkFingerprint {
    /**
     * The pinned combined hash of `DeterminismSelfTest` (400 seeded battles). `DeterminismTest` fails when battle behaviour
     * or content changes it; re-pin with `./gradlew :engine:test --tests '*DeterminismTest*' -Dgolden.update=true`. Both
     * devices send [rulesId] with their game version, so two builds with different battle rules refuse to link.
     */
    const val PINNED_COMBINED_HASH = "f3ae87c7a4e66836a5f76794c4795792f0e6e39bebcc72fff5916effbe0a405f"

    /** Short id of the engine rules this build plays by (the start of [PINNED_COMBINED_HASH]). */
    val rulesId: String get() = PINNED_COMBINED_HASH.take(8)

    /**
     * SHA-256 (hex) over species, moves, abilities, items, learnsets, the type chart and the content version, each in id
     * order. Learnsets are in because a traded Pokémon learns moves on this device after the trade (level-up and
     * evolution moves). Pokédex text is left out: nothing in a link session reads it. Costs a full serialization of the
     * content, so compute it once per session.
     */
    fun dataHash(dex: Dex): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun feed(label: String, text: String) { md.update(label.toByteArray(Charsets.UTF_8)); md.update(0); md.update(text.toByteArray(Charsets.UTF_8)); md.update(0) }
        feed("species", ContentJson.encodeToString(ListSerializer(SpeciesData.serializer()), dex.species.values.sortedBy { it.id }))
        feed("moves", ContentJson.encodeToString(ListSerializer(MoveData.serializer()), dex.moves.values.sortedBy { it.id }))
        feed("abilities", ContentJson.encodeToString(ListSerializer(AbilityData.serializer()), dex.abilities.values.sortedBy { it.id }))
        feed("items", ContentJson.encodeToString(ListSerializer(ItemData.serializer()), dex.items.values.sortedBy { it.id }))
        feed("learnsets", ContentJson.encodeToString(ListSerializer(LearnsetData.serializer()), dex.learnsets.values.sortedBy { it.species }))
        val types = dex.typeChart.types
        feed("types", types.joinToString(",") + "|" + types.joinToString(";") { a -> types.joinToString(",") { d -> dex.typeChart.effectiveness(a, d).toString() } })
        feed("content", dex.manifest?.contentVersion?.toString() ?: "-")
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
