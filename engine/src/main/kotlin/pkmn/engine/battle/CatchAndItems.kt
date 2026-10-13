package pkmn.engine.battle

import pkmn.engine.mon.MajorStatus
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Generation VIII+ capture formula including critical captures. */
object CatchCalc {
    data class Result(val caught: Boolean, val shakes: Int, val critical: Boolean)

    fun ballModifier(b: Battle, user: BattleMon, target: BattleMon, ball: String): Double {
        val sp = target.species
        val types = target.effectiveTypes()
        return when (ball) {
            "masterball" -> 255.0
            "greatball" -> 1.5
            "ultraball" -> 2.0
            "safariball", "sportball" -> 1.5
            "netball" -> if ("Water" in types || "Bug" in types) 3.5 else 1.0
            "diveball" -> if (b.environment == "water") 3.5 else 1.0
            "nestball" -> max(1.0, (41 - target.level) / 10.0)
            "repeatball" -> if (b.player.mons.isNotEmpty() && repeatCheck?.invoke(sp.baseSpecies) == true) 3.5 else 1.0
            "timerball" -> min(4.0, 1.0 + b.turn * 1229.0 / 4096.0)
            "duskball" -> if (b.isNight || b.environment == "cave") 3.0 else 1.0
            "quickball" -> if (b.turn <= 1) 5.0 else 1.0
            "levelball" -> when { user.level >= target.level * 4 -> 8.0; user.level >= target.level * 2 -> 4.0; user.level > target.level -> 2.0; else -> 1.0 }
            "lureball" -> if (b.environment == "fishing") 4.0 else 1.0
            "moonball" -> if (sp.evolutions.any { it.item == "moonstone" }) 4.0 else 1.0
            "loveball" -> if (user.species.baseSpecies == sp.baseSpecies && user.mon.gender != target.mon.gender && user.mon.gender != pkmn.engine.mon.Gender.NONE) 8.0 else 1.0
            "heavyball" -> 1.0 // handled as an additive modifier below
            "fastball" -> if (sp.baseStats.spe >= 100) 4.0 else 1.0
            "dreamball" -> if (target.status == MajorStatus.SLEEP) 4.0 else 1.0
            "beastball" -> if ("Ultra Beast" in sp.tags) 5.0 else 0.1
            else -> 1.0
        }
    }

    /** Hook so the campaign layer can tell the Repeat Ball whether a species was caught before. */
    var repeatCheck: ((String) -> Boolean)? = null

    fun attempt(b: Battle, user: BattleMon, target: BattleMon, ball: String): Result {
        if (ball == "masterball") return Result(true, 3, false)
        val maxHp = target.maxHp.toDouble()
        val hp = target.hp.toDouble()
        var rate = target.species.catchRate.toDouble()
        if (ball == "heavyball") {
            val w = target.species.weightkg
            rate = max(1.0, rate + when { w >= 300 -> 30; w >= 200 -> 20; w < 100 -> -20; else -> 0 })
        }
        val ballMod = if ("Ultra Beast" in target.species.tags && ball != "beastball") 0.1 else ballModifier(b, user, target, ball)
        val statusMod = when (target.status) {
            MajorStatus.SLEEP, MajorStatus.FREEZE -> 2.5
            null -> 1.0
            else -> 1.5
        }
        // Low-level wild Pokémon are easier to catch (Gen VIII+).
        val lowLevel = if (target.level < 13) (36 - 2 * target.level) / 10.0 else 1.0
        var a = ((3 * maxHp - 2 * hp) * rate * ballMod / (3 * maxHp)) * statusMod * lowLevel
        a = min(255.0, max(1.0, a))
        if (a >= 255) return Result(true, 3, false)
        val shakeProb = floor(65536 / (255 / a).pow(0.1875)).toInt()
        // Critical capture chance scales with Pokédex progress (supplied by the campaign layer).
        val dexCaught = caughtCount?.invoke() ?: 0
        val critMult = when { dexCaught > 600 -> 2.5; dexCaught > 450 -> 2.0; dexCaught > 300 -> 1.5; dexCaught > 150 -> 1.0; dexCaught > 30 -> 0.5; else -> 0.0 }
        val critChance = floor(min(255.0, a) * critMult / 6)
        if (b.rng.nextInt(256) < critChance) {
            val ok = b.rng.nextInt(65536) < shakeProb
            return Result(ok, if (ok) 1 else 0, true)
        }
        var shakes = 0
        for (i in 0 until 4) {
            if (b.rng.nextInt(65536) >= shakeProb) return Result(false, min(shakes, 3), false)
            shakes++
        }
        return Result(true, 3, false)
    }

    var caughtCount: (() -> Int)? = null
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
