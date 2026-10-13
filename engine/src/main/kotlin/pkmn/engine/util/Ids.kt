package pkmn.engine.util

/** Canonical identifier: lowercase ASCII letters and digits only (same scheme as Pokémon Showdown). */
fun toId(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
        val l = c.lowercaseChar()
        when {
            l in 'a'..'z' || l in '0'..'9' -> sb.append(l)
            l == 'é' || l == 'è' -> sb.append('e')
        }
    }
    return sb.toString()
}
