package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.battle.*
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.Dex
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.mon.Pokemon
import pkmn.engine.mon.PokemonFactory
import pkmn.engine.save.LoadResult
import pkmn.engine.save.SaveManager
import pkmn.engine.util.Rng
import java.io.File
import java.nio.file.Files

/**
 * Engine performance measurements on the build machine (JVM, desktop CPU): content loading, battle turn
 * resolution, AI decisions and save/load of a full save. Writes reports/performance.json.
 * Rendering is measured separately in the running game (desktop `perf` automation command).
 */
class PerfBench(private val root: File) {
    private fun ms(nanos: Long) = nanos / 1_000_000.0
    private fun pct(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    fun run(): Boolean {
        val assets = File(root, "assets")
        var t = System.nanoTime()
        val dex = Dex.load(DirectoryContentSource(assets))
        dex.species.keys.forEach { dex.learnset(it) }
        val dexMs = ms(System.nanoTime() - t)

        t = System.nanoTime()
        val content = WorldContent(DirectoryContentSource(assets))
        content.index.maps.forEach { content.map(it) }
        val scripts = content.scripts.size
        val worldMs = ms(System.nanoTime() - t)

        // Battles: random level-matched teams, both sides driven by the AI (same setup as the fuzz test).
        val factory = PokemonFactory(dex)
        val pool = dex.species.values.filter { !it.isBattleOnly && it.num > 0 }
        val items = dex.items.values.filter { it.category == pkmn.engine.data.ItemCategory.HELD || it.isBerry }.map { it.id }
        val turnNanos = ArrayList<Long>()
        val aiNanos = ArrayList<Long>()
        val battles = 300
        val wall = System.nanoTime()
        for (i in 0 until battles) {
            val rng = Rng(5000L + i)
            val level = 10 + rng.nextInt(91)
            fun mon(): Pokemon {
                val sp = pool[rng.nextInt(pool.size)]
                return factory.create(sp.id, level, rng, item = if (rng.nextInt(2) == 0) items[rng.nextInt(items.size)] else null)
            }
            val s0 = Side(0, "A", List(3 + rng.nextInt(4)) { mon() }, Controller.PLAYER, dex, aiLevel = 2, gimmicks = BattleRules.ALL_GIMMICKS)
            val s1 = Side(1, "B", List(3 + rng.nextInt(4)) { mon() }, Controller.AI, dex, aiLevel = 3, gimmicks = BattleRules.ALL_GIMMICKS)
            val b = Battle(dex, listOf(s0, s1), BattleRules(allowedGimmicks = BattleRules.ALL_GIMMICKS, isTrainerBattle = true), 9000L + i)
            val ai = BattleAI()
            b.start()
            var guard = 0
            while (b.outcome == BattleOutcome.ONGOING && guard++ < 400) {
                val a0 = System.nanoTime()
                when (b.request(s0)) {
                    is Request.SwitchIn -> b.choose(s0, Choice.Switch(ai.chooseReplacement(b, s0)))
                    is Request.Move -> { val c = ai.chooseAction(b, s0); if (b.choose(s0, c) != null) b.choose(s0, Choice.UseMove(0)) }
                    Request.Wait -> {}
                }
                aiNanos.add(System.nanoTime() - a0)
                val t0 = System.nanoTime()
                b.advance(ai)
                turnNanos.add(System.nanoTime() - t0)
            }
        }
        val battleWallMs = ms(System.nanoTime() - wall)
        turnNanos.sort(); aiNanos.sort()

        // Save/load: a late-game save with a full party and 8 full boxes.
        val ctx = GameContext(dex, content, GameContext.newGame(content, "BENCH", "m", "RIVAL", 1))
        val rng = Rng(77)
        repeat(6) { ctx.receivePokemon(factory.create(pool[rng.nextInt(pool.size)].id, 60, rng)) }
        for (b in 0 until 8) for (s in 0 until 30) ctx.state.boxes[b][s] = factory.create(pool[rng.nextInt(pool.size)].id, 40, rng)
        val dir = Files.createTempDirectory("pkmn-bench").toFile()
        val sm = SaveManager(dir, dex)
        val saveN = ArrayList<Long>(); val loadN = ArrayList<Long>()
        repeat(30) {
            val s0 = System.nanoTime(); sm.save(ctx.state); saveN.add(System.nanoTime() - s0)
            val l0 = System.nanoTime(); val r = sm.load(); loadN.add(System.nanoTime() - l0)
            check(r is LoadResult.Ok) { "bench save failed to load: $r" }
        }
        val saveBytes = File(dir, "save.json").length()
        dir.deleteRecursively()
        saveN.sort(); loadN.sort()

        System.gc()
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)

        val json = buildJsonObject {
            put("machine", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${rt.availableProcessors()} cores, Java ${System.getProperty("java.version")}")
            put("note", "JVM on the build machine, warm after the first battles; Android devices are slower (not measured: no device/emulator available)")
            put("dexLoadMs", dexMs.round1()); put("species", dex.species.size); put("moves", dex.moves.size)
            put("worldLoadMs", worldMs.round1()); put("maps", content.index.maps.size); put("scripts", scripts)
            put("battles", battles); put("turns", turnNanos.size); put("battleWallMs", battleWallMs.round1())
            put("turnMeanMs", ms(turnNanos.average().toLong()).round3()); put("turnP95Ms", ms(pct(turnNanos, 0.95)).round3()); put("turnMaxMs", ms(turnNanos.last()).round3())
            put("aiDecisionMeanMs", ms(aiNanos.average().toLong()).round3()); put("aiDecisionP95Ms", ms(pct(aiNanos, 0.95)).round3())
            put("saveBytes", saveBytes)
            put("saveMedianMs", ms(pct(saveN, 0.5)).round3()); put("saveP95Ms", ms(pct(saveN, 0.95)).round3())
            put("loadMedianMs", ms(pct(loadN, 0.5)).round3()); put("loadP95Ms", ms(pct(loadN, 0.95)).round3())
            put("heapAfterGcMb", heapMb.round1())
        }
        writeJson(File(root, "reports/performance.json"), json)
        println(PrettyJson.encodeToString(JsonElement.serializer(), json))
        return true
    }

    private fun Double.round1() = Math.round(this * 10) / 10.0
    private fun Double.round3() = Math.round(this * 1000) / 1000.0
}
