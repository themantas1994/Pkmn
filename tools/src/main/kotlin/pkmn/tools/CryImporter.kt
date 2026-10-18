package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.ContentJson
import pkmn.engine.data.FormKind
import pkmn.engine.data.SpeciesData
import java.io.File
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Imports official Pokémon cries for personal builds (user-authorised, local only; see docs/ASSET_IMPORT.md).
 *
 * Raw cache: `assets-local/audio/cries-raw/<pokeapiId>.ogg` (downloaded once, never modified).
 * Live set:  `assets-local/audio/cries/<speciesId>.ogg` (`.mp3` for the few upstream files that are really MP3) plus `IMPORT_LOG.json` and `COVERAGE.json` in the same folder.
 * Everything under `assets-local/` is git-ignored and must never be committed or redistributed.
 *
 * Usage: `tools cries [--only=1-151] [--forms=false] [--force] [--retries=3] [--delay-ms=250] [--base-url=<url>]
 *         [--offline] [--tolerate-failures]`. Downloads are sequential with a pause between requests, are skipped when
 * cached (so an interrupted run resumes), and are written through temp file + rename.
 *
 * [fetch] replaces the HTTP client (tests): the body, null for "not found", or [IOException] for transient failures.
 */
class CryImporter(private val root: File, args: List<String>, private val fetch: ((String) -> ByteArray?)? = null,
                  private val species: List<SpeciesData>? = null, private val mapping: SpriteMapping? = null) {
    data class Options(
        val only: List<IntRange>? = null,
        val forms: Boolean = true,
        val force: Boolean = false,
        val retries: Int = 3,
        val delayMs: Long = 250,
        val baseUrl: String = DEFAULT_BASE,
        val offline: Boolean = false,
        val tolerateFailures: Boolean = false,
    )

    val options = parseArgs(args)
    private val rawDir = File(root, RAW_REL)
    private val liveDir = File(root, LIVE_REL)
    private val retrieved = Instant.now().toString()

    /** What the cry of one of our species resolves to: its own PokeAPI id, or "use the base species". */
    data class CryRef(val pokeapiId: Int?, val method: String)

    fun run(): Boolean {
        val all = species ?: run {
            val f = File(root, "assets/data/species.json")
            if (!f.isFile) {
                System.err.println("ERROR: ${f.path} not found. Run from the repository root; generate it with ./gradlew :tools:importData.")
                return false
            }
            ContentJson.decodeFromString<List<SpeciesData>>(f.readText())
        }
        val map = mapping ?: SpriteMapping(Csv.read(File(root, "data-src/pokeapi/pokemon.csv")),
            Csv.read(File(root, "data-src/pokeapi/pokemon_forms.csv")), Csv.read(File(root, "data-src/pokeapi/pokemon_species.csv")))
        val refs = all.associate { it.id to cryRef(it, map) }
        val inScope = all.filter { sp -> (options.only == null || options.only.any { sp.num in it }) && (options.forms || sp.formKind == FormKind.BASE) }

        val previous = readPreviousLog()
        val missingUpstream = sortedSetOf<Int>()
        val failures = ArrayList<String>()
        var downloaded = 0; var cached = 0
        val ids = inScope.mapNotNull { refs[it.id]?.pokeapiId }.toSortedSet()
        if (!options.offline) {
            for (id in ids) {
                if (!options.force && rawFile(id) != null) { cached++; continue }
                try {
                    val body = download("${options.baseUrl.trimEnd('/')}/$id.ogg")
                    if (body == null) missingUpstream += id else {
                        // A few upstream ".ogg" files are really MP3; keep the true extension so the audio backends decode them.
                        SpriteImporter.writeAtomic(File(rawDir, "$id.${audioKind(body)}"), body); downloaded++
                    }
                } catch (e: Exception) {
                    failures += "$id: ${e::class.simpleName}: ${e.message}"
                }
                if (fetch == null && options.delayMs > 0) Thread.sleep(options.delayMs)
            }
        }

        // Live set: copy each mapped species' raw file. Files that exist are kept unless --force.
        var written = 0
        for (sp in inScope) {
            val id = refs[sp.id]?.pokeapiId ?: continue
            val raw = rawFile(id) ?: continue
            val bytes = raw.readBytes()
            val live = File(liveDir, "${sp.id}.${raw.extension}")
            val old = liveFile(liveDir, sp.id)
            if (old != null && old != live) old.delete() // the format of the upstream file changed
            if (live.isFile && !options.force && sha256(live.readBytes()) == sha256(bytes)) continue
            SpriteImporter.writeAtomic(live, bytes); written++
        }

        val files = sortedMapOf<String, JsonObject>()
        for (sp in all) {
            val ref = refs[sp.id] ?: continue
            val live = liveFile(liveDir, sp.id) ?: continue
            val bytes = live.readBytes()
            val sha = sha256(bytes)
            val prev = previous[sp.id]?.takeIf { it.first == sha }
            files[sp.id] = buildJsonObject {
                put("speciesId", sp.id); put("pokeapiId", ref.pokeapiId ?: -1); put("file", live.relativeTo(root).invariantSeparatorsPath)
                put("source", "${options.baseUrl.trimEnd('/')}/${ref.pokeapiId}.ogg"); put("sha256", sha); put("bytes", bytes.size)
                put("retrieved", prev?.second ?: retrieved); put("mapping", ref.method)
            }
        }
        writeJson(File(liveDir, "IMPORT_LOG.json"), buildJsonObject {
            put("tool", "pkmn.tools.CryImporter (./gradlew :tools:importCries)")
            put("redistributable", false)
            put("license", "Official Pokémon cries are © Nintendo/Creatures/GAME FREAK. Downloaded from PokeAPI/cries for personal use only; do not commit or redistribute.")
            put("source", options.baseUrl); put("lastRun", retrieved)
            putJsonObject("lastRunCounts") {
                put("downloaded", downloaded); put("cached", cached); put("liveWritten", written)
                put("missingUpstream", missingUpstream.size); put("failures", failures.size)
            }
            put("missingUpstream", JsonArray(missingUpstream.map { JsonPrimitive(it) }))
            put("failures", strArray(failures))
            put("files", JsonArray(files.values.toList()))
        })

        val cov = coverage(inScope, all, refs, liveDir, failedIds = failures.map { it.substringBefore(':').toInt() }.toSet(), upstreamMissing = missingUpstream)
        writeJson(File(liveDir, "COVERAGE.json"), cov.json)
        cov.summary.forEach(::println)
        println("Cries: $downloaded downloaded, $cached cached, $written live files written, ${missingUpstream.size} missing upstream, ${failures.size} failed. Log: ${File(liveDir, "IMPORT_LOG.json").relativeTo(root).path}")
        failures.take(20).forEach { println("  FAILED $it") }
        if (failures.isNotEmpty() && options.tolerateFailures && !options.offline && (downloaded + cached) > 0) {
            println("WARNING: ${failures.size} download(s) failed; continuing because --tolerate-failures is set. Run again to retry them (cached files are kept).")
            return true
        }
        return failures.isEmpty()
    }

    /** The cached raw download for a PokeAPI id (`.ogg` or, for the few MP3 files upstream, `.mp3`), if complete. */
    private fun rawFile(id: Int): File? = EXTENSIONS.map { File(rawDir, "$id.$it") }.firstOrNull { it.isFile && audioKind(it.readBytes()) == it.extension }

    private fun readPreviousLog(): Map<String, Pair<String, String>> = runCatching {
        Json.parseToJsonElement(File(liveDir, "IMPORT_LOG.json").readText()).jsonObject["files"]!!.jsonArray.associate { el ->
            val o = el.jsonObject
            o["speciesId"]!!.jsonPrimitive.content to (o["sha256"]!!.jsonPrimitive.content to o["retrieved"]!!.jsonPrimitive.content)
        }
    }.getOrDefault(emptyMap())

    private val client by lazy {
        HttpClient.newBuilder().proxy(ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    private fun httpGet(url: String): ByteArray? {
        val res = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        return when {
            res.statusCode() == 404 -> null
            res.statusCode() == 200 -> res.body()
            res.statusCode() == 429 || res.statusCode() >= 500 -> throw IOException("HTTP ${res.statusCode()}")
            else -> throw IllegalStateException("HTTP ${res.statusCode()} for $url")
        }
    }

    private fun download(url: String): ByteArray? {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val body = (fetch ?: ::httpGet)(url) ?: return null
                if (audioKind(body) == null) throw IOException("response is neither a complete Ogg nor an MP3 file")
                return body
            } catch (e: IOException) {
                if (attempt > options.retries) throw IOException("$url: ${e.message}", e)
                Thread.sleep(if (fetch == null) 1000L * (1 shl (attempt - 1)) else 1L)
            }
        }
    }

    class Coverage(val json: JsonObject, val summary: List<String>)

    companion object {
        const val DEFAULT_BASE = "https://raw.githubusercontent.com/PokeAPI/cries/main/cries/pokemon/latest"
        const val RAW_REL = "assets-local/audio/cries-raw"
        const val LIVE_REL = "assets-local/audio/cries"
        val EXTENSIONS = listOf("ogg", "mp3")

        fun liveFile(dir: File, speciesId: String): File? = EXTENSIONS.map { File(dir, "$speciesId.$it") }.firstOrNull { it.isFile }

        /** "ogg" for a complete Ogg stream, "mp3" for an MP3 stream (ID3 tag or frame sync), else null. */
        fun audioKind(b: ByteArray): String? = when {
            isCompleteOgg(b) -> "ogg"
            b.size >= 417 && ((b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) ||
                ((b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0)) -> "mp3"
            else -> null
        }

        /**
         * PokeAPI id whose cry file belongs to [sp], or null with a method explaining why not. A form whose PokeAPI
         * stem is `<id>-<form>` (a cosmetic form of the same Pokémon) shares the base Pokémon's file and so uses the
         * base species at runtime instead of a duplicate copy.
         */
        fun cryRef(sp: SpeciesData, mapping: SpriteMapping): CryRef {
            val ref = mapping.map(sp) ?: return CryRef(null, "unmapped")
            val numeric = ref.stem.toIntOrNull()
            if (numeric != null) return CryRef(numeric, ref.method)
            val lead = ref.stem.substringBefore('-').toIntOrNull() ?: return CryRef(null, "unmapped")
            return if (sp.formKind != FormKind.BASE) CryRef(null, "shares-base-cry") else CryRef(lead, ref.method)
        }

        /**
         * Coverage from the files actually on disk. Every species in [inScope] lands in exactly one bucket:
         * covered (own live file), fallbackToBase (form without own file whose base species has one),
         * failed / missingUpstream / unmapped / missing (the game plays the generic blip). Only `covered` and
         * `fallbackToBase` count as covered.
         */
        fun coverage(inScope: List<SpeciesData>, all: List<SpeciesData>, refs: Map<String, CryRef>, liveDir: File,
                     failedIds: Set<Int> = emptySet(), upstreamMissing: Set<Int> = emptySet()): Coverage {
            fun has(id: String) = liveFile(liveDir, id) != null
            val byId = all.associateBy { it.id }
            val buckets = linkedMapOf("covered" to ArrayList<String>(), "fallbackToBase" to ArrayList(), "failed" to ArrayList(),
                "missingUpstream" to ArrayList(), "unmapped" to ArrayList(), "missing" to ArrayList())
            for (sp in inScope) {
                val ref = refs[sp.id]
                val baseId = byId[toIdSafe(sp.baseSpecies)]?.id
                val bucket = when {
                    has(sp.id) -> "covered"
                    sp.formKind != FormKind.BASE && baseId != null && baseId != sp.id && has(baseId) -> "fallbackToBase"
                    ref?.pokeapiId == null -> "unmapped"
                    ref.pokeapiId in failedIds -> "failed"
                    ref.pokeapiId in upstreamMissing -> "missingUpstream"
                    else -> "missing"
                }
                buckets.getValue(bucket) += sp.id
            }
            val dupGroups = inScope.filter { has(it.id) }.groupBy { sha256(liveFile(liveDir, it.id)!!.readBytes()) }.values
                .filter { it.size > 1 }.map { g -> g.map { it.id }.sorted() }.sortedBy { it.first() }
            val json = buildJsonObject {
                put("scope", "species and forms in assets/data/species.json within the requested --only/--forms")
                put("inScope", inScope.size)
                putJsonObject("counts") { buckets.forEach { (k, v) -> put(k, v.size) } }
                put("coveredTotal", buckets.getValue("covered").size + buckets.getValue("fallbackToBase").size)
                put("duplicateSha256Groups", dupGroups.size)
                putJsonArray("duplicateGroups") { dupGroups.forEach { add(strArray(it)) } }
                putJsonObject("species") { buckets.forEach { (k, v) -> put(k, strArray(v.sorted())) } }
            }
            val b = buckets.mapValues { it.value.size }
            val summary = listOf(
                "Cries: ${inScope.size} species/forms in scope: ${b["covered"]} own file, ${b["fallbackToBase"]} form -> base species, " +
                    "${b["unmapped"]} unmapped, ${b["missingUpstream"]} not upstream, ${b["failed"]} failed, ${b["missing"]} missing (generic blip). " +
                    "${dupGroups.size} duplicate sha256 group(s).",
            )
            return Coverage(json, summary)
        }

        private fun toIdSafe(name: String) = pkmn.engine.util.toId(name)

        /** True if [b] is a structurally complete Ogg stream: whole pages up to one carrying the end-of-stream flag. */
        fun isCompleteOgg(b: ByteArray): Boolean {
            var o = 0
            var lastFlags = 0
            var pages = 0
            while (o + 27 <= b.size) {
                if (b[o] != 'O'.code.toByte() || b[o + 1] != 'g'.code.toByte() || b[o + 2] != 'g'.code.toByte() || b[o + 3] != 'S'.code.toByte()) return false
                val segs = b[o + 26].toInt() and 0xFF
                if (o + 27 + segs > b.size) return false
                var body = 0
                for (i in 0 until segs) body += b[o + 27 + i].toInt() and 0xFF
                val end = o + 27 + segs + body
                if (end > b.size) return false
                lastFlags = b[o + 5].toInt() and 0xFF
                pages++
                o = end
            }
            return pages > 0 && o == b.size && (lastFlags and 0x04) != 0
        }

        fun parseArgs(args: List<String>): Options {
            var o = Options()
            for (a in SpriteImporter.splitOptions(args)) {
                val (k, v) = if ('=' in a) a.substringBefore('=') to a.substringAfter('=') else a to "true"
                o = when (k) {
                    "--only" -> o.copy(only = SpriteImporter.parseRanges(v))
                    "--forms" -> o.copy(forms = v.toBoolean())
                    "--force" -> o.copy(force = v.toBoolean())
                    "--retries" -> o.copy(retries = v.toInt())
                    "--delay-ms" -> o.copy(delayMs = v.toLong())
                    "--base-url" -> o.copy(baseUrl = v)
                    "--offline" -> o.copy(offline = v.toBoolean())
                    "--tolerate-failures" -> o.copy(tolerateFailures = v.toBoolean())
                    else -> throw IllegalArgumentException("unknown option '$a'")
                }
            }
            return o
        }
    }
}
