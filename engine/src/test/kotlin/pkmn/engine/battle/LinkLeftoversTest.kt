package pkmn.engine.battle

import pkmn.engine.TestSupport
import pkmn.engine.TestSupport.mon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Link play, Phase 2: the Phase 1 leftovers (state hash coverage, doubles rejection, forfeit outside link rules). */
class LinkLeftoversTest {
    private fun battle() = TestSupport.battle(listOf(mon("pikachu"), mon("eevee")), listOf(mon("bulbasaur"), mon("snorlax")), seed = 11).also { it.start() }

    @Test
    fun `the state hash covers who set an effect`() {
        val b = battle()
        val a = b.player.active!!; val f = b.opponent.active!!
        b.opponent.active!!.volatiles["partiallytrapped"] = EffectState(5).also { it.source = a }
        val h1 = b.stateHash()
        b.opponent.active!!.volatiles["partiallytrapped"]!!.source = f
        assertNotEquals(h1, b.stateHash(), "the trap source decides who a later effect reads")
        b.opponent.active!!.volatiles["partiallytrapped"]!!.source = null
        assertNotEquals(h1, b.stateHash())
    }

    @Test
    fun `the state hash covers KO credit and the last damage`() {
        val b = battle()
        val a = b.player.active!!; val f = b.opponent.active!!
        val base = b.stateHash()
        f.koCredit = a; val credit = b.stateHash(); assertNotEquals(base, credit)
        f.lastDamagedBy = DamageRecord(a, 12, "Physical", 1); val dmg = b.stateHash(); assertNotEquals(credit, dmg)
        f.lastDamagedBy = DamageRecord(a, 13, "Physical", 1); assertNotEquals(dmg, b.stateHash())
        f.lastDamagedBy = DamageRecord(a, 12, "Special", 1); assertNotEquals(dmg, b.stateHash())
        f.lastDamagedBy = DamageRecord(f, 12, "Physical", 1); assertNotEquals(dmg, b.stateHash())
        f.lastHpLossEffect = "poison"; val loss = b.stateHash(); f.lastHpLossEffect = "burn"; assertNotEquals(loss, b.stateHash())
        f.timesAttacked = 3; val ta = b.stateHash(); f.critsThisBattle = 2; assertNotEquals(ta, b.stateHash())
        b.lastMoveUsed = "tackle"; assertNotEquals(b.stateHash(), run { b.lastMoveUsed = "growl"; b.stateHash() })
    }

    @Test
    fun `link rules reject doubles`() {
        assertFailsWith<IllegalArgumentException> { BattleRules.link(format = BattleFormat.DOUBLES) }
        assertTrue(BattleRules.link().isLinkSafe)
        // Hand-built rules do not pass either: a remote side needs the real thing.
        val doubles = BattleRules.link().copy(format = BattleFormat.DOUBLES)
        assertTrue(!doubles.isLinkSafe)
        val sides = listOf(Side(0, "A", listOf(mon("pikachu")), Controller.PLAYER, TestSupport.dex), Side(1, "B", listOf(mon("eevee")), Controller.REMOTE, TestSupport.dex))
        assertFailsWith<IllegalArgumentException> { Battle(TestSupport.dex, sides, doubles, 1) }
    }

    @Test
    fun `single-player sides cannot forfeit`() {
        val b = battle()
        assertNotNull(b.choose(b.player, Choice.Forfeit), "the engine rejects Forfeit outside link rules")
        assertEquals(BattleOutcome.ONGOING, b.outcome)
        assertNotNull(b.choose(b.opponent, Choice.Forfeit))
        // The same choice works under link rules.
        val l = Battle(TestSupport.dex, listOf(
            Side(0, "A", listOf(mon("pikachu")), Controller.PLAYER, TestSupport.dex), Side(1, "B", listOf(mon("eevee")), Controller.REMOTE, TestSupport.dex)), BattleRules.link(), 1)
        l.start()
        assertNull(l.choose(l.sides[0], Choice.Forfeit))
    }
}
