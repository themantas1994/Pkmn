package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.mon
import pkmn.engine.TestSupport.turn
import pkmn.engine.mon.MajorStatus
import kotlin.test.*

/** Interactions that commonly go wrong in fan engines: priority, immunities, ability bypass, items, field effects. */
class BattleEdgeCaseTest {

    private fun dmg(b: Battle, user: BattleMon, target: BattleMon, move: String, crit: Boolean = false): Int {
        b.forcedRoll = 100; b.forcedCrit = crit
        val r = b.calcDamage(MoveCtx(user, target, ActiveMove(dex.move(move))))!!
        b.forcedRoll = null; b.forcedCrit = null
        return r
    }

    @Test
    fun `focus sash survives a one-hit KO from full hp and is consumed`() {
        val b = battle(listOf(mon("garchomp", level = 100, moves = listOf("earthquake"))), listOf(mon("abra", level = 20, item = "focussash", moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        val abra = b.opponent.active!!
        assertEquals(1, abra.hp)
        assertNull(abra.item)
    }

    @Test
    fun `skill link always hits five times`() {
        val b = battle(listOf(mon("cinccino", ability = "skilllink", moves = listOf("tailslap"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        repeat(3) { turn(b, Choice.UseMove(0), Choice.UseMove(0)) }
        assertEquals(3, b.log.count { it.contains("5 time") }, b.log.joinToString("\n"))
    }

    @Test
    fun `substitute blocks status moves`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("substitute", "splash"))), listOf(mon("gengar", moves = listOf("splash", "toxic"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue("substitute" in b.player.active!!.volatiles)
        turn(b, Choice.UseMove(1), Choice.UseMove(1))
        assertNull(b.player.active!!.mon.status)
    }

    @Test
    fun `sandstorm damages only non rock ground steel types`() {
        val b = battle(listOf(mon("tyranitar", ability = "sandstream", moves = listOf("splash"))), listOf(mon("pikachu", moves = listOf("splash"))))
        val ttar = b.player.active!!; val pika = b.opponent.active!!
        assertEquals("sandstorm", b.field.weather)
        val pikaHp = pika.hp; val ttarHp = ttar.hp
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(pikaHp - pika.maxHp / 16, pika.hp)
        assertEquals(ttarHp, ttar.hp)
    }

    @Test
    fun `prankster status moves fail against dark types`() {
        val b = battle(listOf(mon("sableye", ability = "prankster", moves = listOf("thunderwave"))), listOf(mon("umbreon", moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertNull(b.opponent.active!!.mon.status)
        val c = battle(listOf(mon("sableye", ability = "prankster", moves = listOf("thunderwave"))), listOf(mon("snorlax", moves = listOf("splash"))))
        turn(c, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(MajorStatus.PARALYSIS, c.opponent.active!!.mon.status)
    }

    @Test
    fun `priority moves go first and trick room reverses speed order`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("quickattack", "tackle"))), listOf(mon("jolteon", moves = listOf("tackle"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        val firstUser = b.log.first { it.contains(" used ") }
        assertTrue(firstUser.contains("Snorlax"), firstUser)

        val t = battle(listOf(mon("snorlax", moves = listOf("trickroom", "tackle"))), listOf(mon("jolteon", moves = listOf("splash", "tackle"))))
        turn(t, Choice.UseMove(0), Choice.UseMove(0))
        val mark = t.log.size
        turn(t, Choice.UseMove(1), Choice.UseMove(1))
        val first = t.log.drop(mark).first { it.contains(" used ") }
        assertTrue(first.contains("Snorlax"), first)
    }

    @Test
    fun `mold breaker hits through levitate`() {
        val b = battle(listOf(mon("excadrill", ability = "moldbreaker", moves = listOf("earthquake"))), listOf(mon("bronzong", ability = "levitate", moves = listOf("splash"))))
        val hp = b.opponent.active!!.hp
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.opponent.active!!.hp < hp)
    }

    @Test
    fun `wonder guard only lets super effective hits through`() {
        val b = battle(listOf(mon("arcanine", moves = listOf("tackle", "flamethrower"))), listOf(mon("shedinja", ability = "wonderguard", moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertFalse(b.opponent.active!!.fainted)
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertTrue(b.opponent.mons.single().fainted)
    }

    @Test
    fun `critical hits ignore the target's positive defense boosts`() {
        val b = battle(listOf(mon("machamp", moves = listOf("crosschop"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val user = b.player.active!!; val target = b.opponent.active!!
        val critPlain = dmg(b, user, target, "crosschop", crit = true)
        target.boosts[1] = 2
        assertEquals(critPlain, dmg(b, user, target, "crosschop", crit = true))
        assertTrue(dmg(b, user, target, "crosschop") < critPlain)
    }

    @Test
    fun `disguise absorbs the first hit for an eighth of max hp`() {
        val b = battle(listOf(mon("tyranitar", ability = "unnerve", moves = listOf("crunch"))), listOf(mon("mimikyu", ability = "disguise", moves = listOf("splash"))))
        val mimi = b.opponent.active!!
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(mimi.maxHp - mimi.maxHp / 8, mimi.hp)
        assertTrue(b.log.any { it.contains("disguise was busted") })
    }

    @Test
    fun `life orb boosts damage and costs a tenth of max hp`() {
        val b = battle(listOf(mon("garchomp", item = "lifeorb", moves = listOf("dragonclaw"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val chomp = b.player.active!!
        val plain = battle(listOf(mon("garchomp", moves = listOf("dragonclaw"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        assertTrue(dmg(b, chomp, b.opponent.active!!, "dragonclaw") > dmg(plain, plain.player.active!!, plain.opponent.active!!, "dragonclaw"))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(chomp.maxHp - chomp.maxHp / 10, chomp.hp)
    }

    @Test
    fun `rocky helmet hurts contact attackers by a sixth`() {
        val b = battle(listOf(mon("machamp", moves = listOf("crosschop"))), listOf(mon("skarmory", item = "rockyhelmet", moves = listOf("splash"))))
        val champ = b.player.active!!
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(champ.maxHp - champ.maxHp / 6, champ.hp)
    }

    @Test
    fun `out of pp the user struggles and takes recoil`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("tackle"))), listOf(mon("blissey", moves = listOf("splash"))))
        val lax = b.player.active!!
        lax.moveSlots[0].pp = 0
        val req = b.request(b.player) as Request.Move
        assertFalse(req.usableMoves[0])
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.log.any { it.contains("Struggle") })
        // Showdown rounds Struggle recoil (Math.round(maxhp / 4)).
        assertEquals(lax.maxHp - Math.round(lax.maxHp / 4.0).toInt(), lax.hp)
    }

    @Test
    fun `assault vest and taunt block status moves`() {
        val b = battle(listOf(mon("snorlax", item = "assaultvest", moves = listOf("tackle", "curse"))), listOf(mon("blissey", moves = listOf("splash"))))
        val req = b.request(b.player) as Request.Move
        assertTrue(req.usableMoves[0]); assertFalse(req.usableMoves[1])

        val t = battle(listOf(mon("snorlax", moves = listOf("tackle", "curse"))), listOf(mon("aerodactyl", moves = listOf("taunt"))))
        turn(t, Choice.UseMove(0), Choice.UseMove(0))
        val after = t.request(t.player) as Request.Move
        assertTrue(after.usableMoves[0]); assertFalse(after.usableMoves[1])
    }

    @Test
    fun `explosion makes the user faint`() {
        val b = battle(listOf(mon("electrode", moves = listOf("explosion")), mon("snorlax")), listOf(mon("blissey", level = 100, moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.player.mons[0].fainted)
    }

    @Test
    fun `perish song faints both sides after three turns`() {
        val b = battle(listOf(mon("lapras", moves = listOf("perishsong", "splash"))), listOf(mon("blissey", moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        repeat(3) { if (b.outcome == BattleOutcome.ONGOING) turn(b, Choice.UseMove(1), Choice.UseMove(0)) }
        assertTrue(b.player.mons[0].fainted)
        assertTrue(b.opponent.mons[0].fainted)
    }

    @Test
    fun `ground moves hit airborne foes under gravity and electric hits ground types never`() {
        val b = battle(listOf(mon("garchomp", moves = listOf("gravity", "earthquake"))), listOf(mon("skarmory", moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        val hp = b.opponent.active!!.hp
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertTrue(b.opponent.active!!.hp < hp)
        val mc = MoveCtx(b.player.active!!, b.player.active!!, ActiveMove(dex.move("thunderbolt")))
        assertEquals(0.0, b.typeEffectiveness(mc))
    }
}
