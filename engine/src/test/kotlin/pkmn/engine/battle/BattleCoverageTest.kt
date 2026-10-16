package pkmn.engine.battle

import pkmn.engine.TestSupport.dex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleCoverageTest {
    @Test
    fun `every move, ability and item is classified`() {
        assertEquals(dex.moves.keys, BattleCoverage.moves(dex).keys)
        assertEquals(dex.abilities.keys, BattleCoverage.abilities(dex).keys)
        assertEquals(dex.items.keys, BattleCoverage.items(dex).keys)
        for ((name, map) in listOf("moves" to BattleCoverage.movesDetailed(dex), "abilities" to BattleCoverage.abilitiesDetailed(dex), "items" to BattleCoverage.itemsDetailed(dex))) {
            println("Coverage $name: " + BattleCoverage.counts(map.mapValues { it.value.status }))
            println("  unimplemented: " + map.filterValues { it.status == BattleCoverage.Status.UNIMPLEMENTED }.keys.joinToString(" "))
        }
    }

    @Test
    fun `data-driven is only claimed for moves without Showdown callbacks`() {
        val m = BattleCoverage.moves(dex)
        // Knock Off and Protect have callbacks in Showdown: they must be scripted, never "data-driven".
        assertEquals(BattleCoverage.Status.IMPLEMENTED_SCRIPT, m["knockoff"])
        assertEquals(BattleCoverage.Status.IMPLEMENTED_SCRIPT, m["protect"])
        assertEquals(BattleCoverage.Status.DATA_DRIVEN, m["tackle"])
        assertEquals(BattleCoverage.Status.DATA_DRIVEN, m["flamethrower"])
        assertEquals(BattleCoverage.Status.NO_EFFECT_NEEDED, m["helpinghand"])
    }

    @Test
    fun `no-singles-effect abilities and inert items are not registered as handlers`() {
        for (id in Abilities.NO_SINGLES_EFFECT) assertTrue(Effects.ability(id) == null, id)
        for (id in Items.NO_BATTLE_EFFECT) assertTrue(Effects.item(id) == null, id)
        val a = BattleCoverage.abilities(dex)
        assertEquals(BattleCoverage.Status.NO_EFFECT_NEEDED, a["telepathy"])
        assertEquals(BattleCoverage.Status.IMPLEMENTED_SCRIPT, a["intimidate"])
        assertEquals(BattleCoverage.Status.IMPLEMENTED_SCRIPT, a["levitate"])
        val i = BattleCoverage.items(dex)
        assertEquals(BattleCoverage.Status.NO_EFFECT_NEEDED, i["luckyegg"])
        assertEquals(BattleCoverage.Status.IMPLEMENTED_SCRIPT, i["leftovers"])
        assertEquals(BattleCoverage.Status.IMPLEMENTED_SCRIPT, i["heavydutyboots"])
    }
}
