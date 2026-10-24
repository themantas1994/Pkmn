package pkmn.tools

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkBudgetTest {
    private fun root(): File = Files.createTempDirectory("budget").toFile()
    private fun File.sized(rel: String, bytes: Long) {
        val f = File(this, rel)
        f.parentFile.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(bytes) }
    }

    @Test
    fun exactlyAtBudgetPasses() {
        val r = root()
        r.sized("android/build/outputs/apk/release/a.apk", ApkBudget.RELEASE_APK_LIMIT)
        r.sized("android/build/outputs/apk/debug/a.apk", ApkBudget.DEBUG_APK_LIMIT)
        r.sized("android/build/outputs/bundle/release/a.aab", ApkBudget.RELEASE_AAB_LIMIT)
        r.sized("assets/gfx/sub/x.png", ApkBudget.GFX_LIMIT)
        assertTrue(ApkBudget.run(r, requireAll = true))
    }

    @Test
    fun eachArtifactOneByteOverBudgetFails() {
        for ((rel, limit) in listOf(
            "android/build/outputs/apk/release/a.apk" to ApkBudget.RELEASE_APK_LIMIT,
            "android/build/outputs/apk/debug/a.apk" to ApkBudget.DEBUG_APK_LIMIT,
            "android/build/outputs/bundle/release/a.aab" to ApkBudget.RELEASE_AAB_LIMIT,
            "assets/gfx/sub/x.png" to ApkBudget.GFX_LIMIT,
        )) {
            val r = root()
            r.sized(rel, limit + 1)
            assertFalse(ApkBudget.run(r, requireAll = false), rel)
        }
    }

    @Test
    fun missingArtifactsAreSkippedUnlessRequired() {
        val r = root()
        r.sized("assets/gfx/x.png", 10)
        assertTrue(ApkBudget.run(r, requireAll = false))
        assertFalse(ApkBudget.run(r, requireAll = true))
    }

    @Test
    fun personalApkAndAssetsLocalAreNotCounted() {
        val r = root()
        r.sized("android/build/outputs/apk/personal/p.apk", 100 * ApkBudget.MB)
        r.sized("assets-local/sprites/pokemon/big.png", 100 * ApkBudget.MB)
        r.sized("assets/gfx/x.png", 10)
        assertTrue(ApkBudget.run(r, requireAll = false))
        assertEquals(null, ApkBudget.measure(r).first { it.name == "Debug APK" }.actualBytes)
        assertEquals(10L, ApkBudget.measure(r).first { it.name == "assets/gfx" }.actualBytes)
    }

    @Test
    fun toolMainDispatchesAndReportsFailure() {
        val r = root()
        r.sized("android/build/outputs/apk/debug/a.apk", ApkBudget.DEBUG_APK_LIMIT + 1)
        assertEquals(EXIT_FAILED, runTool(arrayOf("apk-budget", "--root=${r.path}")))
        assertEquals(EXIT_FAILED, runTool(arrayOf("apk-budget", "--require", "--root=${root().path}")))
        assertEquals(EXIT_OK, runTool(arrayOf("apk-budget", "--root=${root().path}")))
    }
}
