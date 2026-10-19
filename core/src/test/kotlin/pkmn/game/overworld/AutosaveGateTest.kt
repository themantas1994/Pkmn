package pkmn.game.overworld

import kotlin.test.*

class AutosaveGateTest {
    @Test
    fun `autosave requested after a scripted battle waits until the script has applied its rewards`() {
        val g = AutosaveGate()
        g.scriptStarted()
        assertFalse(g.request(busy = false), "battle ended mid-script: scriptBusy is already false but the script still runs")
        assertFalse(g.request(busy = false), "a pause/background save is deferred too")
        assertTrue(g.scriptEnded(), "script completion releases the deferred save")
        assertFalse(g.scriptEnded(), "only once")
    }

    @Test
    fun `an interrupted or lost script still releases the deferred save when it ends`() {
        val g = AutosaveGate()
        g.scriptStarted()
        assertFalse(g.request(busy = true))
        assertTrue(g.scriptEnded()) // ScriptHost.finish also runs when the VM aborts after a loss or a script error
    }

    @Test
    fun `nested scripts release the save only when the outermost one ends`() {
        val g = AutosaveGate()
        g.scriptStarted(); g.scriptStarted()
        assertFalse(g.request(busy = false))
        assertFalse(g.scriptEnded())
        assertTrue(g.scriptEnded())
    }

    @Test
    fun `outside scripts autosave runs immediately unless busy`() {
        val g = AutosaveGate()
        assertTrue(g.request(busy = false))
        assertFalse(g.request(busy = true))
    }
}
