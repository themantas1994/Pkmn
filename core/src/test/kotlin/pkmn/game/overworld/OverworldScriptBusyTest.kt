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

    private fun overworldSource(): String {
        val rel = "core/src/main/kotlin/pkmn/game/overworld/OverworldScreen.kt"
        return generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, rel) }.firstOrNull { it.isFile }?.readText() ?: fail("cannot find $rel")
    }

    /** C5-8: OverworldScreen needs a GL context, so this guards the source: loadMap must refresh the sprite after resetting surf/bike. */
    @Test
    fun `loadMap refreshes the player sprite after it resets surfing or biking`() {
        val src = overworldSource()
        val start = src.indexOf("fun loadMap(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        val reset = body.indexOf("state.biking = false")
        assertTrue(reset >= 0 && body.indexOf("player.sprite = playerSprite()", reset) > reset,
            "loadMap must set player.sprite = playerSprite() after clearing surfing/biking")
    }

    /** C5-5: the step handler must not open the SURF dialog; only A facing water (interact) does. */
    @Test
    fun `the step handler does not offer surf`() {
        val src = overworldSource()
        assertFalse("OfferSurf" in src, "StepResult.OfferSurf is gone: a step into water is a bump")
        val calls = Regex("offerSurf\\(\\)").findAll(src.substringAfter("private fun offerSurf")).count() +
            Regex("offerSurf\\(\\)").findAll(src.substringBefore("private fun offerSurf")).count()
        assertEquals(1, calls, "offerSurf() is called from interact() only")
    }
}
