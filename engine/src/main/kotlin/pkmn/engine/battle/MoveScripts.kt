package pkmn.engine.battle

import pkmn.engine.data.Stat
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.MoveSlot
import kotlin.math.max
import kotlin.math.min

/**
 * Custom behaviour for moves whose effect is not fully described by data. Moves without an entry use the
 * data-driven pipeline in [Battle] (damage, secondary effects, boosts, status, volatiles, side conditions...).
 */
object MoveScripts {
    private fun reg(m: MoveScript) = Effects.registerMove(m)

    private fun bp(id: String, f: (Battle, MoveCtx, Int) -> Int) = reg(object : MoveScript(id) {
        override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = f(b, mc, bp)
    })

    private fun fixed(id: String, f: (Battle, MoveCtx) -> Int?) = reg(object : MoveScript(id) {
        override fun fixedDamage(b: Battle, mc: MoveCtx) = f(b, mc)
    })

    private fun failUnless(id: String, cond: (Battle, MoveCtx) -> Boolean) = reg(object : MoveScript(id) {
        override fun onTry(b: Battle, mc: MoveCtx) = cond(b, mc)
    })

    private fun status(id: String, f: (Battle, MoveCtx) -> Boolean) = reg(object : MoveScript(id) {
        override fun onUseStatus(b: Battle, mc: MoveCtx): Boolean = f(b, mc)
    })

    private fun afterHit(id: String, f: (Battle, MoveCtx) -> Unit) = reg(object : MoveScript(id) {
        override fun onHit(b: Battle, mc: MoveCtx) = f(b, mc)
    })

    /** After-hit effect on the target: blocked when the hit only struck a substitute. */
    private fun afterHitTarget(id: String, f: (Battle, MoveCtx) -> Unit) = reg(object : MoveScript(id) {
        override fun onHit(b: Battle, mc: MoveCtx) { if (!mc.substituteHit && !mc.target.fainted) f(b, mc) }
    })

    private fun weightPower(w: Double) = when { w >= 200 -> 120; w >= 100 -> 100; w >= 50 -> 80; w >= 25 -> 60; w >= 10 -> 40; else -> 20 }

    private fun speed(b: Battle, m: BattleMon) = b.getSpeed(m)

    /** Transform/Imposter: copy species data, types, stats, ability, boosts and moves (5 PP each). */
    fun transform(b: Battle, user: BattleMon, target: BattleMon) {
        if (target.transformed || target.hasVolatile("substitute") || user.transformed) { b.message("But it failed!"); return }
        user.transformed = true
        user.setSpecies(target.species, changeAbility = false)
        user.types = target.types
        user.ability = target.ability
        user.abilityState = EffectState()
        target.boosts.copyInto(user.boosts)
        user.moveSlots = target.moveSlots.map { MoveSlot(it.id, min(5, b.dex.moveOrNull(it.id)?.pp ?: 5)) }.toMutableList()
        b.events.add(BattleEvent.FormeChange(user.side.index, b.teamIndex(user), target.species.id, "transform", "${b.nm(user)} transformed into ${target.species.name}!"))
        b.log.add("transformed")
    }

    fun registerAll() {
        // ------------------------------------------------------------ protection (stalling moves)
        for (id in listOf("protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "obstruct", "silktrap", "burningbulwark", "endure", "maxguard")) {
            reg(object : MoveScript(id) {
                override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                    val user = mc.user
                    val stall = user.volatiles["stall"]
                    if (!user.movedThisTurn) return false
                    if (b.activeMons().all { it === user || it.movedThisTurn } && b.activeMons().size > 1 && user.side.foe.active?.movedThisTurn == true) {
                        user.volatiles.remove("stall"); return false
                    }
                    if (stall != null && id != "maxguard") {
                        val chance = 1.0 / Math.pow(3.0, stall.counter.toDouble())
                        if (b.rng.nextDouble() >= chance) { user.volatiles.remove("stall"); return false }
                    }
                    return true
                }
                override fun onUseStatus(b: Battle, mc: MoveCtx): Boolean? {
                    val vol = if (id == "detect") "protect" else id
                    val st = b.addVolatile(mc.user, vol, mc.user, id) ?: return false
                    st.duration = 1
                    b.addVolatile(mc.user, "stall", mc.user, id)
                    return true
                }
            })
        }

        // ------------------------------------------------------------ fixed / proportional damage
        fixed("superfang") { _, mc -> max(1, mc.target.hp / 2) }
        fixed("naturesmadness") { _, mc -> max(1, mc.target.hp / 2) }
        fixed("ruination") { _, mc -> max(1, mc.target.hp / 2) }
        fixed("guardianofalola") { _, mc -> max(1, mc.target.hp * 3 / 4) }
        fixed("psywave") { b, mc -> max(1, mc.user.level * (50 + b.rng.nextInt(101)) / 100) }
        fixed("finalgambit") { b, mc -> val d = mc.user.hp; b.directDamage(mc.user, d, mc.user, "finalgambit", silent = true); d }
        reg(object : MoveScript("endeavor") {
            override fun onTry(b: Battle, mc: MoveCtx) = mc.target.hp > mc.user.hp
            override fun fixedDamage(b: Battle, mc: MoveCtx) = mc.target.hp - mc.user.hp
        })
        for ((id, cat, mult) in listOf(Triple("counter", "Physical", 2.0), Triple("mirrorcoat", "Special", 2.0), Triple("metalburst", null, 1.5), Triple("comeuppance", null, 1.5))) {
            reg(object : MoveScript(id) {
                override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                    val r = mc.user.lastDamagedBy ?: return false
                    return r.turn == b.turn && r.amount > 0 && r.source.side !== mc.user.side && (cat == null || r.category == cat)
                }
                override fun fixedDamage(b: Battle, mc: MoveCtx) = max(1, (mc.user.lastDamagedBy!!.amount * mult).toInt())
            })
        }

        // ------------------------------------------------------------ variable base power
        bp("gyroball") { b, mc, _ -> min(150, 25 * speed(b, mc.target) / max(1, speed(b, mc.user)) + 1) }
        bp("electroball") { b, mc, _ -> val r = speed(b, mc.user) / max(1, speed(b, mc.target)); when { r >= 4 -> 150; r >= 3 -> 120; r >= 2 -> 80; r >= 1 -> 60; else -> 40 } }
        for (id in listOf("eruption", "waterspout", "dragonenergy")) bp(id) { _, mc, _ -> max(1, 150 * mc.user.hp / mc.user.maxHp) }
        for (id in listOf("flail", "reversal")) bp(id) { _, mc, _ ->
            val p = 48 * mc.user.hp / mc.user.maxHp
            when { p <= 1 -> 200; p <= 4 -> 150; p <= 9 -> 100; p <= 16 -> 80; p <= 32 -> 40; else -> 20 }
        }
        bp("return") { _, mc, _ -> max(1, mc.user.mon.friendship * 10 / 25) }
        bp("frustration") { _, mc, _ -> max(1, (255 - mc.user.mon.friendship) * 10 / 25) }
        for (id in listOf("lowkick", "grassknot")) bp(id) { b, mc, _ -> weightPower(b.getWeight(mc.target)) }
        for (id in listOf("heavyslam", "heatcrash")) bp(id) { b, mc, _ ->
            val r = b.getWeight(mc.user) / b.getWeight(mc.target)
            when { r >= 5 -> 120; r >= 4 -> 100; r >= 3 -> 80; r >= 2 -> 60; else -> 40 }
        }
        bp("acrobatics") { _, mc, p -> if (mc.user.item == null) p * 2 else p }
        bp("facade") { _, mc, p -> if (mc.user.status != null) p * 2 else p }
        bp("hex") { _, mc, p -> if (mc.target.status != null || mc.target.ability == "comatose") p * 2 else p }
        bp("infernalparade") { _, mc, p -> if (mc.target.status != null) p * 2 else p }
        bp("venoshock") { _, mc, p -> if (mc.target.status == MajorStatus.POISON || mc.target.status == MajorStatus.TOXIC) p * 2 else p }
        bp("barbbarrage") { _, mc, p -> if (mc.target.status == MajorStatus.POISON || mc.target.status == MajorStatus.TOXIC) p * 2 else p }
        bp("brine") { _, mc, p -> if (mc.target.hp * 2 <= mc.target.maxHp) p * 2 else p }
        bp("payback") { _, mc, p -> if (mc.target.movedThisTurn) p * 2 else p }
        for (id in listOf("avalanche", "revenge")) bp(id) { b, mc, p -> if (mc.user.lastDamagedBy?.let { it.turn == b.turn && it.source === mc.target } == true) p * 2 else p }
        bp("assurance") { b, mc, p -> if (mc.target.hurtThisTurn) p * 2 else p }
        for (id in listOf("storedpower", "powertrip")) bp(id) { _, mc, p -> p + 20 * mc.user.boosts.take(7).filter { it > 0 }.sum() }
        bp("punishment") { _, mc, _ -> min(200, 60 + 20 * mc.target.boosts.take(7).filter { it > 0 }.sum()) }
        for (id in listOf("wringout", "crushgrip")) bp(id) { _, mc, _ -> max(1, 120 * mc.target.hp / mc.target.maxHp) }
        bp("hardpress") { _, mc, _ -> max(1, 100 * mc.target.hp / mc.target.maxHp) }
        bp("magnitude") { b, _, _ ->
            val r = b.rng.nextInt(100)
            val (mag, power) = when { r < 5 -> 4 to 10; r < 15 -> 5 to 30; r < 35 -> 6 to 50; r < 65 -> 7 to 70; r < 85 -> 8 to 90; r < 95 -> 9 to 110; else -> 10 to 150 }
            if (!b.previewing) b.message("Magnitude $mag!")
            power
        }
        reg(object : MoveScript("weatherball") {
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) {
                mc.move.type = when {
                    c.b.isSun(mc.user) -> "Fire"; c.b.isRain(mc.user) -> "Water"; c.b.isSand() -> "Rock"; c.b.isSnow() -> "Ice"; else -> "Normal"
                }
            }
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = if (b.weather() != null && b.weather() != "deltastream") bp * 2 else bp
        })
        reg(object : MoveScript("terrainpulse") {
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) {
                if (!c.b.isGrounded(mc.user)) return
                mc.move.type = when (c.b.field.terrain) { "electricterrain" -> "Electric"; "grassyterrain" -> "Grass"; "mistyterrain" -> "Fairy"; "psychicterrain" -> "Psychic"; else -> "Normal" }
            }
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = if (b.field.terrain != null && b.isGrounded(mc.user)) bp * 2 else bp
        })
        bp("risingvoltage") { b, mc, p -> if (b.field.terrain == "electricterrain" && b.isGrounded(mc.target)) p * 2 else p }
        bp("expandingforce") { b, mc, p -> if (b.field.terrain == "psychicterrain" && b.isGrounded(mc.user)) p * 3 / 2 else p }
        bp("mistyexplosion") { b, mc, p -> if (b.field.terrain == "mistyterrain" && b.isGrounded(mc.user)) p * 3 / 2 else p }
        bp("psyblade") { b, _, p -> if (b.field.terrain == "electricterrain") p * 3 / 2 else p }
        for (id in listOf("boltbeak", "fishiousrend")) bp(id) { _, mc, p -> if (!mc.target.movedThisTurn) p * 2 else p }
        bp("lashout") { _, mc, p -> if (mc.user.statsLoweredThisTurn) p * 2 else p }
        bp("ragefist") { _, mc, _ -> min(350, 50 + 50 * mc.user.timesAttacked) }
        bp("lastrespects") { _, mc, _ -> min(5050, 50 + 50 * mc.user.side.totalFainted) }
        bp("temperflare") { _, mc, p -> if (mc.user.lastMoveFailed) p * 2 else p }
        bp("stompingtantrum") { _, mc, p -> if (mc.user.lastMoveFailed) p * 2 else p }
        bp("retaliate") { _, mc, p -> if (mc.user.side.faintedLastTurn) p * 2 else p }
        bp("trumpcard") { _, mc, _ ->
            val pp = mc.user.moveSlot("trumpcard")?.pp ?: 0
            when (pp) { 0 -> 200; 1 -> 80; 2 -> 60; 3 -> 50; else -> 40 }
        }
        bp("spitup") { _, mc, _ -> 100 * (mc.user.volatiles["stockpile"]?.counter ?: 0) }
        bp("furycutter") { _, mc, _ -> 40 * (1 shl min(2, (mc.user.volatiles["furycutter"]?.counter ?: 0))) }
        afterHit("furycutter") { b, mc -> val st = mc.user.volatiles.getOrPut("furycutter") { EffectState() }; st.counter++ }
        reg(object : MoveScript("tripleaxel") { override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = 20 * (mc.hitIndex + 1) })
        reg(object : MoveScript("triplekick") { override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = 10 * (mc.hitIndex + 1) })
        reg(object : MoveScript("beatup") {
            override fun hitCount(b: Battle, mc: MoveCtx) = mc.user.side.mons.count { !it.fainted && it.status == null }
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int): Int {
                val members = mc.user.side.mons.filter { !it.fainted && it.status == null }
                val m = members.getOrNull(mc.hitIndex) ?: mc.user
                return m.species.baseStats.atk / 10 + 5
            }
        })
        reg(object : MoveScript("populationbomb") { override fun hitCount(b: Battle, mc: MoveCtx) = if (mc.user.item == "loadeddice") 4 + b.rng.nextInt(7) else 10 })
        for (id in listOf("rollout", "iceball")) reg(object : MoveScript(id) {
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int): Int {
                val n = mc.user.volatiles["rollout"]?.counter ?: 0
                return bp * (1 shl n) * (if (mc.user.hasVolatile("defensecurl")) 2 else 1)
            }
            override fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {
                if (!hitAny) { mc.user.volatiles.remove("rollout"); return }
                val st = mc.user.volatiles["rollout"]
                if (st == null) { mc.user.volatiles["rollout"] = EffectState().also { it.counter = 1; it.moveId = id } }
                else { st.counter++; if (st.counter >= 5) mc.user.volatiles.remove("rollout") }
            }
        })
        bp("wakeupslap") { _, mc, p -> if (mc.target.status == MajorStatus.SLEEP) p * 2 else p }
        afterHitTarget("wakeupslap") { b, mc -> if (mc.target.status == MajorStatus.SLEEP) b.cureStatus(mc.target) }
        bp("smellingsalts") { _, mc, p -> if (mc.target.status == MajorStatus.PARALYSIS) p * 2 else p }
        afterHitTarget("smellingsalts") { b, mc -> if (mc.target.status == MajorStatus.PARALYSIS) b.cureStatus(mc.target) }
        for (id in listOf("collisioncourse", "electrodrift")) reg(object : MoveScript(id) {
            override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (mc.effectiveness > 1) mod.chain(5461.0 / 4096) }
        })
        bp("solarbeam") { b, mc, p -> if (b.weather() != null && !b.isSun(mc.user) && b.weather() != "deltastream") p / 2 else p }
        bp("solarblade") { b, mc, p -> if (b.weather() != null && !b.isSun(mc.user) && b.weather() != "deltastream") p / 2 else p }
        bp("knockoff") { b, mc, p -> if (mc.target.item != null && b.canRemoveItem(mc.target, mc.target.item!!)) p * 3 / 2 else p }
        afterHitTarget("knockoff") { b, mc ->
            val item = b.takeItem(mc.target, mc.user)
            if (item != null) b.message("${b.nm(mc.user)} knocked off ${b.nmL(mc.target)}'s ${b.dex.itemOrNull(item)?.name}!")
        }
        failUnless("poltergeist") { _, mc -> mc.target.item != null }
        bp("pursuit") { _, mc, p -> if (mc.target.switchFlag != null) p * 2 else p }
        bp("naturalgift") { b, mc, _ -> b.dex.itemOrNull(mc.user.item)?.naturalGiftPower ?: 0 }
        reg(object : MoveScript("fling") {
            override fun onTry(b: Battle, mc: MoveCtx) = mc.user.item != null && b.canRemoveItem(mc.user, mc.user.item!!)
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int) = b.dex.itemOrNull(mc.user.item)?.flingPower?.takeIf { it > 0 } ?: 10
            override fun onHit(b: Battle, mc: MoveCtx) {
                val item = mc.user.item ?: return
                b.message("${b.nm(mc.user)} flung its ${b.dex.itemOrNull(item)?.name}!")
                b.consumeItem(mc.user)
                when (item) {
                    "flameorb" -> b.trySetStatus(mc.target, MajorStatus.BURN, mc.user, "fling", primary = false)
                    "toxicorb" -> b.trySetStatus(mc.target, MajorStatus.TOXIC, mc.user, "fling", primary = false)
                    "lightball" -> b.trySetStatus(mc.target, MajorStatus.PARALYSIS, mc.user, "fling", primary = false)
                    "poisonbarb" -> b.trySetStatus(mc.target, MajorStatus.POISON, mc.user, "fling", primary = false)
                    "kingsrock", "razorfang" -> b.addVolatile(mc.target, "flinch", mc.user, "fling")
                }
            }
        })
        reg(object : MoveScript("present") {
            override fun basePower(b: Battle, mc: MoveCtx, bp: Int): Int {
                val r = b.rng.nextInt(10)
                return when { r < 4 -> 40; r < 7 -> 80; r < 8 -> 120; else -> { mc.move.data2["presentheal"] = true; 0 } }
            }
        })

        // ------------------------------------------------------------ conditional failure
        for (id in listOf("fakeout", "firstimpression", "matblock")) failUnless(id) { _, mc -> mc.user.activeMoveActions <= 1 }
        failUnless("suckerpunch") { b, mc -> !mc.target.movedThisTurn && pendingMoveIsAttack(b, mc.target) }
        failUnless("thunderclap") { b, mc -> !mc.target.movedThisTurn && pendingMoveIsAttack(b, mc.target) }
        failUnless("upperhand") { b, mc -> !mc.target.movedThisTurn && (b.dex.moveOrNull(pendingMoveId(b, mc.target))?.priority ?: 0) > 0 }
        failUnless("dreameater") { _, mc -> mc.target.status == MajorStatus.SLEEP || mc.target.ability == "comatose" }
        failUnless("snore") { _, mc -> mc.user.status == MajorStatus.SLEEP || mc.user.ability == "comatose" }
        failUnless("lastresort") { _, mc -> mc.user.moveSlots.size > 1 && mc.user.moveSlots.all { it.id == "lastresort" || it.pp < it.maxPp(mc.user.dex) } }
        failUnless("belch") { _, mc -> mc.user.hasVolatile("ateberry") }
        failUnless("steelroller") { b, _ -> b.field.terrain != null }
        afterHit("steelroller") { b, _ -> b.clearTerrain() }
        failUnless("burnup") { _, mc -> mc.user.hasType("Fire") }
        afterHit("burnup") { b, mc -> mc.user.types = mc.user.types.filter { it != "Fire" }.ifEmpty { listOf("???") }; b.message("${b.nm(mc.user)} burned itself out!") }
        failUnless("doubleshock") { _, mc -> mc.user.hasType("Electric") }
        afterHit("doubleshock") { b, mc -> mc.user.types = mc.user.types.filter { it != "Electric" }.ifEmpty { listOf("???") }; b.message("${b.nm(mc.user)} used up all its electricity!") }
        reg(object : MoveScript("focuspunch") {
            override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                if (mc.user.lastDamagedBy?.turn == b.turn && mc.user.lastDamagedBy?.amount ?: 0 > 0) { b.message("${b.nm(mc.user)} lost its focus and couldn't move!"); mc.move.data2["silentFail"] = true; return false }
                return true
            }
        })
        failUnless("hyperspacefury") { _, mc -> mc.user.species.id == "hoopaunbound" }

        // ------------------------------------------------------------ item interaction
        for (id in listOf("thief", "covet")) afterHitTarget(id) { b, mc ->
            if (mc.user.item != null || mc.user.fainted) return@afterHitTarget
            val item = b.takeItem(mc.target, mc.user) ?: return@afterHitTarget
            mc.user.item = item
            b.message("${b.nm(mc.user)} stole ${b.nmL(mc.target)}'s ${b.dex.itemOrNull(item)?.name}!")
        }
        for (id in listOf("trick", "switcheroo")) status(id) { b, mc ->
            val a = mc.user.item; val t = mc.target.item
            if (a == null && t == null) return@status false
            if ((a != null && !b.canRemoveItem(mc.user, a)) || (t != null && !b.canRemoveItem(mc.target, t))) return@status false
            if (mc.target.ability == "stickyhold" && b.abilityActive(mc.target)) return@status false
            mc.user.item = t; mc.target.item = a
            mc.user.itemKnockedOff = true; mc.target.itemKnockedOff = true
            mc.user.itemState = EffectState(); mc.target.itemState = EffectState()
            b.message("${b.nm(mc.user)} switched items with its target!")
            true
        }
        for (id in listOf("bugbite", "pluck")) afterHitTarget(id) { b, mc ->
            val item = mc.target.item ?: return@afterHitTarget
            if (b.dex.itemOrNull(item)?.isBerry != true) return@afterHitTarget
            if (b.takeItem(mc.target, mc.user) == null) return@afterHitTarget
            mc.target.itemKnockedOff = false
            b.message("${b.nm(mc.user)} stole and ate its target's ${b.dex.itemOrNull(item)?.name}!")
            Items.applyBerry(b, mc.user, item)
        }
        afterHitTarget("incinerate") { b, mc ->
            val item = mc.target.item ?: return@afterHitTarget
            val d = b.dex.itemOrNull(item)
            if (d?.isBerry == true || d?.isGem == true) { mc.target.item = null; mc.target.itemKnockedOff = false; b.message("${b.nm(mc.target)}'s ${d.name} was burned up!") }
        }
        status("recycle") { b, mc ->
            val last = mc.user.lastItem ?: return@status false
            if (mc.user.item != null) return@status false
            mc.user.item = last; mc.user.lastItem = null
            b.message("${b.nm(mc.user)} found one ${b.dex.itemOrNull(last)?.name}!"); true
        }

        // ------------------------------------------------------------ HP / stat manipulation
        status("rest") { b, mc ->
            val u = mc.user
            if (u.hp >= u.maxHp || u.status == MajorStatus.SLEEP) return@status false
            if (u.ability in setOf("insomnia", "vitalspirit", "comatose", "purifyingsalt") && b.abilityActive(u)) return@status false
            if (b.field.terrain == "electricterrain" && b.isGrounded(u)) return@status false
            u.mon.status = null
            if (!b.trySetStatus(u, MajorStatus.SLEEP, u, "rest", primary = true)) return@status false
            b.heal(u, u.maxHp, "rest"); true
        }
        status("painsplit") { b, mc ->
            val avg = (mc.user.hp + mc.target.hp) / 2
            fun set(m: BattleMon) { val d = avg - m.hp; m.hp = min(avg, m.maxHp); b.events.add(BattleEvent.HpChange(m.side.index, b.teamIndex(m), m.hp, m.maxHp, d, "painsplit")) }
            set(mc.user); set(mc.target)
            b.message("The battlers shared their pain!"); true
        }
        status("bellydrum") { b, mc ->
            val u = mc.user
            if (u.hp <= u.maxHp / 2 || u.boost("atk") >= 6) return@status false
            b.directDamage(u, u.maxHp / 2, u, "bellydrum")
            u.boosts[0] = 6
            b.message("${b.nm(u)} cut its own HP and maximized its Attack!"); true
        }
        status("filletaway") { b, mc ->
            val u = mc.user
            if (u.hp <= u.maxHp / 2) return@status false
            b.directDamage(u, u.maxHp / 2, u, "filletaway")
            b.boost(u, mapOf("atk" to 2, "spa" to 2, "spe" to 2), u, "filletaway"); true
        }
        status("clangoroussoul") { b, mc ->
            val u = mc.user
            if (u.hp <= u.maxHp / 3) return@status false
            b.directDamage(u, u.maxHp / 3, u, "clangoroussoul")
            b.boost(u, mapOf("atk" to 1, "def" to 1, "spa" to 1, "spd" to 1, "spe" to 1), u, "clangoroussoul"); true
        }
        status("curse") { b, mc ->
            val u = mc.user
            if (u.hasType("Ghost")) {
                val t = u.side.foe.active ?: return@status false
                if (t.hasVolatile("curse")) return@status false
                b.directDamage(u, max(1, u.baseMaxHp / 2), u, "curse")
                b.addVolatile(t, "curse", u, "curse") != null
            } else b.boost(u, mapOf("spe" to -1, "atk" to 1, "def" to 1), u, "curse")
        }
        status("swallow") { b, mc ->
            val n = mc.user.volatiles["stockpile"]?.counter ?: return@status false
            val frac = when (n) { 1 -> 0.25; 2 -> 0.5; else -> 1.0 }
            b.heal(mc.user, (mc.user.baseMaxHp * frac).toInt(), "swallow")
            b.removeVolatile(mc.user, "stockpile"); true
        }
        afterHit("spitup") { b, mc -> b.removeVolatile(mc.user, "stockpile") }
        failUnless("spitup") { _, mc -> mc.user.hasVolatile("stockpile") }
        status("haze") { b, _ -> for (m in b.activeMons()) m.boosts.fill(0); b.message("All stat changes were eliminated!"); true }
        afterHitTarget("clearsmog") { b, mc -> mc.target.boosts.fill(0); b.message("${b.nm(mc.target)}'s stat changes were removed!") }
        status("psychup") { b, mc -> val t = mc.user.side.foe.active ?: return@status false; t.boosts.copyInto(mc.user.boosts); b.message("${b.nm(mc.user)} copied ${b.nmL(t)}'s stat changes!"); true }
        status("topsyturvy") { b, mc -> if (mc.target.boosts.all { it == 0 }) return@status false; for (i in mc.target.boosts.indices) mc.target.boosts[i] = -mc.target.boosts[i]; b.message("${b.nm(mc.target)}'s stat changes were all reversed!"); true }
        status("strengthsap") { b, mc ->
            val t = mc.target
            val atk = b.applyBoost(t.rawStat(Stat.ATK), t.boost("atk"))
            if (t.boost("atk") <= -6) return@status false
            b.boost(t, mapOf("atk" to -1), mc.user, "strengthsap")
            b.heal(mc.user, atk, "strengthsap"); true
        }
        status("junglehealing") { b, mc -> b.heal(mc.user, mc.user.baseMaxHp / 4, "junglehealing"); b.cureStatus(mc.user); true }
        status("lifedew") { b, mc -> b.heal(mc.user, mc.user.baseMaxHp / 4, "lifedew") > 0 }
        status("purify") { b, mc -> if (mc.target.status == null) false else { b.cureStatus(mc.target); b.heal(mc.user, mc.user.baseMaxHp / 2, "purify"); true } }
        status("refresh") { b, mc -> if (mc.user.status == null || mc.user.status == MajorStatus.SLEEP || mc.user.status == MajorStatus.FREEZE) false else { b.cureStatus(mc.user); true } }
        for (id in listOf("healbell", "aromatherapy")) status(id) { b, mc ->
            var did = false
            for (m in mc.user.side.mons) if (m.mon.status != null && !m.fainted) { m.mon.status = null; m.mon.sleepTurns = 0; did = true }
            b.message(if (id == "healbell") "A bell chimed!" else "A soothing aroma wafted through the area!")
            b.events.add(BattleEvent.StatusChange(mc.user.side.index, b.teamIndex(mc.user), null, ""))
            true
        }
        status("memento") { b, mc ->
            b.boost(mc.target, mapOf("atk" to -2, "spa" to -2), mc.user, "memento")
            b.directDamage(mc.user, mc.user.hp, mc.user, "memento", silent = true); true
        }
        for (id in listOf("healingwish", "lunardance")) status(id) { b, mc ->
            if (mc.user.side.canSwitchTo().isEmpty()) return@status false
            b.addSlotCondition(mc.user.side, id, mc.user)
            b.directDamage(mc.user, mc.user.hp, mc.user, id, silent = true); true
        }
        status("wish") { b, mc -> b.addSlotCondition(mc.user.side, "wish", mc.user) }
        reg(object : MoveScript("futuresight") {
            override fun onUseStatus(b: Battle, mc: MoveCtx): Boolean? = null
            override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                val side = mc.target.side
                if ("futuremove" in side.slotConditions) return false
                b.addSlotCondition(side, "futuremove", mc.user)
                side.slotConditions["futuremove"]?.moveId = mc.move.id
                b.message("${b.nm(mc.user)} foresaw an attack!")
                mc.move.data2["silentFail"] = true
                return false
            }
        })
        Effects.move("futuresight")?.let { fs -> reg(object : MoveScript("doomdesire") { override fun onTry(b: Battle, mc: MoveCtx) = fs.onTry(b, mc) }) }
        status("perishsong") { b, mc ->
            var any = false
            for (m in b.activeMons()) if (!m.hasVolatile("perishsong") && !(m.ability == "soundproof" && b.abilityActive(m) && m !== mc.user)) { b.addVolatile(m, "perishsong", mc.user, "perishsong"); any = true }
            if (any) b.message("All Pokémon that heard the song will faint in three turns!")
            any
        }
        status("destinybond") { b, mc -> if (mc.user.lastMove == "destinybond") false else b.addVolatile(mc.user, "destinybond", mc.user, "destinybond") != null }
        status("transform") { b, mc -> transform(b, mc.user, mc.target); mc.user.transformed }
        status("mimic") { b, mc ->
            val last = mc.target.lastMove ?: return@status false
            if (last in setOf("mimic", "transform", "sketch", "struggle", "metronome") || mc.user.moveSlot(last) != null) return@status false
            val idx = mc.user.moveSlots.indexOfFirst { it.id == "mimic" }
            if (idx < 0) return@status false
            if (mc.user.moveSlots === mc.user.mon.moves) mc.user.moveSlots = mc.user.mon.moves.map { it.copy() }.toMutableList()
            mc.user.moveSlots[idx] = MoveSlot(last, b.dex.move(last).pp)
            b.message("${b.nm(mc.user)} learned ${b.dex.move(last).name}!"); true
        }
        status("sketch") { b, mc ->
            val last = mc.target.lastMove ?: return@status false
            if (last in setOf("sketch", "struggle", "chatter") || mc.user.mon.moves.any { it.id == last }) return@status false
            val idx = mc.user.mon.moves.indexOfFirst { it.id == "sketch" }
            if (idx < 0) return@status false
            mc.user.mon.moves[idx] = MoveSlot(last, b.dex.move(last).pp)
            b.message("${b.nm(mc.user)} sketched ${b.dex.move(last).name}!"); true
        }
        status("spite") { b, mc ->
            val last = mc.target.lastMove ?: return@status false
            val slot = mc.target.moveSlot(last) ?: return@status false
            if (slot.pp <= 0) return@status false
            val red = min(4, slot.pp); slot.pp -= red
            b.message("It reduced the PP of ${b.nmL(mc.target)}'s ${b.dex.move(last).name} by $red!"); true
        }
        for ((id, stats) in listOf("powersplit" to listOf(Stat.ATK, Stat.SPA), "guardsplit" to listOf(Stat.DEF, Stat.SPD))) status(id) { b, mc ->
            for (s in stats) {
                val avg = (mc.user.rawStat(s) + mc.target.rawStat(s)) / 2
                mc.user.volatiles["split_${s.id}"] = EffectState().also { it.counter = avg }
                mc.target.volatiles["split_${s.id}"] = EffectState().also { it.counter = avg }
            }
            b.message("${b.nm(mc.user)} shared its ${if (id == "powersplit") "power" else "guard"} with the target!"); true
        }
        for ((id, idxs) in listOf("powerswap" to listOf(0, 2), "guardswap" to listOf(1, 3), "heartswap" to listOf(0, 1, 2, 3, 4, 5, 6))) status(id) { b, mc ->
            for (i in idxs) { val t = mc.user.boosts[i]; mc.user.boosts[i] = mc.target.boosts[i]; mc.target.boosts[i] = t }
            b.message("${b.nm(mc.user)} switched stat changes with its target!"); true
        }
        status("speedswap") { b, mc -> b.message("${b.nm(mc.user)} switched Speed with its target!"); true }

        // ------------------------------------------------------------ ability / type changes
        status("skillswap") { b, mc ->
            val a = mc.user.ability; val t = mc.target.ability
            val banned = setOf("wonderguard", "multitype", "illusion", "stancechange", "schooling", "comatose", "shieldsdown", "disguise", "rkssystem", "battlebond", "powerconstruct", "iceface", "gulpmissile", "neutralizinggas", "hungerswitch", "zerotohero", "commander", "protosynthesis", "quarkdrive", "terashift", "poisonpuppeteer", "teraformzero", "terashell")
            if (a in banned || t in banned || !b.canChangeAbility(mc.user) || !b.canChangeAbility(mc.target) || mc.target.isDynamaxed) return@status false
            b.message("${b.nm(mc.user)} swapped Abilities with its target!")
            Abilities.setAbility(b, mc.user, t); Abilities.setAbility(b, mc.target, a); true
        }
        status("roleplay") { b, mc ->
            val a = mc.target.ability
            if (a == mc.user.ability || a in MoveScriptsExtra.NO_COPY_ABILITIES || !b.canChangeAbility(mc.user)) return@status false
            b.message("${b.nm(mc.user)} copied ${b.nmL(mc.target)}'s ${b.dex.abilityOrNull(a)?.name}!")
            Abilities.setAbility(b, mc.user, a); true
        }
        status("entrainment") { b, mc ->
            val a = mc.user.ability
            if (a == mc.target.ability || a in MoveScriptsExtra.NO_COPY_ABILITIES || mc.target.ability == "truant" || !b.canChangeAbility(mc.target) || mc.target.isDynamaxed) return@status false
            b.message("${b.nm(mc.target)} acquired ${b.dex.abilityOrNull(a)?.name}!")
            Abilities.setAbility(b, mc.target, a); true
        }
        status("simplebeam") { b, mc ->
            if (mc.target.ability == "simple" || mc.target.ability == "truant" || !b.canChangeAbility(mc.target) || mc.target.isDynamaxed) false
            else { b.message("${b.nm(mc.target)} acquired Simple!"); Abilities.setAbility(b, mc.target, "simple"); true }
        }
        status("worryseed") { b, mc ->
            if (mc.target.ability in setOf("insomnia", "truant") || !b.canChangeAbility(mc.target) || mc.target.isDynamaxed) false
            else { b.message("${b.nm(mc.target)} acquired Insomnia!"); Abilities.setAbility(b, mc.target, "insomnia"); if (mc.target.status == MajorStatus.SLEEP) b.cureStatus(mc.target); true }
        }
        status("soak") { b, mc -> if (mc.target.types == listOf("Water") || mc.target.terastallized != null) false else { mc.target.types = listOf("Water"); b.message("${b.nm(mc.target)} transformed into the Water type!"); true } }
        status("magicpowder") { b, mc -> if (mc.target.types == listOf("Psychic") || mc.target.terastallized != null) false else { mc.target.types = listOf("Psychic"); b.message("${b.nm(mc.target)} transformed into the Psychic type!"); true } }
        status("trickortreat") { b, mc -> if (mc.target.hasType("Ghost")) false else { mc.target.addedType = "Ghost"; b.message("Ghost type was added to ${b.nmL(mc.target)}!"); true } }
        status("forestscurse") { b, mc -> if (mc.target.hasType("Grass")) false else { mc.target.addedType = "Grass"; b.message("Grass type was added to ${b.nmL(mc.target)}!"); true } }
        status("reflecttype") { b, mc -> mc.user.types = mc.target.types; b.message("${b.nm(mc.user)}'s type became the same as ${b.nmL(mc.target)}'s type!"); true }
        status("conversion") { b, mc ->
            val t = b.dex.moveOrNull(mc.user.moveSlots.firstOrNull()?.id)?.type ?: return@status false
            if (mc.user.types == listOf(t)) return@status false
            mc.user.types = listOf(t); b.message("${b.nm(mc.user)}'s type changed to $t!"); true
        }
        status("camouflage") { b, mc ->
            val t = when (b.field.terrain) { "electricterrain" -> "Electric"; "grassyterrain" -> "Grass"; "mistyterrain" -> "Fairy"; "psychicterrain" -> "Psychic"; else -> when (b.environment) { "cave" -> "Rock"; "water" -> "Water"; "grass" -> "Grass"; "sand" -> "Ground"; "snow" -> "Ice"; else -> "Normal" } }
            mc.user.types = listOf(t); b.message("${b.nm(mc.user)}'s type changed to $t!"); true
        }

        // ------------------------------------------------------------ hazards / screens removal
        afterHit("rapidspin") { b, mc -> clearOwnHazards(b, mc.user); mc.user.volatiles.remove("leechseed"); mc.user.volatiles.remove("partiallytrapped") }
        afterHit("mortalspin") { b, mc -> clearOwnHazards(b, mc.user); mc.user.volatiles.remove("leechseed"); mc.user.volatiles.remove("partiallytrapped") }
        status("defog") { b, mc ->
            b.boost(mc.target, mapOf("evasion" to -1), mc.user, "defog", secondary = true)
            for (s in b.sides) for (id in listOf("spikes", "toxicspikes", "stealthrock", "stickyweb", "gmaxsteelsurge")) b.removeSideCondition(s, id)
            for (id in listOf("reflect", "lightscreen", "auroraveil", "safeguard", "mist")) b.removeSideCondition(mc.target.side, id)
            b.clearTerrain(); true
        }
        status("tidyup") { b, mc ->
            for (s in b.sides) for (id in listOf("spikes", "toxicspikes", "stealthrock", "stickyweb", "gmaxsteelsurge")) b.removeSideCondition(s, id)
            for (m in b.activeMons()) if (m.hasVolatile("substitute")) b.removeVolatile(m, "substitute")
            b.message("Tidying up complete!")
            b.boost(mc.user, mapOf("atk" to 1, "spe" to 1), mc.user, "tidyup"); true
        }
        status("courtchange") { b, _ ->
            val a = b.sides[0].conditions.toMap(); val c = b.sides[1].conditions.toMap()
            b.sides[0].conditions.clear(); b.sides[0].conditions.putAll(c)
            b.sides[1].conditions.clear(); b.sides[1].conditions.putAll(a)
            b.message("Swapped the effects on each side of the field!"); true
        }
        for (id in listOf("brickbreak", "psychicfangs", "ragingbull")) reg(object : MoveScript(id) {
            override fun onTry(b: Battle, mc: MoveCtx): Boolean {
                for (s in listOf("reflect", "lightscreen", "auroraveil")) if (b.removeSideCondition(mc.target.side, s)) b.message("The wall shattered!")
                return true
            }
        })

        // ------------------------------------------------------------ move-calling moves
        status("sleeptalk") { b, mc ->
            if (mc.user.status != MajorStatus.SLEEP && mc.user.ability != "comatose") return@status false
            val options = mc.user.moveSlots.map { it.id }.filter { it != "sleeptalk" && b.dex.moveOrNull(it)?.let { m -> !m.hasFlag("nosleeptalk") && !m.hasFlag("charge") } == true }
            if (options.isEmpty()) return@status false
            callMove(b, mc.user, options[b.rng.nextInt(options.size)]); true
        }
        status("metronome") { b, mc ->
            val pool = b.dex.moves.values.filter { it.hasFlag("metronome") && it.isNonstandard == null && it.isZ == null && it.isMax == null }
            callMove(b, mc.user, pool[b.rng.nextInt(pool.size)].id); true
        }
        status("copycat") { b, mc -> val last = b.lastMoveUsed?.takeIf { it != "copycat" && b.dex.moveOrNull(it)?.hasFlag("failcopycat") != true } ?: return@status false; callMove(b, mc.user, last); true }
        status("mirrormove") { b, mc -> val last = mc.target.lastMove?.takeIf { b.dex.moveOrNull(it)?.hasFlag("mirror") == true } ?: return@status false; callMove(b, mc.user, last); true }
        status("assist") { b, mc ->
            val pool = mc.user.side.mons.filter { it !== mc.user }.flatMap { m -> m.mon.moves.map { it.id } }.filter { b.dex.moveOrNull(it)?.hasFlag("noassist") != true }
            if (pool.isEmpty()) return@status false
            callMove(b, mc.user, pool[b.rng.nextInt(pool.size)]); true
        }
        status("naturepower") { b, mc ->
            val id = when (b.field.terrain) { "electricterrain" -> "thunderbolt"; "grassyterrain" -> "energyball"; "mistyterrain" -> "moonblast"; "psychicterrain" -> "psychic"; else -> when (b.environment) { "cave" -> "powergem"; "water" -> "hydropump"; "grass" -> "energyball"; "sand" -> "earthpower"; "snow" -> "icebeam"; else -> "triattack" } }
            callMove(b, mc.user, id); true
        }
        status("mefirst") { b, mc -> val id = pendingMoveId(b, mc.target) ?: return@status false; if (b.dex.move(id).isStatus || mc.target.movedThisTurn) return@status false; callMove(b, mc.user, id); true }
        status("lockon") { b, mc -> b.addVolatile(mc.user, "lockon", mc.user, "lockon"); b.message("${b.nm(mc.user)} took aim at ${b.nmL(mc.target)}!"); true }
        Effects.move("lockon")?.let { lo -> reg(object : MoveScript("mindreader") { override fun onUseStatus(b: Battle, mc: MoveCtx) = lo.onUseStatus(b, mc) }) }
        status("teleport") { b, mc ->
            if (mc.user.side.isWild) { b.message("${b.nm(mc.user)} fled from battle!"); b.finish(BattleOutcome.FOE_FLED); return@status true }
            if (mc.user.side.isPlayer && b.opponent.isWild && mc.user.side.canSwitchTo().isEmpty()) { b.message("${b.nm(mc.user)} teleported away!"); b.finish(BattleOutcome.RAN); return@status true }
            if (mc.user.side.canSwitchTo().isEmpty()) return@status false
            mc.user.switchFlag = "true"; true
        }
        status("revivalblessing") { b, mc ->
            val fainted = mc.user.side.mons.filter { it.fainted }
            if (fainted.isEmpty()) return@status false
            val r = fainted.first()
            r.mon.fainted = false
            r.hp = r.baseMaxHp / 2
            b.message("${r.name} was revived and is ready to fight again!"); true
        }
        status("celebrate") { b, _ -> b.message("Congratulations!"); true }
        status("splash") { b, _ -> b.message("But nothing happened!"); true }
        status("holdhands") { b, _ -> true }
        status("happyhour") { b, _ -> b.message("Everyone is caught up in the happy atmosphere!"); true }
        status("auroraveil") { b, mc -> if (!b.isSnow()) false else b.addSideCondition(mc.user.side, "auroraveil", mc.user) }
        afterHit("payday") { b, mc -> b.payDay += 5 * mc.user.level; b.message("Coins were scattered everywhere!") }
        afterHit("makeitrain") { b, mc -> b.payDay += 5 * mc.user.level; b.message("Coins were scattered everywhere!") }
        afterHit("genesissupernova") { b, mc -> b.setTerrain("psychicterrain", mc.user) }
        afterHit("splinteredstormshards") { b, _ -> b.clearTerrain() }
        afterHit("icespinner") { b, _ -> b.clearTerrain() }
        afterHitTarget("sparklingaria") { b, mc -> if (mc.target.status == MajorStatus.BURN) b.cureStatus(mc.target) }
        afterHit("uproar") { b, mc -> if (!mc.user.hasVolatile("uproar")) b.addVolatile(mc.user, "uproar", mc.user, "uproar") }
        afterHitTarget("thousandwaves") { b, mc -> b.addVolatile(mc.target, "trapped", mc.user, "thousandwaves") }
        afterHitTarget("anchorshot") { b, mc -> b.addVolatile(mc.target, "trapped", mc.user, "anchorshot") }
        afterHitTarget("spiritshackle") { b, mc -> b.addVolatile(mc.target, "trapped", mc.user, "spiritshackle") }
        for (id in listOf("meanlook", "block", "spiderweb")) status(id) { b, mc -> b.addVolatile(mc.target, "trapped", mc.user, id)?.let { b.message("${b.nm(mc.target)} can no longer escape!"); true } ?: false }

        // ------------------------------------------------------------ Max / G-Max move effects
        maxWeather("maxflare", "sunnyday"); maxWeather("maxgeyser", "raindance"); maxWeather("maxrockfall", "sandstorm"); maxWeather("maxhailstorm", "snowscape")
        maxTerrain("maxlightning", "electricterrain"); maxTerrain("maxovergrowth", "grassyterrain"); maxTerrain("maxstarfall", "mistyterrain"); maxTerrain("maxmindstorm", "psychicterrain")
        maxSelfBoost("maxknuckle", "atk"); maxSelfBoost("maxairstream", "spe"); maxSelfBoost("maxooze", "spa"); maxSelfBoost("maxquake", "spd"); maxSelfBoost("maxsteelspike", "def")
        maxFoeDrop("maxstrike", "spe"); maxFoeDrop("maxflutterby", "spa"); maxFoeDrop("maxphantasm", "def"); maxFoeDrop("maxwyrmwind", "atk"); maxFoeDrop("maxdarkness", "spd")
        for (id in listOf("gmaxvinelash", "gmaxwildfire", "gmaxcannonade", "gmaxvolcalith", "gmaxsteelsurge")) afterHit(id) { b, mc -> b.addSideCondition(mc.target.side, id, mc.user) }
        afterHit("gmaxstonesurge") { b, mc -> b.addSideCondition(mc.target.side, "stealthrock", mc.user) }
        afterHit("gmaxvoltcrash") { b, mc -> b.trySetStatus(mc.target, MajorStatus.PARALYSIS, mc.user, "gmaxvoltcrash", false) }
        afterHit("gmaxstunshock") { b, mc -> b.trySetStatus(mc.target, if (b.rng.nextBoolean()) MajorStatus.PARALYSIS else MajorStatus.POISON, mc.user, "gmaxstunshock", false) }
        afterHit("gmaxbefuddle") { b, mc -> b.trySetStatus(mc.target, listOf(MajorStatus.SLEEP, MajorStatus.PARALYSIS, MajorStatus.POISON)[b.rng.nextInt(3)], mc.user, "gmaxbefuddle", false) }
        afterHit("gmaxmalodor") { b, mc -> b.trySetStatus(mc.target, MajorStatus.POISON, mc.user, "gmaxmalodor", false) }
        afterHit("gmaxsmite") { b, mc -> b.addVolatile(mc.target, "confusion", mc.user, "gmaxsmite") }
        afterHit("gmaxgoldrush") { b, mc -> b.addVolatile(mc.target, "confusion", mc.user, "gmaxgoldrush"); b.payDay += 100 * mc.user.level }
        afterHit("gmaxcuddle") { b, mc -> b.addVolatile(mc.target, "attract", mc.user, "gmaxcuddle") }
        afterHit("gmaxterror") { b, mc -> b.addVolatile(mc.target, "trapped", mc.user, "gmaxterror") }
        for (id in listOf("gmaxcentiferno", "gmaxsandblast")) afterHit(id) { b, mc -> b.addVolatile(mc.target, "partiallytrapped", mc.user, id)?.moveId = id }
        afterHit("gmaxfoamburst") { b, mc -> b.boost(mc.target, mapOf("spe" to -2), mc.user, "gmaxfoamburst", secondary = true) }
        afterHit("gmaxtartness") { b, mc -> b.boost(mc.target, mapOf("evasion" to -1), mc.user, "gmaxtartness", secondary = true) }
        afterHit("gmaxresonance") { b, mc -> b.addSideCondition(mc.user.side, "auroraveil", mc.user) }
        afterHit("gmaxgravitas") { b, mc -> b.addPseudoWeather("gravity", mc.user) }
        afterHit("gmaxsweetness") { b, mc -> for (m in mc.user.side.mons) { m.mon.status = null } }
        afterHit("gmaxfinale") { b, mc -> b.heal(mc.user, mc.user.maxHp / 6, "gmaxfinale") }
        afterHit("gmaxreplenish") { b, mc -> if (mc.user.lastItem != null && mc.user.item == null && b.rng.nextBoolean()) { mc.user.item = mc.user.lastItem; mc.user.lastItem = null } }
        afterHit("gmaxwindrage") { b, mc -> for (id in listOf("reflect", "lightscreen", "auroraveil", "safeguard", "mist", "spikes", "toxicspikes", "stealthrock", "stickyweb")) b.removeSideCondition(mc.target.side, id); b.clearTerrain() }
        afterHit("gmaxmeltdown") { b, mc -> b.addVolatile(mc.target, "torment", mc.user, "gmaxmeltdown") }
        afterHit("gmaxdepletion") { b, mc -> mc.target.lastMove?.let { mc.target.moveSlot(it)?.let { s -> s.pp = max(0, s.pp - 2) } } }
        afterHit("gmaxsnooze") { b, mc -> if (b.rng.nextBoolean()) b.addVolatile(mc.target, "yawn", mc.user, "gmaxsnooze") }
        afterHit("gmaxchistrike") { b, mc -> b.addVolatile(mc.user, "focusenergy", mc.user, "gmaxchistrike") }
        for (id in listOf("gmaxdrumsolo", "gmaxfireball", "gmaxhydrosnipe")) reg(object : MoveScript(id) {
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { mc.move.ignoreAbility = true }
        })
        MoveScriptsExtra.registerAll()
    }

    private fun maxWeather(id: String, w: String) = afterHit(id) { b, mc -> if (mc.user.isDynamaxed) b.setWeather(w, mc.user) }
    private fun maxTerrain(id: String, t: String) = afterHit(id) { b, mc -> if (mc.user.isDynamaxed) b.setTerrain(t, mc.user) }
    private fun maxSelfBoost(id: String, stat: String) = afterHit(id) { b, mc -> if (mc.user.isDynamaxed && !mc.user.fainted) b.boost(mc.user, mapOf(stat to 1), mc.user, id) }
    private fun maxFoeDrop(id: String, stat: String) = afterHit(id) { b, mc -> if (mc.user.isDynamaxed && !mc.target.fainted) b.boost(mc.target, mapOf(stat to -1), mc.user, id, secondary = true) }

    private fun clearOwnHazards(b: Battle, user: BattleMon) {
        for (id in listOf("spikes", "toxicspikes", "stealthrock", "stickyweb", "gmaxsteelsurge")) if (b.removeSideCondition(user.side, id)) b.message("${b.nm(user)} blew away the hazards!")
    }

    /** Calls another move as part of this one (Metronome, Sleep Talk...). */
    fun callMove(b: Battle, user: BattleMon, moveId: String) {
        val m = b.dex.moveOrNull(moveId) ?: return
        val am = ActiveMove(m)
        b.useMove(MoveCtx(user, b.defaultTarget(user, am), am), calledBy = "call")
    }

    /** Whether the target has chosen an attacking move this turn (used by Sucker Punch). Approximated from its last move. */
    private fun pendingMoveIsAttack(b: Battle, target: BattleMon): Boolean {
        val id = pendingMoveId(b, target) ?: return false
        return b.dex.moveOrNull(id)?.isStatus == false
    }

    fun pendingMoveId(b: Battle, target: BattleMon): String? = target.volatiles["pendingmove"]?.moveId

}
