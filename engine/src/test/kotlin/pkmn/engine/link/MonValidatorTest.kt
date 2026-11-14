package pkmn.engine.link

import pkmn.engine.TestSupport
import pkmn.engine.link.MonValidator.Rule
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.MoveSlot
import pkmn.engine.mon.Pokemon
import kotlin.test.*

/** Every [MonValidator] rule rejects a Pokémon that breaks it, and only that rule fires. */
class MonValidatorTest {
    private val dex = TestSupport.dex
    private val v = MonValidator(dex)

    private fun good(species: String = "pikachu"): Pokemon = TestSupport.mon(species, level = 30, moves = listOf("thundershock", "quickattack")).also {
        it.otName = "ARI"; it.otId = 4242; it.metLevel = 12; it.nickname = "SPARKY"
    }

    private fun rejects(rule: Rule, forTrade: Boolean = true, levelCap: Int = 100, mon: Pokemon = good(), change: (Pokemon) -> Unit) {
        assertNull(v.check(mon.copy(), forTrade, levelCap), "the unchanged Pokémon is valid")
        val bad = mon.copy().also(change)
        assertEquals(rule, v.check(bad, forTrade, levelCap)?.rule)
    }

    @Test fun `a valid Pokémon passes for a trade and a battle`() {
        assertNull(v.check(good(), forTrade = true)); assertNull(v.check(good(), forTrade = false))
        assertNull(v.check(good().also { it.originalMetLevel = 5 }, forTrade = true))
    }

    @Test fun `unknown species`() = rejects(Rule.SPECIES) { it.speciesId = "notamon" }
    @Test fun `battle-only form`() = rejects(Rule.BATTLE_ONLY_FORM, mon = good("venusaur")) { it.speciesId = "venusaurmega" }
    @Test fun `fusion form in a trade only`() {
        rejects(Rule.FUSION_FORM, mon = good("kyurem")) { it.speciesId = "kyuremblack" }
        assertNotEquals(Rule.FUSION_FORM, v.check(good("kyurem").also { it.speciesId = "kyuremblack"; it.hp = it.maxHp(dex) }, forTrade = false)?.rule)
    }
    @Test fun `level out of range or over the cap`() {
        rejects(Rule.LEVEL) { it.level = 0 }
        rejects(Rule.LEVEL) { it.level = 101 }
        rejects(Rule.LEVEL, forTrade = false, levelCap = 50) { it.level = 51 }
    }
    @Test fun `IVs out of range`() { rejects(Rule.IVS) { it.ivs.spe = 32 }; rejects(Rule.IVS) { it.ivs.hp = -1 } }
    @Test fun `EVs over 252 or over 510 in total`() {
        rejects(Rule.EVS) { it.evs.atk = 253 }
        rejects(Rule.EVS) { it.evs.atk = 252; it.evs.def = 252; it.evs.spa = 7 }
    }
    @Test fun `no moves or more than four`() {
        rejects(Rule.MOVE_COUNT) { it.moves.clear() }
        rejects(Rule.MOVE_COUNT) { m -> listOf("tackle", "growl", "tailwhip").forEach { m.moves.add(MoveSlot(it, 5)) } }
    }
    @Test fun `unknown move`() = rejects(Rule.MOVE_UNKNOWN) { it.moves.add(MoveSlot("notamove", 5)) }
    @Test fun `duplicate move`() = rejects(Rule.MOVE_DUPLICATE) { it.moves.add(it.moves[0].copy()) }
    @Test fun `PP or PP Ups out of range`() {
        rejects(Rule.PP) { it.moves[0].ppUps = 4 }
        rejects(Rule.PP) { it.moves[0].pp = 99 }
        rejects(Rule.PP) { it.moves[0].pp = -1 }
    }
    @Test fun `unknown held item`() = rejects(Rule.ITEM) { it.heldItem = "notanitem" }
    @Test fun `invalid ability slot`() = rejects(Rule.ABILITY_SLOT) { it.abilitySlot = "7" }
    @Test fun `HP out of range`() { rejects(Rule.HP) { it.hp = it.maxHp(dex) + 1 }; rejects(Rule.HP) { it.hp = -1 } }
    @Test fun `nickname without a font glyph or not as written`() {
        rejects(Rule.NICKNAME) { it.nickname = "ピカチュウ" }
        rejects(Rule.NICKNAME) { it.nickname = "A".repeat(13) }
    }
    @Test fun `OT name missing or invalid in a trade`() {
        rejects(Rule.OT_NAME) { it.otName = "" }
        rejects(Rule.OT_NAME) { it.otName = "BAD\u0007" }
        assertNull(v.check(good().also { it.otName = "" }, forTrade = false), "an empty OT is fine in a battle team")
        assertNull(v.check(good().also { it.otName = "VOLTA_FAN" }, forTrade = true), "the game's own in-game trade OT")
        assertNull(v.check(good().also { it.nickname = "ピカ"; it.otName = "BAD\u0007" }, forTrade = false), "names are not checked in a battle team (Phase 2)")
    }
    @Test fun `experience beyond level 100`() {
        rejects(Rule.EXP) { it.exp = -1 }
        rejects(Rule.EXP) { it.exp = Int.MAX_VALUE }
    }
    @Test fun `every OT name and nickname in the game's scripts is accepted`() {
        val scripts = java.io.File(System.getProperty("pkmn.assets")).parentFile.resolve("world/scripts")
        val names = scripts.walk().filter { it.isFile }.flatMap { f -> Regex("""\b(ot|nick)=("[^"]*"|\S+)""").findAll(f.readText()).map { it.groupValues } }.toList()
        assertTrue(names.size > 10, "found ${names.size} names")
        for ((_, kind, raw) in names) {
            val name = raw.trim('"')
            val m = good().also { if (kind == "ot") it.otName = name else it.nickname = name }
            assertNull(v.check(m, forTrade = true), "$kind=$name")
        }
    }
    @Test fun `met level above the level`() { rejects(Rule.MET_LEVEL) { it.metLevel = 31 }; rejects(Rule.MET_LEVEL) { it.metLevel = 0 } }
    @Test fun `original met level out of range`() {
        rejects(Rule.ORIGINAL_MET_LEVEL) { it.originalMetLevel = 0 }
        rejects(Rule.ORIGINAL_MET_LEVEL) { it.originalMetLevel = 31 }
    }
    @Test fun `friendship out of range`() = rejects(Rule.FRIENDSHIP) { it.friendship = 256 }
    @Test fun `Dynamax level out of range`() = rejects(Rule.DYNAMAX_LEVEL) { it.dynamaxLevel = 11 }
    @Test fun `unknown Tera type`() = rejects(Rule.TERA_TYPE) { it.teraType = "Cosmic" }
    @Test fun `sleep counter without sleep or too long`() {
        rejects(Rule.STATUS) { it.sleepTurns = 3 }
        rejects(Rule.STATUS) { it.status = MajorStatus.SLEEP; it.sleepTurns = 99 }
    }
    @Test fun `oversized extra data`() {
        rejects(Rule.EXTRA_DATA) { m -> repeat(MonValidator.MAX_COUNTERS + 1) { m.counters["c$it"] = 1 } }
        rejects(Rule.EXTRA_DATA) { it.metLocation = "x".repeat(MonValidator.MAX_TEXT + 1) }
    }
    @Test fun `the other trainer's name`() {
        assertNull(v.checkTrainerName("MIO"))
        assertEquals(Rule.TRAINER_NAME, v.checkTrainerName("MIO\n")?.rule)
        assertEquals(Rule.TRAINER_NAME, v.checkTrainerName("")?.rule)
    }
    @Test fun `every rule has a rejecting case here`() {
        // Keeps this file honest when a rule is added: each Rule name must appear in a test above.
        val src = java.io.File("src/test/kotlin/pkmn/engine/link/MonValidatorTest.kt").readText()
        for (r in Rule.entries) assertTrue("Rule.${r.name}" in src, "no test for ${r.name}")
    }
    @Test fun `a team check applies the rules and the team size`() {
        val rules = LinkRules(maxTeam = 2)
        assertNull(v.checkTeam(rules, listOf(good(), good())))
        assertNotNull(v.checkTeam(rules, listOf(good(), good(), good())))
        assertNotNull(v.checkTeam(rules, emptyList()))
        assertNotNull(v.checkTeam(rules, listOf(good().also { it.level = 0 })))
    }
}
