package pkmn.engine.save

import pkmn.engine.TestSupport
import java.nio.file.Files
import kotlin.test.*

/** A save that throws leaves files that load as the state before it; one that reports Saved is the new state. */
class SaveAtomicityTest {
    private val dex get() = TestSupport.dex

    @Test
    fun `a save that fails after writing its temporary copy leaves the old state on disk`() {
        for (step in listOf("tmp-written", "main-moved")) {
            val dir = Files.createTempDirectory("save-atomic").toFile()
            assertEquals(SaveResult.Saved, SaveManager(dir, dex).save(GameState(money = 1)))
            assertEquals(SaveResult.Saved, SaveManager(dir, dex).save(GameState(money = 2)))
            val sm = SaveManager(dir, dex).also { it.testFault = { at -> if (at == step) throw java.io.IOException("fail at $at") } }
            assertFailsWith<java.io.IOException>(step) { sm.save(GameState(money = 3)) }
            val l = assertIs<LoadResult.Ok>(SaveManager(dir, dex).load(), step)
            assertEquals(2, l.state.money, "$step: the failed save did not happen")
            assertFalse(java.io.File(dir, "save.json.tmp").exists(), "$step: no temporary copy left for crash recovery")
        }
    }

    @Test
    fun `a failure after the new save is in place still counts as saved`() {
        val dir = Files.createTempDirectory("save-atomic").toFile()
        assertEquals(SaveResult.Saved, SaveManager(dir, dex).save(GameState(money = 1)))
        val sm = SaveManager(dir, dex).also { it.testFault = { at -> if (at == "replaced") throw java.io.IOException("fail late") } }
        assertEquals(SaveResult.Saved, sm.save(GameState(money = 2)))
        assertEquals(2, assertIs<LoadResult.Ok>(SaveManager(dir, dex).load()).state.money)
    }
}
