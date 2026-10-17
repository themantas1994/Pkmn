package pkmn.engine.mon

import pkmn.engine.TestSupport
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.mon
import pkmn.engine.battle.*
import pkmn.engine.campaign.FieldItems
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.data.Stat
import java.io.File
import kotlin.test.*

/**
 * Natures, IVs, EVs and the Generation III+ stat formula. Every expected number here was worked out by hand (the
 * arithmetic is in the comments) or is a published example; none is produced by the code under test.
 */
class StatFormulaTest {
    private fun spread(hp: Int, atk: Int, def: Int, spa: Int, spd: Int, spe: Int) = StatSpread(hp, atk, def, spa, spd, spe)
    private fun stats(p: Pokemon) = Stat.entries.map { p.stat(dex, it) }

    @Test
    fun `published example - Garchomp level 78 Adamant`() {
        // Bulbapedia "Stat" article (Generation III onward) worked example.
        val g = mon("garchomp", 78, nature = Nature.ADAMANT, ivs = spread(24, 12, 30, 16, 23, 5), evs = spread(74, 190, 91, 48, 84, 23))
        assertEquals(listOf(289, 278, 193, 135, 171, 171), stats(g))
    }

    @Test
    fun `level 1, 50 and 100 fixtures with IVs 0 and 31 and EVs 0, 4, 252`() {
        // Pikachu 35/55/40/50/50/90.
        // L1 IV0 EV0 Hardy: HP floor(70*1/100)+1+10 = 11; Atk floor(110/100)+5 = 6; Spe floor(180/100)+5 = 6.
        val l1 = mon("pikachu", 1, ivs = spread(0, 0, 0, 0, 0, 0))
        assertEquals(11, l1.stat(dex, Stat.HP)); assertEquals(6, l1.stat(dex, Stat.ATK)); assertEquals(6, l1.stat(dex, Stat.SPE))
        // L100 IV31: HP EV0 -> 70+31 = 101 + 110 = 211; EV3 -> floor(3/4) = 0 -> 211; EV4 -> +1 -> 212; EV252 -> +63 -> 274.
        for ((ev, hp) in listOf(0 to 211, 3 to 211, 4 to 212, 252 to 274))
            assertEquals(hp, mon("pikachu", 100, evs = spread(ev, 0, 0, 0, 0, 0)).stat(dex, Stat.HP), "EV $ev")
        // L100 Jolly Spe IV31 EV252: (180+31+63) = 274 + 5 = 279; x110/100 = 306.9 -> 306.
        assertEquals(306, mon("pikachu", 100, nature = Nature.JOLLY, evs = spread(0, 0, 0, 0, 0, 252)).stat(dex, Stat.SPE))
        // L100 Modest Atk IV0 EV0: 110 + 5 = 115; x90/100 = 103.5 -> 103.
        assertEquals(103, mon("pikachu", 100, nature = Nature.MODEST, ivs = spread(31, 0, 31, 31, 31, 31)).stat(dex, Stat.ATK))
        // Mewtwo L50 (154 SpA, 130 Spe): Modest SpA EV252 -> (308+31+63)*50/100 = 201 + 5 = 206 x1.1 = 226;
        // Timid Spe EV252 -> (260+31+63)*50/100 = 177 + 5 = 182 x1.1 = 200.
        assertEquals(226, mon("mewtwo", 50, nature = Nature.MODEST, evs = spread(0, 0, 0, 252, 0, 0)).stat(dex, Stat.SPA))
        assertEquals(200, mon("mewtwo", 50, nature = Nature.TIMID, evs = spread(0, 0, 0, 0, 0, 252)).stat(dex, Stat.SPE))
        // Blissey L100 HP EV252 IV31: 510+31+63 = 604 + 110 = 714. Shedinja's HP is always 1.
        assertEquals(714, mon("blissey", 100, evs = spread(252, 0, 0, 0, 0, 0)).stat(dex, Stat.HP))
        assertEquals(1, mon("shedinja", 100, evs = spread(252, 0, 0, 0, 0, 0)).stat(dex, Stat.HP))
        // EVs near the 510 cap: 252/252/6 behaves like 252/252/4 for the third stat (floor(6/4) = 1).
        val near = mon("pikachu", 100, evs = spread(6, 252, 0, 0, 0, 252))
        assertEquals(212, near.stat(dex, Stat.HP))
    }

    @Test
    fun `all 25 natures raise and lower the right stats by exactly 10 percent and never touch HP`() {
        // Independent table (stat raised, stat lowered) in the official order.
        val table = listOf(
            "HARDY" to null, "LONELY" to ("atk" to "def"), "BRAVE" to ("atk" to "spe"), "ADAMANT" to ("atk" to "spa"), "NAUGHTY" to ("atk" to "spd"),
            "BOLD" to ("def" to "atk"), "DOCILE" to null, "RELAXED" to ("def" to "spe"), "IMPISH" to ("def" to "spa"), "LAX" to ("def" to "spd"),
            "TIMID" to ("spe" to "atk"), "HASTY" to ("spe" to "def"), "SERIOUS" to null, "JOLLY" to ("spe" to "spa"), "NAIVE" to ("spe" to "spd"),
            "MODEST" to ("spa" to "atk"), "MILD" to ("spa" to "def"), "QUIET" to ("spa" to "spe"), "BASHFUL" to null, "RASH" to ("spa" to "spd"),
            "CALM" to ("spd" to "atk"), "GENTLE" to ("spd" to "def"), "SASSY" to ("spd" to "spe"), "CAREFUL" to ("spd" to "spa"), "QUIRKY" to null,
        )
        assertEquals(25, Nature.entries.size)
        assertEquals(table.map { it.first }, Nature.entries.map { it.name })
        // Mew: every base stat 100. L100 IV31 EV0: raw = 200+31 + 5 = 236; raised 259 (259.6), lowered 212 (212.4); HP 341.
        for ((name, change) in table) {
            val m = mon("mew", 100, nature = Nature.valueOf(name))
            assertEquals(341, m.stat(dex, Stat.HP), "$name HP")
            for (s in Stat.NON_HP) {
                val expected = when (s.id) { change?.first -> 259; change?.second -> 212; else -> 236 }
                assertEquals(expected, m.stat(dex, s), "$name ${s.id}")
            }
        }
    }

    @Test
    fun `nature, IVs and EVs reach the battle engine`() {
        val adamant = mon("garchomp", 50, nature = Nature.ADAMANT, evs = spread(0, 252, 0, 0, 0, 0))
        val modest = mon("garchomp", 50, nature = Nature.MODEST, ivs = spread(31, 0, 31, 31, 31, 31))
        val b = TestSupport.battle(listOf(adamant), listOf(modest))
        assertEquals(adamant.stat(dex, Stat.ATK), b.player.active!!.rawStat(Stat.ATK))
        assertEquals(modest.stat(dex, Stat.ATK), b.opponent.active!!.rawStat(Stat.ATK))
        // (260+31+63)*50/100 = 177 + 5 = 182 x1.1 = 200 vs (260+0)*50/100 = 130 + 5 = 135 x0.9 = 121.
        assertEquals(200, b.player.active!!.rawStat(Stat.ATK)); assertEquals(121, b.opponent.active!!.rawStat(Stat.ATK))
        // Same move, same roll: the Adamant, EV-trained attacker deals more damage.
        fun dmg(attacker: Pokemon): Int {
            val bb = TestSupport.battle(listOf(attacker.copy().also { it.moves.clear(); it.moves.add(MoveSlot("earthquake", 10)) }), listOf(mon("snorlax", 50)))
            bb.forcedRoll = 100; bb.forcedCrit = false
            val before = bb.opponent.active!!.hp
            TestSupport.turn(bb, Choice.UseMove(0), Choice.UseMove(0))
            return before - bb.opponent.active!!.hp
        }
        assertTrue(dmg(adamant) > dmg(modest))
    }

    @Test
    fun `level-up recalculates stats and keeps damage taken`() {
        val m = mon("pikachu", 99, evs = spread(0, 0, 0, 0, 0, 252))
        m.hp = m.maxHp(dex) - 20
        val prog = Progression(dex)
        prog.addExp(m, m.species(dex).growthRate.expForLevel(100) - m.exp)
        assertEquals(100, m.level)
        assertEquals(211, m.maxHp(dex))     // see above
        assertEquals(211 - 20, m.hp)          // HP rises with the max
        assertEquals(279, m.stat(dex, Stat.SPE)) // Hardy: 274 + 5
    }

    @Test
    fun `defeating a Pokemon awards its EV yield once, with caps, Macho Brace and Power items`() {
        // Geodude yields 1 Defense EV.
        val p = mon("mewtwo", 70, moves = listOf("psychic"))
        val b = TestSupport.battle(listOf(p), listOf(mon("geodude", 5)), rules = BattleRules(isTrainerBattle = true))
        TestSupport.turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(1, p.evs.def); assertEquals(1, p.evs.total)
        val brace = mon("pikachu", 50).also { it.heldItem = "machobrace" }
        b.addEvs(brace, dex.species("geodude").evYield); assertEquals(2, brace.evs.def)
        val belt = mon("pikachu", 50).also { it.heldItem = "powerbelt" }
        b.addEvs(belt, dex.species("pidgey").evYield) // 1 Speed + 8 Defense from the Power Belt
        assertEquals(1, belt.evs.spe); assertEquals(8, belt.evs.def)
        val capped = mon("pikachu", 50, evs = spread(0, 0, 252, 0, 0, 0))
        b.addEvs(capped, dex.species("geodude").evYield); assertEquals(252, capped.evs.def)
        val full = mon("pikachu", 50, evs = spread(6, 252, 0, 0, 0, 252))
        b.addEvs(full, dex.species("geodude").evYield); assertEquals(510, full.evs.total)
    }

    @Test
    fun `a level 100 Pokemon still gains EVs but no EXP`() {
        val p = mon("mewtwo", 100, moves = listOf("psychic"))
        val b = TestSupport.battle(listOf(p), listOf(mon("geodude", 5)), rules = BattleRules(isTrainerBattle = true))
        TestSupport.turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(1, p.evs.def)
        assertEquals(0, b.events.count { it is BattleEvent.ExpGain })
    }

    private val content by lazy { WorldContent(DirectoryContentSource(File(System.getProperty("pkmn.assets")))) }
    private fun items(): Pair<GameContext, FieldItems> {
        val ctx = GameContext(dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 1))
        return ctx to FieldItems(ctx)
    }

    @Test
    fun `vitamins, feathers and EV berries respect the caps and keep damage`() {
        val (_, fi) = items()
        val m = mon("pikachu", 100)
        repeat(30) { fi.useOn(m, "protein") }
        assertEquals(252, m.evs.atk, "vitamins stop at 252 (Generation VIII+)")
        assertFalse(fi.useOn(m, "protein").ok)
        m.hp = m.maxHp(dex) - 30
        fi.useOn(m, "hpup")
        assertEquals(10, m.evs.hp)
        assertEquals(m.maxHp(dex) - 30, m.hp, "max HP rose by 2 (floor(10/4)); damage is kept")
        repeat(300) { fi.useOn(m, "swiftfeather") }
        assertEquals(510, m.evs.total)
        assertTrue(m.evs.spe <= 252)
        fi.useOn(m, "kelpsyberry")
        assertEquals(242, m.evs.atk, "EV berries remove 10")
        repeat(30) { fi.useOn(m, "kelpsyberry") }
        assertEquals(0, m.evs.atk)
    }

    @Test
    fun `a mint changes the stat nature but keeps the original`() {
        val (_, fi) = items()
        val m = mon("mew", 100, nature = Nature.MODEST)
        assertTrue(fi.useOn(m, "adamantmint").ok)
        assertEquals(Nature.MODEST, m.nature)
        assertEquals(Nature.ADAMANT, m.statNature)
        assertEquals(259, m.stat(dex, Stat.ATK)); assertEquals(212, m.stat(dex, Stat.SPA))
        assertFalse(fi.useOn(m, "adamantmint").ok)
        assertTrue(fi.useOn(m, "modestmint").ok)
        assertNull(m.mintNature, "a mint matching the original nature restores it")
    }

    @Test
    fun `new Pokemon get random IVs 0-31 and natures, scripted ones get guaranteed IVs`() {
        val rng = TestSupport.rng(42)
        val f = PokemonFactory(dex)
        val natures = HashSet<Nature>()
        repeat(2000) {
            val m = f.create("pidgey", 10, rng)
            for (s in Stat.entries) assertTrue(m.ivs[s] in 0..31)
            assertEquals(0, m.evs.total)
            natures += m.nature
        }
        assertEquals(25, natures.size)
        repeat(50) { val l = f.create("lugia", 70, rng, perfectIvs = 3); assertTrue(Stat.entries.count { l.ivs[it] == 31 } >= 3) }
        assertEquals(Nature.CALM, f.create("lugia", 70, rng, nature = Nature.CALM).nature)
    }
}
