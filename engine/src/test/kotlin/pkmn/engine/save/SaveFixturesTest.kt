package pkmn.engine.save

import pkmn.engine.TestSupport
import pkmn.engine.mon.Gender
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.Nature
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** The migration chain run on hand-authored saves of every historical schema (see src/test/resources/saves/README.md). */
class SaveFixturesTest {
    private val dex = TestSupport.dex

    private fun loadFixture(version: Int): LoadResult.Ok {
        val dir = Files.createTempDirectory("save-fixture").toFile()
        val bytes = javaClass.getResourceAsStream("/saves/v$version.json")!!.use { it.readBytes() }
        File(dir, "save.json").writeBytes(bytes)
        val r = SaveManager(dir, dex).load()
        assertIs<LoadResult.Ok>(r, "fixture v$version: $r")
        assertFalse(r.fromBackup)
        return r
    }

    /** Progress that every version stores the same way. */
    private fun assertCommonProgress(s: GameState) {
        assertEquals("ARI", s.playerName); assertEquals("KAI", s.rivalName); assertEquals(4242, s.trainerId)
        assertEquals(2450, s.money); assertEquals(12, s.coins)
        assertEquals(listOf("chikorita", "pidgey"), s.party.map { it.speciesId })
        val leafy = s.party[0]
        assertEquals("LEAFY", leafy.nickname); assertEquals(14, leafy.level); assertEquals(Nature.MODEST, leafy.nature)
        assertEquals(Gender.FEMALE, leafy.gender); assertEquals(20, leafy.ivs.hp); assertEquals(30, leafy.evs.spa)
        assertEquals(listOf("tackle", "growl", "razorleaf"), leafy.moves.map { it.id }); assertEquals(5, leafy.metLevel)
        assertEquals(112, leafy.friendship); assertEquals(2035, leafy.exp)
        val pidgey = s.party[1]
        assertEquals(MajorStatus.POISON, pidgey.status); assertEquals("antidote", pidgey.heldItem); assertEquals(Nature.JOLLY, pidgey.nature)
        assertEquals("sentret", s.boxes[0][3]?.speciesId)
        assertEquals(16, s.boxes.size); assertTrue(s.boxes.all { it.size == 30 })
        assertEquals(3, s.itemCount("potion")); assertEquals(5, s.itemCount("pokeball"))
        assertEquals(setOf("got_starter", "beat_rival_1"), s.flags); assertEquals(4, s.vars["story"]); assertEquals(setOf(0), s.badges)
        assertEquals(setOf("rival_1", "youngster_joey"), s.defeatedTrainers); assertEquals(1, s.quests["egg"]?.step)
        assertEquals("route30", s.map); assertEquals(7, s.x); assertEquals(12, s.y); assertEquals("up", s.facing)
        assertEquals(3723000L, s.playTimeMs); assertEquals(4180L, s.steps); assertEquals(1234567L, s.rngState)
        assertEquals(setOf("new_bark_town", "cherrygrove_city"), s.visited)
        assertEquals("set", s.options.battleStyle); assertTrue(s.options.expShare); assertEquals(1, s.options.animSpeed)
        // Pokédex: everything owned is caught (sentret was missing from the old caught list), nothing seen is lost.
        assertTrue(s.caught.containsAll(listOf("chikorita", "pidgey", "sentret")))
        assertTrue("rattata" in s.seen)
        // Every owned Pokémon has a unique non-zero uid.
        val uids = s.ownedPokemon().map { it.uid }
        assertTrue(uids.none { it == 0L }); assertEquals(uids.size, uids.toSet().size)
    }

    private fun assertSurvivesResave(r: LoadResult.Ok) {
        val dir = Files.createTempDirectory("save-fixture-resave").toFile()
        assertEquals(SaveResult.Saved, SaveManager(dir, dex).save(r.state))
        val again = SaveManager(dir, dex).load() as LoadResult.Ok
        assertNull(again.migratedFrom)
        assertCommonProgress(again.state)
    }

    @Test
    fun `version 1 fixture migrates through 2 and 3`() {
        val r = loadFixture(1)
        assertEquals(1, r.migratedFrom)
        assertCommonProgress(r.state)
        assertTrue(r.state.ownedPokemon().none { it.outsider }) // 2 -> 3: outsider defaults to false
        assertNull(r.state.party[0].mintNature)
        assertTrue(r.state.formsCaught.containsAll(listOf("chikorita", "pidgey", "sentret"))) // rebuilt from owned Pokémon
        assertSurvivesResave(r)
    }

    @Test
    fun `version 2 fixture migrates to 3 and repairs pre-fix shop TMs`() {
        val r = loadFixture(2)
        assertEquals(2, r.migratedFrom)
        assertCommonProgress(r.state)
        assertTrue(r.state.ownedPokemon().none { it.outsider })
        assertEquals(Nature.TIMID, r.state.party[0].mintNature)
        assertEquals(1, r.state.itemCount("tm:thunderbolt")); assertEquals(0, r.state.itemCount("tmthunderbolt"))
        assertEquals(listOf(7001L, 7002L, 7003L), r.state.ownedPokemon().map { it.uid })
        assertEquals(listOf(7003L), r.state.pendingNicknames)
        assertSurvivesResave(r)
    }

    @Test
    fun `version 3 fixture migrates to 4`() {
        val r = loadFixture(3)
        assertEquals(3, r.migratedFrom)
        assertNull(r.state.pendingTrade)
        assertTrue(r.state.ownedPokemon().all { it.originalMetLevel == null })
        assertTrue(r.notices.isEmpty())
        assertCommonProgress(r.state)
        assertTrue(r.state.party[0].outsider); assertFalse(r.state.party[1].outsider)
        assertEquals(1, r.state.itemCount("tm:thunderbolt"))
        assertSurvivesResave(r)
    }

    @Test
    fun `version 4 fixture loads and its committed trade journal survives the load`() {
        val r = loadFixture(4)
        assertNull(r.migratedFrom)
        assertCommonProgress(r.state)
        val pidgey = r.state.party[1]
        assertEquals("MIO", pidgey.otName); assertTrue(pidgey.outsider); assertEquals(3, pidgey.originalMetLevel)
        // COMMITTED: kept until the partner links again (a restart decides nothing). The fixture predates
        // partnerTrainerId, so it matches the partner by name.
        val j = assertNotNull(r.state.pendingTrade)
        assertEquals(TradeJournalState.COMMITTED, j.state); assertEquals("MIO", j.partnerName); assertNull(j.partnerTrainerId)
        assertEquals(r.tradeJournal, j)
        assertEquals(listOf("Your link trade with MIO is done on your side. Link with MIO again so their game can finish it too."), r.notices)
        assertSurvivesResave(r)
    }

    @Test
    fun `a version 4 save is refused as unsupported by code that only knows version 3`() {
        val dir = Files.createTempDirectory("save-v4-old").toFile()
        val ok = loadFixture(4)
        assertEquals(SaveResult.Saved, SaveManager(dir, dex).save(ok.state))
        val old = SaveManager(dir, dex, currentVersion = 3)
        val r = old.load()
        assertIs<LoadResult.Unsupported>(r); assertEquals(4, r.version)
        // The old code must not write over it either, and the file is untouched.
        val before = File(dir, "save.json").readBytes()
        assertIs<SaveResult.Refused>(old.save(GameState()))
        assertContentEquals(before, File(dir, "save.json").readBytes())
    }
}
