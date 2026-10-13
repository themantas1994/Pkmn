package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.ContentJson
import pkmn.engine.data.SpeciesData
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * Scans `assets/` (excluding `assets/data` and the generated `assets/manifest`) and the optional, git-ignored
 * `assets-local/` tree, and writes:
 *  - `assets/manifest/assets.json`: every bundled (redistributable) asset;
 *  - `assets-local/manifest/assets.json`: every locally imported asset (same schema). Kept out of `assets/` so the
 *    committed manifest never depends on, or names, files that may not be redistributed;
 *  - `reports/asset-validation.json`: errors and warnings. Returns false when there are errors.
 *
 * Attribution comes from `assets/ATTRIBUTION.json` (globs relative to the repository root).
 */
class AssetManifestTool(private val root: File) {
    data class Attribution(val pathGlob: String, val source: String, val license: String, val author: String,
                           val redistributable: Boolean, val status: String?) {
        private val matcher = FileSystems.getDefault().getPathMatcher("glob:$pathGlob")
        fun matches(repoPath: String) = matcher.matches(Paths.get(repoPath))
    }

    data class AssetEntry(
        val id: String, val root: String, val path: String, val format: String, val sizeBytes: Long,
        val width: Int?, val height: Int?, val category: String, val frames: Int?,
        val attribution: Attribution?, val status: String,
    ) {
        fun toJson() = buildJsonObject {
            put("id", id); put("path", path); put("format", format); put("sizeBytes", sizeBytes)
            width?.let { put("width", it) }; height?.let { put("height", it) }
            put("category", category)
            frames?.let { put("frames", it) }
            if (attribution != null) {
                put("source", attribution.source); put("license", attribution.license); put("author", attribution.author)
                put("redistributable", attribution.redistributable)
            } else { put("source", "unknown"); put("license", "unknown"); put("redistributable", false) }
            put("status", status)
        }
    }

    class Scan(val bundled: List<AssetEntry>, val local: List<AssetEntry>, val issues: Issues)

    fun run(): Boolean {
        val scan = scan()
        writeJson(File(root, "assets/manifest/assets.json"), manifestJson(scan.bundled, "assets"))
        if (File(root, "assets-local").isDirectory) writeJson(File(root, "assets-local/manifest/assets.json"), manifestJson(scan.local, "assets-local"))
        writeJson(File(root, "reports/asset-validation.json"), scan.issues.toJson {
            put("generatedBy", "pkmn.tools.AssetManifestTool")
            put("bundledAssets", scan.bundled.size)
            put("localAssets", scan.local.size)
            put("bundledByCategory", countBy(scan.bundled.map { it.category }))
            put("localByCategory", countBy(scan.local.map { it.category }))
        })
        println("Asset manifest: ${scan.bundled.size} bundled, ${scan.local.size} local asset(s); " +
            "${scan.issues.errors.size} error(s), ${scan.issues.warnings.size} warning(s)")
        scan.issues.errors.take(40).forEach { println("  ERROR [${it.code}] ${it.subject}: ${it.message}") }
        return scan.issues.ok
    }

    private fun manifestJson(entries: List<AssetEntry>, rootName: String) = buildJsonObject {
        put("version", 1)
        put("root", rootName)
        put("count", entries.size)
        put("byCategory", countBy(entries.map { it.category }))
        put("assets", JsonArray(entries.map { it.toJson() }))
    }

    fun scan(): Scan {
        val issues = Issues()
        val attributions = loadAttributions(issues)
        val speciesIds = loadSpeciesIds()
        val bundled = scanRoot(File(root, "assets"), "assets", attributions, speciesIds, issues,
            exclude = setOf("data", "manifest"), excludeFiles = setOf("ATTRIBUTION.json"))
        val local = scanRoot(File(root, "assets-local"), "assets-local", attributions, speciesIds, issues,
            exclude = setOf("manifest"), excludeFiles = emptySet())
        return Scan(bundled, local, issues)
    }

    private fun loadAttributions(issues: Issues): List<Attribution> {
        val f = File(root, "assets/ATTRIBUTION.json")
        if (!f.isFile) { issues.error("attribution-missing", "assets/ATTRIBUTION.json", "attribution file missing"); return emptyList() }
        return try {
            Json.parseToJsonElement(f.readText()).jsonObject["entries"]!!.jsonArray.mapIndexedNotNull { i, el ->
                val o = el.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                val missing = listOf("pathGlob", "source", "license", "author").filter { s(it).isNullOrBlank() } +
                    listOfNotNull("redistributable".takeIf { o["redistributable"]?.jsonPrimitive?.booleanOrNull == null })
                if (missing.isNotEmpty()) { issues.error("attribution-entry", "ATTRIBUTION.json#$i", "missing field(s): ${missing.joinToString()}"); null }
                else Attribution(s("pathGlob")!!, s("source")!!, s("license")!!, s("author")!!, o["redistributable"]!!.jsonPrimitive.boolean, s("status"))
            }
        } catch (e: Exception) {
            issues.error("attribution-parse", "assets/ATTRIBUTION.json", e.message ?: "invalid JSON"); emptyList()
        }
    }

    private fun loadSpeciesIds(): Set<String>? = File(root, "assets/data/species.json").takeIf { it.isFile }?.let { f ->
        ContentJson.decodeFromString<List<SpeciesData>>(f.readText()).map { it.id }.toSet()
    }

    private fun scanRoot(dir: File, rootName: String, attributions: List<Attribution>, speciesIds: Set<String>?,
                         issues: Issues, exclude: Set<String>, excludeFiles: Set<String>): List<AssetEntry> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.walkTopDown().onEnter { d -> d == dir || d.relativeTo(dir).invariantSeparatorsPath.substringBefore('/') !in exclude }
            .filter { it.isFile && !it.name.startsWith(".") && it.relativeTo(dir).invariantSeparatorsPath !in excludeFiles }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        val local = rootName == "assets-local"
        val result = ArrayList<AssetEntry>()
        for (f in files) {
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            val repoPath = "$rootName/$rel"
            if (local && rel == "sprites/IMPORT_LOG.json") continue
            val ext = f.extension.lowercase()
            val format = ext.ifEmpty { "none" }
            if (ext !in SUPPORTED) issues.error("asset-format", repoPath, "unsupported format '.$format' (supported: ${SUPPORTED.sorted().joinToString()})")
            if (f.length() == 0L) issues.error("asset-empty", repoPath, "zero-size file")
            val category = categoryOf(rel, ext)
            var w: Int? = null; var h: Int? = null
            if (ext == "png" && f.length() > 0) {
                val dims = imageSize(f)
                if (dims == null) issues.error("asset-image", repoPath, "unreadable PNG")
                else {
                    w = dims.first; h = dims.second
                    if (w % 8 != 0 || h % 8 != 0) {
                        if (category in GRID_CATEGORIES && !local) issues.error("asset-grid", repoPath, "${w}x$h is not a multiple of 8 ($category assets must align to the 8px grid)")
                        else issues.warn("asset-grid", repoPath, "${w}x$h is not a multiple of 8",
                            "Only tile/character/ui art must align to the 8px grid; $category images are drawn at their own size.")
                    }
                }
            }
            if (category == "pokemon-sprite") checkPokemonSprite(rel, repoPath, speciesIds, issues)
            var frames: Int? = null
            if (ext == "png") File(f.parentFile, f.nameWithoutExtension + ".json").takeIf { it.isFile }?.let { d ->
                frames = checkSheet(d, f, w, h, "$rootName/${d.relativeTo(dir).invariantSeparatorsPath}", issues)
            }
            if (ext == "json" && !File(f.parentFile, f.nameWithoutExtension + ".png").isFile) {
                // Standalone descriptor (e.g. a frame list) – validate if it declares frames.
                runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()?.takeIf { isSheet(it) }?.let {
                    checkSheet(f, null, null, null, repoPath, issues)
                }
            }
            val attr = attributions.lastOrNull { it.matches(repoPath) }
            if (attr == null) {
                if (local) issues.warn("asset-attribution", repoPath, "no ATTRIBUTION.json entry; treated as non-redistributable",
                    "assets-local is git-ignored and never bundled unless explicitly requested for a personal build.")
                else issues.error("asset-attribution", repoPath, "no ATTRIBUTION.json entry (source/license unknown)")
            } else if (!local && !attr.redistributable) {
                issues.error("asset-not-redistributable", repoPath, "bundled asset is marked non-redistributable (${attr.license}); move it to assets-local/")
            }
            // A sheet descriptor shares its image's stem; give it a distinct id ("characters/player.sheet").
            val stemId = rel.substringBeforeLast('.')
            val id = if (ext == "json" && File(f.parentFile, f.nameWithoutExtension + ".png").isFile) "$stemId.sheet" else stemId
            val status = attr?.status ?: if (local) "local-import" else "unspecified"
            result += AssetEntry(id, rootName, repoPath, format, f.length(), w, h, category, frames, attr, status)
        }
        result.groupBy { it.id }.filter { it.value.size > 1 }.forEach { (id, list) ->
            issues.error("asset-duplicate-id", "$rootName:$id", "several files share the id: ${list.joinToString { it.path }}")
        }
        return result
    }

    private fun checkPokemonSprite(rel: String, repoPath: String, speciesIds: Set<String>?, issues: Issues) {
        val parts = rel.removePrefix("${SpriteLayout.REL}/").split('/')
        if (parts.size != 2) { issues.error("sprite-layout", repoPath, "expected ${SpriteLayout.REL}/<kind>/<speciesId>.png"); return }
        if (parts[0] !in SpriteLayout.KINDS) issues.error("sprite-kind", repoPath, "unknown sprite kind '${parts[0]}' (expected ${SpriteLayout.KINDS.joinToString()})")
        val id = parts[1].substringBeforeLast('.')
        if (speciesIds != null && id !in speciesIds) issues.error("sprite-species", repoPath, "'$id' is not a species id in species.json")
    }

    private fun isSheet(o: JsonObject) = "frameWidth" in o || "frameCount" in o || "frames" in o

    /** Validates a sheet descriptor; returns the declared/derived frame count. */
    private fun checkSheet(desc: File, image: File?, w: Int?, h: Int?, repoPath: String, issues: Issues): Int? {
        val o = try { Json.parseToJsonElement(desc.readText()).jsonObject } catch (e: Exception) {
            issues.error("sheet-parse", repoPath, e.message ?: "invalid JSON"); return null
        }
        if (!isSheet(o)) return null
        val fw = o["frameWidth"]?.jsonPrimitive?.intOrNull
        val fh = o["frameHeight"]?.jsonPrimitive?.intOrNull
        val frameFiles = (o["frames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val capacity = if (fw != null && fh != null && fw > 0 && fh > 0 && w != null && h != null) {
            if (w % fw != 0 || h % fh != 0) issues.error("sheet-grid", repoPath, "image ${w}x$h is not divisible into ${fw}x$fh frames")
            (w / fw) * (h / fh)
        } else null
        val declared = o["frameCount"]?.jsonPrimitive?.intOrNull
        val count = declared ?: frameFiles?.size ?: capacity
        if (declared != null && capacity != null && declared > capacity)
            issues.error("sheet-missing-frames", repoPath, "declares $declared frames but the image only holds $capacity")
        frameFiles?.forEach { name ->
            if (!File(desc.parentFile, name).isFile) issues.error("sheet-missing-frames", repoPath, "frame file '$name' does not exist")
        }
        if (image == null && frameFiles == null) issues.error("sheet-image", repoPath, "descriptor has no sibling image and no frame list")
        (o["animations"] as? JsonObject)?.forEach { (name, anim) ->
            val idx = when (anim) {
                is JsonArray -> anim
                is JsonObject -> anim["frames"] as? JsonArray
                else -> null
            }?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()
            if (count != null) idx.filter { it < 0 || it >= count }.forEach {
                issues.error("sheet-missing-frames", repoPath, "animation '$name' references frame $it but only $count frame(s) exist")
            }
        }
        return count
    }

    companion object {
        val SUPPORTED = setOf("png", "json", "atlas", "fnt", "ttf", "otf", "ogg", "wav", "mp3", "tmx", "tsx", "txt", "csv", "glsl", "vert", "frag")
        val GRID_CATEGORIES = setOf("tile", "character", "ui")

        fun categoryOf(rel: String, ext: String): String {
            val p = rel.lowercase()
            fun seg(vararg names: String) = names.any { n -> p.startsWith("$n/") || p.contains("/$n/") }
            return when {
                p.startsWith("${SpriteLayout.REL}/") || p.contains("/${SpriteLayout.REL}/") -> "pokemon-sprite"
                ext in setOf("ogg", "wav", "mp3") || seg("audio", "music", "sfx", "sound") -> "audio"
                ext in setOf("ttf", "otf", "fnt") || seg("fonts", "font") -> "font"
                ext in setOf("tmx", "tsx") || seg("maps", "map") -> "map"
                seg("tiles", "tilesets", "tileset") -> "tile"
                seg("characters", "character", "npcs", "npc", "player", "overworld") -> "character"
                seg("ui", "gui", "hud") -> "ui"
                else -> "other"
            }
        }

        fun imageSize(f: File): Pair<Int, Int>? = runCatching {
            ImageIO.createImageInputStream(f).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                try { reader.input = input; reader.getWidth(0) to reader.getHeight(0) } finally { reader.dispose() }
            }
        }.getOrNull()
    }
}
