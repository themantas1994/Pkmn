package pkmn.tools

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** G0.1: art/build_all.py is byte-deterministic and reproduces the committed assets/gfx and assets/fonts. */
class ArtBuildDeterminismTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val python = File(System.getProperty("pkmn.art.python"))
    private val outputs = listOf("assets/gfx", "assets/fonts")

    private fun snapshot(root: File): Map<String, String> = outputs.flatMap { dir ->
        File(root, dir).walkTopDown().filter { it.isFile }.map { f ->
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            f.relativeTo(root).path to sha
        }.toList()
    }.toMap()

    private fun build(root: File) {
        val log = File(root, "art-build.log")
        val p = ProcessBuilder(python.path, "-I", "art/build_all.py", "--no-previews").directory(root)
            .redirectErrorStream(true).redirectOutput(log).start()
        assertEquals(0, p.waitFor(), log.readText())
    }

    @Test
    fun twoBuildsAreByteIdenticalAndMatchTheCommittedAssets() {
        assumeTrue(python.isFile, "art python not available (run :tools:buildArt or :tools:artVenv first): $python")
        val tmp = Files.createTempDirectory("artbuild").toFile()
        try {
            File(repo, "art").copyRecursively(File(tmp, "art"))
            File(repo, "assets").copyRecursively(File(tmp, "assets"))
            File(repo, "docs/screenshots").copyRecursively(File(tmp, "docs/screenshots")) // validate_tint compares against them
            val committed = snapshot(tmp)
            assertTrue(committed.isNotEmpty(), "no art outputs found")
            build(tmp)
            val first = snapshot(tmp)
            build(tmp)
            val second = snapshot(tmp)
            assertEquals(first, second, "second build differs from the first")
            assertEquals(committed, first, "buildArt output differs from the committed assets (regenerate with :tools:buildArt and commit)")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
