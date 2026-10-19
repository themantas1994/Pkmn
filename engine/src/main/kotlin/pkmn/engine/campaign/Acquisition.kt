package pkmn.engine.campaign

import pkmn.engine.mon.Pokemon
import java.text.Normalizer

/**
 * Nickname rules (docs/CAPTURE.md): up to [MAX_LENGTH] characters (the Generation VI+ limit), letters of the Latin
 * script, digits, spaces and the symbols on the in-game keyboard. Names are NFC-normalised and trimmed; a name equal to
 * the species name is stored as "no nickname", so the Pokémon keeps following its species name when it evolves.
 */
object Nicknames {
    const val MAX_LENGTH = 12
    const val SYMBOLS = " .,-'!?&♂♀"

    sealed class Check {
        data class Ok(val name: String) : Check()
        data class Invalid(val reason: String) : Check()
    }

    fun check(input: String?): Check {
        if (input == null) return Check.Invalid("empty")
        val name = Normalizer.normalize(input, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return Check.Invalid("empty")
        if (name.codePointCount(0, name.length) > MAX_LENGTH) return Check.Invalid("longer than $MAX_LENGTH characters")
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val ok = cp.toChar().toString() in SYMBOLS && Character.charCount(cp) == 1 ||
                Character.isDigit(cp) && cp < 128 ||
                Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
            if (!ok) return Check.Invalid("character '${String(Character.toChars(cp))}' is not allowed")
            i += Character.charCount(cp)
        }
        return Check.Ok(name)
    }
}

/** What happened when the player obtained a Pokémon (capture, starter, gift, trade). */
data class Acquisition(
    val mon: Pokemon,
    /** "party", "box:<n>" or null when every box is full (the Pokémon was not stored). */
    val where: String?,
    /** First time this species is registered as caught: the Pokédex entry is shown. */
    val newSpecies: Boolean,
    /** First time this exact form is registered as caught. */
    val newForm: Boolean,
    /** The same individual (uid) was already stored: nothing was added or registered again. */
    val duplicate: Boolean = false,
) {
    /** The Pokémon is stored (party or a box). False means every box was full and nothing was added or registered. */
    val stored: Boolean get() = where != null
    val inParty: Boolean get() = where == "party"
    /** Sent straight to a PC box because the party was full. */
    val inPc: Boolean get() = where?.startsWith("box:") == true
    /** Party and PC were both full: the caller must keep the Pokémon pending (gift) or refuse the capture. */
    val storageFull: Boolean get() = where == null
}
