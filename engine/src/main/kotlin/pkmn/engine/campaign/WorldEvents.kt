package pkmn.engine.campaign

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import pkmn.engine.data.ContentJson
import pkmn.engine.data.Dex
import pkmn.engine.save.GameState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * When a recurring event is available. Every non-empty list must match (empty = any).
 * With [nightBelongsToPreviousDay] (default), 00:00–03:59 counts as the night that began the evening before, so
 * "Thursday night" runs Thursday 18:00 to Friday 03:59 in local time.
 */
@Serializable
data class EventSchedule(
    val days: List<String> = emptyList(),
    val periods: List<String> = emptyList(),
    val months: List<Int> = emptyList(),
    val daysOfMonth: List<Int> = emptyList(),
    val nightBelongsToPreviousDay: Boolean = true,
)

/** A visible overworld Pokémon that appears on a schedule (see assets/events/events.json and docs/EVENTS.md). */
@Serializable
data class WorldEventDef(
    val id: String,
    val name: String,
    val schedule: EventSchedule,
    val map: String,
    val x: Int,
    val y: Int,
    val species: String,
    val level: Int,
    val facing: String = "down",
    val moves: List<String> = emptyList(),
    val intro: List<String> = emptyList(),
    val music: String? = null,
    /** Story flag required before the event can appear (null = none). */
    val requiresFlag: String? = null,
    /** Once caught it never returns; otherwise it returns at the next scheduled occurrence. */
    val endsWhenCaught: Boolean = true,
)

/**
 * Data-driven recurring world events (weekday/period/month rules). All checks use the injected [GameClock], so they
 * follow the device's local calendar and can be tested at any simulated time.
 *
 * Persistence (in [GameState.vars] / flags, so no save migration is needed):
 *  * `event:<id>:seen` = epoch day of the occurrence the player already interacted with (it hides until the next one);
 *  * flag `event:<id>:caught` once caught (if [WorldEventDef.endsWhenCaught]).
 */
class WorldEvents(val defs: List<WorldEventDef>, val errors: List<String> = emptyList()) {

    /** The occurrence key (anchor date) if [def]'s schedule matches [now], else null. */
    fun occurrence(def: WorldEventDef, now: ZonedDateTime): LocalDate? {
        val local = now.toLocalDateTime()
        val period = TimePeriod.of(local.toLocalTime())
        val s = def.schedule
        val date = if (period == TimePeriod.NIGHT && s.nightBelongsToPreviousDay) TimePeriod.anchorDate(local) else local.toLocalDate()
        if (s.periods.isNotEmpty() && s.periods.none { TimePeriod.parse(it) == period }) return null
        if (s.days.isNotEmpty() && s.days.none { parseDay(it) == date.dayOfWeek }) return null
        if (s.months.isNotEmpty() && date.monthValue !in s.months) return null
        if (s.daysOfMonth.isNotEmpty() && date.dayOfMonth !in s.daysOfMonth) return null
        return date
    }

    fun isActive(def: WorldEventDef, state: GameState, now: ZonedDateTime): Boolean {
        val occ = occurrence(def, now) ?: return false
        if (def.requiresFlag != null && !state.hasFlag(def.requiresFlag)) return false
        if (def.endsWhenCaught && state.hasFlag(caughtFlag(def))) return false
        return state.vars[seenVar(def)] != occ.toEpochDay().toInt()
    }

    fun activeOn(mapId: String, state: GameState, now: ZonedDateTime): List<WorldEventDef> =
        defs.filter { it.map == mapId && isActive(it, state, now) }

    /** The player interacted with this occurrence (battle fought, fled or won): it leaves until the next one. */
    fun markEncountered(def: WorldEventDef, state: GameState, now: ZonedDateTime) {
        val occ = occurrence(def, now) ?: TimePeriod.anchorDate(now.toLocalDateTime())
        state.vars[seenVar(def)] = occ.toEpochDay().toInt()
    }

    fun markCaught(def: WorldEventDef, state: GameState) { state.setFlag(caughtFlag(def)) }

    /** Next time (at or after [now]) the event's schedule starts matching, searching up to 400 days ahead; for UI hints and tests. */
    fun nextStart(def: WorldEventDef, now: ZonedDateTime): ZonedDateTime? {
        var t = now.truncatedTo(ChronoUnit.HOURS)
        repeat(400 * 24) {
            if (occurrence(def, t) != null && !t.isBefore(now.truncatedTo(ChronoUnit.HOURS))) return t
            t = t.plusHours(1)
        }
        return null
    }

    companion object {
        fun seenVar(def: WorldEventDef) = "event:${def.id}:seen"
        fun caughtFlag(def: WorldEventDef) = "event:${def.id}:caught"

        fun parseDay(s: String): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.name.equals(s, true) || it.name.take(3).equals(s, true) }

        /**
         * Parses the event file. Invalid events (unknown day, period, species or map; bad level) are dropped with an error
         * message instead of failing the game; a missing or unreadable file means no events.
         */
        fun parse(text: String?, dex: Dex?, mapExists: (String) -> Boolean): WorldEvents {
            if (text.isNullOrBlank()) return WorldEvents(emptyList())
            val all = try { ContentJson.decodeFromString(ListSerializer(WorldEventDef.serializer()), text) }
            catch (e: Exception) { return WorldEvents(emptyList(), listOf("events file unreadable: ${e.message?.take(200)}")) }
            val errors = ArrayList<String>()
            val ok = all.filter { d ->
                val problems = buildList {
                    d.schedule.days.filter { parseDay(it) == null }.forEach { add("unknown day '$it'") }
                    d.schedule.periods.filter { TimePeriod.parse(it) == null }.forEach { add("unknown period '$it'") }
                    if (d.schedule.months.any { it !in 1..12 }) add("bad month")
                    if (d.schedule.daysOfMonth.any { it !in 1..31 }) add("bad day of month")
                    if (d.level !in 1..100) add("bad level ${d.level}")
                    if (dex != null && dex.speciesOrNull(d.species) == null) add("unknown species '${d.species}'")
                    if (dex != null) d.moves.filter { dex.moveOrNull(it) == null }.forEach { add("unknown move '$it'") }
                    if (!mapExists(d.map)) add("unknown map '${d.map}'")
                }
                if (problems.isNotEmpty()) errors.add("event ${d.id}: ${problems.joinToString()}")
                problems.isEmpty()
            }
            if (ok.map { it.id }.toSet().size != ok.size) errors.add("duplicate event ids")
            return WorldEvents(ok.distinctBy { it.id }, errors)
        }
    }
}
