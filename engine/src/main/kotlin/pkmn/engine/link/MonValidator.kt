package pkmn.engine.link

import pkmn.engine.campaign.Nicknames
import pkmn.engine.data.Dex
import pkmn.engine.data.FormKind
import pkmn.engine.mon.Pokemon

/**
 * Checks a Pokémon that arrived from the other device before this game uses it: in a link battle team or as a link trade
 * ("Checks on a received Pokémon" in the link play plan). The other device is untrusted, so every field this game reads
 * is checked against the content and the same limits the game itself keeps. Names must pass [Nicknames.check] unchanged,
 * which guarantees every character has a font glyph. Content checks mirror what [pkmn.engine.save.SaveValidator]
 * repairs in a save; here nothing is repaired, the Pokémon is refused.
 *
 * [check] returns the first broken [Rule] with a short reason, or null when the Pokémon is acceptable.
 */
class MonValidator(private val dex: Dex) {
    enum class Rule {
        SPECIES, BATTLE_ONLY_FORM, FUSION_FORM, LEVEL, IVS, EVS, MOVE_COUNT, MOVE_UNKNOWN, MOVE_DUPLICATE, PP, ITEM,
        ABILITY_SLOT, HP, NICKNAME, OT_NAME, MET_LEVEL, ORIGINAL_MET_LEVEL, FRIENDSHIP, DYNAMAX_LEVEL, TERA_TYPE,
        STATUS, EXTRA_DATA, TRAINER_NAME, EXP,
    }

    data class Problem(val rule: Rule, val reason: String) {
        override fun toString() = reason
    }

    /**
     * [forTrade]: the Pokémon will join this player's save, so fusion forms (which this game only makes by fusing two
     * owned Pokémon) and a missing original trainer are refused too. A battle team only needs to be playable.
     * [levelCap] is the highest level allowed (the battle rules' cap; 100 for a trade).
     */
    fun check(m: Pokemon, forTrade: Boolean, levelCap: Int = 100): Problem? {
        val sp = dex.speciesOrNull(m.speciesId) ?: return Problem(Rule.SPECIES, "unknown species")
        if (sp.isBattleOnly || sp.formKind == FormKind.BATTLE_ONLY) return Problem(Rule.BATTLE_ONLY_FORM, "battle-only form ${sp.id}")
        if (forTrade && sp.formKind == FormKind.FUSION) return Problem(Rule.FUSION_FORM, "fusion form ${sp.id}")
        if (m.level !in 1..minOf(100, levelCap)) return Problem(Rule.LEVEL, "level ${m.level}")
        val ivs = listOf(m.ivs.hp, m.ivs.atk, m.ivs.def, m.ivs.spa, m.ivs.spd, m.ivs.spe)
        if (ivs.any { it !in 0..31 }) return Problem(Rule.IVS, "IVs out of range")
        val evs = listOf(m.evs.hp, m.evs.atk, m.evs.def, m.evs.spa, m.evs.spd, m.evs.spe)
        if (evs.any { it !in 0..252 } || evs.sum() > 510) return Problem(Rule.EVS, "EVs out of range")
        if (m.moves.isEmpty() || m.moves.size > 4) return Problem(Rule.MOVE_COUNT, "${m.moves.size} moves")
        if (m.moves.any { dex.moveOrNull(it.id) == null }) return Problem(Rule.MOVE_UNKNOWN, "unknown move")
        if (m.moves.map { it.id }.distinct().size != m.moves.size) return Problem(Rule.MOVE_DUPLICATE, "duplicate move")
        if (m.moves.any { it.ppUps !in 0..3 || it.pp !in 0..it.maxPp(dex) }) return Problem(Rule.PP, "PP out of range")
        if (m.heldItem != null && dex.itemOrNull(m.heldItem) == null) return Problem(Rule.ITEM, "unknown item")
        if (m.abilitySlot !in sp.abilities) return Problem(Rule.ABILITY_SLOT, "ability slot ${m.abilitySlot}")
        if (m.hp !in 0..m.maxHp(dex)) return Problem(Rule.HP, "HP ${m.hp}")
        // Names only matter for a Pokémon that joins this save; a battle team shows them for one battle (as in Phase 2).
        if (forTrade && m.nickname != null && !nameOk(m.nickname!!)) return Problem(Rule.NICKNAME, "nickname")
        if (forTrade && !trainerNameOk(m.otName)) return Problem(Rule.OT_NAME, "OT name")
        if (m.exp !in 0..m.species(dex).growthRate.expForLevel(100)) return Problem(Rule.EXP, "experience ${m.exp}")
        if (m.metLevel !in 1..m.level) return Problem(Rule.MET_LEVEL, "met level ${m.metLevel}")
        m.originalMetLevel?.let { if (it !in 1..m.level) return Problem(Rule.ORIGINAL_MET_LEVEL, "original met level $it") }
        if (m.friendship !in 0..255) return Problem(Rule.FRIENDSHIP, "friendship")
        if (m.dynamaxLevel !in 0..10) return Problem(Rule.DYNAMAX_LEVEL, "Dynamax level")
        if (m.teraType != null && !dex.typeChart.isType(m.teraType!!)) return Problem(Rule.TERA_TYPE, "tera type")
        if (m.sleepTurns !in 0..7 || (m.status == null && m.sleepTurns != 0)) return Problem(Rule.STATUS, "sleep counter")
        if (m.counters.size > MAX_COUNTERS || m.counters.keys.any { it.length > MAX_TEXT } || m.metLocation.length > MAX_TEXT || m.ball.length > MAX_TEXT)
            return Problem(Rule.EXTRA_DATA, "oversized extra data")
        return null
    }

    /** A party member in the other device's [TradeSync] summary: a real species and form, a level, a drawable nickname. */
    fun checkPartyEntry(e: TradePartyEntry): Problem? {
        val sp = dex.speciesOrNull(e.speciesId) ?: return Problem(Rule.SPECIES, "unknown species")
        if (sp.isBattleOnly || sp.formKind == FormKind.BATTLE_ONLY) return Problem(Rule.BATTLE_ONLY_FORM, "battle-only form ${sp.id}")
        if (e.level !in 1..100) return Problem(Rule.LEVEL, "level ${e.level}")
        if (e.nickname != null && !nameOk(e.nickname)) return Problem(Rule.NICKNAME, "nickname")
        return null
    }

    /** The other player's trainer name, as shown on this device. */
    fun checkTrainerName(name: String): Problem? = if (trainerNameOk(name)) null else Problem(Rule.TRAINER_NAME, "trainer name")

    /** Checks a whole team for a link battle. */
    fun checkTeam(rules: LinkRules, mons: List<Pokemon>): String? {
        if (mons.isEmpty() || mons.size > rules.maxTeam) return "team of ${mons.size}"
        for (m in mons) check(m, forTrade = false, levelCap = rules.levelCap)?.let { return it.reason }
        return null
    }

    /** A name passes when it has no control characters and [Nicknames.check] accepts it: every character is one the font draws. */
    private fun nameOk(name: String): Boolean = name.none { it.isISOControl() } && Nicknames.check(name) is Nicknames.Check.Ok

    /** Trainer and OT names follow the nickname rules plus '_': the game's own in-game trade OT `VOLTA_FAN` uses it, and the font draws it. */
    private fun trainerNameOk(name: String): Boolean = nameOk(name.replace('_', ' '))

    companion object {
        const val MAX_COUNTERS = 64
        const val MAX_TEXT = 64
    }
}
