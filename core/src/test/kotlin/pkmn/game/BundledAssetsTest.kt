package pkmn.game

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AN-2: the bundled-sprite unpack is restartable and never damages a valid existing set. Fake data only. */
class BundledAssetsTest {
    private class FakeSource(val files: Map<String, String>) : BundledAssetSource {
        var failOn: String? = null
        var failures = 0
        override fun list(path: String): List<String> {
            val prefix = "$path/"
            return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        }
        override fun open(path: String): InputStream {
            if (path == failOn) { failures++; throw IOException("disk full") }
            return ByteArrayInputStream((files[path] ?: throw IOException("missing $path")).toByteArray())
        }
    }

    private fun tmp() = Files.createTempDirectory("bundled").toFile()
    private val set = mapOf("imported/sprites/pokemon/front/a.png" to "A1", "imported/sprites/pokemon/back/a.png" to "B1", "imported/audio/cries/a.ogg" to "C1")

    @Test
    fun publicBuildBundlesNothing() {
        assertEquals(BundledAssets.Outcome.NothingBundled, BundledAssets.unpack(FakeSource(emptyMap()), tmp(), "1"))
    }

    @Test
    fun unpacksOnceThenIsUpToDateAndRefreshesWhenTheBuildChanges() {
        val dir = tmp()
        assertEquals(BundledAssets.Outcome.Unpacked(3), BundledAssets.unpack(FakeSource(set), dir, "1-100"))
        assertEquals("A1", File(dir, "imported/sprites/pokemon/front/a.png").readText())
        assertEquals(BundledAssets.Outcome.UpToDate, BundledAssets.unpack(FakeSource(set.mapValues { "X" }), dir, "1-100"))
        assertEquals("A1", File(dir, "imported/sprites/pokemon/front/a.png").readText(), "up to date: not rewritten")
        // A rebuilt APK (new install time) refreshes the files and drops the old marker.
        assertEquals(BundledAssets.Outcome.Unpacked(3), BundledAssets.unpack(FakeSource(set.mapValues { "N" }), dir, "1-200"))
        assertEquals("N", File(dir, "imported/sprites/pokemon/front/a.png").readText())
        assertEquals(listOf(".bundled-1-200"), File(dir, "imported").listFiles { f -> f.name.startsWith(".bundled-") }!!.map { it.name })
    }

    @Test
    fun failedCopyKeepsTheExistingValidFilesAndWritesNoMarkerAndLeavesNoPartFiles() {
        val dir = tmp()
        BundledAssets.unpack(FakeSource(set), dir, "1-100")
        val src = FakeSource(set.mapValues { "NEW" }).also { it.failOn = "imported/sprites/pokemon/front/a.png" }
        val retries = ArrayList<Int>()
        val out = BundledAssets.unpack(src, dir, "1-200", onRetry = { n, _ -> retries += n })
        assertTrue(out is BundledAssets.Outcome.Failed); assertEquals(2, src.failures); assertEquals(listOf(1), retries)
        assertEquals("A1", File(dir, "imported/sprites/pokemon/front/a.png").readText(), "the valid old file survives a failed refresh")
        assertTrue(File(dir, "imported/.bundled-1-100").exists(), "the old marker stays; the new build's marker is absent")
        assertFalse(File(dir, "imported/.bundled-1-200").exists())
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".part") }, "no temp files left behind")
        // The next start (source healthy again) completes it.
        assertEquals(BundledAssets.Outcome.Unpacked(3), BundledAssets.unpack(FakeSource(set.mapValues { "NEW" }), dir, "1-200"))
    }

    @Test
    fun aTransientFailureIsRetried() {
        val dir = tmp()
        var calls = 0
        val src = object : BundledAssetSource {
            val inner = FakeSource(set)
            override fun list(path: String) = inner.list(path)
            override fun open(path: String): InputStream { if (calls++ == 0) throw IOException("flaky"); return inner.open(path) }
        }
        assertEquals(BundledAssets.Outcome.Unpacked(3), BundledAssets.unpack(src, dir, "1"))
    }

    @Test
    fun cancellationStopsWithoutAMarker() {
        val dir = tmp(); var n = 0
        val out = BundledAssets.unpack(FakeSource(set), dir, "1", cancelled = { n++ >= 3 })
        assertEquals(BundledAssets.Outcome.Cancelled, out)
        assertFalse(File(dir, "imported/.bundled-1").exists())
    }
}
