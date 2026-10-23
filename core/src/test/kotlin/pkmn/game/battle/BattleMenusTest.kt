package pkmn.game.battle

import pkmn.engine.battle.*
import pkmn.engine.data.Dex
import pkmn.engine.mon.PokemonFactory
import pkmn.engine.util.Rng
import pkmn.game.input.Button
import kotlin.test.*

class BattleMenusTest {
    private val dex: Dex by lazy { Dex.loadDefault() }

    @Test
    fun `gimmick entry shown only when the request offers a gimmick`() {
        assertEquals(listOf("FIGHT", "PKMN", "PACK", "RUN"), BattleMenus.actions(false))
        assertEquals(listOf("FIGHT", "PKMN", "PACK", "RUN", "GIMMICK"), BattleMenus.actions(true))
    }

    @Test
    fun `four-entry navigation is unchanged`() {
        assertEquals(2, BattleMenus.navigate(0, Button.DOWN, 4))
        assertEquals(0, BattleMenus.navigate(2, Button.UP, 4))
        assertEquals(1, BattleMenus.navigate(0, Button.RIGHT, 4))
        assertEquals(0, BattleMenus.navigate(1, Button.LEFT, 4))
        assertEquals(3, BattleMenus.navigate(1, Button.DOWN, 4))
    }

    @Test
    fun `five-entry navigation reaches GIMMICK and never lands on a missing cell`() {
        assertEquals(4, BattleMenus.navigate(2, Button.DOWN, 5))
        assertEquals(4, BattleMenus.navigate(3, Button.DOWN, 5)) // column 1 has no third row
        assertEquals(2, BattleMenus.navigate(4, Button.UP, 5))
        assertEquals(4, BattleMenus.navigate(0, Button.UP, 5)) // wraps
        assertEquals(4, BattleMenus.navigate(4, Button.RIGHT, 5)) // no cell to the right
        assertEquals(4, BattleMenus.navigate(4, Button.LEFT, 5))
    }

    @Test
    fun `gimmick choices keep a stable order`() {
        val req = Request.Move(
            Side(0, "P", listOf(PokemonFactory(dex).create("pikachu", 5, Rng(1))), Controller.PLAYER, dex).also { it.active = it.mons[0] }.active!!,
            listOf(true), true, setOf(Gimmick.TERA, Gimmick.MEGA), null)
        assertEquals(listOf(Gimmick.MEGA, Gimmick.TERA), BattleMenus.gimmicks(req))
        assertEquals(emptyList(), BattleMenus.gimmicks(Request.Wait))
    }

    private fun flow(gimmicks: Set<Gimmick>, allowed: Set<Gimmick>): BattleFlow {
        val f = PokemonFactory(dex)
        val zard = f.create("charizard", 50, Rng(1), moves = listOf("dragonclaw", "flareblitz")).also { it.heldItem = "charizarditex" }
        val foe = f.create("snorlax", 100, Rng(2), moves = listOf("splash"))
        val b = Battle(dex, listOf(
            Side(0, "Player", listOf(zard), Controller.PLAYER, dex, gimmicks = gimmicks),
            Side(1, "Rival", listOf(foe), Controller.AI, dex, aiLevel = 1)),
            BattleRules(allowedGimmicks = allowed, isTrainerBattle = true), seed = 3)
        b.start()
        return BattleFlow(b).also { it.startPresenting(); while (it.nextEvent() != null) {}; it.settle() }
    }

    @Test
    fun `chosen gimmick reaches the engine through the flow and is spent`() {
        val flow = flow(setOf(Gimmick.MEGA), setOf(Gimmick.MEGA))
        assertEquals(BattleFlow.Phase.CHOOSE_ACTION, flow.phase)
        assertEquals(listOf(Gimmick.MEGA), BattleMenus.gimmicks(flow.battle.request(flow.battle.player)))
        assertNull(flow.submit(Choice.UseMove(0, BattleMenus.gimmicks(flow.battle.request(flow.battle.player)).first())))
        assertEquals("charizardmegax", flow.battle.player.active!!.species.id)
        while (flow.nextEvent() != null) {}
        flow.settle()
        assertEquals(emptyList(), BattleMenus.gimmicks(flow.battle.request(flow.battle.player)))
    }

    @Test
    fun `no gimmick offered when the rules or side do not allow one`() {
        for ((side, rules) in listOf(setOf(Gimmick.MEGA) to emptySet(), emptySet<Gimmick>() to setOf(Gimmick.MEGA))) {
            val flow = flow(side, rules)
            assertEquals(emptyList(), BattleMenus.gimmicks(flow.battle.request(flow.battle.player)))
            assertEquals(4, BattleMenus.actions(false).size)
            assertNull(flow.submit(Choice.UseMove(0)))
            assertEquals("charizard", flow.battle.player.active!!.species.id)
        }
    }
}
