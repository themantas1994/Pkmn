package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameClock
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import java.time.LocalDateTime
import kotlin.test.*

/** Side-quest chain A (PKM-75): the seven Linden Field Notes volumes run end to end through the compiled scripts. */
class LindenFieldNotesTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))
    private val quest = "p5a_linden_notes"

    private fun at(ctx: GameContext, hour: Int) { ctx.clock = GameClock.fixed(LocalDateTime.of(2026, 10, 15, hour, 0)) }
    private fun badges(ctx: GameContext, n: Int) { ctx.state.badges.clear(); for (i in 1..n) ctx.state.badges.add(i) }
    private fun step(ctx: GameContext) = ctx.state.quests[quest]?.step ?: -1
    private fun item(ctx: GameContext, id: String) = ctx.state.bag[id] ?: 0

    /** Runs a script; [answers] feed `choice` prompts in order, battles are won. Returns everything said. */
    private fun run(ctx: GameContext, id: String, answers: List<Int> = emptyList(), win: Boolean = true): String {
        val vm = ScriptVM(ctx, content.scripts.getValue(id), { content.scripts[it] })
        val said = ArrayList<String>()
        val picks = answers.iterator()
        while (true) {
            val r = vm.next() ?: break
            if (r is ScriptRequest.Say) said.add(r.text)
            if (!r.blocking) continue
            when (r) {
                is ScriptRequest.Choice -> vm.resume(if (picks.hasNext()) picks.next() else 0)
                is ScriptRequest.TrainerBattle -> vm.resume(if (win) 1 else 0)
                else -> vm.resume(1)
            }
        }
        return said.joinToString(" ")
    }

    private fun started(ctx: GameContext) { badges(ctx, 2); run(ctx, "p5a_lab_aide") }

    @Test
    fun `aide waits for the Tide Badge then starts the quest once`() {
        val ctx = newCtx()
        badges(ctx, 1)
        assertTrue(run(ctx, "p5a_lab_aide").contains("Tide Badge"))
        assertEquals(-1, step(ctx))
        badges(ctx, 2)
        assertTrue(run(ctx, "p5a_lab_aide").contains("vol. 1"))
        assertEquals(0, step(ctx))
        run(ctx, "p5a_lab_aide")
        assertEquals(0, step(ctx), "second talk must not restart or advance")
    }

    @Test
    fun `vol 1 needs a bonded starter`() {
        val ctx = newCtx()
        started(ctx)
        ctx.receivePokemon(ctx.factory.create("totodile", 20, ctx.rng).also { it.friendship = 60 })
        run(ctx, "p5a_brindle_contact")
        assertEquals(0, step(ctx))
        assertEquals(0, item(ctx, "ether"))
        ctx.state.party.first { it.speciesId == "totodile" }.friendship = 200
        run(ctx, "p5a_brindle_contact")
        assertEquals(1, step(ctx))
        assertEquals(3, item(ctx, "ether"))
        run(ctx, "p5a_brindle_contact")
        assertEquals(3, item(ctx, "ether"), "paid once")
    }

    @Test
    fun `a later contact stays shut until its volume`() {
        val ctx = newCtx()
        started(ctx)
        badges(ctx, 8)
        run(ctx, "p5a_lumen_contact")
        run(ctx, "p5a_thesis_contact", listOf(1))
        assertEquals(0, step(ctx))
    }

    @Test
    fun `vol 2 photo needs night and a seen Joltik`() {
        val ctx = newCtx()
        started(ctx); ctx.state.quests.getValue(quest).step = 1; badges(ctx, 3)
        at(ctx, 12); run(ctx, "p5a_r7_camera")
        assertFalse(ctx.state.hasFlag("p5a_photo"))
        at(ctx, 22); run(ctx, "p5a_r7_camera")
        assertFalse(ctx.state.hasFlag("p5a_photo"), "Joltik not seen yet")
        ctx.state.seen.add("joltik"); run(ctx, "p5a_r7_camera")
        assertTrue(ctx.state.hasFlag("p5a_photo"))
        run(ctx, "p5a_voltara_contact")
        assertEquals(2, step(ctx))
        assertEquals(5, item(ctx, "escaperope"))
    }

    @Test
    fun `vol 3 accepts a revived fossil in the party only`() {
        val ctx = newCtx()
        started(ctx); ctx.state.quests.getValue(quest).step = 2; badges(ctx, 4)
        run(ctx, "p5a_lumen_contact")
        assertEquals(2, step(ctx))
        ctx.receivePokemon(ctx.factory.create("kabuto", 30, ctx.rng))
        run(ctx, "p5a_lumen_contact")
        assertEquals(3, step(ctx))
        assertEquals(3, item(ctx, "ether"))
    }

    @Test
    fun `vol 4 needs a reading in each period and pays an egg plus Everstone`() {
        val ctx = newCtx()
        started(ctx); ctx.state.quests.getValue(quest).step = 3; badges(ctx, 5)
        for (h in listOf(7, 7, 12, 22)) { at(ctx, h); run(ctx, "p5a_mist_post") }
        assertTrue(ctx.state.hasFlag("p5a_mist_m") && ctx.state.hasFlag("p5a_mist_d") && ctx.state.hasFlag("p5a_mist_n"))
        val before = ctx.state.party.size
        run(ctx, "p5a_seraphine_contact")
        assertEquals(4, step(ctx))
        assertEquals(1, item(ctx, "everstone"))
        assertEquals(before + 1, ctx.state.party.size)
    }

    @Test
    fun `vol 4 pays nothing and keeps the step when party and boxes are full`() {
        val ctx = newCtx()
        started(ctx); ctx.state.quests.getValue(quest).step = 3; badges(ctx, 5)
        listOf("p5a_mist_m", "p5a_mist_d", "p5a_mist_n").forEach { ctx.state.setFlag(it) }
        while (ctx.state.party.size < 6) ctx.state.party.add(ctx.factory.create("rattata", 5, ctx.rng))
        for (b in ctx.state.boxes) for (i in b.indices) b[i] = ctx.factory.create("rattata", 5, ctx.rng)
        run(ctx, "p5a_seraphine_contact")
        assertEquals(3, step(ctx))
        assertEquals(0, item(ctx, "everstone"))
    }

    @Test
    fun `vol 5 sensors are set by day and read only after dark`() {
        val ctx = newCtx()
        started(ctx); ctx.state.quests.getValue(quest).step = 4; badges(ctx, 6)
        val sensors = listOf("p5a_sensor_city", "p5a_sensor_trail", "p5a_sensor_route9")
        at(ctx, 23); sensors.forEach { run(ctx, it) }
        assertFalse(ctx.state.hasFlag("p5a_sens_city"), "no setting at night")
        at(ctx, 12); sensors.forEach { run(ctx, it) }
        sensors.forEach { run(ctx, it) }
        assertFalse(ctx.state.hasFlag("p5a_sens_city_read"), "no reading by day")
        run(ctx, "p5a_ashfall_contact")
        assertEquals(4, step(ctx))
        at(ctx, 22); sensors.forEach { run(ctx, it) }
        run(ctx, "p5a_ashfall_contact")
        assertEquals(5, step(ctx))
        assertEquals(5, item(ctx, "escaperope"))
    }

    @Test
    fun `vol 6 markers then report`() {
        val ctx = newCtx()
        started(ctx); ctx.state.quests.getValue(quest).step = 5; badges(ctx, 7)
        run(ctx, "p5a_marker_1"); run(ctx, "p5a_marker_2")
        run(ctx, "p5a_frostmere_contact")
        assertEquals(5, step(ctx))
        run(ctx, "p5a_marker_3"); run(ctx, "p5a_frostmere_contact")
        assertEquals(6, step(ctx))
    }

    @Test
    fun `vol 7 thesis resumes after a wrong answer and ends the chain with a win`() {
        val ctx = newCtx()
        started(ctx); ctx.state.quests.getValue(quest).step = 6; badges(ctx, 8)
        run(ctx, "p5a_thesis_contact", listOf(1, 0, 0, 2))  // Q4 wrong
        assertEquals(3, ctx.state.vars["p5a_q"])
        assertFalse(ctx.state.quests.getValue(quest).completed)
        run(ctx, "p5a_thesis_contact", listOf(1, 1), win = false)  // Q4 right, Q5 right, battle lost
        assertEquals(5, ctx.state.vars["p5a_q"])
        assertFalse(ctx.state.quests.getValue(quest).completed, "a lost battle must not finish the chain")
        run(ctx, "p5a_thesis_contact")
        assertTrue(ctx.state.quests.getValue(quest).completed)
        assertEquals(1, item(ctx, "abilitycapsule"))
        assertTrue(ctx.state.hasFlag("p5a_researchers_pin"))
        run(ctx, "p5a_thesis_contact")
        assertEquals(1, item(ctx, "abilitycapsule"), "paid once")
    }

    @Test
    fun `professor thanks the player after the chain`() {
        val ctx = newCtx()
        ctx.state.setFlag("got_starter"); ctx.state.setFlag("got_pokedex"); ctx.state.setFlag("p5a_researchers_pin")
        assertTrue(run(ctx, "lab_professor").contains("field notes"))
    }
}
