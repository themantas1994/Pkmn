package pkmn.game.overworld

/**
 * The overworld's input lock. A battle started from inside a script must hand the lock back as it found it:
 * the script is still running after the battle, so clearing the lock would let the player move mid-script.
 */
class ScriptBusy {
    var busy = false

    /** Locks input and returns the function that restores the state from before this call. */
    fun enter(): () -> Unit {
        val was = busy
        busy = true
        return { busy = was }
    }
}
