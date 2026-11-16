package pkmn.engine.save

import pkmn.engine.TestSupport
import pkmn.engine.mon.Pokemon
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Load-time repair and refusal rules of [SaveValidator] / [SaveManager]. Temp directories only. */
class SaveValidatorTest {
    private val dex = TestSupport.dex

    private fun tempDir() = Files.createTempDirectory("save-validator").toFile()
    private fun snapshot(dir: File) = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }

    private fun stateWith(species: String): GameState = GameState(playerName = "TEST").also {
        it.party.add(TestSupport.mon("chikorita", 10).also { m -> m.speciesId = species })
    }

    @Test
    fun `a save with an unknown species is refused without touching any file`() {
        val dir = tempDir()
        assertEquals(SaveResult.Saved, SaveManager(dir).save(stateWith("zzzmon")))
        val before = snapshot(dir)
        val r = SaveManager(dir, dex).load()
        assertIs<LoadResult.Failed>(r)
        assertTrue(r.preservedFiles.isEmpty(), "nothing is renamed")
        assertTrue("zzzmon" in r.reason, "the message names the species: ${r.reason}")
        assertEquals(before, snapshot(dir), "load must not rename, rewrite or move anything")
        assertTrue(dir.listFiles()!!.none { it.name.contains("corrupt") })
    }

    @Test
    fun `an unknown species also blocks an older backup from being loaded silently`() {
        val dir = tempDir()
        val mgr = SaveManager(dir)
        assertEquals(SaveResult.Saved, mgr.save(GameState(playerName = "OLD").also { it.party.add(TestSupport.mon("chikorita", 10)) }))
        assertEquals(SaveResult.Saved, mgr.save(stateWith("zzzmon"))) // the first save becomes the backup
        val before = snapshot(dir)
        assertIs<LoadResult.Failed>(SaveManager(dir, dex).load())
        assertEquals(before, snapshot(dir))
    }

    @Test
    fun `the validator reports unloadable species, repair leaves them alone and load still refuses the file`() {
        val s = stateWith("zzzmon")
        val v = SaveValidator(dex)
        assertEquals(listOf("zzzmon"), v.unknownSpecies(s))
        v.repair(s) // must not throw: it must not call species(dex) on the unknown id
        assertEquals(listOf("zzzmon"), s.party.map { it.speciesId }, "repair neither renames nor drops the Pokémon")
        assertEquals(listOf("zzzmon"), v.unknownSpecies(s), "still unloadable after repair")

        val dir = tempDir()
        assertEquals(SaveResult.Saved, SaveManager(dir).save(stateWith("zzzmon")))
        val before = snapshot(dir)
        val r = SaveManager(dir, dex).load()
        assertIs<LoadResult.Failed>(r)
        assertEquals(before, snapshot(dir), "the refused load leaves the file unchanged")
    }

    @Test
    fun `saving over an unloadable main save is refused and leaves the main and the older backup untouched`() {
        val dir = tempDir()
        val plain = SaveManager(dir) // no Dex: can write the unknown species
        assertEquals(SaveResult.Saved, plain.save(GameState(playerName = "OLD").also { it.party.add(TestSupport.mon("chikorita", 10)) }))
        assertEquals(SaveResult.Saved, plain.save(stateWith("zzzmon"))) // main is now unloadable, .bak is the older save
        val main = File(dir, "save.json"); val bak = File(dir, "save.json.bak")
        assertTrue(main.exists() && bak.exists())
        val mainBytes = main.readBytes(); val bakBytes = bak.readBytes()
        val before = snapshot(dir)

        // The player declined to load, started a new game and the game now tries to save it.
        val r = SaveManager(dir, dex).save(GameState(playerName = "NEW").also { it.party.add(TestSupport.mon("chikorita", 5)) })

        assertIs<SaveResult.Refused>(r)
        assertTrue("zzzmon" in r.reason, "the message names the species: ${r.reason}")
        assertContentEquals(mainBytes, main.readBytes(), "main save unchanged")
        assertContentEquals(bakBytes, bak.readBytes(), "older backup unchanged")
        assertEquals(before, snapshot(dir), "no file added, renamed or removed (no .tmp, no .corrupt)")
    }

    @Test
    fun `saving over a loadable main save still rotates it into the backup`() {
        val dir = tempDir()
        val mgr = SaveManager(dir, dex)
        assertEquals(SaveResult.Saved, mgr.save(GameState(playerName = "A").also { it.party.add(TestSupport.mon("chikorita", 10)) }))
        assertEquals(SaveResult.Saved, mgr.save(GameState(playerName = "B").also { it.party.add(TestSupport.mon("chikorita", 11)) }))
        assertTrue(File(dir, "save.json.bak").exists())
    }

    @Test
    fun `an unknown form of a known species is repaired to its base species`() {
        val s = stateWith("chikorita9")
        val v = SaveValidator(dex)
        assertTrue(v.unknownSpecies(s).isEmpty())
        val w = v.repair(s)
        assertEquals("chikorita", s.party[0].speciesId)
        assertTrue(w.any { "chikorita9" in it && "replaced" in it }, "warning says replaced, not kept: $w")
    }

    // ------------------------------------------------------------------ F4: unknown held item

    @Test
    fun `an unknown held item is moved to the bag, not deleted`() {
        val s = GameState(playerName = "TEST")
        s.party.add(TestSupport.mon("chikorita", 10, item = null).also { it.heldItem = "zzz" })
        val w = SaveValidator(dex).repair(s)
        assertNull(s.party[0].heldItem)
        assertEquals(1, s.bag["zzz"])
        assertTrue(w.any { "zzz" in it && "bag" in it }, "$w")
    }

    @Test
    fun `an unknown held item adds to an existing stack`() {
        val s = GameState(playerName = "TEST")
        s.bag["zzz"] = 2
        s.party.add(TestSupport.mon("chikorita", 10).also { it.heldItem = "zzz" })
        SaveValidator(dex).repair(s)
        assertEquals(3, s.bag["zzz"])
    }

    // ------------------------------------------------------------------ F5: structure

    private fun mons(n: Int) = List(n) { TestSupport.mon("chikorita", 10).also { m -> m.uid = it + 1L } }

    @Test
    fun `a short box is padded to thirty slots`() {
        val s = GameState(playerName = "TEST")
        s.party.add(mons(1)[0])
        s.boxes[3] = MutableList(10) { null }
        SaveValidator(dex).repair(s)
        assertEquals(16, s.boxes.size)
        assertTrue(s.boxes.all { it.size == 30 })
    }

    @Test
    fun `missing boxes are added`() {
        val s = GameState(playerName = "TEST")
        s.party.add(mons(1)[0])
        s.boxes.clear()
        SaveValidator(dex).repair(s)
        assertEquals(16, s.boxes.size)
        assertTrue(s.boxes.all { it.size == 30 && it.all { m -> m == null } })
    }

    @Test
    fun `a box longer than thirty moves its overflow into free slots`() {
        val s = GameState(playerName = "TEST")
        val ms = mons(3)
        s.party.add(ms[0])
        s.boxes[0] = MutableList(32) { null }
        s.boxes[0][31] = ms[1]; s.boxes[0][5] = ms[2]
        val w = SaveValidator(dex).repair(s)
        assertTrue(s.boxes.all { it.size == 30 })
        assertEquals(setOf(ms[1], ms[2]), s.boxes.flatten().filterNotNull().toSet(), "nothing lost")
        assertEquals(2, s.boxes.flatten().filterNotNull().size)
        assertTrue(w.any { "box" in it.lowercase() }, "$w")
    }

    @Test
    fun `a party of seven keeps six and moves the seventh to the PC`() {
        val s = GameState(playerName = "TEST")
        val ms = mons(7)
        s.party.addAll(ms)
        val w = SaveValidator(dex).repair(s)
        assertEquals(ms.take(6), s.party)
        assertSame(ms[6], s.boxes.flatten().filterNotNull().single())
        assertTrue(w.any { "party" in it.lowercase() }, "$w")
    }

    @Test
    fun `with every box full an overflow party member gets a new box and is kept`() {
        val s = GameState(playerName = "TEST")
        s.boxes.clear()
        repeat(16) { s.boxes.add(MutableList<Pokemon?>(30) { TestSupport.mon("chikorita", 10) }) }
        val ms = mons(7)
        s.party.addAll(ms)
        val w = SaveValidator(dex).repair(s)
        assertEquals(6, s.party.size)
        assertEquals(17, s.boxes.size, "a 17th box was appended")
        assertTrue(s.boxes.all { it.size == 30 })
        assertTrue(s.boxes.take(16).all { b -> b.none { it == null } }, "the full boxes were not changed")
        assertSame(ms[6], s.boxes[16][0], "the overflow Pokémon is kept in the new box")
        assertEquals(1, s.boxes[16].count { it != null })
        assertTrue(w.any { "party" in it.lowercase() }, "$w")
    }

    @Test
    fun `a Pokemon with five moves keeps the first four`() {
        val s = GameState(playerName = "TEST")
        val m = TestSupport.mon("chikorita", 10, moves = listOf("tackle", "growl", "razorleaf", "synthesis", "leechseed"))
        s.party.add(m)
        val w = SaveValidator(dex).repair(s)
        assertEquals(listOf("tackle", "growl", "razorleaf", "synthesis"), m.moves.map { it.id })
        assertTrue(w.any { "moves" in it.lowercase() }, "$w")
    }

    @Test
    fun `a well formed state produces no structural warnings`() {
        val s = GameState(playerName = "TEST")
        s.party.addAll(mons(6))
        val w = SaveValidator(dex).repair(s)
        assertTrue(w.none { "box" in it.lowercase() || "party" in it.lowercase() || "moves" in it.lowercase() }, "$w")
    }
}
