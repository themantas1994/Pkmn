package pkmn.engine.campaign

import kotlinx.serialization.json.*
import pkmn.engine.TestSupport
import pkmn.engine.battle.BattleOutcome
import pkmn.engine.battle.Choice
import pkmn.engine.battle.Obedience
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.mon.Pokemon
import pkmn.engine.save.LoadResult
import pkmn.engine.save.SaveManager
import pkmn.engine.world.ScriptParser
import pkmn.engine.world.ScriptRequest
import pkmn.engine.world.ScriptVM
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Obedience data on every acquisition path, badge caps in real battles, saves and the version 2 -> 3 migration. */
class ObedienceCampaignTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private val dex get() = TestSupport.dex

    private fun newCtx(): GameContext = GameContext(dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    private fun run(ctx: GameContext, source: String): List<ScriptRequest> {
        val script = ScriptParser.parseFile(source, "test.pks").single()
        val vm = ScriptVM(ctx, script, { content.scripts[it] })
        val out = ArrayList<ScriptRequest>()
        while (true) {
            val r = vm.next() ?: break
            out.add(r)
            if (r.blocking) vm.resume(1)
        }
        return out
    }

    private fun catchWild(ctx: GameContext, species: String, level: Int): Pokemon {
        val b = ctx.createWildBattle(ctx.factory.create(species, level, ctx.rng), null)
        b.start()
        while (b.outcome == BattleOutcome.ONGOING) { assertNull(b.choose(b.player, Choice.UseItem("masterball"))); b.advance() }
        ctx.applyBattleResult(b, null)
        return ctx.lastCapture!!.mon
    }

    @Test
    fun `the starter is an outsider received at level 5 and obeys with no badges`() {
        val ctx = newCtx()
        val reqs = ArrayList<ScriptRequest>()
        val vm = ScriptVM(ctx, content.scripts.getValue("lab_ball_fire"), { content.scripts[it] })
        while (true) { val r = vm.next() ?: break; reqs.add(r); if (r.blocking) vm.resume(1) }
        val s = ctx.state.party.single()
        assertTrue(s.outsider); assertEquals(5, s.metLevel)
        assertEquals(20, ctx.obedienceCap())
        assertTrue(Obedience.alwaysObeys(s, ctx.obedienceCap()))
    }

    @Test
    fun `gifts, fossils and trades are outsiders at the received level, hatched eggs are not`() {
        val ctx = newCtx()
        run(ctx, "script t\n  givemon lapras 40\n  giveegg togepi 1\nend\n")
        val lapras = ctx.state.party.first { it.speciesId == "lapras" }
        assertTrue(lapras.outsider); assertEquals(40, lapras.metLevel)
        assertFalse(Obedience.alwaysObeys(lapras, ctx.obedienceCap()), "a level-40 gift needs 4 badges")
        val egg = ctx.state.party.first { it.speciesId == "togepi" }
        assertFalse(egg.outsider); assertEquals(1, egg.metLevel)
        // In-game trade: the received Pokémon carries the NPC's OT and its received level.
        ctx.state.party.add(ctx.factory.create("drilbur", 20, ctx.rng))
        val trade = run(ctx, "script t2\n  trade drilbur onix 30 ot=DIGGER\nend\n").filterIsInstance<ScriptRequest.Trade>().single()
        assertTrue(trade.offer.outsider); assertEquals(30, trade.offer.metLevel)
        ctx.performTrade(ctx.state.party.indexOfFirst { it.speciesId == "drilbur" }, "drilbur", trade.offer)
        assertTrue(ctx.state.party.any { it.uid == trade.offer.uid && it.outsider })
    }

    @Test
    fun `a capture uses its met level, which levelling up and evolving do not change`() {
        val ctx = newCtx()
        ctx.acquire(ctx.factory.create("cyndaquil", 5, ctx.rng))
        val mon = catchWild(ctx, "machop", 24)
        assertFalse(mon.outsider); assertEquals(24, mon.metLevel)
        assertFalse(Obedience.alwaysObeys(mon, ctx.obedienceCap()))
        ctx.progression.addExp(mon, 10_000_000)
        assertEquals(100, mon.level); assertEquals(24, mon.metLevel)
        ctx.evolve(mon, "machoke")
        assertEquals(24, mon.metLevel); assertFalse(mon.outsider)
        // One badge: cap 25 >= 24, so it always obeys even at level 100.
        ctx.state.badges.add(1)
        assertTrue(Obedience.alwaysObeys(mon, ctx.obedienceCap()))
    }

    @Test
    fun `Shedinja keeps Nincada's origin`() {
        val ctx = newCtx()
        val nincada = ctx.factory.create("nincada", 20, ctx.rng).also { it.metLevel = 12; it.outsider = true }
        ctx.acquire(nincada)
        ctx.state.addItem("pokeball", 1)
        ctx.evolve(nincada, "ninjask")
        val shed = ctx.state.party.first { it.speciesId == "shedinja" }
        assertEquals(12, shed.metLevel); assertTrue(shed.outsider)
    }

    @Test
    fun `badges raise the cap mid-run and the cap reaches wild and trainer battles`() {
        val ctx = newCtx()
        ctx.acquire(ctx.factory.create("cyndaquil", 5, ctx.rng))
        val trainer = content.trainers.keys.first()
        for (n in 0..8) {
            val expected = if (n == 8) null else 20 + 5 * n
            assertEquals(expected, ctx.obedienceCap(), "badges $n")
            assertEquals(expected, ctx.createWildBattle(ctx.factory.create("pidgey", 3, ctx.rng), null).rules.obedienceCap)
            assertEquals(expected, ctx.createTrainerBattle(trainer, null).rules.obedienceCap)
            if (n < 8) run(ctx, "script b$n\n  badge ${n + 1}\nend\n")
        }
        assertEquals(8, ctx.state.badgeCount())
    }

    @Test
    fun `save and load keep the met level and outsider flag`() {
        val dir = Files.createTempDirectory("obey1").toFile()
        val ctx = newCtx()
        run(ctx, "script t\n  givemon lapras 40\nend\n")
        val caught = ctx.factory.create("machop", 30, ctx.rng).also { it.metLevel = 22 }
        ctx.state.boxes[0][0] = caught
        SaveManager(dir, dex).save(ctx.state)
        val st = (SaveManager(dir, dex).load() as LoadResult.Ok).state
        assertEquals(true to 40, st.party[0].outsider to st.party[0].metLevel)
        assertEquals(false to 22, st.boxes[0][0]!!.outsider to st.boxes[0][0]!!.metLevel)
    }

    @Test
    fun `version 2 saves migrate - outsider false, saved met levels kept, missing met levels become the current level`() {
        val dir = Files.createTempDirectory("obey2").toFile()
        val ctx = newCtx()
        val a = ctx.factory.create("cyndaquil", 18, ctx.rng).also { it.metLevel = 5 }
        val b = ctx.factory.create("pidgey", 33, ctx.rng)
        ctx.state.party.addAll(listOf(a, b))
        ctx.state.boxes[1][2] = ctx.factory.create("rattata", 27, ctx.rng).also { it.metLevel = 4 }
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val payload = json.encodeToJsonElement(pkmn.engine.save.GameState.serializer(), ctx.state).jsonObject
        fun strip(o: JsonElement, dropMet: Boolean) = JsonObject(o.jsonObject - setOfNotNull("outsider", if (dropMet) "metLevel" else null))
        val v2 = JsonObject(payload + mapOf(
            "party" to JsonArray(payload["party"]!!.jsonArray.mapIndexed { i, m -> strip(m, dropMet = i == 1) }),
            "boxes" to JsonArray(payload["boxes"]!!.jsonArray.map { box -> JsonArray(box.jsonArray.map { if (it is JsonNull) it else strip(it, false) }) }),
        ))
        assertTrue(v2["party"]!!.jsonArray.none { "outsider" in it.jsonObject })
        val text = json.encodeToString(JsonElement.serializer(), v2)
        val env = buildJsonObject {
            put("format", "pkmn-crystalline-save"); put("version", 2); put("savedAt", 0L)
            put("sha256", SaveManager.sha256(text)); put("payload", v2)
        }
        dir.mkdirs(); File(dir, "save.json").writeText(env.toString())
        val r = SaveManager(dir, dex).load() as LoadResult.Ok
        assertEquals(2, r.migratedFrom)
        val st = r.state
        assertEquals(listOf(5, 33), st.party.map { it.metLevel }, "saved met level kept; missing one = current level")
        assertTrue(st.ownedPokemon().none { it.outsider })
        assertEquals(4, st.boxes[1][2]!!.metLevel)
        assertEquals(a.ivs, st.party[0].ivs)
        // Re-saving writes the current version.
        SaveManager(dir, dex).save(st)
        assertNull((SaveManager(dir, dex).load() as LoadResult.Ok).migratedFrom)
        assertEquals(3, SaveManager.CURRENT_VERSION)
    }

    @Test
    fun `a met level above the current level is repaired on load`() {
        val dir = Files.createTempDirectory("obey3").toFile()
        val ctx = newCtx()
        ctx.state.party.add(ctx.factory.create("pidgey", 10, ctx.rng).also { it.metLevel = 60 })
        SaveManager(dir, dex).save(ctx.state)
        val r = SaveManager(dir, dex).load() as LoadResult.Ok
        assertEquals(10, r.state.party[0].metLevel)
        assertTrue(r.warnings.any { "met level" in it })
    }
}
