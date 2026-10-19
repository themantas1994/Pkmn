package pkmn.tools

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CI-2: scripts/check-generated-assets.sh must flag modified tracked AND new untracked files under assets/. */
class GeneratedAssetsGuardTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val script = File(repo, "scripts/check-generated-assets.sh")

    private fun sh(dir: File, vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun git(dir: File, vararg a: String) = sh(dir, "git", "-c", "user.email=t@t", "-c", "user.name=t", *a).also { assertEquals(0, it.first, it.second) }

    @Test
    fun guardDetectsModifiedTrackedAndNewUntrackedFiles() {
        assertTrue(script.isFile, "missing $script")
        val dir = Files.createTempDirectory("guard").toFile()
        try {
            git(dir, "init", "-q")
            File(dir, "assets/data").mkdirs(); File(dir, "assets/data/a.json").writeText("[]")
            File(dir, "other.txt").writeText("x")
            git(dir, "add", "."); git(dir, "commit", "-q", "-m", "init")

            assertEquals(0, sh(dir, "bash", script.path).first, "clean tree passes")
            File(dir, "other.txt").writeText("changed outside assets")
            assertEquals(0, sh(dir, "bash", script.path).first, "changes outside assets/ are ignored")

            File(dir, "assets/data/a.json").writeText("[1]")
            val (modCode, modOut) = sh(dir, "bash", script.path)
            assertEquals(1, modCode, "modified tracked file must fail"); assertTrue("assets/data/a.json" in modOut, modOut)
            git(dir, "checkout", "--", "assets")

            File(dir, "assets/data/new.json").writeText("[]")
            val (newCode, newOut) = sh(dir, "bash", script.path)
            assertEquals(1, newCode, "new untracked file must fail (git diff would miss it)"); assertTrue("assets/data/new.json" in newOut, newOut)
            assertEquals(0, sh(dir, "git", "diff", "--exit-code", "--", "assets/").first, "plain git diff misses the untracked file")
        } finally { dir.deleteRecursively() }
    }
}
