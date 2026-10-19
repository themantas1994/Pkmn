package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.FormKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryImporterTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val content by lazy { Content.load(File(repo, "assets/data")) }
    private val mapping by lazy {
        SpriteMapping(Csv.read(File(repo, "data-src/pokeapi/pokemon.csv")), Csv.read(File(repo, "data-src/pokeapi/pokemon_forms.csv")),
            Csv.read(File(repo, "data-src/pokeapi/pokemon_species.csv")))
    }

    /** A minimal structurally valid Ogg stream (one BOS page, one EOS page) whose payload is [seed]. */
    private fun fakeOgg(seed: Int, truncate: Boolean = false, eos: Boolean = true): ByteArray {
        fun page(flags: Int, payload: ByteArray) = ByteArrayOutputStream().apply {
            write("OggS".toByteArray()); write(0); write(flags); write(ByteArray(20)); write(1); write(payload.size); write(payload)
        }.toByteArray()
        val b = page(0x02, byteArrayOf(seed.toByte(), 1, 2)) + page(if (eos) 0x04 else 0, byteArrayOf(seed.toByte(), 9, 9, 9))
        return if (truncate) b.copyOf(b.size - 2) else b
    }

    /** MPEG-1 Layer III, 128 kbit/s, 48 kHz, no padding: every frame is 384 bytes (header FF FB 94 C4). */
    private fun mp3Frame(xingFrames: Int? = null): ByteArray {
        val f = ByteArray(384); f[0] = 0xFF.toByte(); f[1] = 0xFB.toByte(); f[2] = 0x94.toByte(); f[3] = 0xC4.toByte()
        if (xingFrames != null) { // "Xing" at offset 36 (MPEG-1 stereo), flags = frames, then the count
            "Xing".toByteArray().copyInto(f, 36); f[43] = 1
            f[44] = (xingFrames shr 24).toByte(); f[45] = (xingFrames shr 16).toByte(); f[46] = (xingFrames shr 8).toByte(); f[47] = xingFrames.toByte()
        }
        return f
    }

    /** Some upstream ".ogg" files are really MP3 (Xing header): a Xing frame plus [audioFrames] audio frames. */
    private fun fakeMp3(audioFrames: Int = 3, xing: Boolean = true): ByteArray =
        (if (xing) mp3Frame(audioFrames) else ByteArray(0)) + (1..audioFrames).fold(ByteArray(0)) { acc, _ -> acc + mp3Frame() }

    @Test
    fun recognisesCompleteOggOnly() {
        assertEquals("mp3", CryImporter.audioKind(fakeMp3()))
        assertEquals("ogg", CryImporter.audioKind(fakeOgg(1)))
        assertEquals(null, CryImporter.audioKind(fakeOgg(1, truncate = true)))
        assertTrue(CryImporter.isCompleteOgg(fakeOgg(1)))
        assertFalse(CryImporter.isCompleteOgg(fakeOgg(1, truncate = true)))
        assertFalse(CryImporter.isCompleteOgg(fakeOgg(1, eos = false)))
        assertFalse(CryImporter.isCompleteOgg("<html>".toByteArray()))
    }

    @Test
    fun mp3MustBeACompleteFrameChain() {
        assertTrue(CryImporter.isCompleteMp3(fakeMp3()))
        assertTrue(CryImporter.isCompleteMp3(fakeMp3(xing = false)))
        // truncated inside a frame, in the header, to a single byte, and cut exactly at a frame boundary (Xing count mismatch)
        assertFalse(CryImporter.isCompleteMp3(fakeMp3().let { it.copyOf(it.size - 1) }))
        assertFalse(CryImporter.isCompleteMp3(fakeMp3().let { it.copyOf(it.size - 300) }))
        assertFalse(CryImporter.isCompleteMp3(fakeMp3().let { it.copyOf(it.size - 383) }))
        assertFalse(CryImporter.isCompleteMp3(fakeMp3().let { it.copyOf(it.size - 768) }), "Xing says 3 audio frames, only 1 present")
        assertFalse(CryImporter.isCompleteMp3(fakeMp3(xing = false).let { it.copyOf(it.size - 1) }))
        assertFalse(CryImporter.isCompleteMp3(byteArrayOf(0xFF.toByte())))
        // garbage after the chain, a frame header with reserved values, and plain text are not MP3
        assertFalse(CryImporter.isCompleteMp3(fakeMp3() + byteArrayOf(1, 2, 3)))
        assertFalse(CryImporter.isCompleteMp3(fakeMp3(xing = false).also { it[2] = 0xF4.toByte() }))
        assertFalse(CryImporter.isCompleteMp3("<html>not an mp3 at all, but long enough to pass a size check".repeat(20).toByteArray()))
        // ID3v2 tag in front and ID3v1 tag behind are fine; a cut inside the tagged stream is not
        val id3 = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val v1 = "TAG".toByteArray() + ByteArray(125)
        assertTrue(CryImporter.isCompleteMp3(id3 + fakeMp3() + v1))
        assertFalse(CryImporter.isCompleteMp3((id3 + fakeMp3() + v1).let { it.copyOf(it.size - 129) }))
        assertEquals("mp3", CryImporter.audioKind(id3 + fakeMp3()))
        assertEquals(null, CryImporter.audioKind(fakeMp3().let { it.copyOf(it.size - 1) }))
    }

    @Test
    fun mapsSpeciesToPokeApiCryIds() {
        fun id(s: String) = CryImporter.cryRef(content.dex.species(s), mapping)
        assertEquals(1, id("bulbasaur").pokeapiId)
        assertEquals(10034, id("charizardmegax").pokeapiId)
        assertEquals(10257, id("maushold").pokeapiId)
        // Arceus' types are cosmetic forms of #493: no file of their own, so the game uses the base species.
        assertEquals(null, id("arceusfire").pokeapiId)
        assertEquals("shares-base-cry", id("arceusfire").method)
        assertEquals("unmapped", id("ogerponwellspringtera").method)
    }

    @Test
    fun parsesOptions() {
        val o = CryImporter.parseArgs(listOf("--only=1-151 --forms=false", "--delay-ms=0", "--tolerate-failures"))
        assertEquals(listOf(1..151), o.only); assertFalse(o.forms); assertEquals(0, o.delayMs); assertTrue(o.tolerateFailures)
        kotlin.test.assertFailsWith<IllegalArgumentException> { CryImporter.parseArgs(listOf("--nope")) }
    }

    @Test
    fun importsWithFakeSourceAndReportsHonestCoverage() {
        val root = Files.createTempDirectory("cries").toFile()
        try {
            val wanted = listOf("bulbasaur", "ivysaur", "charizard", "charizardmegax", "arceus", "arceusfire", "pikachu")
            val sp = wanted.map { content.dex.species(it) }
            val requests = ArrayList<String>()
            val fetch: (String) -> ByteArray? = { url ->
                requests += url
                when (val n = url.substringAfterLast('/').removeSuffix(".ogg").toInt()) {
                    2 -> null // pretend PokeAPI lacks Ivysaur
                    25 -> fakeOgg(1) // Pikachu shares its bytes with Bulbasaur: a duplicate sha256 group
                    1 -> fakeOgg(1)
                    6 -> fakeMp3()
                    else -> fakeOgg(n)
                }
            }
            val importer = { CryImporter(root, listOf("--delay-ms=0", "--retries=0"), fetch, sp, mapping) }
            assertTrue(importer().run())
            val live = File(root, "assets-local/audio/cries")
            assertTrue(File(live, "bulbasaur.ogg").isFile)
            assertTrue(File(live, "charizardmegax.ogg").isFile)
            assertTrue(File(live, "charizard.mp3").isFile, "an MP3 behind an .ogg URL keeps its true extension")
            assertFalse(File(live, "charizard.ogg").exists())
            assertFalse(File(live, "ivysaur.ogg").exists(), "missing upstream must not produce a file")
            assertFalse(File(live, "arceusfire.ogg").exists(), "cosmetic form has no own file")
            assertTrue(File(root, "assets-local/audio/cries-raw/1.ogg").isFile)

            val cov = Json.parseToJsonElement(File(live, "COVERAGE.json").readText()).jsonObject
            val counts = cov["counts"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
            assertEquals(5, counts["covered"]) // bulbasaur, charizard, charizardmegax, arceus, pikachu
            assertEquals(1, counts["fallbackToBase"]) // arceusfire -> arceus
            assertEquals(1, counts["missingUpstream"]) // ivysaur: plays the generic blip, NOT covered
            assertEquals(6, cov["coveredTotal"]!!.jsonPrimitive.int)
            assertEquals(1, cov["duplicateSha256Groups"]!!.jsonPrimitive.int)
            assertEquals(wanted.size, counts.values.sum())
            val log = Json.parseToJsonElement(File(live, "IMPORT_LOG.json").readText()).jsonObject
            assertEquals(false, log["redistributable"]!!.jsonPrimitive.boolean)
            val first = log["files"]!!.jsonArray.first { it.jsonObject["speciesId"]!!.jsonPrimitive.content == "bulbasaur" }.jsonObject
            assertTrue(first["source"]!!.jsonPrimitive.content.endsWith("/1.ogg"))
            assertEquals(64, first["sha256"]!!.jsonPrimitive.content.length)

            // Resumable: a second run downloads only what is not cached (the upstream-missing id is asked again).
            requests.clear()
            assertTrue(importer().run())
            assertEquals(listOf("2.ogg"), requests.map { it.substringAfterLast('/') })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun transientAndTruncatedResponsesAreRetriedThenReported() {
        val root = Files.createTempDirectory("cries").toFile()
        try {
            var calls = 0
            val fetch: (String) -> ByteArray? = { calls++; if (calls == 1) fakeOgg(1, truncate = true) else fakeOgg(1) }
            val sp = listOf(content.dex.species("bulbasaur"))
            assertTrue(CryImporter(root, listOf("--delay-ms=0", "--retries=2"), fetch, sp, mapping).run())
            assertEquals(2, calls)
            val bad = CryImporter(root, listOf("--delay-ms=0", "--retries=1", "--force"), { fakeOgg(1, truncate = true) }, sp, mapping)
            assertFalse(bad.run())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun noCriesInPublicAssets() {
        val audio = File(repo, "assets/audio")
        val cries = audio.walkTopDown().filter { it.isFile && (it.extension == "ogg" || "cries" in it.invariantSeparatorsPath) }.toList()
        assertTrue(cries.isEmpty(), "official cries must stay in assets-local/: $cries")
        assertFalse(File(repo, "assets/audio/cries").exists())
        assertTrue(File(repo, ".gitignore").readLines().any { it.trim() == "assets-local/" })
        // Every base species maps to a cry, and forms never lose the base fallback.
        assertTrue(content.species.filter { it.formKind == FormKind.BASE }.all { CryImporter.cryRef(it, mapping).pokeapiId != null })
    }
}
