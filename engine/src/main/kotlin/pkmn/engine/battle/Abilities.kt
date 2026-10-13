package pkmn.engine.battle

import pkmn.engine.data.Stat
import pkmn.engine.mon.MajorStatus
import kotlin.math.max

/**
 * Ability implementations. Each ability is an [Effect] attached to its holder; hooks check the holder's role.
 * Abilities not registered here have no battle effect yet and are reported by the coverage tool.
 */
object Abilities {
    private fun reg(e: Effect) = Effects.registerAbility(e)

    private fun announce(c: EffectCtx) = c.b.message("[${c.b.nm(c.mon)}'s ${c.b.dex.abilityOrNull(c.effect.id)?.name ?: c.effect.id}]")

    /** Base class for abilities. */
    open class A(id: String) : Effect(id, EffectKind.ABILITY)

    // ---------------------------------------------------------------- builders

    private fun typeBoostPinch(id: String, type: String) = reg(object : A(id) {
        override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.type == type && c.mon.hp <= c.mon.maxHp / 3) mod.chain(1.5) }
        override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.type == type && c.mon.hp <= c.mon.maxHp / 3) mod.chain(1.5) }
    })

    private fun absorb(id: String, type: String, onAbsorb: (EffectCtx, MoveCtx) -> Unit) = reg(object : A(id) {
        override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
            if (!c.isTarget(mc) || mc.user === mc.target || mc.move.type != type) return true
            if (mc.move.target in setOf("all", "foeSide", "allySide")) return true
            announce(c)
            onAbsorb(c, mc)
            return false
        }
    })

    private fun healAbsorb(id: String, type: String) = absorb(id, type) { c, _ ->
        if (c.b.heal(c.mon, max(1, c.mon.baseMaxHp / 4), id) == 0) c.b.message("It doesn't affect ${c.b.nmL(c.mon)}...")
        else c.b.message("${c.b.nm(c.mon)} restored HP.")
    }

    private fun boostAbsorb(id: String, type: String, stat: String) = absorb(id, type) { c, _ ->
        if (!c.b.boost(c.mon, mapOf(stat to 1), c.mon, id)) c.b.message("It doesn't affect ${c.b.nmL(c.mon)}...")
    }

    private fun statusImmunity(id: String, vararg statuses: MajorStatus, volatiles: Set<String> = emptySet()) = reg(object : A(id) {
        override fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?): Boolean {
            if (target !== c.holder || status !in statuses) return true
            if (sourceEffect != null && source !== target) { announce(c); c.b.message("${c.b.nm(target)} is unaffected!") }
            return false
        }
        override fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?) = !(target === c.holder && volatile in volatiles)
        override fun onUpdate(c: EffectCtx) {
            if (c.mon.status in statuses) { announce(c); c.b.cureStatus(c.mon) }
            for (v in volatiles) if (c.mon.hasVolatile(v)) c.b.removeVolatile(c.mon, v)
        }
    })

    private fun weatherSetter(id: String, weather: String) = reg(object : A(id) {
        override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.setWeather(weather, c.mon, abilityTriggered = true) }
    })

    private fun terrainSetter(id: String, terrain: String) = reg(object : A(id) {
        override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.setTerrain(terrain, c.mon) }
    })

    private fun weatherSpeed(id: String, test: (Battle, BattleMon) -> Boolean) = reg(object : A(id) {
        override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon === c.holder && test(c.b, mon)) mod.chain(2.0) }
    })

    private fun contactStatus(id: String, chance: Int, pick: (Battle) -> MajorStatus) = reg(object : A(id) {
        override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
            if (!c.isTarget(mc) || !mc.move.hasFlag("contact") || mc.user === c.holder) return
            if (mc.user.item == "protectivepads" && c.b.itemActive(mc.user)) return
            if (c.b.rng.nextInt(100) < chance) c.b.trySetStatus(mc.user, pick(c.b), c.mon, id, primary = false)
        }
    })

    private fun intimidateLike(id: String, stat: String) = reg(object : A(id) {
        override fun onStart(c: EffectCtx, source: BattleMon?) {
            val foe = c.mon.side.foe.active ?: return
            if (foe.fainted) return
            announce(c)
            if (foe.hasVolatile("substitute")) { c.b.message("${c.b.nm(foe)} wasn't affected!"); return }
            if (foe.ability in setOf("innerfocus", "oblivious", "owntempo", "scrappy") && c.b.abilityActive(foe) && id == "intimidate") {
                c.b.message("[${c.b.nm(foe)}'s ${c.b.dex.abilityOrNull(foe.ability)?.name}]"); c.b.message("${c.b.nm(foe)}'s Attack was not lowered!"); return
            }
            c.b.boost(foe, mapOf(stat to -1), c.mon, id, secondary = true)
            if (id == "intimidate") {
                if (foe.ability == "rattled" && c.b.abilityActive(foe)) c.b.boost(foe, mapOf("spe" to 1), foe, "rattled")
                if (foe.item == "adrenalineorb" && c.b.itemActive(foe)) { c.b.boost(foe, mapOf("spe" to 1), foe, "adrenalineorb"); c.b.consumeItem(foe) }
            }
        }
    })

    private fun statDoubler(id: String, stat: Stat, mult: Double = 2.0) = reg(object : A(id) {
        override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (stat == Stat.ATK && c.isUser(mc)) mod.chain(mult) }
        override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (stat == Stat.SPA && c.isUser(mc)) mod.chain(mult) }
    })

    private fun moveFlagBoost(id: String, mult: Double, pred: (MoveCtx) -> Boolean) = reg(object : A(id) {
        override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && pred(mc)) mod.chain(mult) }
    })

    private fun ateAbility(id: String, type: String) = reg(object : A(id) {
        override fun onModifyType(c: EffectCtx, mc: MoveCtx) {
            if (c.isUser(mc) && mc.move.type == "Normal" && !mc.move.isZ && !mc.move.isMax && mc.move.id !in setOf("judgment", "multiattack", "naturalgift", "revelationdance", "technoblast", "terrainpulse", "weatherball", "terablast", "struggle")) {
                mc.move.type = type; mc.move.data2["ate"] = true
            }
        }
        override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.data2["ate"] == true) mod.chain(1.2) }
    })

    private fun defenderHalve(id: String, pred: (MoveCtx) -> Boolean, mult: Double = 0.5) = reg(object : A(id) {
        override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && pred(mc)) mod.chain(mult) }
    })

    private fun boostBlocker(id: String, stats: Set<String>?) = reg(object : A(id) {
        override fun onTryBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>, source: BattleMon?, sourceEffect: String?) {
            if (target !== c.holder || source == null || source === target) return
            var blocked = false
            for (k in boosts.keys.toList()) if (boosts[k]!! < 0 && (stats == null || k in stats)) { boosts.remove(k); blocked = true }
            if (blocked && sourceEffect != null && c.b.dex.moveOrNull(sourceEffect)?.secondaries?.isNotEmpty() != true) {
                announce(c); c.b.message("${c.b.nm(target)}'s stats were not lowered!")
            }
        }
    })

    private fun residualHeal(id: String, frac: Int, cond: (Battle, BattleMon) -> Boolean) = reg(object : A(id) {
        override val residualOrder = 5
        override fun onResidual(c: EffectCtx) {
            if (cond(c.b, c.mon) && c.b.heal(c.mon, max(1, c.mon.baseMaxHp / frac), id) > 0) { announce(c); c.b.message("${c.b.nm(c.mon)} restored HP.") }
        }
    })

    private fun onKoBoost(id: String, stat: (BattleMon) -> String) = reg(object : A(id) {
        override fun onSourceFaint(c: EffectCtx, target: BattleMon, move: ActiveMove?) {
            if (c.mon.fainted) return
            announce(c); c.b.boost(c.mon, mapOf(stat(c.mon) to 1), c.mon, id)
        }
    })

    private fun bestStat(m: BattleMon): String {
        val stats = listOf("atk", "def", "spa", "spd", "spe")
        return stats.maxByOrNull { m.rawStat(Stat.byId(it)!!) }!!
    }

    /** Replaces [mon]'s Ability (Skill Swap, Role Play, Entrainment...) and runs the new Ability's start effect. */
    fun setAbility(b: Battle, mon: BattleMon, id: String) {
        Effects.ability(mon.ability)?.let { old -> if (b.abilityActive(mon)) old.onEnd(EffectCtx(b, old, mon, null, mon.abilityState)) }
        mon.ability = id
        mon.abilityState = EffectState()
        if (b.abilityActive(mon)) Effects.ability(id)?.let { it.onStart(EffectCtx(b, it, mon, null, mon.abilityState), null) }
    }

    fun registerAll() {
        // ------------------------------------------------ weather / terrain setters
        weatherSetter("drizzle", "raindance")
        weatherSetter("drought", "sunnyday")
        weatherSetter("sandstream", "sandstorm")
        weatherSetter("snowwarning", "snowscape")
        weatherSetter("orichalcumpulse", "sunnyday")
        terrainSetter("electricsurge", "electricterrain")
        terrainSetter("grassysurge", "grassyterrain")
        terrainSetter("mistysurge", "mistyterrain")
        terrainSetter("psychicsurge", "psychicterrain")
        terrainSetter("hadronengine", "electricterrain")
        for ((id, w) in listOf("desolateland" to "desolateland", "primordialsea" to "primordialsea", "deltastream" to "deltastream")) reg(object : A(id) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.setWeather(w, c.mon, true) }
            override fun onSwitchOut(c: EffectCtx) { endStrong(c, w) }
            override fun onFaint(c: EffectCtx, source: BattleMon?) { endStrong(c, w) }
        })
        reg(object : A("airlock") { override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("The effects of the weather disappeared.") } })
        reg(object : A("cloudnine") { override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("The effects of the weather disappeared.") } })

        // ------------------------------------------------ speed
        weatherSpeed("swiftswim") { b, m -> b.isRain(m) }
        weatherSpeed("chlorophyll") { b, m -> b.isSun(m) }
        weatherSpeed("sandrush") { b, _ -> b.isSand() }
        weatherSpeed("slushrush") { b, _ -> b.isSnow() }
        weatherSpeed("surgesurfer") { b, _ -> b.field.terrain == "electricterrain" }
        reg(object : A("quickfeet") { override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon === c.holder && mon.status != null) mod.chain(1.5) } })
        reg(object : A("unburden") { override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon === c.holder && mon.usedItemThisBattle && mon.item == null) mod.chain(2.0) } })
        reg(object : A("slowstart") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("${c.b.nm(c.mon)} can't get it going!"); c.state.counter = 5 }
            override fun onResidual(c: EffectCtx) { if (c.state.counter > 0) { c.state.counter--; if (c.state.counter == 0) c.b.message("${c.b.nm(c.mon)} finally got its act together!") } }
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.state.counter > 0) mod.chain(0.5) }
            override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon === c.holder && c.state.counter > 0) mod.chain(0.5) }
        })
        reg(object : A("speedboost") {
            override val residualOrder = 26
            override fun onResidual(c: EffectCtx) { if (c.mon.activeTurns > 0) { announce(c); c.b.boost(c.mon, mapOf("spe" to 1), c.mon, "speedboost") } }
        })
        reg(object : A("moody") {
            override val residualOrder = 26
            override fun onResidual(c: EffectCtx) {
                val stats = listOf("atk", "def", "spa", "spd", "spe")
                val up = stats.filter { c.mon.boost(it) < 6 }.let { if (it.isEmpty()) null else it[c.b.rng.nextInt(it.size)] }
                val down = stats.filter { it != up && c.mon.boost(it) > -6 }.let { if (it.isEmpty()) null else it[c.b.rng.nextInt(it.size)] }
                announce(c)
                val m = HashMap<String, Int>(); if (up != null) m[up] = 2; if (down != null) m[down] = -1
                c.b.boost(c.mon, m, c.mon, "moody")
            }
        })

        // ------------------------------------------------ offensive stat abilities
        statDoubler("hugepower", Stat.ATK)
        statDoubler("purepower", Stat.ATK)
        reg(object : A("hustle") {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc)) mod.chain(1.5) }
            override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.category == "Physical") mod.chain(0.8) }
        })
        reg(object : A("guts") { override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.status != null) mod.chain(1.5) } })
        reg(object : A("solarpower") {
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.b.isSun(c.mon)) mod.chain(1.5) }
            override fun onWeatherChange(c: EffectCtx) {}
            override val residualOrder = 5
            override fun onResidual(c: EffectCtx) { if (c.b.isSun(c.mon)) c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / 8), c.mon, "solarpower") }
        })
        reg(object : A("gorillatactics") {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && !c.mon.isDynamaxed) mod.chain(1.5) }
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && c.state.moveId == null) c.state.moveId = mc.move.data.id }
            override fun onDisableMove(c: EffectCtx, moveId: String) = c.state.moveId != null && moveId != c.state.moveId && !c.mon.isDynamaxed
            override fun onSwitchOut(c: EffectCtx) { c.state.moveId = null }
        })
        typeBoostPinch("blaze", "Fire")
        typeBoostPinch("torrent", "Water")
        typeBoostPinch("overgrow", "Grass")
        typeBoostPinch("swarm", "Bug")
        moveFlagBoost("technician", 1.5) { it.move.basePower <= 60 && !it.move.isZ && !it.move.isMax }
        moveFlagBoost("ironfist", 1.2) { it.move.hasFlag("punch") }
        moveFlagBoost("strongjaw", 1.5) { it.move.hasFlag("bite") }
        moveFlagBoost("megalauncher", 1.5) { it.move.hasFlag("pulse") }
        moveFlagBoost("sharpness", 1.5) { it.move.hasFlag("slicing") }
        moveFlagBoost("toughclaws", 1.3) { it.move.hasFlag("contact") }
        moveFlagBoost("punkrock", 1.3) { it.move.hasFlag("sound") }
        moveFlagBoost("reckless", 1.2) { it.move.data.recoil != null || it.move.data.hasCrashDamage }
        moveFlagBoost("sandforce", 1.3) { it.move.type in setOf("Rock", "Ground", "Steel") && it.user.side.foe.let { _ -> true } }
        moveFlagBoost("steelworker", 1.5) { it.move.type == "Steel" }
        moveFlagBoost("steelyspirit", 1.5) { it.move.type == "Steel" }
        moveFlagBoost("dragonsmaw", 1.5) { it.move.type == "Dragon" }
        moveFlagBoost("transistor", 1.3) { it.move.type == "Electric" }
        moveFlagBoost("rockypayload", 1.5) { it.move.type == "Rock" }
        moveFlagBoost("waterbubble", 2.0) { it.move.type == "Water" }
        moveFlagBoost("analytic", 1.3) { it.target.movedThisTurn }
        moveFlagBoost("supremeoverlord", 1.0) { false }
        reg(object : A("sheerforce") {
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) {
                if (c.isUser(mc) && (mc.move.secondaries.isNotEmpty() || mc.move.data.self?.boosts?.values?.any { it > 0 } == true && false)) {
                    mc.move.secondaries.clear(); mc.move.hasSheerForce = true
                }
            }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.hasSheerForce) mod.chain(1.3) }
        })
        reg(object : A("adaptability") { override fun onModifyStab(c: EffectCtx, mc: MoveCtx, stab: Double) = if (stab == 1.5 && c.mon.terastallized == null) 2.0 else if (stab == 2.0 && c.mon.terastallized != null) 2.25 else stab })
        reg(object : A("tintedlens") { override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.effectiveness < 1) mod.chain(2.0) } })
        reg(object : A("sniper") { override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.crit) mod.chain(1.5) } })
        reg(object : A("superluck") { override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc)) ratio + 1 else ratio })
        reg(object : A("skilllink") {})
        reg(object : A("serenegrace") {
            override fun onModifySecondaries(c: EffectCtx, mc: MoveCtx, secondaries: MutableList<pkmn.engine.data.SecondaryEffect>) {
                if (!c.isUser(mc)) return
                for (i in secondaries.indices) secondaries[i] = secondaries[i].copy(chance = (secondaries[i].chance * 2).coerceAtMost(100))
            }
        })
        reg(object : A("shielddust") {
            override fun onModifySecondaries(c: EffectCtx, mc: MoveCtx, secondaries: MutableList<pkmn.engine.data.SecondaryEffect>) {
                if (c.isTarget(mc) && mc.user !== c.holder) secondaries.removeAll { it.self == null }
            }
        })
        reg(object : A("compoundeyes") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.data.ohko == null) mod.chain(1.3) } })
        reg(object : A("noguard") { override fun onAccuracyOverride(c: EffectCtx, mc: MoveCtx): Boolean? = if (c.isUser(mc) || c.isTarget(mc)) true else null })
        reg(object : A("keeneye") {
            override fun onTryBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>, source: BattleMon?, sourceEffect: String?) {
                if (target === c.holder && source !== target && (boosts["accuracy"] ?: 0) < 0) { boosts.remove("accuracy"); announce(c); c.b.message("${c.b.nm(target)}'s accuracy was not lowered!") }
            }
        })
        reg(object : A("mindseye") {
            override fun onTryBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>, source: BattleMon?, sourceEffect: String?) {
                if (target === c.holder && source !== target && (boosts["accuracy"] ?: 0) < 0) boosts.remove("accuracy")
            }
            override fun onImmunity(c: EffectCtx, mc: MoveCtx, type: String): Boolean? =
                if (c.isUser(mc) && (type == "Normal" || type == "Fighting") && mc.target.hasType("Ghost") && !mc.target.hasType("Normal").let { false }) false else null
        })
        reg(object : A("scrappy") {
            override fun onImmunity(c: EffectCtx, mc: MoveCtx, type: String): Boolean? =
                if (c.isUser(mc) && (type == "Normal" || type == "Fighting") && mc.target.hasType("Ghost")) false else null
        })
        ateAbility("aerilate", "Flying")
        ateAbility("pixilate", "Fairy")
        ateAbility("refrigerate", "Ice")
        ateAbility("galvanize", "Electric")
        reg(object : A("normalize") {
            override fun onModifyType(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && !mc.move.isZ && !mc.move.isMax) { mc.move.type = "Normal"; mc.move.data2["ate"] = true } }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.data2["ate"] == true) mod.chain(1.2) }
        })
        reg(object : A("liquidvoice") { override fun onModifyType(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && mc.move.hasFlag("sound")) mc.move.type = "Water" } })
        reg(object : A("prankster") {
            override fun onModifyPriority(c: EffectCtx, move: ActiveMove, user: BattleMon, priority: Int) = if (move.isStatus && user === c.holder) priority + 1 else priority
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && mc.move.isStatus) mc.move.data2["prankster"] = true }
        })
        reg(object : A("galewings") { override fun onModifyPriority(c: EffectCtx, move: ActiveMove, user: BattleMon, priority: Int) = if (move.type == "Flying" && user === c.holder && user.hp == user.maxHp) priority + 1 else priority })
        reg(object : A("triage") { override fun onModifyPriority(c: EffectCtx, move: ActiveMove, user: BattleMon, priority: Int) = if (move.hasFlag("heal") && user === c.holder) priority + 3 else priority })
        reg(object : A("stall") { override fun onFractionalPriority(c: EffectCtx, move: ActiveMove, user: BattleMon) = -0.1 })
        reg(object : A("myceliummight") {
            override fun onFractionalPriority(c: EffectCtx, move: ActiveMove, user: BattleMon) = if (move.isStatus) -0.1 else 0.0
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && mc.move.isStatus) mc.move.ignoreAbility = true }
        })
        for (id in listOf("moldbreaker", "teravolt", "turboblaze")) reg(object : A(id) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("${c.b.nm(c.mon)} breaks the mold!") }
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc)) mc.move.ignoreAbility = true }
        })
        reg(object : A("infiltrator") { override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc)) mc.move.infiltrates = true } })
        reg(object : A("longreach") { override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc)) mc.move.flags.remove("contact") } })
        reg(object : A("parentalbond") {})
        reg(object : A("stakeout") {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.target.activeTurns == 0) mod.chain(2.0) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.target.activeTurns == 0) mod.chain(2.0) }
        })
        reg(object : A("flareboost") { override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.status == MajorStatus.BURN && mc.move.category == "Special") mod.chain(1.5) } })
        reg(object : A("toxicboost") { override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && (c.mon.status == MajorStatus.POISON || c.mon.status == MajorStatus.TOXIC) && mc.move.category == "Physical") mod.chain(1.5) } })
        reg(object : A("defeatist") {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.hp <= c.mon.maxHp / 2) mod.chain(0.5) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.hp <= c.mon.maxHp / 2) mod.chain(0.5) }
        })
        reg(object : A("rivalry") {
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (!c.isUser(mc)) return
                val g1 = c.mon.mon.gender; val g2 = mc.target.mon.gender
                if (g1 == pkmn.engine.mon.Gender.NONE || g2 == pkmn.engine.mon.Gender.NONE) return
                mod.chain(if (g1 == g2) 1.25 else 0.75)
            }
        })
        for ((id, type) in listOf("protosynthesis" to "sun", "quarkdrive" to "electric")) reg(object : A(id) {
            fun active(c: EffectCtx): Boolean {
                val env = if (type == "sun") c.b.isSun(c.mon) else c.b.field.terrain == "electricterrain"
                if (env) return true
                return c.state.flag
            }
            override fun onStart(c: EffectCtx, source: BattleMon?) { check(c) }
            override fun onWeatherChange(c: EffectCtx) { if (c.holder?.isActive == true) check(c) }
            override fun onTerrainChange(c: EffectCtx) { if (c.holder?.isActive == true) check(c) }
            override fun onUpdate(c: EffectCtx) { check(c) }
            private fun check(c: EffectCtx) {
                val env = if (type == "sun") c.b.isSun(c.mon) else c.b.field.terrain == "electricterrain"
                if (!env && !c.state.flag && c.mon.item == "boosterenergy" && c.b.itemActive(c.mon)) {
                    c.b.consumeItem(c.mon); c.state.flag = true
                    c.b.message("${c.b.nm(c.mon)} used its Booster Energy to activate ${if (type == "sun") "Protosynthesis" else "Quark Drive"}!")
                }
                if ((env || c.state.flag) && c.state.type == null) {
                    c.state.type = bestStat(c.mon)
                    c.b.message("The ${if (type == "sun") "harsh sunlight" else "Electric Terrain"} boosted ${c.b.nmL(c.mon)}'s ${pkmn.engine.battle.BattleMon.boostLabel(c.state.type!!)}!".takeIf { env } ?: "${c.b.nm(c.mon)}'s ${pkmn.engine.battle.BattleMon.boostLabel(c.state.type!!)} was heightened!")
                } else if (!env && !c.state.flag && c.state.type != null) {
                    c.state.type = null
                }
            }
            private fun mult(c: EffectCtx, stat: String) = if (active(c) && c.state.type == stat) (if (stat == "spe") 1.5 else 1.3) else 1.0
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc)) mod.chain(mult(c, "atk")) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc)) mod.chain(mult(c, "spa")) }
            override fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc)) mod.chain(mult(c, "def")) }
            override fun onModifySpD(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc)) mod.chain(mult(c, "spd")) }
            override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon === c.holder) mod.chain(mult(c, "spe")) }
        })

        // ------------------------------------------------ defensive abilities
        healAbsorb("voltabsorb", "Electric")
        healAbsorb("waterabsorb", "Water")
        healAbsorb("eartheater", "Ground")
        boostAbsorb("motordrive", "Electric", "spe")
        boostAbsorb("lightningrod", "Electric", "spa")
        boostAbsorb("stormdrain", "Water", "spa")
        boostAbsorb("sapsipper", "Grass", "atk")
        absorb("flashfire", "Fire") { c, _ ->
            if (!c.mon.hasVolatile("flashfire")) { c.mon.volatiles["flashfire"] = EffectState(); c.b.message("The power of ${c.b.nmL(c.mon)}'s Fire-type moves rose!") }
            else c.b.message("It doesn't affect ${c.b.nmL(c.mon)}...")
        }
        reg(object : A("wellbakedbody") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTarget(mc) || mc.user === mc.target || mc.move.type != "Fire") return true
                announce(c); if (!c.b.boost(c.mon, mapOf("def" to 2), c.mon, "wellbakedbody")) c.b.message("It doesn't affect ${c.b.nmL(c.mon)}...")
                return false
            }
        })
        reg(object : A("dryskin") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTarget(mc) || mc.user === mc.target || mc.move.type != "Water") return true
                announce(c); if (c.b.heal(c.mon, c.mon.baseMaxHp / 4, "dryskin") == 0) c.b.message("It doesn't affect ${c.b.nmL(c.mon)}...")
                return false
            }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && mc.move.type == "Fire") mod.chain(1.25) }
            override val residualOrder = 5
            override fun onResidual(c: EffectCtx) {
                if (c.b.isRain(c.mon)) c.b.heal(c.mon, max(1, c.mon.baseMaxHp / 8), "dryskin")
                else if (c.b.isSun(c.mon)) c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / 8), c.mon, "dryskin")
            }
        })
        reg(object : A("levitate") {})
        reg(object : A("wonderguard") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTarget(mc) || mc.user === mc.target || mc.move.isStatus || mc.move.id == "struggle" || mc.move.type == "???") return true
                if (c.b.typeEffectiveness(mc) <= 1.0) { announce(c); c.b.message("It doesn't affect ${c.b.nmL(c.mon)}..."); return false }
                return true
            }
        })
        reg(object : A("bulletproof") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (c.isTarget(mc) && mc.user !== mc.target && mc.move.hasFlag("bullet")) { announce(c); c.b.message("It doesn't affect ${c.b.nmL(c.mon)}..."); return false }
                return true
            }
        })
        reg(object : A("soundproof") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (c.isTarget(mc) && mc.user !== mc.target && mc.move.hasFlag("sound")) { announce(c); c.b.message("It doesn't affect ${c.b.nmL(c.mon)}..."); return false }
                return true
            }
        })
        reg(object : A("windrider") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (c.isTarget(mc) && mc.user !== mc.target && mc.move.hasFlag("wind")) { announce(c); c.b.boost(c.mon, mapOf("atk" to 1), c.mon, "windrider"); return false }
                return true
            }
        })
        reg(object : A("goodasgold") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (c.isTarget(mc) && mc.user !== mc.target && mc.move.isStatus) { announce(c); c.b.message("It doesn't affect ${c.b.nmL(c.mon)}..."); return false }
                return true
            }
        })
        for (id in listOf("queenlymajesty", "dazzling", "armortail")) reg(object : A(id) {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (c.isTarget(mc) && mc.user.side !== c.mon.side && mc.move.priority > 0) {
                    announce(c); c.b.message("${c.b.nm(mc.user)} cannot use ${mc.move.name}!"); return false
                }
                return true
            }
        })
        reg(object : A("magicbounce") {
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (!c.isTarget(mc) || !c.b.reflects(mc, c.mon)) return true
                if (c.b.previewing) return false
                c.b.reflectMove(mc, c.mon)
                return false
            }
        })
        defenderHalve("multiscale", { it.target.hp >= it.target.maxHp })
        defenderHalve("shadowshield", { it.target.hp >= it.target.maxHp })
        defenderHalve("filter", { it.effectiveness > 1 }, 0.75)
        defenderHalve("solidrock", { it.effectiveness > 1 }, 0.75)
        defenderHalve("prismarmor", { it.effectiveness > 1 }, 0.75)
        defenderHalve("fluffy", { it.move.hasFlag("contact") && it.move.type != "Fire" })
        defenderHalve("icescales", { it.move.category == "Special" })
        reg(object : A("thickfat") {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && mc.move.type in setOf("Fire", "Ice")) mod.chain(0.5) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && mc.move.type in setOf("Fire", "Ice")) mod.chain(0.5) }
        })
        reg(object : A("heatproof") {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && mc.move.type == "Fire") mod.chain(0.5) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && mc.move.type == "Fire") mod.chain(0.5) }
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean) =
                if (target === c.holder && sourceEffect == "brn") amount / 2 else amount
        })
        reg(object : A("purifyingsalt") {
            override fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?) = target !== c.holder
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && mc.move.type == "Ghost") mod.chain(0.5) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && mc.move.type == "Ghost") mod.chain(0.5) }
        })
        reg(object : A("marvelscale") { override fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.mon.status != null) mod.chain(1.5) } })
        reg(object : A("furcoat") { override fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc)) mod.chain(2.0) } })
        reg(object : A("grasspelt") { override fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.b.field.terrain == "grassyterrain") mod.chain(1.5) } })
        reg(object : A("battlearmor") { override fun onCriticalHit(c: EffectCtx, mc: MoveCtx) = !c.isTarget(mc) })
        reg(object : A("shellarmor") { override fun onCriticalHit(c: EffectCtx, mc: MoveCtx) = !c.isTarget(mc) })
        reg(object : A("sturdy") {
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean): Int {
                if (target === c.holder && isMove && target.hp == target.maxHp && amount >= target.hp) {
                    announce(c); c.b.message("${c.b.nm(target)} endured the hit!"); return target.hp - 1
                }
                return amount
            }
            override fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean {
                if (c.isTarget(mc) && mc.move.data.ohko != null) { announce(c); c.b.message("${c.b.nm(c.mon)} is unaffected!"); return false }
                return true
            }
        })
        reg(object : A("magicguard") {
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean) =
                if (target === c.holder && !isMove && sourceEffect !in setOf("confusion", "substitute", "bellydrum", "mindblown", "struggle", "curse", "painsplit", "destinybond", "perishsong", "finalgambit", "clangoroussoul", "filletaway")) 0 else amount
        })
        reg(object : A("rockhead") {})
        reg(object : A("overcoat") {})
        reg(object : A("unaware") {})
        reg(object : A("disguise") {
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean): Int {
                if (target !== c.holder || !isMove || target.species.id != "mimikyu" && target.species.id != "mimikyutotem") return amount
                c.b.message("Its disguise served it as a decoy!")
                val busted = c.b.dex.speciesOrNull(target.species.id + "busted") ?: return 0
                c.b.formeChange(target, busted.id, "battle", "${c.b.nm(target)}'s disguise was busted!", changeAbility = false)
                c.b.directDamage(target, max(1, target.baseMaxHp / 8), target, "disguise")
                return 0
            }
        })
        reg(object : A("iceface") {
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean): Int {
                if (target !== c.holder || !isMove || target.species.id != "eiscue") return amount
                val mv = c.b.dex.moveOrNull(sourceEffect)
                if (mv?.category != "Physical") return amount
                c.b.formeChange(target, "eiscuenoice", "battle", "${c.b.nm(target)}'s Ice Face was broken!", changeAbility = false)
                return 0
            }
        })
        reg(object : A("owntempo") {
            override fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?) = !(target === c.holder && volatile == "confusion")
        })
        reg(object : A("oblivious") {
            override fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?) = !(target === c.holder && volatile in setOf("attract", "taunt"))
        })
        reg(object : A("innerfocus") {
            override fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?) = !(target === c.holder && volatile == "flinch")
        })
        reg(object : A("steadfast") {})
        reg(object : A("aromaveil") {
            override fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?) = !(target === c.holder && volatile in setOf("attract", "disable", "encore", "healblock", "taunt", "torment"))
        })
        statusImmunity("immunity", MajorStatus.POISON, MajorStatus.TOXIC)
        statusImmunity("pastelveil", MajorStatus.POISON, MajorStatus.TOXIC)
        statusImmunity("limber", MajorStatus.PARALYSIS)
        statusImmunity("insomnia", MajorStatus.SLEEP)
        statusImmunity("vitalspirit", MajorStatus.SLEEP)
        statusImmunity("sweetveil", MajorStatus.SLEEP)
        statusImmunity("waterveil", MajorStatus.BURN)
        statusImmunity("thermalexchange", MajorStatus.BURN)
        statusImmunity("magmaarmor", MajorStatus.FREEZE)
        statusImmunity("comatose", *MajorStatus.entries.toTypedArray())
        reg(object : A("leafguard") {
            override fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?) = !(target === c.holder && c.b.isSun(target))
        })
        reg(object : A("flowerveil") {
            override fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?) = !(target === c.holder && target.hasType("Grass") && source !== target)
        })
        boostBlocker("clearbody", null)
        boostBlocker("whitesmoke", null)
        boostBlocker("fullmetalbody", null)
        boostBlocker("hypercutter", setOf("atk"))
        boostBlocker("bigpecks", setOf("def"))
        boostBlocker("illuminate", setOf("accuracy"))
        reg(object : A("mirrorarmor") {
            override fun onTryBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>, source: BattleMon?, sourceEffect: String?) {
                if (target !== c.holder || source == null || source === target) return
                val neg = boosts.filterValues { it < 0 }
                if (neg.isEmpty()) return
                neg.keys.forEach { boosts.remove(it) }
                announce(c)
                if (source.isActive && !source.fainted) c.b.boost(source, neg, target, "mirrorarmor", secondary = true)
            }
        })
        reg(object : A("contrary") { override fun onChangeBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>) { for (k in boosts.keys.toList()) boosts[k] = -boosts[k]!! } })
        reg(object : A("simple") { override fun onChangeBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>) { for (k in boosts.keys.toList()) boosts[k] = boosts[k]!! * 2 } })
        reg(object : A("defiant") {
            override fun onAfterBoost(c: EffectCtx, target: BattleMon, boosts: Map<String, Int>, source: BattleMon?) {
                if (target === c.holder && source != null && source.side !== target.side && boosts.values.any { it < 0 }) { announce(c); c.b.boost(target, mapOf("atk" to 2), target, "defiant") }
            }
        })
        reg(object : A("competitive") {
            override fun onAfterBoost(c: EffectCtx, target: BattleMon, boosts: Map<String, Int>, source: BattleMon?) {
                if (target === c.holder && source != null && source.side !== target.side && boosts.values.any { it < 0 }) { announce(c); c.b.boost(target, mapOf("spa" to 2), target, "competitive") }
            }
        })
        reg(object : A("tangledfeet") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.mon.hasVolatile("confusion")) mod.chain(0.5) } })
        reg(object : A("sandveil") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.b.isSand()) mod.chain(0.8) } })
        reg(object : A("snowcloak") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.b.isSnow()) mod.chain(0.8) } })
        reg(object : A("suctioncups") { override fun onDragOut(c: EffectCtx, mon: BattleMon) = mon !== c.holder })
        reg(object : A("stickyhold") { override fun onTakeItem(c: EffectCtx, mon: BattleMon, item: String, source: BattleMon?) = !(mon === c.holder && source != null && source !== mon) })

        // ------------------------------------------------ contact punishers / on-hit triggers
        contactStatus("static", 30) { MajorStatus.PARALYSIS }
        contactStatus("flamebody", 30) { MajorStatus.BURN }
        contactStatus("poisonpoint", 30) { MajorStatus.POISON }
        contactStatus("effectspore", 30) { b -> listOf(MajorStatus.SLEEP, MajorStatus.POISON, MajorStatus.PARALYSIS)[b.rng.nextInt(3)] }
        reg(object : A("roughskin") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.hasFlag("contact") && !(mc.user.item == "protectivepads")) { announce(c); c.b.directDamage(mc.user, max(1, mc.user.baseMaxHp / 8), c.mon, "roughskin") }
            }
        })
        reg(object : A("ironbarbs") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.hasFlag("contact") && !(mc.user.item == "protectivepads")) { announce(c); c.b.directDamage(mc.user, max(1, mc.user.baseMaxHp / 8), c.mon, "ironbarbs") }
            }
        })
        reg(object : A("aftermath") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && c.mon.fainted && mc.move.hasFlag("contact")) { announce(c); c.b.directDamage(mc.user, max(1, mc.user.baseMaxHp / 4), c.mon, "aftermath") }
            }
        })
        reg(object : A("innardsout") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && c.mon.fainted) { announce(c); c.b.directDamage(mc.user, damage, c.mon, "innardsout") }
            }
        })
        reg(object : A("cutecharm") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.hasFlag("contact") && c.b.rng.nextInt(100) < 30) c.b.addVolatile(mc.user, "attract", c.mon, "cutecharm")
            }
        })
        reg(object : A("gooey") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.move.hasFlag("contact")) { announce(c); c.b.boost(mc.user, mapOf("spe" to -1), c.mon, "gooey", secondary = true) } }
        })
        reg(object : A("tanglinghair") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.move.hasFlag("contact")) { announce(c); c.b.boost(mc.user, mapOf("spe" to -1), c.mon, "tanglinghair", secondary = true) } }
        })
        reg(object : A("weakarmor") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.move.category == "Physical") { announce(c); c.b.boost(c.mon, mapOf("def" to -1, "spe" to 2), c.mon, "weakarmor") } }
        })
        reg(object : A("stamina") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && !c.mon.fainted) { announce(c); c.b.boost(c.mon, mapOf("def" to 1), c.mon, "stamina") } }
        })
        reg(object : A("justified") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.move.type == "Dark" && !c.mon.fainted) { announce(c); c.b.boost(c.mon, mapOf("atk" to 1), c.mon, "justified") } }
        })
        reg(object : A("rattled") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.move.type in setOf("Dark", "Bug", "Ghost") && !c.mon.fainted) { announce(c); c.b.boost(c.mon, mapOf("spe" to 1), c.mon, "rattled") } }
        })
        reg(object : A("watercompaction") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.move.type == "Water" && !c.mon.fainted) { announce(c); c.b.boost(c.mon, mapOf("def" to 2), c.mon, "watercompaction") } }
        })
        reg(object : A("steamengine") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.move.type in setOf("Water", "Fire") && !c.mon.fainted) { announce(c); c.b.boost(c.mon, mapOf("spe" to 6), c.mon, "steamengine") } }
        })
        reg(object : A("angerpoint") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.crit && !c.mon.fainted) { announce(c); c.mon.boosts[0] = 6; c.b.message("${c.b.nm(c.mon)} maxed its Attack!") } }
        })
        reg(object : A("angershell") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (!c.isTarget(mc) || c.mon.fainted) return
                val before = c.mon.hp + damage
                if (before > c.mon.maxHp / 2 && c.mon.hp <= c.mon.maxHp / 2) { announce(c); c.b.boost(c.mon, mapOf("atk" to 1, "spa" to 1, "spe" to 1, "def" to -1, "spd" to -1), c.mon, "angershell") }
            }
        })
        reg(object : A("berserk") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (!c.isTarget(mc) || c.mon.fainted) return
                val before = c.mon.hp + damage
                if (before > c.mon.maxHp / 2 && c.mon.hp <= c.mon.maxHp / 2) { announce(c); c.b.boost(c.mon, mapOf("spa" to 1), c.mon, "berserk") }
            }
        })
        reg(object : A("colorchange") {
            override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {
                if (c.isTarget(mc) && !c.mon.fainted && mc.damage > 0 && !c.mon.hasType(mc.move.type) && mc.move.type != "???") {
                    c.mon.types = listOf(mc.move.type); announce(c); c.b.message("${c.b.nm(c.mon)}'s type changed to ${mc.move.type}!")
                }
            }
        })
        reg(object : A("cursedbody") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && !mc.user.fainted && c.b.rng.nextInt(10) < 3) c.b.addVolatile(mc.user, "disable", c.mon, "cursedbody")
            }
        })
        reg(object : A("mummy") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.hasFlag("contact") && mc.user.ability !in setOf("mummy", "lingeringaroma")) { mc.user.ability = "mummy"; c.b.message("${c.b.nm(mc.user)}'s Ability became Mummy!") }
            }
        })
        reg(object : A("lingeringaroma") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.hasFlag("contact") && mc.user.ability !in setOf("mummy", "lingeringaroma")) { mc.user.ability = "lingeringaroma"; c.b.message("A lingering aroma clings to ${c.b.nmL(mc.user)}!") }
            }
        })
        reg(object : A("poisontouch") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {}
            override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {
                if (c.isUser(mc) && mc.move.hasFlag("contact") && !mc.target.fainted && c.b.rng.nextInt(10) < 3) c.b.trySetStatus(mc.target, MajorStatus.POISON, c.mon, "poisontouch", primary = false)
            }
        })
        reg(object : A("toxicchain") {
            override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {
                if (c.isUser(mc) && !mc.move.isStatus && !mc.target.fainted && c.b.rng.nextInt(10) < 3) c.b.trySetStatus(mc.target, MajorStatus.TOXIC, c.mon, "toxicchain", primary = false)
            }
        })
        reg(object : A("stench") {
            override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {
                if (c.isUser(mc) && !mc.move.isStatus && !mc.target.fainted && c.b.rng.nextInt(10) == 0) c.b.addVolatile(mc.target, "flinch", c.mon, "stench")
            }
        })
        reg(object : A("cottondown") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc)) { announce(c); c.b.boost(mc.user, mapOf("spe" to -1), c.mon, "cottondown", secondary = true) } }
        })
        reg(object : A("electromorphosis") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && !c.mon.fainted) { announce(c); c.b.addVolatile(c.mon, "charge", c.mon, "electromorphosis") } }
        })
        reg(object : A("seedsower") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc)) { announce(c); c.b.setTerrain("grassyterrain", c.mon) } }
        })
        reg(object : A("sandspit") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc)) { announce(c); c.b.setWeather("sandstorm", c.mon, true) } }
        })
        reg(object : A("toxicdebris") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc) && mc.move.category == "Physical") c.b.addSideCondition(mc.user.side, "toxicspikes", c.mon) }
        })

        // ------------------------------------------------ switch-in abilities
        intimidateLike("intimidate", "atk")
        reg(object : A("download") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val foe = c.mon.side.foe.active ?: return
                announce(c)
                val def = c.b.applyBoost(foe.rawStat(Stat.DEF), foe.boost("def")); val spd = c.b.applyBoost(foe.rawStat(Stat.SPD), foe.boost("spd"))
                c.b.boost(c.mon, mapOf((if (def < spd) "atk" else "spa") to 1), c.mon, "download")
            }
        })
        reg(object : A("intrepidsword") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { if (!c.state.flag) { c.state.flag = true; announce(c); c.b.boost(c.mon, mapOf("atk" to 1), c.mon, "intrepidsword") } }
        })
        reg(object : A("dauntlessshield") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { if (!c.state.flag) { c.state.flag = true; announce(c); c.b.boost(c.mon, mapOf("def" to 1), c.mon, "dauntlessshield") } }
        })
        reg(object : A("pressure") { override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("${c.b.nm(c.mon)} is exerting its pressure!") } })
        reg(object : A("unnerve") { override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("${if (c.mon.side.isPlayer) "The opposing team" else "Your team"} is too nervous to eat Berries!") } })
        reg(object : A("frisk") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val foe = c.mon.side.foe.active ?: return
                foe.item?.let { announce(c); c.b.message("${c.b.nm(c.mon)} frisked ${c.b.nmL(foe)} and found its ${c.b.dex.itemOrNull(it)?.name ?: it}!") }
            }
        })
        reg(object : A("anticipation") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val foe = c.mon.side.foe.active ?: return
                val danger = foe.moveSlots.any { s -> c.b.dex.moveOrNull(s.id)?.let { m -> !m.isStatus && (c.b.dex.typeChart.effectiveness(m.type, c.mon.effectiveTypes()) > 1 || m.ohko != null) } == true }
                if (danger) { announce(c); c.b.message("${c.b.nm(c.mon)} shuddered!") }
            }
        })
        reg(object : A("trace") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val foe = c.mon.side.foe.active ?: return
                val banned = setOf("trace", "multitype", "stancechange", "schooling", "comatose", "shieldsdown", "disguise", "rkssystem", "battlebond", "powerconstruct", "iceface", "gulpmissile", "neutralizinggas", "zerotohero", "commander", "imposter", "illusion", "flowergift", "forecast", "hungerswitch", "asoneglastrier", "asonespectrier", "receiver", "powerofalchemy", "wonderguard", "zenmode", "protosynthesis", "quarkdrive", "terashift", "teraformzero")
                if (foe.ability in banned) return
                announce(c)
                c.mon.ability = foe.ability
                c.mon.abilityState = EffectState()
                c.b.message("${c.b.nm(c.mon)} traced ${c.b.nmL(foe)}'s ${c.b.dex.abilityOrNull(foe.ability)?.name}!")
                Effects.ability(foe.ability)?.onStart(EffectCtx(c.b, Effects.ability(foe.ability)!!, c.mon, null, c.mon.abilityState), null)
            }
        })
        reg(object : A("imposter") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                val foe = c.mon.side.foe.active ?: return
                if (foe.hasVolatile("substitute") || foe.transformed) return
                MoveScripts.transform(c.b, c.mon, foe)
            }
        })
        reg(object : A("naturalcure") { override fun onSwitchOut(c: EffectCtx) { if (c.mon.status != null) c.b.cureStatus(c.mon, silent = true) } })
        reg(object : A("regenerator") { override fun onSwitchOut(c: EffectCtx) { if (!c.mon.fainted) c.mon.hp = (c.mon.hp + c.mon.baseMaxHp / 3).coerceAtMost(c.mon.baseMaxHp) } })
        reg(object : A("zerotohero") {
            override fun onSwitchOut(c: EffectCtx) {
                if (c.mon.species.id == "palafin" && !c.mon.fainted) { c.mon.setSpecies(c.b.dex.species("palafinhero")); c.mon.preTransformSpecies = null; c.state.flag = true }
            }
            override fun onStart(c: EffectCtx, source: BattleMon?) { if (c.mon.species.id == "palafinhero") { announce(c); c.b.message("${c.b.nm(c.mon)} underwent a heroic transformation!") } }
        })

        // ------------------------------------------------ residual / status interaction
        residualHeal("raindish", 16) { b, m -> b.isRain(m) }
        residualHeal("icebody", 16) { b, _ -> b.isSnow() }
        reg(object : A("poisonheal") {
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean): Int {
                if (target === c.holder && sourceEffect == "psn") { c.b.heal(target, max(1, target.baseMaxHp / 8), "poisonheal"); return 0 }
                return amount
            }
        })
        reg(object : A("shedskin") {
            override val residualOrder = 5
            override fun onResidual(c: EffectCtx) { if (c.mon.status != null && c.b.rng.nextInt(3) == 0) { announce(c); c.b.cureStatus(c.mon) } }
        })
        reg(object : A("hydration") {
            override val residualOrder = 5
            override fun onResidual(c: EffectCtx) { if (c.mon.status != null && c.b.isRain(c.mon)) { announce(c); c.b.cureStatus(c.mon) } }
        })
        reg(object : A("baddreams") {
            override val residualOrder = 25
            override fun onResidual(c: EffectCtx) {
                val foe = c.mon.side.foe.active ?: return
                if (foe.status == MajorStatus.SLEEP || foe.ability == "comatose") { announce(c); c.b.directDamage(foe, max(1, foe.baseMaxHp / 8), c.mon, "baddreams") }
            }
        })
        reg(object : A("harvest") {
            override val residualOrder = 26
            override fun onResidual(c: EffectCtx) {
                val last = c.mon.lastItem ?: return
                if (c.mon.item != null || c.b.dex.itemOrNull(last)?.isBerry != true) return
                if (c.b.isSun(c.mon) || c.b.rng.nextBoolean()) { c.mon.item = last; c.mon.lastItem = null; announce(c); c.b.message("${c.b.nm(c.mon)} harvested one ${c.b.dex.itemOrNull(last)?.name}!") }
            }
        })
        reg(object : A("cheekpouch") {
            override fun onEatItem(c: EffectCtx, mon: BattleMon, item: String) { if (mon === c.holder) c.b.heal(mon, mon.baseMaxHp / 3, "cheekpouch") }
        })
        reg(object : A("klutz") {})
        reg(object : A("synchronize") {})
        reg(object : A("runaway") {})
        reg(object : A("shadowtag") {
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = mon.side !== c.mon.side && mon.ability != "shadowtag" && !mon.hasType("Ghost")
        })
        reg(object : A("arenatrap") {
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = mon.side !== c.mon.side && c.b.isGrounded(mon) && !mon.hasType("Ghost")
        })
        reg(object : A("magnetpull") {
            override fun onTrapPokemon(c: EffectCtx, mon: BattleMon) = mon.side !== c.mon.side && mon.hasType("Steel") && !mon.hasType("Ghost")
        })
        onKoBoost("moxie") { "atk" }
        onKoBoost("chillingneigh") { "atk" }
        onKoBoost("grimneigh") { "spa" }
        onKoBoost("asoneglastrier") { "atk" }
        onKoBoost("asonespectrier") { "spa" }
        onKoBoost("beastboost") { m -> bestStat(m) }
        reg(object : A("soulheart") {
            override fun onSourceFaint(c: EffectCtx, target: BattleMon, move: ActiveMove?) { announce(c); c.b.boost(c.mon, mapOf("spa" to 1), c.mon, "soulheart") }
        })
        reg(object : A("neutralizinggas") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("Neutralizing gas filled the area!") }
            override fun onSwitchOut(c: EffectCtx) { c.b.message("The effects of the neutralizing gas wore off!") }
        })
        reg(object : A("truant") {
            override fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean {
                if (c.state.flag) { c.state.flag = false; c.b.message("${c.b.nm(c.mon)} is loafing around!"); return false }
                c.state.flag = true
                return true
            }
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.state.flag = false }
        })
        reg(object : A("stancechange") {
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) {
                if (!c.isUser(mc) || c.mon.species.baseSpecies != "aegislash") return
                if (!mc.move.isStatus && c.mon.species.id == "aegislash") c.b.formeChange(c.mon, "aegislashblade", "battle", "Changed to Blade Forme!", changeAbility = false)
                else if (mc.move.id == "kingsshield" && c.mon.species.id == "aegislashblade") c.b.formeChange(c.mon, "aegislash", "battle", "Changed to Shield Forme!", changeAbility = false)
            }
        })
        reg(object : A("zenmode") {
            override val residualOrder = 27
            override fun onResidual(c: EffectCtx) {
                val sp = c.mon.species
                val base = sp.baseSpecies
                if (base != "darmanitan") return
                val low = c.mon.hp <= c.mon.maxHp / 2
                val zen = sp.id.endsWith("zen")
                if (low && !zen) c.b.dex.speciesOrNull(sp.id + "zen")?.let { c.b.formeChange(c.mon, it.id, "battle", "Zen Mode triggered!", changeAbility = false) }
                else if (!low && zen) c.b.formeChange(c.mon, sp.id.removeSuffix("zen"), "battle", "Zen Mode ended!", changeAbility = false)
            }
        })
        reg(object : A("schooling") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { update(c) }
            override val residualOrder = 27
            override fun onResidual(c: EffectCtx) { update(c) }
            private fun update(c: EffectCtx) {
                if (c.mon.species.baseSpecies != "wishiwashi" || c.mon.level < 20) return
                val school = c.mon.hp > c.mon.maxHp / 4
                if (school && c.mon.species.id == "wishiwashi") c.b.formeChange(c.mon, "wishiwashischool", "battle", "${c.b.nm(c.mon)} formed a school!", changeAbility = false)
                else if (!school && c.mon.species.id == "wishiwashischool") c.b.formeChange(c.mon, "wishiwashi", "battle", "${c.b.nm(c.mon)} stopped schooling!", changeAbility = false)
            }
        })
        reg(object : A("shieldsdown") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { update(c) }
            override val residualOrder = 27
            override fun onResidual(c: EffectCtx) { update(c) }
            override fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?) =
                !(target === c.holder && target.species.id.startsWith("minior") && target.species.id.contains("meteor"))
            private fun update(c: EffectCtx) {
                if (c.mon.species.baseSpecies != "minior") return
                val meteor = c.mon.hp > c.mon.maxHp / 2
                val isMeteor = c.mon.species.id == "miniormeteor"
                if (!meteor && isMeteor) c.b.formeChange(c.mon, "minior", "battle", "Shields Down deactivated!", changeAbility = false)
                else if (meteor && !isMeteor && c.mon.species.id == "minior") c.b.formeChange(c.mon, "miniormeteor", "battle", null, changeAbility = false)
            }
        })
        reg(object : A("battlebond") {
            override fun onSourceFaint(c: EffectCtx, target: BattleMon, move: ActiveMove?) {
                if (c.mon.species.id == "greninjabond" && !c.state.flag) {
                    c.state.flag = true
                    c.b.message("${c.b.nm(c.mon)} became fully charged due to its bond with its Trainer!")
                    c.b.boost(c.mon, mapOf("atk" to 1, "spa" to 1, "spe" to 1), c.mon, "battlebond")
                }
            }
        })
        reg(object : A("powerconstruct") {
            override val residualOrder = 27
            override fun onResidual(c: EffectCtx) {
                val sp = c.mon.species
                if (sp.baseSpecies != "zygarde" || sp.id == "zygardecomplete" || c.mon.hp > c.mon.maxHp / 2) return
                c.b.message("You sense the presence of many!")
                val ratio = c.mon.hp
                c.b.formeChange(c.mon, "zygardecomplete", "battle", "${c.b.nm(c.mon)} transformed into its Complete Forme!", changeAbility = false)
                c.mon.hp = ratio + (c.mon.maxHp - c.b.dex.species(sp.id).let { pkmn.engine.mon.Pokemon.calcStat(it, Stat.HP, c.mon.level, c.mon.mon.ivs.hp, c.mon.mon.evs.hp, c.mon.mon.nature) })
            }
        })
        reg(object : A("forecast") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { update(c) }
            override fun onWeatherChange(c: EffectCtx) { if (c.holder?.isActive == true) update(c) }
            private fun update(c: EffectCtx) {
                if (c.mon.species.baseSpecies != "castform") return
                val target = when {
                    c.b.isSun(c.mon) -> "castformsunny"; c.b.isRain(c.mon) -> "castformrainy"; c.b.isSnow() -> "castformsnowy"; else -> "castform"
                }
                if (c.mon.species.id != target) c.b.formeChange(c.mon, target, "battle", "${c.b.nm(c.mon)} transformed!", changeAbility = false)
            }
        })
        reg(object : A("hungerswitch") {
            override val residualOrder = 27
            override fun onResidual(c: EffectCtx) {
                if (c.mon.species.baseSpecies != "morpeko" || c.mon.terastallized != null) return
                c.b.formeChange(c.mon, if (c.mon.species.id == "morpeko") "morpekohangry" else "morpeko", "battle", null, changeAbility = false)
            }
        })
        reg(object : A("libero") {
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) {
                if (!c.isUser(mc) || c.state.flag || mc.move.type == "???" || c.mon.terastallized != null) return
                if (c.mon.types != listOf(mc.move.type)) { c.mon.types = listOf(mc.move.type); c.state.flag = true; announce(c); c.b.message("${c.b.nm(c.mon)} transformed into the ${mc.move.type} type!") }
            }
            override fun onSwitchIn(c: EffectCtx) { c.state.flag = false }
        })
        Effects.ability("libero")?.let { lib -> reg(object : A("protean") {
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) = lib.onModifyMove(c, mc)
        }) }
        reg(object : A("supersweetsyrup") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.state.flag) return
                c.state.flag = true
                val foe = c.mon.side.foe.active ?: return
                announce(c); c.b.boost(foe, mapOf("evasion" to -1), c.mon, "supersweetsyrup", secondary = true)
            }
        })
        reg(object : A("costar") {})
        reg(object : A("embodyaspectteal") { override fun onStart(c: EffectCtx, source: BattleMon?) { if (c.mon.terastallized != null) c.b.boost(c.mon, mapOf("spe" to 1), c.mon, id) } })
        reg(object : A("embodyaspecthearthflame") { override fun onStart(c: EffectCtx, source: BattleMon?) { if (c.mon.terastallized != null) c.b.boost(c.mon, mapOf("atk" to 1), c.mon, id) } })
        reg(object : A("embodyaspectwellspring") { override fun onStart(c: EffectCtx, source: BattleMon?) { if (c.mon.terastallized != null) c.b.boost(c.mon, mapOf("spd" to 1), c.mon, id) } })
        reg(object : A("embodyaspectcornerstone") { override fun onStart(c: EffectCtx, source: BattleMon?) { if (c.mon.terastallized != null) c.b.boost(c.mon, mapOf("def" to 1), c.mon, id) } })
        reg(object : A("terashift") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.mon.species.id == "terapagos") c.b.formeChange(c.mon, "terapagosterastal", "battle", "${c.b.nm(c.mon)} transformed!", changeAbility = true)
            }
        })
        reg(object : A("terashell") {
            override fun onEffectiveness(c: EffectCtx, mc: MoveCtx, type: String, multiplier: Double): Double {
                if (!c.isTarget(mc) || c.mon.hp < c.mon.maxHp || mc.move.isStatus) return multiplier
                mc.move.data2["terashell"] = true
                return if (type == c.mon.effectiveTypes().first()) 0.5 else 1.0
            }
        })
        reg(object : A("teraformzero") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                if (c.mon.terastallized == null) return
                c.b.clearWeather(); c.b.clearTerrain()
            }
        })
        reg(object : A("commander") {})
        reg(object : A("ballfetch") {})
        reg(object : A("honeygather") {})
        reg(object : A("curiousmedicine") {})
        reg(object : A("hospitality") {})
        reg(object : A("telepathy") {})
        reg(object : A("friendguard") {})
        reg(object : A("healer") {})
        reg(object : A("plus") {})
        reg(object : A("minus") {})
        reg(object : A("battery") {})
        reg(object : A("powerspot") {})
        reg(object : A("symbiosis") {})
        reg(object : A("victorystar") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc)) mod.chain(1.1) } })
        reg(object : A("lightmetal") { override fun onModifyWeight(c: EffectCtx, mon: BattleMon, weight: Double) = weight / 2 })
        reg(object : A("heavymetal") { override fun onModifyWeight(c: EffectCtx, mon: BattleMon, weight: Double) = weight * 2 })
        reg(object : A("aurabreak") {})
        for ((id, type) in listOf("fairyaura" to "Fairy", "darkaura" to "Dark")) reg(object : A(id) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("${c.b.nm(c.mon)} is radiating a ${type.lowercase()} aura!") }
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (mc.move.type != type) return
                val broken = c.b.activeMons().any { it.ability == "aurabreak" && c.b.abilityActive(it) }
                mod.chain(if (broken) 0.75 else 4.0 / 3.0)
            }
        })
        for ((id, stat) in listOf("swordofruin" to "def", "beadsofruin" to "spd", "tabletsofruin" to "atk", "vesselofruin" to "spa")) reg(object : A(id) {
            override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.message("${c.b.nm(c.mon)}'s ${c.b.dex.abilityOrNull(id)?.name} weakened the ${BattleMon.boostLabel(stat)} of all surrounding Pokémon!") }
            override fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (stat == "def" && c.isUser(mc) && mc.target.ability != id) mod.chain(0.75) }
            override fun onModifySpD(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (stat == "spd" && c.isUser(mc) && mc.target.ability != id) mod.chain(0.75) }
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (stat == "atk" && c.isTarget(mc) && mc.user.ability != id) mod.chain(0.75) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (stat == "spa" && c.isTarget(mc) && mc.user.ability != id) mod.chain(0.75) }
        })
        reg(object : A("orichalcumpulse") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.setWeather("sunnyday", c.mon, true) }
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.b.isSun(c.mon)) mod.chain(5461.0 / 4096) }
        })
        reg(object : A("hadronengine") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { announce(c); c.b.setTerrain("electricterrain", c.mon) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.b.field.terrain == "electricterrain") mod.chain(5461.0 / 4096) }
        })
        reg(object : A("supremeoverlord") {
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc)) mod.chain(1.0 + 0.1 * minOf(5, c.mon.side.totalFainted)) }
        })
        reg(object : A("unseenfist") {})
        reg(object : A("guarddog") {
            override fun onTryBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>, source: BattleMon?, sourceEffect: String?) {
                if (target === c.holder && sourceEffect == "intimidate" && (boosts["atk"] ?: 0) < 0) boosts["atk"] = 1
            }
            override fun onDragOut(c: EffectCtx, mon: BattleMon) = mon !== c.holder
        })
        reg(object : A("rkssystem") {})
        reg(object : A("multitype") {})
        reg(object : A("emergencyexit") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (!c.isTarget(mc) || c.mon.fainted) return
                if (c.mon.hp + damage > c.mon.maxHp / 2 && c.mon.hp <= c.mon.maxHp / 2 && c.mon.side.canSwitchTo().isNotEmpty()) c.mon.switchFlag = "true"
            }
        })
        Effects.ability("emergencyexit")?.let { ee -> reg(object : A("wimpout") { override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) = ee.onDamagingHit(c, mc, damage) }) }
        reg(object : A("screencleaner") {
            override fun onStart(c: EffectCtx, source: BattleMon?) {
                for (s in c.b.sides) for (id in listOf("reflect", "lightscreen", "auroraveil")) c.b.removeSideCondition(s, id)
            }
        })
        reg(object : A("perishbody") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (!c.isTarget(mc) || !mc.move.hasFlag("contact")) return
                announce(c)
                c.b.addVolatile(c.mon, "perishsong", c.mon, "perishbody"); c.b.addVolatile(mc.user, "perishsong", c.mon, "perishbody")
                c.b.message("Both Pokémon will faint in three turns!")
            }
        })
        reg(object : A("quickdraw") {
            override fun onFractionalPriority(c: EffectCtx, move: ActiveMove, user: BattleMon) = if (!move.isStatus && c.b.rng.nextInt(10) < 3) 0.1 else 0.0
        })
        reg(object : A("propellertail") {})
        reg(object : A("stalwart") {})
        reg(object : A("receiver") {})
        reg(object : A("powerofalchemy") {})
        reg(object : A("pickpocket") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (!c.isTarget(mc) || c.mon.item != null || !mc.move.hasFlag("contact") || c.mon.fainted) return
                val stolen = c.b.takeItem(mc.user, c.mon) ?: return
                c.mon.item = stolen
                c.b.message("${c.b.nm(c.mon)} stole ${c.b.nmL(mc.user)}'s ${c.b.dex.itemOrNull(stolen)?.name}!")
            }
        })
        reg(object : A("magician") {
            override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {
                if (!c.isUser(mc) || c.mon.item != null || mc.move.isStatus || mc.target.fainted) return
                val stolen = c.b.takeItem(mc.target, c.mon) ?: return
                c.mon.item = stolen
                c.b.message("${c.b.nm(c.mon)} stole ${c.b.nmL(mc.target)}'s ${c.b.dex.itemOrNull(stolen)?.name}!")
            }
        })
    }

    private fun endStrong(c: EffectCtx, w: String) {
        if (c.b.field.weather != w) return
        val other = c.b.activeMons().any { it !== c.holder && it.ability == w && c.b.abilityActive(it) }
        if (!other) c.b.clearWeather()
    }

}
