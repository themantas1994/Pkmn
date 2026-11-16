package pkmn.tools

import pkmn.engine.campaign.WorldContent
import kotlinx.serialization.json.*
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Computes the numbers STATUS.md quotes from the build outputs and, with `--check`, compares them with the document.
 * The counts are no longer pinned in STATUS.md (every PR bumping them conflicted with every other PR); `status` prints
 * them. If the document does quote a phrase below, it must still match, so a stale pinned number fails CI.
 *
 *  * world: "N maps, N trainers, N encounter tables, N scripts"  (from assets/)
 *  * world load: "(N maps, N scripts)"                           (same source)
 *  * tests: "N engine, N core and N tool tests", or the first triple of "all passing (A + B + C ..."
 *                                                                (from <module>/build/test-results/test (JUnit XML); skipped tests excluded)
 *  * reach: "Maps reachable with 0..8 badges: a, b, ..."          (from reports/progression.json)
 * A quoted number in any other shape of those phrases fails the check (C7-1), so nothing can drift unnoticed.
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

        private val WORLD_LIST = Regex("""(\d+) maps, (\d+) trainers, (\d+) encounter tables, (\d+) scripts""")
        private val WORLD_LOAD = Regex("""\((\d+) maps, (\d+) scripts\)""")
        private val TESTS_PHRASE = Regex("""(\d+) engine, (\d+) core and (\d+) tool tests""")
        /** "all passing (525 + 160 + 152 with ..., history; 494 + 154 + 152 ...)": only the first triple is current; the span is the whole parenthesis. */
        private val TESTS_TRIPLE = Regex("""all passing \((\d+) \+ (\d+) \+ (\d+)[^)]*\)""")
        private val REACH_LIST = Regex("""Maps reachable with 0\.\.8 badges: ([\d, ]+)\.""")
        /** Shapes of quoted numbers that must be covered by a rule above. */
        private val LOOSE = listOf(
            Regex("""\d+ maps, \d+ \w+"""), Regex("""\(\d+ maps"""), Regex("""\d+ (engine|core|tool) tests?"""),
            Regex("""\d+ \+ \d+ \+ \d+"""), Regex("""Maps reachable with[^\n]*"""),
        )
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

    /** Maps reachable per badge stage 0..8, from reports/progression.json (written by `:tools:checkProgression`), or null when absent. */
    fun reachByBadges(): List<Int>? {
        val f = File(root, "reports/progression.json")
        if (!f.isFile) return null
        val stages = Json.parseToJsonElement(f.readText()).jsonObject["stages"]?.jsonArray ?: return null
        return stages.map { it.jsonObject.getValue("maps").jsonPrimitive.int }
    }

    /**
     * Mismatches between STATUS.md and the computed numbers (empty = consistent).
     * Every quoted number must match one of the formats below; a number in a format no rule covers is a problem too
     * (it could drift unnoticed), so a document whose numbers match nothing no longer passes.
     */
    fun check(): List<String> {
        val status = statusText ?: File(root, "STATUS.md").readText()
        val problems = ArrayList<String>()
        val covered = ArrayList<IntRange>()
        fun rule(re: Regex) = re.findAll(status).toList().onEach { covered += it.range }
        val w = world()
        for (m in rule(WORLD_LIST)) {
            val got = World(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
            if (got != w) problems += "STATUS.md world counts $got differ from the compiled world $w"
        }
        for (m in rule(WORLD_LOAD)) {
            val got = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            if (got != w.maps to w.scripts) problems += "STATUS.md world counts (maps, scripts) $got differ from the compiled world (${w.maps}, ${w.scripts})"
        }
        val t = tests()
        val notLikeCi = notLikeCi()
        if (notLikeCi.isNotEmpty()) problems += "the last test run skipped or ran different tests than CI: ${notLikeCi.joinToString("; ")}"
        val testCounts = rule(TESTS_PHRASE).map { Tests(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) } +
            // "all passing (A + B + C ..." quotes the current counts first; the rest of the parenthesis is history.
            rule(TESTS_TRIPLE).map { Tests(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        if (t != null && notLikeCi.isEmpty()) for (got in testCounts) if (got != t) problems += "STATUS.md test counts $got differ from the last test run $t"
        for (m in rule(REACH_LIST)) {
            val got = m.groupValues[1].split(',').map { it.trim().toIntOrNull() ?: -1 }
            val want = reachByBadges()
            when {
                want == null -> problems += "STATUS.md quotes maps reachable per badge but reports/progression.json is missing (run :tools:checkProgression)"
                got != want -> problems += "STATUS.md maps reachable with 0..8 badges $got differ from reports/progression.json $want"
            }
        }
        for (loose in LOOSE) for (m in loose.findAll(status))
            if (covered.none { m.range.first >= it.first && m.range.last <= it.last })
                problems += "STATUS.md quotes \"${m.value.take(60)}\" but no check covers that format; use one of the checked formats (see StatusCheck)"
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
