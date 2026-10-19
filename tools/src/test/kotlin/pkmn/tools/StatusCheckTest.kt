package pkmn.tools

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusCheckTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile

    private fun xml(dir: File, tests: Int, skipped: Int = 0, failures: Int = 0) {
        dir.mkdirs()
        File(dir, "TEST-x.xml").writeText("""<?xml version="1.0"?><testsuite name="x" tests="$tests" skipped="$skipped" failures="$failures" errors="0"/>""")
    }

    @Test
    fun worldCountsComeFromTheCompiledWorldAndMismatchIsReported() {
        val w = StatusCheck(repo).world()
        assertTrue(w.maps > 100 && w.scripts > 100)
        val phrase = "${w.maps} maps, ${w.trainers} trainers, ${w.encounterTables} encounter tables, ${w.scripts} scripts"
        assertEquals(emptyList(), StatusCheck(repo, "World: $phrase.").check().filter { "world" in it })
        val bad = StatusCheck(repo, "World: ${w.maps + 1} maps, ${w.trainers} trainers, ${w.encounterTables} encounter tables, ${w.scripts} scripts").check()
        assertTrue(bad.any { "world counts" in it }, bad.toString())
        assertTrue(StatusCheck(repo, "nothing here").check().any { "no '<n> maps" in it })
    }

    @Test
    fun testCountsAreReadFromJunitXmlAndSkippedAreExcluded() {
        val tmp = Files.createTempDirectory("status").toFile()
        try {
            // A world is needed for check(). Copy it: never symlink the repo's assets, deleteRecursively would follow the link.
            for (d in listOf("world", "maps", "scripts")) File(repo, "assets/$d").copyRecursively(File(tmp, "assets/$d"))
            val sc = StatusCheck(tmp)
            assertEquals(null, sc.tests(), "incomplete results -> not checked")
            xml(File(tmp, "engine/build/test-results/test"), 10)
            xml(File(tmp, "core/build/test-results/test"), 5, skipped = 1)
            xml(File(tmp, "tools/build/test-results/test"), 7, failures = 2)
            assertEquals(StatusCheck.Tests(10, 4, 5), sc.tests())
            val w = sc.world()
            val world = "${w.maps} maps, ${w.trainers} trainers, ${w.encounterTables} encounter tables, ${w.scripts} scripts"
            assertEquals(emptyList(), StatusCheck(tmp, "$world; 10 engine, 4 core and 5 tool tests").check())
            val bad = StatusCheck(tmp, "$world; 10 engine, 5 core and 5 tool tests").check()
            assertTrue(bad.single().contains("test counts"), bad.toString())
        } finally { tmp.deleteRecursively() }
    }
}
