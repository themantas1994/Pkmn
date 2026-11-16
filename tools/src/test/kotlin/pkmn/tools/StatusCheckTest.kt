package pkmn.tools

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusCheckTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile

    private fun xml(dir: File, tests: Int, skipped: Int = 0, failures: Int = 0, name: String = "x") {
        dir.mkdirs()
        File(dir, "TEST-$name.xml").writeText("""<?xml version="1.0"?><testsuite name="$name" tests="$tests" skipped="$skipped" failures="$failures" errors="0"/>""")
    }

    @Test
    fun worldCountsComeFromTheCompiledWorldAndMismatchIsReported() {
        val w = StatusCheck(repo).world()
        assertTrue(w.maps > 100 && w.scripts > 100)
        val phrase = "${w.maps} maps, ${w.trainers} trainers, ${w.encounterTables} encounter tables, ${w.scripts} scripts"
        assertEquals(emptyList(), StatusCheck(repo, "World: $phrase.").check().filter { "world" in it })
        val bad = StatusCheck(repo, "World: ${w.maps + 1} maps, ${w.trainers} trainers, ${w.encounterTables} encounter tables, ${w.scripts} scripts").check()
        assertTrue(bad.any { "world counts" in it }, bad.toString())
        assertEquals(emptyList(), StatusCheck(repo, "nothing pinned here").check())
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
            xml(File(tmp, "core/build/test-results/test"), 5)
            assertEquals(StatusCheck.Tests(10, 5, 5), sc.tests())
            assertEquals(emptyList(), StatusCheck(tmp, "$world; 10 engine, 5 core and 5 tool tests").check())
            val bad = StatusCheck(tmp, "$world; 10 engine, 4 core and 5 tool tests").check()
            assertTrue(bad.single().contains("test counts"), bad.toString())
        } finally { tmp.deleteRecursively() }
    }

    /** Counts must be CI's: ArtBuildDeterminismTest skipped (no art venv) or a benchmark run (-Pbenchmarks) differs from CI. */
    @Test
    fun runsWhoseSkipsDifferFromCiAreRejected() {
        val tmp = Files.createTempDirectory("status").toFile()
        try {
            for (d in listOf("world", "maps", "scripts")) File(repo, "assets/$d").copyRecursively(File(tmp, "assets/$d"))
            xml(File(tmp, "engine/build/test-results/test"), 10)
            xml(File(tmp, "core/build/test-results/test"), 5)
            xml(File(tmp, "core/build/test-results/test"), 1, skipped = 1, name = "pkmn.game.audio.ChipSynthBenchmarkTest")
            val sc = StatusCheck(tmp)
            assertEquals(emptyList(), sc.notLikeCi(), "the opt-in benchmark is skipped on CI too")
            xml(File(tmp, "tools/build/test-results/test"), 7, skipped = 1, name = "pkmn.tools.ArtBuildDeterminismTest")
            assertEquals(listOf("tools: pkmn.tools.ArtBuildDeterminismTest (1 skipped)"), sc.notLikeCi())
            val w = sc.world()
            val world = "${w.maps} maps, ${w.trainers} trainers, ${w.encounterTables} encounter tables, ${w.scripts} scripts"
            val problems = StatusCheck(tmp, "$world; 10 engine, 5 core and 6 tool tests").check()
            assertTrue(problems.single().contains("ArtBuildDeterminismTest"), problems.toString())
            assertFalse(sc.run(check = false), "statusCounts must not print numbers to copy while tests were skipped")
            xml(File(tmp, "tools/build/test-results/test"), 7, name = "pkmn.tools.ArtBuildDeterminismTest")
            xml(File(tmp, "core/build/test-results/test"), 1, name = "pkmn.game.audio.ChipSynthBenchmarkTest")
            assertEquals(listOf("core: pkmn.game.audio.ChipSynthBenchmarkTest (1 ran, CI skips it)"), sc.notLikeCi())
        } finally { tmp.deleteRecursively() }
    }

    /** A tmp root with the repo's compiled world and a minimal reports/progression.json (maps reachable per badge stage). */
    private fun withRoot(reach: List<Int> = (0..8).map { 100 + it }, body: (File, StatusCheck.World) -> Unit) {
        val tmp = Files.createTempDirectory("status").toFile()
        try {
            for (d in listOf("world", "maps", "scripts")) File(repo, "assets/$d").copyRecursively(File(tmp, "assets/$d"))
            File(tmp, "reports").mkdirs()
            File(tmp, "reports/progression.json").writeText("""{"stages":[${reach.joinToString(",") { """{"maps":$it}""" }}]}""")
            body(tmp, StatusCheck(tmp).world())
        } finally { tmp.deleteRecursively() }
    }

    /** C7-1: a quoted number in a format no rule covers used to pass silently; now it is a problem. */
    @Test
    fun numbersInAnUnrecognisedFormatFail() = withRoot { tmp, _ ->
        for (doc in listOf("We have 12 maps, 30 trainers and so on.", "All passing (12 + 3 + 4).", "Maps reachable with 0..8 badges: see the report.",
            "All tests: 12 engine tests, 3 core tests.")) {
            val problems = StatusCheck(tmp, doc).check()
            assertTrue(problems.any { "no check covers" in it }, "$doc -> $problems")
        }
    }

    /** C7-1: the formats STATUS.md really uses (the "525 + 160 + 152" triple, the world-load row, the reach list) are checked. */
    @Test
    fun theFormatsStatusMdUsesAreChecked() = withRoot { tmp, w ->
        xml(File(tmp, "engine/build/test-results/test"), 10)
        xml(File(tmp, "core/build/test-results/test"), 5)
        xml(File(tmp, "tools/build/test-results/test"), 7)
        val good = "all passing (10 + 5 + 7 with a feature, from a run; 9 + 5 + 7 before it). | World load (${w.maps} maps, ${w.scripts} scripts) |\n" +
            "Maps reachable with 0..8 badges: ${(0..8).joinToString(", ") { (100 + it).toString() }}."
        assertEquals(emptyList(), StatusCheck(tmp, good).check())
        val triple = StatusCheck(tmp, good.replace("10 + 5 + 7", "10 + 5 + 8")).check()
        assertTrue(triple.any { "test counts" in it }, triple.toString())
        val load = StatusCheck(tmp, good.replace("${w.maps} maps", "${w.maps + 1} maps")).check()
        assertTrue(load.any { "world counts" in it }, load.toString())
        val reach = StatusCheck(tmp, good.replace("100, 101", "99, 101")).check()
        assertTrue(reach.any { "reachable" in it }, reach.toString())
        val short = StatusCheck(tmp, good.replace(", 108.", ".")).check()
        assertTrue(short.any { "reachable" in it }, short.toString())
    }
}
