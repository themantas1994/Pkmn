package pkmn.engine.battle

import pkmn.engine.data.Stat
import pkmn.engine.mon.MajorStatus
import kotlin.math.max

/** Held item implementations. Items without an entry here have no battle effect yet (see coverage report). */
object Items {
    private fun reg(e: Effect) = Effects.registerItem(e)

    open class I(id: String) : Effect(id, EffectKind.ITEM)

    private fun itemName(b: Battle, id: String) = b.dex.itemOrNull(id)?.name ?: id

    private fun Battle.unnerved(m: BattleMon) = m.side.foe.active?.let { f -> !f.fainted && f.ability in setOf("unnerve", "asoneglastrier", "asonespectrier") && abilityActive(f) } == true

    /** Eats a berry: consumes it and returns the effect multiplier (Ripen doubles it). */
    private fun eat(b: Battle, m: BattleMon, id: String): Int {
        b.message("${b.nm(m)} ate its ${itemName(b, id)}!")
        b.consumeItem(m)
        return if (m.ability == "ripen" && b.abilityActive(m)) 2 else 1
    }

    private fun pinchThreshold(b: Battle, m: BattleMon, base: Int): Boolean {
        val gluttony = m.ability == "gluttony" && b.abilityActive(m)
        return m.hp <= m.maxHp / (if (gluttony && base == 4) 2 else base)
    }

    private fun typeBoostItem(id: String, type: String, mult: Double = 1.2) = reg(object : I(id) {
        override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.type == type) mod.chain(mult) }
    })

    private fun resistBerry(id: String, type: String) = reg(object : I(id) {
        override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) {
            if (!c.isTarget(mc) || mc.move.type != type || (mc.effectiveness <= 1 && type != "Normal") || c.b.unnerved(c.mon)) return
            if (c.b.previewing) { mod.chain(0.5); return }
            val m = eat(c.b, c.mon, id)
            c.b.message("The ${itemName(c.b, id)} weakened the damage to ${c.b.nmL(c.mon)}!")
            mod.chain(if (m == 2) 0.25 else 0.5)
        }
    })

    private fun statusBerry(id: String, cures: Set<MajorStatus>, confusion: Boolean = false) = reg(object : I(id) {
        override fun onUpdate(c: EffectCtx) {
            val m = c.mon
            if (c.b.unnerved(m)) return
            val st = m.status
            if ((st != null && st in cures) || (confusion && m.hasVolatile("confusion"))) {
                eat(c.b, m, id)
                if (st != null && st in cures) c.b.cureStatus(m)
                if (confusion && m.hasVolatile("confusion")) { m.volatiles.remove("confusion"); c.b.message("${c.b.nm(m)} snapped out of its confusion!") }
            }
        }
    })

    private fun pinchStatBerry(id: String, stat: String) = reg(object : I(id) {
        override fun onUpdate(c: EffectCtx) {
            val m = c.mon
            if (c.b.unnerved(m) || !pinchThreshold(c.b, m, 4)) return
            val mult = eat(c.b, m, id)
            c.b.boost(m, mapOf(stat to mult), m, id)
        }
    })

    private fun confusionBerry(id: String, minusStat: Stat) = reg(object : I(id) {
        override fun onUpdate(c: EffectCtx) {
            val m = c.mon
            if (c.b.unnerved(m) || !pinchThreshold(c.b, m, 4)) return
            val mult = eat(c.b, m, id)
            c.b.heal(m, m.baseMaxHp / 3 * mult, id)
            if (m.mon.nature.minus == minusStat) c.b.addVolatile(m, "confusion", m, id)
        }
    })

    private val PINCH_STAT = mapOf("liechiberry" to "atk", "ganlonberry" to "def", "petayaberry" to "spa", "apicotberry" to "spd", "salacberry" to "spe")
    private val CONFUSE_BERRY = mapOf("figyberry" to Stat.ATK, "wikiberry" to Stat.SPA, "magoberry" to Stat.SPE, "aguavberry" to Stat.SPD, "iapapaberry" to Stat.DEF)
    private val CURE_BERRY = mapOf(
        "cheriberry" to setOf(MajorStatus.PARALYSIS), "przcureberry" to setOf(MajorStatus.PARALYSIS),
        "chestoberry" to setOf(MajorStatus.SLEEP), "mintberry" to setOf(MajorStatus.SLEEP),
        "pechaberry" to setOf(MajorStatus.POISON, MajorStatus.TOXIC), "psncureberry" to setOf(MajorStatus.POISON, MajorStatus.TOXIC),
        "rawstberry" to setOf(MajorStatus.BURN), "iceberry" to setOf(MajorStatus.BURN),
        "aspearberry" to setOf(MajorStatus.FREEZE), "burntberry" to setOf(MajorStatus.FREEZE),
        "lumberry" to MajorStatus.entries.toSet(), "miracleberry" to MajorStatus.entries.toSet(),
    )

    /**
     * Applies the effect of eating berry [id] to [m] regardless of its normal trigger (Bug Bite, Pluck, Stuff Cheeks,
     * Teatime, Fling). The caller is responsible for removing the berry. Returns true if it did something.
     */
    fun applyBerry(b: Battle, m: BattleMon, id: String): Boolean {
        val mult = if (m.ability == "ripen" && b.abilityActive(m)) 2 else 1
        for (c in b.targetHandlers(m)) if (c.holder === m) c.effect.onEatItem(c, m, id)
        m.volatiles["ateberry"] = EffectState()
        PINCH_STAT[id]?.let { return b.boost(m, mapOf(it to mult), m, id) }
        CONFUSE_BERRY[id]?.let { st ->
            val healed = b.heal(m, m.baseMaxHp / 3 * mult, id) > 0
            if (m.mon.nature.minus == st) b.addVolatile(m, "confusion", m, id)
            return healed
        }
        CURE_BERRY[id]?.let { cures ->
            var did = false
            if (m.status != null && m.status in cures) { b.cureStatus(m); did = true }
            if (id in setOf("lumberry", "miracleberry") && m.hasVolatile("confusion")) { m.volatiles.remove("confusion"); did = true }
            return did
        }
        return when (id) {
            "sitrusberry", "enigmaberry" -> b.heal(m, m.baseMaxHp / 4 * mult, id) > 0
            "oranberry", "berry" -> b.heal(m, 10 * mult, id) > 0
            "goldberry" -> b.heal(m, 30 * mult, id) > 0
            "persimberry", "bitterberry" -> m.volatiles.remove("confusion") != null
            "leppaberry", "mysteryberry" -> {
                val slot = m.moveSlots.firstOrNull { it.pp == 0 } ?: m.moveSlots.firstOrNull { it.pp < it.maxPp(b.dex) } ?: return false
                slot.pp = minOf(slot.maxPp(b.dex), slot.pp + (if (id == "leppaberry") 10 else 5) * mult); true
            }
            "starfberry" -> {
                val stats = listOf("atk", "def", "spa", "spd", "spe").filter { m.boost(it) < 6 }
                stats.isNotEmpty() && b.boost(m, mapOf(stats[b.rng.nextInt(stats.size)] to 2 * mult), m, id)
            }
            "lansatberry" -> b.addVolatile(m, "focusenergy", m, id) != null
            "micleberry" -> { m.volatiles["micleberry"] = EffectState(2); true }
            "keeberry" -> b.boost(m, mapOf("def" to mult), m, id)
            "marangaberry" -> b.boost(m, mapOf("spd" to mult), m, id)
            else -> false
        }
    }

    /** [m] eats its held berry [id] right now, whatever its trigger condition. */
    fun forceEat(b: Battle, m: BattleMon, id: String) {
        b.message("${b.nm(m)} ate its ${itemName(b, id)}!")
        m.lastItem = id; m.item = null; m.usedItemThisBattle = true; m.itemState = EffectState()
        applyBerry(b, m, id)
    }

    fun registerAll() {
        // ------------------------------------------------ choice items
        for ((id, stat) in listOf("choiceband" to "atk", "choicespecs" to "spa", "choicescarf" to "spe")) reg(object : I(id) {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (stat == "atk" && c.isUser(mc) && !c.mon.isDynamaxed) mod.chain(1.5) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (stat == "spa" && c.isUser(mc) && !c.mon.isDynamaxed) mod.chain(1.5) }
            override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (stat == "spe" && mon === c.holder && !mon.isDynamaxed) mod.chain(1.5) }
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && c.state.moveId == null && !mc.move.isZ && !mc.move.isMax) c.state.moveId = mc.move.data.id }
            override fun onDisableMove(c: EffectCtx, moveId: String) = c.state.moveId != null && moveId != c.state.moveId && !c.mon.isDynamaxed && c.mon.moveSlot(c.state.moveId!!) != null
            override fun onSwitchOut(c: EffectCtx) { c.state.moveId = null }
        })

        // ------------------------------------------------ damage boosters
        reg(object : I("lifeorb") {
            override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc)) mod.chain(5324.0 / 4096) }
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) {
                if (c.isUser(mc) && mc.move.totalDamage > 0 && !mc.move.isStatus && !mc.move.hasSheerForce && !c.mon.fainted &&
                    !(c.mon.ability == "magicguard" && c.b.abilityActive(c.mon))) {
                    if (c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / 10), c.mon, "lifeorb") > 0) c.b.message("${c.b.nm(c.mon)} lost some of its HP!")
                }
            }
        })
        reg(object : I("expertbelt") { override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.effectiveness > 1) mod.chain(4915.0 / 4096) } })
        reg(object : I("muscleband") { override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.category == "Physical") mod.chain(4505.0 / 4096) } })
        reg(object : I("wiseglasses") { override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.category == "Special") mod.chain(4505.0 / 4096) } })
        reg(object : I("punchingglove") {
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.move.hasFlag("punch")) mod.chain(1.1) }
            override fun onModifyMove(c: EffectCtx, mc: MoveCtx) { if (c.isUser(mc) && mc.move.hasFlag("punch")) mc.move.flags.remove("contact") }
        })
        reg(object : I("metronome") {
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) {
                if (!c.isUser(mc)) return
                if (c.state.moveId == mc.move.data.id && !c.mon.lastMoveFailed) c.state.counter++ else { c.state.moveId = mc.move.data.id; c.state.counter = 0 }
            }
            override fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (c.isUser(mc) && c.state.moveId == mc.move.data.id) mod.chain(1.0 + 0.2 * minOf(5, c.state.counter))
            }
        })
        val typeItems = listOf(
            "silkscarf" to "Normal", "blackbelt" to "Fighting", "sharpbeak" to "Flying", "poisonbarb" to "Poison", "softsand" to "Ground",
            "hardstone" to "Rock", "silverpowder" to "Bug", "spelltag" to "Ghost", "metalcoat" to "Steel", "charcoal" to "Fire",
            "mysticwater" to "Water", "miracleseed" to "Grass", "magnet" to "Electric", "twistedspoon" to "Psychic", "nevermeltice" to "Ice",
            "dragonfang" to "Dragon", "blackglasses" to "Dark", "fairyfeather" to "Fairy",
            "seaincense" to "Water", "waveincense" to "Water", "roseincense" to "Grass", "oddincense" to "Psychic", "rockincense" to "Rock",
        )
        for ((id, t) in typeItems) typeBoostItem(id, t)
        val plates = listOf("flameplate" to "Fire", "splashplate" to "Water", "zapplate" to "Electric", "meadowplate" to "Grass", "icicleplate" to "Ice",
            "fistplate" to "Fighting", "toxicplate" to "Poison", "earthplate" to "Ground", "skyplate" to "Flying", "mindplate" to "Psychic",
            "insectplate" to "Bug", "stoneplate" to "Rock", "spookyplate" to "Ghost", "dracoplate" to "Dragon", "dreadplate" to "Dark",
            "ironplate" to "Steel", "pixieplate" to "Fairy")
        for ((id, t) in plates) typeBoostItem(id, t)
        // Gems: 1.3x, consumed on use.
        for (t in listOf("Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark", "Fairy")) {
            val id = t.lowercase() + "gem"
            reg(object : I(id) {
                override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                    if (!c.isUser(mc) || mc.move.type != t || mc.move.isStatus || mc.move.isZ || mc.move.isMax) return
                    mod.chain(1.3)
                    if (!c.b.previewing) { c.b.message("The ${itemName(c.b, id)} strengthened ${c.b.nmL(c.mon)}'s power!"); c.b.consumeItem(c.mon) }
                }
            })
        }
        // Species-specific stat items.
        reg(object : I("lightball") {
            override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.species.baseSpecies == "pikachu") mod.chain(2.0) }
            override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.species.baseSpecies == "pikachu") mod.chain(2.0) }
        })
        reg(object : I("thickclub") { override fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.species.baseSpecies in setOf("cubone", "marowak")) mod.chain(2.0) } })
        reg(object : I("deepseatooth") { override fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.species.baseSpecies == "clamperl") mod.chain(2.0) } })
        reg(object : I("deepseascale") { override fun onModifySpD(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.mon.species.baseSpecies == "clamperl") mod.chain(2.0) } })
        reg(object : I("metalpowder") { override fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.mon.species.id == "ditto" && !c.mon.transformed) mod.chain(2.0) } })
        reg(object : I("quickpowder") { override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon === c.holder && mon.species.id == "ditto" && !mon.transformed) mod.chain(2.0) } })
        reg(object : I("souldew") { override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && c.mon.species.baseSpecies in setOf("latios", "latias") && mc.move.type in setOf("Psychic", "Dragon")) mod.chain(1.2) } })
        for ((id, sp) in listOf("adamantorb" to "dialga", "lustrousorb" to "palkia", "griseousorb" to "giratina", "adamantcrystal" to "dialga", "lustrousglobe" to "palkia", "griseouscore" to "giratina")) reg(object : I(id) {
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {
                if (!c.isUser(mc) || c.mon.species.baseSpecies != sp) return
                val types = c.b.dex.species(sp).types
                if (mc.move.type in types) mod.chain(1.2)
            }
        })
        reg(object : I("leek") { override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc) && c.mon.species.baseSpecies in setOf("farfetchd", "sirfetchd")) ratio + 2 else ratio })
        reg(object : I("stick") { override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc) && c.mon.species.baseSpecies == "farfetchd") ratio + 2 else ratio })
        reg(object : I("luckypunch") { override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc) && c.mon.species.baseSpecies == "chansey") ratio + 2 else ratio })
        reg(object : I("scopelens") { override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc)) ratio + 1 else ratio })
        reg(object : I("razorclaw") { override fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int) = if (c.isUser(mc)) ratio + 1 else ratio })
        reg(object : I("widelens") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc)) mod.chain(1.1) } })
        reg(object : I("zoomlens") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isUser(mc) && mc.target.movedThisTurn) mod.chain(1.2) } })
        reg(object : I("brightpowder") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc)) mod.chain(0.9) } })
        reg(object : I("laxincense") { override fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc)) mod.chain(0.9) } })
        reg(object : I("kingsrock") {
            override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {
                if (c.isUser(mc) && !mc.move.isStatus && !mc.target.fainted && mc.move.secondaries.none { it.volatileStatus == "flinch" } && c.b.rng.nextInt(10) == 0) c.b.addVolatile(mc.target, "flinch", c.mon, "kingsrock")
            }
        })
        Effects.item("kingsrock")?.let { kr -> reg(object : I("razorfang") { override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) = kr.onAfterMoveSecondary(c, mc) }) }
        reg(object : I("shellbell") {
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) {
                if (c.isUser(mc) && mc.move.totalDamage > 0 && !c.mon.fainted && !mc.move.hasSheerForce) c.b.heal(c.mon, max(1, mc.move.totalDamage / 8), "shellbell")
            }
        })
        reg(object : I("loadeddice") {})
        reg(object : I("throatspray") {
            override fun onAfterMove(c: EffectCtx, mc: MoveCtx) {
                if (c.isUser(mc) && mc.move.hasFlag("sound") && !c.mon.fainted && !c.mon.lastMoveFailed) { c.b.consumeItem(c.mon); c.b.boost(c.mon, mapOf("spa" to 1), c.mon, "throatspray") }
            }
        })

        // ------------------------------------------------ defensive items
        reg(object : I("leftovers") {
            override val residualOrder = 5
            override fun onResidual(c: EffectCtx) { if (c.b.heal(c.mon, max(1, c.mon.baseMaxHp / 16), "leftovers") > 0) c.b.message("${c.b.nm(c.mon)} restored a little HP using its Leftovers!") }
        })
        reg(object : I("blacksludge") {
            override val residualOrder = 5
            override fun onResidual(c: EffectCtx) {
                if (c.mon.hasType("Poison")) { if (c.b.heal(c.mon, max(1, c.mon.baseMaxHp / 16), "blacksludge") > 0) c.b.message("${c.b.nm(c.mon)} restored a little HP using its Black Sludge!") }
                else if (c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / 8), c.mon, "blacksludge") > 0) c.b.message("${c.b.nm(c.mon)} was hurt by its Black Sludge!")
            }
        })
        reg(object : I("assaultvest") {
            override fun onModifySpD(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc)) mod.chain(1.5) }
            override fun onDisableMove(c: EffectCtx, moveId: String) = c.b.dex.moveOrNull(moveId)?.let { it.isStatus && it.id != "mefirst" } == true
        })
        reg(object : I("eviolite") {
            override fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.mon.species.evolutions.isNotEmpty()) mod.chain(1.5) }
            override fun onModifySpD(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (c.isTarget(mc) && c.mon.species.evolutions.isNotEmpty()) mod.chain(1.5) }
        })
        reg(object : I("focussash") {
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean): Int {
                if (target === c.holder && isMove && target.hp == target.maxHp && amount >= target.hp) {
                    c.b.message("${c.b.nm(target)} hung on using its Focus Sash!"); c.b.consumeItem(target); return target.hp - 1
                }
                return amount
            }
        })
        reg(object : I("focusband") {
            override fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean): Int {
                if (target === c.holder && isMove && amount >= target.hp && c.b.rng.nextInt(10) == 0) { c.b.message("${c.b.nm(target)} hung on using its Focus Band!"); return target.hp - 1 }
                return amount
            }
        })
        reg(object : I("rockyhelmet") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.hasFlag("contact") && mc.user.item != "protectivepads") {
                    if (c.b.directDamage(mc.user, max(1, mc.user.baseMaxHp / 6), c.mon, "rockyhelmet") > 0) c.b.message("${c.b.nm(mc.user)} was hurt by the Rocky Helmet!")
                }
            }
        })
        reg(object : I("airballoon") {
            override fun onStart(c: EffectCtx, source: BattleMon?) { c.b.message("${c.b.nm(c.mon)} floats in the air with its Air Balloon!") }
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) { if (c.isTarget(mc)) { c.b.message("${c.b.nm(c.mon)}'s Air Balloon popped!"); c.b.consumeItem(c.mon) } }
        })
        reg(object : I("weaknesspolicy") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.effectiveness > 1 && !c.mon.fainted) { c.b.consumeItem(c.mon); c.b.boost(c.mon, mapOf("atk" to 2, "spa" to 2), c.mon, "weaknesspolicy") }
            }
        })
        for ((id, type, stat) in listOf(Triple("absorbbulb", "Water", "spa"), Triple("cellbattery", "Electric", "atk"), Triple("luminousmoss", "Water", "spd"), Triple("snowball", "Ice", "atk"))) reg(object : I(id) {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.type == type && !c.mon.fainted) { c.b.consumeItem(c.mon); c.b.boost(c.mon, mapOf(stat to 1), c.mon, id) }
            }
        })
        reg(object : I("heavydutyboots") {})
        reg(object : I("safetygoggles") {})
        reg(object : I("utilityumbrella") {})
        reg(object : I("protectivepads") {})
        reg(object : I("covertcloak") {
            override fun onModifySecondaries(c: EffectCtx, mc: MoveCtx, secondaries: MutableList<pkmn.engine.data.SecondaryEffect>) {
                if (c.isTarget(mc) && mc.user !== c.holder) secondaries.removeAll { it.self == null }
            }
        })
        reg(object : I("clearamulet") {
            override fun onTryBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>, source: BattleMon?, sourceEffect: String?) {
                if (target !== c.holder || source == null || source === target) return
                if (boosts.values.any { it < 0 }) { boosts.entries.removeIf { it.value < 0 }; c.b.message("The effects of ${c.b.nmL(target)}'s Clear Amulet prevent its stats from being lowered!") }
            }
        })
        reg(object : I("abilityshield") {})
        reg(object : I("shedshell") {})
        reg(object : I("ironball") { override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon === c.holder) mod.chain(0.5) } })
        reg(object : I("laggingtail") { override fun onFractionalPriority(c: EffectCtx, move: ActiveMove, user: BattleMon) = -0.1 })
        reg(object : I("fullincense") { override fun onFractionalPriority(c: EffectCtx, move: ActiveMove, user: BattleMon) = -0.1 })
        reg(object : I("quickclaw") {
            override fun onFractionalPriority(c: EffectCtx, move: ActiveMove, user: BattleMon) = if (!move.isStatus || true) { if (c.b.rng.nextInt(5) == 0) 0.1 else 0.0 } else 0.0
        })
        reg(object : I("custapberry") {
            override fun onFractionalPriority(c: EffectCtx, move: ActiveMove, user: BattleMon): Double {
                if (user.hp <= user.maxHp / 4 && !c.b.unnerved(user)) { eat(c.b, user, "custapberry"); return 0.1 }
                return 0.0
            }
        })
        for (id in listOf("machobrace", "powerweight", "powerbracer", "powerbelt", "powerlens", "powerband", "poweranklet")) reg(object : I(id) {
            override fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) { if (mon === c.holder) mod.chain(0.5) }
        })
        reg(object : I("bigroot") {})
        reg(object : I("gripclaw") {})
        reg(object : I("bindingband") {})
        reg(object : I("lightclay") {})
        reg(object : I("heatrock") {}); reg(object : I("damprock") {}); reg(object : I("smoothrock") {}); reg(object : I("icyrock") {})
        reg(object : I("terrainextender") {})
        reg(object : I("luckyegg") {})
        reg(object : I("everstone") {})
        reg(object : I("expshare") {})
        reg(object : I("smokeball") {})
        reg(object : I("destinyknot") {})
        reg(object : I("ringtarget") {})
        reg(object : I("powerherb") {})
        reg(object : I("boosterenergy") {})
        reg(object : I("toxicorb") {
            override val residualOrder = 28
            override fun onResidual(c: EffectCtx) { c.b.trySetStatus(c.mon, MajorStatus.TOXIC, c.mon, "toxicorb", primary = false) }
        })
        reg(object : I("flameorb") {
            override val residualOrder = 28
            override fun onResidual(c: EffectCtx) { c.b.trySetStatus(c.mon, MajorStatus.BURN, c.mon, "flameorb", primary = false) }
        })
        reg(object : I("stickybarb") {
            override val residualOrder = 28
            override fun onResidual(c: EffectCtx) { c.b.directDamage(c.mon, max(1, c.mon.baseMaxHp / 8), c.mon, "stickybarb") }
        })
        reg(object : I("whiteherb") {
            override fun onUpdate(c: EffectCtx) {
                if (c.mon.boosts.any { it < 0 }) {
                    for (i in c.mon.boosts.indices) if (c.mon.boosts[i] < 0) c.mon.boosts[i] = 0
                    c.b.message("${c.b.nm(c.mon)} returned its stats to normal using its White Herb!"); c.b.consumeItem(c.mon)
                }
            }
        })
        reg(object : I("mentalherb") {
            override fun onUpdate(c: EffectCtx) {
                val vs = listOf("attract", "taunt", "encore", "torment", "disable", "healblock").filter { c.mon.hasVolatile(it) }
                if (vs.isNotEmpty()) { vs.forEach { c.mon.volatiles.remove(it) }; c.b.message("${c.b.nm(c.mon)} cured its status using its Mental Herb!"); c.b.consumeItem(c.mon) }
            }
        })
        reg(object : I("mirrorherb") {
            override fun onAfterBoost(c: EffectCtx, target: BattleMon, boosts: Map<String, Int>, source: BattleMon?) {
                if (target === c.holder || source == null || target.side === c.mon.side) return
                val pos = boosts.filterValues { it > 0 }
                if (pos.isNotEmpty() && c.mon.item == "mirrorherb") { c.b.consumeItem(c.mon); c.b.boost(c.mon, pos, c.mon, "mirrorherb") }
            }
        })
        reg(object : I("ejectbutton") {
            override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {
                if (c.isTarget(mc) && !c.mon.fainted && mc.damage > 0 && !mc.substituteHit && c.mon.side.canSwitchTo().isNotEmpty() && !mc.move.isStatus) {
                    c.b.message("${c.b.nm(c.mon)} is switched out with the Eject Button!"); c.b.consumeItem(c.mon); c.mon.switchFlag = "true"
                }
            }
        })
        reg(object : I("redcard") {
            override fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {
                if (c.isTarget(mc) && !c.mon.fainted && mc.damage > 0 && !mc.user.fainted && !mc.substituteHit && mc.user.side.canSwitchTo().isNotEmpty() && c.b.canDrag(mc.user)) {
                    c.b.message("${c.b.nm(c.mon)} held up its Red Card against ${c.b.nmL(mc.user)}!"); c.b.consumeItem(c.mon); mc.user.forceSwitchFlag = true
                }
            }
        })
        reg(object : I("ejectpack") {
            override fun onAfterBoost(c: EffectCtx, target: BattleMon, boosts: Map<String, Int>, source: BattleMon?) {
                if (target === c.holder && boosts.values.any { it < 0 } && c.mon.side.canSwitchTo().isNotEmpty() && c.mon.item == "ejectpack") {
                    c.b.message("${c.b.nm(c.mon)} is switched out by the Eject Pack!"); c.b.consumeItem(c.mon); c.mon.switchFlag = "true"
                }
            }
        })
        reg(object : I("adrenalineorb") {})
        reg(object : I("roomservice") {
            override fun onUpdate(c: EffectCtx) {
                if (c.b.field.has("trickroom") && c.mon.boost("spe") > -6) { c.b.consumeItem(c.mon); c.b.boost(c.mon, mapOf("spe" to -1), c.mon, "roomservice") }
            }
        })
        for ((id, terrain, stat) in listOf(Triple("electricseed", "electricterrain", "def"), Triple("grassyseed", "grassyterrain", "def"), Triple("mistyseed", "mistyterrain", "spd"), Triple("psychicseed", "psychicterrain", "spd"))) reg(object : I(id) {
            override fun onUpdate(c: EffectCtx) { if (c.b.field.terrain == terrain) { c.b.consumeItem(c.mon); c.b.boost(c.mon, mapOf(stat to 1), c.mon, id) } }
        })
        // Weather-extending, orbs and stones without direct battle hooks are handled elsewhere (Gimmicks, setWeather).
        for (id in listOf("redorb", "blueorb", "rustedsword", "rustedshield", "cornerstonemask", "hearthflamemask", "wellspringmask")) reg(object : I(id) {
            override fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) { if (id.endsWith("mask") && c.isUser(mc) && c.mon.species.baseSpecies == "ogerpon") mod.chain(1.2) }
        })

        // ------------------------------------------------ berries
        reg(object : I("sitrusberry") {
            override fun onUpdate(c: EffectCtx) {
                if (c.mon.hp <= c.mon.maxHp / 2 && !c.b.unnerved(c.mon)) { val m = eat(c.b, c.mon, "sitrusberry"); c.b.heal(c.mon, c.mon.baseMaxHp / 4 * m, "sitrusberry") }
            }
        })
        reg(object : I("oranberry") {
            override fun onUpdate(c: EffectCtx) {
                if (c.mon.hp <= c.mon.maxHp / 2 && !c.b.unnerved(c.mon)) { val m = eat(c.b, c.mon, "oranberry"); c.b.heal(c.mon, 10 * m, "oranberry") }
            }
        })
        reg(object : I("berryjuice") {
            override fun onUpdate(c: EffectCtx) { if (c.mon.hp <= c.mon.maxHp / 2) { c.b.consumeItem(c.mon); c.b.heal(c.mon, 20, "berryjuice") } }
        })
        reg(object : I("leppaberry") {
            override fun onUpdate(c: EffectCtx) {
                val slot = c.mon.moveSlots.firstOrNull { it.pp == 0 } ?: return
                if (c.b.unnerved(c.mon)) return
                val m = eat(c.b, c.mon, "leppaberry")
                slot.pp = minOf(slot.maxPp(c.b.dex), 10 * m)
                c.b.message("${c.b.nm(c.mon)} restored PP to its ${c.b.dex.moveOrNull(slot.id)?.name}!")
            }
        })
        statusBerry("lumberry", MajorStatus.entries.toSet(), confusion = true)
        statusBerry("cheriberry", setOf(MajorStatus.PARALYSIS))
        statusBerry("chestoberry", setOf(MajorStatus.SLEEP))
        statusBerry("pechaberry", setOf(MajorStatus.POISON, MajorStatus.TOXIC))
        statusBerry("rawstberry", setOf(MajorStatus.BURN))
        statusBerry("aspearberry", setOf(MajorStatus.FREEZE))
        statusBerry("persimberry", emptySet(), confusion = true)
        pinchStatBerry("liechiberry", "atk")
        pinchStatBerry("ganlonberry", "def")
        pinchStatBerry("petayaberry", "spa")
        pinchStatBerry("apicotberry", "spd")
        pinchStatBerry("salacberry", "spe")
        reg(object : I("starfberry") {
            override fun onUpdate(c: EffectCtx) {
                if (c.b.unnerved(c.mon) || !pinchThreshold(c.b, c.mon, 4)) return
                val stats = listOf("atk", "def", "spa", "spd", "spe").filter { c.mon.boost(it) < 6 }
                if (stats.isEmpty()) return
                val m = eat(c.b, c.mon, "starfberry")
                c.b.boost(c.mon, mapOf(stats[c.b.rng.nextInt(stats.size)] to 2 * m), c.mon, "starfberry")
            }
        })
        reg(object : I("lansatberry") {
            override fun onUpdate(c: EffectCtx) {
                if (c.b.unnerved(c.mon) || !pinchThreshold(c.b, c.mon, 4)) return
                eat(c.b, c.mon, "lansatberry"); c.b.addVolatile(c.mon, "focusenergy", c.mon, "lansatberry")
            }
        })
        reg(object : I("micleberry") {
            override fun onUpdate(c: EffectCtx) {
                if (c.b.unnerved(c.mon) || !pinchThreshold(c.b, c.mon, 4)) return
                eat(c.b, c.mon, "micleberry"); c.mon.volatiles["micleberry"] = EffectState(2)
            }
        })
        confusionBerry("figyberry", Stat.ATK)
        confusionBerry("wikiberry", Stat.SPA)
        confusionBerry("magoberry", Stat.SPE)
        confusionBerry("aguavberry", Stat.SPD)
        confusionBerry("iapapaberry", Stat.DEF)
        for ((id, cat) in listOf("keeberry" to "Physical", "marangaberry" to "Special")) reg(object : I(id) {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.category == cat && !c.mon.fainted && !c.b.unnerved(c.mon)) {
                    val m = eat(c.b, c.mon, id); c.b.boost(c.mon, mapOf((if (cat == "Physical") "def" else "spd") to m), c.mon, id)
                }
            }
        })
        for ((id, cat) in listOf("jabocaberry" to "Physical", "rowapberry" to "Special")) reg(object : I(id) {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.move.category == cat && !mc.user.fainted && !c.b.unnerved(c.mon)) {
                    val m = eat(c.b, c.mon, id); c.b.directDamage(mc.user, mc.user.baseMaxHp / 8 * m, c.mon, id)
                }
            }
        })
        reg(object : I("enigmaberry") {
            override fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {
                if (c.isTarget(mc) && mc.effectiveness > 1 && !c.mon.fainted && !c.b.unnerved(c.mon)) { val m = eat(c.b, c.mon, "enigmaberry"); c.b.heal(c.mon, c.mon.baseMaxHp / 4 * m, "enigmaberry") }
            }
        })
        val resist = listOf("occaberry" to "Fire", "passhoberry" to "Water", "wacanberry" to "Electric", "rindoberry" to "Grass", "yacheberry" to "Ice",
            "chopleberry" to "Fighting", "kebiaberry" to "Poison", "shucaberry" to "Ground", "cobaberry" to "Flying", "payapaberry" to "Psychic",
            "tangaberry" to "Bug", "chartiberry" to "Rock", "kasibberry" to "Ghost", "habanberry" to "Dragon", "colburberry" to "Dark",
            "babiriberry" to "Steel", "roseliberry" to "Fairy", "chilanberry" to "Normal")
        for ((id, t) in resist) resistBerry(id, t)
    }
}
