package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.battle.BattleCoverage
import pkmn.engine.data.Dex
import pkmn.engine.data.DirectoryContentSource
import java.io.File

/**
 * Merges the move execution results written by `MoveExecutionTest` (engine/build/reports/move-execution.json) with
 * [BattleCoverage] into reports/moves-audit.json and reports/moves-audit.md. Every move of the dex ends in exactly one class:
 * implemented-and-tested, data-driven-and-tested, no-battle-effect or unsupported-or-incomplete.
 *
 * Run `./gradlew :engine:test --tests '*MoveExecutionTest*'` first, then `./gradlew :tools:auditMoves`.
 */
class MoveAudit(private val root: File, private val write: Boolean = true) {
    private val classes = listOf("implemented-and-tested", "data-driven-and-tested", "no-battle-effect", "unsupported-or-incomplete")

    /** Engine defects found while building the audit; kept here so the report always carries them. */
    private val engineFindings = listOf(
        "FIXED Battle.applyStatusMoveEffects: Moonlight, Morning Sun, Synthesis and Shore Up healed nothing (Showdown has no `heal` field for them and the engine only healed from data).",
        "FIXED MoveScripts: a second helper registration for the same move replaced the first, so Spit Up never dealt damage and Knock Off / Wake-Up Slap / Smelling Salts / Fury Cutter lost their power modifiers and Burn Up / Double Shock / Steel Roller their failure conditions; scripts registered in MoveScripts for the same id are now merged.",
        "FIXED Diamond Storm raised Defense by 4 every time: the importer turns raw `self: {chance: 50, boosts: {def: 2}}` into an unconditional `self` boost and a script added +2 again; the script now rolls the 50% (Serene Grace: 100%) and suppresses the data boost.",
        "FIXED Plasma Fists never set Ion Deluge: damaging moves ignored the `pseudoWeather` field.",
        "DOCUMENTED DataImporter drops `self.chance`; Diamond Storm is the only move affected in the current data, so the engine fix is local to that move.",
        "DOCUMENTED reports/learnsets.md: shop TMs lose their `tm:` prefix in assets/world/shops.json (MapCompilerTool), Showdown 'R' (form-required) learnset sources are not imported.",
    )

    fun run(): Boolean {
        val resultFile = File(root, "engine/build/reports/move-execution.json")
        if (!resultFile.exists()) {
            System.err.println("Missing ${resultFile.path}: run ./gradlew :engine:test --tests '*MoveExecutionTest*' first")
            return false
        }
        val dex = Dex.load(DirectoryContentSource(File(root, "assets")))
        val coverage = BattleCoverage.movesDetailed(dex)
        val executed = Json.parseToJsonElement(resultFile.readText()).jsonObject.getValue("moves").jsonArray.map { it.jsonObject }
        val byId = executed.associateBy { it.getValue("id").jsonPrimitive.content }
        val problems = ArrayList<String>()
        if (byId.size != executed.size) problems += "duplicate move ids in the execution results"
        for (id in dex.moves.keys) if (id !in byId) problems += "move $id was not executed"
        for (id in byId.keys) if (id !in dex.moves) problems += "executed unknown move $id"
        for ((id, r) in byId) {
            val cls = r.getValue("classification").jsonPrimitive.content
            if (cls !in classes) problems += "$id has invalid class $cls"
            if (cls != "unsupported-or-incomplete" && r.getValue("evidence").jsonArray.isEmpty()) problems += "$id is classified $cls without evidence"
            if (cls != "no-battle-effect" && cls != "unsupported-or-incomplete" && r.getValue("seeds").jsonPrimitive.int == 0) problems += "$id is classified $cls but was never executed"
            val cov = coverage[id]
            if (cov != null && cov.status.name != r.getValue("coverage").jsonPrimitive.content) problems += "$id: coverage changed since the test ran (${r.getValue("coverage").jsonPrimitive.content} vs ${cov.status})"
        }
        val counts = classes.associateWith { c -> byId.values.count { it.getValue("classification").jsonPrimitive.content == c } }
        val matrix = BattleCoverage.Status.entries.associate { st -> st.name to classes.associateWith { c -> byId.values.count { it.getValue("coverage").jsonPrimitive.content == st.name && it.getValue("classification").jsonPrimitive.content == c } } }
        val byPath = byId.values.groupBy { it.getValue("path").jsonPrimitive.content }.mapValues { (_, v) -> v.size }
        val json = buildJsonObject {
            put("moves", dex.moves.size)
            put("scenario", "reports are produced by engine/src/test/kotlin/pkmn/engine/battle/MoveExecutionTest.kt (deterministic singles duels, fixed seeds) merged with BattleCoverage")
            put("counts", buildJsonObject { for ((k, v) in counts) put(k, v) })
            put("byCoverageStatus", buildJsonObject { for ((k, row) in matrix) put(k, buildJsonObject { for ((c, n) in row) put(c, n) }) })
            put("byExecutionPath", buildJsonObject { for ((k, v) in byPath.toSortedMap()) put(k, v) })
            put("engineFindings", strArray(engineFindings))
            put("problems", strArray(problems))
            put("entries", JsonArray(dex.moves.keys.sorted().map { id ->
                val r = byId[id]
                buildJsonObject {
                    put("id", id)
                    put("name", dex.move(id).name)
                    put("classification", r?.get("classification") ?: JsonPrimitive("unsupported-or-incomplete"))
                    put("coverage", coverage[id]?.status?.name ?: "?")
                    put("coverageReason", coverage[id]?.reason ?: "")
                    put("path", r?.get("path") ?: JsonPrimitive("singles"))
                    put("seeds", r?.get("seeds") ?: JsonPrimitive(0))
                    put("evidence", r?.get("evidence") ?: JsonArray(emptyList()))
                    put("failures", r?.get("failures") ?: JsonArray(listOf(JsonPrimitive("not executed"))))
                    put("repro", r?.get("repro") ?: JsonPrimitive(""))
                }
            }))
        }
        val md = markdown(counts, matrix, byPath, byId.values.toList(), problems)
        if (write) {
            writeJson(File(root, "reports/moves-audit.json"), json)
            File(root, "reports/moves-audit.md").apply { parentFile.mkdirs() }.writeText(md)
        }
        println("Move audit: $counts" + (if (problems.isNotEmpty()) ", ${problems.size} problems" else "") + if (write) " -> reports/moves-audit.{json,md}" else "")
        for (p in problems.take(10)) println("  problem: $p")
        return problems.isEmpty() && counts.getValue("unsupported-or-incomplete") == 0
    }

    private fun markdown(counts: Map<String, Int>, matrix: Map<String, Map<String, Int>>, byPath: Map<String, Int>, moves: List<JsonObject>, problems: List<String>): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }
        line("# Move execution audit")
        line()
        line("Generated by `./gradlew :engine:test --tests '*MoveExecutionTest*'` followed by `./gradlew :tools:auditMoves`. Machine-readable data: `reports/moves-audit.json`.")
        line("Every move of the dex is executed in a deterministic singles battle (Mew L100 vs Snorlax L100 with inert abilities, fixed seeds, up to 48 retries for accuracy and secondary chances; Z-Moves and Max/G-Max Moves through their gimmick path) and checked against expectations derived from its data plus invariants (no exception, HP and stat stages in range, PP spent, priority honoured against a much faster/slower foe). A move counts as tested only if it was executed and at least one check passed or a state change was observed.")
        line()
        line("## Classification")
        line()
        line("| class | moves |")
        line("|---|---|")
        for (c in classes) line("| $c | ${counts.getValue(c)} |")
        line("| **total** | ${counts.values.sum()} |")
        line()
        line("| BattleCoverage status | " + classes.joinToString(" | ") + " |")
        line("|---|" + classes.joinToString("|") { "---" } + "|")
        for ((st, row) in matrix) line("| $st | " + classes.joinToString(" | ") { row.getValue(it).toString() } + " |")
        line()
        line("Execution paths: ${byPath.toSortedMap()}.")
        line()
        line("- **implemented-and-tested**: coverage says IMPLEMENTED_SCRIPT; executed with data-derived checks (damage, boosts, status...) and, for about 60 moves with preconditions or unique effects, script-specific checks; the rest rely on the data-derived checks or an observed state change, which prove execution but not every detail of the script.")
        line("- **data-driven-and-tested**: no Showdown callbacks; the data effect (damage, boosts, status, heal, drain, recoil, multi-hit, conditions...) was observed.")
        line("- **no-battle-effect**: no singles effect by design (BattleCoverage reason or message-only moves); executed without any state change.")
        line("- **unsupported-or-incomplete**: threw, broke an invariant, or its expected effect was not observed.")
        line()
        line("## Engine findings")
        line()
        for (f in engineFindings) line("- $f")
        line()
        line("## Unsupported or incomplete moves")
        line()
        val bad = moves.filter { it.getValue("classification").jsonPrimitive.content == "unsupported-or-incomplete" }
        if (bad.isEmpty()) line("None: every move was executed and its expected effects were observed.") else {
            for (m in bad) {
                line("- `${m.getValue("id").jsonPrimitive.content}` (${m.getValue("coverage").jsonPrimitive.content}): ${m.getValue("failures").jsonArray.joinToString("; ") { it.jsonPrimitive.content }}")
                line("  - repro: ${m.getValue("repro").jsonPrimitive.content}")
            }
        }
        line()
        line("## Moves without a battle effect")
        line()
        for (m in moves.filter { it.getValue("classification").jsonPrimitive.content == "no-battle-effect" }.sortedBy { it.getValue("id").jsonPrimitive.content })
            line("- `${m.getValue("id").jsonPrimitive.content}`: ${m.getValue("evidence").jsonArray.joinToString("; ") { it.jsonPrimitive.content }}")
        if (problems.isNotEmpty()) {
            line()
            line("## Problems merging the results")
            line()
            for (p in problems) line("- $p")
        }
        return sb.toString()
    }
}
