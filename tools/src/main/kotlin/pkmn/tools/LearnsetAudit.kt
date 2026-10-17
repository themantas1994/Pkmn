package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.*
import pkmn.engine.mon.PokemonFactory
import pkmn.engine.mon.Progression
import pkmn.engine.util.Rng
import pkmn.engine.util.toId
import java.io.File

/*
 * Learnset legality audit ("Crystalline move-availability policy", see docs/LEARNSETS.md).
 *
 * The audit is split in two layers so it can be tested with synthetic data:
 *  - [LearnsetAuditCore] works on an [AuditInput] (a Dex, the raw Showdown learnsets and the world's Pokémon/TM/tutor
 *    references) and produces findings, conflicts and the JSON/Markdown reports;
 *  - [LearnsetAudit] loads the real files of the repository, builds the input and writes reports/learnsets.{json,md}.
 *
 * Both Pokémon Showdown and PokeAPI are community-maintained datasets, not official game data.
 */

/** A Pokémon the world fields or hands out (trainer team member, gift, scripted battle, trade, scheduled event). */
data class WorldMon(val kind: String, val owner: String, val species: String, val level: Int, val moves: List<String>, val source: String)

/** A TM placed in the world (map pickup, script reward, shop). */
data class TmPlacement(val move: String, val kind: String, val source: String)

data class TutorDef(val id: String, val moves: List<String>, val source: String)

data class Finding(val severity: String, val code: String, val subject: String, val message: String)

/** An unresolved or policy-relevant species/move conflict. */
data class Conflict(
    val species: String, val move: String, val issue: String,
    val selectedValue: String, val justification: String, val source: String,
)

class AuditInput(
    val dex: Dex,
    /** Raw Showdown learnsets: species id -> move id -> source codes ("9L24", "9M", "7E", "6S3", ...). */
    val raw: Map<String, Map<String, List<String>>>,
    /** Showdown formats-data `isNonstandard` per species id (absent = legal in the current games). */
    val formatsStatus: Map<String, String>,
    val worldMons: List<WorldMon>,
    val tutors: List<TutorDef>,
    val tms: List<TmPlacement>,
    /** Species handed out or encountered in the world (before evolution closure). */
    val obtainableRoots: Set<String>,
    val provenance: JsonElement? = null,
    /** Compare assets/data/learnsets.json with a recomputation from [raw] (needs the complete raw Showdown data). */
    val crossCheckImporter: Boolean = true,
    /** Extra findings produced while reading the world files (e.g. compiled shops losing the `tm:` prefix). */
    val loaderFindings: List<Finding> = emptyList(),
)

class LearnsetAuditResult(val json: JsonObject, val markdown: String, val findings: List<Finding>, val conflicts: List<Conflict>) {
    val errors get() = findings.count { it.severity == "error" }
    val warnings get() = findings.count { it.severity == "warning" }
}

enum class Route { LEVEL_UP, PREVO_LEVEL_UP, MACHINE, LEVEL_TOO_HIGH, OLDER_LEVEL_UP, EGG, FORM_SIGNATURE, EVENT, NONE }
data class Verdict(val route: Route, val detail: String) {
    /** Legal under the Crystalline policy (egg-only and event-only are conflicts, not legal). */
    val legal get() = route == Route.LEVEL_UP || route == Route.PREVO_LEVEL_UP || route == Route.MACHINE
}

class LearnsetAuditCore(private val inp: AuditInput) {
    private val dex = inp.dex
    private val findings = ArrayList<Finding>().apply { addAll(inp.loaderFindings) }
    private val conflicts = ArrayList<Conflict>()

    private fun error(code: String, subject: String, msg: String) { findings += Finding("error", code, subject, msg) }
    private fun warn(code: String, subject: String, msg: String) { findings += Finding("warning", code, subject, msg) }
    private fun info(code: String, subject: String, msg: String) { findings += Finding("info", code, subject, msg) }

    private val allSpecies = dex.species.values.sortedWith(compareBy({ it.num }, { it.id }))
    private val nonLegalStatuses = setOf("Past", "Unobtainable", "LGPE", "CAP", "Custom", "Gmax", "Future")

    // ------------------------------------------------------------------ learnset access (mirrors Dex / Progression)

    private class Sets(val level: Map<String, Int>, val machine: Set<String>, val egg: Set<String>, val owner: String)

    private val setsCache = HashMap<String, Sets?>()
    private fun setsOf(id: String): Sets? = setsCache.getOrPut(id) {
        dex.learnset(id)?.let { ls ->
            Sets(ls.levelUp.groupBy { it.move }.mapValues { e -> e.value.minOf { it.level } }, ls.machine.toSet(), ls.egg.toSet(), ls.species)
        }
    }

    /** Species followed back through its pre-evolutions (the same walk as Progression.canLearnMachine). */
    private fun chain(sp: SpeciesData): List<SpeciesData> {
        val out = ArrayList<SpeciesData>()
        val seen = HashSet<String>()
        var cur: SpeciesData? = sp
        while (cur != null && seen.add(cur.id)) { out += cur; cur = cur.prevo?.let { dex.speciesOrNull(it) } }
        return out
    }

    private fun rawEntry(speciesId: String): Map<String, List<String>>? {
        inp.raw[speciesId]?.let { return it }
        val sp = dex.speciesOrNull(speciesId) ?: return null
        sp.changesFrom?.let { c -> inp.raw[toId(c)]?.let { return it } }
        return inp.raw[toId(sp.baseSpecies)]
    }

    private fun rawSources(sp: SpeciesData, move: String): List<String> = chain(sp).flatMap { rawEntry(it.id)?.get(move).orEmpty() }

    private fun isGen9Source(s: String) = s.startsWith("9")

    /** How [speciesId] at [level] can legally have [move] under the policy. */
    fun verdict(speciesId: String, move: String, level: Int): Verdict {
        val sp = dex.speciesOrNull(speciesId) ?: return Verdict(Route.NONE, "unknown species")
        val ch = chain(sp)
        var tooHigh: Int? = null
        for ((i, s) in ch.withIndex()) {
            val lv = setsOf(s.id)?.level?.get(move) ?: continue
            if (lv <= level) return Verdict(if (i == 0) Route.LEVEL_UP else Route.PREVO_LEVEL_UP, "level $lv" + if (i > 0) " as ${s.id}" else "")
            tooHigh = minOf(tooHigh ?: lv, lv)
        }
        for (s in ch) if (setsOf(s.id)?.machine?.contains(move) == true) {
            val gen9 = rawSources(sp, move).any { it.startsWith("9M") || it.startsWith("9T") }
            return Verdict(Route.MACHINE, if (gen9) "TM/TR/tutor (Gen IX)" else "TM/TR/tutor (older generations only)")
        }
        if (tooHigh != null) return Verdict(Route.LEVEL_TOO_HIGH, "learned at level $tooHigh, above level $level")
        olderLevelUp(ch, move)?.let { return Verdict(Route.OLDER_LEVEL_UP, it) }
        if (ch.any { setsOf(it.id)?.egg?.contains(move) == true }) return Verdict(Route.EGG, "egg move (breeding not implemented)")
        val src = rawSources(sp, move)
        if (src.any { Regex("^\\d+R").containsMatchIn(it) }) return Verdict(Route.FORM_SIGNATURE, "Showdown 'R' source (move required by the form); the importer does not import R sources: " + src.joinToString(","))
        if (src.any { Regex("^\\d+[SVD]").containsMatchIn(it) }) return Verdict(Route.EVENT, "event/LGPE/VC/Dream World source only: " + src.joinToString(","))
        return Verdict(Route.NONE, "not in the species' (or a pre-evolution's) level-up, TM/tutor or egg lists")
    }

    /** Level-up sources from a generation older than the selected level-up list (dropped by the policy). */
    private fun olderLevelUp(ch: List<SpeciesData>, move: String): String? {
        for (s in ch) {
            val selected = dex.learnset(s.id)?.levelUpGen ?: continue
            val older = rawEntry(s.id)?.get(move).orEmpty().mapNotNull { Regex("^(\\d+)L(\\d+)$").matchEntire(it) }
                .filter { it.groupValues[1].toInt() < selected }
            if (older.isNotEmpty()) return "level-up only in older generations (${older.joinToString(",") { it.value }}) and not in the selected Gen $selected list" + if (s !== ch.first()) " (as ${s.id})" else ""
        }
        return null
    }

    /** Same rule as Progression.canLearnMachine: level-up or machine list of the species or a pre-evolution. */
    private fun machineLegal(sp: SpeciesData, move: String) = chain(sp).any { s -> setsOf(s.id)?.let { move in it.machine || move in it.level } == true }

    private fun gen9Legal(sp: SpeciesData, move: String) = chain(sp).any { s ->
        setsOf(s.id)?.let { st -> move in st.machine && rawSources(s, move).any { it.startsWith("9M") || it.startsWith("9T") } || st.level[move] != null && dex.learnset(s.id)?.levelUpGen == 9 } == true
    }

    // ------------------------------------------------------------------ derived world facts

    private val obtainable: Set<String> by lazy {
        val out = LinkedHashSet<String>()
        val todo = ArrayDeque(inp.obtainableRoots.filter { dex.speciesOrNull(it) != null })
        while (todo.isNotEmpty()) {
            val id = todo.removeFirst()
            if (!out.add(id)) continue
            dex.speciesOrNull(id)?.evolutions?.forEach { if (dex.speciesOrNull(it.target) != null) todo.addLast(it.target) }
        }
        out
    }

    fun run(): LearnsetAuditResult {
        val report = LinkedHashMap<String, JsonElement>()
        report["dataset"] = inp.provenance ?: JsonNull
        report["policy"] = policy()
        report["coverage"] = coverage()
        report["integrity"] = integrity()
        report["importerCrossCheck"] = importerCrossCheck()
        report["levelUpGenerations"] = levelUpGenerations()
        report["defaultWildMovesets"] = defaultMovesets()
        report["machineGenerations"] = machineGenerations()
        report["nonstandardDependencies"] = nonstandard()
        report["signatureMoves"] = signatureMoves()
        report["world"] = world()
        report["tmAvailability"] = tmAvailability()
        report["tutors"] = tutors()
        report["conflicts"] = conflictsJson()
        val summary = buildJsonObject {
            put("species", dex.species.size)
            put("errors", findings.count { it.severity == "error" })
            put("warnings", findings.count { it.severity == "warning" })
            put("infos", findings.count { it.severity == "info" })
            put("errorsByCode", countBy(findings.filter { it.severity == "error" }.map { it.code }))
            put("warningsByCode", countBy(findings.filter { it.severity == "warning" }.map { it.code }))
            put("conflicts", conflicts.size)
            put("conflictsByIssue", countBy(conflicts.map { it.issue }))
        }
        val json = buildJsonObject {
            put("summary", summary)
            for ((k, v) in report) put(k, v)
            put("findings", JsonArray(findings.sortedWith(compareBy({ it.severity != "error" }, { it.severity != "warning" }, { it.code }, { it.subject })).map {
                buildJsonObject { put("severity", it.severity); put("code", it.code); put("subject", it.subject); put("message", it.message) }
            }))
        }
        return LearnsetAuditResult(json, markdown(json), findings.toList(), conflicts.toList())
    }

    // ------------------------------------------------------------------ report sections

    private fun policy() = buildJsonObject {
        put("name", "Crystalline move-availability policy")
        put("levelUp", "Most recent generation's level-up list that exists for the species/form (Gen IX when it is in Scarlet/Violet, otherwise its latest game). Level 0 = learned on evolution, level 1 = starting moves.")
        put("machine", "A move is machine-legal if the species or a pre-evolution can learn it by TM/TR/tutor in ANY generation (the importer's behaviour); legal in older generations only is counted separately.")
        put("egg", "Egg moves are recorded, breeding is not implemented: out of scope, world assignments that rely on them are reported as conflicts.")
        put("event", "Event-only (S) and Let's Go / Virtual Console (V) sources are not learnable in-game.")
        put("forms", "A form uses its own Showdown learnset when it has one with a level-up list, otherwise Dex.learnset inherits changesFrom, then the base species.")
        put("nonstandard", "Moves with a Showdown isNonstandard flag (Past, Unobtainable, LGPE, CAP, Custom, Gmax, Future) are reported with every species that depends on them.")
    }

    private fun coverage(): JsonObject {
        var own = 0; var base = 0; var changes = 0; var missing = 0; var emptyOwn = 0
        val inherited = ArrayList<JsonObject>()
        val missingIds = ArrayList<String>()
        val emptyLevelUpBase = ArrayList<String>()
        val inheritedTypeDiffers = ArrayList<String>()
        val partial = ArrayList<String>()
        val byKind = LinkedHashMap<String, IntArray>()
        for (sp in allSpecies) {
            val kind = sp.formKind.name
            val counts = byKind.getOrPut(kind) { IntArray(4) } // own, inherited, missing, total
            counts[3]++
            val own0 = dex.learnsets[sp.id]
            val eff = dex.learnset(sp.id)
            when {
                own0 != null && own0.levelUp.isNotEmpty() -> { own++; counts[0]++ }
                eff == null -> { missing++; counts[2]++; missingIds += sp.id; error("learnset-missing", sp.id, "${sp.id} has no learnset (own, changesFrom or base)") }
                eff.levelUp.isEmpty() -> { // nothing usable to fall back to: default moves become the Tackle fallback
                    emptyOwn++; counts[0]++; emptyLevelUpBase += sp.id
                    error("empty-level-up", sp.id, "${sp.id} resolves to ${eff.species}'s learnset, which has an empty level-up list (default moves fall back to Tackle)")
                }
                else -> {
                    val viaChanges = sp.changesFrom?.let { toId(it) } == eff.species
                    if (viaChanges) changes++ else base++
                    counts[1]++
                    inherited += buildJsonObject {
                        put("species", sp.id); put("formKind", kind); put("inheritsFrom", eff.species)
                        put("via", if (viaChanges) "changesFrom" else "baseSpecies")
                        put("ownEntryDiscarded", own0 != null)
                    }
                    if (own0 != null) {
                        warn("own-data-discarded", sp.id, "${sp.id} has its own learnset entry (machine=${own0.machine.size}, egg=${own0.egg.size}) but an empty level-up list, so Dex.learnset ignores it and uses ${eff.species}")
                    }
                    val b = dex.speciesOrNull(eff.species)
                    val cosmeticLike = sp.formKind in setOf(FormKind.MEGA, FormKind.PRIMAL, FormKind.GIGANTAMAX, FormKind.BATTLE_ONLY, FormKind.ULTRA_BURST, FormKind.TOTEM)
                    if (b != null && !cosmeticLike && b.types.toSet() != sp.types.toSet()) {
                        inheritedTypeDiffers += sp.id
                        warn("inherited-form-types-differ", sp.id, "${sp.id} (${sp.types.joinToString("/")}) inherits ${b.id}'s learnset (${b.types.joinToString("/")}); its real movepool may differ")
                    }
                }
            }
            // Forms with an own learnset that is much smaller than the base species' look partial.
            if (own0 != null && sp.formKind != FormKind.BASE) {
                val b = dex.learnsets[toId(sp.baseSpecies)]
                if (b != null && own0.levelUp.size + own0.machine.size < (b.levelUp.size + b.machine.size) / 3 && b.levelUp.size + b.machine.size >= 20)
                    partial += sp.id
            }
        }
        if (partial.isNotEmpty()) info("partial-form-learnset", partial.size.toString(), "forms whose own learnset is under a third of the base species' size: ${partial.joinToString(" ")}")
        return buildJsonObject {
            put("species", dex.species.size)
            put("own", own); put("inheritedFromBase", base); put("inheritedFromChangesFrom", changes)
            put("ownWithEmptyLevelUp", emptyOwn); put("missing", missing)
            put("byFormKind", buildJsonObject {
                for ((k, c) in byKind) put(k, buildJsonObject { put("total", c[3]); put("own", c[0]); put("inherited", c[1]); put("missing", c[2]) })
            })
            put("missingSpecies", strArray(missingIds))
            put("emptyLevelUpOnBaseSpecies", strArray(emptyLevelUpBase))
            put("inheritedFormsWithDifferentTypes", strArray(inheritedTypeDiffers))
            put("formsWithPartialOwnLearnset", strArray(partial))
            put("inheritingForms", JsonArray(inherited))
        }
    }

    private fun integrity(): JsonObject {
        var duplicates = 0; var invalid = 0; var badLevel = 0; var unsorted = 0; var repeatMoves = 0
        val repeatSamples = ArrayList<String>()
        for ((id, ls) in dex.learnsets.entries.sortedBy { it.key }) {
            if (dex.speciesOrNull(id) == null) error("learnset-unknown-species", id, "learnsets.json has an entry for unknown species '$id'")
            val seen = HashSet<Pair<Int, String>>()
            var prev = -1
            for (lm in ls.levelUp) {
                if (dex.moveOrNull(lm.move) == null || dex.moveOrNull(lm.move)?.id != lm.move) { invalid++; error("invalid-move-id", id, "level-up move '${lm.move}' at level ${lm.level} is not a move id") }
                if (lm.level !in 0..100) { badLevel++; error("level-out-of-range", id, "${lm.move} at level ${lm.level} (valid range 0..100)") }
                if (!seen.add(lm.level to lm.move)) { duplicates++; error("duplicate-level-move", id, "${lm.move} appears twice at level ${lm.level}") }
                if (lm.level < prev) { unsorted++; error("level-up-unsorted", id, "level-up list is not ascending at ${lm.move} (level ${lm.level} after $prev); PokemonFactory.defaultMoves relies on the order") }
                prev = maxOf(prev, lm.level)
            }
            for ((name, list) in listOf("machine" to ls.machine, "egg" to ls.egg)) {
                for (m in list) if (dex.moveOrNull(m)?.id != m) { invalid++; error("invalid-move-id", id, "$name move '$m' is not a move id") }
                val dupes = list.groupBy { it }.filter { it.value.size > 1 }.keys
                if (dupes.isNotEmpty()) { duplicates += dupes.size; error("duplicate-move-id", id, "$name list repeats ${dupes.joinToString(",")}") }
            }
            if (ls.levelUpGen !in 0..9) error("level-up-gen-invalid", id, "levelUpGen=${ls.levelUpGen}")
            if (ls.levelUp.isEmpty() && ls.levelUpGen != 0) warn("level-up-gen-without-moves", id, "empty level-up list but levelUpGen=${ls.levelUpGen}")
            val multi = ls.levelUp.groupBy { it.move }.filter { e -> e.value.map { it.level }.distinct().size > 1 }
            if (multi.isNotEmpty()) { repeatMoves += multi.size; if (repeatSamples.size < 8) repeatSamples += "$id:${multi.keys.first()}" }
        }
        if (repeatMoves > 0) info("move-at-several-levels", repeatMoves.toString(), "moves listed at several different levels in one species' level-up list (legal: relearnable); e.g. ${repeatSamples.joinToString(" ")}")
        return buildJsonObject {
            put("learnsetEntries", dex.learnsets.size)
            put("duplicates", duplicates); put("invalidMoveIds", invalid); put("levelsOutOfRange", badLevel); put("unsortedLevelUp", unsorted)
            put("movesAtSeveralLevels", repeatMoves)
        }
    }

    /** Independent re-implementation of DataImporter.importLearnsets (regex based) compared to assets/data/learnsets.json. */
    fun recompute(src: Map<String, List<String>>, moveIds: Set<String>): LearnsetData {
        val rx = Regex("^(\\d+)([A-Za-z])(\\d*)$")
        val levelByGen = java.util.TreeMap<Int, MutableSet<Pair<Int, String>>>()
        val machine = java.util.TreeSet<String>()
        val egg = java.util.TreeSet<String>()
        for ((move, sources) in src) {
            if (move !in moveIds) continue
            for (s in sources) {
                val m = rx.matchEntire(s) ?: continue
                val gen = m.groupValues[1].toInt()
                when (m.groupValues[2]) {
                    "L" -> levelByGen.getOrPut(gen) { LinkedHashSet() }.add((m.groupValues[3].toIntOrNull() ?: 1) to move)
                    "M", "T" -> machine += move
                    "E" -> egg += move
                }
            }
        }
        val gen = if (levelByGen.isEmpty()) 0 else levelByGen.lastKey()
        val levelUp = levelByGen[gen].orEmpty().sortedWith(compareBy({ it.first }, { it.second })).map { LevelMove(it.first, it.second) }
        return LearnsetData("", levelUp, gen, machine.toList(), egg.toList())
    }

    private fun importerCrossCheck(): JsonObject {
        if (!inp.crossCheckImporter) return buildJsonObject { put("skipped", true) }
        val speciesIds = dex.species.keys
        val moveIds = dex.moves.keys
        val expected = HashMap<String, LearnsetData>()
        for ((id, src) in inp.raw) if (id in speciesIds) expected[id] = recompute(src, moveIds)
        val discrepancies = ArrayList<JsonObject>()
        fun record(species: String, field: String, exp: String, act: String) {
            discrepancies += buildJsonObject { put("species", species); put("field", field); put("expected", exp); put("actual", act) }
            error("importer-discrepancy", species, "$field differs between the raw Showdown data and assets/data/learnsets.json: expected $exp, actual $act")
            conflicts += Conflict(species, "-", "importer-discrepancy:$field", act, "assets/data/learnsets.json must equal the independent recomputation from data-src/showdown/learnsets.json (expected $exp); re-run :tools:importData", "assets/data/learnsets.json")
        }
        fun brief(l: List<Any>) = "${l.size} entries" + (l.firstOrNull()?.let { " (first $it)" } ?: "")
        for ((id, exp) in expected.entries.sortedBy { it.key }) {
            val act = dex.learnsets[id]
            if (act == null) { record(id, "entry", "present", "missing"); continue }
            if (act.levelUp != exp.levelUp) {
                val diff = (exp.levelUp.toSet() - act.levelUp.toSet()).map { "${it.level}:${it.move}" } + (act.levelUp.toSet() - exp.levelUp.toSet()).map { "-${it.level}:${it.move}" }
                record(id, "levelUp", brief(exp.levelUp.map { "${it.level}:${it.move}" }), brief(act.levelUp.map { "${it.level}:${it.move}" }) + if (diff.isNotEmpty()) " diff ${diff.take(5)}" else " (order differs)")
            }
            if (act.levelUpGen != exp.levelUpGen) record(id, "levelUpGen", exp.levelUpGen.toString(), act.levelUpGen.toString())
            if (act.machine != exp.machine) record(id, "machine", brief(exp.machine), brief(act.machine) + " diff ${((exp.machine.toSet() - act.machine.toSet()) + (act.machine.toSet() - exp.machine.toSet())).take(5)}")
            if (act.egg != exp.egg) record(id, "egg", brief(exp.egg), brief(act.egg) + " diff ${((exp.egg.toSet() - act.egg.toSet()) + (act.egg.toSet() - exp.egg.toSet())).take(5)}")
        }
        for (id in dex.learnsets.keys.sorted()) if (id !in expected) record(id, "entry", "absent (no raw learnset)", "present")
        val rawNotImported = inp.raw.keys.filter { it !in speciesIds }.sorted()
        val droppedMoves = java.util.TreeMap<String, Int>()
        for ((id, src) in inp.raw) if (id in speciesIds) for (m in src.keys) if (m !in moveIds) droppedMoves.merge(m, 1, Int::plus)
        val speciesWithoutRaw = allSpecies.filter { it.id !in inp.raw }.map { it.id }
        // Source codes the importer ignores: S (event), V (LGPE/VC), D (Dream World), R (required by the form).
        val ignored = java.util.TreeMap<String, Int>()
        val rPairs = ArrayList<String>()
        var onlyIgnored = 0
        for ((id, src) in inp.raw.entries.sortedBy { it.key }) if (id in speciesIds) for ((m, ss) in src) {
            if (m !in moveIds) continue
            val codes = ss.map { it.dropWhile { c -> c.isDigit() }.take(1) }
            for (c in codes) if (c !in setOf("L", "M", "T", "E")) ignored.merge(c, 1, Int::plus)
            if (codes.none { it in setOf("L", "M", "T", "E") }) { onlyIgnored++; if ("R" in codes) rPairs += "$id:$m" }
        }
        if (rPairs.isNotEmpty()) warn("importer-ignores-r-sources", rPairs.size.toString(),
            "DataImporter.importLearnsets drops Showdown 'R' (move required by the form) sources, so these form signature moves are unlearnable for the form: ${rPairs.joinToString(" ")}")
        return buildJsonObject {
            put("rawSpeciesEntries", inp.raw.size)
            put("rawEntriesForImportedSpecies", expected.size)
            put("rawEntriesNotImported", rawNotImported.size)
            put("rawEntriesNotImportedSample", strArray(rawNotImported.take(40)))
            put("speciesWithoutRawEntry", speciesWithoutRaw.size)
            put("rawMovesMissingFromMoveData", buildJsonObject { for ((m, n) in droppedMoves) put(m, n) })
            put("ignoredSourceCodes", buildJsonObject { for ((c, n) in ignored) put(c, n) })
            put("speciesMovePairsWithOnlyIgnoredSources", onlyIgnored)
            put("formSignaturePairsDropped", strArray(rPairs))
            put("discrepancyCount", discrepancies.size)
            put("discrepancies", JsonArray(discrepancies.take(200)))
        }
    }

    private fun levelUpGenerations(): JsonObject {
        val dist = java.util.TreeMap<Int, Int>()
        val svButOld = ArrayList<String>()
        val pastButGen9 = ArrayList<String>()
        var level0 = 0; var level1 = 0
        for ((id, ls) in dex.learnsets.entries.sortedBy { it.key }) {
            dist.merge(ls.levelUpGen, 1, Int::plus)
            level0 += ls.levelUp.count { it.level == 0 }; level1 += ls.levelUp.count { it.level == 1 }
            if (ls.levelUp.isEmpty()) continue
            val status = inp.formatsStatus[id]
            if (status == null && ls.levelUpGen != 9) svButOld += "$id(g${ls.levelUpGen})"
            if (status == "Past" && ls.levelUpGen == 9) pastButGen9 += id
        }
        if (svButOld.isNotEmpty()) warn("current-species-old-level-up", svButOld.size.toString(), "species with no Showdown isNonstandard flag whose level-up list is older than Gen IX: ${svButOld.take(30).joinToString(" ")}")
        if (pastButGen9.isNotEmpty()) info("past-species-gen9-level-up", pastButGen9.size.toString(), "species flagged Past that still have a Gen IX level-up list: ${pastButGen9.take(30).joinToString(" ")}")
        return buildJsonObject {
            put("levelUpGenDistribution", buildJsonObject { for ((g, n) in dist) put(g.toString(), n) })
            put("level0Entries", level0); put("level1Entries", level1)
            put("notFlaggedButOlderThanGen9", strArray(svButOld))
            put("flaggedPastButGen9", strArray(pastButGen9))
            put("speciesByShowdownStatus", countBy(allSpecies.map { inp.formatsStatus[it.id] ?: "legal" }))
        }
    }

    private fun defaultMovesets(): JsonObject {
        val factory = PokemonFactory(dex)
        val levels = listOf(1, 5, 25, 50, 100)
        var checked = 0
        val tackleFallback = ArrayList<String>()
        val fewAtMax = ArrayList<String>()
        val illegal = ArrayList<String>()
        for (sp in allSpecies) {
            val fallbackLevels = ArrayList<Int>()
            for (lvl in levels) {
                checked++
                val moves = factory.defaultMoves(sp.id, lvl)
                if (moves.size > 4) error("default-moveset-too-big", sp.id, "default moveset at level $lvl has ${moves.size} moves: $moves")
                if (moves.toSet().size != moves.size) error("default-moveset-duplicate", sp.id, "default moveset at level $lvl repeats a move: $moves")
                for (m in moves) {
                    if (dex.moveOrNull(m) == null) { error("default-moveset-invalid-move", sp.id, "default move '$m' at level $lvl is not a move"); continue }
                    val v = verdict(sp.id, m, lvl)
                    if (!v.legal) {
                        if (m == "tackle" && moves.size == 1) fallbackLevels += lvl
                        else { illegal += "${sp.id}@$lvl:$m"; error("default-moveset-illegal", sp.id, "default move $m at level $lvl is not legal: ${v.detail}") }
                    }
                }
            }
            if (fallbackLevels.isNotEmpty()) {
                tackleFallback += sp.id
                warn("default-moveset-tackle-fallback", sp.id, "default moveset is the Tackle fallback at levels $fallbackLevels although Tackle is not in its learnset")
            }
            if (factory.defaultMoves(sp.id, 100).size < 4) fewAtMax += sp.id
        }
        return buildJsonObject {
            put("levelsChecked", JsonArray(levels.map { JsonPrimitive(it) }))
            put("checks", checked)
            put("tackleFallbackSpecies", strArray(tackleFallback))
            put("illegalDefaultMoves", strArray(illegal))
            put("speciesWithFewerThanFourMovesAtLevel100", fewAtMax.size)
            put("speciesWithFewerThanFourMovesAtLevel100List", strArray(fewAtMax.take(200)))
        }
    }

    private fun machineGenerations(): JsonObject {
        var total = 0; var gen9 = 0; var olderSv = 0; var olderNotSv = 0; var inLevelUp = 0
        val perSpecies = HashMap<String, Int>()
        val perMove = HashMap<String, Int>()
        val latestGen = java.util.TreeMap<Int, Int>()
        val kinds = java.util.TreeMap<String, Int>()
        for ((id, ls) in dex.learnsets) {
            val sv = ls.levelUpGen == 9
            val srcs = inp.raw[id]
            val lv = ls.levelUp.map { it.move }.toSet()
            for (m in ls.machine) {
                total++
                val s = srcs?.get(m).orEmpty()
                val g9 = s.any { it.startsWith("9M") || it.startsWith("9T") }
                if (g9) { gen9++; continue }
                if (m in lv) { inLevelUp++; continue }
                if (sv) olderSv++ else olderNotSv++
                perSpecies.merge(id, 1, Int::plus); perMove.merge(m, 1, Int::plus)
                val mt = s.filter { it.length > 1 && (it[1] == 'M' || it[1] == 'T') && it[0].isDigit() }
                latestGen.merge(mt.maxOfOrNull { it.takeWhile { c -> c.isDigit() }.toInt() } ?: 0, 1, Int::plus)
                kinds.merge(mt.map { it.dropWhile { c -> c.isDigit() }.take(1) }.distinct().sorted().joinToString("+").ifEmpty { "?" }, 1, Int::plus)
            }
        }
        val older = olderSv + olderNotSv
        // Level-up moves of older generations that the "latest generation only" rule drops.
        var droppedLevelUp = 0
        val droppedBySpecies = HashMap<String, Int>()
        val droppedByMove = HashMap<String, Int>()
        val rxL = Regex("^(\\d+)L\\d+$")
        for ((id, ls) in dex.learnsets) {
            val src = inp.raw[id] ?: continue
            val keep = ls.levelUp.map { it.move }.toSet() + ls.machine + ls.egg
            for ((m, ss) in src) {
                if (m !in dex.moves || m in keep) continue
                if (ss.any { x -> rxL.matchEntire(x)?.let { it.groupValues[1].toInt() < ls.levelUpGen } == true }) {
                    droppedLevelUp++; droppedBySpecies.merge(id, 1, Int::plus); droppedByMove.merge(m, 1, Int::plus)
                }
            }
        }
        return buildJsonObject {
            put("olderGenerationLevelUpDropped", buildJsonObject {
                put("note", "Species/move pairs learnable by level-up in an older generation only (absent from the selected level-up list, TM/tutor list and egg list): not legal under the policy.")
                put("pairs", droppedLevelUp); put("species", droppedBySpecies.size); put("moves", droppedByMove.size)
                put("topSpecies", JsonArray(droppedBySpecies.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(10).map { buildJsonObject { put("species", it.key); put("moves", it.value) } }))
            })
            put("note", "machine = TM/TR/tutor from ANY generation (importer behaviour). 'older only' = no Gen IX M/T source and not in the selected level-up list.")
            put("machinePairs", total)
            put("withGen9TmTrOrTutor", gen9)
            put("alsoInSelectedLevelUpList", inLevelUp)
            put("olderGenerationsOnly", older)
            put("olderGenerationsOnlyForGen9Species", olderSv)
            put("olderGenerationsOnlyForSpeciesNotInGen9", olderNotSv)
            put("olderOnlyByLatestGeneration", buildJsonObject { for ((g, n) in latestGen) put(g.toString(), n) })
            put("olderOnlyBySourceKind", buildJsonObject { for ((k, n) in kinds) put(k, n) })
            put("distinctMovesOlderOnly", perMove.size)
            put("speciesWithOlderOnlyMachineMoves", perSpecies.size)
            put("topSpeciesByOlderOnlyMoves", JsonArray(perSpecies.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(15).map { buildJsonObject { put("species", it.key); put("moves", it.value) } }))
            put("topMovesByOlderOnlySpecies", JsonArray(perMove.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(15).map { buildJsonObject { put("move", it.key); put("species", it.value) } }))
        }
    }

    private fun nonstandard(): JsonObject {
        val byMove = java.util.TreeMap<String, Triple<MutableList<String>, MutableList<String>, MutableList<String>>>()
        for ((id, ls) in dex.learnsets.entries.sortedBy { it.key }) {
            fun add(m: String, idx: Int) {
                val flag = dex.moveOrNull(m)?.isNonstandard ?: return
                if (flag !in nonLegalStatuses) return
                val t = byMove.getOrPut(m) { Triple(ArrayList(), ArrayList(), ArrayList()) }
                when (idx) { 0 -> t.first; 1 -> t.second; else -> t.third }.add(id)
            }
            ls.levelUp.map { it.move }.distinct().forEach { add(it, 0) }
            ls.machine.forEach { add(it, 1) }
            ls.egg.forEach { add(it, 2) }
        }
        val statusCounts = java.util.TreeMap<String, IntArray>()
        for ((m, t) in byMove) {
            val c = statusCounts.getOrPut(dex.move(m).isNonstandard!!) { IntArray(3) }
            c[0] += t.first.size; c[1] += t.second.size; c[2] += t.third.size
        }
        val movesByStatus = dex.moves.values.filter { it.isNonstandard != null }.groupBy { it.isNonstandard!! }
        val speciesStatuses = countBy(allSpecies.mapNotNull { (it.isNonstandard ?: inp.formatsStatus[it.id]) })
        val worldUses = ArrayList<JsonObject>()
        for (wm in inp.worldMons) for (m in wm.moves) {
            val flag = dex.moveOrNull(m)?.isNonstandard ?: continue
            worldUses += buildJsonObject { put("source", wm.source); put("species", wm.species); put("move", m); put("isNonstandard", flag) }
        }
        for (t in inp.tms) { val flag = dex.moveOrNull(t.move)?.isNonstandard ?: continue
            worldUses += buildJsonObject { put("source", t.source); put("species", "-"); put("move", "tm:${t.move}"); put("isNonstandard", flag) } }
        for (t in inp.tutors) for (m in t.moves) { val flag = dex.moveOrNull(m)?.isNonstandard ?: continue
            worldUses += buildJsonObject { put("source", t.source); put("species", "-"); put("move", "tutor:$m"); put("isNonstandard", flag) } }
        return buildJsonObject {
            put("note", "Each entry lists the species whose level-up (levelUp), TM/tutor (machine) or egg list depends on a move Showdown flags isNonstandard. Species lists are space separated ids.")
            put("movesByStatus", buildJsonObject { for ((k, v) in movesByStatus.toSortedMap()) put(k, v.size) })
            put("speciesPairsByStatus", buildJsonObject { for ((k, c) in statusCounts) put(k, buildJsonObject { put("levelUp", c[0]); put("machine", c[1]); put("egg", c[2]); put("total", c.sum()) }) })
            put("speciesByNonstandardStatus", speciesStatuses)
            put("moves", JsonArray(byMove.map { (m, t) ->
                buildJsonObject {
                    put("move", m); put("isNonstandard", dex.move(m).isNonstandard!!)
                    put("levelUpSpecies", t.first.size); put("machineSpecies", t.second.size); put("eggSpecies", t.third.size)
                    put("levelUp", t.first.joinToString(" ")); put("machine", t.second.joinToString(" ")); put("egg", t.third.joinToString(" "))
                }
            }))
            put("worldAssignmentsUsingNonstandardMoves", JsonArray(worldUses))
        }
    }

    private fun familyRoot(sp: SpeciesData): String {
        val base = dex.speciesOrNull(sp.baseSpecies) ?: sp
        return chain(base).last().id
    }

    private fun signatureMoves(): JsonObject {
        val learners = HashMap<String, MutableSet<String>>()   // move -> learner species ids
        val viaKinds = HashMap<String, MutableSet<String>>()
        for (sp in allSpecies) {
            val st = setsOf(sp.id) ?: continue
            for (m in st.level.keys) { learners.getOrPut(m) { HashSet() }.add(sp.id); viaKinds.getOrPut(m) { HashSet() }.add("levelUp") }
            for (m in st.machine) { learners.getOrPut(m) { HashSet() }.add(sp.id); viaKinds.getOrPut(m) { HashSet() }.add("machine") }
            for (m in st.egg) { learners.getOrPut(m) { HashSet() }.add(sp.id); viaKinds.getOrPut(m) { HashSet() }.add("egg") }
        }
        learners.keys.retainAll { dex.moveOrNull(it) != null }
        val families = learners.mapValues { (_, ids) -> ids.mapNotNull { dex.speciesOrNull(it) }.map { familyRoot(it) }.toSortedSet() }
        val signatures = ArrayList<JsonObject>()
        var eggOnly = 0
        for ((m, fam) in families.entries.sortedBy { it.key }) {
            if (fam.size != 1) continue
            val kinds = viaKinds.getValue(m).sorted()
            val onlyEgg = kinds == listOf("egg")
            if (onlyEgg) { eggOnly++; warn("signature-egg-only", m, "$m is learnable only as an egg move by the ${fam.first()} family (breeding is not implemented, so the move is unobtainable)") }
            signatures += buildJsonObject {
                put("move", m); put("family", fam.first()); put("via", strArray(kinds))
                put("learners", learners.getValue(m).sorted().joinToString(" "))
                put("isNonstandard", dex.moveOrNull(m)?.isNonstandard?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
        // Moves a form requires (Mega Rayquaza -> Dragon Ascent, Meloetta/Genesect style): the base species must have it.
        val required = ArrayList<JsonObject>()
        for (sp in allSpecies) {
            val rm = sp.requiredMove ?: continue
            val base = dex.speciesOrNull(sp.baseSpecies) ?: sp
            val ok = setsOf(base.id)?.let { rm in it.level || rm in it.machine || rm in it.egg } == true
            required += buildJsonObject { put("species", sp.id); put("requiredMove", rm); put("baseSpeciesHasIt", ok) }
            if (!ok) { error("required-move-unlearnable", sp.id, "${sp.id} requires $rm but ${base.id} cannot learn it"); conflicts += Conflict(base.id, rm, "required-move-unlearnable", "requiredMove=$rm", "the form can only be reached through a move its base species cannot learn", "data-src/showdown/pokedex.json requiredMove") }
        }
        // Moves nobody learns in-game.
        val unlearnable = dex.moves.values.filter { it.id !in learners }.sortedBy { it.id }
        val eventOnly = HashMap<String, Int>()
        for ((_, src) in inp.raw) for ((m, s) in src) if (m in dex.moves && s.all { Regex("^\\d+[SV]").containsMatchIn(it) }) eventOnly.merge(m, 1, Int::plus)
        val rOnly = HashSet<String>()
        for ((_, src) in inp.raw) for ((m, s) in src) if (m in dex.moves && s.any { Regex("^\\d+R").containsMatchIn(it) }) rOnly += m
        val byReason = java.util.TreeMap<String, MutableList<String>>()
        for (mv in unlearnable) {
            val reason = when {
                mv.isZ != null -> "z-move"
                mv.isMax != null -> "max-move"
                mv.id in rOnly -> "form-signature-r-source-not-imported"
                mv.id in eventOnly -> "event-or-lgpe-source-only"
                mv.isNonstandard != null -> "nonstandard:${mv.isNonstandard}"
                else -> "no-learner"
            }
            byReason.getOrPut(reason) { ArrayList() }.add(mv.id)
        }
        for (id in byReason["no-learner"].orEmpty()) warn("move-without-learner", id, "$id is a standard move that no species learns in-game")
        return buildJsonObject {
            put("learnableMoves", learners.size)
            put("signatureMoveCount", signatures.size)
            put("signatureMovesEggOnly", eggOnly)
            put("signatureMoves", JsonArray(signatures))
            put("formsRequiringAMove", JsonArray(required))
            put("unlearnableMoves", buildJsonObject {
                put("total", unlearnable.size)
                for ((k, v) in byReason) put(k, buildJsonObject { put("count", v.size); put("moves", v.joinToString(" ")) })
            })
        }
    }

    // ------------------------------------------------------------------ world legality

    private fun world(): JsonObject {
        val byKind = java.util.TreeMap<String, Int>()
        val routeCounts = java.util.TreeMap<String, Int>()
        val illegal = ArrayList<JsonObject>()
        var checkedMoves = 0
        for (wm in inp.worldMons) {
            byKind.merge(wm.kind, 1, Int::plus)
            val sp = dex.speciesOrNull(wm.species)
            if (sp == null) { error("world-unknown-species", wm.source, "${wm.owner}: unknown species '${wm.species}'"); continue }
            if (wm.level != -1 && wm.level !in 1..100) error("world-level-out-of-range", wm.source, "${wm.owner}: ${wm.species} level ${wm.level}")
            if (wm.moves.size > 4) error("world-too-many-moves", wm.source, "${wm.owner}: ${wm.species} has ${wm.moves.size} moves (max 4)")
            if (wm.moves.toSet().size != wm.moves.size) error("world-duplicate-move", wm.source, "${wm.owner}: ${wm.species} repeats a move in ${wm.moves}")
            val level = if (wm.level == -1) 100 else wm.level
            for (m in wm.moves) {
                checkedMoves++
                if (dex.moveOrNull(m) == null) {
                    error("world-unknown-move", wm.source, "${wm.owner}: ${wm.species} knows unknown move '$m'")
                    conflicts += Conflict(wm.species, m, "unknown-move", "moves=${wm.moves}", "move id does not exist in moves.json", wm.source)
                    continue
                }
                val v = verdict(wm.species, m, level)
                routeCounts.merge(v.route.name, 1, Int::plus)
                if (v.legal) continue
                val issue = when (v.route) {
                    Route.EGG -> "egg-only"; Route.EVENT -> "event-only"; Route.LEVEL_TOO_HIGH -> "level-too-high"
                    Route.OLDER_LEVEL_UP -> "older-gen-level-up-only"; Route.FORM_SIGNATURE -> "form-signature-not-imported"; else -> "illegal-move"
                }
                val sev = if (v.route == Route.LEVEL_TOO_HIGH || v.route == Route.NONE) "error" else "warning"
                findings += Finding(sev, "world-$issue", wm.source, "${wm.owner}: ${wm.species} L${wm.level} knows $m: ${v.detail}")
                val just = when (v.route) {
                    Route.EGG -> "legal in the games but only via breeding, which is out of scope; either replace the move or implement breeding"
                    Route.EVENT -> "only distributed through events/LGPE/VC in the source data and so not learnable in-game; replace the move or accept it as a scripted exception"
                    Route.LEVEL_TOO_HIGH -> "the species learns it by level-up only at a higher level and has no TM/tutor/egg route; raise the level or change the move"
                    Route.OLDER_LEVEL_UP -> "legal in an older game but the policy keeps only the most recent generation's level-up list; add the move to the species' level-up data deliberately or change the move"
                    Route.FORM_SIGNATURE -> "the form's own signature move is marked 'R' in Showdown and dropped by DataImporter.importLearnsets; import it for that form or change the move"
                    else -> "no level-up, TM/tutor, evolution or egg route exists under the policy; change the move or document a scripted exception"
                }
                conflicts += Conflict(wm.species, m, issue, "${wm.kind} ${wm.owner} L${wm.level} moves=${wm.moves.joinToString(",")}", "$just (${v.detail})", wm.source)
                illegal += buildJsonObject {
                    put("source", wm.source); put("kind", wm.kind); put("owner", wm.owner); put("species", wm.species)
                    put("level", wm.level); put("move", m); put("issue", issue); put("detail", v.detail)
                }
            }
        }
        return buildJsonObject {
            put("monsChecked", inp.worldMons.size)
            put("monsWithExplicitMoves", inp.worldMons.count { it.moves.isNotEmpty() })
            put("movesChecked", checkedMoves)
            put("byKind", buildJsonObject { for ((k, v) in byKind) put(k, v) })
            put("legalityRoutes", buildJsonObject { for ((k, v) in routeCounts) put(k, v) })
            put("illegalAssignmentCount", illegal.size)
            put("illegalAssignmentsByIssue", countBy(illegal.map { it["issue"]!!.jsonPrimitive.content }))
            put("illegalAssignments", JsonArray(illegal))
            put("obtainableSpecies", obtainable.size)
        }
    }

    /** Cross-checks the audit's learner rule against the game's own [Progression.canLearnMachine]. */
    private fun engineCrossCheck(moves: Collection<String>): JsonObject {
        val mismatches = ArrayList<String>()
        var checked = 0
        var skipped = 0
        try {
            val factory = PokemonFactory(dex)
            val progression = Progression(dex)
            for (sp in allSpecies) {
                val mon = try { factory.create(sp.id, 50, Rng(1)) } catch (e: Exception) { skipped++; continue }
                for (m in moves) {
                    checked++
                    val mine = machineLegal(sp, m)
                    val engine = progression.canLearnMachine(mon, m)
                    if (mine != engine) mismatches += "${sp.id}:$m(audit=$mine,engine=$engine)"
                }
            }
        } catch (e: Exception) {
            info("engine-cross-check-skipped", "canLearnMachine", "could not run the engine cross-check: $e")
        }
        for (m in mismatches.take(50)) error("engine-learner-mismatch", m, "audit learner rule and Progression.canLearnMachine disagree")
        return buildJsonObject { put("pairsChecked", checked); put("speciesSkipped", skipped); put("mismatches", mismatches.size); put("sample", strArray(mismatches.take(20))) }
    }

    private fun learnersOf(move: String): List<SpeciesData> = allSpecies.filter { machineLegal(it, move) }

    private fun tmAvailability(): JsonObject {
        val byMove = inp.tms.groupBy { it.move }.toSortedMap()
        val entries = ArrayList<JsonObject>()
        val none = ArrayList<String>()
        val fewObtainable = ArrayList<String>()
        for ((m, places) in byMove) {
            if (dex.moveOrNull(m) == null) {
                error("tm-unknown-move", m, "TM for unknown move '$m' at ${places.first().source}")
                continue
            }
            val all = learnersOf(m)
            val obt = all.filter { it.id in obtainable }
            val g9 = all.filter { gen9Legal(it, m) }
            val machineOnly = all.filter { s -> chain(s).any { c -> setsOf(c.id)?.machine?.contains(m) == true } }
            if (obt.isEmpty()) {
                none += m
                error("tm-unlearnable", m, "TM $m is placed in the world (${places.first().source}) but no obtainable species can learn it")
                conflicts += Conflict("-", m, "tm-unlearnable", "tm:$m at ${places.first().source}", "no obtainable species (encounters, gifts, trainers' gifts and their evolutions) can learn this move by level-up or machine", places.first().source)
            } else if (obt.size <= 3) {
                fewObtainable += m
                warn("tm-few-learners", m, "TM $m can be learned by only ${obt.size} obtainable species: ${obt.joinToString(",") { it.id }}")
            }
            if (dex.move(m).isNonstandard != null) warn("tm-nonstandard-move", m, "TM $m teaches a move Showdown flags ${dex.move(m).isNonstandard}")
            entries += buildJsonObject {
                put("move", m)
                put("placements", JsonArray(places.map { buildJsonObject { put("kind", it.kind); put("source", it.source) } }))
                put("learnersAll", all.size); put("learnersObtainable", obt.size)
                put("learnersViaTmOrTutorList", machineOnly.size)
                put("learnersGen9", g9.size)
                put("isNonstandard", dex.move(m).isNonstandard?.let { JsonPrimitive(it) } ?: JsonNull)
                if (obt.size <= 5) put("obtainableLearners", strArray(obt.map { it.id }))
            }
        }
        val machineMoves = dex.learnsets.values.flatMap { it.machine }.toSet()
        val worldTmMoves = byMove.keys
        val engine = engineCrossCheck((worldTmMoves + inp.tutors.flatMap { it.moves }).filter { dex.moveOrNull(it) != null }.toSortedSet())
        return buildJsonObject {
            put("tmItemsInWorld", inp.tms.size)
            put("distinctTmMoves", byMove.size)
            put("placementsByKind", countBy(inp.tms.map { it.kind }))
            put("machineLegalMoves", machineMoves.size)
            put("machineLegalMovesWithoutAnyTm", machineMoves.count { it !in worldTmMoves })
            put("tmsNoObtainableLearner", strArray(none))
            put("tmsWithAtMostThreeObtainableLearners", strArray(fewObtainable))
            put("engineCanLearnMachineCrossCheck", engine)
            put("tms", JsonArray(entries))
        }
    }

    private fun tutors(): JsonObject {
        val entries = ArrayList<JsonObject>()
        for (t in inp.tutors) for (m in t.moves) {
            if (dex.moveOrNull(m) == null) {
                error("tutor-unknown-move", t.source, "tutor ${t.id} offers unknown move '$m'")
                conflicts += Conflict("-", m, "unknown-move", "tutor ${t.id}", "move id does not exist in moves.json", t.source)
                continue
            }
            val all = learnersOf(m)
            val obt = all.filter { it.id in obtainable }
            val g9 = all.count { gen9Legal(it, m) }
            if (obt.isEmpty()) {
                error("tutor-unlearnable", "${t.id}:$m", "tutor ${t.id} offers $m but no obtainable species can learn it")
                conflicts += Conflict("-", m, "tutor-unlearnable", "tutor ${t.id}", "no obtainable species can learn this move by level-up or machine, so the tutor entry can never be used", t.source)
            } else if (obt.size <= 3) warn("tutor-few-learners", "${t.id}:$m", "tutor ${t.id} offers $m but only ${obt.size} obtainable species can learn it: ${obt.joinToString(",") { it.id }}")
            entries += buildJsonObject {
                put("tutor", t.id); put("move", m); put("learnersAll", all.size); put("learnersObtainable", obt.size); put("learnersGen9", g9)
            }
        }
        return buildJsonObject {
            put("tutors", inp.tutors.size)
            put("entries", entries.size)
            put("entriesWithoutObtainableLearner", entries.count { it["learnersObtainable"]!!.jsonPrimitive.int == 0 })
            put("list", JsonArray(entries))
        }
    }

    private fun conflictsJson() = JsonArray(conflicts.map {
        buildJsonObject {
            put("species", it.species); put("move", it.move); put("issue", it.issue)
            put("selectedValue", it.selectedValue); put("justification", it.justification); put("source", it.source)
        }
    })

    // ------------------------------------------------------------------ markdown

    private fun JsonElement.o() = jsonObject
    private fun JsonElement.n(vararg path: String): Int = path.fold(this) { e, k -> e.jsonObject.getValue(k) }.jsonPrimitive.int

    private fun markdown(j: JsonObject): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }
        val cov = j.getValue("coverage").o()
        line("# Learnset legality audit")
        line()
        line("Generated by `./gradlew :tools:auditLearnsets` (`learnsets` tool command). Machine-readable data: `reports/learnsets.json`. Policy: `docs/LEARNSETS.md`.")
        line("Pokémon Showdown and PokeAPI are community-maintained datasets, not official game data.")
        line()
        line("## Dataset provenance")
        line()
        (j["dataset"] as? JsonObject)?.let { d ->
            d["sources"]?.jsonArray?.forEach { s ->
                val so = s.o()
                line("- **${so["name"]?.jsonPrimitive?.content}**: ${so["source"]?.jsonPrimitive?.content} (retrieved ${so["retrieved"]?.jsonPrimitive?.content}; ${so["license"]?.jsonPrimitive?.content?.substringBefore(" (")})")
            }
            d["files"]?.jsonObject?.let { f ->
                line()
                line("| file | sha256 (data-src/SHA256SUMS) | verified |")
                line("|---|---|---|")
                for ((k, v) in f) line("| `$k` | `${v.o()["sha256"]?.jsonPrimitive?.content?.take(16)}…` | ${v.o()["verified"]?.jsonPrimitive?.content} |")
            }
        }
        line()
        line("## Summary")
        line()
        val s = j.getValue("summary").o()
        line("- Species and forms: **${s.n("species")}**; findings: **${s.n("errors")} errors**, ${s.n("warnings")} warnings, ${s.n("infos")} infos; conflicts: ${s.n("conflicts")}.")
        line("- Learnsets: own ${cov.n("own")}, inherited from base ${cov.n("inheritedFromBase")}, inherited via changesFrom ${cov.n("inheritedFromChangesFrom")}, own entry with empty level-up ${cov.n("ownWithEmptyLevelUp")}, missing ${cov.n("missing")}.")
        val ic = j.getValue("importerCrossCheck").o()
        if ("skipped" in ic) line("- Importer vs raw Showdown: skipped.")
        else line("- Importer vs raw Showdown: ${ic.n("rawEntriesForImportedSpecies")} entries recomputed, **${ic.n("discrepancyCount")} discrepancies** (${ic.n("rawEntriesNotImported")} raw entries are not roster species and are not imported).")
        val w = j.getValue("world").o()
        line("- World: ${w.n("monsChecked")} Pokémon (${w.n("movesChecked")} explicit moves) checked, **${w.n("illegalAssignmentCount")} illegal/conflicting assignments** ${w["illegalAssignmentsByIssue"]}.")
        val tm = j.getValue("tmAvailability").o()
        line("- TMs in the world: ${tm.n("tmItemsInWorld")} placements, ${tm.n("distinctTmMoves")} distinct moves; ${tm["tmsNoObtainableLearner"]!!.jsonArray.size} with no obtainable learner.")
        val mg = j.getValue("machineGenerations").o()
        line("- Machine-legal pairs: ${mg.n("machinePairs")}; with Gen IX TM/TR/tutor ${mg.n("withGen9TmTrOrTutor")}; legal only in older generations **${mg.n("olderGenerationsOnly")}** (${mg.n("olderGenerationsOnlyForGen9Species")} for Gen IX species, ${mg.n("olderGenerationsOnlyForSpeciesNotInGen9")} for species outside Gen IX).")
        line()
        line("## Findings by code")
        line()
        line("| severity | code | count |")
        line("|---|---|---|")
        val rows = j.getValue("findings").jsonArray.map { it.o() }.groupBy { it["severity"]!!.jsonPrimitive.content to it["code"]!!.jsonPrimitive.content }
        for ((k, v) in rows.entries.sortedWith(compareBy({ it.key.first != "error" }, { it.key.first != "warning" }, { -it.value.size }, { it.key.second }))) line("| ${k.first} | `${k.second}` | ${v.size} |")
        line()
        line("## Top issues")
        line()
        val errs = j.getValue("findings").jsonArray.map { it.o() }.filter { it["severity"]!!.jsonPrimitive.content == "error" }
        if (errs.isEmpty()) line("No errors.") else {
            for ((code, list) in errs.groupBy { it["code"]!!.jsonPrimitive.content }.entries.sortedByDescending { it.value.size }) {
                line("### `$code` (${list.size})")
                line()
                for (f in list.take(12)) line("- `${f["subject"]!!.jsonPrimitive.content}`: ${f["message"]!!.jsonPrimitive.content}")
                if (list.size > 12) line("- … ${list.size - 12} more in `reports/learnsets.json`")
                line()
            }
        }
        line("## Learnset coverage by form kind")
        line()
        line("| form kind | total | own | inherited | missing |")
        line("|---|---|---|---|---|")
        for ((k, v) in cov.getValue("byFormKind").jsonObject) { val c = v.o(); line("| $k | ${c.n("total")} | ${c.n("own")} | ${c.n("inherited")} | ${c.n("missing")} |") }
        line()
        line("Forms inheriting a learnset although their types differ from the base species: ${cov["inheritedFormsWithDifferentTypes"]!!.jsonArray.joinToString(" ") { it.jsonPrimitive.content }.ifEmpty { "none" }}")
        line()
        line("## Level-up generation")
        line()
        val lg = j.getValue("levelUpGenerations").o()
        line("Selected level-up generation per learnset: ${lg["levelUpGenDistribution"]}. Species without a Showdown isNonstandard flag but with an older list: ${lg["notFlaggedButOlderThanGen9"]!!.jsonArray.size}.")
        line()
        line("## Machine / tutor generations")
        line()
        line("| | pairs |")
        line("|---|---|")
        line("| machine-legal pairs (any generation) | ${mg.n("machinePairs")} |")
        line("| with a Gen IX TM/TR/tutor source | ${mg.n("withGen9TmTrOrTutor")} |")
        line("| also in the selected level-up list | ${mg.n("alsoInSelectedLevelUpList")} |")
        line("| **older generations only** | ${mg.n("olderGenerationsOnly")} |")
        line("| of which species that are in Gen IX | ${mg.n("olderGenerationsOnlyForGen9Species")} |")
        line("| of which species outside Gen IX | ${mg.n("olderGenerationsOnlyForSpeciesNotInGen9")} |")
        line()
        line("Older-only pairs by latest generation: ${mg["olderOnlyByLatestGeneration"]}; by source kind (M=TM/HM, T=tutor): ${mg["olderOnlyBySourceKind"]}.")
        line()
        line("## Moves flagged non-standard by Showdown")
        line()
        val ns = j.getValue("nonstandardDependencies").o()
        line("Moves by status: ${ns["movesByStatus"]}; species/move pairs by status: ${ns["speciesPairsByStatus"]}. Full per-move species lists are in `reports/learnsets.json` (`nonstandardDependencies.moves`).")
        line("World assignments (trainer/gift moves, TMs, tutor entries) that use such moves: ${ns["worldAssignmentsUsingNonstandardMoves"]!!.jsonArray.size}.")
        line()
        line("## Signature moves")
        line()
        val sg = j.getValue("signatureMoves").o()
        line("${sg.n("signatureMoveCount")} moves are learnable by exactly one evolutionary family; ${sg.n("signatureMovesEggOnly")} of them only as egg moves. Moves nobody learns: ${sg["unlearnableMoves"]!!.jsonObject["total"]} (z-moves, max moves, event-only and nonstandard moves are broken out in the JSON).")
        line()
        line("## World legality")
        line()
        line("Pokémon checked by kind: ${w["byKind"]}. Legality routes: ${w["legalityRoutes"]}.")
        line()
        val ill = w["illegalAssignments"]!!.jsonArray.map { it.o() }
        if (ill.isEmpty()) line("No illegal assignments.") else {
            line("| issue | species | move | level | source |")
            line("|---|---|---|---|---|")
            for (x in ill.take(40)) line("| ${x["issue"]!!.jsonPrimitive.content} | ${x["species"]!!.jsonPrimitive.content} | ${x["move"]!!.jsonPrimitive.content} | ${x["level"]!!.jsonPrimitive.content} | `${x["source"]!!.jsonPrimitive.content}` |")
            if (ill.size > 40) line("| … | | | | ${ill.size - 40} more in `reports/learnsets.json` |")
        }
        line()
        line("## TM availability")
        line()
        line("Obtainable species: ${w.n("obtainableSpecies")}. TMs without an obtainable learner: ${tm["tmsNoObtainableLearner"]!!.jsonArray.joinToString(" ") { it.jsonPrimitive.content }.ifEmpty { "none" }}. With at most three: ${tm["tmsWithAtMostThreeObtainableLearners"]!!.jsonArray.joinToString(" ") { it.jsonPrimitive.content }.ifEmpty { "none" }}.")
        line("Machine-legal moves with no TM in the world: ${tm.n("machineLegalMovesWithoutAnyTm")} of ${tm.n("machineLegalMoves")}. Audit vs `Progression.canLearnMachine`: ${tm["engineCanLearnMachineCrossCheck"]!!.o()["mismatches"]} mismatches over ${tm["engineCanLearnMachineCrossCheck"]!!.o()["pairsChecked"]} pairs.")
        line()
        line("## Tutors")
        line()
        val tu = j.getValue("tutors").o()
        line("${tu.n("tutors")} tutors, ${tu.n("entries")} entries, ${tu.n("entriesWithoutObtainableLearner")} without an obtainable learner.")
        line()
        line("## Conflicts")
        line()
        line("${s.n("conflicts")} entries ${s["conflictsByIssue"]}; each has {species, move, issue, selectedValue, justification, source} in `reports/learnsets.json` (`conflicts`).")
        return sb.toString()
    }
}

// ====================================================================== file loading + CLI wrapper

class LearnsetAudit(private val root: File, private val write: Boolean = true) {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(): Boolean {
        val dex = Dex.load(DirectoryContentSource(File(root, "assets")))
        val loaderFindings = ArrayList<Finding>()
        val input = buildInput(dex, loaderFindings)
        val result = LearnsetAuditCore(input).run()
        if (write) {
            writeJson(File(root, "reports/learnsets.json"), result.json)
            File(root, "reports/learnsets.md").apply { parentFile.mkdirs() }.writeText(result.markdown)
        }
        val s = result.json.getValue("summary").jsonObject
        println("Learnset audit: ${s["species"]} species/forms, ${s["errors"]} errors, ${s["warnings"]} warnings, ${s["conflicts"]} conflicts" + if (write) " -> reports/learnsets.{json,md}" else "")
        for (f in result.findings.filter { it.severity == "error" }.groupBy { it.code }.entries.sortedByDescending { it.value.size }.take(12))
            println("  error ${f.key}: ${f.value.size} (e.g. ${f.value.first().subject}: ${f.value.first().message})")
        return result.errors == 0
    }

    private fun readRaw(): Map<String, Map<String, List<String>>> {
        val obj = Json.parseToJsonElement(File(root, "data-src/showdown/learnsets.json").readText()).jsonObject
        val out = LinkedHashMap<String, Map<String, List<String>>>()
        for ((id, el) in obj) {
            val ls = el.jsonObject["learnset"]?.jsonObject ?: continue
            out[id] = ls.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
        }
        return out
    }

    private fun readFormatsStatus(): Map<String, String> {
        val f = File(root, "data-src/showdown/formats-data.js")
        if (!f.exists()) return emptyMap()
        val out = HashMap<String, String>()
        for (m in Regex("(\\w+):\\{([^{}]*)\\}").findAll(f.readText())) {
            Regex("isNonstandard:\"(\\w+)\"").find(m.groupValues[2])?.let { out[m.groupValues[1]] = it.groupValues[1] }
        }
        return out
    }

    private fun provenance(loaderFindings: MutableList<Finding>): JsonElement {
        val manifest = File(root, "assets/data/manifest.json").takeIf { it.exists() }?.let { lenient.parseToJsonElement(it.readText()).jsonObject }
        val sums = File(root, "data-src/SHA256SUMS").takeIf { it.exists() }?.let { readSha256Sums(it) } ?: emptyMap()
        val relevant = listOf("showdown/learnsets.json", "showdown/formats-data.js", "showdown/pokedex.json", "showdown/moves.json")
        return buildJsonObject {
            put("note", "Pokémon Showdown and PokeAPI are community-maintained datasets; neither is official game data.")
            put("sources", manifest?.get("provenance")?.jsonArray?.let { arr ->
                JsonArray(arr.map { p -> buildJsonObject {
                    for (k in listOf("name", "source", "license", "retrieved")) p.jsonObject[k]?.let { put(k, it) }
                } })
            } ?: JsonArray(emptyList()))
            put("files", buildJsonObject {
                for (rel in relevant) {
                    val expected = sums[rel]
                    val file = File(root, "data-src/$rel")
                    val actual = if (file.exists()) sha256(file.readBytes()) else null
                    val verified = expected != null && expected == actual
                    if (!verified) loaderFindings += Finding("error", "source-hash-mismatch", rel, "data-src/$rel does not match data-src/SHA256SUMS (expected $expected, actual $actual)")
                    put(rel, buildJsonObject { put("sha256", expected ?: "missing"); put("verified", verified) })
                }
            })
        }
    }

    // ---- world sources

    private fun parseOptions(tokens: List<String>) = tokens.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    internal fun buildInput(dex: Dex, loaderFindings: MutableList<Finding>): AuditInput {
        val mons = ArrayList<WorldMon>()
        val tms = ArrayList<TmPlacement>()
        val roots = LinkedHashSet<String>()

        // Trainers: data from the compiled trainers.json, locations from the world/trainers sources.
        val locations = HashMap<String, String>()
        File(root, "world/trainers").listFiles { f -> f.extension == "txt" }?.sortedBy { it.name }?.forEach { f ->
            var current: String? = null
            var idx = 0
            f.readLines().forEachIndexed { i, raw ->
                val t = raw.trim().split(Regex("\\s+"))
                if (t[0] == "trainer" && t.size > 1) { current = t[1]; idx = 0 }
                else if (t[0] == "mon" && current != null) { locations["$current#$idx"] = "world/trainers/${f.name}:${i + 1}"; idx++ }
            }
        }
        File(root, "assets/world/trainers.json").takeIf { it.exists() }?.let { file ->
            for (tr in Json.parseToJsonElement(file.readText()).jsonArray) {
                val o = tr.jsonObject
                val id = o["id"]!!.jsonPrimitive.content
                o["team"]?.jsonArray?.forEachIndexed { i, m ->
                    val mo = m.jsonObject
                    mons += WorldMon("trainer", id, mo["species"]!!.jsonPrimitive.content, mo["level"]?.jsonPrimitive?.int ?: 1,
                        mo["moves"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), locations["$id#$i"] ?: "assets/world/trainers.json:$id#${i + 1}")
                }
            }
        }

        // Scripts: givemon / wildbattle / giveegg / trade, and TM rewards.
        File(root, "world/scripts").listFiles { f -> f.extension == "pks" }?.sortedBy { it.name }?.forEach { f ->
            var script = "?"
            f.readLines().forEachIndexed { i, raw ->
                val t = raw.substringBefore('#').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (t.isEmpty()) return@forEachIndexed
                val where = "world/scripts/${f.name}:${i + 1}"
                val opts = parseOptions(t)
                fun moves() = opts["moves"]?.split(',')?.map { toId(it) }?.filter { it.isNotEmpty() }.orEmpty()
                when (t[0]) {
                    "script" -> script = t.getOrElse(1) { "?" }
                    "givemon", "wildbattle" -> if (t.size >= 3) mons += WorldMon(t[0], script, toId(t[1]), t[2].toIntOrNull() ?: -1, moves(), "$where ($script)")
                    "giveegg" -> if (t.size >= 2) mons += WorldMon("giveegg", script, toId(t[1]), 1, moves(), "$where ($script)")
                    "trade" -> if (t.size >= 4) mons += WorldMon("trade", script, toId(t[2]), t[3].toIntOrNull() ?: -1, moves(), "$where ($script)")
                    "tm" -> if (t.size >= 2) tms += TmPlacement(toId(t[1]), "script", "$where ($script)")
                    "giveitem" -> if (t.size >= 2 && t[1].lowercase().startsWith("tm:")) tms += TmPlacement(toId(t[1].substringAfter(':')), "script", "$where ($script)")
                }
            }
        }

        // Scheduled events and encounters.
        File(root, "assets/events/events.json").takeIf { it.exists() }?.let { file ->
            for (e in Json.parseToJsonElement(file.readText()).jsonArray) {
                val o = e.jsonObject
                val sp = o["species"]?.jsonPrimitive?.content ?: continue
                mons += WorldMon("event", o["id"]!!.jsonPrimitive.content, toId(sp), o["level"]?.jsonPrimitive?.int ?: -1,
                    o["moves"]?.jsonArray?.map { toId(it.jsonPrimitive.content) }.orEmpty(), "assets/events/events.json:${o["id"]!!.jsonPrimitive.content}")
            }
        }
        File(root, "assets/world/encounters.json").takeIf { it.exists() }?.let { file ->
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> { e["species"]?.let { s -> (s as? JsonPrimitive)?.contentOrNull?.let { roots += toId(it) } }; e.values.forEach(::walk) }
                    is JsonArray -> e.forEach(::walk)
                    else -> {}
                }
            }
            walk(Json.parseToJsonElement(file.readText()))
        }
        for (m in mons) if (m.kind != "trainer") roots += m.species

        // Map pickups.
        File(root, "assets/maps").listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            o["items"]?.jsonArray?.forEach { it ->
                val io = it.jsonObject
                val item = io["item"]?.jsonPrimitive?.content ?: return@forEach
                if (item.lowercase().startsWith("tm:")) tms += TmPlacement(toId(item.substringAfter(':')), "map", "assets/maps/${f.name}:${io["id"]?.jsonPrimitive?.content}")
            }
        }

        // Shops. The compiler stores TMs as "tm<move>" (the "tm:" prefix is lost), which the game does not treat as a TM.
        File(root, "assets/world/shops.json").takeIf { it.exists() }?.let { file ->
            for (s in Json.parseToJsonElement(file.readText()).jsonArray) {
                val o = s.jsonObject
                val id = o["id"]!!.jsonPrimitive.content
                for (it in o["items"]?.jsonArray.orEmpty()) {
                    val item = it.jsonPrimitive.content
                    when {
                        item.lowercase().startsWith("tm:") -> tms += TmPlacement(toId(item.substringAfter(':')), "shop", "assets/world/shops.json:$id")
                        item.startsWith("tm") && dex.itemOrNull(item) == null && dex.moveOrNull(item.removePrefix("tm")) != null -> {
                            tms += TmPlacement(item.removePrefix("tm"), "shop", "assets/world/shops.json:$id")
                            loaderFindings += Finding("error", "shop-tm-prefix-lost", "assets/world/shops.json:$id",
                                "shop '$id' lists '$item' instead of 'tm:${item.removePrefix("tm")}': MapCompilerTool.compileShops runs toId() over the item, dropping the ':'; the game only treats bag keys starting with 'tm:' as TMs (FieldItems.useOn, ShopScreen.price, BagScreen)")
                        }
                    }
                }
            }
        }

        // Tutors.
        val tutors = ArrayList<TutorDef>()
        File(root, "assets/world/tutors.json").takeIf { it.exists() }?.let { file ->
            for ((id, arr) in Json.parseToJsonElement(file.readText()).jsonObject) tutors += TutorDef(id, arr.jsonArray.map { it.jsonPrimitive.content }, "assets/world/tutors.json:$id")
        }

        return AuditInput(dex, readRaw(), readFormatsStatus(), mons, tutors, tms, roots, provenance(loaderFindings), loaderFindings = loaderFindings)
    }
}
