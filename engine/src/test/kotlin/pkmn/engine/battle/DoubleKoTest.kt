package pkmn.engine.battle

import pkmn.engine.TestSupport
import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.mon
import pkmn.engine.TestSupport.turn
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.MoveSlot
import java.io.File
import kotlin.test.*

/**
 * Both sides' last Pokémon faint together (C1-1). Gen 5+ / Showdown: the side of the Pokémon that fainted last wins;
 * the win is checked after every residual handler. Only a mutual forfeit is a draw.
 */
class DoubleKoTest {

    /** Player's last Pokémon and the foe's last Pokémon, both at 1 HP. */
    private fun lastMonsAtOneHp(player: pkmn.engine.mon.Pokemon, foe: pkmn.engine.mon.Pokemon): Battle {
        val b = battle(listOf(player), listOf(foe))
        b.player.active!!.hp = 1
        b.opponent.active!!.hp = 1
        return b
    }

    @Test
    fun `player KOs the foe's last pokemon and faints to double-edge recoil - player wins`() {
        val b = lastMonsAtOneHp(mon("tauros", moves = listOf("doubleedge")), mon("snorlax", moves = listOf("splash")))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.player.mons[0].fainted && b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(BattleOutcome.WIN, b.outcome, b.log.joinToString("\n"))
    }

    @Test
    fun `player KOs the foe's last pokemon and faints to life orb - player wins`() {
        val b = lastMonsAtOneHp(mon("tauros", item = "lifeorb", moves = listOf("tackle")), mon("snorlax", moves = listOf("splash")))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.player.mons[0].fainted && b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(BattleOutcome.WIN, b.outcome, b.log.joinToString("\n"))
    }

    @Test
    fun `player KOs the foe's last pokemon and faints to rocky helmet - player wins`() {
        val b = lastMonsAtOneHp(mon("tauros", moves = listOf("tackle")), mon("snorlax", item = "rockyhelmet", moves = listOf("splash")))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.player.mons[0].fainted && b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(BattleOutcome.WIN, b.outcome, b.log.joinToString("\n"))
    }

    @Test
    fun `foe's last pokemon explodes on the player's last one - player wins`() {
        // Showdown faints the Explosion user before the hit lands, so the target is the last to faint.
        val b = battle(listOf(mon("snorlax", moves = listOf("splash"))), listOf(mon("electrode", level = 100, moves = listOf("explosion"))))
        b.player.active!!.hp = 1
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.player.mons[0].fainted && b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(BattleOutcome.WIN, b.outcome, b.log.joinToString("\n"))
    }

    @Test
    fun `player's last pokemon explodes on the foe's last one - player loses`() {
        val b = battle(listOf(mon("electrode", level = 100, moves = listOf("explosion"))), listOf(mon("snorlax", moves = listOf("splash"))))
        b.opponent.active!!.hp = 1
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.player.mons[0].fainted && b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(BattleOutcome.LOSE, b.outcome, b.log.joinToString("\n"))
    }

    @Test
    fun `residual damage ends the battle at the first side to run out and skips the remaining handlers`() {
        // Both last Pokémon are poisoned at 1 HP. Jolteon (faster) takes its poison damage first and faints; the battle
        // ends there, so slower Slowbro's poison never ticks and the player wins.
        val b = lastMonsAtOneHp(mon("slowbro", moves = listOf("splash")), mon("jolteon", moves = listOf("splash")))
        b.player.active!!.mon.status = MajorStatus.POISON
        b.opponent.active!!.mon.status = MajorStatus.POISON
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertFalse(b.player.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(1, b.player.mons[0].hp)
        assertEquals(BattleOutcome.WIN, b.outcome, b.log.joinToString("\n"))
    }

    @Test
    fun `a mutual forfeit is still a draw`() {
        val s0 = Side(0, "Player", listOf(mon("pikachu")), Controller.PLAYER, dex, gimmicks = BattleRules.ALL_GIMMICKS)
        val s1 = Side(1, "Remote", listOf(mon("eevee")), Controller.REMOTE, dex, gimmicks = BattleRules.ALL_GIMMICKS)
        val b = Battle(dex, listOf(s0, s1), BattleRules.link(), 1)
        b.start()
        assertNull(b.choose(b.player, Choice.Forfeit))
        assertNull(b.choose(b.opponent, Choice.Forfeit))
        b.advance()
        assertEquals(BattleOutcome.DRAW, b.outcome, b.log.joinToString("\n"))
    }

    @Test
    fun `a recoil double KO against a trainer is a win - no blackout and the trainer is marked defeated`() {
        val content = WorldContent(DirectoryContentSource(File(System.getProperty("pkmn.assets"))))
        val c = GameContext(dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))
        val lead = c.factory.create("tauros", 100, c.rng)
        lead.moves.clear(); lead.moves.add(MoveSlot("doubleedge", 15))
        c.acquire(lead)
        c.state.money = 1000
        for (i in 1..8) c.state.badges.add(i) // a level-100 lead obeys
        val trainerId = content.trainers.values.first { it.prize == null && it.trainerClass == "youngster" }.id
        val b = c.createTrainerBattle(trainerId, null, seed = 1)
        for (m in b.opponent.mons) { m.mon.moves.clear(); m.mon.moves.add(MoveSlot("splash", 40)) }
        b.start()
        for (m in b.opponent.mons) m.hp = if (m === b.opponent.active) 1 else 0
        b.player.active!!.hp = 1
        TestSupport.turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(BattleOutcome.WIN, b.outcome, b.log.joinToString("\n"))
        val msgs = c.applyBattleResult(b, trainerId)
        assertTrue(trainerId in c.state.defeatedTrainers)
        assertTrue(msgs.none { "blacked out" in it }, msgs.joinToString("\n"))
        assertTrue(c.state.money > 1000, msgs.joinToString("\n"))
    }
}
