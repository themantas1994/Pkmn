package pkmn.engine.campaign

import pkmn.engine.TestSupport
import pkmn.engine.battle.BattleOutcome
import pkmn.engine.battle.Choice
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.data.EvolutionMethod
import pkmn.engine.save.GameState
import pkmn.engine.world.FieldMove
import pkmn.engine.world.ScriptParser
import pkmn.engine.world.ScriptVM
import java.io.File
import kotlin.test.*

/** Bag, shop, money, PC, evolution items, Rare Candy, trade and field-move policy (audit findings IT-1..IT-5, FM-3). */
class InventoryTest {
    private val content by lazy { WorldContent(DirectoryContentSource(File(System.getProperty("pkmn.assets")))) }
    private val dex get() = TestSupport.dex

    private fun newCtx(): GameContext = GameContext(dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    private fun withParty(vararg species: String, level: Int = 20): GameContext {
        val c = newCtx()
        for (s in species) c.acquire(c.factory.create(s, level, c.rng), offerNickname = false)
        return c
    }

    private fun drive(vm: ScriptVM) { while (true) { val r = vm.next() ?: break; if (r.blocking) vm.resume(0) } }

    // ---------------------------------------------------------------- IT-1: battle items

    private fun battleOf(c: GameContext) = c.createWildBattle(c.factory.create("pidgey", 5, c.rng), null).also { it.start() }

    @Test
    fun `a potion at full HP is rejected and costs nothing`() {
        val c = withParty("cyndaquil"); c.state.addItem("potion", 3)
        val b = battleOf(c)
        assertNotNull(b.choose(b.player, Choice.UseItem("potion", 0)))
        assertTrue(b.itemsUsed.isEmpty())
        assertEquals(emptyList(), c.settleBattleItems(b))
        assertEquals(3, c.state.itemCount("potion"))
    }

    @Test
    fun `max revive on a living Pokemon is rejected`() {
        val c = withParty("cyndaquil", "totodile"); c.state.addItem("maxrevive")
        val b = battleOf(c)
        assertNotNull(b.choose(b.player, Choice.UseItem("maxrevive", 1)))
        c.settleBattleItems(b)
        assertEquals(1, c.state.itemCount("maxrevive"))
    }

    @Test
    fun `an accepted potion is spent exactly once`() {
        val c = withParty("cyndaquil"); c.state.addItem("potion", 2)
        val b = battleOf(c)
        b.player.mons[0].hp = 3
        assertNull(b.choose(b.player, Choice.UseItem("potion", 0)))
        b.advance()
        assertTrue(b.player.mons[0].hp > 3)
        assertEquals(listOf("potion"), c.settleBattleItems(b))
        assertEquals(1, c.state.itemCount("potion"))
        c.settleBattleItems(b); c.applyBattleResult(b, null)
        assertEquals(1, c.state.itemCount("potion"), "settling again must not charge twice")
    }

    @Test
    fun `an accepted revive on a fainted Pokemon is spent`() {
        val c = withParty("cyndaquil", "totodile"); c.state.addItem("revive")
        c.state.party[1].hp = 0
        val b = battleOf(c)
        assertNull(b.choose(b.player, Choice.UseItem("revive", 1)))
        b.advance()
        assertFalse(b.player.mons[1].fainted)
        c.settleBattleItems(b)
        assertEquals(0, c.state.itemCount("revive"))
    }

    @Test
    fun `cancelling the bag chooses nothing so nothing is spent`() {
        val c = withParty("cyndaquil"); c.state.addItem("potion")
        val b = battleOf(c)
        assertEquals(emptyList(), c.settleBattleItems(b))
        assertEquals(1, c.state.itemCount("potion"))
    }

    @Test
    fun `insufficient items cannot be spent`() {
        val c = withParty("cyndaquil")
        val b = battleOf(c)
        assertFalse(c.canSpendBattleItem(b, "potion"))
        c.state.addItem("potion")
        assertTrue(c.canSpendBattleItem(b, "potion"))
        b.player.mons[0].hp = 3
        assertNull(b.choose(b.player, Choice.UseItem("potion", 0))); b.advance()
        assertFalse(c.canSpendBattleItem(b, "potion"), "the used, unsettled potion is reserved")
    }

    @Test
    fun `a thrown ball is spent whether or not it catches`() {
        val c = withParty("cyndaquil"); c.state.addItem("pokeball", 5)
        val b = battleOf(c)
        assertNull(b.choose(b.player, Choice.UseItem("pokeball")))
        b.advance()
        c.settleBattleItems(b)
        assertEquals(4, c.state.itemCount("pokeball"))
    }

    // ---------------------------------------------------------------- IT-2: evolution items

    @Test
    fun `every use-item evolution keeps its item until the evolution completes`() {
        val fi = FieldItems(newCtx())
        var tested = 0
        for (sp in dex.species.values) for (evo in sp.evolutions) {
            if (evo.method != EvolutionMethod.USE_ITEM || evo.item == null) continue
            val c = newCtx()
            val mon = c.factory.create(sp.id, 30, c.rng)
            c.acquire(mon, offerNickname = false)
            c.state.addItem(evo.item!!)
            val r = FieldItems(c).useOn(mon, evo.item!!)
            if (r.evolveTo == null) continue // gender / region / party conditions not met by this specimen
            tested++
            assertTrue(r.ok && r.consumeOnEvolve && !r.consumed, "${sp.id} + ${evo.item}")
            // B-cancel: the screen never calls completeItemEvolution.
            assertEquals(1, c.state.itemCount(evo.item!!), "cancel keeps ${evo.item}")
            assertEquals(sp.id, mon.speciesId)
            c.completeItemEvolution(mon, r.evolveTo!!, evo.item!!)
            assertEquals(r.evolveTo, mon.speciesId)
            assertEquals(0, c.state.itemCount(evo.item!!), "completing spends ${evo.item}")
        }
        assertTrue(tested >= 8, "only $tested item evolutions exercised")
        assertNotNull(fi)
    }

    @Test
    fun `the linking cord is spent only when the trade evolution happens`() {
        val c = withParty("machoke"); c.state.addItem("linkingcord")
        val mon = c.state.party[0]
        val r = FieldItems(c).useOn(mon, "linkingcord")
        assertEquals("machamp", r.evolveTo)
        assertTrue(r.consumeOnEvolve && !r.consumed)
        assertEquals(1, c.state.itemCount("linkingcord"))
        c.completeItemEvolution(mon, "machamp", "linkingcord")
        assertEquals(0, c.state.itemCount("linkingcord"))
    }

    @Test
    fun `an item with no evolution for the Pokemon is rejected and kept`() {
        val c = withParty("machoke"); c.state.addItem("firestone")
        val r = FieldItems(c).useOn(c.state.party[0], "firestone")
        assertFalse(r.ok); assertFalse(r.consumed)
    }

    // ---------------------------------------------------------------- IT-3: Rare Candy cap

    @Test
    fun `rare candy stops at the level cap`() {
        val c = withParty("cyndaquil"); c.state.options.difficulty = 2
        val cap = c.levelCap(); assertTrue(cap < 100)
        val mon = c.state.party[0]
        val fi = FieldItems(c)
        mon.level = cap - 1; mon.exp = mon.species(dex).growthRate.expForLevel(cap - 1)
        val up = fi.useOn(mon, "rarecandy")
        assertTrue(up.ok && up.consumed); assertEquals(cap, mon.level)
        val expAtCap = mon.exp
        val blocked = fi.useOn(mon, "rarecandy")
        assertFalse(blocked.ok); assertFalse(blocked.consumed)
        assertEquals(cap, mon.level); assertEquals(expAtCap, mon.exp)
        mon.level = cap + 5
        assertFalse(fi.useOn(mon, "rarecandy").ok, "above the cap")
    }

    @Test
    fun `rare candy below the cap and without a cap, and never past 100`() {
        val c = withParty("cyndaquil"); val fi = FieldItems(c); val mon = c.state.party[0]
        c.state.options.difficulty = 2
        mon.level = 5; mon.exp = mon.species(dex).growthRate.expForLevel(5)
        assertEquals(6, fi.useOn(mon, "rarecandy").let { assertTrue(it.ok); mon.level })
        c.state.options.difficulty = 1
        mon.level = 60; mon.exp = mon.species(dex).growthRate.expForLevel(60)
        assertTrue(fi.useOn(mon, "rarecandy").ok); assertEquals(61, mon.level)
        mon.level = 100
        assertFalse(fi.useOn(mon, "rarecandy").ok)
    }

    // ---------------------------------------------------------------- IT-4: inventory capacity, shop, TMs

    @Test
    fun `addItem never silently discards quantity`() {
        val s = GameState()
        assertEquals(990, s.addItem("pokeball", 990))
        assertEquals(9, s.addItem("pokeball", 50))
        assertEquals(999, s.itemCount("pokeball"))
        assertEquals(0, s.addItem("pokeball", 1))
        assertEquals(0, s.addItem("pokeball", -4))
    }

    @Test
    fun `gifts into a full stack stay with the caller`() {
        val c = newCtx(); c.state.addItem("potion", 995)
        val whole = c.giveItem("potion", 10)
        assertEquals(0, whole.accepted); assertEquals(10, whole.leftover); assertFalse(whole.complete)
        assertEquals(995, c.state.itemCount("potion"))
        val part = c.giveItem("potion", 10, allOrNothing = false)
        assertEquals(4, part.accepted); assertEquals(6, part.leftover)
        assertEquals(999, c.state.itemCount("potion"))
    }

    @Test
    fun `giveitem script reports a full stack and keeps the reward pending`() {
        val c = newCtx(); c.state.addItem("potion", 999)
        val script = ScriptParser.parseFile("script t\n  giveitem potion 3\nend\n", "t.pks").single()
        val vm = ScriptVM(c, script, { null })
        drive(vm)
        assertEquals(0, vm.result, "0 = not given, the script keeps the reward pending")
        assertEquals(999, c.state.itemCount("potion"))
        c.state.bag["potion"] = 990
        val vm2 = ScriptVM(c, script, { null }); drive(vm2)
        assertEquals(1, vm2.result); assertEquals(993, c.state.itemCount("potion"))
    }

    @Test
    fun `buying never charges for items the bag cannot take`() {
        val c = newCtx(); c.state.money = 100_000; c.state.addItem("pokeball", 950)
        assertEquals(49, c.maxPurchase("pokeball", 200))
        val refused = c.buy("pokeball", 99, 200)
        assertFalse(refused.ok); assertEquals(100_000, c.state.money); assertEquals(950, c.state.itemCount("pokeball"))
        val ok = c.buy("pokeball", 49, 200)
        assertTrue(ok.ok); assertEquals(100_000 - 49 * 200, c.state.money)
        assertEquals(999, c.state.itemCount("pokeball"))
        assertEquals(0, c.maxPurchase("pokeball", 200))
    }

    @Test
    fun `buying with too little money or room changes nothing`() {
        val c = newCtx(); c.state.money = 150
        val r = c.buy("potion", 2, 100)
        assertFalse(r.ok); assertEquals(150, c.state.money); assertEquals(0, c.state.itemCount("potion"))
        assertEquals(1, c.maxPurchase("potion", 100))
        assertFalse(c.buy("potion", 0, 100).ok)
    }

    @Test
    fun `ten pokeballs add a premier ball bonus only as far as it fits`() {
        val c = newCtx(); c.state.money = 10_000
        val r = c.buy("pokeball", 10, 200)
        assertEquals(1, r.bonus); assertEquals(1, c.state.itemCount("premierball"))
        c.state.bag["premierball"] = 999
        assertEquals(0, c.buy("pokeball", 10, 200).bonus)
        assertEquals(999, c.state.itemCount("premierball"))
    }

    @Test
    fun `TMs are reusable so a second purchase or gift is refused or absorbed`() {
        val c = newCtx(); c.state.money = 10_000
        assertTrue(c.buy("tm:bulletseed", 1, 3000).ok)
        assertEquals(7000, c.state.money)
        val again = c.buy("tm:bulletseed", 1, 3000)
        assertFalse(again.ok); assertEquals(7000, c.state.money); assertEquals(0, c.maxPurchase("tm:bulletseed", 3000))
        assertEquals(1, c.state.itemCount("tm:bulletseed"))
        assertTrue(c.giveItem("tm:bulletseed").complete, "a duplicate TM gift loses nothing")
        assertEquals(1, c.state.itemCount("tm:bulletseed"))
    }

    // ---------------------------------------------------------------- IT-5: money, PC, dusk, trade

    @Test
    fun `money has one cap for prizes, pay day, selling and scripts`() {
        val c = newCtx(); c.state.money = GameState.MAX_MONEY - 10
        c.state.addItem("potion", 5)
        assertFalse(c.sell("potion", 5, 150).ok, "selling past the cap is refused")
        assertEquals(0, c.maxSale("potion", 150))
        assertEquals(5, c.state.itemCount("potion"))
        c.state.money = GameState.MAX_MONEY - 400
        assertEquals(2, c.maxSale("potion", 150))
        assertTrue(c.sell("potion", 2, 150).ok); assertEquals(GameState.MAX_MONEY - 100, c.state.money)
        assertEquals(100, c.state.addMoney(5_000)); assertEquals(GameState.MAX_MONEY, c.state.money)
    }

    @Test
    fun `givemoney clamps at zero and at the cap`() {
        val c = newCtx(); c.state.money = 300
        fun run(src: String) = drive(ScriptVM(c, ScriptParser.parseFile("script t\n  $src\nend\n", "t.pks").single(), { null }))
        run("givemoney -500"); assertEquals(0, c.state.money)
        run("givemoney -1"); assertEquals(0, c.state.money)
        run("givemoney 200"); assertEquals(200, c.state.money)
        run("givemoney -50"); assertEquals(150, c.state.money)
        run("givemoney 99999999"); assertEquals(GameState.MAX_MONEY, c.state.money)
    }

    @Test
    fun `the PC keeps a Pokemon that can still fight`() {
        val c = withParty("cyndaquil", "totodile", "chikorita")
        assertNull(c.depositError(0))
        c.state.party[0].hp = 0; c.state.party[2].hp = 0
        assertNotNull(c.depositError(1), "the only able Pokemon stays")
        assertNull(c.depositError(0), "a fainted one may go")
        assertNotNull(c.depositToPc(1)); assertEquals(3, c.state.party.size)
        assertNull(c.depositToPc(0)); assertEquals(2, c.state.party.size)
        c.state.party.removeAt(0)
        assertNotNull(c.depositError(0), "the last Pokemon stays")
    }

    @Test
    fun `a full PC refuses a deposit`() {
        val c = withParty("cyndaquil", "totodile")
        for (box in c.state.boxes) for (i in box.indices) box[i] = c.factory.create("rattata", 3, c.rng)
        assertEquals("The PC is full!", c.depositError(0))
    }

    @Test
    fun `dusk evolution respects ALWAYS DAY`() {
        val c = withParty("rockruffdusk", level = 26)
        c.clock = GameClock.fixed(java.time.LocalDateTime.of(2026, 10, 15, 17, 30))
        c.state.options.realTimeClock = true
        assertEquals("lycanrocdusk", c.pendingEvolutions(listOf(0), null).firstOrNull()?.second)
        c.state.options.realTimeClock = false
        assertNull(c.pendingEvolutions(listOf(0), null).firstOrNull())
    }

    @Test
    fun `a traded-in Pokemon goes through acquire and earns the traded exp bonus`() {
        val c = withParty("abra", "cyndaquil")
        val before = c.state.party[0].uid
        val received = c.factory.create("haunter", 25, c.rng)
        received.otName = "TRADER"; received.otId = c.state.trainerId // an unlucky random OT id must not defeat the bonus
        val evo = c.performTrade(0, "abra", received)
        assertSame(received, c.state.party[0], "keeps the slot")
        assertEquals(2, c.state.party.size)
        assertEquals("cyndaquil", c.state.party[1].speciesId)
        assertTrue("haunter" in c.state.caught && received.uid != 0L && received.uid != before)
        assertNotEquals(c.state.trainerId, received.otId)
        assertEquals("gengar", evo)
        assertNull(c.state.findByUid(before), "the given Pokemon is gone")
    }

    @Test
    fun `traded Pokemon earn 1_5x experience`() {
        fun expGain(traded: Boolean): Int {
            val c = newCtx()
            val m = c.factory.create("cyndaquil", 10, c.rng)
            if (traded) { m.otName = "TRADER"; m.otId = (c.state.trainerId + 7) and 0xFFFF } else { m.otName = c.state.playerName; m.otId = c.state.trainerId }
            c.acquire(m, offerNickname = false)
            val before = m.exp
            val wild = c.factory.create("pidgey", 10, c.rng)
            val b = c.createWildBattle(wild, null); b.start()
            while (b.outcome == BattleOutcome.ONGOING) { assertNull(b.choose(b.player, Choice.UseItem("masterball"))); b.advance() }
            return m.exp - before
        }
        val own = expGain(false); val traded = expGain(true)
        assertTrue(own > 10)
        assertTrue(traded in (own * 3 / 2 - 1)..(own * 3 / 2 + 1), "own=$own traded=$traded")
    }

    @Test
    fun `acquire reports a full storage`() {
        val c = withParty("cyndaquil")
        repeat(5) { c.acquire(c.factory.create("rattata", 3, c.rng), offerNickname = false) }
        val inPc = c.acquire(c.factory.create("rattata", 3, c.rng), offerNickname = false)
        assertTrue(inPc.stored && inPc.inPc && !inPc.inParty)
        for (box in c.state.boxes) for (i in box.indices) if (box[i] == null) box[i] = c.factory.create("rattata", 3, c.rng)
        val none = c.acquire(c.factory.create("pidgey", 3, c.rng), offerNickname = false)
        assertTrue(none.storageFull && !none.stored); assertNull(none.where)
        assertFalse("pidgey" in c.state.caught, "nothing is registered for a Pokemon that was not stored")
    }

    // ---------------------------------------------------------------- FM-3: field-move badges

    @Test
    fun `field moves need the named badge, not a badge count`() {
        val c = newCtx()
        // Out of order: badges 5, 6, 7 are three badges but not Strength's badge (3) or Cut's badge (1).
        c.state.badges.addAll(listOf(5, 6, 7))
        assertFalse(c.canUseFieldMove(FieldMove.CUT)); assertFalse(c.canUseFieldMove(FieldMove.STRENGTH))
        assertTrue(c.canUseFieldMove(FieldMove.FLY)); assertTrue(c.canUseFieldMove(FieldMove.WATERFALL))
        // Holding a move's badge without the preceding ones is enough for that move only.
        c.state.badges.clear(); c.state.badges.add(4)
        assertTrue(c.canUseFieldMove(FieldMove.SURF))
        assertFalse(c.canUseFieldMove(FieldMove.CUT)); assertFalse(c.canUseFieldMove(FieldMove.FLY))
    }
}
