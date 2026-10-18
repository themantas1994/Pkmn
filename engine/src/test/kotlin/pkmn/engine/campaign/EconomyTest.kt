package pkmn.engine.campaign

import pkmn.engine.TestSupport
import pkmn.engine.battle.Battle
import pkmn.engine.battle.BattleOutcome
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.save.GameState
import java.io.File
import kotlin.test.*

/** Trainer prize money vs the defeat penalty. */
class EconomyTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private val trainerId get() = content.trainers.values.first { it.prize == null && it.trainerClass == "youngster" }.id

    private fun ctx(money: Int, level: Int = 10, badges: Int = 0): GameContext {
        val c = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))
        c.acquire(c.factory.create("cyndaquil", level, c.rng))
        c.state.money = money
        for (i in 1..badges) c.state.badges.add(i)
        return c
    }

    private fun battle(c: GameContext, outcome: BattleOutcome, trainer: String? = null): Battle {
        val b = if (trainer != null) c.createTrainerBattle(trainer, null)
        else c.createWildBattle(c.factory.create("pidgey", 5, c.rng), null)
        // The outcome has a private setter; the engine battle flow is covered elsewhere, this suite tests only the money rules.
        Battle::class.java.getDeclaredField("outcome").apply { isAccessible = true }.set(b, outcome)
        return b
    }

    @Test
    fun `winning pays class payout times highest level, once`() {
        val c = ctx(1000)
        val prize = c.prizeMoney(trainerId)
        assertTrue(prize > 0)
        val b = battle(c, BattleOutcome.WIN, trainerId)
        c.applyBattleResult(b, trainerId)
        assertEquals(1000 + prize, c.state.money)
        c.applyBattleResult(b, trainerId) // re-entered callback
        c.applyBattleResult(battle(c, BattleOutcome.WIN, trainerId), trainerId) // rematch of a beaten trainer
        assertEquals(1000 + prize, c.state.money)
    }

    @Test
    fun `defeat costs 4 x (1 + badges) x highest level, not half the wallet`() {
        for ((badges, level, expected) in listOf(Triple(0, 10, 40), Triple(0, 5, 20), Triple(3, 30, 480), Triple(8, 60, 2160))) {
            val c = ctx(1_000_000, level, badges)
            c.applyBattleResult(battle(c, BattleOutcome.LOSE, trainerId), trainerId)
            assertEquals(1_000_000 - expected, c.state.money, "badges=$badges level=$level")
        }
    }

    @Test
    fun `defeat penalty is comparable to the prize of a same-level trainer`() {
        val c = ctx(5000, 10)
        val t = content.trainers.getValue(trainerId)
        val level = t.team.maxOf { it.level }
        val c2 = ctx(5000, level)
        assertTrue(c2.defeatPenalty() <= c2.prizeMoney(trainerId), "early penalty ${c2.defeatPenalty()} vs prize ${c2.prizeMoney(trainerId)}")
        assertEquals(40, c.defeatPenalty())
    }

    @Test
    fun `penalty never exceeds half the wallet or goes negative`() {
        for ((money, expected) in listOf(0 to 0, 1 to 0, 2 to 1, 79 to 39, 80 to 40, 81 to 40, 9_999_999 to 40)) {
            val c = ctx(money)
            c.applyBattleResult(battle(c, BattleOutcome.LOSE, trainerId), trainerId)
            assertEquals(money - expected, c.state.money, "money=$money")
            assertTrue(c.state.money >= 0)
        }
    }

    @Test
    fun `one defeat charges exactly once even if the result is applied again`() {
        val c = ctx(1000)
        val b = battle(c, BattleOutcome.LOSE, trainerId)
        c.applyBattleResult(b, trainerId); c.applyBattleResult(b, trainerId)
        assertEquals(960, c.state.money)
    }

    @Test
    fun `wild battles pay no prize and a wild defeat uses the same penalty`() {
        val c = ctx(1000)
        c.applyBattleResult(battle(c, BattleOutcome.WIN), null)
        assertEquals(1000, c.state.money)
        c.applyBattleResult(battle(c, BattleOutcome.LOSE), null)
        assertEquals(960, c.state.money)
    }

    @Test
    fun `a defeat in a scripted battle that may be lost costs nothing`() {
        val c = ctx(1000)
        val msgs = c.applyBattleResult(battle(c, BattleOutcome.LOSE, trainerId), trainerId, canLose = true)
        assertEquals(1000, c.state.money)
        assertTrue(msgs.isEmpty())
    }

    @Test
    fun `balance after a defeat survives a save and load`() {
        val c = ctx(1000)
        c.applyBattleResult(battle(c, BattleOutcome.LOSE, trainerId), trainerId)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val back = json.decodeFromString<GameState>(json.encodeToString(GameState.serializer(), c.state))
        assertEquals(960, back.money)
    }
}
