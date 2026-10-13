package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.mon
import pkmn.engine.TestSupport.turn
import kotlin.test.*

class GimmickTest {

    @Test
    fun `mega evolution changes forme, stats and ability, once per battle`() {
        val zard = mon("charizard", item = "charizarditex", moves = listOf("dragonclaw", "flareblitz"))
        val b = battle(listOf(zard, mon("venusaur", item = "venusaurite")), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val req = b.request(b.player) as Request.Move
        assertTrue(Gimmick.MEGA in req.gimmicks)
        val atkBefore = b.player.active!!.rawStat(pkmn.engine.data.Stat.ATK)
        turn(b, Choice.UseMove(0, Gimmick.MEGA), Choice.UseMove(0))
        val p = b.player.active!!
        assertEquals("charizardmegax", p.species.id)
        assertEquals(listOf("Fire", "Dragon"), p.types)
        assertEquals("toughclaws", p.ability)
        assertTrue(p.rawStat(pkmn.engine.data.Stat.ATK) > atkBefore)
        assertFalse(b.canUseGimmickNow(p, Gimmick.MEGA))
        // Reverts after battle.
        b.finish(BattleOutcome.WIN)
        assertEquals("charizard", zard.speciesId)
    }

    @Test
    fun `only one gimmick per battle under the default ruleset`() {
        val b = battle(listOf(mon("charizard", item = "charizarditey", moves = listOf("flamethrower"), tera = "Fire")), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        turn(b, Choice.UseMove(0, Gimmick.MEGA), Choice.UseMove(0))
        assertFalse(b.canUseGimmickNow(b.player.active!!, Gimmick.TERA))
        assertFalse(b.canUseGimmickNow(b.player.active!!, Gimmick.DYNAMAX))
    }

    @Test
    fun `z-move replaces the base move with boosted power`() {
        val b = battle(listOf(mon("pikachu", item = "electriumz", moves = listOf("thunderbolt"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val z = Gimmicks.zMoveFor(b, b.player.active!!, dex.move("thunderbolt"))!!
        assertEquals("gigavolthavoc", z.id)
        assertEquals(175, z.basePower)
        turn(b, Choice.UseMove(0, Gimmick.ZMOVE), Choice.UseMove(0))
        assertTrue(b.log.any { it.contains("Gigavolt Havoc") })
        assertTrue(Gimmick.ZMOVE in b.player.usedGimmicks)
    }

    @Test
    fun `signature z-move requires the right species and move`() {
        val b = battle(listOf(mon("pikachu", item = "pikaniumz", moves = listOf("volttackle", "thunderbolt"))), listOf(mon("snorlax")))
        assertEquals("catastropika", Gimmicks.zMoveFor(b, b.player.active!!, dex.move("volttackle"))?.id)
        assertNull(Gimmicks.zMoveFor(b, b.player.active!!, dex.move("thunderbolt")))
    }

    @Test
    fun `dynamax doubles hp, converts moves and lasts three turns`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("bodyslam", "rest"))), listOf(mon("blissey", level = 100, moves = listOf("splash"))))
        val p = b.player.active!!
        val base = p.maxHp
        turn(b, Choice.UseMove(0, Gimmick.DYNAMAX), Choice.UseMove(0))
        assertTrue(b.log.any { it.contains("Max Strike") })
        assertEquals(base * 2, p.maxHp)
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertFalse(p.isDynamaxed)
        assertEquals(base, p.maxHp)
    }

    @Test
    fun `gigantamax uses the g-max move`() {
        val zard = mon("charizard", moves = listOf("flamethrower")).also { it.gmaxFactor = true }
        val b = battle(listOf(zard), listOf(mon("blissey", level = 100, moves = listOf("splash"))))
        turn(b, Choice.UseMove(0, Gimmick.DYNAMAX), Choice.UseMove(0))
        assertTrue(b.log.any { it.contains("G-Max Wildfire") }, b.log.joinToString("\n"))
    }

    @Test
    fun `terastallization changes type and boosts stab`() {
        val pika = mon("pikachu", moves = listOf("thunderbolt"), tera = "Electric")
        val b = battle(listOf(pika), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val user = b.player.active!!
        val target = b.opponent.active!!
        b.forcedRoll = 100; b.forcedCrit = false
        val before = b.calcDamage(MoveCtx(user, target, ActiveMove(dex.move("thunderbolt"))))!!
        Gimmicks.terastallize(b, user)
        val after = b.calcDamage(MoveCtx(user, target, ActiveMove(dex.move("thunderbolt"))))!!
        assertTrue(after > before, "tera STAB should be 2x: $before -> $after")
        val flying = mon("pikachu", tera = "Flying")
        val b2 = battle(listOf(flying), listOf(mon("snorlax")))
        Gimmicks.terastallize(b2, b2.player.active!!)
        assertEquals(listOf("Flying"), b2.player.active!!.effectiveTypes())
    }

    @Test
    fun `primal reversion happens on switch-in`() {
        val b = battle(listOf(mon("groudon", level = 70, item = "redorb")), listOf(mon("snorlax")))
        assertEquals("groudonprimal", b.player.active!!.species.id)
        assertEquals("desolateland", b.field.weather)
    }

    @Test
    fun `ultra burst transforms necrozma`() {
        val nec = mon("necrozmaduskmane", level = 70, item = "ultranecroziumz", moves = listOf("sunsteelstrike"))
        val b = battle(listOf(nec), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        turn(b, Choice.UseMove(0, Gimmick.ULTRA_BURST), Choice.UseMove(0))
        assertEquals("necrozmaultra", b.player.active!!.species.id)
    }

    @Test
    fun `gimmicks are unavailable when the format disables them`() {
        val b = battle(listOf(mon("charizard", item = "charizarditex")), listOf(mon("snorlax")), rules = BattleRules())
        val req = b.request(b.player) as Request.Move
        assertTrue(req.gimmicks.isEmpty())
    }
}
