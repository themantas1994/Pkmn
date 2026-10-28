package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.factory
import pkmn.engine.TestSupport.mon
import pkmn.engine.data.FormKind
import pkmn.engine.util.Rng
import kotlin.test.*

/** Doubles step A: per-slot actives, one request per slot, chosen targets, per-slot switching and replacement. */
class DoublesStepATest {
    private val doubles = BattleRules(isTrainerBattle = true, format = BattleFormat.DOUBLES)

    private fun choose(b: Battle, side: Side, slot: Int, c: Choice) = assertNull(b.choose(side, c, slot), "choice $c for slot $slot")

    @Test
    fun `doubles sends out two Pokemon per side and asks once per slot`() {
        val b = battle(listOf(mon("pikachu"), mon("eevee"), mon("snorlax")), listOf(mon("rattata"), mon("pidgey")), rules = doubles)
        assertEquals(2, b.player.actives.size)
        assertEquals(listOf("pikachu", "eevee"), b.player.actives.map { it!!.species.id })
        assertEquals(listOf("rattata", "pidgey"), b.opponent.actives.map { it!!.species.id })
        assertEquals(4, b.events.count { it is BattleEvent.SwitchIn })
        val reqs = b.requests(b.player)
        assertEquals(listOf(0, 1), reqs.map { (it as Request.Move).slot })
        assertSame(b.player.actives[1], (reqs[1] as Request.Move).active)
        assertEquals(1, b.player.actives[1]!!.slot)
        assertEquals(listOf("snorlax"), b.player.canSwitchTo().map { it.species.id })
    }

    @Test
    fun `singles keeps one slot and the original request`() {
        val b = battle(listOf(mon("pikachu"), mon("eevee")), listOf(mon("rattata")))
        assertEquals(1, b.player.actives.size)
        assertSame(b.player.active, b.player.actives[0])
        assertEquals(listOf(b.request(b.player)), b.requests(b.player))
        assertEquals(Request.Wait, b.request(b.player, 1))
    }

    @Test
    fun `a chosen target is hit and the other foe is not`() {
        val b = battle(listOf(mon("machamp", moves = listOf("tackle")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("snorlax", moves = listOf("splash")), mon("blissey", moves = listOf("splash"))), rules = doubles)
        b.forcedCrit = false
        val (left, right) = b.opponent.actives.map { it!! }
        val l0 = left.hp; val r0 = right.hp
        choose(b, b.player, 0, Choice.UseMove(0, target = 2))
        choose(b, b.player, 1, Choice.UseMove(0))
        assertTrue(b.advance())
        assertEquals(l0, left.hp, "slot 0 foe untouched")
        assertTrue(right.hp < r0, "slot 1 foe hit")
        assertTrue(b.events.any { it is BattleEvent.HpChange && it.side == 1 && it.teamIndex == 1 && it.delta < 0 })
    }

    @Test
    fun `target locations are validated`() {
        val b = battle(listOf(mon("machamp", moves = listOf("tackle", "swordsdance")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("snorlax", moves = listOf("splash")), mon("blissey", moves = listOf("splash"))), rules = doubles)
        assertEquals("Invalid target", b.choose(b.player, Choice.UseMove(0, target = 3), 0))
        assertEquals("Can't target itself", b.choose(b.player, Choice.UseMove(0, target = -1), 0))
        // A fixed-target move ignores the location.
        assertNull(b.choose(b.player, Choice.UseMove(1, target = -1), 0))
        // Hitting the ally is allowed for a "normal" move.
        assertNull(b.choose(b.player, Choice.UseMove(0, target = -2), 0))
    }

    @Test
    fun `an attack can hit the user's own ally`() {
        val b = battle(listOf(mon("machamp", moves = listOf("tackle")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("snorlax", moves = listOf("splash")), mon("blissey", moves = listOf("splash"))), rules = doubles)
        b.forcedCrit = false
        val ally = b.player.actives[1]!!
        val hp = ally.hp
        choose(b, b.player, 0, Choice.UseMove(0, target = -2))
        choose(b, b.player, 1, Choice.UseMove(0))
        b.advance()
        assertTrue(ally.hp < hp)
    }

    @Test
    fun `a fainted foe is replaced in its own slot`() {
        val b = battle(listOf(mon("machamp", level = 100, moves = listOf("closecombat")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("snorlax", moves = listOf("splash")), mon("rattata", level = 2, moves = listOf("splash")), mon("pidgey", moves = listOf("splash"))),
            rules = doubles)
        val left = b.opponent.actives[0]
        choose(b, b.player, 0, Choice.UseMove(0, target = 2))
        choose(b, b.player, 1, Choice.UseMove(0))
        b.advance()
        assertTrue(b.opponent.mons[1].fainted)
        assertTrue(b.needsReplacement(b.opponent))
        assertEquals(Request.Wait, b.request(b.player, 0), "nobody chooses during the replacement")
        b.advance()
        assertSame(left, b.opponent.actives[0])
        assertEquals("pidgey", b.opponent.actives[1]!!.species.id)
        assertFalse(b.needsReplacement(b.opponent))
        assertTrue(b.requests(b.player).all { it is Request.Move })
    }

    @Test
    fun `a slot stays empty when the bench is empty`() {
        val b = battle(listOf(mon("machamp", level = 100, moves = listOf("closecombat")), mon("eevee", moves = listOf("splash"))),
            listOf(mon("snorlax", moves = listOf("splash")), mon("rattata", level = 2, moves = listOf("splash"))), rules = doubles)
        choose(b, b.player, 0, Choice.UseMove(0, target = 2))
        choose(b, b.player, 1, Choice.UseMove(0))
        b.advance()
        assertEquals(BattleOutcome.ONGOING, b.outcome)
        assertFalse(b.needsReplacement(b.opponent))
        assertEquals(Request.Wait, b.request(b.opponent, 1))
        assertTrue(b.opponent.actives[1]!!.fainted)
        // The turn runs with three choices; the default target skips the fainted slot.
        val snorlax = b.opponent.actives[0]!!
        val hp = snorlax.hp
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0))
        assertTrue(b.advance())
        assertTrue(snorlax.hp < hp || snorlax.fainted)
    }

    @Test
    fun `two slots cannot switch to the same Pokemon`() {
        val b = battle(listOf(mon("pikachu"), mon("eevee"), mon("snorlax"), mon("pidgey")), listOf(mon("rattata"), mon("pidgey")), rules = doubles)
        choose(b, b.player, 0, Choice.Switch(2))
        assertEquals("Cannot switch to that Pokémon", b.choose(b.player, Choice.Switch(2), 1))
        choose(b, b.player, 1, Choice.Switch(3))
        b.advance()
        assertEquals(listOf("snorlax", "pidgey"), b.player.actives.map { it!!.species.id })
    }

    @Test
    fun `a self-switch from slot 1 asks for that slot and resumes the turn`() {
        val b = battle(listOf(mon("pikachu", moves = listOf("splash")), mon("scizor", level = 100, moves = listOf("uturn")), mon("snorlax")),
            listOf(mon("rattata", moves = listOf("splash")), mon("pidgey", moves = listOf("splash"))), rules = doubles)
        choose(b, b.player, 0, Choice.UseMove(0))
        choose(b, b.player, 1, Choice.UseMove(0, target = 1))
        b.advance()
        val req = b.request(b.player, 1)
        assertEquals(Request.SwitchIn(forced = true, selfSwitch = true, slot = 1), req)
        assertEquals(Request.Wait, b.request(b.player, 0))
        assertNull(b.choose(b.player, Choice.Switch(2), 1))
        assertEquals("snorlax", b.player.actives[1]!!.species.id)
        assertEquals("pikachu", b.player.actives[0]!!.species.id)
        assertTrue(b.requests(b.player).all { it is Request.Move })
    }

    @Test
    fun `random AI doubles battles run without errors`() {
        val pool = dex.species.values.filter { it.formKind == FormKind.BASE && it.isNonstandard == null }
        for (i in 0 until 40) {
            val rng = Rng(7000L + i)
            fun randomMon(): pkmn.engine.mon.Pokemon {
                val sp = pool[rng.nextInt(pool.size)]
                val ls = dex.learnset(sp.id)
                val moves = (ls?.levelUp?.map { it.move }.orEmpty()).distinct().ifEmpty { listOf("tackle") }
                return factory.create(sp.id, 50, rng, moves = List(4) { moves[rng.nextInt(moves.size)] }.distinct())
            }
            val s0 = Side(0, "A", List(2 + rng.nextInt(5)) { randomMon() }, Controller.PLAYER, dex, aiLevel = 2)
            val s1 = Side(1, "B", List(2 + rng.nextInt(5)) { randomMon() }, Controller.AI, dex, aiLevel = 1 + i % 3)
            val b = Battle(dex, listOf(s0, s1), doubles, 7000L + i)
            val ai = BattleAI()
            try {
                b.start()
                var guard = 0
                while (b.outcome == BattleOutcome.ONGOING && guard++ < 400) {
                    // Read each slot's request fresh: a choice for slot 0 can resolve a replacement or resume the turn.
                    for (slot in 0 until 2) when (val r = b.request(s0, slot)) {
                        is Request.SwitchIn -> check(s0.canSwitchTo().any { b.choose(s0, Choice.Switch(s0.mons.indexOf(it)), slot) == null }) {
                            "no replacement accepted for slot $slot ($r)"
                        }
                        is Request.Move -> if (b.choose(s0, ai.chooseAction(b, s0, slot), slot) != null) b.choose(s0, Choice.Pass, slot)
                        Request.Wait -> {}
                    }
                    b.advance(ai)
                    for (s in b.sides) {
                        val act = s.actives.filterNotNull()
                        check(act.size == act.toSet().size) { "a Pokémon is in two slots" }
                    }
                }
                check(b.outcome != BattleOutcome.ONGOING || b.turn >= 100) { "battle stalled at turn ${b.turn}" }
            } catch (e: Throwable) {
                throw AssertionError("doubles battle $i failed at turn ${b.turn}: $e\n${b.log.takeLast(12).joinToString("\n")}", e)
            }
        }
    }
}
