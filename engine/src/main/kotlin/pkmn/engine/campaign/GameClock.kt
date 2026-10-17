package pkmn.engine.campaign

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Source of the device's local wall-clock time. The game never keeps its own clock: every query reads the
 * operating system clock in the device's current time zone, so suspending the app, changing the clock or the
 * time zone, and daylight-saving transitions are all picked up on the next read. Tests inject a fixed clock.
 */
fun interface GameClock {
    fun now(): ZonedDateTime

    companion object {
        /** The device clock in the device's current default zone (re-read on every call, so zone changes apply). */
        fun system(): GameClock = GameClock { ZonedDateTime.now(Clock.systemDefaultZone()) }
        fun of(clock: Clock): GameClock = GameClock { ZonedDateTime.now(clock) }
        fun fixed(local: LocalDateTime, zone: ZoneId = ZoneId.of("UTC")): GameClock = GameClock { local.atZone(zone) }
    }
}

/**
 * Gold/Silver/Crystal three-period day: morning 04:00–09:59, day 10:00–17:59, night 18:00–03:59 (local time).
 * The night period crosses midnight; [anchorDate] gives the calendar day a night "belongs to" (the evening it began).
 */
enum class TimePeriod(val id: String) {
    MORNING("morning"), DAY("day"), NIGHT("night");

    companion object {
        const val MORNING_START = 4
        const val DAY_START = 10
        const val NIGHT_START = 18

        fun of(t: LocalTime): TimePeriod = when (t.hour) {
            in MORNING_START until DAY_START -> MORNING
            in DAY_START until NIGHT_START -> DAY
            else -> NIGHT
        }

        fun parse(s: String): TimePeriod? = entries.firstOrNull { it.id == s.lowercase() }

        /** The calendar date whose evening started the current night (00:00–03:59 belongs to the previous day). */
        fun anchorDate(t: LocalDateTime): LocalDate = if (t.hour < MORNING_START) t.toLocalDate().minusDays(1) else t.toLocalDate()
    }
}

/** Presentation-only lighting phase: like [TimePeriod] but with a dusk hour (17:00–17:59) for the evening tint. */
enum class LightPhase { MORNING, DAY, EVENING, NIGHT;
    companion object {
        fun of(t: LocalTime): LightPhase = when {
            t.hour == TimePeriod.NIGHT_START - 1 -> EVENING
            else -> when (TimePeriod.of(t)) { TimePeriod.MORNING -> MORNING; TimePeriod.DAY -> DAY; TimePeriod.NIGHT -> NIGHT }
        }
    }
}
