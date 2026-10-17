# Clock, day/night, time-dependent encounters and weekly events

## Time source

`GameClock` (`engine/.../campaign/GameClock.kt`) is the only time source.
- **In the game**, it is `ZonedDateTime.now(Clock.systemDefaultZone())`, read on every query. That means:
  - The device's local wall clock and current time zone are always used, and daylight-saving changes apply.
  - If the player changes the clock or time zone, the next read picks it up.
  - No network or location permission is involved.
- **In tests and desktop automation**, a fixed clock is injected instead: `ctx.clock = GameClock.fixed(...)`, or the `clock` automation command.

### When the game re-reads the clock

The overworld re-reads the clock:
- when a map loads;
- every 10 seconds while exploring (a cheap check, not per frame);
- immediately when the app resumes (`PkmnGame.resume` calls `refreshWorldTime`).

Event Pokémon therefore appear and disappear on schedule without a restart.

## Periods

These are the Gold/Silver/Crystal hours (`pokecrystal` `constants/misc_constants.asm`: `MORN_HOUR 4`, `DAY_HOUR 10`, `NITE_HOUR 18`):

| Period | Local time |
|---|---|
| morning | 04:00–09:59 |
| day | 10:00–17:59 |
| night | 18:00–03:59 (crosses midnight) |

The options screen's **CLOCK: ALWAYS DAY** accessibility setting pins the period to day.

**Lighting.** The lighting has a fourth, purely cosmetic phase, **evening** (17:00–17:59), for a dusk tint. It never affects encounters or events. Outdoor maps are tinted multiplicatively per phase, which keeps pixels sharp and contrast intact; there is no translucent overlay. Building windows glow at night. Outdoor battle backdrops are dimmer at night.

## Encounter tables

The format is unchanged: `<method> <time> <species> <min-max> <weight>`, where time is `any`, `morning`, `day` or `night`. At a given period a roll uses that period's slots plus any `any` slots, weighted relative to their sum.

If that leaves nothing usable, the roll falls back in this order:
1. `any`
2. `day`
3. `morning`
4. `night`
5. any non-empty period

"Nothing usable" means no slots, all weights zero or below, or unknown species. A partly authored table therefore never stops encounters. Water and rod tables in the campaign use `any`.

## Recurring events: `assets/events/events.json`

Each event is a visible overworld Pokémon on a schedule:

```json
{
  "id": "mistwood_moon_dancer",
  "name": "Moonlit Dancer",
  "schedule": { "days": ["THURSDAY"], "periods": ["night"], "nightBelongsToPreviousDay": true },
  "map": "p4b_mistwood", "x": 17, "y": 19, "facing": "down",
  "species": "clefable", "level": 40, "moves": ["moonblast", "moonlight", "metronome", "cosmicpower"],
  "intro": ["A soft light is dancing on the shrine islet...", "..."],
  "music": "battle_legendary",
  "requiresFlag": null,
  "endsWhenCaught": true
}
```

### Schedule fields

`days`, `periods`, `months` and `daysOfMonth` must **all** match. An empty list means "any".

With `nightBelongsToPreviousDay` (the default), 00:00–03:59 belongs to the night that began the evening before. **"Thursday night" therefore runs from Thursday 18:00 to Friday 03:59, device-local.** Thursday 01:00 is part of Wednesday night.

### Persistence

Events use the existing save fields, so no save migration is needed:
- `vars["event:<id>:seen"]` holds the epoch day of the occurrence already met. After the player battles the Pokémon, catches it or runs away, it stays gone for the rest of that night.
- The flag `event:<id>:caught` ends a catchable event for good.

### Fairness

Missing a Thursday costs nothing: the Pokémon returns every Thursday night until it is caught.

### Validation

Invalid entries are dropped with an error and never crash the game. They include:
- an unknown day, period, species, move or map;
- a level outside 1–100;
- a duplicate id;
- an unreadable file.

### The bundled event

On Thursday nights a **Clefable (Lv. 40) dances on the shrine islet of Mistwood's moon pond**, north of Seraphine Town. The islet needs Surf, so it rewards optional exploration. Talking to it plays a short intro, then a wild battle with the legendary battle theme starts.

## Test results

All of these run in `./gradlew :engine:test` with fixed clocks; no test waits for a real Thursday.

| Requirement | Test (`TimeAndEventsTest` unless noted) | Result |
|---|---|---|
| Every period boundary (x:00 and x:59:59) | `period boundaries follow Gold Silver Crystal` | pass |
| Device time zone respected (UTC, Tokyo, Los Angeles, Sydney) | `game time uses the local zone of the injected clock`, `weekday is the device-local weekday` | pass |
| Daylight saving (London spring forward / fall back) | `daylight saving transitions keep local wall time` | pass |
| Thursday night appears; Thursday day, Wednesday night, Friday night do not; crossing midnight | `Thursday night only` | pass |
| Zone change mid-session re-evaluates | `weekday is the device-local weekday` | pass |
| Encountering, catching, re-entering; save/load during and after | `encountering hides it until next week, catching ends it, and both survive save and load` | pass |
| Invalid or missing configuration | `invalid or missing event configuration is rejected safely` | pass |
| Bundled event file is valid and placed on a walkable, free tile | `the bundled event file is valid` | pass |
| Encounters change only per configured periods; safe fallback | `encounter slots change only with configured periods and fall back safely` | pass |
| Weights respected (4,000 rolls) | `encounter weights are respected` | pass |
| Night table used at 23:00 | `WorldTest > wild encounters respect tables and time of day` | pass |
| Existing saves and options still load | `older saves without the new options still load`, `exp share default changes only for new players` | pass |

### Checked in the running desktop game

These were run under Xvfb with the `clock` automation command (see `docs/SCREENSHOTS.md`):
- Fernwick Town at 12:00, 07:00, 17:30 and 21:00 (day, morning, evening, night with lit windows).
- Mistwood on Wednesday 21:00: no event Pokémon.
- Mistwood on Thursday 21:00: the Clefable appears with sparkles. Talking to it plays the intro and starts the battle. After it is caught it is gone.

**Resume on Android** (`refreshWorldTime` on `resume`) is code-reviewed only. No device or emulator was available.
