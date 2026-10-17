package pkmn.tools

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class SpriteStylerTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile

    // ------------------------------------------------------------------ fixtures

    /** A smooth, many-coloured blob on a transparent canvas with a semi-transparent halo: the worst case for the styler. */
    private fun blob(w: Int = 96, h: Int = 96): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val cx = w / 2.0; val cy = h / 2.0; val r = minOf(w, h) * 0.38
        for (y in 0 until h) for (x in 0 until w) {
            val d = Math.hypot(x - cx, y - cy)
            val a = when { d <= r -> 255; d <= r + 3 -> 90 + ((r + 3 - d) * 40).toInt(); else -> 0 }
            if (a > 0) img.setRGB(x, y, (a shl 24) or (((x * 255 / w) and 255) shl 16) or (((y * 255 / h) and 255) shl 8) or ((x + y) * 3 and 255))
        }
        return img
    }

    private fun pngBytes(img: BufferedImage = blob()): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("sprite-style").toFile()
        try {
            File(root, "assets/data").mkdirs()
            File(repo, "assets/data/species.json").copyTo(File(root, "assets/data/species.json"))
            File(root, "data-src/pokeapi").mkdirs()
            for (f in listOf("pokemon", "pokemon_forms", "pokemon_species")) File(repo, "data-src/pokeapi/$f.csv").copyTo(File(root, "data-src/pokeapi/$f.csv"))
            block(root)
        } finally { root.deleteRecursively() }
    }

    private fun raw(root: File, kind: String, id: String, bytes: ByteArray = pngBytes()) =
        File(root, "assets-local/sprites-raw/pokemon/$kind/$id.png").also { it.parentFile.mkdirs(); it.writeBytes(bytes) }

    private fun live(root: File) = File(root, "assets-local/sprites/pokemon")
    private fun liveHashes(root: File): Map<String, String> = live(root).walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(live(root)).invariantSeparatorsPath to sha256(it.readBytes()) }.toSortedMap()
    private fun pipeline(root: File, vararg args: String) = SpriteStylePipeline(root, args.toList())
    private fun report(root: File, name: String) = Json.parseToJsonElement(File(root, "assets-local/sprites/$name").readText()).jsonObject

    // ------------------------------------------------------------------ styler

    @Test
    fun battleSpriteIs96x96WithAtMostFourColoursAndBinaryAlpha() {
        val out = SpriteStyler.style(blob(), "front")
        assertEquals(96, out.width); assertEquals(96, out.height)
        assertNull(SpriteStyler.validate(out, "front"))
        assertTrue(SpriteStyler.opaqueColours(out).size in 1..4)
        val alphas = (0 until 96).flatMap { y -> (0 until 96).map { x -> out.getRGB(x, y) ushr 24 } }.toSet()
        assertTrue(alphas.all { it == 0 || it == 255 }, "alpha values: $alphas")
        for (c in SpriteStyler.opaqueColours(out)) for (shift in listOf(16, 8, 0)) {
            val v = c shr shift and 255
            assertTrue(v % 8 == 0 && v <= 248, "channel $v is not a multiple of 8 up to 248")
        }
    }

    @Test
    fun battleSpriteIsAChunky3xGrid() {
        val out = SpriteStyler.style(blob(), "back")
        for (y in 0 until 96) for (x in 0 until 96) assertEquals(out.getRGB(x / 3 * 3, y / 3 * 3), out.getRGB(x, y))
    }

    @Test
    fun iconIs16x16FromNonSquareInputWithoutCropping() {
        val out = SpriteStyler.style(blob(68, 56), "icon")
        assertEquals(16, out.width); assertEquals(16, out.height)
        assertNull(SpriteStyler.validate(out, "icon"))
        // The 68x56 canvas is fitted as 16x13 and centred: the top row (outside the fitted area) stays transparent.
        assertTrue((0 until 16).all { out.getRGB(it, 0) ushr 24 == 0 })
    }

    @Test
    fun squareSpriteTouchingTheCanvasEdgeKeepsAllContent() {
        val full = BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 20) for (x in 0 until 40) full.setRGB(x, y, if (x < 20) 0xFFCC2020.toInt() else 0xFF2020CC.toInt())
        val out = SpriteStyler.style(full, "icon")
        val colours = SpriteStyler.opaqueColours(out)
        assertTrue(colours.any { it shr 16 and 255 > 150 } && colours.any { it and 255 > 150 }, "both halves must survive the downscale")
    }

    @Test
    fun outputIsDeterministic() {
        val a = SpriteStyler.encodePng(SpriteStyler.style(blob(), "front"))
        val b = SpriteStyler.encodePng(SpriteStyler.style(SpriteStyler.decode(pngBytes()), "front"))
        assertContentEquals(a, b)
        assertContentEquals(SpriteStyler.encodePng(SpriteStyler.style(blob(), "icon")), SpriteStyler.encodePng(SpriteStyler.style(blob(), "icon")))
    }

    @Test
    fun outlineNeverAddsAColourAndUsesTheDarkestOne() {
        val plain = SpriteStyler.style(blob(), "icon", outline = false)
        val outlined = SpriteStyler.style(blob(), "icon", outline = true)
        val plainColours = SpriteStyler.opaqueColours(plain)
        val outlinedColours = SpriteStyler.opaqueColours(outlined)
        assertTrue(outlinedColours.size <= 4)
        assertTrue(plainColours.containsAll(outlinedColours), "outline introduced a colour: $outlinedColours vs $plainColours")
        fun luma(c: Int) = 0.2126 * (c shr 16 and 255) + 0.7152 * (c shr 8 and 255) + 0.0722 * (c and 255)
        val darkest = plainColours.minBy { luma(it) }
        for (y in 0 until 16) for (x in 0 until 16) {
            if (outlined.getRGB(x, y) ushr 24 == 0) continue
            val edge = x == 0 || y == 0 || x == 15 || y == 15 || listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dy) -> outlined.getRGB(x + dx, y + dy) ushr 24 == 0 }
            if (edge) assertEquals(darkest, outlined.getRGB(x, y) and 0xFFFFFF, "edge pixel $x,$y")
        }
    }

    @Test
    fun invalidInputIsRejectedNotCrashing() {
        assertFailsWith<IllegalArgumentException> { SpriteStyler.decode(byteArrayOf(1, 2, 3, 4)) }
        assertFailsWith<IllegalArgumentException> { SpriteStyler.decode(pngBytes().copyOf(60)) }
        assertFailsWith<IllegalArgumentException> { SpriteStyler.style(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "front") }
    }

    @Test
    fun validateFlagsBadOutput() {
        assertNotNull(SpriteStyler.validate(BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "front"))
        val five = BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB)
        for (i in 0 until 5) five.setRGB(i, 0, (0xFF shl 24) or (i * 8 shl 16))
        assertTrue(SpriteStyler.validate(five, "front")!!.contains("colours"))
        val halfAlpha = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, 0x80102030.toInt()) }
        assertTrue(SpriteStyler.validate(halfAlpha, "icon")!!.contains("alpha"))
    }

    // ------------------------------------------------------------------ pipeline

    @Test
    fun stylesRawCacheIntoLiveSetAndNeverTouchesRaw() = withRoot { root ->
        val rawFiles = listOf(raw(root, "front", "bulbasaur"), raw(root, "icon", "bulbasaur", pngBytes(blob(68, 56))), raw(root, "back", "ivysaur"))
        val before = rawFiles.map { sha256(it.readBytes()) }
        assertTrue(pipeline(root, "--only=1-3").run())
        assertEquals(before, rawFiles.map { sha256(it.readBytes()) })
        for ((kind, id, size) in listOf(Triple("front", "bulbasaur", 96), Triple("icon", "bulbasaur", 16), Triple("back", "ivysaur", 96))) {
            val img = ImageIO.read(File(live(root), "$kind/$id.png"))
            assertEquals(size, img.width); assertNull(SpriteStyler.validate(img, kind))
        }
        assertFalse(File(root, "assets-local/sprites-styled.tmp").exists())
        assertFalse(File(root, "assets-local/sprites/pokemon.bak").exists())
        val rep = report(root, "STYLE_REPORT.json")
        assertEquals(3, rep["counts"]!!.jsonObject["converted"]!!.jsonPrimitive.int)
        assertEquals("gbc", rep["styleMode"]!!.jsonPrimitive.content)
        assertEquals(4, rep["style"]!!.jsonObject["opaqueColorLimit"]!!.jsonPrimitive.int)
        assertEquals(3, rep["files"]!!.jsonArray.size)
        assertEquals(sha256(File(live(root), "front/bulbasaur.png").readBytes()),
            rep["files"]!!.jsonArray.first { it.jsonObject["speciesId"]!!.jsonPrimitive.content == "bulbasaur" && it.jsonObject["kind"]!!.jsonPrimitive.content == "front" }
                .jsonObject["sha256"]!!.jsonPrimitive.content)
    }

    @Test
    fun repeatedRunsAreIdempotentAndNeverRestyleOutput() = withRoot { root ->
        raw(root, "front", "bulbasaur"); raw(root, "icon", "ivysaur", pngBytes(blob(40, 30)))
        assertTrue(pipeline(root).run())
        val first = liveHashes(root); val firstSet = report(root, "STYLE_REPORT.json")["outputSetSha256"]
        assertTrue(pipeline(root).run())
        assertEquals(first, liveHashes(root))
        assertEquals(firstSet, report(root, "STYLE_REPORT.json")["outputSetSha256"])
        // Every report entry derives from the raw cache file (checksum of the untouched source), never from live output.
        for (f in report(root, "STYLE_REPORT.json")["files"]!!.jsonArray) {
            val o = f.jsonObject
            val source = File(root, o["source"]!!.jsonPrimitive.content)
            assertTrue(o["source"]!!.jsonPrimitive.content.startsWith("assets-local/sprites-raw/"))
            assertEquals(sha256(source.readBytes()), o["sourceSha256"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun corruptRawFileIsRecordedAndSkipped() = withRoot { root ->
        raw(root, "front", "bulbasaur"); raw(root, "front", "ivysaur", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9))
        raw(root, "front", "Bad-Name"); raw(root, "back", "venusaur", ByteArray(0))
        assertTrue(pipeline(root, "--only=1-3", "--kinds=front").run())
        assertTrue(File(live(root), "front/bulbasaur.png").isFile)
        assertFalse(File(live(root), "front/ivysaur.png").exists())
        val rep = report(root, "STYLE_REPORT.json")
        assertEquals(3, rep["failures"]!!.jsonArray.size)
        val cov = report(root, "COVERAGE.json")["kinds"]!!.jsonObject["front"]!!.jsonObject
        assertEquals(1, cov["imported"]!!.jsonPrimitive.int)
        assertEquals(1, cov["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun missingOrEmptyRawCacheFailsClearlyAndKeepsLive() = withRoot { root ->
        assertFalse(pipeline(root).run())
        raw(root, "front", "bulbasaur"); assertTrue(pipeline(root).run())
        val before = liveHashes(root)
        File(root, "assets-local/sprites-raw").deleteRecursively()
        assertFalse(pipeline(root).run())
        File(root, "assets-local/sprites-raw/pokemon/front").mkdirs()
        assertFalse(pipeline(root).run())
        assertEquals(before, liveHashes(root))
    }

    @Test
    fun strictFailureKeepsThePreviousLiveSet() = withRoot { root ->
        raw(root, "front", "bulbasaur"); assertTrue(pipeline(root).run())
        val before = liveHashes(root)
        raw(root, "front", "ivysaur", byteArrayOf(1, 2, 3)); raw(root, "front", "bulbasaur", pngBytes(blob(50, 50)))
        assertFalse(pipeline(root, "--strict").run())
        assertEquals(before, liveHashes(root))
        assertFalse(File(root, "assets-local/sprites-styled.tmp").exists())
        assertTrue(File(root, "assets-local/STYLE_REPORT.failed.json").isFile)
    }

    @Test
    fun invalidStylerOutputNeverGoesLive() = withRoot { root ->
        raw(root, "front", "bulbasaur"); assertTrue(pipeline(root).run())
        val before = liveHashes(root)
        val fiveColours = { _: BufferedImage, _: String, _: Boolean ->
            BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB).also { img -> for (i in 0 until 5) img.setRGB(i, 0, (0xFF shl 24) or (i * 8 shl 16)) }
        }
        raw(root, "front", "ivysaur")
        assertFalse(SpriteStylePipeline(root, emptyList(), styler = fiveColours).run())
        assertEquals(before, liveHashes(root))
    }

    @Test
    fun failedRenameRestoresThePreviousSet() = withRoot { root ->
        raw(root, "front", "bulbasaur"); assertTrue(pipeline(root).run())
        val before = liveHashes(root)
        raw(root, "front", "ivysaur")
        val failing = { from: File, to: File -> if (from.name == "pokemon" && from.parentFile.name == "sprites-styled.tmp") throw java.io.IOException("disk full") else SpriteStylePipeline.atomicMove(from, to) }
        assertFalse(SpriteStylePipeline(root, emptyList(), move = failing).run())
        assertEquals(before, liveHashes(root))
        assertFalse(File(root, "assets-local/sprites/pokemon.bak").exists())
        assertFalse(File(root, "assets-local/sprites-styled.tmp").exists())
        assertTrue(pipeline(root).run()) // and a later run succeeds
        assertEquals(setOf("front/bulbasaur.png", "front/ivysaur.png"), liveHashes(root).keys)
    }

    @Test
    fun interruptedSwapIsRecoveredOnTheNextRun() = withRoot { root ->
        raw(root, "front", "bulbasaur"); assertTrue(pipeline(root).run())
        val before = liveHashes(root)
        // Simulate a crash between "live -> backup" and "tmp -> live" and a failing run that cannot proceed.
        live(root).renameTo(File(root, "assets-local/sprites/pokemon.bak"))
        File(root, "assets-local/sprites-raw").deleteRecursively()
        assertFalse(pipeline(root).run())
        assertEquals(before, liveHashes(root))
    }

    @Test
    fun styleNoneCopiesRawUnchangedButStillValidates() = withRoot { root ->
        val original = pngBytes(blob(68, 56))
        raw(root, "icon", "bulbasaur", original); raw(root, "front", "ivysaur", byteArrayOf(9, 9, 9))
        assertTrue(pipeline(root, "--style=none").run())
        assertContentEquals(original, File(live(root), "icon/bulbasaur.png").readBytes())
        assertFalse(File(live(root), "front/ivysaur.png").exists())
        assertEquals("none", report(root, "STYLE_REPORT.json")["styleMode"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { SpriteStylePipeline.parseArgs(listOf("--style=sepia")) }
    }

    @Test
    fun coverageCountsAddUpAndAreScopedToTheRequest() = withRoot { root ->
        raw(root, "front", "bulbasaur"); raw(root, "front", "ivysaur"); raw(root, "front", "pikachu")
        assertTrue(pipeline(root, "--only=1-3", "--kinds=front,icon").run())
        val cov = report(root, "COVERAGE.json")
        val scope = cov["scope"]!!.jsonObject["speciesEntries"]!!.jsonPrimitive.int
        assertTrue(scope >= 3)
        for (kind in listOf("front", "icon")) {
            val k = cov["kinds"]!!.jsonObject[kind]!!.jsonObject
            fun n(key: String) = k[key]!!.jsonPrimitive.int
            assertEquals(scope, n("imported") + n("missing") + n("failed") + n("unmapped"))
        }
        assertEquals(2, cov["kinds"]!!.jsonObject["front"]!!.jsonObject["imported"]!!.jsonPrimitive.int)
        assertEquals(0, cov["kinds"]!!.jsonObject["icon"]!!.jsonObject["imported"]!!.jsonPrimitive.int)
        assertEquals(1, cov["liveFilesOutsideScope"]!!.jsonPrimitive.int) // pikachu is processed but outside 1-3
        assertNull(cov["kinds"]!!.jsonObject["back"])
    }

    // ------------------------------------------------------------------ importer (raw cache)

    private fun importer(root: File, vararg args: String, fetch: ((String) -> ByteArray?)? = null) =
        SpriteImporter(root, listOf("--dest=assets-local/sprites-raw/pokemon") + args, fetch)

    @Test
    fun importToRawCacheIsIdempotentAndLogsChecksums() = withRoot { root ->
        val calls = AtomicInteger()
        val body = pngBytes()
        val fetch = { _: String -> calls.incrementAndGet(); body }
        assertTrue(importer(root, "--only=1-2", "--forms=false", "--kinds=front", fetch = fetch).run())
        assertEquals(2, calls.get())
        assertTrue(File(root, "assets-local/sprites-raw/pokemon/front/bulbasaur.png").isFile)
        assertFalse(File(root, "assets-local/sprites").exists(), "default destination must stay untouched")
        assertTrue(importer(root, "--only=1-2", "--forms=false", "--kinds=front", fetch = fetch).run())
        assertEquals(2, calls.get(), "second run must download nothing")
        val log = Json.parseToJsonElement(File(root, "assets-local/sprites-raw/IMPORT_LOG.json").readText()).jsonObject
        assertEquals("raw-cache", log["layout"]!!.jsonPrimitive.content)
        assertEquals(2, log["files"]!!.jsonArray.size)
        assertEquals(sha256(body), log["files"]!!.jsonArray[0].jsonObject["sha256"]!!.jsonPrimitive.content)
        // offline: no fetch at all, existing cache untouched
        assertTrue(importer(root, "--only=1-2", "--offline", fetch = { throw AssertionError("network used while offline") }).run())
    }

    @Test
    fun truncatedDownloadLeavesNoFileInTheCache() = withRoot { root ->
        val complete = pngBytes()
        val truncated = complete.copyOf(complete.size / 2)
        var ok = importer(root, "--only=1-1", "--forms=false", "--kinds=front", "--retries=0", fetch = { truncated }).run()
        assertFalse(ok)
        assertFalse(File(root, "assets-local/sprites-raw/pokemon/front/bulbasaur.png").exists())
        assertTrue(File(root, "assets-local/sprites-raw/pokemon/front").walkTopDown().none { it.isFile })
        // a transient failure followed by success retries and stores the complete file
        val n = AtomicInteger()
        ok = importer(root, "--only=1-1", "--forms=false", "--kinds=front", "--retries=1", fetch = { if (n.getAndIncrement() == 0) truncated else complete }).run()
        assertTrue(ok)
        assertContentEquals(complete, File(root, "assets-local/sprites-raw/pokemon/front/bulbasaur.png").readBytes())
    }

    @Test
    fun completePngDetectionToleratesTrailingBytesButNotCutOffFiles() {
        val png = pngBytes()
        assertTrue(SpriteImporter.isCompletePng(png))
        assertTrue(SpriteImporter.isCompletePng(png + byteArrayOf(0, 0))) // seen upstream (PokeAPI shiny/10035.png)
        assertFalse(SpriteImporter.isCompletePng(png.copyOf(png.size - 5)))
        assertFalse(SpriteImporter.isCompletePng(png.copyOf(30)))
        assertFalse(SpriteImporter.isCompletePng(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun tolerateFailuresKeepsThePipelineGoingOnlyWhenSomethingWasImported() = withRoot { root ->
        val body = pngBytes()
        val flaky = { url: String -> if (url.endsWith("/2.png")) throw java.io.IOException("HTTP 503") else body }
        assertFalse(importer(root, "--only=1-3", "--forms=false", "--kinds=front", "--retries=0", fetch = flaky).run())
        assertTrue(importer(root, "--only=1-3", "--forms=false", "--kinds=front", "--retries=0", "--tolerate-failures", fetch = flaky).run())
        assertEquals(setOf("bulbasaur.png", "venusaur.png"), File(root, "assets-local/sprites-raw/pokemon/front").list()!!.toSet())
        val allFail = { _: String -> throw java.io.IOException("offline") }
        withRoot { empty ->
            assertFalse(importer(empty, "--only=1-3", "--forms=false", "--kinds=front", "--retries=0", "--tolerate-failures", fetch = allFail).run())
        }
    }

    @Test
    fun serverThatDropsTheConnectionMidBodyLeavesNoFile() = withRoot { root ->
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.sendResponseHeaders(200, 5000)
            ex.responseBody.write(pngBytes().copyOf(40)); ex.responseBody.flush()
            ex.close()
        }
        server.start()
        try {
            val ok = importer(root, "--only=1-1", "--forms=false", "--kinds=front", "--retries=0", "--base-url=http://127.0.0.1:${server.address.port}").run()
            assertFalse(ok)
            assertTrue(File(root, "assets-local/sprites-raw").walkTopDown().none { it.isFile && it.extension in setOf("png", "part") })
        } finally { server.stop(0) }
    }

    @Test
    fun writeAtomicLeavesNoPartFile() {
        val dir = Files.createTempDirectory("atomic").toFile()
        try {
            SpriteImporter.writeAtomic(File(dir, "a/b.png"), byteArrayOf(1, 2, 3))
            SpriteImporter.writeAtomic(File(dir, "a/b.png"), byteArrayOf(4, 5))
            assertEquals(listOf("b.png"), File(dir, "a").list()!!.toList())
            assertContentEquals(byteArrayOf(4, 5), File(dir, "a/b.png").readBytes())
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ importer (--from)

    private fun zip(file: File, entries: List<Pair<String, ByteArray>>) =
        ZipOutputStream(file.outputStream()).use { z -> for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }

    @Test
    fun missingFromSourceAndMalformedZipFailClearly() = withRoot { root ->
        assertFalse(importer(root, "--from=${root.path}/nope.zip").run())
        File(root, "garbage.zip").writeBytes(ByteArray(300) { it.toByte() })
        assertFalse(importer(root, "--from=${File(root, "garbage.zip").path}").run())
        val log = Json.parseToJsonElement(File(root, "assets-local/sprites-raw/IMPORT_LOG.json").readText()).jsonObject
        assertTrue(log["failures"]!!.jsonArray.single().jsonPrimitive.content.contains("not a valid zip"))
        assertTrue(File(root, "assets-local/sprites-raw/pokemon").walkTopDown().none { it.isFile })
    }

    @Test
    fun zipPathTraversalIsRejected() = withRoot { root ->
        val png = pngBytes()
        val loose = File(root, "loose.zip")
        zip(loose, listOf("../../front/bulbasaur.png" to png, "/abs/front/ivysaur.png" to png, "C:/x/front/venusaur.png" to png, "pack/front/pikachu.png" to png))
        assertFalse(importer(root, "--from=${loose.path}").run())
        val documented = File(root, "documented.zip")
        zip(documented, listOf("sprites/../../escape.png" to png, "sprites/pokemon/front/eevee.png" to png, "sprites/pokemon/../../../front/mew.png" to png))
        assertFalse(importer(root, "--from=${documented.path}").run())
        val rawFront = File(root, "assets-local/sprites-raw/pokemon/front")
        assertEquals(setOf("pikachu.png", "eevee.png"), rawFront.list()!!.toSet())
        // nothing was written outside the raw cache
        assertFalse(File(root, "escape.png").exists()); assertFalse(File(root.parentFile, "escape.png").exists())
        assertFalse(File(root, "assets-local/.zip-extract.tmp").exists())
        assertEquals(setOf("assets-local"), root.list()!!.filter { it.startsWith("assets-local") }.toSet())
    }

    @Test
    fun parsesPipelineOptionsAndPathsWithSpaces() {
        val o = SpriteImporter.parseArgs(listOf("--dest=assets-local/sprites-raw/pokemon", "--offline", "--from=/tmp/my sprites/pack.zip --only=1-12"))
        assertEquals(File("assets-local/sprites-raw/pokemon"), o.dest); assertTrue(o.offline)
        assertEquals(File("/tmp/my sprites/pack.zip"), o.from); assertEquals(listOf(1..12), o.only)
        val s = SpriteStylePipeline.parseArgs(listOf("--style=none", "--no-outline", "--strict", "--only=1-3"))
        assertEquals("none", s.style); assertFalse(s.outline); assertTrue(s.strict)
    }
}
