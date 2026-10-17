package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.ContentJson
import pkmn.engine.data.FormKind
import pkmn.engine.data.SpeciesData
import pkmn.engine.util.SpriteArchive
import java.io.File
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Imports Pokémon sprites for personal builds into `assets-local/sprites/pokemon/<kind>/<speciesId>.png`
 * (git-ignored, never redistributed). See docs/ASSET_IMPORT.md.
 *
 * Usage: `tools sprites [--only=1-151[,152-251]] [--force] [--forms=false] [--kinds=front,back] [--jobs=8]
 *         [--retries=3] [--from=<dir|zip>] [--base-url=<url>] [--dest=<dir>] [--offline] [--tolerate-failures]`
 *
 * `--dest` redirects the `<kind>/<id>.png` tree (the personal APK pipeline uses the raw cache
 * `assets-local/sprites-raw/pokemon`); IMPORT_LOG.json is then written next to it. Files are written via
 * temp file + rename, so an interrupted run never leaves a truncated PNG behind.
 *
 * [fetch] replaces the HTTP client (tests): it returns the body, null for "not found", or throws [IOException]
 * for transient failures.
 */
class SpriteImporter(private val root: File, args: List<String>, private val fetch: ((String) -> ByteArray?)? = null) {
    data class Options(
        val only: List<IntRange>? = null,
        val force: Boolean = false,
        val forms: Boolean = true,
        val kinds: List<String> = SpriteLayout.KINDS,
        val jobs: Int = 8,
        val retries: Int = 3,
        val from: File? = null,
        val baseUrl: String = DEFAULT_BASE,
        /** Destination of the `<kind>/<id>.png` tree, relative to the repository root unless absolute. */
        val dest: File? = null,
        /** No network access: only `--from` imports run; existing files and their log entries are kept as they are. */
        val offline: Boolean = false,
        /** Network mode only: individual failed downloads do not fail the run as long as something was imported (rerun to retry). */
        val tolerateFailures: Boolean = false,
    )

    val options: Options = parseArgs(args)
    private val outDir = options.dest?.let { if (it.isAbsolute) it else File(root, it.path) } ?: SpriteLayout.localDir(root)
    private val logFile = File(outDir.parentFile, "IMPORT_LOG.json")
    private val retrieved = Instant.now().toString()

    data class Entry(val speciesId: String, val kind: String, val file: String, val source: String, val sha256: String,
                     val bytes: Long, val retrieved: String, val mapping: String?) {
        fun toJson() = buildJsonObject {
            put("speciesId", speciesId); put("kind", kind); put("file", file); put("source", source)
            put("sha256", sha256); put("bytes", bytes); put("retrieved", retrieved); mapping?.let { put("mapping", it) }
        }
    }

    fun run(): Boolean {
        val speciesFile = File(root, "assets/data/species.json")
        if (!speciesFile.isFile) {
            System.err.println("ERROR: ${speciesFile.path} not found. Run from the repository root (or pass --root=<repo>); generate it with ./gradlew :tools:importData.")
            return false
        }
        val species = ContentJson.decodeFromString<List<SpeciesData>>(speciesFile.readText())
        val selected = species.filter { sp ->
            (options.only == null || options.only.any { sp.num in it }) && (options.forms || sp.formKind == FormKind.BASE)
        }
        val entries = ConcurrentLinkedQueue<Entry>()
        val missing = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<String>()
        val unmapped = ArrayList<String>()
        var skipped = 0
        val previous = readPreviousLog()

        if (options.from != null) {
            importFrom(options.from, species.map { it.id }.toSet(), entries, failures)
        } else if (!options.offline) {
            val mapping = SpriteMapping(
                Csv.read(File(root, "data-src/pokeapi/pokemon.csv")),
                Csv.read(File(root, "data-src/pokeapi/pokemon_forms.csv")),
                Csv.read(File(root, "data-src/pokeapi/pokemon_species.csv")),
            )
            val pool = Executors.newFixedThreadPool(options.jobs.coerceIn(1, 32))
            for (sp in selected) {
                val ref = mapping.map(sp)
                if (ref == null) { unmapped += sp.id; continue }
                for (kind in options.kinds) {
                    val dest = File(outDir, "$kind/${sp.id}.png")
                    if (dest.isFile && !options.force) {
                        skipped++
                        val rel = dest.relativeTo(root).invariantSeparatorsPath
                        entries += previous[sp.id to kind]?.takeIf { it.sha256 == sha256(dest.readBytes()) }
                            ?: Entry(sp.id, kind, rel, "existing file (source unknown)", sha256(dest.readBytes()), dest.length(), retrieved, null)
                        continue
                    }
                    pool.submit {
                        try {
                            val got = candidatesFor(kind, ref.stem).firstNotNullOfOrNull { url -> download(url)?.let { url to it } }
                            if (got == null) missing += "${sp.id}:$kind"
                            else {
                                writeAtomic(dest, got.second)
                                entries += Entry(sp.id, kind, dest.relativeTo(root).invariantSeparatorsPath, got.first, sha256(got.second),
                                    got.second.size.toLong(), retrieved, "${ref.method}:${ref.pokeapiIdentifier}")
                            }
                        } catch (e: Exception) {
                            failures += "${sp.id}:$kind: ${e::class.simpleName}: ${e.message}"
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.HOURS)
        }

        // Keep log entries of species outside this run's selection so the log describes everything on disk.
        val merged = LinkedHashMap<Pair<String, String>, Entry>()
        previous.values.filter { File(root, it.file).isFile }.forEach { merged[it.speciesId to it.kind] = it }
        entries.forEach { merged[it.speciesId to it.kind] = it }
        writeJson(logFile, buildJsonObject {
            put("tool", "pkmn.tools.SpriteImporter (./gradlew :tools:importSprites)")
            put("layout", if (outDir.canonicalFile == SpriteLayout.localDir(root).canonicalFile) "live" else "raw-cache")
            put("redistributable", false)
            put("license", "Official Pokémon sprites are © Nintendo/Creatures/GAME FREAK. Imported for personal use only; do not commit or redistribute.")
            put("source", options.from?.path ?: if (options.offline) "offline (existing files only)" else options.baseUrl)
            put("lastRun", retrieved)
            putJsonObject("lastRunOptions") {
                put("only", options.only?.joinToString(",") { "${it.first}-${it.last}" } ?: "all")
                put("force", options.force); put("forms", options.forms); put("kinds", strArray(options.kinds)); put("jobs", options.jobs)
                put("offline", options.offline)
            }
            putJsonObject("lastRunCounts") {
                put("speciesSelected", if (options.from != null || options.offline) 0 else selected.size)
                put("written", entries.count { it.retrieved == retrieved && !it.source.startsWith("existing") })
                put("skippedExisting", skipped)
                put("missingUpstream", missing.size); put("unmapped", unmapped.size); put("failures", failures.size)
            }
            put("missing", strArray(missing.sorted()))
            put("unmapped", strArray(unmapped.sorted()))
            put("failures", strArray(failures.sorted()))
            put("files", JsonArray(merged.values.sortedWith(compareBy({ it.kind }, { it.speciesId })).map { it.toJson() }))
        })
        println("Sprites: ${entries.size - skipped} written, $skipped already present, ${missing.size} not available upstream, " +
            "${unmapped.size} unmapped, ${failures.size} failed. Log: ${logFile.relativeTo(root).path}")
        failures.take(20).forEach { println("  FAILED $it") }
        if (failures.isNotEmpty() && options.tolerateFailures && options.from == null && !options.offline && entries.isNotEmpty()) {
            println("WARNING: ${failures.size} download(s) failed; continuing because --tolerate-failures is set. Run again to retry them (existing files are kept).")
            return true
        }
        return failures.isEmpty()
    }

    fun candidatesFor(kind: String, stem: String): List<String> {
        val b = options.baseUrl.trimEnd('/')
        return when (kind) {
            "front" -> listOf("$b/$stem.png")
            "back" -> listOf("$b/back/$stem.png")
            "front-shiny" -> listOf("$b/shiny/$stem.png")
            "back-shiny" -> listOf("$b/back/shiny/$stem.png")
            // Gen VIII menu icons cover #1-905 and most forms; Gen VII icons are the fallback.
            "icon" -> listOf("$b/versions/generation-viii/icons/$stem.png", "$b/versions/generation-vii/icons/$stem.png")
            else -> emptyList()
        }
    }

    private val client by lazy {
        HttpClient.newBuilder().proxy(ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    /** One HTTP attempt: the body, null on 404, [IOException] for transient failures. */
    private fun httpGet(url: String): ByteArray? {
        val res = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray())
        return when {
            res.statusCode() == 404 -> null
            res.statusCode() == 200 -> res.body()
            res.statusCode() == 429 || res.statusCode() >= 500 -> throw IOException("HTTP ${res.statusCode()}")
            else -> throw IllegalStateException("HTTP ${res.statusCode()} for $url")
        }
    }

    /** Returns the PNG bytes, null on 404, and retries transient failures (including truncated bodies) with backoff. */
    private fun download(url: String): ByteArray? {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val body = (fetch ?: ::httpGet)(url) ?: return null
                if (!isPng(body)) throw IOException("response is not a PNG")
                if (!isCompletePng(body)) throw IOException("response is a truncated PNG")
                return body
            } catch (e: IOException) {
                if (attempt > options.retries) throw IOException("$url: ${e.message}", e)
                Thread.sleep(500L * (1 shl (attempt - 1)))
            }
        }
    }

    /** Imports `<kind>/<speciesId>.png` files from a user-supplied directory or zip (any leading path is ignored). */
    private fun importFrom(src: File, ids: Set<String>, entries: MutableCollection<Entry>, failures: MutableCollection<String>) {
        val pattern = Regex("(?:^|.*/)(${SpriteLayout.KINDS.joinToString("|") { Regex.escape(it) }})/([a-z0-9]+)\\.png$")
        fun accept(path: String, bytes: () -> ByteArray?, sourceUri: String) {
            val m = pattern.matchEntire(path.replace('\\', '/')) ?: return
            val (kind, id) = m.destructured
            if (kind !in options.kinds) return
            if (id !in ids) { failures += "$path: '$id' is not a species id"; return }
            val dest = File(outDir, "$kind/$id.png")
            if (dest.isFile && !options.force) return
            val data = bytes()
            if (data == null) { failures += "$path: larger than ${MAX_ENTRY_BYTES / 1024 / 1024} MiB, skipped"; return }
            if (!isPng(data)) { failures += "$path: not a PNG"; return }
            writeAtomic(dest, data)
            entries += Entry(id, kind, dest.relativeTo(root).invariantSeparatorsPath, sourceUri, sha256(data), data.size.toLong(), retrieved, "user-supplied")
        }
        when {
            src.isDirectory -> src.walkTopDown().filter { it.isFile }.forEach { f ->
                accept(f.relativeTo(src).invariantSeparatorsPath, { f.takeIf { it.length() <= MAX_ENTRY_BYTES }?.readBytes() }, f.absoluteFile.toURI().toString())
            }
            src.isFile && src.extension.lowercase() == "zip" -> importZip(src, ::accept, failures)
            else -> failures += "--from=${src.path}: not a directory or .zip file"
        }
    }

    /**
     * Zips: a malformed archive is a clear failure and entries with `..`, absolute or drive-letter paths are rejected.
     * Archives in the documented `sprites/` layout are extracted by the shared [SpriteArchive] (path traversal and size
     * limits) into a scratch directory inside assets-local; loose `<kind>/<id>.png` packs are read entry by entry.
     * Either way the destination path is built from the species id, never from the entry name.
     */
    private fun importZip(src: File, accept: (String, () -> ByteArray?, String) -> Unit, failures: MutableCollection<String>) {
        val names = try {
            ZipFile(src).use { z -> z.entries().asSequence().map { it.name }.toList() }
        } catch (e: ZipException) {
            failures += "--from=${src.path}: not a valid zip file (${e.message})"; return
        } catch (e: IOException) {
            failures += "--from=${src.path}: cannot read zip file (${e.message})"; return
        }
        names.filter { isUnsafeZipName(it) }.forEach { failures += "$it: rejected unsafe path in zip" }
        val zipUri = src.absoluteFile.toURI()
        val documented = names.any { n -> n.replace('\\', '/').let { it.startsWith("sprites/") || it.startsWith("assets-local/sprites/") } }
        if (documented) {
            val scratch = File(root, "assets-local/.zip-extract.tmp")
            scratch.deleteRecursively()
            try {
                src.inputStream().use { SpriteArchive.extract(it, scratch) }
                val base = File(scratch, "sprites")
                base.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    val rel = f.relativeTo(base).invariantSeparatorsPath
                    accept(rel, { f.readBytes() }, "$zipUri!/sprites/$rel")
                }
            } finally { scratch.deleteRecursively() }
        } else {
            ZipFile(src).use { z ->
                z.entries().asSequence().filter { !it.isDirectory && !isUnsafeZipName(it.name) }.forEach { e ->
                    accept(e.name, {
                        z.getInputStream(e).use { it.readNBytes(MAX_ENTRY_BYTES + 1) }.takeIf { it.size <= MAX_ENTRY_BYTES }
                    }, "$zipUri!/${e.name}")
                }
            }
        }
    }

    private fun readPreviousLog(): Map<Pair<String, String>, Entry> = runCatching {
        Json.parseToJsonElement(logFile.readText()).jsonObject["files"]!!.jsonArray.associate { el ->
            val o = el.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            val e = Entry(s("speciesId")!!, s("kind")!!, s("file")!!, s("source")!!, s("sha256")!!, o["bytes"]!!.jsonPrimitive.long, s("retrieved")!!, s("mapping"))
            (e.speciesId to e.kind) to e
        }
    }.getOrDefault(emptyMap())

    companion object {
        const val DEFAULT_BASE = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon"
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

        fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

        /**
         * True when the chunk structure of the PNG reaches a complete IEND chunk, i.e. the download was not cut off.
         * Trailing bytes after IEND are tolerated (some upstream files have them).
         */
        fun isCompletePng(b: ByteArray): Boolean {
            if (!isPng(b)) return false
            var o = 8
            while (o + 12 <= b.size) {
                val len = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
                if (len < 0 || o + 12 + len > b.size) return false
                if (b[o + 4] == 'I'.code.toByte() && b[o + 5] == 'E'.code.toByte() && b[o + 6] == 'N'.code.toByte() && b[o + 7] == 'D'.code.toByte()) return true
                o += 12 + len.toInt()
            }
            return false
        }

        fun isUnsafeZipName(name: String): Boolean {
            val n = name.replace('\\', '/')
            return n.startsWith("/") || n.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(n) || n.split('/').any { it == ".." }
        }

        /** Writes via a sibling temp file and an atomic rename: readers never see a partial file. */
        fun writeAtomic(dest: File, data: ByteArray) {
            dest.parentFile.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            try {
                tmp.writeBytes(data)
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { tmp.delete() }
        }

        fun parseRanges(spec: String): List<IntRange> = spec.split(',').filter { it.isNotBlank() }.map { part ->
            val p = part.trim().split('-')
            when (p.size) {
                1 -> p[0].toInt()..p[0].toInt()
                2 -> p[0].toInt()..p[1].toInt()
                else -> throw IllegalArgumentException("bad range '$part'")
            }
        }

        /** Splits one string holding several options ("--only=1-30 --forms=false") before each "--", so paths with spaces survive. */
        fun splitOptions(args: List<String>): List<String> = args.flatMap { it.trim().split(Regex("\\s+(?=--)")) }.filter { it.isNotBlank() }

        fun parseArgs(args: List<String>): Options {
            var o = Options()
            for (a in splitOptions(args)) {
                val (k, v) = if ('=' in a) a.substringBefore('=') to a.substringAfter('=') else a to "true"
                o = when (k) {
                    "--only" -> o.copy(only = parseRanges(v))
                    "--force" -> o.copy(force = v.toBoolean())
                    "--forms" -> o.copy(forms = v.toBoolean())
                    "--kinds" -> o.copy(kinds = v.split(',').map { it.trim() }.also { ks ->
                        ks.filter { it !in SpriteLayout.KINDS }.takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException("unknown kind(s) $it") }
                    })
                    "--jobs" -> o.copy(jobs = v.toInt())
                    "--retries" -> o.copy(retries = v.toInt())
                    "--from" -> o.copy(from = File(v))
                    "--base-url" -> o.copy(baseUrl = v)
                    "--dest" -> o.copy(dest = File(v))
                    "--offline" -> o.copy(offline = v.toBoolean())
                    "--tolerate-failures" -> o.copy(tolerateFailures = v.toBoolean())
                    else -> throw IllegalArgumentException("unknown option '$a'")
                }
            }
            return o
        }
    }
}
