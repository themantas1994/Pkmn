package pkmn.engine.campaign

import pkmn.engine.TestSupport
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.save.LoadResult
import pkmn.engine.save.SaveManager
import pkmn.engine.world.EncounterSlot
import pkmn.engine.world.EncounterTable
import java.io.File
import java.nio.file.Files
import java.time.*
import kotlin.test.*

class TimeAndEventsTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: String = "UTC") = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone))

    // 2026-10-15 is a Thursday.
    private val thu = LocalDate.of(2026, 10, 15)

    @Test
    fun `period boundaries follow Gold Silver Crystal`() {
        val expect = mapOf(0 to TimePeriod.NIGHT, 3 to TimePeriod.NIGHT, 4 to TimePeriod.MORNING, 9 to TimePeriod.MORNING,
            10 to TimePeriod.DAY, 17 to TimePeriod.DAY, 18 to TimePeriod.NIGHT, 23 to TimePeriod.NIGHT)
        for ((h, p) in expect) {
            assertEquals(p, TimePeriod.of(LocalTime.of(h, 0)), "$h:00")
            assertEquals(p, TimePeriod.of(LocalTime.of(h, 59, 59)), "$h:59")
        }
        assertEquals(LightPhase.EVENING, LightPhase.of(LocalTime.of(17, 30)))
        // 00:00–03:59 belongs to the previous evening.
        assertEquals(thu, TimePeriod.anchorDate(LocalDateTime.of(2026, 10, 16, 3, 59)))
        assertEquals(LocalDate.of(2026, 10, 16), TimePeriod.anchorDate(LocalDateTime.of(2026, 10, 16, 4, 0)))
    }

    @Test
    fun `light tint changes at most 4 of 255 per channel between consecutive minutes`() {
        fun ch(v: Float) = Math.round(v * 255)
        var prev = LightTint.at(LocalTime.of(23, 59))
        for (m in 0 until 24 * 60) {
            val cur = LightTint.at(LocalTime.of(m / 60, m % 60))
            for ((a, b) in listOf(prev.r to cur.r, prev.g to cur.g, prev.b to cur.b))
                assertTrue(Math.abs(ch(a) - ch(b)) <= 4, "jump at ${m / 60}:${m % 60}")
            prev = cur
        }
        val noon = LightTint.at(LocalTime.NOON)
        assertEquals(1f, noon.r); assertEquals(1f, noon.g); assertEquals(1f, noon.b)
        assertEquals(0.58f, LightTint.at(LocalTime.of(2, 0)).r)
    }

    @Test
    fun `game time uses the local zone of the injected clock`() {
        val ctx = newCtx()
        val instant = Instant.parse("2026-10-15T12:00:00Z") // noon UTC
        ctx.clock = GameClock.of(Clock.fixed(instant, ZoneId.of("UTC")))
        assertEquals("day", ctx.timeOfDay())
        ctx.clock = GameClock.of(Clock.fixed(instant, ZoneId.of("Asia/Tokyo"))) // 21:00 local
        assertEquals("night", ctx.timeOfDay())
        ctx.clock = GameClock.of(Clock.fixed(instant, ZoneId.of("America/Los_Angeles"))) // 05:00 local
        assertEquals("morning", ctx.timeOfDay())
        // Accessibility "always day".
        ctx.state.options.realTimeClock = false
        assertEquals("day", ctx.timeOfDay())
    }

    @Test
    fun `daylight saving transitions keep local wall time`() {
        // Europe/London springs forward 2026-03-29 01:00 -> 02:00 and falls back 2026-10-25 02:00 -> 01:00.
        val zone = ZoneId.of("Europe/London")
        val ctx = newCtx()
        ctx.clock = GameClock.of(Clock.fixed(Instant.parse("2026-03-29T03:30:00Z"), zone)) // 04:30 BST
        assertEquals("morning", ctx.timeOfDay())
        ctx.clock = GameClock.of(Clock.fixed(Instant.parse("2026-10-25T09:30:00Z"), zone)) // 09:30 GMT
        assertEquals("morning", ctx.timeOfDay())
        ctx.clock = GameClock.of(Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), zone)) // 10:00 GMT
        assertEquals("day", ctx.timeOfDay())
    }

    private val events by lazy { content.events }
    private val dancer by lazy { events.defs.single { it.id == "mistwood_moon_dancer" } }

    @Test
    fun `the bundled event file is valid`() {
        val strict = WorldEvents.parse(assets.resolve("events/events.json").readText(), TestSupport.dex) { content.mapOrNull(it) != null }
        assertEquals(emptyList(), strict.errors)
        assertEquals(events.defs.map { it.id }, strict.defs.map { it.id })
        val m = content.map(dancer.map)
        assertTrue(m.inBounds(dancer.x, dancer.y))
        assertTrue(m.npcs.none { it.x == dancer.x && it.y == dancer.y } && m.items.none { it.x == dancer.x && it.y == dancer.y })
        assertFalse(pkmn.engine.world.TileRules.isSolid(pkmn.engine.world.TileRules.behavior(m.tileAt(dancer.x, dancer.y))))
    }

    @Test
    fun `Thursday night only`() {
        val ctx = newCtx()
        fun active(t: ZonedDateTime) = events.isActive(dancer, ctx.state, t)
        assertTrue(active(at(2026, 10, 15, 18)), "Thu 18:00")
        assertTrue(active(at(2026, 10, 15, 23, 59)), "Thu 23:59")
        assertTrue(active(at(2026, 10, 16, 0, 30)), "Fri 00:30 is still Thursday night")
        assertTrue(active(at(2026, 10, 16, 3, 59)), "Fri 03:59")
        assertFalse(active(at(2026, 10, 16, 4, 0)), "Fri morning")
        assertFalse(active(at(2026, 10, 15, 12)), "Thu day")
        assertFalse(active(at(2026, 10, 15, 17, 59)), "Thu 17:59")
        assertFalse(active(at(2026, 10, 15, 2)), "Thu 02:00 is Wednesday night")
        assertFalse(active(at(2026, 10, 14, 22)), "Wed night")
        assertFalse(active(at(2026, 10, 16, 22)), "Fri night")
        // Every Thursday, across months and years.
        assertTrue(active(at(2026, 12, 31, 20)))
        assertTrue(active(at(2027, 1, 7, 21)))
        assertEquals(at(2026, 10, 22, 18), events.nextStart(dancer, at(2026, 10, 16, 5)))
    }

    @Test
    fun `weekday is the device-local weekday`() {
        val ctx = newCtx()
        // Thursday 20:00 in Sydney is Thursday 09:00 UTC: the event follows the device's zone.
        val sydneyThuNight = ZonedDateTime.of(2026, 10, 15, 20, 0, 0, 0, ZoneId.of("Australia/Sydney"))
        assertTrue(events.isActive(dancer, ctx.state, sydneyThuNight))
        assertFalse(events.isActive(dancer, ctx.state, sydneyThuNight.withZoneSameInstant(ZoneId.of("UTC"))))
        // Changing the zone mid-session changes the answer on the next query (nothing cached).
        ctx.clock = GameClock.of(Clock.fixed(sydneyThuNight.toInstant(), ZoneId.of("Australia/Sydney")))
        assertEquals(listOf(dancer), ctx.activeEvents(dancer.map))
        ctx.clock = GameClock.of(Clock.fixed(sydneyThuNight.toInstant(), ZoneId.of("UTC")))
        assertEquals(emptyList(), ctx.activeEvents(dancer.map))
        assertEquals(emptyList(), ctx.activeEvents("route1"))
    }

    @Test
    fun `encountering hides it until next week, catching ends it, and both survive save and load`() {
        val ctx = newCtx()
        val night = at(2026, 10, 15, 21)
        assertTrue(events.isActive(dancer, ctx.state, night))
        events.markEncountered(dancer, ctx.state, night)
        assertFalse(events.isActive(dancer, ctx.state, night))
        assertFalse(events.isActive(dancer, ctx.state, at(2026, 10, 16, 1)), "same night after midnight")
        assertTrue(events.isActive(dancer, ctx.state, at(2026, 10, 22, 19)), "returns next Thursday")

        val dir = Files.createTempDirectory("evsave").toFile()
        val mgr = SaveManager(dir, TestSupport.dex)
        mgr.save(ctx.state)
        val loaded = (mgr.load() as LoadResult.Ok).state
        assertFalse(events.isActive(dancer, loaded, night))
        assertTrue(events.isActive(dancer, loaded, at(2026, 10, 22, 19)))

        events.markCaught(dancer, loaded)
        mgr.save(loaded)
        val again = (mgr.load() as LoadResult.Ok).state
        assertFalse(events.isActive(dancer, again, at(2026, 10, 29, 19)), "caught: never returns")
    }

    @Test
    fun `invalid or missing event configuration is rejected safely`() {
        val bad = """[
          {"id":"a","name":"A","schedule":{"days":["Thorsday"]},"map":"route1","x":1,"y":1,"species":"pikachu","level":5},
          {"id":"b","name":"B","schedule":{"periods":["dusk"]},"map":"nowhere","x":1,"y":1,"species":"notamon","level":500},
          {"id":"c","name":"C","schedule":{"days":["fri"],"periods":["night"]},"map":"route1","x":1,"y":1,"species":"lapras","level":30}
        ]"""
        val ev = WorldEvents.parse(bad, TestSupport.dex) { content.mapOrNull(it) != null }
        assertEquals(listOf("c"), ev.defs.map { it.id })
        assertEquals(2, ev.errors.size)
        assertTrue(WorldEvents.parse("{not json", null) { true }.let { it.defs.isEmpty() && it.errors.isNotEmpty() })
        assertTrue(WorldEvents.parse(null, null) { true }.defs.isEmpty())
        // Short day names work.
        assertTrue(ev.isActive(ev.defs[0], newCtx().state, at(2026, 10, 16, 20)))
    }

    @Test
    fun `encounter slots change only with configured periods and fall back safely`() {
        val ctx = newCtx()
        val route = content.encounters.getValue("route1")
        val night = ctx.encounterSlots(route, "grass", "night").map { it.species }.toSet()
        val day = ctx.encounterSlots(route, "grass", "day").map { it.species }.toSet()
        assertNotEquals(night, day)
        // Methods authored only for "any" are identical at every time.
        val rods = listOf("morning", "day", "night").map { ctx.encounterSlots(route, "oldrod", it).map { s -> s.species } }
        assertEquals(1, rods.toSet().size)
        // Empty or broken period tables fall back to "any", then "day".
        val t = EncounterTable("t", mapOf(
            "grass" to mapOf("night" to emptyList(), "day" to listOf(EncounterSlot("pidgey", 2, 3, 10)), "morning" to listOf(EncounterSlot("pidgey", 2, 3, 0))),
            "surf" to mapOf("any" to listOf(EncounterSlot("tentacool", 5, 5, 3), EncounterSlot("notamon", 5, 5, 99)))))
        assertEquals(listOf("pidgey"), ctx.encounterSlots(t, "grass", "night").map { it.species })
        assertEquals(listOf("pidgey"), ctx.encounterSlots(t, "grass", "morning").map { it.species })
        assertEquals(listOf("tentacool"), ctx.encounterSlots(t, "surf", "night").map { it.species })
        assertEquals(emptyList(), ctx.encounterSlots(t, "headbutt", "day"))
    }

    @Test
    fun `encounter weights are respected`() {
        val ctx = newCtx()
        ctx.clock = GameClock.fixed(LocalDateTime.of(2026, 10, 15, 22, 0))
        val map = content.map("route1")
        val counts = HashMap<String, Int>()
        repeat(4000) { ctx.rollEncounter(map, "grass", forceEncounter = true)?.let { counts.merge(it.speciesId, 1, Int::plus) } }
        // Night table: hoothoot 45, rattata 40, spinarak 15.
        val total = counts.values.sum().toDouble()
        assertEquals(0.45, counts.getValue("hoothoot") / total, 0.04)
        assertEquals(0.15, counts.getValue("spinarak") / total, 0.03)
    }

    @Test
    fun `exp share default changes only for new players`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertFalse(pkmn.engine.save.Options().expShare)
        // An options file written before the change never stored the old default (true): it stays on.
        assertTrue(pkmn.engine.save.Options.decodeLegacyAware(json, """{"textSpeed":2}""").expShare)
        // Explicit choices are kept either way.
        assertFalse(pkmn.engine.save.Options.decodeLegacyAware(json, """{"expShare":false}""").expShare)
        assertTrue(pkmn.engine.save.Options.decodeLegacyAware(json, """{"expShare":true}""").expShare)
    }

    @Test
    fun `older saves without the new options still load`() {
        val legacy = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(pkmn.engine.save.Options.serializer(), """{"textSpeed":1,"battleStyle":"set"}""")
        assertEquals(0, legacy.animSpeed)
        assertEquals("set", legacy.battleStyle)
        assertTrue(legacy.realTimeClock)
        // A full save round trip keeps the new field.
        val ctx = newCtx()
        ctx.state.options.animSpeed = 2
        val dir = Files.createTempDirectory("oldsave").toFile()
        val mgr = SaveManager(dir, TestSupport.dex)
        mgr.save(ctx.state)
        assertEquals(2, (mgr.load() as LoadResult.Ok).state.options.animSpeed)
    }
}
