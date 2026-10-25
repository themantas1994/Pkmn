package pkmn.game.battle

import pkmn.engine.battle.BattleEvent
import kotlin.test.*

class HitFxTest {
    private val hp = BattleEvent.HpChange(1, 0, 10, 100, -90, "move")

    @Test
    fun `crit message right after the hp change is detected`() {
        val ev = listOf(hp, BattleEvent.Message("A critical hit!"), BattleEvent.Effectiveness(2.0))
        assertTrue(HitFx.critFollows(ev, 1))
    }

    @Test
    fun `other messages and effectiveness are skipped, the next hit stops the scan`() {
        assertTrue(HitFx.critFollows(listOf(BattleEvent.Effectiveness(2.0), BattleEvent.Message("A critical hit!")), 0))
        assertFalse(HitFx.critFollows(listOf(BattleEvent.Message("It's super effective!"), hp, BattleEvent.Message("A critical hit!")), 0))
    }

    @Test
    fun `no crit when the next hit or no message follows`() {
        assertFalse(HitFx.critFollows(listOf(hp, hp, BattleEvent.Message("A critical hit!")), 1))
        assertFalse(HitFx.critFollows(listOf(hp), 1))
    }

    @Test
    fun `flash strength grows with damage and crits are full`() {
        assertEquals(1f, HitFx.flashStrength(0.01f, true))
        assertTrue(HitFx.flashStrength(0.5f, false) > HitFx.flashStrength(0.05f, false))
        assertTrue(HitFx.flashStrength(1f, false) <= 0.9f)
    }
}
