package pkmn.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** CI-1: every CLI command must turn failure into a non-zero exit code without calling exitProcess. */
class ToolsMainTest {
    @Test
    fun failingCommandsYieldNonZeroExitOnInvalidRoot() {
        val root = Files.createTempDirectory("toolsroot").toFile()
        try {
            for (cmd in listOf("import", "validate", "maps", "manifest", "progression", "entrances", "items", "moves", "learnsets", "capture", "playtime")) {
                val code = runTool(arrayOf(cmd, "--root=${root.absolutePath}", "--no-write"))
                assertEquals(EXIT_FAILED, code, "command '$cmd' must fail on an empty root")
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun unknownCommandIsUsageError() {
        assertEquals(EXIT_USAGE, runTool(arrayOf("nope")))
        assertEquals(EXIT_USAGE, runTool(emptyArray()))
    }

    @Test
    fun screenshotsWithMissingDirsFails() {
        val d = Files.createTempDirectory("shots").toFile()
        try {
            assertEquals(EXIT_FAILED, runTool(arrayOf("screenshots", d.resolve("none").path, d.resolve("none2").path)))
        } finally { d.deleteRecursively() }
    }
}
