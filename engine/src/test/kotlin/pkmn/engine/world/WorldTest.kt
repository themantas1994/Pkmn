package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.save.LoadResult
import pkmn.engine.save.SaveManager
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WorldTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }

    private fun newCtx(): GameContext = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    private fun run(ctx: GameContext, id: String, answers: MutableList<Int> = mutableListOf()): List<ScriptRequest> {
        val vm = ScriptVM(ctx, content.scripts.getValue(id), { content.scripts[it] })
        val out = ArrayList<ScriptRequest>()
        while (true) {
            val r = vm.next() ?: break
            out.add(r)
            if (r.blocking) vm.resume(when (r) { is ScriptRequest.Ask, is ScriptRequest.Choice -> answers.removeFirstOrNull() ?: 1; is ScriptRequest.TrainerBattle -> 1; else -> 0 })
        }
        return out
    }

    @Test
    fun `script parser handles nested conditions and rejects unknown commands`() {
        val src = """
            script t
              if flag a and not flag b
                say "one"
              elif badges >= 2 or item potion
                say "two"
              else
                if true
                  say "three"
                end
              end
            end
        """.trimIndent()
        val s = ScriptParser.parseFile(src, "t.pks").single()
        assertTrue(s.code.isNotEmpty())
        assertFailsWith<ScriptError> { ScriptParser.parseFile("script x\n  dance\nend", "x.pks") }
        assertFailsWith<ScriptError> { ScriptParser.parseFile("script x\n  if flag a\n  say \"hi\"\nend", "x.pks") }
    }

    @Test
    fun `choosing a starter gives the pokemon and sets flags`() {
        val ctx = newCtx()
        val reqs = run(ctx, "lab_ball_fire", mutableListOf(1))
        assertTrue(ctx.state.hasFlag("got_starter"))
        assertEquals("cyndaquil", ctx.state.party.single().speciesId)
        assertTrue(reqs.any { it is ScriptRequest.SetVisible && !it.visible })
        assertTrue("cyndaquil" in ctx.state.caught)
        // Declining does nothing.
        val ctx2 = newCtx()
        run(ctx2, "lab_ball_grass", mutableListOf(0))
        assertFalse(ctx2.state.hasFlag("got_starter"))
        assertTrue(ctx2.state.party.isEmpty())
    }

    @Test
    fun `trade swaps the offered pokemon and reports trade evolutions`() {
        val ctx = newCtx()
        ctx.receivePokemon(ctx.factory.create("wooper", 12, ctx.rng))
        val s = ScriptParser.parseFile("script t\n  trade wooper haunter 20 nick=Spooky ot=KEN\nend", "t.pks").single()
        val req = ScriptVM(ctx, s, { null }).next() as ScriptRequest.Trade
        assertEquals("wooper", req.want)
        assertEquals("KEN", req.offer.otName)
        assertEquals(listOf(0), ctx.tradeCandidates("wooper"))
        assertTrue(ctx.tradeCandidates("pikachu").isEmpty())
        ctx.receivePokemon(ctx.factory.create("exeggutoralola", 30, ctx.rng))
        assertTrue(ctx.tradeCandidates("exeggutor").isEmpty())
        assertEquals("gengar", ctx.performTrade(0, "wooper", req.offer))
        assertEquals("haunter", ctx.state.party[0].speciesId)
        assertFailsWith<IllegalArgumentException> { ctx.performTrade(0, "wooper", req.offer) }
    }

    @Test
    fun `result of givemon survives its message box`() {
        val ctx = newCtx()
        val src = "script t\n  givemon pikachu 5\n  if result 1\n    setflag got_it\n  end\nend"
        val vm = ScriptVM(ctx, ScriptParser.parseFile(src, "t.pks").single(), { null })
        while (true) { val r = vm.next() ?: break; if (r.blocking) vm.resume() }
        assertTrue(ctx.state.hasFlag("got_it"))
    }

    @Test
    fun `professor gives the pokedex after the starter`() {
        val ctx = newCtx()
        run(ctx, "lab_ball_water", mutableListOf(1))
        run(ctx, "lab_professor")
        assertTrue(ctx.state.hasFlag("got_pokedex"))
        assertEquals(5, ctx.state.itemCount("pokeball"))
    }

    @Test
    fun `all world scripts parse and every map loads`() {
        assertTrue(content.scripts.isNotEmpty())
        for (id in content.index.maps) assertNotNull(content.mapOrNull(id), id)
        val start = content.map(content.info.startMap)
        assertTrue(start.inBounds(content.info.startX, content.info.startY))
    }

    @Test
    fun `movement rules handle walls, ledges, warps and connections`() {
        val ctx = newCtx()
        val rules = OverworldRules(ctx)
        val route = content.map("route1")
        val none: (Int, Int) -> Occupant? = { _, _ -> null }
        // Ledge row at y=6 is jumpable southwards only.
        assertIs<StepResult.Jump>(rules.step(route, 2, 5, Facing.DOWN, false, none))
        assertIs<StepResult.Bump>(rules.step(route, 2, 7, Facing.UP, false, none))
        // Trees block.
        assertIs<StepResult.Bump>(rules.step(route, 1, 2, Facing.LEFT, false, none))
        // Walking south off route 1 enters Hollowbrook Town.
        val t = rules.step(route, 9, route.height - 1, Facing.DOWN, false, none)
        assertIs<StepResult.Transfer>(t)
        assertEquals("hometown", t.map)
        // Door warps.
        val home = content.map("hometown")
        val w = rules.step(home, 4, 7, Facing.UP, false, none)
        assertIs<StepResult.Warp>(w)
        assertEquals("player_house_1f", w.warp.toMap)
    }

    @Test
    fun `save round trip preserves state and recovers from corruption`() {
        val dir = Files.createTempDirectory("save").toFile()
        val ctx = newCtx()
        run(ctx, "lab_ball_grass", mutableListOf(1))
        ctx.state.setFlag("custom")
        ctx.state.money = 12345
        val mgr = SaveManager(dir, TestSupport.dex)
        mgr.save(ctx.state)
        val loaded = (mgr.load() as LoadResult.Ok).state
        assertEquals(12345, loaded.money)
        assertEquals("chikorita", loaded.party.single().speciesId)
        assertEquals(ctx.state.party.single().ivs, loaded.party.single().ivs)
        assertTrue(loaded.hasFlag("custom"))
        // Second save creates a backup; corrupting the main file falls back to it.
        ctx.state.money = 999
        mgr.save(ctx.state)
        File(dir, "save.json").writeText("{ truncated")
        val r = mgr.load()
        assertIs<LoadResult.Ok>(r)
        assertTrue(r.fromBackup)
        assertEquals(12345, r.state.money)
        // Both corrupted: nothing is silently discarded.
        File(dir, "save.json").writeText("garbage")
        File(dir, "save.json.bak").writeText("garbage")
        val f = mgr.load()
        assertIs<LoadResult.Failed>(f)
        assertTrue(f.preservedFiles.isNotEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.contains(".corrupt-") })
    }

    @Test
    fun `scripted legendary encounters build a pokemon with perfect ivs`() {
        val ctx = newCtx()
        val s = ScriptParser.parseFile("script t\n  wildbattle lugia 70 norun\nend", "t.pks").single()
        val req = ScriptVM(ctx, s, { null }).next() as ScriptRequest.WildBattle
        assertTrue(pkmn.engine.data.Stat.entries.count { req.mon.ivs[it] == 31 } >= 3)
        assertFalse(req.canRun)
    }

    @Test
    fun `every campaign trainer battle can be built and played to the end`() {
        val ctx = newCtx()
        // A strong level-100 team so every battle finishes; the point is that each trainer's data is battle-valid.
        val party = listOf("mewtwo", "arceus", "rayquaza", "kyogre", "groudon", "giratina").map {
            ctx.factory.create(it, 100, ctx.rng, perfectIvs = 6)
        }
        ctx.state.party.clear(); ctx.state.party.addAll(party)
        val ai = pkmn.engine.battle.BattleAI()
        var turns = 0
        for ((id, _) in content.trainers) {
            for (m in ctx.state.party) m.healFully(TestSupport.dex)
            val b = ctx.createTrainerBattle(id, null, seed = id.hashCode().toLong())
            b.start()
            var guard = 0
            while (b.outcome == pkmn.engine.battle.BattleOutcome.ONGOING && guard++ < 300) {
                when (b.request(b.player)) {
                    is pkmn.engine.battle.Request.SwitchIn -> b.choose(b.player, pkmn.engine.battle.Choice.Switch(ai.chooseReplacement(b, b.player)))
                    is pkmn.engine.battle.Request.Move -> b.choose(b.player, ai.chooseAction(b, b.player))
                    pkmn.engine.battle.Request.Wait -> {}
                }
                b.advance(ai)
            }
            assertNotEquals(pkmn.engine.battle.BattleOutcome.ONGOING, b.outcome, "battle against $id did not finish")
            turns += b.turn
        }
        assertTrue(content.trainers.size > 100 && turns > 0)
    }

    @Test
    fun `tampered save payload is detected by checksum`() {
        val dir = Files.createTempDirectory("save2").toFile()
        val ctx = newCtx()
        val mgr = SaveManager(dir, TestSupport.dex)
        mgr.save(ctx.state)
        val f = File(dir, "save.json")
        f.writeText(f.readText().replace("\"money\":3000", "\"money\":999999"))
        assertIs<LoadResult.Failed>(mgr.load())
    }

    @Test
    fun `wild encounters respect tables and time of day`() {
        val ctx = newCtx()
        ctx.clock = { java.time.LocalTime.of(23, 0) }
        val route = content.map("route1")
        val species = (0 until 300).mapNotNull { ctx.rollEncounter(route, "grass", forceEncounter = true)?.speciesId }.toSet()
        assertTrue(species.all { it in setOf("hoothoot", "rattata", "spinarak") }, species.toString())
    }

    @Test
    fun `trainer battle setup uses trainer data`() {
        val ctx = newCtx()
        run(ctx, "lab_ball_grass", mutableListOf(1))
        val b = ctx.createTrainerBattle("route1_mia", content.map("route1"))
        assertEquals(2, b.opponent.mons.size)
        assertFalse(b.rules.canRun)
        b.start()
        assertEquals("sentret", b.opponent.active!!.species.id)
    }
}
