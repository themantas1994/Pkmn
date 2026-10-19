package pkmn.game.overworld

/**
 * Decides when an autosave may run. While a script is running (including the stretch after a scripted battle,
 * when badge/TM/flags are still to be applied) a request is only remembered and is granted when the last script ends,
 * so the save file never holds a half-applied script outcome.
 */
class AutosaveGate {
    private var scripts = 0
    private var deferred = false

    /** True when an autosave may run now; otherwise the request is kept for [scriptEnded]. */
    fun request(busy: Boolean): Boolean {
        if (busy || scripts > 0) { deferred = true; return false }
        deferred = false
        return true
    }

    fun scriptStarted() { scripts++ }

    /** True when the last script has ended and an autosave was requested meanwhile. */
    fun scriptEnded(): Boolean {
        if (scripts > 0) scripts--
        if (scripts > 0 || !deferred) return false
        deferred = false
        return true
    }
}
