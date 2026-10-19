package pkmn.tools

import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentValidatorTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val content by lazy { Content.load(File(repo, "assets/data")) }

    private fun codes(i: Issues) = i.errors.map { it.code }.toSet()

    @Test
    fun bundledContentHasNoErrors() {
        val issues = ContentValidator(repo).validate(content)
        ContentValidator(repo).validateProvenance(File(repo, "data-src"), content.manifest, issues)
        assertEquals(emptyList(), issues.errors.map { "${it.code} ${it.subject}: ${it.message}" })
        assertEquals(1025, content.species.count { it.formKind == FormKind.BASE })
    }

    @Test
    fun bundledScriptsHandleBattleResultsCorrectly() {
        val scripts = WorldContent(DirectoryContentSource(File(repo, "assets"))).scripts.values
        val issues = Issues()
        ContentValidator(repo).validateBattleScripts(scripts, issues)
        assertEquals(emptyList(), issues.errors.map { "${it.code} ${it.subject}: ${it.message}" })
    }

    @Test
    fun battleResultRuleFlagsMishandledBattles() {
        val src = "script a\n  battle t canlose\n  badge 1\nend\nscript b\n  battle t\n  if result lose\n    say \"x\"\n  end\nend\nscript c\n  battle t canlose\n  if result win\n    badge 1\n  end\nend\n"
        val issues = Issues()
        ContentValidator(repo).validateBattleScripts(pkmn.engine.world.ScriptParser.parseFile(src, "t.pks"), issues)
        assertEquals(setOf("battle-reward-unchecked", "battle-loss-unreachable"), codes(issues))
        assertEquals(2, issues.errors.size)
    }

    private fun map(id: String, npcs: List<pkmn.engine.world.NpcDef> = emptyList(), triggers: List<pkmn.engine.world.TriggerDef> = emptyList(),
                    obstacles: List<pkmn.engine.world.ObstacleDef> = emptyList()) =
        pkmn.engine.world.MapData(id = id, name = id, width = 1, height = 1, tilePalette = listOf("g"), ground = listOf(0), npcs = npcs, triggers = triggers, obstacles = obstacles)

    @Test
    fun bundledFlagsAreAllDefined() {
        val w = WorldContent(DirectoryContentSource(File(repo, "assets")))
        val issues = Issues()
        ContentValidator(repo).validateFlags(w.scripts.values, w.index.maps.map { w.map(it) }, w.events.defs.mapNotNull { it.requiresFlag }, issues)
        assertEquals(emptyList(), issues.errors.map { "${it.code} ${it.subject}: ${it.message}" })
    }

    @Test
    fun undefinedFlagAndTypoAreErrorsButDynamicFlagsAreFine() {
        val src = "script a\n  setflag got_badge\n  pokedex\n  unlock mega\nend\n" +
            "script b\n  if flag got_badge and flag has_pokedex and flag unlock_mega and flag hall_of_fame_x\n    say \"x\"\n  end\n  if not flag got_bagde\n    say \"y\"\n  end\n  if flag cut_tree_1 or flag item:pot1 or flag hidden:m:npc\n    say \"z\"\n  end\nend\n"
        val npc = pkmn.engine.world.NpcDef(id = "n", x = 0, y = 0, sprite = "s", hideIf = "got_bagde", showIf = "got_badge")
        val trig = pkmn.engine.world.TriggerDef(x = 0, y = 0, script = "a", unlessFlag = "never_set")
        val obs = pkmn.engine.world.ObstacleDef(0, 0, "cut", "cut_tree_1")
        val issues = Issues()
        ContentValidator(repo).validateFlags(pkmn.engine.world.ScriptParser.parseFile(src, "t.pks"), listOf(map("m", listOf(npc), listOf(trig), listOf(obs))), listOf("event_gate"), issues)
        assertEquals(setOf("flag-undefined"), codes(issues))
        val msgs = issues.errors.joinToString("\n") { "${it.subject}: ${it.message}" }
        for (bad in listOf("hall_of_fame_x", "got_bagde", "never_set", "event_gate")) assertTrue("'$bad'" in msgs, "$bad not reported: $msgs")
        assertTrue("did you mean 'got_badge'" in msgs, msgs)
        assertTrue("'unlock_mega'" !in msgs && "'cut_tree_1'" !in msgs && "'item:pot1'" !in msgs && "'has_pokedex'" !in msgs, msgs)
        assertEquals(5, issues.errors.size, msgs)
    }

    @Test
    fun bundledMusicIdsAllHaveTracks() {
        val issues = Issues()
        ContentValidator(repo).validateMusic(File(repo, "assets"), issues)
        assertEquals(emptyList(), issues.errors.map { "${it.subject}: ${it.message}" })
    }

    @Test
    fun missingMusicTrackIsAnError() {
        val dir = Files.createTempDirectory("music").toFile()
        try {
            File(dir, "audio/music").mkdirs(); File(dir, "maps").mkdirs()
            File(dir, "audio/music/ok.mml").writeText("")
            File(dir, "maps/a.json").writeText("""{"id":"a","music":"ok"}""")
            File(dir, "maps/b.json").writeText("""{"id":"b","music":"nope"}""")
            val issues = Issues()
            ContentValidator(repo).validateMusic(dir, issues)
            assertEquals(listOf("music-missing"), issues.errors.map { it.code })
            assertEquals("b", issues.errors.single().subject)
        } finally { dir.deleteRecursively() }
    }

    private fun sp(id: String, num: Int, kind: FormKind = FormKind.BASE, base: String = id, abilities: Map<String, String> = mapOf("0" to "overgrow"),
                   types: List<String> = listOf("Grass"), evolutions: List<EvolutionData> = emptyList(), prevo: String? = null,
                   requiredItems: List<String> = emptyList(), canGigantamax: String? = null) =
        SpeciesData(id = id, num = num, name = id.replaceFirstChar { it.uppercase() }, baseSpecies = base, forme = if (kind == FormKind.BASE) null else "X",
            formKind = kind, types = types, baseStats = BaseStats(45, 49, 49, 65, 65, 45), abilities = abilities, maleRatio = 0.5,
            heightm = 1.0, weightkg = 1.0, prevo = prevo, evolutions = evolutions, requiredItems = requiredItems, canGigantamax = canGigantamax)

    private fun tiny(species: List<SpeciesData>, items: List<ItemData> = emptyList(), moves: List<MoveData>? = null) = Content(
        species = species,
        moves = moves ?: listOf(MoveData("tackle", 1, "Tackle", "Normal", "Physical", 40, 100, 35, target = "normal")),
        abilities = listOf(AbilityData("overgrow", 65, "Overgrow")),
        items = items,
        learnsets = species.map { LearnsetData(it.id, listOf(LevelMove(1, "tackle"))) },
        typeChart = TypeChartData(listOf("Normal", "Grass", "Fire"), emptyMap()),
        manifest = null,
    )

    @Test
    fun cleanSyntheticRegistryPasses() {
        val c = tiny(listOf(sp("a", 1, evolutions = listOf(EvolutionData("b", EvolutionMethod.LEVEL, level = 16))), sp("b", 2, prevo = "a")))
        assertTrue(ContentValidator(repo).validate(c, expectedBaseCount = 2).ok)
    }

    @Test
    fun detectsRegistryErrors() {
        val c = tiny(listOf(
            sp("a", 1, abilities = mapOf("0" to "nosuchability"), evolutions = listOf(EvolutionData("ghost", EvolutionMethod.LEVEL, level = 5))),
            sp("a", 1),
            sp("c", 3, types = listOf("Cosmic"), prevo = "a", canGigantamax = "G-Max Thing"),
            sp("cmega", 3, kind = FormKind.MEGA, base = "c", requiredItems = listOf("cite")),
        ), items = listOf(ItemData("cite", 1, "Cite", ItemCategory.MEGA_STONE, BagPocket.ITEMS, megaStone = mapOf("c" to "c"))))
        val codes = codes(ContentValidator(repo).validate(c, expectedBaseCount = 3))
        for (expected in listOf("duplicate-id", "dex-duplicate", "dex-missing", "species-ability", "evo-target-missing", "evo-inconsistent",
            "species-type", "form-gmax-missing", "megastone-to-kind", "form-mega-trigger")) assertTrue(expected in codes, "missing $expected in $codes")
    }

    @Test
    fun detectsBadMoves() {
        val bad = MoveData("zap", 2, "Zap", "Laser", "Magic", 40, 150, 10, target = "everyone",
            secondaries = listOf(SecondaryEffect(chance = 10, status = "doom", volatileStatus = "nosuchvolatile")))
        val codes = codes(ContentValidator(repo).validate(tiny(listOf(sp("a", 1)), moves = listOf(bad, MoveData("tackle", 1, "Tackle", "Normal", "Physical", 40, 100, 35, target = "normal"))), 1))
        for (expected in listOf("move-type", "move-category", "move-target", "move-accuracy", "move-secondary-status", "move-secondary-volatile"))
            assertTrue(expected in codes, "missing $expected in $codes")
    }

    @Test
    fun detectsUnresolvedLearnset() {
        val c = tiny(listOf(sp("a", 1))).let { Content(it.species, it.moves, it.abilities, it.items, emptyList(), it.typeChart, null) }
        assertTrue("learnset-unresolved" in codes(ContentValidator(repo).validate(c, 1)))
    }

    @Test
    fun provenanceFlagsUnlistedAndChangedFiles() {
        val dir = Files.createTempDirectory("prov").toFile()
        File(dir, "a.csv").writeText("x\n")
        File(dir, "b.csv").writeText("y\n")
        File(dir, "SHA256SUMS").writeText("${sha256("x\n".toByteArray())}  ./a.csv\n${"0".repeat(64)}  ./gone.csv\n")
        val manifest = ContentManifest(1, "", 1, listOf(ContentProvenance("t", "s", "MIT", mapOf("a.csv" to "deadbeef"), "today")))
        val issues = Issues()
        ContentValidator(repo).validateProvenance(dir, manifest, issues)
        val codes = codes(issues)
        for (expected in listOf("provenance-unlisted", "provenance-manifest-unlisted", "provenance-manifest-checksum", "provenance-missing-file"))
            assertTrue(expected in codes, "missing $expected in $codes")
        dir.deleteRecursively()
    }

    @Test
    fun evolutionAuditUsesProgressionRules() {
        val results = EvolutionAudit(content.dex).auditAll().associateBy { "${it.from}->${it.evo.target}" }
        assertTrue(results.getValue("bulbasaur->ivysaur").satisfiable)
        assertTrue("useItem:blackaugurite" in results.getValue("scyther->kleavor").requires)
        assertTrue("linkingCord" in results.getValue("kadabra->alakazam").requires)
        val alolan = results.getValue("pikachu->raichualola")
        assertTrue(!alolan.satisfiable && alolan.reason!!.contains("Alola"))
        assertTrue(results.getValue("milcery->alcremie").satisfiable)
    }

    @Test
    fun battleCoverageDegradesGracefullyAndReadsReflectively() {
        assertTrue(BattleCoverageReport.load(content.dex, "pkmn.engine.battle.DoesNotExist").isFailure)
        val ok = BattleCoverageReport.load(content.dex, FakeBattleCoverage::class.java.name).getOrThrow()
        assertEquals(mapOf("tackle" to "FULL"), ok["moves"])
        assertEquals("PARTIAL", ok.getValue("abilities")["overgrow"])
    }
}

enum class FakeStatus { FULL, PARTIAL }

object FakeBattleCoverage {
    fun moves(dex: Dex) = mapOf("tackle" to FakeStatus.FULL)
    fun abilities(dex: Dex) = mapOf("overgrow" to FakeStatus.PARTIAL)
    fun items(dex: Dex) = emptyMap<String, FakeStatus>()
}
