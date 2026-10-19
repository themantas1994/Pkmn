package pkmn.engine.battle

import pkmn.engine.data.Stat
import pkmn.engine.mon.Gender
import pkmn.engine.mon.MajorStatus
import kotlin.math.max
import kotlin.math.min

/**
 * Second batch of move scripts: moves whose Showdown definitions have callbacks that were not covered by
 * [MoveScripts] (audit against data/moves.ts, see [BattleCoverage]). Registered after [MoveScripts], so an id
 * defined here replaces an earlier, less complete script.
 */
object MoveScriptsExtra {
    private fun reg(m: MoveScript) = Effects.registerMove(m)

    private fun status(id: String, f: (Battle, MoveCtx) -> Boolean) = reg(object : MoveScript(id) {
        override fun onUseStatus(b: Battle, mc: MoveCtx): Boolean = f(b, mc)
    })

    private fun secondary(id: String, f: (Battle, MoveCtx) -> Unit) = reg(object : MoveScript(id) {
        override fun onSecondary(b: Battle, mc: MoveCtx) = f(b, mc)
    })

    private fun bp(id: String, f: (Battle, MoveCtx, Int) -> Int) = reg(object : MoveScript(id) {
        override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = f(b, mc, bp)
    })

    /** Attack and Sp. Atk including stat stages but not other modifiers (Showdown's getStat(stat, false, true)). */
    fun boostedStat(b: Battle, m: BattleMon, stat: String) = b.applyBoost(m.rawStat(Stat.byId(stat)!!), m.boost(stat))

    private fun physicalIfStronger(b: Battle, mc: MoveCtx) {
        if (boostedStat(b, mc.user, "atk") > boostedStat(b, mc.user, "spa")) mc.move.category = "Physical"
    }

    /** Moves whose only purpose involves an ally; they fail in single battles (Showdown checks activePerHalf). */
    val FAIL_IN_SINGLES = listOf("afteryou", "quash", "allyswitch", "followme", "ragepowder", "spotlight")

    private val HIDDEN_POWER_TYPES = listOf("Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel",
        "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark")

    fun hiddenPowerType(m: BattleMon): String {
        val iv = m.mon.ivs
        val a = (iv.hp and 1) + 2 * (iv.atk and 1) + 4 * (iv.def and 1) + 8 * (iv.spe and 1) + 16 * (iv.spa and 1) + 32 * (iv.spd and 1)
        return HIDDEN_POWER_TYPES[a * 15 / 63]
    }

    /** Type granted by the holder's Plate (Judgment), Memory (Multi-Attack) or Drive (Techno Blast). */
    private fun itemType(b: Battle, m: BattleMon, kind: String): String? {
        if (!b.itemActive(m)) return null
        val item = b.dex.itemOrNull(m.item) ?: return null
        return when (kind) {
            "plate" -> item.onPlate?.takeIf { item.zMove == null }
            "memory" -> item.onMemory
            else -> item.onDrive
        }
    }

    fun registerAll() {
        for (id in FAIL_IN_SINGLES) status(id) { _, _ -> false }

        // ------------------------------------------------------------ type-changing moves
        reg(object : MoveScript("judgment") { override fun onModifyType(c: EffectCtx, mc: MoveCtx) { itemType(c.b, mc.user, "plate")?.let { mc.move.type = it } } })
        reg(object : MoveScript("multiattack") { override fun onModifyType(c: EffectCtx, mc: MoveCtx) { itemType(c.b, mc.user, "memory")?.let { mc.move.type = it } } })
        reg(object : MoveScript("technoblast") { override fun onModifyType(c: EffectCtx, mc: MoveCtx) { itemType(c.b, mc.user, "drive")?.let { mc.move.type = it } } })
        reg(object : MoveScript("hiddenpower") { override fun onModifyType(c: EffectCtx, mc: MoveCtx) { mc.move.type = hiddenPowerType(mc.user) } })
        reg(object : MoveScript("revelationdance") {
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) {
                val t = mc.user.terastallized?.takeIf { it != "Stellar" } ?: mc.user.effectiveTypes().firstOrNull { it != "???" } ?: "???"
                mc.move.type = t
            }
        })
        reg(object : MoveScript("aurawheel") {
            override fun onTry(b: Battle, mc: MoveCtx) = mc.user.species.baseSpecies == "morpeko"
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) { mc.move.type = if (mc.user.species.id == "morpekohangry") "Dark" else "Electric" }
        })
        reg(object : MoveScript("ivycudgel") {
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) {
                val id = mc.user.species.id
                mc.move.type = when {
                    id.startsWith("ogerponwellspring") -> "Water"
                    id.startsWith("ogerponhearthflame") -> "Fire"
                    id.startsWith("ogerponcornerstone") -> "Rock"
                    else -> mc.move.type
                }
            }
        })
        reg(object : MoveScript("terablast") {
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) { mc.user.terastallized?.let { mc.move.type = it } }
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { if (mc.user.terastallized != null) physicalIfStronger(c.b, mc) }
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = if (mc.user.terastallized == "Stellar") 100 else bp
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                if (hitAny && mc.user.terastallized == "Stellar" && !mc.user.fainted) b.boost(mc.user, mapOf("atk" to -1, "spa" to -1), mc.user, "terablast")
            }
        })
        reg(object : MoveScript("terastarstorm") {
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) {
                if (mc.user.species.id == "terapagosstellar") { mc.move.type = "Stellar"; if (mc.user.terastallized != null) physicalIfStronger(c.b, mc) }
            }
        })
        for (id in listOf("photongeyser", "lightthatburnsthesky")) reg(object : MoveScript(id) {
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { physicalIfStronger(c.b, mc); mc.move.ignoreAbility = true }
        })
        reg(object : MoveScript("shellsidearm") {
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) {
                val b = c.b; val u = mc.user; val t = mc.target
                val base = (2 * u.level / 5 + 2) * 90
                val phys = base * boostedStat(b, u, "atk") / max(1, boostedStat(b, t, "def")) / 50
                val spec = base * boostedStat(b, u, "spa") / max(1, boostedStat(b, t, "spd")) / 50
                if (phys > spec || (phys == spec && !b.previewing && b.rng.nextBoolean())) { mc.move.category = "Physical"; mc.move.flags.add("contact") }
            }
        })
        reg(object : MoveScript("freezedry") {
            override fun onEffectiveness(c: EffectCtx, mc: MoveCtx, type: String, multiplier: Double) = if (type == "Water" && mc.move.id == "freezedry") 2.0 else multiplier
        })
        reg(object : MoveScript("thousandarrows") {
            // Hits airborne targets; a Flying type that is not grounded takes neutral damage.
            override fun onEffectiveness(c: EffectCtx, mc: MoveCtx, type: String, multiplier: Double) =
                if (type == "Flying" && mc.move.type == "Ground" && !c.b.isGrounded(mc.target)) 1.0 else multiplier
        })
        reg(object : MoveScript("secretpower") {
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) {
                val s = when (c.b.field.terrain) {
                    "electricterrain" -> pkmn.engine.data.SecondaryEffect(30, status = "par")
                    "grassyterrain" -> pkmn.engine.data.SecondaryEffect(30, status = "slp")
                    "mistyterrain" -> pkmn.engine.data.SecondaryEffect(30, boosts = mapOf("spa" to -1))
                    "psychicterrain" -> pkmn.engine.data.SecondaryEffect(30, boosts = mapOf("spe" to -1))
                    else -> null
                } ?: return
                if (!mc.move.hasSheerForce) { mc.move.secondaries.clear(); mc.move.secondaries.add(s) }
            }
        })

        // ------------------------------------------------------------ variable power
        reg(object : MoveScript("ficklebeam") {
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (mc.move.id != "ficklebeam" || c.b.previewing) return
                if (c.b.rng.nextInt(10) < 3) { c.b.message("${c.b.nm(mc.user)} is going all out for this attack!"); mod.chain(2.0) }
            }
        })
        for ((id, other) in listOf("fusionbolt" to "fusionflare", "fusionflare" to "fusionbolt")) reg(object : MoveScript(id) {
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (mc.move.id == id && c.b.lastSuccessfulMoveThisTurn == other) mod.chain(2.0) }
        })
        reg(object : MoveScript("gravapple") { override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (mc.move.id == "gravapple" && c.b.field.has("gravity")) mod.chain(1.5) } })
        reg(object : MoveScript("grassyglide") {
            override fun onModifyPriority(c: EffectCtx, move: ActiveMove, user: BattleMon, priority: Int) =
                if (move.id == "grassyglide" && c.b.field.terrain == "grassyterrain" && c.b.isGrounded(user)) priority + 1 else priority
        })
        bp("watershuriken") { _, mc, p -> if (mc.user.species.id == "greninjaash" && mc.user.ability == "battlebond") p + 5 else p }
        for (id in listOf("pikapapow", "veeveevolley")) bp(id) { _, mc, _ -> max(1, mc.user.mon.friendship * 10 / 25) }
        reg(object : MoveScript("echoedvoice") {
            override fun onTry(b: Battle, mc: MoveCtx): Boolean { b.addPseudoWeather("echoedvoice", mc.user); return true }
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = bp * (b.field.pseudoWeather["echoedvoice"]?.counter ?: 1).coerceIn(1, 5)
        })

        // Weight-based moves fail against Dynamax Pokémon.
        for (id in listOf("lowkick", "grassknot", "heavyslam", "heatcrash")) {
            val prev = Effects.move(id)
            reg(object : MoveScript(id) {
                override fun onTry(b: Battle, mc: MoveCtx) = !mc.target.isDynamaxed
                override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = prev?.basePower(b, mc, bp) ?: bp
            })
        }

        // ------------------------------------------------------------ secondary effects defined by callbacks
        secondary("triattack") { b, mc -> b.trySetStatus(mc.target, listOf(MajorStatus.BURN, MajorStatus.PARALYSIS, MajorStatus.FREEZE)[b.rng.nextInt(3)], mc.user, "triattack", primary = false) }
        secondary("direclaw") { b, mc -> b.trySetStatus(mc.target, listOf(MajorStatus.POISON, MajorStatus.PARALYSIS, MajorStatus.SLEEP)[b.rng.nextInt(3)], mc.user, "direclaw", primary = false) }
        secondary("alluringvoice") { b, mc -> if (mc.target.statsRaisedThisTurn) b.addVolatile(mc.target, "confusion", mc.user, "alluringvoice") }
        secondary("burningjealousy") { b, mc -> if (mc.target.statsRaisedThisTurn) b.trySetStatus(mc.target, MajorStatus.BURN, mc.user, "burningjealousy", primary = false) }
        secondary("eeriespell") { b, mc ->
            val last = mc.target.lastMove ?: return@secondary
            val slot = mc.target.moveSlot(last) ?: return@secondary
            if (slot.pp <= 0) return@secondary
            val red = min(3, slot.pp); slot.pp -= red
            b.message("It reduced the PP of ${b.nmL(mc.target)}'s ${b.dex.move(last).name} by $red!")
        }
        secondary("throatchop") { b, mc -> b.addVolatile(mc.target, "throatchop", mc.user, "throatchop") }
        // The imported data drops the "self" part of these secondaries.
        // Diamond Storm's raw data is `self: {chance: 50, boosts: {def: 2}}`, which the importer turns into an unconditional
        // `self` boost: the script takes over the roll (Serene Grace doubles it) and suppresses the data boost.
        reg(object : MoveScript("diamondstorm") {
            override fun onHit(b: Battle, mc: MoveCtx) { mc.move.data2["selfBoostsHandled"] = true }
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                val chance = if (mc.user.ability == "serenegrace" && b.abilityActive(mc.user)) 100 else 50
                if (hitAny && !mc.user.fainted && b.rng.nextInt(100) < chance) b.boost(mc.user, mapOf("def" to 2), mc.user, "diamondstorm")
            }
        })
        secondary("clangoroussoulblaze") { b, mc -> if (!mc.user.fainted) b.boost(mc.user, mapOf("atk" to 1, "def" to 1, "spa" to 1, "spd" to 1, "spe" to 1), mc.user, "clangoroussoulblaze") }
        reg(object : MoveScript("steelbeam") {
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                // Like Mind Blown, Steel Beam costs half the user's HP even when it misses.
                if (!hitAny && !mc.user.fainted && !(mc.user.ability == "magicguard" && b.abilityActive(mc.user)))
                    b.directDamage(mc.user, (mc.user.baseMaxHp + 1) / 2, mc.user, "steelbeam")
            }
        })
        for ((id, cond) in listOf("ceaselessedge" to "spikes", "stoneaxe" to "stealthrock")) reg(object : MoveScript(id) {
            override fun onHit(b: Battle, mc: MoveCtx) { if (!mc.move.hasSheerForce && !mc.user.fainted && mc.hitIndex == 0) b.addSideCondition(mc.target.side, cond, mc.user) }
        })

        // ------------------------------------------------------------ after-hit effects
        reg(object : MoveScript("fellstinger") {
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                if (hitAny && mc.target.fainted && !mc.user.fainted) b.boost(mc.user, mapOf("atk" to 3), mc.user, "fellstinger")
            }
        })
        reg(object : MoveScript("relicsong") {
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                val u = mc.user
                if (!hitAny || u.fainted || u.transformed || u.species.baseSpecies != "meloetta" || mc.move.hasSheerForce) return
                val to = if (u.species.id == "meloettapirouette") "meloetta" else "meloettapirouette"
                b.formeChange(u, to, "battle", "${b.nm(u)} transformed!", changeAbility = false)
            }
        })
        reg(object : MoveScript("jawlock") {
            override fun onHit(b: Battle, mc: MoveCtx) {
                if (mc.substituteHit || mc.target.fainted || mc.user.fainted) return
                b.addVolatile(mc.user, "trapped", mc.target, "jawlock"); b.addVolatile(mc.target, "trapped", mc.user, "jawlock")
                b.message("Neither Pokémon can run away!")
            }
        })
        reg(object : MoveScript("coreenforcer") {
            override fun onHit(b: Battle, mc: MoveCtx) {
                val t = mc.target
                if (t.fainted || !t.movedThisTurn || !b.canChangeAbility(t)) return
                b.addVolatile(t, "gastroacid", mc.user, "coreenforcer")
            }
        })
        reg(object : MoveScript("freezyfrost") { override fun onHit(b: Battle, mc: MoveCtx) { for (m in b.activeMons()) m.boosts.fill(0); b.message("All stat changes were eliminated!") } })
        reg(object : MoveScript("sappyseed") { override fun onHit(b: Battle, mc: MoveCtx) { if (!mc.substituteHit) b.addVolatile(mc.target, "leechseed", mc.user, "sappyseed") } })
        reg(object : MoveScript("sparklyswirl") {
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                if (!hitAny) return
                for (m in mc.user.side.mons) if (!m.fainted && m.status != null) { if (m.isActive) b.cureStatus(m, silent = true) else { m.mon.status = null; m.mon.sleepTurns = 0 } }
            }
        })
        reg(object : MoveScript("mindblown") {
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                // Mind Blown costs half the user's HP even when it misses or fails against a target.
                if (!hitAny && !mc.user.fainted && !(mc.user.ability == "magicguard" && b.abilityActive(mc.user)))
                    b.directDamage(mc.user, (mc.user.baseMaxHp + 1) / 2, mc.user, "mindblown")
            }
        })

        // ------------------------------------------------------------ two-turn moves
        reg(object : MoveScript("solarbeam") {
            override fun skipCharge(b: Battle, user: BattleMon) = b.isSun(user)
            override val chargeMessage = "{user} absorbed light!"
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = if (b.weatherFor(mc.user) in setOf("raindance", "primordialsea", "sandstorm", "hail", "snow", "snowscape")) bp / 2 else bp
        })
        reg(object : MoveScript("solarblade") {
            override fun skipCharge(b: Battle, user: BattleMon) = b.isSun(user)
            override val chargeMessage = "{user} absorbed light!"
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = if (b.weatherFor(mc.user) in setOf("raindance", "primordialsea", "sandstorm", "hail", "snow", "snowscape")) bp / 2 else bp
        })
        reg(object : MoveScript("electroshot") {
            override fun skipCharge(b: Battle, user: BattleMon) = b.isRain(user)
            override val chargeMessage = "{user} absorbed electricity!"
            override fun onChargeTurn(b: Battle, mc: MoveCtx) { b.boost(mc.user, mapOf("spa" to 1), mc.user, "electroshot") }
        })
        reg(object : MoveScript("meteorbeam") {
            override val chargeMessage = "{user} is overflowing with space power!"
            override fun onChargeTurn(b: Battle, mc: MoveCtx) { b.boost(mc.user, mapOf("spa" to 1), mc.user, "meteorbeam") }
        })
        reg(object : MoveScript("skullbash") {
            override val chargeMessage = "{user} tucked in its head!"
            override fun onChargeTurn(b: Battle, mc: MoveCtx) { b.boost(mc.user, mapOf("def" to 1), mc.user, "skullbash") }
        })
        for ((id, msg) in listOf("skyattack" to "{user} became cloaked in a harsh light!", "razorwind" to "{user} whipped up a whirlwind!",
            "freezeshock" to "{user} became cloaked in a freezing light!", "iceburn" to "{user} became cloaked in freezing air!",
            "fly" to "{user} flew up high!", "bounce" to "{user} sprang up!", "dig" to "{user} burrowed its way under the ground!",
            "dive" to "{user} hid underwater!", "phantomforce" to "{user} vanished instantly!", "shadowforce" to "{user} vanished instantly!",
            "geomancy" to "{user} is absorbing power!")) {
            if (Effects.move(id) == null) reg(object : MoveScript(id) { override val chargeMessage = msg })
        }

        reg(object : MoveScript("skydrop") {
            override val chargeMessage = "{user} took its target into the sky!"
            override fun onChargeTurn(b: Battle, mc: MoveCtx) {
                val t = mc.target
                if (t.fainted || t.side === mc.user.side || t.hasVolatile("substitute") || b.getWeight(t) >= 200.0) { mc.move.data2["skydropFailed"] = true; return }
                b.addVolatile(t, "skydropped", mc.user, "skydrop")
            }
            override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                if (!mc.target.hasType("Flying")) return true
                b.message("It doesn't affect ${b.nmL(mc.target)}...")
                mc.move.data2["silentFail"] = true
                return false
            }
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                if (mc.move.data2["skydropFailed"] == true) { mc.user.volatiles.remove("twoturnmove"); b.message("But it failed!") }
                else if (!mc.user.hasVolatile("twoturnmove")) mc.target.volatiles.remove("skydropped")
            }
        })
        status("snatch") { b, mc -> b.addVolatile(mc.user, "snatch", mc.user, "snatch"); b.message("${b.nm(mc.user)} waits for a target to make a move!"); true }

        // ------------------------------------------------------------ start-of-turn charge moves
        reg(object : MoveScript("focuspunch") {
            override fun onBeforeTurn(b: Battle, user: BattleMon) { b.message("${b.nm(user)} is tightening its focus!") }
            override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                val r = mc.user.lastDamagedBy
                if (r != null && r.turn == b.turn && r.amount > 0) { b.message("${b.nm(mc.user)} lost its focus and couldn't move!"); mc.move.data2["silentFail"] = true; return false }
                return true
            }
        })
        reg(object : MoveScript("beakblast") {
            override fun onBeforeTurn(b: Battle, user: BattleMon) { b.addVolatile(user, "beakblast", user, "beakblast"); b.message("${b.nm(user)} started heating up its beak!") }
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) { mc.user.volatiles.remove("beakblast") }
        })
        reg(object : MoveScript("shelltrap") {
            override fun onBeforeTurn(b: Battle, user: BattleMon) { b.addVolatile(user, "shelltrap", user, "shelltrap"); b.message("${b.nm(user)} set a shell trap!") }
            override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                val hit = mc.user.volatiles["shelltrap"]?.flag == true
                mc.user.volatiles.remove("shelltrap")
                if (!hit) { b.message("${b.nm(mc.user)}'s shell trap didn't work!"); mc.move.data2["silentFail"] = true }
                return hit
            }
        })

        // ------------------------------------------------------------ conditional failure
        reg(object : MoveScript("darkvoid") { override fun onTry(b: Battle, mc: MoveCtx) = mc.user.species.baseSpecies == "darkrai" || mc.move.data2["bounced"] == true })
        reg(object : MoveScript("synchronoise") {
            override fun onTry(b: Battle, mc: MoveCtx) = mc.user.effectiveTypes().any { mc.target.hasType(it) }
        })
        reg(object : MoveScript("captivate") {
            override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                val g1 = mc.user.mon.gender; val g2 = mc.target.mon.gender
                return g1 != Gender.NONE && g2 != Gender.NONE && g1 != g2
            }
        })
        status("noretreat") { b, mc ->
            if (mc.user.hasVolatile("noretreat")) return@status false
            b.boost(mc.user, mapOf("atk" to 1, "def" to 1, "spa" to 1, "spd" to 1, "spe" to 1), mc.user, "noretreat")
            b.addVolatile(mc.user, "noretreat", mc.user, "noretreat")
            true
        }

        // ------------------------------------------------------------ status moves
        status("acupressure") { b, mc ->
            val stats = BattleMon.BOOST_IDS.filter { mc.user.boost(it) < 6 }
            if (stats.isEmpty()) false else b.boost(mc.user, mapOf(stats[b.rng.nextInt(stats.size)] to 2), mc.user, "acupressure")
        }
        status("growth") { b, mc -> val n = if (b.isSun(mc.user)) 2 else 1; b.boost(mc.user, mapOf("atk" to n, "spa" to n), mc.user, "growth", showFailure = true) }
        status("takeheart") { b, mc ->
            val ok = b.boost(mc.user, mapOf("spa" to 1, "spd" to 1), mc.user, "takeheart")
            val cured = mc.user.status != null
            if (cured) b.cureStatus(mc.user)
            ok || cured
        }
        status("lunarblessing") { b, mc ->
            val healed = b.heal(mc.user, max(1, Mod.pokeRound(mc.user.maxHp * 0.25)), "lunarblessing") > 0
            val cured = mc.user.status != null
            if (cured) b.cureStatus(mc.user)
            if (healed) b.message("${b.nm(mc.user)}'s HP was restored.")
            healed || cured
        }
        status("healpulse") { b, mc ->
            val t = mc.target
            val amt = if (mc.user.ability == "megalauncher" && b.abilityActive(mc.user)) Mod.pokeRound(t.baseMaxHp * 0.75) else (t.baseMaxHp + 1) / 2
            (b.heal(t, amt, "healpulse") > 0).also { if (it) b.message("${b.nm(t)}'s HP was restored.") }
        }
        status("floralhealing") { b, mc ->
            val t = mc.target
            val amt = if (b.field.terrain == "grassyterrain") Mod.pokeRound(t.baseMaxHp * 0.667) else (t.baseMaxHp + 1) / 2
            (b.heal(t, amt, "floralhealing") > 0).also { if (it) b.message("${b.nm(t)}'s HP was restored.") }
        }
        status("conversion2") { b, mc ->
            val last = mc.target.lastMove?.let { b.dex.moveOrNull(it) } ?: return@status false
            val options = b.dex.typeChart.types.filter { t -> t != "Stellar" && t != "???" && !mc.user.hasType(t) && b.dex.typeChart.effectiveness(last.type, t) < 1.0 }
            if (options.isEmpty() || mc.user.terastallized != null) return@status false
            val t = options[b.rng.nextInt(options.size)]
            mc.user.types = listOf(t); mc.user.addedType = null
            b.message("${b.nm(mc.user)}'s type changed to $t!"); true
        }
        status("doodle") { b, mc ->
            val a = mc.target.ability
            if (a in NO_COPY_ABILITIES || mc.user.ability == a || !b.canChangeAbility(mc.user)) return@status false
            Abilities.setAbility(b, mc.user, a)
            b.message("${b.nm(mc.user)} copied ${b.nmL(mc.target)}'s ${b.dex.abilityOrNull(a)?.name}!"); true
        }
        status("partingshot") { b, mc ->
            val t = mc.target
            val lowered = b.boost(t, mapOf("atk" to -1, "spa" to -1), mc.user, "partingshot")
            val mirror = t.ability == "mirrorarmor" && b.abilityActive(t, mc)
            if ((lowered || mirror) && mc.user.side.canSwitchTo().isNotEmpty()) mc.user.switchFlag = "true"
            lowered || mirror
        }
        status("shedtail") { b, mc ->
            val u = mc.user
            if (u.side.canSwitchTo().isEmpty() || u.hasVolatile("substitute")) return@status false
            val cost = (u.maxHp + 1) / 2
            if (u.hp <= cost) return@status false
            b.directDamage(u, cost, u, "substitute")
            u.volatiles["substitute"] = EffectState().also { it.hp = u.maxHp / 4 }
            b.message("${b.nm(u)} shed its tail to create a decoy!")
            u.switchFlag = "shedtail"; true
        }
        status("stuffcheeks") { b, mc ->
            val item = mc.user.item
            if (item == null || b.dex.itemOrNull(item)?.isBerry != true) return@status false
            if (!b.boost(mc.user, mapOf("def" to 2), mc.user, "stuffcheeks")) return@status false
            Items.forceEat(b, mc.user, item); true
        }
        status("teatime") { b, _ ->
            val eaters = b.activeMons().filter { m -> m.item?.let { b.dex.itemOrNull(it)?.isBerry } == true && b.itemActive(m) }
            if (eaters.isEmpty()) return@status false
            b.message("It's teatime! Everyone dug in to their Berries!")
            for (m in eaters) Items.forceEat(b, m, m.item!!)
            true
        }
        status("chillyreception") { b, mc ->
            b.message("${b.nm(mc.user)} is preparing to tell a chillingly bad joke!")
            val set = b.setWeather("snowscape", mc.user)
            val switch = mc.user.side.canSwitchTo().isNotEmpty()
            if (switch) mc.user.switchFlag = "true"
            set || switch
        }
        status("instruct") { b, mc ->
            val t = mc.target
            val last = t.lastMove?.let { b.dex.moveOrNull(it) } ?: return@status false
            val slot = t.moveSlot(last.id)
            if (t.isDynamaxed || last.hasFlag("failinstruct") || last.hasFlag("charge") || last.hasFlag("recharge") || last.isZ != null || last.isMax != null || slot == null || slot.pp <= 0) return@status false
            b.message("${b.nm(t)} followed ${b.nmL(mc.user)}'s instructions!")
            slot.pp--
            MoveScripts.callMove(b, t, last.id); true
        }
        status("venomdrench") { b, mc ->
            val t = mc.target
            if (t.status != MajorStatus.POISON && t.status != MajorStatus.TOXIC) false
            else b.boost(t, mapOf("atk" to -1, "spa" to -1, "spe" to -1), mc.user, "venomdrench")
        }
        status("psychoshift") { b, mc ->
            val s = mc.user.status ?: return@status false
            if (mc.target.status != null) return@status false
            if (!b.trySetStatus(mc.target, s, mc.user, "psychoshift", primary = true)) return@status true
            b.cureStatus(mc.user); true
        }
        status("autotomize") { b, mc ->
            val u = mc.user
            if (!b.boost(u, mapOf("spe" to 2), u, "autotomize", showFailure = true)) return@status false
            if (b.getWeight(u) > 0.1) {
                val st = u.volatiles.getOrPut("autotomize") { EffectState() }
                st.counter++
                b.message("${b.nm(u)} became nimble!")
            }
            true
        }
        status("bestow") { b, mc ->
            val item = mc.user.item ?: return@status false
            if (mc.target.item != null || !b.canRemoveItem(mc.user, item)) return@status false
            mc.user.item = null; mc.user.itemState = EffectState(); mc.user.itemKnockedOff = true
            mc.target.item = item; mc.target.itemState = EffectState()
            b.message("${b.nm(mc.target)} received ${b.dex.itemOrNull(item)?.name} from ${b.nmL(mc.user)}!"); true
        }
        status("corrosivegas") { b, mc ->
            val item = b.takeItem(mc.target, mc.user) ?: return@status false
            b.message("${b.nm(mc.user)} corroded ${b.nmL(mc.target)}'s ${b.dex.itemOrNull(item)?.name}!"); true
        }
        status("flowershield") { b, mc ->
            var ok = false
            for (m in b.activeMons()) if (m.hasType("Grass")) ok = b.boost(m, mapOf("def" to 1), mc.user, "flowershield") || ok
            ok
        }
        status("rototiller") { b, mc ->
            var ok = false
            for (m in b.activeMons()) if (m.hasType("Grass") && b.isGrounded(m)) ok = b.boost(m, mapOf("atk" to 1, "spa" to 1), mc.user, "rototiller") || ok
            ok
        }
        for ((id, boosts) in listOf("magneticflux" to mapOf("def" to 1, "spd" to 1), "gearup" to mapOf("atk" to 1, "spa" to 1))) status(id) { b, mc ->
            if (mc.user.ability !in setOf("plus", "minus") || !b.abilityActive(mc.user)) false else b.boost(mc.user, boosts, mc.user, id)
        }
        status("odorsleuth") { b, mc -> b.addVolatile(mc.target, "foresight", mc.user, "odorsleuth") != null }
        status("electrify") { b, mc ->
            if (mc.target.movedThisTurn) return@status false
            b.addVolatile(mc.target, "electrify", mc.user, "electrify") != null
        }
        status("bide") { b, mc ->
            val u = mc.user
            val st = u.volatiles["bide"]
            if (st == null) { b.addVolatile(u, "bide", u, "bide"); return@status true }
            st.counter++
            if (st.counter < 2) { b.message("${b.nm(u)} is storing energy!"); return@status true }
            u.volatiles.remove("bide")
            b.message("${b.nm(u)} unleashed its energy!")
            val target = u.side.foe.active
            if (st.hp <= 0 || target == null || target.fainted) return@status false
            b.directDamage(target, st.hp * 2, u, "bide", moveDamage = true)
            true
        }
    }

    /** Abilities that copying moves (Doodle, Role Play) and Trace cannot take. */
    val NO_COPY_ABILITIES = setOf("trace", "multitype", "stancechange", "schooling", "comatose", "shieldsdown", "disguise", "rkssystem",
        "battlebond", "powerconstruct", "iceface", "gulpmissile", "neutralizinggas", "zerotohero", "commander", "imposter", "illusion",
        "flowergift", "forecast", "hungerswitch", "asoneglastrier", "asonespectrier", "receiver", "powerofalchemy", "wonderguard",
        "zenmode", "protosynthesis", "quarkdrive", "terashift", "teraformzero", "poisonpuppeteer", "terashell")
}
