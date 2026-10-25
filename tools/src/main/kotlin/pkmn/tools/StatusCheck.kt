package pkmn.tools

import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Computes the numbers STATUS.md quotes from the build outputs and, with `--check`, compares them with the document.
 * The counts are no longer pinned in STATUS.md (every PR bumping them conflicted with every other PR); `status` prints
 * them. If the document does quote a phrase below, it must still match, so a stale pinned number fails CI.
 *
 *  * world: "N maps, N trainers, N encounter tables, N scripts"  (from assets/)
 *  * tests: "N engine, N core and N tool tests"                  (from <module>/build/test-results/test (JUnit XML); skipped tests excluded)
 *
 * Test counts are only checked when all three result directories exist (run `:engine:test :core:test :tools:test` first).
 * The counts must be CI's, so a run whose skips differ from CI's is rejected: ArtBuildDeterminismTest is skipped without
 * the art venv CI builds (run `:tools:artVenv` first), and the opt-in benchmarks in [CI_SKIPPED] run with `-Pbenchmarks`.
 */
class StatusCheck(private val root: File, private val statusText: String? = null) {
    data class World(val maps: Int, val trainers: Int, val encounterTables: Int, val scripts: Int)
    data class Tests(val engine: Int, val core: Int, val tools: Int)

    companion object {
        /** Suites CI skips by design (opt-in, machine-dependent). Any other skip, or one of these running, is not CI's count. */
        val CI_SKIPPED = setOf("pkmn.game.audio.ChipSynthBenchmarkTest")
    }

    fun world(): World {
        val w = WorldContent(DirectoryContentSource(File(root, "assets")))
        return World(w.index.maps.size, w.trainers.size, w.encounters.size, w.scripts.size)
    }

    /** Passed tests (tests - skipped - failures - errors) in a module's JUnit XML reports, or null when it has none. */
    fun passedTests(module: String): Int? {
        val suites = suites(module) ?: return null
        return suites.sumOf { s -> s.attr("tests") - s.attr("skipped") - s.attr("failures") - s.attr("errors") }
    }

    /** Suites in the last run whose skips differ from CI's: "<module>: <suite> (<n> skipped)" or "(<n> ran, CI skips it)". */
    fun notLikeCi(): List<String> = listOf("engine", "core", "tools").flatMap { m ->
        suites(m).orEmpty().mapNotNull { s ->
            val name = s.getAttribute("name"); val skipped = s.attr("skipped"); val ran = s.attr("tests") - skipped
            when {
                name !in CI_SKIPPED && skipped > 0 -> "$m: $name ($skipped skipped)"
                name in CI_SKIPPED && ran > 0 -> "$m: $name ($ran ran, CI skips it)"
                else -> null
            }
        }
    }

    private fun suites(module: String): List<org.w3c.dom.Element>? =
        File(root, "$module/build/test-results/test").listFiles { f -> f.extension == "xml" }?.takeIf { it.isNotEmpty() }
            ?.sortedBy { it.name }?.map { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement }

    private fun org.w3c.dom.Element.attr(k: String) = getAttribute(k).toIntOrNull() ?: 0

    fun tests(): Tests? {
        val e = passedTests("engine"); val c = passedTests("core"); val t = passedTests("tools")
        return if (e != null && c != null && t != null) Tests(e, c, t) else null
    }

    /** Mismatches between STATUS.md and the computed numbers (empty = consistent). */
    fun check(): List<String> {
        val status = statusText ?: File(root, "STATUS.md").readText()
        val problems = ArrayList<String>()
        val wm = Regex("""(\d+) maps, (\d+) trainers, (\d+) encounter tables, (\d+) scripts""").findAll(status).toList()
        val w = world()
        for (m in wm) {
            val got = World(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
            if (got != w) problems += "STATUS.md world counts $got differ from the compiled world $w"
        }
        val t = tests()
        val notLikeCi = notLikeCi()
        if (notLikeCi.isNotEmpty()) problems += "the last test run skipped or ran different tests than CI: ${notLikeCi.joinToString("; ")}"
        if (t != null && notLikeCi.isEmpty()) {
            val tm = Regex("""(\d+) engine, (\d+) core and (\d+) tool tests""").findAll(status).toList()
            for (m in tm) {
                val got = Tests(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                if (got != t) problems += "STATUS.md test counts $got differ from the last test run $t"
            }
        }
        return problems
    }

    fun run(check: Boolean): Boolean {
        val w = world()
        println("world: ${w.maps} maps, ${w.trainers} trainers, ${w.encounterTables} encounter tables, ${w.scripts} scripts")
        val t = tests()
        val notLikeCi = notLikeCi()
        if (notLikeCi.isNotEmpty()) {
            println("tests: NOT USABLE, the last run skipped or ran different tests than CI: ${notLikeCi.joinToString("; ")}")
            println("       run ./gradlew :tools:artVenv, then :engine:test :core:test :tools:test (without -Pbenchmarks) again")
        } else if (t != null) println("tests: ${t.engine} engine, ${t.core} core and ${t.tools} tool tests")
        else println("tests: (no complete JUnit results; run :engine:test :core:test :tools:test)")
        if (!check) return notLikeCi.isEmpty()
        val problems = check()
        problems.forEach { println("STATUS MISMATCH: $it") }
        if (problems.isEmpty()) println("STATUS.md is consistent with the build outputs")
        return problems.isEmpty()
    }
}
