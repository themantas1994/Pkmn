package pkmn.game.audio

/** Key/scale helpers used to validate `#key` directives and to analyse songs in tests. */
object ScaleUtil {
    private val MODES = mapOf(
        "major" to intArrayOf(0, 2, 4, 5, 7, 9, 11),
        "ionian" to intArrayOf(0, 2, 4, 5, 7, 9, 11),
        // natural minor plus the raised 6th/7th of the harmonic/melodic forms
        "minor" to intArrayOf(0, 2, 3, 5, 7, 8, 10, 11),
        "aeolian" to intArrayOf(0, 2, 3, 5, 7, 8, 10),
        "dorian" to intArrayOf(0, 2, 3, 5, 7, 9, 10),
        "phrygian" to intArrayOf(0, 1, 3, 5, 7, 8, 10),
        "lydian" to intArrayOf(0, 2, 4, 6, 7, 9, 11),
        "mixolydian" to intArrayOf(0, 2, 4, 5, 7, 9, 10),
    )
    private val NOTE = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    /** Parses e.g. "F# minor" into (tonic pitch class, scale pitch-class set), or null. */
    fun parseKey(key: String): Pair<Int, Set<Int>>? {
        val parts = key.trim().split(Regex("\\s+"))
        if (parts.size != 2 || parts[0].isEmpty()) return null
        val n = NOTE[parts[0][0].uppercaseChar()] ?: return null
        var pc = n
        for (c in parts[0].substring(1)) pc += when (c) { '#' -> 1; 'b' -> -1; else -> return null }
        val mode = MODES[parts[1].lowercase()] ?: return null
        pc = ((pc % 12) + 12) % 12
        return pc to mode.map { (it + pc) % 12 }.toSet()
    }

    /** Union of the pitch classes of every key listed. */
    fun scaleOf(keys: List<String>): Set<Int> = keys.mapNotNull { parseKey(it)?.second }.flatten().toSet()
}
