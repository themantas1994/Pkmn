package pkmn.engine.campaign

import kotlinx.serialization.json.*
import pkmn.engine.TestSupport
import pkmn.engine.battle.BattleOutcome
import pkmn.engine.battle.Choice
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.data.Stat
import pkmn.engine.mon.Nature
import pkmn.engine.mon.StatSpread
import pkmn.engine.save.LoadResult
import pkmn.engine.save.SaveManager
import pkmn.engine.world.ScriptRequest
import pkmn.engine.world.ScriptVM
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Starter, gifts and captures: Pokédex registration, nickname offers, party/PC placement, saves and migration. */
class AcquisitionTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private val dex get() = TestSupport.dex

    private fun newCtx(): GameContext = GameContext(dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    private fun withStarter(ctx: GameContext = newCtx()): GameContext {
        ctx.acquire(ctx.factory.create("cyndaquil", 5, ctx.rng))
        ctx.resolveNickname(ctx.state.party[0].uid, null)
        return ctx
    }

    /** Runs a wild battle where the player throws [ball] each turn until the battle ends; returns the battle. */
    private fun catchWild(ctx: GameContext, species: String, level: Int = 10, ball: String = "masterball", shiny: Boolean = false): pkmn.engine.battle.Battle {
        val wild = ctx.factory.create(species, level, ctx.rng, shinyOdds = if (shiny) 1 else Int.MAX_VALUE)
        val b = ctx.createWildBattle(wild, null)
        b.start()
        while (b.outcome == BattleOutcome.ONGOING) {
            assertNull(b.choose(b.player, Choice.UseItem(ball)))
            b.advance()
        }
        return b
    }

    private fun runScript(ctx: GameContext, id: String, answers: MutableList<Int>): List<ScriptRequest> {
        val vm = ScriptVM(ctx, content.scripts.getValue(id), { content.scripts[it] })
        val out = ArrayList<ScriptRequest>()
        while (true) {
            val r = vm.next() ?: break
            out.add(r)
            if (r.blocking) vm.resume(when (r) { is ScriptRequest.Ask, is ScriptRequest.Choice -> answers.removeFirstOrNull() ?: 1; else -> 0 })
        }
        return out
    }

    // ------------------------------------------------------------------ starter

    @Test
    fun `the starter is received once, registered, and offered a nickname right after`() {
        val ctx = newCtx()
        val reqs = runScript(ctx, "lab_ball_fire", mutableListOf(1))
        assertEquals(1, ctx.state.party.size)
        val starter = ctx.state.party.single()
        assertEquals("cyndaquil", starter.speciesId)
        val received = reqs.indexOfFirst { it is ScriptRequest.Say && "received Cyndaquil" in it.text }
        assertTrue(received >= 0)
        assertEquals(ScriptRequest.Nickname(starter.uid), reqs[received + 1], "the nickname offer follows the 'received' message")
        assertEquals(DexState.CAUGHT, ctx.dexState("cyndaquil"))
        assertEquals(listOf(starter.uid), ctx.state.pendingNicknames)
        // YES + name: applied; the offer is closed.
        assertNull(ctx.resolveNickname(starter.uid, "Blaze"))
        assertEquals("Blaze", starter.nickname)
        assertEquals("Cyndaquil", starter.species(dex).name)
        assertTrue(ctx.state.pendingNicknames.isEmpty())
        // Running the script again does not give a second starter.
        runScript(ctx, "lab_ball_fire", mutableListOf(1))
        assertEquals(1, ctx.state.party.size)
    }

    @Test
    fun `declining keeps the species name and invalid names change nothing`() {
        val ctx = newCtx()
        val a = ctx.acquire(ctx.factory.create("totodile", 5, ctx.rng))
        val uid = a.mon.uid
        for (bad in listOf("", "   ", "ThisNameIsTooLong", "Pika😀", "Ζεύς", "<script>")) {
            assertNotNull(ctx.resolveNickname(uid, bad), "'$bad' must be rejected")
            assertNull(a.mon.nickname)
            assertTrue(uid in ctx.state.pendingNicknames, "a rejected name leaves the offer open")
        }
        assertNull(ctx.resolveNickname(uid, null))
        assertNull(a.mon.nickname)
        assertEquals("Totodile", a.mon.displayName(dex))
        assertTrue(ctx.state.pendingNicknames.isEmpty())
    }

    @Test
    fun `nickname rules - limit, Unicode normalisation, species name and reset`() {
        assertEquals(Nicknames.Check.Ok("Émile"), Nicknames.check("Émile"), "decomposed input is NFC-normalised")
        assertEquals(Nicknames.Check.Ok("Mr. Sparky"), Nicknames.check("  Mr.   Sparky "))
        assertTrue(Nicknames.check("Twelve Chars").let { it is Nicknames.Check.Ok })
        assertTrue(Nicknames.check("Thirteen Char") is Nicknames.Check.Invalid)
        assertTrue(Nicknames.check("Nidoran♀") is Nicknames.Check.Ok)
        val ctx = withStarter()
        val mon = ctx.state.party[0]
        assertNull(ctx.rename(mon, "Cyndaquil"))
        assertNull(mon.nickname, "a name equal to the species name is no nickname")
        assertNull(ctx.rename(mon, "Ember"))
        assertEquals("Ember", mon.displayName(dex))
        assertNull(ctx.rename(mon, null))
        assertEquals("Cyndaquil", mon.displayName(dex))
    }

    // ------------------------------------------------------------------ captures

    @Test
    fun `a first capture registers the species and offers a nickname, exactly once`() {
        val ctx = withStarter()
        val b = catchWild(ctx, "pidgey")
        assertEquals(BattleOutcome.CAUGHT, b.outcome)
        assertEquals(DexState.SEEN, ctx.dexState("pidgey"), "seen when the battle starts, not yet caught")
        ctx.applyBattleResult(b, null)
        val cap = ctx.lastCapture!!
        assertTrue(cap.newSpecies); assertEquals("party", cap.where); assertFalse(cap.duplicate)
        assertEquals(DexState.CAUGHT, ctx.dexState("pidgey"))
        assertEquals(2, ctx.state.party.size)
        assertEquals(listOf(cap.mon.uid), ctx.state.pendingNicknames)
        assertEquals("ARI", cap.mon.otName)
        // Applying the same result again (a re-entered callback) adds nothing.
        ctx.applyBattleResult(b, null)
        assertTrue(ctx.lastCapture!!.duplicate)
        assertEquals(2, ctx.state.party.size)
        assertEquals(1, ctx.state.pendingNicknames.size)
        assertEquals(1, ctx.state.caught.count { it == "pidgey" })
    }

    @Test
    fun `duplicate species are offered a nickname but do not re-register`() {
        val ctx = withStarter()
        ctx.applyBattleResult(catchWild(ctx, "pidgey"), null)
        ctx.resolveNickname(ctx.lastCapture!!.mon.uid, "Wings")
        ctx.applyBattleResult(catchWild(ctx, "pidgey"), null)
        val second = ctx.lastCapture!!
        assertFalse(second.newSpecies); assertFalse(second.newForm)
        assertTrue(second.mon.uid in ctx.state.pendingNicknames)
        assertNull(ctx.resolveNickname(second.mon.uid, "Wings"))
        assertEquals(2, ctx.state.party.count { it.nickname == "Wings" }, "two Pokémon may share a nickname")
        assertNotEquals(ctx.state.party[1].uid, ctx.state.party[2].uid)
    }

    @Test
    fun `legendary capture, forms and shinies are recorded`() {
        val ctx = withStarter()
        ctx.applyBattleResult(catchWild(ctx, "lugia", 70), null)
        assertTrue(ctx.lastCapture!!.newSpecies)
        assertTrue(ctx.lastCapture!!.mon.uid in ctx.state.pendingNicknames, "legendaries get the offer too")
        ctx.applyBattleResult(catchWild(ctx, "raichualola", 20), null)
        assertTrue("raichu" in ctx.state.caught && "raichualola" in ctx.state.formsCaught)
        ctx.applyBattleResult(catchWild(ctx, "raichu", 20), null)
        assertFalse(ctx.lastCapture!!.newSpecies); assertTrue(ctx.lastCapture!!.newForm)
        ctx.applyBattleResult(catchWild(ctx, "zubat", 10, shiny = true), null)
        assertTrue("zubat" in ctx.state.shiniesCaught)
    }

    @Test
    fun `a capture with a full party goes to the PC with its nickname and Pokedex entry`() {
        val ctx = withStarter()
        repeat(5) { ctx.acquire(ctx.factory.create("rattata", 5, ctx.rng)).also { ctx.resolveNickname(it.mon.uid, null) } }
        assertEquals(6, ctx.state.party.size)
        ctx.applyBattleResult(catchWild(ctx, "growlithe", 15), null)
        val cap = ctx.lastCapture!!
        assertEquals("box:0", cap.where)
        assertEquals(6, ctx.state.party.size)
        assertNull(ctx.resolveNickname(cap.mon.uid, "Rex"))
        assertEquals("Rex", ctx.state.boxes[0].filterNotNull().single().nickname)
        assertEquals(DexState.CAUGHT, ctx.dexState("growlithe"))
    }

    @Test
    fun `when every box is full no ball can be thrown and nothing is lost`() {
        val ctx = withStarter()
        while (ctx.state.party.size < 6) ctx.state.party.add(ctx.factory.create("rattata", 5, ctx.rng))
        for (box in ctx.state.boxes) for (i in box.indices) box[i] = ctx.factory.create("rattata", 5, ctx.rng)
        assertTrue(ctx.storageFull())
        val a = ctx.acquire(ctx.factory.create("pidgey", 5, ctx.rng))
        assertNull(a.where)
        assertFalse("pidgey" in ctx.state.caught, "nothing is registered for a Pokémon that could not be stored")
        assertTrue(a.mon.uid !in ctx.state.pendingNicknames)
    }

    @Test
    fun `failed throws leave the Pokedex at seen and the party unchanged`() {
        val ctx = withStarter()
        val wild = ctx.factory.create("lugia", 70, ctx.rng)
        val b = ctx.createWildBattle(wild, null).also { it.start() }
        assertNull(b.choose(b.player, Choice.UseItem("pokeball"))); b.advance()
        if (b.outcome == BattleOutcome.ONGOING) {
            ctx.applyBattleResult(b, null)
            assertNull(ctx.lastCapture)
            assertEquals(DexState.SEEN, ctx.dexState("lugia"))
            assertEquals(1, ctx.state.party.size)
        }
    }

    // ------------------------------------------------------------------ evolution, saves, interruption

    @Test
    fun `evolution keeps nickname, nature, IVs and EVs and registers the new species`() {
        val ctx = newCtx()
        val mon = ctx.factory.create("charmander", 16, ctx.rng, nature = Nature.ADAMANT)
        ctx.acquire(mon); ctx.resolveNickname(mon.uid, "Ember")
        mon.evs[Stat.ATK] = 252; mon.evs[Stat.SPE] = 252
        val ivs = mon.ivs.copySpread(); val evs = mon.evs.copySpread()
        ctx.evolve(mon, "charmeleon")
        assertEquals("charmeleon", mon.speciesId)
        assertEquals("Ember", mon.nickname); assertEquals(Nature.ADAMANT, mon.nature)
        assertEquals(ivs, mon.ivs); assertEquals(evs, mon.evs)
        assertEquals(DexState.CAUGHT, ctx.dexState("charmeleon"))
    }

    @Test
    fun `an interrupted capture resumes at the nickname prompt after reload, without duplicates`() {
        val dir = Files.createTempDirectory("acq").toFile()
        val ctx = withStarter()
        ctx.applyBattleResult(catchWild(ctx, "eevee", 12), null)
        val uid = ctx.lastCapture!!.mon.uid
        SaveManager(dir, dex).save(ctx.state) // the game saves right after the capture is stored
        // ... the app is killed during the Pokédex screen or nickname prompt.
        val loaded = (SaveManager(dir, dex).load() as LoadResult.Ok).state
        val ctx2 = GameContext(dex, content, loaded)
        assertEquals(2, loaded.party.size)
        assertEquals(DexState.CAUGHT, ctx2.dexState("eevee"))
        assertEquals(uid, ctx2.nextPendingNickname()!!.uid)
        assertNull(ctx2.resolveNickname(uid, "Vee"))
        SaveManager(dir, dex).save(ctx2.state)
        val again = (SaveManager(dir, dex).load() as LoadResult.Ok).state
        assertEquals("Vee", again.party[1].nickname)
        assertTrue(again.pendingNicknames.isEmpty())
        assertEquals(2, again.party.size)
        assertTrue("eevee" in again.formsCaught)
    }

    @Test
    fun `save and load keep nature, IVs, EVs, nickname and Pokedex records exactly`() {
        val dir = Files.createTempDirectory("acq2").toFile()
        val ctx = withStarter()
        val mon = ctx.state.party[0]
        mon.nickname = "Blaze"; mon.nature = Nature.TIMID
        mon.evs[Stat.SPA] = 252; mon.evs[Stat.SPE] = 252; mon.evs[Stat.HP] = 6
        val ivs = mon.ivs.copySpread()
        ctx.markSeen("pikachu")
        SaveManager(dir, dex).save(ctx.state)
        val l = (SaveManager(dir, dex).load() as LoadResult.Ok).state.party[0]
        assertEquals("Blaze", l.nickname); assertEquals(Nature.TIMID, l.nature)
        assertEquals(ivs, l.ivs); assertEquals(StatSpread(6, 0, 0, 252, 0, 252), l.evs)
        assertEquals(mon.uid, l.uid)
        for (s in Stat.entries) assertEquals(mon.stat(dex, s), l.stat(dex, s))
        val st = (SaveManager(dir, dex).load() as LoadResult.Ok).state
        assertTrue("pikachu" in st.seen && "pikachu" !in st.caught)
    }

    @Test
    fun `version 1 saves migrate without losing or rerolling anything`() {
        val dir = Files.createTempDirectory("acq3").toFile()
        val ctx = withStarter()
        val a = ctx.state.party[0]
        a.nickname = "Blaze"; a.nature = Nature.BRAVE; a.evs[Stat.ATK] = 100
        val b = ctx.factory.create("pidgey", 7, ctx.rng)
        ctx.state.party.add(b)
        ctx.state.boxes[0][0] = ctx.factory.create("rattata", 3, ctx.rng)
        // Build a version-1 payload: no form/shiny/nickname-offer fields, duplicate uids, an owned species missing from caught.
        val json = Json { encodeDefaults = true; explicitNulls = false }
        var payload = json.encodeToJsonElement(pkmn.engine.save.GameState.serializer(), ctx.state).jsonObject
        payload = JsonObject(payload - setOf("formsSeen", "formsCaught", "shiniesCaught", "pendingNicknames") + mapOf(
            "caught" to JsonArray(listOf(JsonPrimitive("cyndaquil"))),
            "party" to JsonArray(payload["party"]!!.jsonArray.map { JsonObject(it.jsonObject + ("uid" to JsonPrimitive(7))) }),
        ))
        val text = json.encodeToString(JsonElement.serializer(), payload)
        val env = buildJsonObject {
            put("format", "pkmn-crystalline-save"); put("version", 1); put("savedAt", 0L)
            put("sha256", SaveManager.sha256(text)); put("payload", payload)
        }
        dir.mkdirs(); File(dir, "save.json").writeText(env.toString())
        val r = SaveManager(dir, dex).load() as LoadResult.Ok
        assertEquals(1, r.migratedFrom)
        val st = r.state
        assertEquals(2, st.party.size); assertNotNull(st.boxes[0][0])
        assertEquals(listOf("Blaze", null), st.party.map { it.nickname })
        assertEquals(Nature.BRAVE, st.party[0].nature); assertEquals(100, st.party[0].evs[Stat.ATK])
        assertEquals(a.ivs, st.party[0].ivs); assertEquals(b.ivs, st.party[1].ivs, "IVs are never rerolled on load")
        assertEquals(3, st.ownedPokemon().map { it.uid }.toSet().size, "duplicate ids are made unique")
        assertTrue(st.ownedPokemon().none { it.uid == 0L })
        assertTrue(setOf("cyndaquil", "pidgey", "rattata").all { it in st.caught && it in st.seen }, "owned species are registered")
        assertTrue("pidgey" in st.formsCaught)
        assertTrue(st.pendingNicknames.isEmpty(), "old Pokémon are not asked for a nickname again")
        // Saving re-writes at the current version.
        SaveManager(dir, dex).save(st)
        assertNull((SaveManager(dir, dex).load() as LoadResult.Ok).migratedFrom)
    }

    @Test
    fun `gift Pokemon get the nickname offer, nonick gifts and trades do not`() {
        val ctx = withStarter()
        val g = ctx.acquire(ctx.factory.create("eevee", 10, ctx.rng))
        assertTrue(g.mon.uid in ctx.state.pendingNicknames)
        val quiet = ctx.receivePokemon(ctx.factory.create("lapras", 40, ctx.rng), offerNickname = false)
        assertEquals("party", quiet)
        assertEquals(1, ctx.state.pendingNicknames.size)
        // A Pokémon released or traded away while its offer was open is simply dropped from the queue.
        ctx.state.party.removeAll { it.uid == g.mon.uid }
        assertNull(ctx.nextPendingNickname())
    }

}
