package pkmn.engine.battle

import pkmn.engine.TestSupport
import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.mon
import pkmn.engine.TestSupport.turn
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.Nature
import pkmn.engine.mon.StatSpread
import kotlin.test.*

class BattleCoreTest {

    private fun dmg(b: Battle, user: BattleMon, target: BattleMon, move: String, roll: Int = 100): Int {
        b.forcedRoll = roll; b.forcedCrit = false
        val r = b.calcDamage(MoveCtx(user, target, ActiveMove(dex.move(move))))!!
        b.forcedRoll = null; b.forcedCrit = null
        return r
    }

    @Test
    fun `damage formula matches the official calculation`() {
        val chomp = mon("garchomp", moves = listOf("earthquake"), nature = Nature.ADAMANT, evs = StatSpread(atk = 252))
        val lax = mon("snorlax", moves = listOf("tackle"))
        val b = battle(listOf(chomp), listOf(lax))
        // Atk 200, Def 85, BP 100, Lv50: floor(floor(22*100*200/85)/50)+2 = 105; STAB 1.5 rounds half down.
        assertEquals(157, dmg(b, b.player.active!!, b.opponent.active!!, "earthquake", 100))
        // Roll 85: floor(105*0.85) = 89, *1.5 = 133.5 -> 133.
        assertEquals(133, dmg(b, b.player.active!!, b.opponent.active!!, "earthquake", 85))
    }

    @Test
    fun `type immunity prevents damage`() {
        val b = battle(listOf(mon("pikachu", moves = listOf("thunderbolt"))), listOf(mon("garchomp", moves = listOf("splash"))))
        val hp = b.opponent.active!!.hp
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(hp, b.opponent.active!!.hp)
        assertTrue(b.log.any { it.startsWith("It doesn't affect") })
    }

    @Test
    fun `super effective damage is doubled`() {
        val b = battle(listOf(mon("squirtle", moves = listOf("watergun"))), listOf(mon("charmander", moves = listOf("splash"))))
        val neutral = battle(listOf(mon("squirtle", moves = listOf("watergun"))), listOf(mon("rattata", moves = listOf("splash"))))
        val se = dmg(b, b.player.active!!, b.opponent.active!!, "watergun")
        val mc = MoveCtx(b.player.active!!, b.opponent.active!!, ActiveMove(dex.move("watergun")))
        assertEquals(2.0, b.typeEffectiveness(mc))
        assertTrue(se > dmg(neutral, neutral.player.active!!, neutral.opponent.active!!, "watergun"))
    }

    @Test
    fun `levitate grants ground immunity`() {
        val b = battle(listOf(mon("garchomp", moves = listOf("earthquake"))), listOf(mon("bronzong", ability = "levitate", moves = listOf("splash"))))
        val hp = b.opponent.active!!.hp
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(hp, b.opponent.active!!.hp)
    }

    @Test
    fun `intimidate lowers the foe's attack on entry`() {
        val b = battle(listOf(mon("gyarados", ability = "intimidate")), listOf(mon("rattata")))
        assertEquals(-1, b.opponent.active!!.boost("atk"))
    }

    @Test
    fun `burn halves physical damage`() {
        val b = battle(listOf(mon("machamp", ability = "noguard", moves = listOf("crosschop", "focusblast"))), listOf(mon("snorlax")))
        val normal = dmg(b, b.player.active!!, b.opponent.active!!, "crosschop")
        b.player.active!!.mon.status = MajorStatus.BURN
        val burned = dmg(b, b.player.active!!, b.opponent.active!!, "crosschop")
        assertTrue(burned in normal / 2 - 1..normal / 2 + 1, "burned=$burned normal=$normal")
        // Special moves are unaffected.
        val sp1 = dmg(b, b.player.active!!, b.opponent.active!!, "focusblast")
        b.player.active!!.mon.status = null
        assertEquals(dmg(b, b.player.active!!, b.opponent.active!!, "focusblast"), sp1)
    }

    @Test
    fun `sturdy survives a one-hit KO from full HP`() {
        val b = battle(listOf(mon("garchomp", level = 100, moves = listOf("earthquake"))), listOf(mon("geodude", level = 5, ability = "sturdy", moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(1, b.opponent.active!!.hp)
    }

    @Test
    fun `leftovers restores a sixteenth each turn`() {
        val b = battle(listOf(mon("snorlax", item = "leftovers", moves = listOf("splash"))), listOf(mon("rattata", moves = listOf("tackle"))))
        val p = b.player.active!!
        p.hp = p.maxHp / 2
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.log.any { it.contains("Leftovers") })
    }

    @Test
    fun `stealth rock deals damage based on rock effectiveness`() {
        val b = battle(listOf(mon("garchomp", moves = listOf("stealthrock"))), listOf(mon("rattata", moves = listOf("splash")), mon("charizard", moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        turn(b, Choice.UseMove(0), Choice.Switch(1))
        val zard = b.opponent.active!!
        assertEquals("charizard", zard.species.id)
        assertEquals(zard.maxHp - zard.maxHp / 2, zard.hp)
    }

    @Test
    fun `paralysis halves speed and thunder wave fails on ground types`() {
        val b = battle(listOf(mon("pikachu", moves = listOf("thunderwave"))), listOf(mon("rattata", moves = listOf("splash")), mon("sandshrew")))
        val before = b.getSpeed(b.opponent.active!!)
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(MajorStatus.PARALYSIS, b.opponent.active!!.status)
        assertEquals(before / 2, b.getSpeed(b.opponent.active!!))
        val b2 = battle(listOf(mon("pikachu", moves = listOf("thunderwave"))), listOf(mon("sandshrew", moves = listOf("splash"))))
        turn(b2, Choice.UseMove(0), Choice.UseMove(0))
        assertNull(b2.opponent.active!!.status)
    }

    @Test
    fun `protect blocks attacks`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("protect"))), listOf(mon("machamp", moves = listOf("crosschop"))))
        val hp = b.player.active!!.hp
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(hp, b.player.active!!.hp)
        assertTrue(b.log.any { it.contains("protected itself") })
    }

    @Test
    fun `choice band locks the user into its first move`() {
        val b = battle(listOf(mon("machamp", item = "choiceband", moves = listOf("crosschop", "bulkup"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        val req = b.request(b.player) as Request.Move
        assertTrue(req.usableMoves[0])
        assertFalse(req.usableMoves[1])
    }

    @Test
    fun `toxic damage increases every turn`() {
        val b = battle(listOf(mon("blissey", level = 100, moves = listOf("splash"))), listOf(mon("crobat", moves = listOf("toxic", "splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(MajorStatus.TOXIC, b.player.active!!.status)
        val p = b.player.active!!
        val hp1 = p.hp
        turn(b, Choice.UseMove(0), Choice.UseMove(1))
        val hp2 = p.hp
        turn(b, Choice.UseMove(0), Choice.UseMove(1))
        assertTrue(hp1 - hp2 < hp2 - p.hp, "toxic should escalate")
    }

    @Test
    fun `wild battle master ball always catches`() {
        val b = battle(listOf(mon("pikachu")), listOf(mon("mewtwo", level = 70)), wild = true, rules = BattleRules())
        turn(b, Choice.UseItem("masterball"), Choice.UseMove(0))
        assertEquals(BattleOutcome.CAUGHT, b.outcome)
        assertEquals("mewtwo", b.caughtMon?.speciesId)
    }

    @Test
    fun `defeating a foe awards experience and can level up`() {
        val p = mon("charmander", level = 5, moves = listOf("ember"))
        val b = battle(listOf(p), listOf(mon("bulbasaur", level = 8, moves = listOf("growl"))))
        val exp = p.exp
        repeat(10) { if (b.outcome == BattleOutcome.ONGOING) turn(b, Choice.UseMove(0), Choice.UseMove(0)) }
        assertEquals(BattleOutcome.WIN, b.outcome)
        assertTrue(p.exp > exp)
        assertTrue(p.level >= 6, "should level up, now ${p.level}")
    }

    @Test
    fun `sleep prevents moving and wears off`() {
        val b = battle(listOf(mon("snorlax", level = 100, moves = listOf("tackle"))), listOf(mon("breloom", moves = listOf("spore", "splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(MajorStatus.SLEEP, b.player.active!!.status)
        var turns = 0
        while (b.player.active!!.status == MajorStatus.SLEEP && turns < 5) { turn(b, Choice.UseMove(0), Choice.UseMove(1)); turns++ }
        // 1-3 turns asleep, then it wakes (and moves) on the next turn: the loop counts the waking turn too.
        assertTrue(turns in 2..4, "sleep lasted $turns turns")
    }

    @Test
    fun `trainer battles cannot be escaped`() {
        val b = battle(listOf(mon("pikachu")), listOf(mon("rattata")))
        assertNotNull(b.choose(b.player, Choice.Run))
    }

    @Test
    fun `fainted pokemon must be replaced`() {
        val b = battle(listOf(mon("rattata", level = 2), mon("pidgey", level = 10)), listOf(mon("mewtwo", level = 100, moves = listOf("psychic"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.player.active!!.fainted)
        assertTrue(b.request(b.player) is Request.SwitchIn)
        assertNull(b.choose(b.player, Choice.Switch(1)))
        assertEquals("pidgey", b.player.active!!.species.id)
    }

    @Test
    fun `seeded battles are reproducible`() {
        fun run(seed: Long): List<String> {
            val b = battle(listOf(mon("pikachu", moves = listOf("thunderbolt", "quickattack"))), listOf(mon("eevee", moves = listOf("tackle", "sandattack"))), seed = seed)
            repeat(20) { if (b.outcome == BattleOutcome.ONGOING) { b.choose(b.player, Choice.UseMove(it % 2)); b.advance() } }
            return b.log
        }
        assertEquals(run(42), run(42))
    }
}
