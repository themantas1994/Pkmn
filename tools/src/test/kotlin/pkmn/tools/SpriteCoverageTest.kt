package pkmn.tools

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Q0.3: species used by encounters, trainers, scripts, gifts and events, per tier. Synthetic fixtures only. */
class SpriteCoverageTest {
    private fun root(block: File.() -> Unit): File = Files.createTempDirectory("spritecov").toFile().apply {
        fun w(path: String, text: String) { File(this, path).also { it.parentFile.mkdirs() }.writeText(text) }
        w("assets/data/species.json", """[
          {"id":"chikorita","evolutions":[{"target":"bayleef"}]},{"id":"bayleef","evolutions":[{"target":"meganium"}]},{"id":"meganium"},
          {"id":"pidgey"},{"id":"geodude"},{"id":"onix"},{"id":"rattata"},{"id":"gible"},{"id":"zubat"},{"id":"mew"},{"id":"unused"}]""")
        w("assets/world/encounters.json", """[
          {"id":"route1","methods":{"grass":{"morning":[{"species":"pidgey"}]}}},
          {"id":"p2_route5","methods":{"grass":{"any":[{"species":"geodude"}]}}},
          {"id":"p4a_cave","methods":{"grass":{"any":[{"species":"zubat"}]}}}]""")
        w("assets/world/trainers.json", """[
          {"id":"rival_lab_x","trainerClass":"rival","team":[{"species":"rattata"}]},
          {"id":"p2_leader_y","trainerClass":"leader","team":[{"species":"onix"}]},
          {"id":"p4a_hiker","trainerClass":"hiker","team":[{"species":"zubat"},{"species":"onix"}]}]""")
        w("assets/events/events.json", """[{"id":"e1","species":"mew"}]""")
        w("world/scripts/p1_lab.pks", "script a\n  givemon chikorita 5\nend\n")
        w("world/scripts/p3_x.pks", "script b\n  giveegg gible 1\n  wildbattle mew 70 norun\nend\n")
        block()
    }

    private fun File.sprite(kind: String, id: String) = File(this, "assets/sprites/pokemon/$kind/$id.png").also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1))

    @Test
    fun tiersFollowTheRoadmapRulesAndTakeTheLowestTier() {
        val res = SpriteCoverage(root {}, write = false).compute()
        val tier = res.rows.associate { it.species to it.tier }
        assertEquals(1, tier["chikorita"], "starter")
        assertEquals(1, tier["bayleef"], "starter line")
        assertEquals(1, tier["meganium"], "starter line")
        assertEquals(1, tier["pidgey"], "part 1 route")
        assertEquals(1, tier["rattata"], "part 1 rival")
        assertEquals(2, tier["geodude"], "routes 5-9")
        assertEquals(2, tier["onix"], "leader team beats the tier-3 hiker")
        assertEquals(3, tier["zubat"], "other wild/trainer use")
        assertEquals(3, tier["gible"], "non-starter gift")
        assertEquals(3, tier["mew"], "static and event")
        assertFalse("unused" in tier, "unused species are only counted (tier 4)")
        assertEquals(1, res.unusedSpecies)
    }

    @Test
    fun statusIsBundledSpritesOnlyAndCompleteTierNeedsFrontBackIcon() {
        val r = root {
            for (k in listOf("front", "back", "icon")) sprite(k, "pidgey")
            sprite("front", "rattata")
            // a local override must never change the committed report
            File(this, "assets-local/sprites/pokemon/front").mkdirs(); File(this, "assets-local/sprites/pokemon/front/chikorita.png").writeBytes(byteArrayOf(1))
        }
        val res = SpriteCoverage(r, write = false).compute()
        val status = res.rows.associate { it.species to it.status }
        assertEquals("original", status["pidgey"])
        assertEquals("partial", status["rattata"])
        assertEquals("fallback", status["chikorita"])
        val t1 = res.tiers.getValue(1)
        assertEquals(5, t1.species); assertEquals(1, t1.original); assertEquals(1, t1.partial); assertFalse(t1.complete)
    }

    @Test
    fun unknownSpeciesFailTheRunAndReportIsWrittenDeterministically() {
        val r = root {}
        assertTrue(SpriteCoverage(r).run())
        val first = File(r, "reports/sprite-coverage.json").readText()
        assertTrue(SpriteCoverage(r).run())
        assertEquals(first, File(r, "reports/sprite-coverage.json").readText())
        val j = Json.parseToJsonElement(first).jsonObject
        assertEquals(10, j.getValue("usedSpecies").jsonPrimitive.int)
        File(r, "world/scripts/bad.pks").writeText("script c\n  givemon nosuchmon 5\nend\n")
        assertFalse(SpriteCoverage(r, write = false).run())
    }

    @Test
    fun toolsMainRoutesSpriteCoverage() {
        assertEquals(EXIT_OK, runTool(arrayOf("sprite-coverage", "--root=${root {}.path}")))
    }
}
