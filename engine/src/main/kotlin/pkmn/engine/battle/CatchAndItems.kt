package pkmn.engine.battle

import pkmn.engine.mon.MajorStatus
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Capture settings for one battle (docs/CAPTURE.md). The defaults are the Scarlet/Violet rules; the campaign fills in
 * the player's badges and Pokédex, and the difficulty decides whether the low-level bonus applies.
 */
data class CaptureRules(
    /** Badges the player owns (badge penalty). */
    val badges: Int = 8,
    /** Highest level that [badges] badges can control: index = badge count (Scarlet/Violet obedience table). */
    val badgeLevels: List<Int> = SV_BADGE_LEVELS,
    /** Scarlet/Violet low-level bonus: (36 - 2 x level) / 10 for wild Pokémon below level 13. */
    val lowLevelBonus: Boolean = true,
    /** Species (base species ids) registered as caught: the Repeat Ball and critical-capture odds read it. */
    val caughtSpecies: Set<String> = emptySet(),
    val catchingCharm: Boolean = false,
    /** Out-of-battle multiplier D (sandwich Capture Power, off-guard start). 1 in this game. */
    val difficultyMultiplier: Double = 1.0,
) {
    companion object {
        val SV_BADGE_LEVELS = listOf(25, 30, 35, 40, 45, 50, 55, 60, 100)
    }
}

/**
 * Generation IX (Scarlet/Violet) capture routine, in the game's 1/4096 fixed-point steps.
 *
 * Final rate: X = (((3M - 2H) x G x C x B x BP) / 3M) x L x S x D, capped at 255; X >= 255 always catches.
 * Shake threshold: Y = floor(65536 / (255 / X)^(3/16)); four checks (one on a critical capture), each passing when a
 * random number in 0..65535 is below Y. Critical capture: random 0..255 below floor(min(255, X) x P x Ch / 6).
 * Sources and their confidence are listed in docs/CAPTURE.md.
 */
object CatchCalc {
    data class Result(val caught: Boolean, val shakes: Int, val critical: Boolean)

    /** Every multiplier of one throw, for tests, reports and the capture audit. */
    data class Factors(
        val maxHp: Int, val hp: Int, val catchRate: Int, val ball: Double, val badgePenalty: Double, val lowLevel: Double,
        val status: Double, val difficulty: Double, val guaranteed: Boolean,
    )

    private fun round4096(x: Double) = Math.round(x * 4096.0) / 4096.0
    private fun floor4096(x: Double) = Math.floor(x * 4096.0) / 4096.0

    fun isUltraBeast(sp: pkmn.engine.data.SpeciesData) = "Ultra Beast" in sp.tags

    /** Ball bonus B. Master Ball returns 0 (the formula is skipped). */
    fun ballModifier(b: Battle, user: BattleMon, target: BattleMon, ball: String): Double {
        val sp = target.species
        if (ball == "masterball") return 0.0
        if (isUltraBeast(sp)) return if (ball == "beastball") 5.0 else 410.0 / 4096.0
        val types = target.effectiveTypes()
        return when (ball) {
            "greatball" -> 1.5
            "ultraball" -> 2.0
            "safariball", "sportball" -> 1.5
            "netball" -> if ("Water" in types || "Bug" in types) 3.5 else 1.0
            "diveball" -> if (b.environment == "water") 3.5 else 1.0
            "lureball" -> if (b.environment == "water" || b.environment == "fishing") 4.0 else 1.0
            "nestball" -> if (target.level < 30) max(1.0, (41 - target.level) / 10.0) else 1.0
            "repeatball" -> if (sp.baseSpecies in b.rules.capture.caughtSpecies) 3.5 else 1.0
            "timerball" -> min(4.0, 1.0 + (b.turn - 1).coerceAtLeast(0) * 1229.0 / 4096.0)
            "duskball" -> if (b.isNight || b.environment == "cave") 3.0 else 1.0
            "quickball" -> if (b.turn <= 1) 5.0 else 1.0
            "levelball" -> when {
                user.level / 4 >= target.level -> 8.0
                user.level / 2 >= target.level -> 4.0
                user.level > target.level -> 2.0
                else -> 1.0
            }
            "moonball" -> if (sp.evolutions.any { it.item == "moonstone" }) 4.0 else 1.0
            "loveball" -> if (user.species.baseSpecies == sp.baseSpecies && user.mon.gender != target.mon.gender &&
                user.mon.gender != pkmn.engine.mon.Gender.NONE && target.mon.gender != pkmn.engine.mon.Gender.NONE) 8.0 else 1.0
            "fastball" -> if (sp.baseStats.spe >= 100) 4.0 else 1.0
            "dreamball" -> if (target.status == MajorStatus.SLEEP || target.ability == "comatose") 4.0 else 1.0
            "beastball" -> 410.0 / 4096.0
            else -> 1.0 // Poké, Premier, Luxury, Heal, Friend, Heavy (additive, below) and any other ball
        }
    }

    /** Badge penalty BP: x0.8 for every badge missing to control a Pokémon of [level]. */
    fun badgePenalty(rules: CaptureRules, level: Int): Double {
        if (rules.badges >= rules.badgeLevels.size - 1) return 1.0
        var bp = 1.0f
        var i = rules.badges
        while (i < rules.badgeLevels.size && rules.badgeLevels[i] < level) { bp *= 0.8f; i++ }
        return round4096(bp.toDouble())
    }

    fun factors(b: Battle, user: BattleMon, target: BattleMon, ball: String): Factors {
        val rules = b.rules.capture
        var c = target.species.catchRate
        if (ball == "heavyball") {
            val w = target.species.weightkg
            c = max(1, c + when { w >= 300 -> 30; w >= 200 -> 20; w < 100 -> -20; else -> 0 })
        }
        val s = when (target.status) {
            MajorStatus.SLEEP, MajorStatus.FREEZE -> 2.5
            null -> 1.0
            else -> 1.5
        }
        val l = if (rules.lowLevelBonus && target.level < 13) (36 - 2 * target.level) / 10.0 else 1.0
        return Factors(
            maxHp = target.baseMaxHp, hp = target.hp.coerceIn(0, target.baseMaxHp), catchRate = c,
            ball = ballModifier(b, user, target, ball), badgePenalty = badgePenalty(rules, target.level), lowLevel = l, status = s,
            difficulty = rules.difficultyMultiplier, guaranteed = ball == "masterball",
        )
    }

    /** Final capture rate X in 1/4096 steps (0..255). 256 means guaranteed (Master Ball). */
    fun finalRate(f: Factors): Double {
        if (f.guaranteed) return 256.0
        val m = f.maxHp.toDouble()
        val h = f.hp.toDouble()
        val g = 1.0 // dark-grass multiplier: never active since Generation VI
        var x = round4096((3 * m - 2 * h) * g)
        x = round4096(x * f.catchRate * f.ball)
        x = round4096(x * f.badgePenalty)
        x = floor4096(x / (3 * m))
        x = floor4096(x * Math.round(f.lowLevel * 10.0) / 10.0)
        x = round4096(x * f.status)
        x = round4096(x * f.difficulty)
        return min(255.0, x)
    }

    /** Shake threshold Y (0..65536); each check passes on a random 0..65535 below it. */
    fun shakeThreshold(x: Double): Int {
        if (x >= 255) return 65536
        if (x <= 0) return 0
        val p = Math.pow(round4096(255.0 / x), 3.0 / 16.0).toFloat().toDouble()
        return min(65536.0, Math.floor(round4096(65536.0 / round4096(p)))).toInt()
    }

    /** Critical-capture threshold CC; a random 0..255 below it makes the throw a critical capture. */
    fun criticalThreshold(x: Double, rules: CaptureRules): Int {
        val n = rules.caughtSpecies.size
        val p = when { n > 600 -> 2.5; n > 450 -> 2.0; n > 300 -> 1.5; n > 150 -> 1.0; n > 30 -> 0.5; else -> 0.0 }
        return Math.floor(round4096(min(x, 255.0) * p * (if (rules.catchingCharm) 2 else 1)) / 6.0).toInt()
    }

    /** Exact probability that one throw catches, including critical captures. */
    fun successProbability(f: Factors, rules: CaptureRules): Double {
        val x = finalRate(f)
        if (x >= 255) return 1.0
        val y = shakeThreshold(x) / 65536.0
        val crit = min(1.0, criticalThreshold(x, rules) / 256.0)
        return crit * y + (1 - crit) * y.pow(4)
    }

    fun attempt(b: Battle, user: BattleMon, target: BattleMon, ball: String): Result {
        val f = factors(b, user, target, ball)
        val x = finalRate(f)
        if (x >= 255) return Result(true, 3, false)
        val y = shakeThreshold(x)
        if (b.rng.nextInt(256) < criticalThreshold(x, b.rules.capture)) {
            val ok = b.rng.nextInt(65536) < y
            return Result(ok, if (ok) 1 else 0, true)
        }
        var shakes = 0
        for (i in 0 until 4) {
            if (b.rng.nextInt(65536) >= y) return Result(false, min(shakes, 3), false)
            shakes++
        }
        return Result(true, 3, false)
    }
}

/** Effects of bag items used during battle (Potions, status cures, X items, revives). */
object BagItemEffects {
    data class Heal(val hp: Int = 0, val fraction: Double = 0.0, val cures: Set<MajorStatus> = emptySet(), val cureConfusion: Boolean = false, val revive: Double = 0.0)

    val healing: Map<String, Heal> = mapOf(
        "potion" to Heal(hp = 20), "superpotion" to Heal(hp = 60), "hyperpotion" to Heal(hp = 120), "maxpotion" to Heal(fraction = 1.0),
        "fullrestore" to Heal(fraction = 1.0, cures = MajorStatus.entries.toSet(), cureConfusion = true),
        "freshwater" to Heal(hp = 30), "sodapop" to Heal(hp = 50), "lemonade" to Heal(hp = 70), "moomoomilk" to Heal(hp = 100),
        "energypowder" to Heal(hp = 60), "energyroot" to Heal(hp = 120), "berryjuice" to Heal(hp = 20),
        "sweetheart" to Heal(hp = 20), "ragecandybar" to Heal(hp = 20), "lavacookie" to Heal(cures = MajorStatus.entries.toSet(), cureConfusion = true),
        "antidote" to Heal(cures = setOf(MajorStatus.POISON, MajorStatus.TOXIC)), "burnheal" to Heal(cures = setOf(MajorStatus.BURN)),
        "iceheal" to Heal(cures = setOf(MajorStatus.FREEZE)), "awakening" to Heal(cures = setOf(MajorStatus.SLEEP)),
        "paralyzeheal" to Heal(cures = setOf(MajorStatus.PARALYSIS)),
        "fullheal" to Heal(cures = MajorStatus.entries.toSet(), cureConfusion = true),
        "healpowder" to Heal(cures = MajorStatus.entries.toSet(), cureConfusion = true),
        "pewtercrunchies" to Heal(cures = MajorStatus.entries.toSet(), cureConfusion = true),
        "revive" to Heal(revive = 0.5), "maxrevive" to Heal(revive = 1.0), "revivalherb" to Heal(revive = 1.0),
        "oranberry" to Heal(hp = 10), "sitrusberry" to Heal(fraction = 0.25),
    )

    val xItems = mapOf("xattack" to "atk", "xdefense" to "def", "xspatk" to "spa", "xspdef" to "spd", "xspeed" to "spe", "xaccuracy" to "accuracy")

    /**
     * Dry run of [apply] for choices that are cheap to check: whether the item would change [target] right now. Used to
     * reject a use (and keep the item and the turn) such as a Potion at full HP or a Revive on a live Pokémon. Items
     * whose effect depends on battle state (X items, Dire Hit...) pass here and are charged only if [apply] succeeds.
     */
    fun wouldWork(b: Battle, target: BattleMon, itemId: String, moveIndex: Int): Boolean {
        healing[itemId]?.let { h ->
            if (h.revive > 0) return target.fainted
            if (target.fainted) return false
            val amount = if (h.fraction > 0) (target.maxHp * h.fraction).toInt() else h.hp
            return (amount > 0 && target.hp < target.maxHp) || (target.status != null && target.status in h.cures) ||
                (h.cureConfusion && target.isActive && target.hasVolatile("confusion"))
        }
        when (itemId) {
            "ether", "maxether", "leppaberry" -> {
                val slot = target.moveSlots.getOrNull(moveIndex) ?: target.moveSlots.minByOrNull { it.pp } ?: return false
                return slot.pp < slot.maxPp(b.dex)
            }
            "elixir", "maxelixir" -> return target.moveSlots.any { it.pp < it.maxPp(b.dex) }
        }
        return true
    }

    /** Applies a bag item to a team member (active or benched). Returns false if it had no effect. */
    fun apply(b: Battle, target: BattleMon, itemId: String, moveIndex: Int): Boolean {
        healing[itemId]?.let { h ->
            if (h.revive > 0) {
                if (!target.fainted) return false
                target.mon.fainted = false
                target.hp = max(1, (target.baseMaxHp * h.revive).toInt())
                b.message("${target.name} was revived!")
                return true
            }
            if (target.fainted) return false
            var did = false
            val amount = if (h.fraction > 0) (target.maxHp * h.fraction).toInt() else h.hp
            if (amount > 0 && target.hp < target.maxHp) {
                val before = target.hp
                target.hp = min(target.maxHp, target.hp + amount)
                b.events.add(BattleEvent.HpChange(target.side.index, b.teamIndex(target), target.hp, target.maxHp, target.hp - before, "item"))
                b.message("${target.name}'s HP was restored by ${target.hp - before} point(s).")
                did = true
            }
            if (target.status != null && target.status in h.cures) { b.cureStatus(target); did = true }
            if (h.cureConfusion && target.isActive && target.hasVolatile("confusion")) { target.volatiles.remove("confusion"); did = true }
            return did
        }
        xItems[itemId]?.let { stat ->
            if (!target.isActive) return false
            return b.boost(target, mapOf(stat to 2), target, itemId)
        }
        when (itemId) {
            "ether", "maxether", "leppaberry" -> {
                val slot = target.moveSlots.getOrNull(moveIndex) ?: target.moveSlots.minByOrNull { it.pp } ?: return false
                val maxPp = slot.maxPp(b.dex)
                if (slot.pp >= maxPp) return false
                slot.pp = if (itemId == "maxether") maxPp else min(maxPp, slot.pp + 10)
                b.message("${target.name}'s PP was restored.")
                return true
            }
            "elixir", "maxelixir" -> {
                var did = false
                for (s in target.moveSlots) { val m = s.maxPp(b.dex); if (s.pp < m) { s.pp = if (itemId == "maxelixir") m else min(m, s.pp + 10); did = true } }
                if (did) b.message("${target.name}'s PP was restored.")
                return did
            }
            "direhit" -> return b.addVolatile(target, "focusenergy", target, "direhit") != null
            "guardspec" -> return b.addSideCondition(target.side, "mist", target)
            "pokeflute" -> {
                var did = false
                for (m in b.activeMons()) if (m.status == MajorStatus.SLEEP) { b.cureStatus(m); did = true }
                return did
            }
        }
        return false
    }

    fun isUsableInBattle(itemId: String) = itemId in healing || itemId in xItems ||
        itemId in setOf("ether", "maxether", "elixir", "maxelixir", "direhit", "guardspec", "pokeflute", "leppaberry")

    fun needsTarget(itemId: String) = itemId in healing || itemId in setOf("ether", "maxether", "elixir", "maxelixir", "leppaberry")
}
