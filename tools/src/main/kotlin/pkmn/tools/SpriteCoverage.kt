package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.util.toId
import java.io.File

/**
 * Q0.3: which species the campaign actually shows, per art tier, and how many of them have bundled sprites.
 * Writes reports/sprite-coverage.json.
 *
 * Sources: wild tables (assets/world/encounters.json), trainer teams (assets/world/trainers.json), scripted gifts, eggs
 * and static battles (`givemon`, `giveegg`, `wildbattle` in the world/scripts pks files) and scheduled events (assets/events/events.json).
 *
 * Tiers (docs: graphical-upgrade roadmap, Phase 4b). A species gets the lowest tier of any use:
 * 1 = starters and their evolution lines (gifts in p1_lab), Part 1 wild tables (ids without a `pN_` prefix), Part 1 rival
 *     (`rival_*`), gym 1-2 teams (`gym1_*`, `gym2_*`, `p1_leader_*`);
 * 2 = leader/elite/champion teams, wild tables of Routes 5-9;
 * 3 = every other used species; 4 = species never used (only counted).
 *
 * Status looks only at the bundled front, back and icon PNGs under `assets/sprites/pokemon`, so the report is identical on every machine.
 * A git-ignored `assets-local/` override is never read and never changes it.
 */
class SpriteCoverage(private val root: File, private val write: Boolean = true) {
    data class Row(val species: String, val tier: Int, val status: String, val sources: List<String>)
    data class TierSummary(val species: Int, val original: Int, val partial: Int) {
        val fallback get() = species - original - partial
        val complete get() = species > 0 && original == species
    }
    class Result(val rows: List<Row>, val unusedSpecies: Int, val unknown: Map<String, List<String>>) {
        val tiers: Map<Int, TierSummary> = (1..3).associateWith { t ->
            val r = rows.filter { it.tier == t }
            TierSummary(r.size, r.count { it.status == "original" }, r.count { it.status == "partial" })
        }
    }

    private class Use(val species: String, val kind: String, val ref: String, val tier: Int)

    private val kinds = listOf("front", "back", "icon")
    private val routes5to9 = setOf("p2_route5", "p2_route6", "p2_route7", "p2_route8", "p3a_route9")
    private val pnPrefix = Regex("""^p\d+[a-z]?_""")

    private fun json(path: String): JsonElement? = File(root, path).takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()) }

    private fun collectEncounterSpecies(e: JsonElement, out: MutableList<String>) {
        when (e) {
            is JsonObject -> { (e["species"] as? JsonPrimitive)?.contentOrNull?.let { out += it }; e.values.forEach { collectEncounterSpecies(it, out) } }
            is JsonArray -> e.forEach { collectEncounterSpecies(it, out) }
            else -> {}
        }
    }

    fun compute(): Result {
        val species = json("assets/data/species.json")!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }
        val uses = ArrayList<Use>()

        for (t in json("assets/world/encounters.json")?.jsonArray.orEmpty()) {
            val id = t.jsonObject["id"]!!.jsonPrimitive.content
            val tier = when { id in routes5to9 -> 2; !pnPrefix.containsMatchIn(id) -> 1; else -> 3 }
            val ids = ArrayList<String>().also { collectEncounterSpecies(t, it) }
            ids.forEach { uses += Use(toId(it), "wild", id, tier) }
        }
        for (t in json("assets/world/trainers.json")?.jsonArray.orEmpty()) {
            val o = t.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val cls = o["trainerClass"]?.jsonPrimitive?.contentOrNull
            val tier = when {
                id.startsWith("rival_") || id.startsWith("gym1_") || id.startsWith("gym2_") || id.startsWith("p1_leader_") -> 1
                cls == "leader" || cls == "elite" || cls == "champion" -> 2
                else -> 3
            }
            o["team"]?.jsonArray?.forEach { uses += Use(toId(it.jsonObject["species"]!!.jsonPrimitive.content), "trainer", id, tier) }
        }
        for (e in json("assets/events/events.json")?.jsonArray.orEmpty()) {
            val o = e.jsonObject
            o["species"]?.jsonPrimitive?.contentOrNull?.let { uses += Use(toId(it), "event", o["id"]!!.jsonPrimitive.content, 3) }
        }
        val cmd = Regex("""^\s*(givemon|giveegg|wildbattle)\s+(\S+)""")
        val scripts = File(root, "world/scripts").listFiles { f -> f.extension == "pks" }?.sortedBy { it.name }.orEmpty()
        for (f in scripts) f.readLines().forEachIndexed { i, line ->
            val m = cmd.find(line) ?: return@forEachIndexed
            val kind = if (m.groupValues[1] == "wildbattle") "static" else "gift"
            val starter = m.groupValues[1] == "givemon" && f.nameWithoutExtension == "p1_lab"
            uses += Use(toId(m.groupValues[2]), kind, "${f.name}:${i + 1}", if (starter) 1 else 3)
        }

        // Starter evolution lines are tier 1.
        val evolvesTo = species.mapValues { (_, o) -> o["evolutions"]?.jsonArray?.map { toId(it.jsonObject["target"]!!.jsonPrimitive.content) }.orEmpty() }
        val starterLines = LinkedHashSet<String>()
        fun line(id: String) { if (starterLines.add(id)) evolvesTo[id].orEmpty().forEach(::line) }
        uses.filter { it.kind == "gift" && it.tier == 1 }.forEach { line(it.species) }
        starterLines.forEach { uses += Use(it, "starter-line", it, 1) }

        val unknown = uses.filter { it.species !in species }.groupBy({ it.species }, { "${it.kind}:${it.ref}" }).toSortedMap()
        val rows = uses.filter { it.species in species }.groupBy { it.species }.toSortedMap().map { (id, us) ->
            val have = kinds.count { File(root, "assets/sprites/pokemon/$it/$id.png").isFile }
            val status = when (have) { kinds.size -> "original"; 0 -> "fallback"; else -> "partial" }
            val src = us.map { "${it.kind}:${it.ref}" }.distinct()
            Row(id, us.minOf { it.tier }, status, src)
        }
        return Result(rows, species.size - rows.size, unknown)
    }

    fun run(): Boolean {
        val res = compute()
        for ((id, refs) in res.unknown) System.err.println("sprite-coverage: unknown species '$id' used by ${refs.take(3).joinToString()}")
        if (write) writeJson(File(root, "reports/sprite-coverage.json"), buildJsonObject {
            put("generatedBy", "./gradlew :tools:spriteCoverage")
            put("statusRule", "original = bundled assets/sprites/pokemon/{front,back,icon}/<id>.png all exist; partial = some; fallback = none (procedural placeholder). assets-local/ is ignored.")
            put("tierRules", buildJsonObject {
                put("1", "starters and their lines; Part 1 wild tables (no pN_ prefix); Part 1 rival (rival_*); gym 1-2 (gym1_*, gym2_*, p1_leader_*)")
                put("2", "leader, elite and champion teams; wild tables of Routes 5-9 (p2_route5-8, p3a_route9)")
                put("3", "every other species used by wild tables, trainers, gifts, eggs, static battles or events")
                put("4", "species never used (counted only)")
            })
            put("usedSpecies", res.rows.size)
            put("unusedSpecies", res.unusedSpecies)
            put("tiers", buildJsonObject {
                for ((t, s) in res.tiers) put(t.toString(), buildJsonObject {
                    put("species", s.species); put("original", s.original); put("partial", s.partial); put("fallback", s.fallback); put("complete", s.complete)
                })
                put("4", buildJsonObject { put("species", res.unusedSpecies) })
            })
            put("species", JsonArray(res.rows.sortedWith(compareBy({ it.tier }, { it.species })).map { r ->
                buildJsonObject { put("id", r.species); put("tier", r.tier); put("status", r.status); put("sources", strArray(r.sources.take(5))); put("sourceCount", r.sources.size) }
            }))
        })
        println("sprite-coverage: ${res.rows.size} used species, " + res.tiers.entries.joinToString { (t, s) -> "tier $t ${s.original}/${s.species} original" } + ", ${res.unusedSpecies} unused")
        return res.unknown.isEmpty()
    }
}
