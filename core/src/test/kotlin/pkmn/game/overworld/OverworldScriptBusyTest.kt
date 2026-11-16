package pkmn.game.overworld

import java.io.File
import kotlin.test.*

class OverworldScriptBusyTest {
    @Test
    fun `a battle started by a running script leaves input locked when the battle ends`() {
        val lock = ScriptBusy()
        lock.busy = true // runScriptById
        val restore = lock.enter() // runBattle called by the script
        assertTrue(lock.busy)
        restore() // battle callback
        assertTrue(lock.busy, "the script is still running after the battle")
    }

    @Test
    fun `a battle started from free exploration unlocks input when it ends`() {
        val lock = ScriptBusy()
        val restore = lock.enter()
        assertTrue(lock.busy)
        restore()
        assertFalse(lock.busy)
    }

    /**
     * OverworldScreen needs a GL context (PkmnGame, BattleScreen), so the real runBattle cannot run in a unit test.
     * This guard reads its source and fails if the restore goes back to clearing the lock unconditionally.
     */
    @Test
    fun `runBattle restores the lock through ScriptBusy instead of clearing it`() {
        val rel = "core/src/main/kotlin/pkmn/game/overworld/OverworldScreen.kt"
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, rel) }.firstOrNull { it.isFile }?.readText()
            ?: fail("cannot find $rel from ${File("").absolutePath}")
        val start = src.indexOf("private fun runBattle(")
        assertTrue(start >= 0, "runBattle not found")
        val body = src.substring(start, src.indexOf("private fun blackout(", start))
        assertTrue("busyLock.enter()" in body, "runBattle must take the lock with ScriptBusy.enter()")
        assertFalse("scriptBusy = true" in body, "runBattle must not set the lock directly")
        val afterEvolutions = body.substring(body.indexOf("runEvolutions("))
        val restoreAt = afterEvolutions.indexOf("restoreBusy()")
        assertTrue(restoreAt >= 0, "the post-battle path must call the restore returned by enter()")
        assertTrue(restoreAt < afterEvolutions.indexOf("onDone(outcome)"), "restore happens before onDone")
        assertFalse("scriptBusy = false" in afterEvolutions.substring(0, afterEvolutions.indexOf("onDone(outcome)")),
            "the post-battle path must not clear the lock unconditionally")
    }
}
