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
 */
class StatusCheck(private val root: File, private val statusText: String? = null) {
    data class World(val maps: Int, val trainers: Int, val encounterTables: Int, val scripts: Int)
    data class Tests(val engine: Int, val core: Int, val tools: Int)

    fun world(): World {
        val w = WorldContent(DirectoryContentSource(File(root, "assets")))
        return World(w.index.maps.size, w.trainers.size, w.encounters.size, w.scripts.size)
    }

    /** Passed tests (tests - skipped - failures - errors) in a module's JUnit XML reports, or null when it has none. */
    fun passedTests(module: String): Int? {
        val files = File(root, "$module/build/test-results/test").listFiles { f -> f.extension == "xml" }?.takeIf { it.isNotEmpty() } ?: return null
        var n = 0
        for (f in files) {
            val a = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
            fun attr(k: String) = a.getAttribute(k).toIntOrNull() ?: 0
            n += attr("tests") - attr("skipped") - attr("failures") - attr("errors")
        }
        return n
    }

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
        if (t != null) {
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
        if (t != null) println("tests: ${t.engine} engine, ${t.core} core and ${t.tools} tool tests")
        else println("tests: (no complete JUnit results; run :engine:test :core:test :tools:test)")
        if (!check) return true
        val problems = check()
        problems.forEach { println("STATUS MISMATCH: $it") }
        if (problems.isEmpty()) println("STATUS.md is consistent with the build outputs")
        return problems.isEmpty()
    }
}
