package pkmn.engine.battle

import pkmn.engine.mon.Pokemon

/**
 * Badge-based obedience (docs/MECHANICS.md, "Obedience"). Source: Bulbapedia, "Obedience" (wikitext read 2026-10-18).
 *
 * - **Which level counts.** Scarlet/Violet (Generation IX) compare the Pokémon's *met level* (Pokémon the player caught)
 *   or the level at which it was *received* (gifts, trades, fossils, starters), not its current level. In this game
 *   every received Pokémon is created when the player receives it, so [Pokemon.metLevel] holds the received level for
 *   outsiders ([Pokemon.outsider]) and the met level for captures: one field serves both.
 * - **Level cap.** Paldea table, by badge count: 20 + 5 per badge for 0-7 badges; with all 8 badges every Pokémon obeys.
 *   A Pokémon whose reference level is at or below the cap always obeys.
 * - **Where.** Only battles that award experience (wild and trainer battles); only the player's Pokémon.
 * - **Disobedience check** (Generation III/IV formula; the Generation V+ formulas are undocumented, so the documented
 *   one is reused). With L = reference level and C = cap, each roll R is a random integer 0..255:
 *   1. (L + C) × R1 / 256 < C: obeys.
 *   2. Otherwise it disobeys. Asleep and using Snore or Sleep Talk: it does nothing.
 *   3. (L + C) × R2 / 256 < C: uses another move it can use (none: does nothing).
 *   4. R3 < L - C: falls asleep (not with a major status, during an uproar, or with Vital Spirit/Insomnia);
 *      else R3 < 2 × (L - C): hurts itself as in confusion; else it does nothing.
 *      A blocked nap falls through to the self-hit, as in the Generation III code.
 * The check is skipped while the Pokémon is locked into a move (second turn of a charging move, Bide, Outrage-style
 * locks, Rollout, Uproar, recharging). A disobedient Pokémon's Encore and Rage end.
 */
object Obedience {
    /** Highest reference level that always obeys, indexed by badge count 0..7 (Paldea: 20 + 5 per badge). */
    val PALDEA_LEVEL_CAPS: List<Int> = listOf(20, 25, 30, 35, 40, 45, 50, 55)

    /** Badges after which every Pokémon obeys (the end of the main story). */
    const val ALL_OBEY_BADGES = 8

    /** The level cap for [badges] badges, or null when every Pokémon obeys. */
    fun levelCap(badges: Int): Int? = if (badges >= ALL_OBEY_BADGES) null else PALDEA_LEVEL_CAPS[badges.coerceAtLeast(0)]

    /** The level obedience compares: the met level for captures, the received level for outsiders (see class doc). */
    fun referenceLevel(mon: Pokemon): Int = mon.metLevel.coerceIn(1, 100)

    /** True when [mon] obeys without any roll under [cap] (null = everyone obeys). */
    fun alwaysObeys(mon: Pokemon, cap: Int?): Boolean = cap == null || referenceLevel(mon) <= cap

    /** One roll of the Generation III/IV check: (L + C) × R / 256 with integer division. */
    fun score(level: Int, cap: Int, r: Int): Int = (level + cap) * r / 256

    enum class Outcome { OBEY, IGNORE_ASLEEP, OTHER_MOVE, NAP, SELF_HIT, LOAF }

    /**
     * Decides what a Pokémon of reference [level] does under [cap]. [roll] returns a random integer 0..255 and is called
     * only as often as the formula needs (0 to 3 times). [asleepSleepMove]: it is asleep and was told to use Snore or
     * Sleep Talk. [canNap]: no major status, no uproar, no Vital Spirit/Insomnia.
     */
    fun decide(level: Int, cap: Int, roll: () -> Int, asleepSleepMove: Boolean, canNap: Boolean): Outcome {
        if (level <= cap) return Outcome.OBEY
        if (score(level, cap, roll()) < cap) return Outcome.OBEY
        if (asleepSleepMove) return Outcome.IGNORE_ASLEEP
        if (score(level, cap, roll()) < cap) return Outcome.OTHER_MOVE
        val r3 = roll()
        val gap = level - cap
        return when {
            r3 < gap && canNap -> Outcome.NAP
            r3 < 2 * gap -> Outcome.SELF_HIT
            else -> Outcome.LOAF
        }
    }

    /** Generation III "loafing" lines; one is picked at random when a disobedient Pokémon does nothing. */
    val LOAF_LINES: List<String> = listOf("{name} is loafing around!", "{name} won't obey!", "{name} turned away!", "{name} pretended not to notice!")
}
