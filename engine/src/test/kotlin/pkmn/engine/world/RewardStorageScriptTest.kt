package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

/** Pokémon rewards with a full party and full PC: nothing is consumed or flagged until the gift is stored, and a retry after making room works (WC-3, WC-6). */
class RewardStorageScriptTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }

    private fun ctxFull(): GameContext {
        val ctx = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))
        repeat(6) { ctx.acquire(ctx.factory.create("rattata", 5, ctx.rng), offerNickname = false) }
        for (box in ctx.state.boxes) for (i in box.indices) if (box[i] == null) box[i] = ctx.factory.create("rattata", 3, ctx.rng)
        assertTrue(ctx.storageFull())
        return ctx
    }

    private fun makeRoom(ctx: GameContext) { ctx.state.boxes[0][0] = null; assertFalse(ctx.storageFull()) }

    /** Plays [id]; choices answer [choice], every other blocking request answers 1. */
    private fun play(ctx: GameContext, id: String, choice: Int = 0) {
        val vm = ScriptVM(ctx, content.scripts.getValue(id), { content.scripts[it] })
        while (true) {
            val r = vm.next() ?: break
            if (r.blocking) vm.resume(if (r is ScriptRequest.Choice) choice else 1)
        }
    }

    private fun owns(ctx: GameContext, species: String) = ctx.state.ownedPokemon().any { it.speciesId == species }

    @Test
    fun `Jaw Fossil is kept and the quest stays open while storage is full, then revives on retry`() {
        val ctx = ctxFull()
        ctx.state.addItem("jawfossil", 1)
        ctx.updateQuest("p2_fossil_dig", "start")
        play(ctx, "p2_fossil_reviver")
        assertEquals(1, ctx.state.itemCount("jawfossil"), "fossil not consumed")
        assertFalse(owns(ctx, "tyrunt"))
        assertFalse(ctx.state.quests.getValue("p2_fossil_dig").completed, "quest not completed")
        makeRoom(ctx)
        play(ctx, "p2_fossil_reviver")
        assertEquals(0, ctx.state.itemCount("jawfossil"))
        assertTrue(owns(ctx, "tyrunt"))
        assertTrue(ctx.state.quests.getValue("p2_fossil_dig").completed)
        play(ctx, "p2_fossil_reviver") // no second Pokémon from nothing
        assertEquals(1, ctx.state.ownedPokemon().count { it.speciesId == "tyrunt" })
    }

    @Test
    fun `every fossil of both revivers is returned unspent when there is no room`() {
        for ((script, fossil, mon) in listOf(
            Triple("p2_fossil_reviver", "sailfossil", "amaura"), Triple("p2_fossil_reviver", "oldamber", "aerodactyl"),
            Triple("p4b_fossil_aide", "skullfossil", "cranidos"), Triple("p4b_fossil_aide", "armorfossil", "shieldon"),
            Triple("p4b_fossil_aide", "domefossil", "kabuto"), Triple("p4b_fossil_aide", "helixfossil", "omanyte"),
            Triple("p4b_fossil_aide", "clawfossil", "anorith"), Triple("p4b_fossil_aide", "rootfossil", "lileep"),
        )) {
            val ctx = ctxFull()
            ctx.state.addItem(fossil, 1)
            play(ctx, script)
            assertEquals(1, ctx.state.itemCount(fossil), "$fossil kept")
            assertFalse(owns(ctx, mon), mon)
            assertFalse(ctx.state.hasFlag("p4b_aide_revived"), "aide flag for $fossil")
            makeRoom(ctx)
            play(ctx, script)
            assertEquals(0, ctx.state.itemCount(fossil), "$fossil spent on success")
            assertTrue(owns(ctx, mon), mon)
        }
    }

    @Test
    fun `Nalu keeps the gift pending while storage is full and gives it once on retry`() {
        val ctx = ctxFull()
        play(ctx, "p3c_nalu", choice = 0)
        assertFalse(ctx.state.hasFlag("p3c_nalu_gift"))
        assertFalse(owns(ctx, "rowlet"))
        makeRoom(ctx)
        play(ctx, "p3c_nalu", choice = 0)
        assertTrue(ctx.state.hasFlag("p3c_nalu_gift"))
        assertEquals(1, ctx.state.ownedPokemon().count { it.speciesId == "rowlet" })
        play(ctx, "p3c_nalu", choice = 1)
        assertFalse(owns(ctx, "litten"), "only one gift")
    }

    @Test
    fun `owning the species already does not fake a Nalu gift`() {
        val ctx = ctxFull()
        ctx.state.boxes[0][0] = ctx.factory.create("rowlet", 5, ctx.rng) // the old check (hasmon) would have flagged the gift
        play(ctx, "p3c_nalu", choice = 0)
        assertFalse(ctx.state.hasFlag("p3c_nalu_gift"))
        assertEquals(1, ctx.state.ownedPokemon().count { it.speciesId == "rowlet" })
    }

    @Test
    fun `other Pokemon gifts keep their flags, items and quests until stored`() {
        class Case(val script: String, val setup: (GameContext) -> Unit, val done: (GameContext) -> Boolean, val mon: String, val kept: String? = null)
        val cases = listOf(
            Case("p3a_r11_lapras_lady", { it.state.setFlag("p3a_lapras_quest"); it.state.addItem("bigpearl", 1) }, { it.state.hasFlag("p3a_got_lapras") }, "lapras", "bigpearl"),
            Case("p4a_engineer", { it.state.addItem("machinepart", 1); it.updateQuest("p4a_machine_part", "start") },
                { it.state.quests["p4a_machine_part"]?.completed == true }, "pawmi", "machinepart"),
            Case("p3a_fm_scarf_quest", { it.state.setFlag("p3a_pip_home") }, { it.state.hasFlag("p3a_pip_reward") }, "snom"),
            Case("p4b_dig_carbink", { it.state.setFlag("p4b_dig_cleared") }, { it.state.hasFlag("p4b_got_carbink") }, "carbink"),
            Case("p4b_sol_altar", { it.state.setFlag("p4b_beat_sol_keeper") }, { it.state.hasFlag("p4b_got_sol_gift") }, "growlithehisui"),
            Case("p2_seraphine_keeper", { }, { it.state.hasFlag("p2_togepi_egg") }, "togepi"),
            Case("p1_eevee_ball", { }, { it.state.hasFlag("p1_got_eevee") }, "eevee"),
            Case("p4b_kaen_egg", { }, { it.state.hasFlag("p4b_kaen_egg") }, "larvesta"),
        )
        for (c in cases) {
            val ctx = ctxFull()
            c.setup(ctx)
            play(ctx, c.script)
            assertFalse(c.done(ctx), "${c.script}: completion recorded despite a full storage")
            assertFalse(owns(ctx, c.mon), c.script)
            c.kept?.let { assertEquals(1, ctx.state.itemCount(it), "${c.script}: $it consumed") }
            makeRoom(ctx)
            play(ctx, c.script)
            assertTrue(c.done(ctx), "${c.script}: retry after making room")
            assertTrue(owns(ctx, c.mon), c.script)
            c.kept?.let { assertEquals(0, ctx.state.itemCount(it), "${c.script}: $it spent on success") }
        }
    }

    @Test
    fun `Kaen offers the egg again if it could not be stored after the won battle`() {
        val ctx = ctxFull()
        ctx.updateQuest("p4b_trial_by_fire", "start")
        ctx.updateQuest("p4b_trial_by_fire", "done")
        play(ctx, "p4b_kaen")
        assertFalse(ctx.state.hasFlag("p4b_kaen_egg"))
        makeRoom(ctx)
        play(ctx, "p4b_kaen")
        assertTrue(ctx.state.hasFlag("p4b_kaen_egg"))
        assertTrue(owns(ctx, "larvesta"))
        play(ctx, "p4b_kaen")
        assertEquals(1, ctx.state.ownedPokemon().count { it.speciesId == "larvesta" })
    }
}
