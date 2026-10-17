package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.ContentJson
import pkmn.engine.data.FormKind
import pkmn.engine.data.SpeciesData
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Raw cache -> live sprite set for the personal APK (docs/ASSET_IMPORT.md, "One-command personal APK").
 *
 * ```
 * assets-local/sprites-raw/pokemon/<kind>/<id>.png   raw cache (SpriteImporter --dest), never modified here, never bundled
 * assets-local/sprites-styled.tmp/                   scratch output, validated before it goes live
 * assets-local/sprites/pokemon/<kind>/<id>.png       live set, the only directory the APK bundles
 * assets-local/sprites/{STYLE_REPORT,COVERAGE}.json  what was produced, and how much of the species list it covers
 * ```
 *
 * The input is always the raw cache, so styled output is never styled again. The live directory is replaced by
 * renames only (old -> `pokemon.bak`, tmp -> live, then the backup is deleted); on any failure the previous live set
 * stays in place.
 *
 * Usage: `tools sprites-style [--style=gbc|none] [--no-outline] [--strict] [--only=1-151] [--forms=false] [--kinds=front,icon]`.
 * `--only/--forms/--kinds` limit the coverage report (the scope you asked for); every raw file is processed.
 * `--strict` aborts the swap when any raw file fails; without it failed files are recorded and skipped.
 */
class SpriteStylePipeline(
    private val root: File,
    args: List<String>,
    private val styler: (BufferedImage, String, Boolean) -> BufferedImage = { img, kind, outline -> SpriteStyler.style(img, kind, outline) },
    private val move: (File, File) -> Unit = ::atomicMove,
) {
    data class Options(
        val style: String = "gbc",
        val outline: Boolean = true,
        val strict: Boolean = false,
        val only: List<IntRange>? = null,
        val forms: Boolean = true,
        val kinds: List<String> = SpriteLayout.KINDS,
    )

    val options: Options = parseArgs(args)

    private val local = File(root, "assets-local")
    val rawDir = File(local, "sprites-raw/pokemon")
    val liveDir = SpriteLayout.localDir(root)
    private val tmpRoot = File(local, "sprites-styled.tmp")
    private val tmpDir = File(tmpRoot, "pokemon")
    private val backupDir = File(liveDir.parentFile, "pokemon.bak")
    private val liveRoot = liveDir.parentFile

    private data class Record(val speciesId: String, val kind: String, val source: String, val sourceSha256: String,
                              val output: String, val width: Int, val height: Int, val colours: Int, val sha256: String)

    fun run(): Boolean {
        val speciesFile = File(root, "assets/data/species.json")
        if (!speciesFile.isFile) return fail("${speciesFile.path} not found. Run from the repository root; generate it with ./gradlew :tools:importData.")
        recoverInterruptedSwap()
        val rawFiles = SpriteLayout.KINDS.associateWith { kind ->
            File(rawDir, kind).listFiles { f -> f.isFile && f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
        }
        if (!rawDir.isDirectory || rawFiles.values.all { it.isEmpty() })
            return fail("the raw sprite cache ${rawDir.relativeTo(root).path} contains no PNG files. Import first " +
                "(./gradlew :tools:importSpritesRaw, or --from=<dir|zip>); nothing was changed.")

        tmpRoot.deleteRecursively()
        tmpDir.mkdirs()
        val records = ArrayList<Record>()
        val failures = ArrayList<Pair<String, String>>()
        try {
            for ((kind, files) in rawFiles) for (src in files) {
                val rel = src.relativeTo(root).invariantSeparatorsPath
                val id = src.nameWithoutExtension
                if (!Regex("[a-z0-9]+").matches(id)) { failures += rel to "file name is not a lowercase alphanumeric species id"; continue }
                try {
                    val raw = src.readBytes()
                    val out = File(tmpDir, "$kind/$id.png")
                    val data = if (options.style == "none") { checkPlain(raw); raw } else {
                        val styled = styler(SpriteStyler.decode(raw), kind, options.outline)
                        SpriteStyler.validate(styled, kind)?.let { throw IllegalStateException(it) }
                        SpriteStyler.encodePng(styled)
                    }
                    out.parentFile.mkdirs()
                    out.writeBytes(data)
                    records += Record(id, kind, rel, sha256(raw), "assets-local/sprites/pokemon/$kind/$id.png", 0, 0, 0, sha256(data))
                } catch (e: Exception) {
                    failures += rel to (e.message ?: e::class.simpleName.orEmpty())
                }
            }
            if (records.isEmpty()) return abort(failures, "no raw sprite could be converted")
            if (options.strict && failures.isNotEmpty()) return abort(failures, "--strict: ${failures.size} raw file(s) failed")

            // Validate what is actually on disk before it can replace the live set.
            val checked = records.map { r ->
                val file = File(tmpDir, "${r.kind}/${r.speciesId}.png")
                val bytes = file.readBytes()
                val image = try { SpriteStyler.decode(bytes) } catch (e: Exception) { return abort(failures, "${file.name}: written PNG is unreadable (${e.message})") }
                val problem = if (options.style == "none") plainProblem(image) else SpriteStyler.validate(image, r.kind)
                if (problem != null) return abort(failures, "${r.kind}/${r.speciesId}.png failed output validation: $problem")
                r.copy(width = image.width, height = image.height, colours = SpriteStyler.opaqueColours(image).size, sha256 = sha256(bytes))
            }

            try {
                swapIn()
            } catch (e: Exception) {
                return abort(failures, "could not replace the live sprite set (${e.message}); the previous set was kept")
            }
            tmpRoot.deleteRecursively()
            writeReports(checked, failures)
            return true
        } finally {
            if (tmpRoot.exists()) tmpRoot.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ swap

    /** Repairs a crash between the two renames: a lone backup is the live set, a backup next to a live set is stale. */
    private fun recoverInterruptedSwap() {
        if (!backupDir.exists()) return
        if (liveDir.exists()) backupDir.deleteRecursively() else move(backupDir, liveDir)
    }

    private fun swapIn() {
        liveRoot.mkdirs()
        backupDir.deleteRecursively()
        val hadLive = liveDir.exists()
        if (hadLive) move(liveDir, backupDir)
        try {
            move(tmpDir, liveDir)
        } catch (e: Exception) {
            if (hadLive) { liveDir.deleteRecursively(); move(backupDir, liveDir) }
            throw e
        }
        backupDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ reports

    private fun writeReports(records: List<Record>, failures: List<Pair<String, String>>) {
        val sorted = records.sortedWith(compareBy({ it.kind }, { it.speciesId }))
        val setHash = sha256(sorted.joinToString("") { "${it.kind}/${it.speciesId} ${it.sha256}\n" }.toByteArray())
        writeAtomicJson(File(liveRoot, "STYLE_REPORT.json"), buildJsonObject {
            put("tool", "pkmn.tools.SpriteStylePipeline (./gradlew :tools:styleSprites); algorithm: pkmn-sprite-style-kit sprite_style.py")
            put("createdAt", Instant.now().toString())
            put("redistributable", false)
            put("input", rawDir.relativeTo(root).invariantSeparatorsPath + " (raw cache, never modified)")
            put("output", liveDir.relativeTo(root).invariantSeparatorsPath)
            put("styleMode", options.style)
            putJsonObject("style") {
                if (options.style == "none") put("note", "raw files copied unchanged (validated as PNG, dimensions 1-$MAX_PLAIN_DIMENSION)")
                else SpriteStyler.parameters(options.outline).forEach { (k, v) -> if (v is Boolean) put(k, v) else if (v is Number) put(k, v) else put(k, v.toString()) }
            }
            putJsonObject("counts") {
                put(if (options.style == "none") "copied" else "converted", records.size)
                put("failed", failures.size)
            }
            put("outputSetSha256", setHash)
            put("failures", JsonArray(failures.map { buildJsonObject { put("file", it.first); put("error", it.second) } }))
            put("files", JsonArray(sorted.map { r ->
                buildJsonObject {
                    put("speciesId", r.speciesId); put("kind", r.kind); put("source", r.source); put("sourceSha256", r.sourceSha256)
                    put("output", r.output); put("width", r.width); put("height", r.height); put("opaqueColors", r.colours); put("sha256", r.sha256)
                }
            }))
        })
        writeCoverage(failures)
    }

    private fun writeCoverage(styleFailures: List<Pair<String, String>>) {
        val species = ContentJson.decodeFromString<List<SpeciesData>>(File(root, "assets/data/species.json").readText())
        val inScope = species.filter { sp -> (options.only == null || options.only.any { sp.num in it }) && (options.forms || sp.formKind == FormKind.BASE) }
        val mappingFiles = listOf("pokemon", "pokemon_forms", "pokemon_species").map { File(root, "data-src/pokeapi/$it.csv") }
        val mapping = if (mappingFiles.all { it.isFile }) SpriteMapping(Csv.read(mappingFiles[0]), Csv.read(mappingFiles[1]), Csv.read(mappingFiles[2])) else null
        val unmappedIds = if (mapping == null) emptySet() else inScope.filter { mapping.map(it) == null }.map { it.id }.toSet()

        val importLog = runCatching { Json.parseToJsonElement(File(rawDir.parentFile, "IMPORT_LOG.json").readText()).jsonObject }.getOrNull()
        fun logList(key: String) = importLog?.get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val upstreamMissing = logList("missing").map { it.substringBefore(':') to it.substringAfter(':') }.toSet()
        val importFailed = logList("failures").mapNotNull { Regex("^([a-z0-9]+):([a-z-]+): ").find(it)?.destructured?.let { (id, kind) -> id to kind } }.toSet()
        val styleFailed = styleFailures.mapNotNull { (file, _) ->
            val p = file.split('/'); if (p.size >= 2) p[p.size - 1].removeSuffix(".png") to p[p.size - 2] else null
        }.toSet()

        val allIds = species.map { it.id }.toSet()
        val scopeIds = inScope.map { it.id }.toSet()
        val liveByKind = SpriteLayout.KINDS.associateWith { kind ->
            File(liveDir, kind).listFiles { f -> f.isFile && f.name.endsWith(".png") }?.map { it.nameWithoutExtension }?.toSet().orEmpty()
        }
        val summary = ArrayList<String>()
        summary += "Sprite coverage (style=${options.style}; scope: only=${options.only?.joinToString(",") { "${it.first}-${it.last}" } ?: "all"}, " +
            "forms=${options.forms}, ${inScope.size} species entries)"
        val missingIds = LinkedHashMap<String, List<String>>(); val failedIds = LinkedHashMap<String, List<String>>()
        val kindObjects = LinkedHashMap<String, JsonElement>()
        for (kind in options.kinds) {
            val have = liveByKind.getValue(kind)
            var imported = 0; var failed = 0; var unmapped = 0; var missing = 0; var upstream = 0
            val missList = ArrayList<String>(); val failList = ArrayList<String>()
            for (sp in inScope) when {
                sp.id in have -> imported++
                (sp.id to kind) in styleFailed || (sp.id to kind) in importFailed -> { failed++; failList += sp.id }
                sp.id in unmappedIds -> unmapped++
                else -> { missing++; missList += sp.id; if ((sp.id to kind) in upstreamMissing) upstream++ }
            }
            missingIds[kind] = missList; failedIds[kind] = failList
            kindObjects[kind] = buildJsonObject {
                put("species", inScope.size); put("imported", imported); put("missing", missing); put("failed", failed); put("unmapped", unmapped)
                put("missingUpstream", upstream)
                put("importedPercent", if (inScope.isEmpty()) 0.0 else Math.round(imported * 1000.0 / inScope.size) / 10.0)
            }
            summary += "  %-12s imported %4d / %-4d  missing %4d  failed %3d  unmapped %3d".format(kind, imported, inScope.size, missing, failed, unmapped)
        }
        val unknown = liveByKind.values.sumOf { s -> s.count { it !in allIds } }
        val outOfScope = liveByKind.values.sumOf { s -> s.count { it in allIds && it !in scopeIds } }
        if (outOfScope > 0) summary += "  (+$outOfScope more live files for species outside the requested scope)"
        if (unknown > 0) summary += "  ($unknown live files do not match any species id)"
        if (mapping == null) summary += "  (data-src/pokeapi CSVs not found: unmapped species could not be determined)"
        summary += "  missing = mapped but no live file (e.g. not provided upstream); unmapped = no PokeAPI mapping; failed = download or style error."
        summary += "  Species without a file use the runtime fallback (base-species sprite, then a generated placeholder), not counted as imported."

        writeAtomicJson(File(liveRoot, "COVERAGE.json"), buildJsonObject {
            put("generatedAt", Instant.now().toString())
            put("redistributable", false)
            put("styleMode", options.style)
            putJsonObject("scope") {
                put("only", options.only?.joinToString(",") { "${it.first}-${it.last}" } ?: "all"); put("forms", options.forms)
                put("kinds", strArray(options.kinds)); put("speciesEntries", inScope.size)
            }
            put("kinds", JsonObject(kindObjects))
            put("liveFilesOutsideScope", outOfScope); put("liveFilesUnknownSpecies", unknown)
            put("unmappedSpecies", strArray(unmappedIds.sorted()))
            putJsonObject("missingSpecies") { missingIds.forEach { (k, v) -> put(k, strArray(v)) } }
            putJsonObject("failedSpecies") { failedIds.forEach { (k, v) -> put(k, strArray(v)) } }
            put("summaryText", strArray(summary))
        })
        println("Styled ${options.style} sprites -> ${liveDir.relativeTo(root).path} (${liveByKind.values.sumOf { it.size }} files). Reports: " +
            "${File(liveRoot, "STYLE_REPORT.json").relativeTo(root).path}, ${File(liveRoot, "COVERAGE.json").relativeTo(root).path}")
        summary.forEach { println(it) }
        if (styleFailures.isNotEmpty()) {
            System.err.println("${styleFailures.size} raw file(s) failed and were skipped (first 20):")
            styleFailures.take(20).forEach { System.err.println("  ${it.first}: ${it.second}") }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun checkPlain(bytes: ByteArray) {
        if (!SpriteImporter.isPng(bytes)) throw IllegalArgumentException("not a PNG")
        plainProblem(SpriteStyler.decode(bytes))?.let { throw IllegalArgumentException(it) }
    }

    private fun plainProblem(image: BufferedImage): String? =
        if (image.width !in 1..MAX_PLAIN_DIMENSION || image.height !in 1..MAX_PLAIN_DIMENSION) "implausible dimensions ${image.width}x${image.height}" else null

    private fun fail(message: String): Boolean { System.err.println("ERROR: $message"); return false }

    /** Keeps the previous live set, removes scratch output and records why in assets-local/STYLE_REPORT.failed.json. */
    private fun abort(failures: List<Pair<String, String>>, reason: String): Boolean {
        tmpRoot.deleteRecursively()
        System.err.println("ERROR: $reason. The previous live sprite set was left untouched.")
        failures.take(20).forEach { System.err.println("  ${it.first}: ${it.second}") }
        writeAtomicJson(File(local, "STYLE_REPORT.failed.json"), buildJsonObject {
            put("createdAt", Instant.now().toString()); put("reason", reason); put("styleMode", options.style)
            put("failures", JsonArray(failures.map { buildJsonObject { put("file", it.first); put("error", it.second) } }))
        })
        return false
    }

    private fun writeAtomicJson(file: File, json: JsonElement) =
        SpriteImporter.writeAtomic(file, (PrettyJson.encodeToString(JsonElement.serializer(), json) + "\n").toByteArray())

    companion object {
        const val MAX_PLAIN_DIMENSION = 1024

        fun atomicMove(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }

        fun parseArgs(args: List<String>): Options {
            var o = Options()
            for (a in SpriteImporter.splitOptions(args)) {
                val (k, v) = if ('=' in a) a.substringBefore('=') to a.substringAfter('=') else a to "true"
                o = when (k) {
                    "--style" -> o.copy(style = v.also { require(it == "gbc" || it == "none") { "--style must be gbc or none, not '$it'" } })
                    "--no-outline" -> o.copy(outline = !v.toBoolean())
                    "--strict" -> o.copy(strict = v.toBoolean())
                    "--only" -> o.copy(only = SpriteImporter.parseRanges(v))
                    "--forms" -> o.copy(forms = v.toBoolean())
                    "--kinds" -> o.copy(kinds = v.split(',').map { it.trim() }.also { ks ->
                        ks.filter { it !in SpriteLayout.KINDS }.takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException("unknown kind(s) $it") }
                    })
                    else -> throw IllegalArgumentException("unknown option '$a'")
                }
            }
            return o
        }
    }
}
