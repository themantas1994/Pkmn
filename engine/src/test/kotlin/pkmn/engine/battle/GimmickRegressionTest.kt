package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.mon
import pkmn.engine.TestSupport.turn
import kotlin.test.*

/** Gimmick state that must survive (or not be spent by) switching and failed move attempts. */
class GimmickRegressionTest {
    private val splash = Choice.UseMove(0)

    @Test
    fun `ogerpon stays in its tera forme after switching out and back in`() {
        val b = battle(
            listOf(mon("ogerponwellspring", moves = listOf("splash"), tera = "Water"), mon("snorlax", moves = listOf("splash"))),
            listOf(mon("blissey", level = 100, moves = listOf("splash"))),
        )
        turn(b, Choice.UseMove(0, Gimmick.TERA), splash)
        assertEquals("ogerponwellspringtera", b.player.active!!.species.id)
        turn(b, Choice.Switch(1), splash)
        turn(b, Choice.Switch(0), splash)
        val p = b.player.active!!
        assertEquals("Water", p.terastallized)
        assertEquals("ogerponwellspringtera", p.species.id)
        assertEquals("embodyaspectwellspring", p.ability)
    }

    @Test
    fun `necrozma keeps neuroforce after switching out and back in`() {
        val b = battle(
            listOf(mon("necrozmaduskmane", item = "ultranecroziumz", moves = listOf("splash")), mon("snorlax", moves = listOf("splash"))),
            listOf(mon("blissey", level = 100, moves = listOf("splash"))),
        )
        turn(b, Choice.UseMove(0, Gimmick.ULTRA_BURST), splash)
        assertEquals("neuroforce", b.player.active!!.ability)
        turn(b, Choice.Switch(1), splash)
        turn(b, Choice.Switch(0), splash)
        val p = b.player.active!!
        assertEquals("necrozmaultra", p.species.id)
        assertEquals("neuroforce", p.ability)
    }

    @Test
    fun `terapagos keeps tera shell and teraform zero after switching`() {
        val b = battle(
            listOf(mon("terapagos", moves = listOf("splash"), tera = "Stellar"), mon("snorlax", moves = listOf("splash"))),
            listOf(mon("blissey", level = 100, moves = listOf("splash"))),
        )
        assertEquals("terapagosterastal", b.player.active!!.species.id)
        assertEquals("terashell", b.player.active!!.ability)
        turn(b, Choice.Switch(1), splash)
        turn(b, Choice.Switch(0), splash)
        assertEquals("terashell", b.player.active!!.ability)
        turn(b, Choice.UseMove(0, Gimmick.TERA), splash)
        assertEquals("terapagosstellar", b.player.active!!.species.id)
        assertEquals("teraformzero", b.player.active!!.ability)
        turn(b, Choice.Switch(1), splash)
        turn(b, Choice.Switch(0), splash)
        assertEquals("teraformzero", b.player.active!!.ability)
    }

    @Test
    fun `a z-move is not spent when the user flinches before moving`() {
        val b = battle(
            listOf(mon("pikachu", level = 50, item = "electriumz", moves = listOf("thunderbolt"))),
            listOf(mon("meowth", level = 50, moves = listOf("fakeout"))),
        )
        turn(b, Choice.UseMove(0, Gimmick.ZMOVE), Choice.UseMove(0))
        assertTrue(b.log.any { it.contains("flinch") }, "user should have flinched: ${b.log}")
        assertFalse(Gimmick.ZMOVE in b.player.usedGimmicks)
        assertTrue(b.canUseGimmickNow(b.player.active!!, Gimmick.ZMOVE))
        val slot = b.player.active!!.moveSlots[0]
        assertEquals(slot.maxPp(b.dex), slot.pp)
    }

    @Test
    fun `teal ogerpon reaches its tera forme`() {
        val b = battle(
            listOf(mon("ogerpon", moves = listOf("splash"), tera = "Grass")),
            listOf(mon("blissey", level = 100, moves = listOf("splash"))),
        )
        turn(b, Choice.UseMove(0, Gimmick.TERA), splash)
        assertEquals("ogerpontealtera", b.player.active!!.species.id)
        assertEquals("embodyaspectteal", b.player.active!!.ability)
    }

    @Test
    fun `a transformed pokemon that terastallizes gets its own ability back on switching`() {
        val ditto = mon("ditto", moves = listOf("transform", "splash"), tera = "Normal")
        val b = battle(
            listOf(ditto, mon("snorlax", moves = listOf("splash"))),
            listOf(mon("ogerponwellspring", level = 100, moves = listOf("splash"))),
        )
        val own = b.player.active!!.ability
        turn(b, Choice.UseMove(0), splash)
        assertTrue(b.player.active!!.transformed)
        turn(b, Choice.UseMove(0, Gimmick.TERA), splash)
        assertEquals("ogerponwellspringtera", b.player.active!!.species.id)
        turn(b, Choice.Switch(1), splash)
        turn(b, Choice.Switch(0), splash)
        assertEquals("ditto", b.player.active!!.species.id)
        assertEquals(own, b.player.active!!.ability)
    }

    @Test
    fun `z-move is neither offered nor applied on a locked-move turn`() {
        val b = battle(
            listOf(mon("dragonite", item = "dragoniumz", moves = listOf("outrage", "splash"))),
            listOf(mon("blissey", level = 100, moves = listOf("splash"))),
        )
        assertTrue(Gimmick.ZMOVE in (b.request(b.player) as Request.Move).gimmicks)
        turn(b, splash, splash)
        assertNotNull(b.lockedMove(b.player.active!!), "outrage should be locked")
        assertFalse(Gimmick.ZMOVE in (b.request(b.player) as Request.Move).gimmicks)
        assertNotNull(b.choose(b.player, Choice.UseMove(0, Gimmick.ZMOVE)), "engine must refuse a Z-Move on a locked turn")
        assertFalse(Gimmick.ZMOVE in b.player.usedGimmicks)
    }
}
