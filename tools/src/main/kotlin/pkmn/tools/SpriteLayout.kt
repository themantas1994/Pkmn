package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.Dex
import pkmn.engine.data.FormKind
import java.io.File

/**
 * Directory contract for Pokémon sprites (see docs/ASSET_IMPORT.md):
 * `<root>/sprites/pokemon/<kind>/<speciesId>.png` where root is `assets/` (bundled, redistributable)
 * or `assets-local/` (locally imported, never committed or redistributed).
 */
object SpriteLayout {
    val KINDS = listOf("front", "back", "front-shiny", "back-shiny", "icon")
    const val REL = "sprites/pokemon"

    fun bundledDir(root: File) = File(root, "assets/$REL")
    fun localDir(root: File) = File(root, "assets-local/$REL")

    /** Tracked reports use `includeLocal = false` so they do not depend on what a machine has imported into `assets-local/`. */
    fun availability(root: File, dex: Dex, includeLocal: Boolean): JsonObject = buildJsonObject {
        put("layout", if (includeLocal) "{assets,assets-local}/$REL/<kind>/<speciesId>.png" else "assets/$REL/<kind>/<speciesId>.png")
        for (kind in KINDS) {
            val bundled = File(bundledDir(root), kind).listFiles()?.filter { it.extension == "png" }?.map { it.nameWithoutExtension }?.toSet().orEmpty()
            val local = if (!includeLocal) emptySet() else File(localDir(root), kind).listFiles()?.filter { it.extension == "png" }?.map { it.nameWithoutExtension }?.toSet().orEmpty()
            val any = bundled + local
            putJsonObject(kind) {
                put("bundledFiles", bundled.size)
                if (includeLocal) put("localFiles", local.size)
                put("baseSpeciesWithSprite", dex.nationalDex.count { it.id in any })
                put("baseSpeciesMissing", dex.nationalDex.count { it.id !in any })
                put("formsWithSprite", dex.species.values.count { it.formKind != FormKind.BASE && it.id in any })
                put("unknownIds", any.count { it !in dex.species })
            }
        }
    }
}
