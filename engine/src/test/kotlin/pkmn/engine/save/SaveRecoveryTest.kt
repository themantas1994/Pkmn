package pkmn.engine.save

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import pkmn.engine.TestSupport
import pkmn.engine.mon.Gender
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.Nature
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Newer-version saves, backup rotation, unknown enum values and crash recovery. Temp directories only. */
class SaveRecoveryTest {
    private val dex = TestSupport.dex
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun tempDir() = Files.createTempDirectory("save-recovery").toFile()
    private fun mgr(dir: File) = SaveManager(dir, dex)
    private fun state(money: Int) = GameState(playerName = "TEST", money = money).also {
        it.party.add(TestSupport.mon("chikorita", 10)); it.setFlag("progress_$money")
    }

    /** The bytes a successful save of [money] leaves in `save.json`. */
    private fun savedBytes(money: Int): ByteArray {
        val d = tempDir()
        assertEquals(SaveResult.Saved, mgr(d).save(state(money)))
        return File(d, "save.json").readBytes()
    }

    private fun envelope(version: Int, payload: JsonObject, sha: String? = null): String {
        val text = json.encodeToString(JsonElement.serializer(), payload)
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("format", "pkmn-crystalline-save"); put("version", version); put("savedAt", 1L)
            put("sha256", sha ?: SaveManager.sha256(text)); put("payload", payload)
        })
    }

    private fun newerSave() = envelope(SaveManager.CURRENT_VERSION + 1, buildJsonObject { put("someFutureField", 1) })
    private fun snapshot(dir: File) = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }
    private fun money(r: LoadResult) = (r as LoadResult.Ok).state.money

    // ------------------------------------------------------------------ S-1: newer versions

    @Test
    fun `a newer main save with an older backup is reported unsupported and never touched`() {
        val dir = tempDir()
        File(dir, "save.json").writeText(newerSave())
        File(dir, "save.json.bak").writeBytes(savedBytes(100))
        val before = snapshot(dir)
        val r = mgr(dir).load()
        assertIs<LoadResult.Unsupported>(r)
        assertEquals(SaveManager.CURRENT_VERSION + 1, r.version)
        assertEquals(before, snapshot(dir), "load must not rename or rewrite anything")
        // Autosave / manual save refuses instead of rotating the newer save away.
        assertIs<SaveResult.Refused>(mgr(dir).save(state(5)))
        assertEquals(before, snapshot(dir), "a refused save writes nothing")
        assertTrue(mgr(dir).exists())
    }

    @Test
    fun `a newer save is recognised even if its checksum scheme differs`() {
        val dir = tempDir()
        File(dir, "save.json").writeText(envelope(SaveManager.CURRENT_VERSION + 3, buildJsonObject { put("x", 1) }, sha = "future-hash"))
        assertIs<LoadResult.Unsupported>(mgr(dir).load())
        assertTrue(dir.listFiles()!!.none { it.name.contains("corrupt") })
    }

    @Test
    fun `a newer backup or temp copy also blocks loading an older copy and saving`() {
        for (where in listOf("save.json.bak", "save.json.tmp")) {
            val dir = tempDir()
            File(dir, "save.json").writeBytes(savedBytes(100))
            File(dir, where).writeText(newerSave())
            val before = snapshot(dir)
            assertIs<LoadResult.Unsupported>(mgr(dir).load(), where)
            assertIs<SaveResult.Refused>(mgr(dir).save(state(5)), where)
            assertEquals(before, snapshot(dir), where)
        }
    }

    // ------------------------------------------------------------------ S-2: rotation only from a valid main

    @Test
    fun `valid main is rotated into the backup`() {
        val dir = tempDir()
        mgr(dir).save(state(100)); mgr(dir).save(state(200))
        val main200 = File(dir, "save.json").readBytes()
        mgr(dir).save(state(300))
        assertContentEquals(main200, File(dir, "save.json.bak").readBytes())
        assertEquals(300, money(mgr(dir).load()))
    }

    @Test
    fun `a corrupt main never replaces the only good backup`() {
        val dir = tempDir()
        mgr(dir).save(state(100)); mgr(dir).save(state(200)) // backup = 100
        val goodBackup = File(dir, "save.json.bak").readBytes()
        File(dir, "save.json").writeText("{\"format\":\"pkmn-crystalline-save\",\"vers") // truncated
        assertEquals(100, money(mgr(dir).load()))
        assertEquals(SaveResult.Saved, mgr(dir).save(state(300)))
        assertContentEquals(goodBackup, File(dir, "save.json.bak").readBytes())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("save.json.corrupt-") }, "the damaged main is kept aside")
        assertEquals(300, money(mgr(dir).load()))
        // The new main is damaged too: the old backup is still there.
        File(dir, "save.json").writeText("garbage")
        assertEquals(100, money(mgr(dir).load()))
    }

    @Test
    fun `a checksum-valid main that cannot be decoded is not rotated into the backup`() {
        val dir = tempDir()
        File(dir, "save.json.bak").writeBytes(savedBytes(100))
        File(dir, "save.json").writeText(envelope(SaveManager.CURRENT_VERSION, buildJsonObject { put("party", "not a list") }))
        assertEquals(100, money(mgr(dir).load()))
        val goodBackup = File(dir, "save.json.bak").readBytes()
        mgr(dir).save(state(300))
        assertContentEquals(goodBackup, File(dir, "save.json.bak").readBytes())
    }

    @Test
    fun `a missing main with a valid backup keeps the backup`() {
        val dir = tempDir()
        val goodBackup = savedBytes(100)
        File(dir, "save.json.bak").writeBytes(goodBackup)
        val r = mgr(dir).load()
        assertEquals(100, money(r)); assertTrue((r as LoadResult.Ok).fromBackup)
        mgr(dir).save(state(300))
        assertContentEquals(goodBackup, File(dir, "save.json.bak").readBytes())
        assertEquals(300, money(mgr(dir).load()))
    }

    // ------------------------------------------------------------------ S-3: unknown enum values

    @Test
    fun `unknown enum values are coerced without losing other progress`() {
        val dir = tempDir()
        val s = state(4321).also {
            it.party.add(TestSupport.mon("pidgey", 12)); it.addItem("potion", 7); it.badges.add(2)
            it.party[0].status = MajorStatus.SLEEP; it.party[0].sleepTurns = 2
        }
        val payload = json.encodeToJsonElement(GameState.serializer(), s).jsonObject
        val party = payload["party"]!! as kotlinx.serialization.json.JsonArray
        val odd = JsonObject(party[0].jsonObject + mapOf(
            "nature" to JsonPrimitive("SUPERCALM"), "gender" to JsonPrimitive("OTHER"),
            "status" to JsonPrimitive("DROWSY"), "mintNature" to JsonPrimitive("WEIRD")))
        val lower = JsonObject(party[1].jsonObject + mapOf("nature" to JsonPrimitive("modest")))
        val edited = JsonObject(payload + ("party" to kotlinx.serialization.json.JsonArray(listOf(odd, lower))))
        File(dir, "save.json").writeText(envelope(SaveManager.CURRENT_VERSION, edited))
        val r = mgr(dir).load()
        assertIs<LoadResult.Ok>(r, "$r")
        val m = r.state.party[0]
        assertEquals(Nature.HARDY, m.nature); assertEquals(Gender.NONE, m.gender)
        assertNull(m.status); assertEquals(0, m.sleepTurns); assertNull(m.mintNature)
        assertEquals(Nature.MODEST, r.state.party[1].nature)
        assertEquals(4321, r.state.money); assertEquals(7, r.state.itemCount("potion")); assertTrue(2 in r.state.badges)
        assertTrue(r.state.hasFlag("progress_4321")); assertEquals("pidgey", r.state.party[1].speciesId)
        assertEquals(s.party[0].ivs, m.ivs); assertEquals(s.party[0].moves.map { it.id }, m.moves.map { it.id })
        assertEquals(4, r.warnings.count { "unknown" in it }, "${r.warnings}")
    }

    @Test
    fun `non-enum type errors stay strict`() {
        val dir = tempDir()
        val payload = json.encodeToJsonElement(GameState.serializer(), state(1)).jsonObject
        val party = payload["party"]!! as kotlinx.serialization.json.JsonArray
        val bad = JsonObject(party[0].jsonObject + ("level" to JsonPrimitive("high")))
        File(dir, "save.json").writeText(envelope(SaveManager.CURRENT_VERSION, JsonObject(payload + ("party" to kotlinx.serialization.json.JsonArray(listOf(bad))))))
        assertIs<LoadResult.Failed>(mgr(dir).load())
    }

    // ------------------------------------------------------------------ S-5: interrupted writes

    /**
     * Every point a crash can interrupt `save(new)` over an existing `old` main and `older` backup. Each must load
     * either the old or the new state, never fail and never go back past the old state.
     */
    @Test
    fun `a crash at any step of the write protocol recovers the newest complete state`() {
        val older = savedBytes(100); val old = savedBytes(200); val new = savedBytes(300)
        val partial = new.copyOf(new.size / 2)
        data class Case(val name: String, val main: ByteArray?, val tmp: ByteArray?, val bak: ByteArray?, val expect: Int)
        val cases = listOf(
            Case("tmp partially written", old, partial, older, 200),
            Case("tmp written and synced, before rotation", old, new, older, 300),
            Case("main rotated to backup, tmp not yet renamed", null, new, old, 300),
            Case("main rotated, tmp partial (copy fallback crashed)", null, partial, old, 200),
            Case("committed", new, null, old, 300),
        )
        for (c in cases) {
            val dir = tempDir()
            c.main?.let { File(dir, "save.json").writeBytes(it) }
            c.tmp?.let { File(dir, "save.json.tmp").writeBytes(it) }
            c.bak?.let { File(dir, "save.json.bak").writeBytes(it) }
            assertTrue(mgr(dir).exists(), c.name)
            val r = mgr(dir).load()
            assertEquals(c.expect, money(r), c.name)
            // The next save continues from there and leaves a loadable main and backup.
            assertEquals(SaveResult.Saved, mgr(dir).save(state(400)), c.name)
            assertEquals(400, money(mgr(dir).load()), c.name)
            assertFalse(File(dir, "save.json.tmp").exists(), c.name)
            File(dir, "save.json").delete()
            assertTrue(money(mgr(dir).load()) >= 100, c.name)
        }
    }

    @Test
    fun `a valid temp save is not overlooked when main is missing but a backup exists`() {
        val dir = tempDir()
        File(dir, "save.json.bak").writeBytes(savedBytes(100))
        File(dir, "save.json.tmp").writeBytes(savedBytes(200))
        val r = mgr(dir).load() as LoadResult.Ok
        assertEquals(200, r.state.money)
        assertFalse(r.fromBackup)
        assertTrue(r.warnings.any { "interrupted" in it })
    }

    @Test
    fun `only a partial first save is no save, and garbage everywhere is preserved`() {
        val dir = tempDir()
        File(dir, "save.json.tmp").writeText("{\"format\":")
        assertFalse(mgr(dir).exists())
        assertEquals(LoadResult.NoSave, mgr(dir).load())
        File(dir, "save.json").writeText("garbage"); File(dir, "save.json.bak").writeText("garbage")
        val f = mgr(dir).load()
        assertIs<LoadResult.Failed>(f)
        assertEquals(2, f.preservedFiles.size)
    }

    @Test
    fun `exists agrees with load for a lone temp file whose payload cannot be decoded`() {
        val d = tempDir()
        val bad = buildJsonObject {
            put("party", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("bad", 1) }) })
        }
        File(d, "save.json.tmp").writeText(envelope(SaveManager.CURRENT_VERSION, bad))
        val m = mgr(d)
        assertEquals(m.exists(), m.load() !is LoadResult.NoSave)
        assertFalse(m.exists())
    }
}
