package pkmn.game.battle

import pkmn.engine.battle.*
import pkmn.engine.data.Dex
import pkmn.engine.mon.MoveSlot
import pkmn.engine.mon.Pokemon
import pkmn.engine.mon.PokemonFactory
import pkmn.engine.util.Rng
import kotlin.test.*

/** Drives [BattleFlow] the way the battle screen does, presenting events instantly. */
class BattleFlowTest {
    companion object {
        val dex: Dex by lazy { Dex.loadDefault() }
        val factory by lazy { PokemonFactory(dex) }
    }

    private fun mon(species: String, level: Int, moves: List<String>): Pokemon =
        factory.create(species, level, Rng(species.hashCode().toLong()), moves = moves)

    private fun battle(player: List<Pokemon>, foe: List<Pokemon>, setMode: Boolean = false, wild: Boolean = false): Battle {
        val s0 = Side(0, "Player", player, Controller.PLAYER, dex)
        val s1 = Side(1, if (wild) "Wild" else "Rival", foe, if (wild) Controller.WILD else Controller.AI, dex, aiLevel = 1)
        val b = Battle(dex, listOf(s0, s1), BattleRules(isTrainerBattle = !wild, setMode = setMode, canRun = wild), seed = 3)
        b.start()
        return b
    }

    private fun strongParty(n: Int = 2) = List(n) { i -> mon(if (i == 0) "mewtwo" else "pikachu", 70, listOf("psychic", "thunderbolt")) }
    private fun weakFoes(n: Int) = List(n) { i -> mon(listOf("rattata", "pidgey", "sentret", "zubat", "spearow", "ekans")[i], 5, listOf("tackle")) }

    /** Indicator state as the screen tracks it: fainted flags updated only by presented Faint events. */
    private class Presenter(val flow: BattleFlow) {
        val fainted = Array(2) { s -> BooleanArray(flow.battle.sides[s].mons.size) }
        val seen = ArrayList<BattleEvent>()
        fun drain() {
            while (true) {
                val e = flow.nextEvent() ?: break
                seen.add(e)
                if (e is BattleEvent.Faint) fainted[e.side][e.teamIndex] = true
                // A fainted foe is never shown as the active Pokémon once its faint is presented.
                if (e is BattleEvent.SwitchIn) assertFalse(fainted[e.side][e.teamIndex], "fainted Pokémon sent out")
            }
        }
    }

    /** Runs to the end with [onShift] answering the Shift prompt. Returns the phases visited. */
    private fun play(flow: BattleFlow, onShift: (BattleFlow) -> Unit = { it.answerShift(false) }): List<BattleFlow.Phase> {
        val p = Presenter(flow)
        val phases = ArrayList<BattleFlow.Phase>()
        flow.startPresenting()
        var guard = 0
        while (guard++ < 200) {
            p.drain()
            val ph = flow.settle()
            phases.add(ph)
            when (ph) {
                BattleFlow.Phase.CHOOSE_ACTION -> assertNull(flow.submit(Choice.UseMove(0)))
                BattleFlow.Phase.SHIFT_PROMPT -> {
                    // Indicators already show the fainted foe when the prompt appears.
                    assertEquals(flow.battle.opponent.mons.count { it.fainted }, p.fainted[1].count { it })
                    onShift(flow)
                }
                BattleFlow.Phase.FORCED_SWITCH -> assertNull(flow.forcedPick(flow.battle.player.mons.indexOfFirst { !it.fainted }))
                BattleFlow.Phase.NEXT_MON_PROMPT -> flow.answerNextMon(true)
                BattleFlow.Phase.ENDING -> { assertNotNull(flow.takeOutcome()); assertNull(flow.takeOutcome()); return phases }
                BattleFlow.Phase.PRESENTING -> {}
                else -> fail("unexpected phase $ph")
            }
        }
        fail("battle did not finish: ${phases.takeLast(10)}")
    }

    @Test
    fun `shift mode prompts once per foe replacement and finishes once`() {
        for (n in 1..6) {
            val flow = BattleFlow(battle(strongParty(), weakFoes(n)))
            val phases = play(flow)
            assertEquals(n - 1, phases.count { it == BattleFlow.Phase.SHIFT_PROMPT }, "party of $n")
            assertEquals(BattleOutcome.WIN, flow.battle.outcome)
            assertEquals(1, flow.battle.events.count { it is BattleEvent.End })
            assertEquals(BattleFlow.Phase.DONE, flow.phase)
        }
    }

    @Test
    fun `set mode never prompts`() {
        val flow = BattleFlow(battle(strongParty(), weakFoes(4), setMode = true))
        val phases = play(flow)
        assertTrue(BattleFlow.Phase.SHIFT_PROMPT !in phases)
        assertEquals(BattleOutcome.WIN, flow.battle.outcome)
    }

    @Test
    fun `shift yes opens the party, cancel returns to the prompt, invalid picks are rejected`() {
        val flow = BattleFlow(battle(strongParty(3), weakFoes(2)))
        flow.battle.player.mons[2].mon.hp = 0 // pikachu #2 fainted in reserve
        var asked = 0
        play(flow) { f ->
            asked++
            assertEquals("Pidgey", f.upcomingFoeName())
            f.answerShift(true)
            assertEquals(BattleFlow.Phase.SHIFT_PARTY, f.phase)
            assertNull(f.shiftPick(null))
            assertEquals(BattleFlow.Phase.SHIFT_PROMPT, f.phase)
            f.answerShift(true)
            assertNotNull(f.shiftPick(2), "fainted Pokémon")
            assertNotNull(f.shiftPick(0), "already in battle")
            assertEquals(BattleFlow.Phase.SHIFT_PARTY, f.phase)
            assertNull(f.shiftPick(1))
            assertEquals(BattleFlow.Phase.PRESENTING, f.phase)
        }
        assertEquals(1, asked)
        assertEquals("pikachu", flow.battle.player.active!!.species.id)
    }

    @Test
    fun `forced switch after the player's pokemon faints`() {
        val player = listOf(mon("magikarp", 2, listOf("splash")), mon("mewtwo", 70, listOf("psychic")))
        val flow = BattleFlow(battle(player, listOf(mon("machamp", 60, listOf("crosschop")), mon("rattata", 5, listOf("tackle")))))
        flow.startPresenting()
        flow.skipEvents()
        assertEquals(BattleFlow.Phase.CHOOSE_ACTION, flow.settle())
        flow.submit(Choice.UseMove(0))
        flow.skipEvents()
        assertTrue(flow.battle.player.active!!.fainted)
        assertEquals(BattleFlow.Phase.FORCED_SWITCH, flow.settle())
        assertNotNull(flow.forcedPick(0), "fainted Pokémon rejected")
        assertEquals(BattleFlow.Phase.FORCED_SWITCH, flow.phase)
        assertNull(flow.forcedPick(1))
        play(flow)
        assertEquals(BattleOutcome.WIN, flow.battle.outcome)
    }

    @Test
    fun `wild battle asks to use the next pokemon and can flee`() {
        val player = listOf(mon("magikarp", 2, listOf("splash")), mon("rattata", 50, listOf("tackle")))
        var sawPrompt = false
        for (seed in 0 until 5) {
            val flow = BattleFlow(battle(player.map { it.copyForTest() }, listOf(mon("mewtwo", 100, listOf("psychic"))), wild = true))
            flow.startPresenting(); flow.skipEvents()
            if (flow.settle() != BattleFlow.Phase.CHOOSE_ACTION) continue
            flow.submit(Choice.UseMove(0)); flow.skipEvents()
            if (flow.settle() != BattleFlow.Phase.NEXT_MON_PROMPT) continue
            sawPrompt = true
            flow.answerNextMon(false)
            flow.skipEvents()
            val ph = flow.settle()
            if (flow.battle.outcome == BattleOutcome.RAN) assertEquals(BattleFlow.Phase.ENDING, ph)
            else assertEquals(BattleFlow.Phase.FORCED_SWITCH, ph, "a failed escape must not ask again")
        }
        assertTrue(sawPrompt)
    }

    private fun Pokemon.copyForTest(): Pokemon = mon(speciesId, level, moves.map { it.id }).also { it.hp = it.maxHp(dex) }

    @Test
    fun `final foe fainting reaches the ending exactly once even if settle is called repeatedly`() {
        val flow = BattleFlow(battle(strongParty(), weakFoes(1)))
        play(flow)
        repeat(3) { assertEquals(BattleFlow.Phase.DONE, flow.settle()) }
        assertNull(flow.takeOutcome())
    }
}
