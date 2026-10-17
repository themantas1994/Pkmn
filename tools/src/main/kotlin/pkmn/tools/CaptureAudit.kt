package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.battle.CaptureRules
import pkmn.engine.battle.CatchCalc
import pkmn.engine.mon.Pokemon
import java.io.File
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/**
 * Capture audit (`:tools:auditCapture`): for every wild encounter slot in the campaign, the chance that one Poké Ball
 * catches the Pokémon at full HP, half HP and 1 HP, under
 *  - `before`: the formula shipped before this fix (Gen VIII-style low-level bonus, no badge penalty, fractional maths),
 *  - `sv`: exact Scarlet/Violet rules (low-level bonus and badge penalty), the Casual difficulty,
 *  - `crystalline`: the default difficulty (Scarlet/Violet without the low-level bonus).
 * The badge count used for each slot is the fewest badges with which its map is reachable (progression model).
 * Writes reports/capture.json and reports/capture.md.
 */
class CaptureAudit(private val root: File, private val write: Boolean = true) {
    data class Row(val table: String, val method: String, val species: String, val level: Int, val catchRate: Int, val badges: Int,
                   val before: List<Double>, val sv: List<Double>, val crystalline: List<Double>)

    /** The pre-fix engine formula, kept here only to measure the regression. */
    private fun beforeFix(catchRate: Int, level: Int, maxHp: Int, hp: Int): Double {
        val low = if (level < 13) (36 - 2 * level) / 10.0 else 1.0
        val a = min(255.0, maxOf(1.0, (3.0 * maxHp - 2.0 * hp) * catchRate / (3.0 * maxHp) * low))
        if (a >= 255) return 1.0
        val y = floor(65536 / (255 / a).pow(0.1875)) / 65536
        return y.pow(4)
    }

    fun run(): Boolean {
        val w = WorldAnalysis(root)
        val dex = w.dex
        val stages = (0..8).map { w.stage(it) }
        val tableBadges = HashMap<String, Int>()
        for (m in w.maps.values) {
            val t = m.encounters ?: continue
            val b = stages.firstOrNull { m.id in it.reach.maps }?.badges ?: 8
            tableBadges[t] = minOf(tableBadges[t] ?: 8, b)
        }
        val rows = ArrayList<Row>()
        for ((id, table) in w.content.encounters) for ((method, byTime) in table.methods) {
            val seen = HashSet<Pair<String, Int>>()
            for (slots in byTime.values) for (s in slots) {
                val sp = dex.speciesOrNull(s.species) ?: continue
                if (!seen.add(sp.id to s.minLevel)) continue
                val lv = s.minLevel
                val mon = Pokemon(sp.id, lv)
                val max = mon.maxHp(dex)
                val badges = tableBadges[id] ?: 8
                fun p(rules: CaptureRules, hp: Int): Double {
                    val l = if (rules.lowLevelBonus && lv < 13) (36 - 2 * lv) / 10.0 else 1.0
                    val f = CatchCalc.Factors(max, hp, sp.catchRate, 1.0, CatchCalc.badgePenalty(rules, lv), l, 1.0, 1.0, false)
                    return CatchCalc.successProbability(f, rules)
                }
                val hps = listOf(max, maxOf(1, max / 2), 1)
                val sv = CaptureRules(badges = badges, lowLevelBonus = true)
                val cr = CaptureRules(badges = badges, lowLevelBonus = false)
                rows += Row(id, method, sp.id, lv, sp.catchRate, badges, hps.map { beforeFix(sp.catchRate, lv, max, it) }, hps.map { p(sv, it) }, hps.map { p(cr, it) })
            }
        }
        fun share(sel: (Row) -> Double, pred: (Double) -> Boolean) = rows.count { pred(sel(it)) }.toDouble() / rows.size
        fun mean(sel: (Row) -> Double) = rows.sumOf(sel) / rows.size
        val summary = buildJsonObject {
            put("slots", rows.size)
            for ((name, sel) in listOf<Pair<String, (Row) -> List<Double>>>("before" to { it.before }, "sv" to { it.sv }, "crystalline" to { it.crystalline })) {
                putJsonObject(name) {
                    put("meanFullHp", r3(mean { sel(it)[0] })); put("meanHalfHp", r3(mean { sel(it)[1] })); put("mean1Hp", r3(mean { sel(it)[2] }))
                    put("shareFullHpAtLeast90", r3(share({ sel(it)[0] }) { it >= 0.9 }))
                    put("shareFullHpCertain", r3(share({ sel(it)[0] }) { it >= 1.0 }))
                }
            }
        }
        val json = buildJsonObject {
            put("generatedBy", "./gradlew :tools:auditCapture")
            put("rules", "docs/CAPTURE.md")
            put("summary", summary)
            putJsonArray("slots") {
                for (r in rows.sortedWith(compareBy({ it.badges }, { it.table }, { it.method }, { it.species }))) addJsonObject {
                    put("table", r.table); put("method", r.method); put("species", r.species); put("level", r.level)
                    put("catchRate", r.catchRate); put("badges", r.badges)
                    put("before", JsonArray(r.before.map { JsonPrimitive(r3(it)) }))
                    put("sv", JsonArray(r.sv.map { JsonPrimitive(r3(it)) }))
                    put("crystalline", JsonArray(r.crystalline.map { JsonPrimitive(r3(it)) }))
                }
            }
        }
        val md = buildString {
            appendLine("# Capture audit")
            appendLine()
            appendLine("Generated by `./gradlew :tools:auditCapture`. One Poké Ball, no status, at the lowest level of every wild encounter slot (${rows.size} slots). Rules: [docs/CAPTURE.md](../docs/CAPTURE.md).")
            appendLine()
            appendLine("| Formula | Mean, full HP | Mean, half HP | Mean, 1 HP | Slots ≥ 90% at full HP | Slots certain at full HP |")
            appendLine("|---|---|---|---|---|---|")
            for ((label, key) in listOf("Before the fix" to "before", "Scarlet/Violet exact (Casual)" to "sv", "Crystalline default" to "crystalline")) {
                val o = summary[key]!!.jsonObject
                fun pc(k: String) = "%.1f%%".format(o[k]!!.jsonPrimitive.double * 100)
                appendLine("| $label | ${pc("meanFullHp")} | ${pc("meanHalfHp")} | ${pc("mean1Hp")} | ${pc("shareFullHpAtLeast90")} | ${pc("shareFullHpCertain")} |")
            }
            appendLine()
            appendLine("Early slots (no badges), full HP:")
            appendLine()
            appendLine("| Table | Species | Lv | Catch rate | Before | S/V exact | Crystalline |")
            appendLine("|---|---|---|---|---|---|---|")
            for (r in rows.filter { it.badges == 0 && it.method == "grass" }.sortedBy { it.level }.distinctBy { it.species }.take(15))
                appendLine("| ${r.table} | ${r.species} | ${r.level} | ${r.catchRate} | ${pct(r.before[0])} | ${pct(r.sv[0])} | ${pct(r.crystalline[0])} |")
        }
        if (write) {
            File(root, "reports").mkdirs()
            File(root, "reports/capture.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json) + "\n")
            File(root, "reports/capture.md").writeText(md)
        }
        println(md.lines().take(9).joinToString("\n"))
        return true
    }

    private fun r3(d: Double) = Math.round(d * 1000) / 1000.0
    private fun pct(d: Double) = "%.0f%%".format(d * 100)
}
