package pkmn.engine.link

import pkmn.engine.link.TradeTestSupport.Device
import pkmn.engine.link.TradeTestSupport.assertUniqueUids
import pkmn.engine.save.LoadResult
import pkmn.engine.save.PendingTrade
import pkmn.engine.save.SaveManager
import pkmn.engine.save.TradeJournalState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.*

/**
 * Kills a real process during the "Saving..." step. The child JVM runs the trade store of one device in a loop: save the
 * journal as PREPARED, then apply the swap and save it as COMMITTED, again and again, so nearly all of its time is spent
 * inside a save. The parent kills it with SIGKILL at a random moment and loads what is on disk. Every load must succeed
 * without warnings and show the trade of the journal it found either fully applied (COMMITTED) or not at all (PREPARED).
 * Both devices of a trade run this same store code, so this covers a kill of either process.
 */
class TradeKillTest {
    @Test
    @org.junit.jupiter.api.Timeout(300)
    fun `a process killed while saving a trade leaves a save with the trade fully applied or not at all`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val rnd = Random(20261114)
        val seen = HashMap<String, Int>()
        repeat(12) { run ->
            val dir = Files.createTempDirectory("trade-kill").toFile()
            val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "-Dpkmn.assets=" + System.getProperty("pkmn.assets"),
                "pkmn.engine.link.TradeKillTestKt", dir.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader()
            // The child may log before it is ready (content loading); skip to its READY line.
            val early = ArrayList<String>()
            while (true) {
                val line = out.readLine() ?: break
                if (line == "READY") break
                early.add(line)
            }
            if (!p.isAlive) fail("child did not start: ${early.takeLast(20)}")
            Thread.sleep(rnd.nextLong(20, 400))
            p.destroyForcibly()
            p.descendants().forEach { it.destroyForcibly() }
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            val l = assertIs<LoadResult.Ok>(SaveManager(dir, TradeTestSupport.dex).load(), "run $run")
            // A kill between writing save.json.tmp and renaming it leaves a complete temporary copy; loading takes it and
            // says so. That is the save working as designed; any other warning would mean damaged data.
            val recovered = "The last save was interrupted; it was recovered from the temporary save file."
            assertTrue(l.warnings.all { it == recovered }, "run $run: ${l.warnings}")
            if (recovered in l.warnings) seen.merge("recovered from tmp", 1, Int::plus)
            assertEquals(3, l.state.party.size, "run $run: party size")
            assertUniqueUids(l.state.ownedPokemon(), "run $run")
            val j = l.tradeJournal
            seen.merge(j?.state?.name ?: "none", 1, Int::plus)
            when (j?.state) {
                null -> assertEquals("ARI", l.state.party[0].otName, "run $run: the first save, nothing traded")
                TradeJournalState.PREPARED -> {
                    assertEquals(j.givenUid, l.state.party[0].uid, "run $run: PREPARED, the given Pokémon is still there")
                    assertTrue(l.state.party.none { it.counters["iter"] == j.received.counters["iter"] }, "run $run: nothing received")
                    assertEquals(1, l.notices.size)
                }
                TradeJournalState.COMMITTED -> {
                    assertTrue(l.state.party.none { it.uid == j.givenUid }, "run $run: COMMITTED, the given Pokémon is gone")
                    assertEquals(j.received.counters["iter"], l.state.party[j.givenSlot].counters["iter"], "run $run: received in its slot")
                    assertTrue(l.notices.isEmpty())
                }
            }
        }
        println("trade kill test: journal states found after the kills: $seen")
        assertTrue((seen["PREPARED"] ?: 0) + (seen["COMMITTED"] ?: 0) > 0, "no kill landed inside the trade loop: $seen")
    }
}

/** The child process of [TradeKillTest]: one device trading in a loop until it is killed. */
fun main(args: Array<String>) {
    val a = Device("ARI", 1, listOf("pidgey", "rattata", "sentret"), dir = File(args[0]))
    val other = Device("MIO", 2, listOf("zubat", "geodude"))
    println("READY"); System.out.flush()
    var i = 0
    while (true) {
        val given = a.ctx.state.party[0]
        val rec = other.ctx.state.party[i % 2].copy().also { it.counters["iter"] = i }
        val j = PendingTrade("k$i", TradeJournalState.PREPARED, "MIO", given.uid, 0, given.speciesId, given.displayName(a.ctx.dex), rec)
        a.store.prepare(j)
        a.store.commit(j)
        i++
    }
}
