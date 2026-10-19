package pkmn.engine.battle

import pkmn.engine.TestSupport.battle
import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.mon
import pkmn.engine.TestSupport.turn
import pkmn.engine.mon.MajorStatus
import kotlin.test.*

/**
 * Regression tests for the battle-mechanics audit (findings B1-B13). Target rules are Gen IX as implemented by Pokémon
 * Showdown (data in data-src/showdown); each test names the rule it pins.
 */
class MechanicsAuditTest {

    private val wildRules = BattleRules()

    private fun dmg(b: Battle, user: BattleMon, target: BattleMon, move: String): Int {
        b.forcedRoll = 100; b.forcedCrit = false
        val r = b.calcDamage(MoveCtx(user, target, ActiveMove(dex.move(move))))!!
        b.forcedRoll = null; b.forcedCrit = null
        return r
    }

    private fun logSince(b: Battle, mark: Int) = b.log.drop(mark)

    // ------------------------------------------------------------------ B1 Fury Cutter

    private fun furyCutterPower(b: Battle, user: BattleMon) =
        Effects.move("furycutter")!!.basePower(b, MoveCtx(user, b.opponent.active!!, ActiveMove(dex.move("furycutter"))), 40)

    @Test
    fun `fury cutter doubles on consecutive hits up to 160 and resets after a turn without it`() {
        val b = battle(listOf(mon("scizor", moves = listOf("furycutter", "splash"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val user = b.player.active!!
        user.boosts[BattleMon.boostIndex("accuracy")] = 6
        assertEquals(40, furyCutterPower(b, user))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(80, furyCutterPower(b, user))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(160, furyCutterPower(b, user))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(160, furyCutterPower(b, user), "capped at 160")
        // A turn without Fury Cutter: the chain expires at the end of that turn.
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertEquals(40, furyCutterPower(b, user))
    }

    @Test
    fun `fury cutter resets when it fails and when the user switches out`() {
        val b = battle(listOf(mon("scizor", moves = listOf("furycutter")), mon("pikachu", moves = listOf("splash"))),
            listOf(mon("snorlax", level = 100, moves = listOf("splash", "protect"))))
        val user = b.player.active!!
        user.boosts[BattleMon.boostIndex("accuracy")] = 6
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(160, furyCutterPower(b, user))
        turn(b, Choice.UseMove(0), Choice.UseMove(1)) // blocked by Protect
        assertTrue(b.log.any { it.contains("protected itself") })
        assertEquals(40, furyCutterPower(b, user), "a blocked Fury Cutter resets the chain")
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(80, furyCutterPower(b, user))
        turn(b, Choice.Switch(1), Choice.UseMove(0))
        turn(b, Choice.Switch(0), Choice.UseMove(0))
        assertEquals(40, furyCutterPower(b, user), "switching out resets the chain")
    }

    // ------------------------------------------------------------------ B2 faint attribution

    private fun moxieSetup(foeHp: Int, foeMoves: List<String> = listOf("splash")): Battle {
        val b = battle(listOf(mon("gyarados", ability = "moxie", moves = listOf("seismictoss", "splash"))),
            listOf(mon("snorlax", level = 50, ability = "thickfat", moves = foeMoves), mon("pidgey", level = 5, moves = listOf("splash"))))
        b.opponent.active!!.hp = foeHp
        return b
    }

    @Test
    fun `an attack KO is credited to the attacker`() {
        val b = moxieSetup(foeHp = 50)
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.opponent.mons[0].fainted)
        assertEquals(1, b.player.active!!.boost("atk"), "Moxie after a direct KO")
    }

    @Test
    fun `fainting to poison after being hit earlier in the turn is not an attack KO`() {
        val b = moxieSetup(foeHp = 51)
        b.opponent.active!!.mon.status = MajorStatus.POISON
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(0, b.player.active!!.boost("atk"))
    }

    @Test
    fun `fainting to weather after being hit earlier in the turn is not an attack KO`() {
        val b = moxieSetup(foeHp = 51)
        b.setWeather("sandstorm", null)
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(0, b.player.active!!.boost("atk"))
    }

    @Test
    fun `fainting to its own recoil after being hit earlier in the turn is not an attack KO`() {
        // Gyarados moves first (Seismic Toss leaves 1 HP), then the slower foe faints to Double-Edge recoil.
        val b = moxieSetup(foeHp = 51, foeMoves = listOf("doubleedge"))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertTrue(b.log.any { it.contains("damaged by the recoil") })
        assertEquals(0, b.player.active!!.boost("atk"))
    }

    @Test
    fun `fainting to a hazard after an earlier hit is not an attack KO`() {
        val b = moxieSetup(foeHp = 51)
        turn(b, Choice.UseMove(0), Choice.UseMove(0)) // 1 HP left
        b.addSideCondition(b.opponent, "stealthrock", b.player.active)
        turn(b, Choice.UseMove(1), Choice.Switch(1))
        turn(b, Choice.UseMove(1), Choice.Switch(0))
        assertTrue(b.opponent.mons[0].fainted, b.log.joinToString("\n"))
        assertEquals(0, b.player.active!!.boost("atk"))
    }

    @Test
    fun `destiny bond takes down a direct attacker but not when its user faints to poison`() {
        fun run(foeHp: Int, poisoned: Boolean): Battle {
            val b = battle(listOf(mon("gyarados", moves = listOf("seismictoss"))),
                listOf(mon("jolteon", level = 50, moves = listOf("destinybond")), mon("pidgey", level = 5, moves = listOf("splash"))))
            val foe = b.opponent.active!!
            foe.hp = foeHp
            if (poisoned) foe.mon.status = MajorStatus.POISON
            turn(b, Choice.UseMove(0), Choice.UseMove(0))
            assertTrue(foe.fainted, b.log.joinToString("\n"))
            return b
        }
        assertTrue(run(foeHp = 50, poisoned = false).player.mons[0].fainted, "Destiny Bond after a direct KO")
        val b = run(foeHp = 51, poisoned = true)
        assertFalse(b.player.mons[0].fainted, b.log.joinToString("\n"))
        assertFalse(b.log.any { it.contains("took its attacker down") })
    }

    // ------------------------------------------------------------------ B4 durations

    @Test
    fun `five-turn sandstorm buffets at the end of turns 1 to 4 and subsides at the end of turn 5`() {
        val b = battle(listOf(mon("tyranitar", ability = "unnerve", moves = listOf("sandstorm", "splash"))),
            listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        repeat(3) { turn(b, Choice.UseMove(1), Choice.UseMove(0)) }
        assertEquals("sandstorm", b.field.weather, "still active for turn 5")
        val mark = b.log.size
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertNull(b.field.weather)
        assertTrue(logSince(b, mark).contains("The sandstorm subsided."))
        assertFalse(logSince(b, mark).any { it.contains("buffeted") }, "no damage on the final turn")
        assertEquals(4, b.log.count { it.contains("Enemy Snorlax is buffeted") })
    }

    @Test
    fun `grassy terrain heals on each of its five turns then ends`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("grassyterrain", "splash"))), listOf(mon("snorlax", moves = listOf("splash"))))
        val foe = b.opponent.active!!
        foe.hp = 1
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        repeat(3) { turn(b, Choice.UseMove(1), Choice.UseMove(0)) }
        assertEquals("grassyterrain", b.field.terrain)
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertNull(b.field.terrain)
        assertEquals(1 + 5 * (foe.maxHp / 16), foe.hp)
    }

    @Test
    fun `trick room set on turn 1 reverses turns 2 to 5 and ends after turn 5`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("trickroom", "splash"))), listOf(mon("jolteon", moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        repeat(4) { i ->
            assertTrue(b.field.has("trickroom"), "active on turn ${i + 2}")
            val mark = b.log.size
            turn(b, Choice.UseMove(1), Choice.UseMove(0))
            assertTrue(logSince(b, mark).first { it.contains(" used ") }.startsWith("Snorlax"))
        }
        assertFalse(b.field.has("trickroom"))
    }

    // ------------------------------------------------------------------ B5 sleep

    @Test
    fun `ordinary sleep counters are 2 to 4 and rest is 3`() {
        val seen = HashSet<Int>()
        for (seed in 1L..60L) {
            val b = battle(listOf(mon("snorlax", moves = listOf("splash"))), listOf(mon("snorlax", moves = listOf("splash"))), seed = seed)
            val t = b.opponent.active!!
            assertTrue(b.trySetStatus(t, MajorStatus.SLEEP, b.player.active, "spore", primary = true))
            assertTrue(t.mon.sleepTurns in 2..4, "counter ${t.mon.sleepTurns}")
            seen.add(t.mon.sleepTurns)
        }
        assertEquals(setOf(2, 3, 4), seen)
    }

    private fun turnsAsleep(sleepCounter: Int?, rest: Boolean): Int {
        val b = battle(listOf(mon("snorlax", moves = listOf("rest", "tackle"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val p = b.player.active!!
        if (rest) { p.hp = 1; turn(b, Choice.UseMove(0), Choice.UseMove(0)); assertEquals(MajorStatus.SLEEP, p.status) }
        else { b.trySetStatus(p, MajorStatus.SLEEP, b.opponent.active, "spore", primary = true); p.mon.sleepTurns = sleepCounter!! }
        var asleep = 0
        repeat(6) {
            val mark = b.log.size
            turn(b, Choice.UseMove(1), Choice.UseMove(0))
            if (logSince(b, mark).any { it == "Snorlax is fast asleep." }) asleep++
        }
        return asleep
    }

    @Test
    fun `sleep lasts 1 to 3 turns asleep and rest exactly 2`() {
        assertEquals(1, turnsAsleep(2, rest = false))
        assertEquals(3, turnsAsleep(4, rest = false))
        assertEquals(2, turnsAsleep(null, rest = true))
    }

    // ------------------------------------------------------------------ B6 Mold Breaker vs Sturdy / Disguise

    @Test
    fun `sturdy endures a KO from full hp unless the attacker has mold breaker`() {
        for ((ability, survives) in listOf("sandforce" to true, "moldbreaker" to false)) {
            val b = battle(listOf(mon("excadrill", level = 100, ability = ability, moves = listOf("earthquake"))),
                listOf(mon("golem", level = 10, ability = "sturdy", moves = listOf("splash"))))
            turn(b, Choice.UseMove(0), Choice.UseMove(0))
            val golem = b.opponent.active!!
            assertEquals(survives, !golem.fainted && golem.hp == 1, "$ability: ${b.log.joinToString("\n")}")
        }
    }

    @Test
    fun `disguise takes the hit unless the attacker has mold breaker`() {
        for ((ability, busted) in listOf("sandforce" to true, "moldbreaker" to false)) {
            val b = battle(listOf(mon("excadrill", level = 30, ability = ability, moves = listOf("ironhead"))),
                listOf(mon("mimikyu", level = 50, ability = "disguise", moves = listOf("splash"))))
            val mimi = b.opponent.active!!
            turn(b, Choice.UseMove(0), Choice.UseMove(0))
            assertEquals(busted, mimi.species.id == "mimikyubusted", "$ability: ${b.log.joinToString("\n")}")
            if (busted) assertEquals(mimi.maxHp - mimi.baseMaxHp / 8, mimi.hp, "only the 1/8 bust cost")
            else assertTrue(mimi.maxHp - mimi.hp > mimi.baseMaxHp / 8, "full damage through the disguise")
        }
    }

    // ------------------------------------------------------------------ B7 Ion Deluge / Plasma Fists

    @Test
    fun `ion deluge makes normal moves electric for the rest of the turn`() {
        val b = battle(listOf(mon("garchomp", moves = listOf("iondeluge", "splash"))), listOf(mon("snorlax", moves = listOf("tackle"))))
        val mark = b.log.size
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(logSince(b, mark).any { it.contains("doesn't affect Garchomp") }, "Electric Tackle vs Ground")
        assertEquals("Electric", b.events.filterIsInstance<BattleEvent.MoveUsed>().last { it.moveId == "tackle" }.moveType)
        val hp = b.player.active!!.hp
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertTrue(b.player.active!!.hp < hp, "Normal again the next turn")
    }

    @Test
    fun `plasma fists electrifies the slower foe's normal move that turn`() {
        // Banette (Insomnia) avoids Cursed Body disabling the foe's Tackle.
        val b = battle(listOf(mon("banette", level = 100, ability = "insomnia", moves = listOf("plasmafists", "splash"))), listOf(mon("snorlax", level = 100, moves = listOf("tackle"))))
        val g = b.player.active!!
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(g.hp < g.maxHp, "Electric Tackle hits the Ghost type: ${b.log.joinToString("\n")}")
        val hp = g.hp
        val mark = b.log.size
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertEquals(hp, g.hp)
        assertTrue(logSince(b, mark).any { it.contains("doesn't affect Banette") }, "expired after one turn")
    }

    // ------------------------------------------------------------------ B8 Roar in wild battles

    private fun wildRoar(wildSpecies: String, wildAbility: String? = null, wildMove: String = "splash", userAbility: String? = null): Battle {
        val b = battle(listOf(mon("excadrill", ability = userAbility, moves = listOf("roar"))),
            listOf(mon(wildSpecies, level = 20, ability = wildAbility, moves = listOf(wildMove))), rules = wildRules, wild = true)
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        return b
    }

    @Test
    fun `roar ends a wild battle unless ingrain suction cups or guard dog anchor the target`() {
        assertEquals(BattleOutcome.FOE_FLED, wildRoar("pidgey").outcome)
        for (b in listOf(wildRoar("bulbasaur", wildMove = "ingrain"), wildRoar("octillery", "suctioncups"), wildRoar("mabosstiff", "guarddog"))) {
            assertEquals(BattleOutcome.ONGOING, b.outcome, b.log.joinToString("\n"))
            assertTrue(b.log.contains("But it failed!"))
        }
        assertEquals(BattleOutcome.FOE_FLED, wildRoar("octillery", "suctioncups", userAbility = "moldbreaker").outcome, "Mold Breaker ignores Suction Cups")
    }

    @Test
    fun `roar fails against a trainer's last pokemon`() {
        val b = battle(listOf(mon("excadrill", moves = listOf("roar"))), listOf(mon("pidgey", level = 20, moves = listOf("splash"))))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(BattleOutcome.ONGOING, b.outcome)
        assertTrue(b.log.contains("But it failed!"))
    }

    // ------------------------------------------------------------------ B9 Utility Umbrella and weather

    @Test
    fun `utility umbrella gives moonlight its normal half heal in rain`() {
        for ((item, expected) in listOf(null to 0.25, "utilityumbrella" to 0.5)) {
            val b = battle(listOf(mon("clefable", item = item, moves = listOf("moonlight"))), listOf(mon("snorlax", moves = listOf("splash"))))
            b.setWeather("raindance", null)
            val c = b.player.active!!
            c.hp = 1
            turn(b, Choice.UseMove(0), Choice.UseMove(0))
            assertEquals(1 + Mod.pokeRound(c.baseMaxHp * expected), c.hp, "item=$item")
        }
    }

    @Test
    fun `utility umbrella keeps weather ball normal and solar beam full power in rain`() {
        val b = battle(listOf(mon("snorlax", item = "utilityumbrella", moves = listOf("weatherball"))), listOf(mon("snorlax", moves = listOf("splash"))))
        b.setWeather("raindance", null)
        val u = b.player.active!!; val f = b.opponent.active!!
        fun power(move: String, base: Int) = Effects.move(move)!!.basePower(b, MoveCtx(u, f, ActiveMove(dex.move(move))), base)
        assertEquals(50, power("weatherball", 50))
        assertEquals(120, power("solarbeam", 120))
        u.item = null
        assertEquals(100, power("weatherball", 50))
        assertEquals(60, power("solarbeam", 120))
    }

    // ------------------------------------------------------------------ B10 Tera STAB and the 60-power floor

    @Test
    fun `adaptability with terastallization follows the showdown stab table`() {
        val b = battle(listOf(mon("porygonz", ability = "adaptability", moves = listOf("tackle"))), listOf(mon("snorlax", moves = listOf("splash"))))
        val u = b.player.active!!; val f = b.opponent.active!!
        val eff = Effects.ability("adaptability")!!
        fun stab(type: String, base: Double): Double {
            val mc = MoveCtx(u, f, ActiveMove(dex.move("tackle")).also { it.type = type })
            return eff.onModifyStab(EffectCtx(b, eff, u, null, u.abilityState), mc, base)
        }
        assertEquals(2.0, stab("Normal", 1.5), "not Terastallized")
        u.terastallized = "Normal"
        assertEquals(2.25, stab("Normal", 2.0), "Tera type matches an original type")
        u.terastallized = "Electric"
        assertEquals(2.0, stab("Electric", 1.5), "new Tera type")
        assertEquals(1.5, stab("Normal", 1.5), "original type of a Terastallized user")
        u.terastallized = "Stellar"
        assertEquals(2.0, stab("Normal", 2.0), "Stellar ignores Adaptability")
    }

    @Test
    fun `tera raises weak tera-type moves to 60 power except priority and variable power moves`() {
        val b = battle(listOf(mon("snorlax", level = 100, moves = listOf("tackle"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val u = b.player.active!!; val f = b.opponent.active!!
        assertEquals(dmg(b, u, f, "tackle"), dmg(b, u, f, "quickattack"), "no floor without Tera")
        u.terastallized = "Normal"
        assertEquals(dmg(b, u, f, "covet"), dmg(b, u, f, "tackle"), "Tackle 40 -> 60")
        assertTrue(dmg(b, u, f, "quickattack") < dmg(b, u, f, "tackle"), "priority moves keep their power")
        u.terastallized = "Fighting"
        val light = battle(listOf(mon("snorlax", level = 100, moves = listOf("tackle"))), listOf(mon("pichu", level = 100, moves = listOf("splash"))))
        val lu = light.player.active!!; lu.terastallized = "Fighting"
        assertTrue(dmg(light, lu, light.opponent.active!!, "lowkick") < dmg(light, lu, light.opponent.active!!, "karatechop"), "Low Kick (20) is exempt")
    }

    // ------------------------------------------------------------------ B11 before-move order

    @Test
    fun `flinch is checked before paralysis`() {
        for (seed in 1L..24L) {
            val b = battle(listOf(mon("snorlax", moves = listOf("tackle"))), listOf(mon("snorlax", moves = listOf("splash"))), seed = seed)
            val p = b.player.active!!
            p.mon.status = MajorStatus.PARALYSIS
            p.volatiles["flinch"] = EffectState(1)
            val mark = b.log.size
            turn(b, Choice.UseMove(0), Choice.UseMove(0))
            val l = logSince(b, mark)
            assertTrue(l.contains("Snorlax flinched and couldn't move!"), "seed $seed: $l")
            assertFalse(l.any { it.contains("It can't move!") }, "seed $seed: $l")
        }
    }

    @Test
    fun `confusion is checked before paralysis`() {
        for (seed in 1L..24L) {
            val b = battle(listOf(mon("snorlax", moves = listOf("splash"))), listOf(mon("snorlax", moves = listOf("splash"))), seed = seed)
            val p = b.player.active!!
            p.mon.status = MajorStatus.PARALYSIS
            p.volatiles["confusion"] = EffectState().also { it.counter = 5 }
            turn(b, Choice.UseMove(0), Choice.UseMove(0))
            assertEquals(4, p.volatiles["confusion"]!!.counter, "seed $seed: the confusion counter drops even when fully paralyzed")
        }
    }

    // ------------------------------------------------------------------ B12 recoil, drain, Beat Up, Swallow, Protect

    @Test
    fun `recoil and drain round halves up`() {
        val r = battle(listOf(mon("snorlax", level = 100, moves = listOf("headsmash"))), listOf(mon("snorlax", moves = listOf("splash"))))
        r.opponent.active!!.hp = 3
        val u = r.player.active!!
        u.boosts[BattleMon.boostIndex("accuracy")] = 6
        turn(r, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(u.maxHp - 2, u.hp, "3 damage x 1/2 = 1.5 -> 2 recoil: ${r.log.joinToString("\n")}")

        val d = battle(listOf(mon("snorlax", level = 100, moves = listOf("absorb"))), listOf(mon("snorlax", moves = listOf("splash"))))
        d.opponent.active!!.hp = 3
        val du = d.player.active!!
        du.hp = 10
        turn(d, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(12, du.hp, "3 damage x 1/2 = 1.5 -> 2 drained")
    }

    @Test
    fun `chloroblast costs half the user's max hp when it hits`() {
        val b = battle(listOf(mon("venusaur", level = 100, moves = listOf("chloroblast"))), listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        val u = b.player.active!!
        u.boosts[BattleMon.boostIndex("accuracy")] = 6
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertEquals(u.maxHp - (u.maxHp + 1) / 2, u.hp, "Math.round(maxHp / 2)")
    }

    @Test
    fun `beat up always includes the user and skips statused or fainted allies`() {
        val b = battle(listOf(mon("umbreon", moves = listOf("beatup")), mon("pikachu"), mon("eevee"), mon("rattata")),
            listOf(mon("snorlax", level = 100, moves = listOf("splash"))))
        b.player.active!!.mon.status = MajorStatus.BURN
        b.player.mons[2].mon.status = MajorStatus.POISON
        b.player.mons[3].mon.fainted = true; b.player.mons[3].mon.hp = 0
        val mark = b.events.size
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        val hits = b.events.drop(mark).count { it is BattleEvent.HpChange && it.side == 1 && it.cause == "move" }
        assertEquals(2, hits, b.log.joinToString("\n"))
    }

    @Test
    fun `swallow heals by rounded fractions and always spends the stockpile`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("stockpile", "swallow"))), listOf(mon("snorlax", moves = listOf("splash"))))
        val u = b.player.active!!
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        u.hp = 1
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertEquals(1 + Mod.pokeRound(u.baseMaxHp * 0.25), u.hp)
        assertFalse(u.hasVolatile("stockpile"))
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        u.hp = u.maxHp
        val mark = b.log.size
        turn(b, Choice.UseMove(1), Choice.UseMove(0))
        assertTrue(logSince(b, mark).contains("Snorlax's HP is full!"))
        assertFalse(u.hasVolatile("stockpile"), "spent even at full HP")
    }

    @Test
    fun `spit up spends the stockpile even when blocked`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("stockpile", "spitup"))), listOf(mon("snorlax", moves = listOf("splash", "protect"))))
        val u = b.player.active!!
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        turn(b, Choice.UseMove(1), Choice.UseMove(1))
        assertTrue(b.log.any { it.contains("protected itself") })
        assertFalse(u.hasVolatile("stockpile"))
        assertEquals(0, u.boost("def"))
    }

    @Test
    fun `protect fails when the foe switches whatever the incoming pokemon did before`() {
        for (stale in listOf(false, true)) {
            val b = battle(listOf(mon("snorlax", moves = listOf("protect", "splash"))),
                listOf(mon("jolteon", moves = listOf("splash")), mon("pidgey", moves = listOf("splash"))))
            b.opponent.mons[1].movedThisTurn = stale
            val mark = b.log.size
            turn(b, Choice.UseMove(0), Choice.Switch(1))
            assertTrue(logSince(b, mark).contains("But it failed!"), "stale=$stale: ${logSince(b, mark)}")
            // The chain starts fresh on the next turn: Protect works against a foe that still moves.
            val m2 = b.log.size
            turn(b, Choice.UseMove(0), Choice.UseMove(0))
            assertTrue(logSince(b, m2).any { it.contains("protected itself") }, "stale=$stale")
        }
    }

    // ------------------------------------------------------------------ B13 terrain order and Gravity

    @Test
    fun `grassy terrain heals after future sight and wish and before leftovers`() {
        val order = { id: String -> Effects.condition(id)!!.residualOrder }
        assertTrue(order("grassyterrain") > order("futuremove"))
        assertTrue(order("grassyterrain") > order("wish"))
        val b = battle(listOf(mon("snorlax", item = "leftovers", moves = listOf("grassyterrain"))), listOf(mon("snorlax", moves = listOf("splash"))))
        b.player.active!!.hp = 1
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        val grassy = b.log.indexOfFirst { it == "Snorlax's HP was restored." }
        val lefties = b.log.indexOfFirst { it.contains("Leftovers") }
        assertTrue(grassy in 0 until lefties, b.log.joinToString("\n"))
    }

    @Test
    fun `gravity grounds a flying pokemon mid-fly and seals fly`() {
        val b = battle(listOf(mon("snorlax", moves = listOf("gravity", "splash"))), listOf(mon("jolteon", moves = listOf("fly", "tackle"))))
        val foe = b.opponent.active!!
        turn(b, Choice.UseMove(0), Choice.UseMove(0))
        assertTrue(b.log.contains("Enemy Jolteon couldn't stay airborne because of gravity!"), b.log.joinToString("\n"))
        assertFalse(foe.hasVolatile("twoturnmove"))
        assertNull(b.lockedMove(foe))
        assertFalse(b.canSelectMove(foe, foe.moveSlots[0]), "Fly is sealed by Gravity")
        assertNotNull(b.choose(b.opponent, Choice.UseMove(0)))
    }
}
