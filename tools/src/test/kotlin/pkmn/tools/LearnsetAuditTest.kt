package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the audit logic with a tiny synthetic dex so every rule has a deterministic expectation. */
class LearnsetAuditTest {
    private var n = 0

    private fun sp(id: String, prevo: String? = null, base: String = id, kind: FormKind = FormKind.BASE, types: List<String> = listOf("Normal"), changesFrom: String? = null) =
        SpeciesData(
            id = id, num = ++n, name = id, baseSpecies = base, formKind = kind, types = types,
            baseStats = BaseStats(50, 50, 50, 50, 50, 50), abilities = mapOf("0" to "pressure"), maleRatio = 0.5, heightm = 1.0, weightkg = 1.0,
            prevo = prevo, changesFrom = changesFrom,
        )

    private fun mv(id: String, nonstandard: String? = null) = MoveData(id = id, num = ++n, name = id, type = "Normal", category = "Physical", basePower = 40, accuracy = 100, pp = 10, target = "normal", isNonstandard = nonstandard)

    private val moves = listOf("tackle", "ember", "surf", "growl", "crunch", "wish", "splash").map { mv(it) } + mv("hiddenpower", "Past")

    private fun ls(species: String, levelUp: List<Pair<Int, String>> = emptyList(), gen: Int = 9, machine: List<String> = emptyList(), egg: List<String> = emptyList()) =
        LearnsetData(species, levelUp.map { LevelMove(it.first, it.second) }, if (levelUp.isEmpty()) 0 else gen, machine, egg)

    private fun dex(species: List<SpeciesData>, learnsets: List<LearnsetData>) =
        Dex(species, moves, emptyList(), emptyList(), TypeChart(TypeChartData(listOf("Normal"), emptyMap())), { learnsets })

    private fun input(
        dex: Dex, raw: Map<String, Map<String, List<String>>> = emptyMap(), mons: List<WorldMon> = emptyList(),
        tutors: List<TutorDef> = emptyList(), tms: List<TmPlacement> = emptyList(), roots: Set<String> = emptySet(), crossCheck: Boolean = false,
    ) = AuditInput(dex, raw, emptyMap(), mons, tutors, tms, roots, crossCheckImporter = crossCheck)

    private fun baseDex(): Dex {
        val species = listOf(sp("small"), sp("big", prevo = "small"), sp("bigalola", base = "big", kind = FormKind.REGIONAL, types = listOf("Fire")), sp("bigmega", base = "big", kind = FormKind.MEGA))
        val learnsets = listOf(
            ls("small", listOf(1 to "tackle", 5 to "growl"), machine = listOf("surf"), egg = listOf("crunch")),
            ls("big", listOf(0 to "ember", 1 to "tackle", 30 to "wish")),
            // bigalola has its own entry; bigmega has none and must inherit from big.
            ls("bigalola", listOf(1 to "tackle", 10 to "ember")),
        )
        return dex(species, learnsets)
    }

    private fun run(i: AuditInput) = LearnsetAuditCore(i).run()

    @Test
    fun `illegal trainer move is reported with its source`() {
        val mons = listOf(
            WorldMon("trainer", "t1", "big", 20, listOf("tackle", "ember", "surf", "splash"), "world/trainers/x.txt:7"),
            WorldMon("trainer", "t2", "big", 10, listOf("wish"), "world/trainers/x.txt:9"),
        )
        val r = run(input(baseDex(), mons = mons))
        val splash = r.conflicts.single { it.move == "splash" }
        assertEquals("illegal-move", splash.issue)
        assertEquals("world/trainers/x.txt:7", splash.source)
        assertEquals("big", splash.species)
        assertTrue(splash.justification.isNotBlank() && splash.selectedValue.contains("splash"))
        // tackle (level-up), ember (level 0 evolution move) and surf (a pre-evolution's machine move) are legal.
        assertTrue(r.conflicts.none { it.move in setOf("tackle", "ember", "surf") && it.species == "big" })
        // wish is learned at level 30, above the level of t2's Pokémon.
        assertEquals("level-too-high", r.conflicts.single { it.move == "wish" }.issue)
        assertTrue(r.findings.any { it.severity == "error" && it.code == "world-illegal-move" })
    }

    @Test
    fun `duplicate and invalid move ids are detected`() {
        val dupes = ls("small", listOf(1 to "tackle", 1 to "tackle", 5 to "notamove"), machine = listOf("surf", "surf"))
        val bad = ls("big", listOf(5 to "tackle", 200 to "ember", 3 to "growl"))
        val r = run(input(dex(listOf(sp("small"), sp("big", prevo = "small")), listOf(dupes, bad))))
        val codes = r.findings.map { it.code }
        assertTrue("duplicate-level-move" in codes, codes.toString())
        assertTrue("duplicate-move-id" in codes)
        assertTrue("invalid-move-id" in codes)
        assertTrue("level-out-of-range" in codes)
        assertTrue("level-up-unsorted" in codes)
        assertTrue(r.errors >= 5)
    }

    @Test
    fun `forms inherit from base species or changesFrom and missing learnsets are flagged`() {
        val r = run(input(baseDex()))
        val cov = r.json["coverage"]!!.jsonObject
        fun count(k: String) = cov[k]!!.jsonPrimitive.int
        assertEquals(3, count("own"))          // small, big, bigalola
        assertEquals(1, count("inheritedFromBase")) // bigmega
        assertEquals(0, count("missing"))
        val inherited = cov["inheritingForms"]!!.jsonArray.map { it.jsonObject["species"]!!.jsonPrimitive.content }
        assertEquals(listOf("bigmega"), inherited)
        // A species without any learnset is an error.
        val missing = run(input(dex(listOf(sp("orphan")), emptyList())))
        assertTrue(missing.findings.any { it.code == "learnset-missing" && it.subject == "orphan" })
        // changesFrom is preferred over the base species.
        val d = dex(listOf(sp("a"), sp("b", base = "a", kind = FormKind.ALTERNATE, changesFrom = "c"), sp("c", base = "c")),
            listOf(ls("a", listOf(1 to "tackle")), ls("c", listOf(1 to "ember"))))
        assertEquals("c", d.learnset("b")!!.species)
        val viaChanges = run(input(d, mons = listOf(WorldMon("trainer", "t", "b", 5, listOf("ember"), "x:1"))))
        assertTrue(viaChanges.conflicts.isEmpty(), viaChanges.conflicts.toString())
    }

    @Test
    fun `egg-only and event-only moves are conflicts`() {
        val raw = mapOf("small" to mapOf("splash" to listOf("7S0", "7V")), "big" to emptyMap())
        val mons = listOf(
            WorldMon("trainer", "t1", "big", 50, listOf("crunch"), "world/trainers/a.txt:1"),   // egg move of the pre-evolution
            WorldMon("givemon", "gift", "small", 5, listOf("splash"), "world/scripts/a.pks:3"),    // event-only in the raw data
        )
        val r = run(input(baseDex(), raw = raw, mons = mons))
        assertEquals("egg-only", r.conflicts.single { it.move == "crunch" }.issue)
        assertEquals("event-only", r.conflicts.single { it.move == "splash" }.issue)
        assertTrue(r.errors == 0, "egg/event conflicts are warnings, not errors: ${r.findings}")
        assertTrue(r.warnings >= 2)
    }

    @Test
    fun `older generation level-up is a conflict and form signature R sources are reported`() {
        val raw = mapOf(
            "big" to mapOf("splash" to listOf("9S0", "7L12"), "surf" to listOf("9R")),
        )
        val mons = listOf(
            WorldMon("trainer", "t", "big", 50, listOf("splash", "surf"), "x:1"),
        )
        val r = run(input(baseDex(), raw = raw, mons = mons))
        assertEquals("older-gen-level-up-only", r.conflicts.single { it.move == "splash" }.issue)
        // big's pre-evolution small can learn surf by machine, so it is legal.
        assertTrue(r.conflicts.none { it.move == "surf" })
        val forms = run(input(baseDex(), raw = mapOf("big" to mapOf("splash" to listOf("9R"))), mons = listOf(WorldMon("trainer", "t", "big", 50, listOf("splash"), "x:2"))))
        assertEquals("form-signature-not-imported", forms.conflicts.single { it.move == "splash" }.issue)
    }

    @Test
    fun `importer cross-check flags assets that differ from the raw Showdown data`() {
        val raw = mapOf("small" to mapOf("tackle" to listOf("9L1", "8L1"), "growl" to listOf("9L5", "7L3"), "surf" to listOf("9M"), "crunch" to listOf("9E", "8S0")))
        val good = ls("small", listOf(1 to "tackle", 5 to "growl"), machine = listOf("surf"), egg = listOf("crunch"))
        val ok = run(input(dex(listOf(sp("small")), listOf(good)), raw = raw, crossCheck = true))
        assertTrue(ok.findings.none { it.code == "importer-discrepancy" }, ok.findings.toString())
        val tampered = good.copy(levelUp = listOf(LevelMove(1, "tackle"), LevelMove(7, "growl")), machine = emptyList())
        val bad = run(input(dex(listOf(sp("small")), listOf(tampered)), raw = raw, crossCheck = true))
        val fields = bad.conflicts.filter { it.issue.startsWith("importer-discrepancy") }.map { it.issue }
        assertTrue("importer-discrepancy:levelUp" in fields && "importer-discrepancy:machine" in fields, fields.toString())
        // The independent recomputation keeps only the most recent generation with level data.
        val re = LearnsetAuditCore(input(dex(listOf(sp("small")), listOf(good)), raw = raw)).recompute(raw.getValue("small"), moves.map { it.id }.toSet())
        assertEquals(listOf(LevelMove(1, "tackle"), LevelMove(5, "growl")), re.levelUp)
        assertEquals(9, re.levelUpGen)
        assertEquals(listOf("surf"), re.machine)
        assertEquals(listOf("crunch"), re.egg)
    }

    @Test
    fun `TMs and tutors nobody can learn are flagged`() {
        val d = baseDex()
        val tms = listOf(TmPlacement("surf", "map", "assets/maps/m.json:tm1"), TmPlacement("splash", "script", "world/scripts/s.pks:9"))
        val tutors = listOf(TutorDef("tutor1", listOf("crunch", "splash", "surf"), "assets/world/tutors.json:tutor1"))
        val r = run(input(d, tms = tms, tutors = tutors, roots = setOf("small")))
        assertTrue(r.findings.any { it.code == "tm-unlearnable" && it.subject == "splash" })
        assertTrue(r.conflicts.any { it.issue == "tm-unlearnable" && it.move == "splash" })
        assertTrue(r.findings.none { it.code == "tm-unlearnable" && it.subject == "surf" })
        // surf is legal for small and, through the evolution closure, for big; crunch is only an egg move so no tutor learner.
        assertTrue(r.findings.any { it.code == "tutor-unlearnable" && it.subject == "tutor1:crunch" })
        assertTrue(r.findings.any { it.code == "tutor-unlearnable" && it.subject == "tutor1:splash" })
        assertTrue(r.findings.none { it.code == "tutor-unlearnable" && it.subject == "tutor1:surf" })
        // The audit's learner rule agrees with the game's Progression.canLearnMachine.
        assertTrue(r.findings.none { it.code == "engine-learner-mismatch" })
    }

    @Test
    fun `default wild movesets are legal and at most four moves`() {
        val species = listOf(sp("many"), sp("none"))
        val many = ls("many", listOf(1 to "tackle", 2 to "growl", 3 to "ember", 4 to "surf", 5 to "wish", 6 to "crunch"))
        val r = run(input(dex(species, listOf(many, ls("none")))))
        assertTrue(r.findings.none { it.code == "default-moveset-too-big" || it.code == "default-moveset-illegal" })
        // A species whose learnset has no level-up moves falls back to Tackle, which it cannot legally learn: flagged.
        assertTrue(r.findings.any { it.code == "default-moveset-tackle-fallback" && it.subject == "none" })
        assertTrue(r.findings.any { it.code == "empty-level-up" && it.subject == "none" })
    }

    @Test
    fun `non-standard moves are reported with their species`() {
        val d = dex(listOf(sp("small")), listOf(ls("small", listOf(1 to "hiddenpower"))))
        val r = run(input(d))
        val ns = r.json["nonstandardDependencies"].toString()
        assertTrue("hiddenpower" in ns && "Past" in ns && "small" in ns, ns)
    }

    @Test
    fun `real data audit runs and the importer output matches the raw Showdown source`() {
        val repo = System.getProperty("pkmn.assets")?.let { File(it).parentFile } ?: return
        val dex = Dex.load(DirectoryContentSource(File(repo, "assets")))
        val result = LearnsetAuditCore(LearnsetAudit(repo, write = false).buildInput(dex, ArrayList())).run()
        assertTrue(result.findings.none { it.code == "importer-discrepancy" }, "importer output differs from raw data: ${result.findings.filter { it.code == "importer-discrepancy" }.take(5)}")
        assertTrue(result.findings.none { it.code in setOf("invalid-move-id", "duplicate-level-move", "level-out-of-range", "learnset-missing", "tm-unlearnable") })
    }
}
