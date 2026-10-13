package pkmn.engine.battle

import pkmn.engine.data.*
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.MoveSlot
import pkmn.engine.mon.Pokemon
import pkmn.engine.mon.Progression
import pkmn.engine.util.Rng
import pkmn.engine.util.toId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Singles battle engine. Rules are independent of rendering: the UI submits [Choice]s and plays back [BattleEvent]s.
 *
 * Turn structure:
 *  1. both sides choose (player via UI, others via [BattleAI]);
 *  2. actions are ordered (run, items, switches, gimmick transformations, then moves by priority/speed);
 *  3. each action resolves through the event pipeline, with faint checks after every action;
 *  4. end-of-turn residual effects run in [Effect.residualOrder];
 *  5. fainted Pokémon are replaced (forced switch requests).
 */
class Battle(
    val dex: Dex,
    val sides: List<Side>,
    val rules: BattleRules = BattleRules(),
    seed: Long = System.nanoTime(),
    /** Optional environment info (affects Nature Power, Camouflage, Secret Power, catch modifiers). */
    val environment: String = "plain",
    val isNight: Boolean = false,
) {
    val rng = Rng(seed)
    val field = FieldState()
    val events = ArrayList<BattleEvent>()
    val progression = Progression(dex)
    var turn = 0
        private set
    var outcome = BattleOutcome.ONGOING
        private set
    var caughtMon: Pokemon? = null
        private set
    var runAttempts = 0
    var lastMoveUsed: String? = null
    /** Id of the last move that succeeded this turn (Fusion Flare / Fusion Bolt). */
    var lastSuccessfulMoveThisTurn: String? = null
    /** Debug trace for tests (every message is also mirrored here). */
    val log = ArrayList<String>()
    /** Pending learn-move prompts (team index of player Pokémon -> moves). Consumed by the UI after the battle. */
    val pendingMoveLearns = ArrayList<Pair<Int, String>>()
    /** Player Pokémon that gained levels this battle (team indices); checked for evolution afterwards. */
    val leveledUp = LinkedHashSet<Int>()
    var moneyEarned = 0
    /** Accumulated Pay Day / Make It Rain coins. */
    var payDay = 0
    /** True while the AI previews damage; effects must not consume items or change state. */
    var previewing = false
    /** Forces the damage roll (85..100) for deterministic tests; null = random. */
    var forcedRoll: Int? = null
    /** Forces critical hits on (true) or off (false) for deterministic tests. */
    var forcedCrit: Boolean? = null

    private val choices = HashMap<Int, Choice>()
    private val pendingSwitches = HashSet<Int>()
    private var started = false
    private var currentAction: Any? = null

    val player get() = sides[0]
    val opponent get() = sides[1]

    init {
        require(sides.size == 2) { "Only singles battles between two sides are supported" }
        sides[0].foe = sides[1]; sides[1].foe = sides[0]
        Effects.ensureInit()
    }

    // ================================================================= messages

    fun message(text: String) { events.add(BattleEvent.Message(text)); log.add(text) }

    /** Name for the start of a sentence: "Pikachu", "The opposing Pikachu", "The wild Pikachu". */
    fun nm(mon: BattleMon): String = when (mon.side.controller) {
        Controller.PLAYER -> mon.name
        Controller.WILD -> "The wild ${mon.name}"
        Controller.AI -> "The opposing ${mon.name}"
    }

    /** Name in the middle of a sentence. */
    fun nmL(mon: BattleMon): String = when (mon.side.controller) {
        Controller.PLAYER -> mon.name
        Controller.WILD -> "the wild ${mon.name}"
        Controller.AI -> "the opposing ${mon.name}"
    }

    fun teamIndex(mon: BattleMon) = mon.side.mons.indexOf(mon)

    private fun hpEvent(mon: BattleMon, delta: Int, cause: String) {
        events.add(BattleEvent.HpChange(mon.side.index, teamIndex(mon), mon.hp, mon.maxHp, delta, cause))
    }

    // ================================================================= effect dispatch

    fun isNeutralizingGasActive(): Boolean = sides.any { s ->
        val a = s.active ?: return@any false
        !a.fainted && a.ability == "neutralizinggas" && !a.hasVolatile("gastroacid")
    }

    private val unsuppressable = setOf("asoneglastrier", "asonespectrier", "battlebond", "comatose", "disguise", "gulpmissile",
        "iceface", "multitype", "powerconstruct", "rkssystem", "schooling", "shieldsdown", "stancechange", "tera shift",
        "zenmode", "zerotohero", "terashift", "teraformzero", "neutralizinggas")

    /** Ability Shield protects the holder's Ability from being suppressed, ignored or changed. */
    fun hasAbilityShield(mon: BattleMon) = mon.item == "abilityshield" && !mon.hasVolatile("embargo") && !field.has("magicroom")

    /** Whether [mon]'s Ability may be replaced or suppressed by a move or another Ability. */
    fun canChangeAbility(mon: BattleMon) = !hasAbilityShield(mon) && mon.ability !in unsuppressable

    fun abilityActive(mon: BattleMon, mc: MoveCtx? = null): Boolean {
        if (hasAbilityShield(mon)) return true
        if (mon.hasVolatile("gastroacid") && mon.ability !in unsuppressable) return false
        if (mon.ability != "neutralizinggas" && mon.ability !in unsuppressable && isNeutralizingGasActive()) return false
        if (mc != null && mc.user !== mon && mc.move.ignoreAbility && mon.ability !in unsuppressable) {
            val flags = dex.abilityOrNull(mon.ability)?.flags ?: emptySet()
            if ("breakable" in flags) return false
        }
        return true
    }

    fun itemActive(mon: BattleMon): Boolean {
        val item = mon.item ?: return false
        if (mon.hasVolatile("embargo")) return false
        if (field.has("magicroom")) return false
        if (mon.ability == "klutz" && abilityActive(mon) && item !in setOf("machobrace", "poweranklet", "powerband", "powerbelt", "powerbracer", "powerlens", "powerweight")) return false
        return true
    }

    /** Effects attached to a Pokémon, in dispatch order: status, volatiles, ability, item. */
    fun monEffects(mon: BattleMon, mc: MoveCtx? = null, out: MutableList<EffectCtx>) {
        if (mon.fainted && mc == null) return
        mon.status?.let { s -> Effects.condition(s.id)?.let { out.add(EffectCtx(this, it, mon, null, mon.statusState)) } }
        for ((id, st) in mon.volatiles.entries.toList()) Effects.condition(id)?.let { out.add(EffectCtx(this, it, mon, null, st)) }
        if (abilityActive(mon, mc)) Effects.ability(mon.ability)?.let { out.add(EffectCtx(this, it, mon, null, mon.abilityState)) }
        if (itemActive(mon)) Effects.item(mon.item!!)?.let { out.add(EffectCtx(this, it, mon, null, mon.itemState)) }
    }

    fun sideEffects(side: Side, out: MutableList<EffectCtx>) {
        for ((id, st) in side.conditions.entries.toList()) Effects.condition(id)?.let { out.add(EffectCtx(this, it, null, side, st)) }
    }

    fun fieldEffects(out: MutableList<EffectCtx>) {
        field.weather?.let { w -> if (!weatherSuppressed()) Effects.condition(w)?.let { out.add(EffectCtx(this, it, null, null, field.weatherState)) } }
        field.terrain?.let { t -> Effects.condition(t)?.let { out.add(EffectCtx(this, it, null, null, field.terrainState)) } }
        for ((id, st) in field.pseudoWeather.entries.toList()) Effects.condition(id)?.let { out.add(EffectCtx(this, it, null, null, st)) }
    }

    /** All effects relevant to a move interaction between [mc.user] and [mc.target]. */
    fun moveHandlers(mc: MoveCtx): List<EffectCtx> {
        val out = ArrayList<EffectCtx>(16)
        fieldEffects(out)
        sideEffects(mc.user.side, out)
        if (mc.target.side !== mc.user.side) sideEffects(mc.target.side, out)
        monEffects(mc.user, mc, out)
        if (mc.target !== mc.user) monEffects(mc.target, mc, out)
        Effects.move(mc.move.id)?.let { out.add(EffectCtx(this, it, mc.user, null, EffectState())) }
        return out
    }

    /** Effects relevant to something happening to [mon] (its own effects, its side, the field). */
    fun targetHandlers(mon: BattleMon): List<EffectCtx> {
        val out = ArrayList<EffectCtx>(12)
        fieldEffects(out)
        sideEffects(mon.side, out)
        monEffects(mon, null, out)
        return out
    }

    /** Every active effect in the battle (for "any" hooks). */
    fun allHandlers(): List<EffectCtx> {
        val out = ArrayList<EffectCtx>(24)
        fieldEffects(out)
        for (s in sides) sideEffects(s, out)
        for (m in activeMonsBySpeed()) monEffects(m, null, out)
        return out
    }

    fun activeMons(): List<BattleMon> = sides.mapNotNull { it.active }.filter { !it.fainted }

    fun activeMonsBySpeed(): List<BattleMon> {
        val list = activeMons().toMutableList()
        val tr = field.has("trickroom")
        list.sortWith { a, b ->
            val sa = getSpeed(a); val sb = getSpeed(b)
            if (sa != sb) { if (tr) sa.compareTo(sb) else sb.compareTo(sa) } else 0
        }
        if (list.size == 2 && getSpeed(list[0]) == getSpeed(list[1]) && rng.nextBoolean()) list.reverse()
        return list
    }

    fun weatherSuppressed(): Boolean = activeMons().any { (it.ability == "cloudnine" || it.ability == "airlock") && abilityActive(it) }

    /** Effective weather, accounting for Cloud Nine / Air Lock. */
    fun weather(): String? = if (weatherSuppressed()) null else field.weather

    /** Weather as it affects [mon] (Utility Umbrella ignores sun and rain). */
    fun weatherFor(mon: BattleMon): String? {
        val w = weather() ?: return null
        if (mon.item == "utilityumbrella" && itemActive(mon) && w in setOf("sunnyday", "raindance", "desolateland", "primordialsea")) return null
        return w
    }

    fun isSun(mon: BattleMon) = weatherFor(mon).let { it == "sunnyday" || it == "desolateland" }
    fun isRain(mon: BattleMon) = weatherFor(mon).let { it == "raindance" || it == "primordialsea" }
    fun isSand() = weather() == "sandstorm"
    fun isSnow() = weather().let { it == "snowscape" || it == "snow" || it == "hail" }

    // ================================================================= start / requests

    fun start() {
        if (started) return
        started = true
        for (s in sides) {
            val lead = s.mons.firstOrNull { !it.fainted } ?: continue
            for (m in s.mons) m.mon.ensureHp(dex)
            s.active = lead
            lead.isStarted = true
            val text = when (s.controller) {
                Controller.PLAYER -> "Go! ${lead.name}!"
                Controller.WILD -> "A wild ${lead.name} appeared!"
                Controller.AI -> "${s.name} sent out ${lead.name}!"
            }
            events.add(BattleEvent.SwitchIn(s.index, teamIndex(lead), lead.species.id, lead.hp, lead.maxHp, text)); log.add(text)
        }
        // Wild/trainer intro message ordering: foe first is handled by the UI; run switch-in effects by speed.
        for (m in activeMonsBySpeed()) runSwitchInEffects(m)
        updateFacedFoes()
        checkFaints()
    }

    fun request(side: Side): Request {
        if (outcome != BattleOutcome.ONGOING) return Request.Wait
        if (side.index in pendingSwitches) return Request.SwitchIn(true)
        val active = side.active ?: return Request.Wait
        if (choices.containsKey(side.index)) return Request.Wait
        val locked = lockedMove(active)
        val usable = active.moveSlots.map { canSelectMove(active, it) }
        val gimmicks = Gimmick.entries.filter { canUseGimmickNow(active, it) }.toSet()
        return Request.Move(active, usable, canSwitchOut(active), gimmicks, locked)
    }

    /** The move the active Pokémon is forced to use this turn, if any. */
    fun lockedMove(mon: BattleMon): String? {
        mon.volatiles["mustrecharge"]?.let { return "recharge" }
        mon.volatiles["twoturnmove"]?.let { return it.moveId }
        mon.volatiles["lockedmove"]?.let { return it.moveId }
        mon.volatiles["rollout"]?.let { return it.moveId }
        mon.volatiles["uproar"]?.let { return "uproar" }
        mon.volatiles["bide"]?.let { return "bide" }
        return null
    }

    fun canSelectMove(mon: BattleMon, slot: MoveSlot): Boolean {
        if (slot.pp <= 0) return false
        if (isMoveDisabled(mon, slot.id)) return false
        return true
    }

    fun isMoveDisabled(mon: BattleMon, moveId: String): Boolean {
        for (c in targetHandlers(mon)) if (c.holder === mon && c.effect.onDisableMove(c, moveId)) return true
        // Imprison: the foe that used it seals every move it also knows.
        val foe = mon.side.foe.active
        if (foe != null && !foe.fainted && foe.hasVolatile("imprison") && foe.moveSlots.any { it.id == moveId } && moveId != "struggle") return true
        return false
    }

    fun canSwitchOut(mon: BattleMon): Boolean {
        if (mon.side.canSwitchTo().isEmpty()) return false
        if (mon.item == "shedshell" && itemActive(mon)) return true
        if (mon.hasType("Ghost")) return true
        if (lockedMove(mon) != null && lockedMove(mon) != "uproar") return false
        for (c in allHandlers()) if (c.effect.onTrapPokemon(c, mon)) return false
        return true
    }

    fun choose(side: Side, choice: Choice): String? {
        if (outcome != BattleOutcome.ONGOING) return "Battle is over"
        if (side.index in pendingSwitches) {
            if (choice !is Choice.Switch) return "Must switch"
            val target = side.mons.getOrNull(choice.teamIndex) ?: return "Invalid Pokémon"
            if (target.fainted || target === side.active) return "Cannot switch to that Pokémon"
            pendingSwitches.remove(side.index)
            performSwitch(side, target, forced = true)
            if (pendingSwitches.isEmpty()) afterReplacements()
            return null
        }
        val active = side.active ?: return "No active Pokémon"
        when (choice) {
            is Choice.UseMove -> {
                if (lockedMove(active) == null) {
                    val usableAny = active.moveSlots.any { canSelectMove(active, it) }
                    if (usableAny) {
                        val slot = active.moveSlots.getOrNull(choice.moveIndex) ?: return "Invalid move"
                        if (!canSelectMove(active, slot)) return "${slot.id} cannot be used"
                    }
                }
                if (choice.gimmick != null && !canUseGimmickNow(active, choice.gimmick)) return "Cannot use ${choice.gimmick}"
            }
            is Choice.Switch -> {
                val target = side.mons.getOrNull(choice.teamIndex) ?: return "Invalid Pokémon"
                if (target.fainted || target === active) return "Cannot switch to that Pokémon"
                if (!canSwitchOut(active)) return "${active.name} can't be switched out!"
            }
            is Choice.Run -> if (!side.foe.isWild || !rules.canRun) return "No! There's no running from a trainer battle!"
            is Choice.UseItem -> if (!rules.canUseItems) return "Items can't be used now"
            Choice.Pass -> {}
        }
        choices[side.index] = choice
        return null
    }

    /** Fill in AI choices and run the turn once every side has chosen. Returns true if a turn was executed. */
    fun advance(ai: BattleAI = BattleAI()): Boolean {
        if (outcome != BattleOutcome.ONGOING) return false
        for (s in sides) if (!s.isPlayer) {
            if (s.index in pendingSwitches) {
                val pick = ai.chooseReplacement(this, s)
                choose(s, Choice.Switch(pick))
            } else if (!choices.containsKey(s.index) && pendingSwitches.isEmpty()) {
                val c = ai.chooseAction(this, s)
                if (choose(s, c) != null) choose(s, Choice.UseMove(0))
            }
        }
        if (pendingSwitches.isNotEmpty()) return false
        if (choices.size < sides.count { it.active != null }) return false
        runTurn()
        return true
    }

    // ================================================================= turn resolution

    private class Action(val kind: Int, val side: Side, val mon: BattleMon, val choice: Choice, var priority: Int = 0,
                         var fractional: Double = 0.0, var speed: Int = 0, val tie: Long = 0)

    private fun runTurn() {
        turn++
        events.add(BattleEvent.TurnStart(turn))
        lastSuccessfulMoveThisTurn = null
        for (s in sides) { s.faintedLastTurn = s.faintedThisTurn; s.faintedThisTurn = false }
        for (m in activeMons()) {
            m.movedThisTurn = false; m.hurtThisTurn = false; m.statsRaisedThisTurn = false; m.statsLoweredThisTurn = false
            m.volatiles.remove("protect"); m.volatiles.remove("endure")
        }
        val actions = ArrayList<Action>()
        for (s in sides) {
            val c = choices[s.index] ?: continue
            val mon = s.active ?: continue
            val kind = when (c) { Choice.Run -> 0; is Choice.UseItem -> 1; is Choice.Switch -> 2; else -> 4 }
            actions.add(Action(kind, s, mon, c, tie = rng.nextLong()))
            if (c is Choice.UseMove && c.gimmick != null && c.gimmick != Gimmick.ZMOVE) actions.add(Action(3, s, mon, c, tie = rng.nextLong()))
        }
        choices.clear()
        // Gimmick transformations happen first so that priority (e.g. Mega abilities) and speed use the new forme.
        for (a in actions.filter { it.kind == 3 }.sortedByDescending { getSpeed(it.mon) }) {
            performGimmick(a.mon, (a.choice as Choice.UseMove).gimmick!!)
        }
        for (a in actions) {
            a.speed = getSpeed(a.mon)
            if (a.kind == 4) {
                val moveId = resolveChosenMoveId(a.mon, a.choice)
                val move = dex.moveOrNull(moveId)
                if (move != null) {
                    val am = ActiveMove(move)
                    var p = move.priority
                    if ((a.choice as? Choice.UseMove)?.gimmick == Gimmick.ZMOVE || a.mon.isDynamaxed) p = if (move.isStatus) p else move.priority
                    for (c in moveHandlers(MoveCtx(a.mon, a.mon.side.foe.active ?: a.mon, am))) if (c.holder === a.mon) {
                        p = c.effect.onModifyPriority(c, am, a.mon, p)
                        a.fractional += c.effect.onFractionalPriority(c, am, a.mon)
                    }
                    a.priority = p
                    a.mon.volatiles["pendingmove"] = EffectState().also { it.moveId = moveId; it.counter = p }
                }
                if (lockedMove(a.mon) == null) beforeTurnMoves.add(a.mon to moveId)
            }
        }
        for ((mon, moveId) in beforeTurnMoves) if (!mon.fainted) Effects.move(moveId)?.onBeforeTurn(this, mon)
        beforeTurnMoves.clear()
        val tr = field.has("trickroom")
        val ordered = actions.filter { it.kind != 3 }.sortedWith(Comparator { x, y ->
            if (x.kind != y.kind) return@Comparator x.kind.compareTo(y.kind)
            if (x.priority != y.priority) return@Comparator y.priority.compareTo(x.priority)
            if (x.fractional != y.fractional) return@Comparator y.fractional.compareTo(x.fractional)
            if (x.speed != y.speed) return@Comparator if (tr && x.kind == 4) x.speed.compareTo(y.speed) else y.speed.compareTo(x.speed)
            x.tie.compareTo(y.tie)
        })
        for (a in ordered) {
            if (outcome != BattleOutcome.ONGOING) break
            if (a.side.active !== a.mon && a.kind != 2) continue
            if (a.mon.fainted && a.kind != 2) continue
            currentAction = a
            when (val c = a.choice) {
                Choice.Run -> attemptRun(a.side)
                is Choice.UseItem -> useBagItem(a.side, c)
                is Choice.Switch -> if (a.side.active?.fainted != true) {
                    val target = a.side.mons[c.teamIndex]
                    if (!target.fainted && target !== a.side.active) performSwitch(a.side, target, forced = false)
                }
                is Choice.UseMove, Choice.Pass -> runMoveAction(a.mon, a.choice)
            }
            checkFaints()
            runUpdate()
            checkFaints()
            if (checkWin()) break
            // Self-switch moves (U-turn, Volt Switch) and forced switches (Roar) resolve immediately.
            for (s in sides) resolveSwitchFlags(s)
            if (checkWin()) break
        }
        currentAction = null
        for (s in sides) for (m in s.mons) m.volatiles.remove("pendingmove")
        if (outcome == BattleOutcome.ONGOING) {
            residualPhase()
            checkFaints()
            checkWin()
        }
        if (outcome == BattleOutcome.ONGOING) requestReplacements()
    }

    private val beforeTurnMoves = ArrayList<Pair<BattleMon, String>>()

    private fun resolveChosenMoveId(mon: BattleMon, choice: Choice): String {
        lockedMove(mon)?.let { return if (it == "recharge") "recharge" else it }
        if (choice !is Choice.UseMove) return "struggle"
        if (mon.moveSlots.none { canSelectMove(mon, it) }) return "struggle"
        return mon.moveSlots.getOrNull(choice.moveIndex)?.id ?: "struggle"
    }

    private fun requestReplacements() {
        for (s in sides) {
            val a = s.active
            if (a != null && a.fainted && s.canSwitchTo().isNotEmpty()) pendingSwitches.add(s.index)
        }
    }

    private fun afterReplacements() {
        checkFaints()
        checkWin()
        if (outcome == BattleOutcome.ONGOING) requestReplacements()
    }

    private fun resolveSwitchFlags(s: Side) {
        val a = s.active ?: return
        if (a.switchFlag != null && !a.fainted) {
            val options = s.canSwitchTo()
            val flag = a.switchFlag
            a.switchFlag = null
            if (options.isNotEmpty()) {
                val next = if (s.isPlayer) options.first() else BattleAI().chooseReplacementMon(this, s)
                // The player's choice of replacement is simplified to the AI's pick for self-switching moves
                // only when no UI is attached; the UI layer calls [selfSwitchTarget] beforehand.
                val chosen = selfSwitchOverride.remove(s.index)?.let { s.mons[it] }?.takeIf { !it.fainted && it !== a } ?: next
                performSwitch(s, chosen, forced = false, batonPass = flag == "copyvolatile", shedTail = flag == "shedtail")
            }
        }
        if (a.forceSwitchFlag) {
            a.forceSwitchFlag = false
            val options = s.canSwitchTo()
            if (options.isNotEmpty() && !a.fainted) {
                performSwitch(s, options[rng.nextInt(options.size)], forced = true, isDrag = true)
            }
        }
    }

    /** UI hook: the team index the player wants to switch to after a self-switching move this turn. */
    val selfSwitchOverride = HashMap<Int, Int>()

    // ================================================================= switching

    fun performSwitch(side: Side, incoming: BattleMon, forced: Boolean, isDrag: Boolean = false, batonPass: Boolean = false, shedTail: Boolean = false) {
        val out = side.active
        var passedBoosts: IntArray? = null
        var passedVolatiles: Map<String, EffectState>? = null
        var subHp = 0
        if (out != null && !out.fainted) {
            if (batonPass) {
                passedBoosts = out.boosts.copyOf()
                passedVolatiles = out.volatiles.filterKeys { it in BATON_PASSABLE }
            }
            if (shedTail) subHp = out.volatiles["substitute"]?.hp ?: 0
            for (c in targetHandlers(out)) if (c.holder === out) c.effect.onSwitchOut(c)
            val text = when (side.controller) {
                Controller.PLAYER -> "${out.name}, come back!"
                else -> "${side.name} withdrew ${out.name}!"
            }
            if (!isDrag) { events.add(BattleEvent.SwitchOut(side.index, teamIndex(out), text)); log.add(text) }
        }
        if (out != null) clearOnSwitchOut(out)
        side.active = incoming
        incoming.isStarted = true
        incoming.activeTurns = 0
        incoming.activeMoveActions = 0
        val text = when {
            isDrag -> "${nm(incoming)} was dragged out!"
            side.controller == Controller.PLAYER -> "Go! ${incoming.name}!"
            else -> "${side.name} sent out ${incoming.name}!"
        }
        events.add(BattleEvent.SwitchIn(side.index, teamIndex(incoming), incoming.species.id, incoming.hp, incoming.maxHp, text)); log.add(text)
        if (passedBoosts != null) {
            passedBoosts.copyInto(incoming.boosts)
            passedVolatiles?.forEach { (k, v) -> incoming.volatiles[k] = v }
        }
        if (shedTail && subHp > 0) incoming.volatiles["substitute"] = EffectState().also { it.hp = subHp }
        updateFacedFoes()
        runSwitchInEffects(incoming)
    }

    private fun clearOnSwitchOut(mon: BattleMon) {
        if (mon.isDynamaxed) endDynamax(mon, silent = true)
        mon.volatiles.clear()
        mon.boosts.fill(0)
        mon.addedType = null
        mon.types = mon.species.types
        if (mon.status == MajorStatus.TOXIC) mon.statusState.counter = 0
        // Battle formes that revert on switching (Mega formes persist; e.g. Aegislash, Darmanitan-Zen, Mimikyu keep data-driven rules).
        val sp = mon.species
        if (sp.formKind == FormKind.BATTLE_ONLY && sp.id !in PERSISTENT_BATTLE_FORMES) {
            val back = mon.preTransformSpecies ?: dex.species(mon.originalSpecies)
            mon.setSpecies(back, changeAbility = true)
            mon.preTransformSpecies = null
        }
        if (mon.transformed) {
            mon.transformed = false
            mon.moveSlots = mon.mon.moves
            mon.setSpecies(dex.species(mon.mon.speciesId).let { if (mon.species.formKind == FormKind.MEGA) mon.species else it })
        }
        mon.ability = mon.baseAbility.takeIf { mon.species.formKind != FormKind.MEGA } ?: mon.ability
        mon.abilityState = EffectState()
        mon.lastMove = null
        mon.switchFlag = null
        mon.forceSwitchFlag = false
    }

    fun runSwitchInEffects(mon: BattleMon) {
        if (mon.fainted) return
        // Hazards and side conditions.
        val handlers = ArrayList<EffectCtx>()
        sideEffects(mon.side, handlers)
        for (c in handlers) { c.effect.onSwitchIn(EffectCtx(this, c.effect, mon, c.side, c.state)); if (mon.fainted) return }
        // Slot conditions (Healing Wish, Lunar Dance).
        for ((id, st) in mon.side.slotConditions.entries.toList()) Effects.condition(id)?.onSwitchIn(EffectCtx(this, Effects.condition(id)!!, mon, mon.side, st))
        if (mon.fainted) return
        checkPrimalReversion(mon)
        // Ability and item start effects.
        if (abilityActive(mon)) Effects.ability(mon.ability)?.let { it.onStart(EffectCtx(this, it, mon, null, mon.abilityState), null) }
        if (itemActive(mon)) Effects.item(mon.item!!)?.let { it.onStart(EffectCtx(this, it, mon, null, mon.itemState), null) }
        for (c in allHandlers()) if (c.holder !== mon) c.effect.onAnySwitchIn(c, mon)
        runUpdate()
    }

    private fun updateFacedFoes() {
        val p = player.active ?: return
        val o = opponent.active ?: return
        if (!p.fainted) p.facedFoes.add(o.mon.uid)
    }

    // ================================================================= moves

    private fun runMoveAction(user: BattleMon, choice: Choice) {
        var moveId = resolveChosenMoveId(user, choice)
        // Encore replaces the move chosen this turn by the encored move (Showdown's onOverrideAction).
        user.volatiles["encore"]?.moveId?.let { enc ->
            if (moveId != enc && moveId != "struggle" && lockedMove(user) == null && (user.moveSlot(enc)?.pp ?: 0) > 0) moveId = enc
        }
        if (moveId == "recharge") {
            user.volatiles.remove("mustrecharge")
            message("${nm(user)} must recharge!")
            user.movedThisTurn = true
            return
        }
        val gimmick = (choice as? Choice.UseMove)?.gimmick
        val locked = lockedMove(user) != null
        val slot = user.moveSlot(moveId)
        val base = dex.moveOrNull(moveId) ?: dex.move("struggle")
        var move = ActiveMove(base)
        if (gimmick == Gimmick.ZMOVE) {
            val z = Gimmicks.zMoveFor(this, user, base)
            if (z != null) { move = z; user.side.usedGimmicks.add(Gimmick.ZMOVE) }
        } else if (user.isDynamaxed && base.id != "struggle") {
            move = Gimmicks.maxMoveFor(this, user, base)
        }
        user.volatiles["pendingmove"]?.let { move.priority = it.counter }
        user.movedThisTurn = true
        user.activeMoveActions++
        val target = defaultTarget(user, move)
        val mc = MoveCtx(user, target, move)
        // Before-move checks (sleep, freeze, paralysis, flinch, confusion, Taunt at execution...).
        for (c in moveHandlers(mc)) if (c.holder === user && !c.effect.onBeforeMove(c, mc)) {
            user.lastMoveFailed = true
            user.volatiles.remove("twoturnmove"); user.volatiles.remove("lockedmove"); user.volatiles.remove("rollout")
            return
        }
        if (user.fainted) return
        if (!locked && slot != null && moveId != "struggle" && !move.isZ) {
            if (isMoveDisabled(user, moveId) && !user.isDynamaxed) {
                message("${nm(user)} can't use ${base.name}!")
                return
            }
            var cost = 1
            val foe = user.side.foe.active
            if (foe != null && !foe.fainted && foe.ability == "pressure" && abilityActive(foe) && move.target != "self") cost = 2
            slot.pp = max(0, slot.pp - cost)
        } else if (move.isZ && slot != null) {
            slot.pp = max(0, slot.pp - 1)
        }
        if (move.isZ) {
            message("${nm(user)} surrounded itself with its Z-Power!")
            events.add(BattleEvent.Animation(user.side.index, "zpower"))
            if (move.isStatus) Gimmicks.applyZStatusEffect(this, user, base)
        }
        useMove(mc)
        if (base.id != "struggle" && !move.isZ && !move.isMax) user.lastMove = base.id
        else if (move.isMax) user.lastMove = base.id
        lastMoveUsed = base.id
        user.mon.counters["use:${base.id}"] = (user.mon.counters["use:${base.id}"] ?: 0) + 1
    }

    fun defaultTarget(user: BattleMon, move: ActiveMove): BattleMon = when (move.target) {
        "self", "adjacentAllyOrSelf", "allies", "allySide", "allyTeam", "adjacentAlly" -> user
        else -> user.side.foe.active ?: user
    }

    fun targetsSelf(move: ActiveMove) = move.target in setOf("self", "adjacentAllyOrSelf", "allies", "allySide", "allyTeam", "adjacentAlly")
    fun isFieldMove(move: ActiveMove) = move.target in setOf("all", "allySide", "foeSide", "allyTeam")

    /**
     * Executes a move (also used by moves that call other moves). Returns true if it had an effect.
     */
    fun useMove(mc: MoveCtx, calledBy: String? = null): Boolean {
        val user = mc.user
        val move = mc.move
        val script = Effects.move(move.id)
        // Modify type/move (abilities like Pixilate, items like plates for Judgment, Skill Link...).
        for (c in moveHandlers(mc)) if (c.holder === user || c.effect === script) { c.effect.onModifyType(c, mc) }
        for (c in moveHandlers(mc)) if (c.holder === user || c.effect === script) { c.effect.onModifyMove(c, mc) }
        // Two-turn moves.
        if (move.hasFlag("charge") && !user.hasVolatile("twoturnmove") && calledBy == null) {
            val skip = script?.skipCharge(this, user) == true
            if (!skip) {
                val text = script?.chargeMessage?.replace("{user}", nm(user)) ?: "${nm(user)} is charging up!"
                events.add(BattleEvent.MoveUsed(user.side.index, move.id, move.type, move.category, "${nm(user)} used ${move.name}!", mc.target.side.index))
                message(text)
            }
            script?.onChargeTurn(this, mc)
            if (!skip) {
                addVolatile(user, "twoturnmove", user, move.id)?.moveId = move.id
                if (user.item == "powerherb" && itemActive(user)) {
                    message("${nm(user)} became fully charged due to its Power Herb!")
                    consumeItem(user)
                    user.volatiles.remove("twoturnmove")
                } else {
                    script?.onAfterUse(this, mc, false)
                    return true
                }
            }
        }
        user.volatiles.remove("twoturnmove")
        val text = if (calledBy != null) "${nm(user)} used ${move.name}!" else "${nm(user)} used ${move.name}!"
        events.add(BattleEvent.MoveUsed(user.side.index, move.id, move.type, move.category, text, mc.target.side.index)); log.add(text)

        if (move.id == "struggle" || move.data.struggleRecoil) {
            // Struggle is typeless and always hits.
            move.type = "???"
        }

        val hit: Boolean = when {
            isFieldMove(move) -> runFieldMove(mc, script)
            targetsSelf(move) -> runSelfMove(mc, script)
            else -> runTargetedMove(mc, script)
        }
        user.lastMoveFailed = !hit
        user.lastMoveTarget = mc.target
        if (hit) lastSuccessfulMoveThisTurn = move.id
        for (c in moveHandlers(mc)) if (c.holder === user) c.effect.onAfterMove(c, mc)
        script?.onAfterUse(this, mc, hit)
        if (move.data.selfdestruct == "always" && !user.fainted) {
            directDamage(user, user.hp, user, move.id, silent = true)
        }
        return hit
    }

    private fun fail(): Boolean { message("But it failed!"); return false }

    private fun runFieldMove(mc: MoveCtx, script: MoveScript?): Boolean {
        if (mc.move.target == "foeSide") {
            val foe = mc.user.side.foe.active
            if (foe != null && reflects(mc, foe)) { reflectMove(MoveCtx(mc.user, foe, mc.move), foe); return true }
        }
        script?.onUseStatus(this, mc)?.let { return it || fail().let { false } }
        val m = mc.move.data
        var ok = false
        m.weather?.let { ok = setWeather(it, mc.user) || ok }
        m.terrain?.let { ok = setTerrain(it, mc.user) || ok }
        m.pseudoWeather?.let { ok = addPseudoWeather(it, mc.user) || ok }
        m.sideCondition?.let {
            val side = if (mc.move.target == "foeSide") mc.user.side.foe else mc.user.side
            ok = addSideCondition(side, it, mc.user) || ok
        }
        if (m.boosts.isNotEmpty() && mc.move.target == "allySide") ok = boost(mc.user, m.boosts, mc.user, mc.move.id) || ok
        if (!ok) fail()
        return ok
    }

    private fun runSelfMove(mc: MoveCtx, script: MoveScript?): Boolean {
        val user = mc.user
        // Moves aimed only at an ally have no target in a single battle.
        if (mc.move.target == "adjacentAlly") { message("But there was no target..."); return false }
        val mcSelf = MoveCtx(user, user, mc.move)
        if (script != null && !script.onTry(this, mcSelf)) return fail()
        for (c in moveHandlers(mcSelf)) if (!c.effect.onTryMove(c, mcSelf)) return fail().let { false }
        script?.onUseStatus(this, mcSelf)?.let { r -> if (!r) fail(); return r }
        return applyStatusMoveEffects(mcSelf, primary = true).also { if (!it) fail() }
    }

    private fun runTargetedMove(mc: MoveCtx, script: MoveScript?): Boolean {
        val user = mc.user
        val target = mc.target
        val move = mc.move
        if (target.fainted || !target.isActive) { message("But there was no target..."); return false }
        if (script != null && !script.onTry(this, mc)) return fail()
        for (c in moveHandlers(mc)) if (!c.effect.onTryMove(c, mc)) { if (!mc.move.data2.containsKey("silentFail")) fail(); return false }
        // Semi-invulnerability and accuracy overrides.
        var accOverride: Boolean? = null
        for (c in moveHandlers(mc)) c.effect.onAccuracyOverride(c, mc)?.let { r -> if (accOverride != true) accOverride = r }
        if (accOverride == false) { message("${nm(target)} avoided the attack!"); onMiss(mc); return false }
        // Feint-style moves lift the target's protection before it is checked.
        if (move.data.breaksProtect) breakProtection(mc)
        // Protect, absorbing abilities, Magic Bounce etc.
        for (c in moveHandlers(mc)) if (!c.effect.onTryHit(c, mc)) { onMiss(mc, silent = true); return false }
        if (target.fainted) return false
        // Type immunity.
        if (!move.isStatus || move.id in TYPE_CHECKED_STATUS) {
            if (isImmune(mc, move.type)) {
                message("It doesn't affect ${nmL(target)}...")
                onMiss(mc, silent = true)
                return false
            }
        }
        if (move.data.ohko != null) {
            if (target.level > user.level || target.isDynamaxed || (move.data.ohko == "Ice" && target.hasType("Ice"))) { message("It doesn't affect ${nmL(target)}..."); return false }
        }
        if (move.hasFlag("powder") && (target.hasType("Grass") || (target.ability == "overcoat" && abilityActive(target, mc)) || (target.item == "safetygoggles" && itemActive(target)))) {
            message("It doesn't affect ${nmL(target)}...")
            return false
        }
        if (move.hasFlag("protect") && user.ability == "prankster" && move.isStatus && mc.move.data2["prankster"] == true && target.hasType("Dark") && target.side !== user.side) {
            message("It doesn't affect ${nmL(target)}...")
            return false
        }
        // Accuracy.
        if (accOverride != true && !accuracyCheck(mc)) {
            message(if (move.data.ohko != null) "${nm(user)}'s attack missed!" else "${nm(target)} avoided the attack!")
            onMiss(mc)
            return false
        }
        if (move.isStatus) {
            // A substitute blocks every status move aimed at its owner unless the move bypasses it (sound moves, Infiltrator...).
            if (target.hasVolatile("substitute") && !move.hasFlag("bypasssub") && !move.infiltrates && target !== user && move.data.target != "self") return fail()
            script?.onUseStatus(this, mc)?.let { r -> if (!r) fail(); return r }
            return applyStatusMoveEffects(mc, primary = true).also { if (!it) fail() }
        }
        return runDamagingHits(mc, script)
    }

    private val protectVolatiles = listOf("protect", "kingsshield", "spikyshield", "banefulbunker", "obstruct", "silktrap", "burningbulwark", "maxguard")

    private fun breakProtection(mc: MoveCtx) {
        var broke = false
        for (id in protectVolatiles) if (mc.target.volatiles.remove(id) != null) broke = true
        if (mc.target.isDynamaxed && mc.move.id != "feint") return
        for (id in listOf("quickguard", "wideguard", "craftyshield", "matblock")) if (mc.target.side.conditions.remove(id) != null) broke = true
        if (broke) message("${nm(mc.target)} fell for the feint!")
    }

    /** Whether [target] reflects [mc] back (Magic Bounce, Magic Coat). */
    fun reflects(mc: MoveCtx, target: BattleMon): Boolean {
        if (!mc.move.hasFlag("reflectable") || mc.move.data2["bounced"] == true || target === mc.user || target.fainted) return false
        if (target.hasVolatile("magiccoat")) return true
        val probe = MoveCtx(mc.user, target, mc.move)
        return target.ability == "magicbounce" && abilityActive(target, probe) && !target.hasVolatile("twoturnmove")
    }

    /** Uses a reflected copy of [mc]'s move from [reflector] back at the original user. */
    fun reflectMove(mc: MoveCtx, reflector: BattleMon) {
        if (reflector.ability == "magicbounce" && !reflector.hasVolatile("magiccoat")) message("[${nm(reflector)}'s Magic Bounce]")
        message("${nm(reflector)} bounced the ${mc.move.name} back!")
        val bounced = ActiveMove(mc.move.data).also { it.data2["bounced"] = true }
        val nmc = MoveCtx(reflector, mc.user, bounced)
        val script = Effects.move(bounced.id)
        if (isFieldMove(bounced)) { runFieldMove(nmc, script); return }
        val t = nmc.target
        if (t.fainted || !t.isActive) return
        if (t.hasVolatile("substitute") && !bounced.hasFlag("bypasssub")) { message("But it failed!"); return }
        // A reflected move can't be reflected again, but the original user's own immunities still apply.
        for (c in moveHandlers(nmc)) if (c.holder === t && c.effect.kind == EffectKind.ABILITY && !c.effect.onTryHit(c, nmc)) return
        val r = script?.onUseStatus(this, nmc) ?: applyStatusMoveEffects(nmc, primary = true)
        if (!r) message("But it failed!")
    }

    private fun onMiss(mc: MoveCtx, silent: Boolean = false) {
        if (mc.move.data.hasCrashDamage && !mc.user.fainted) {
            message("${nm(mc.user)} kept going and crashed!")
            directDamage(mc.user, mc.user.baseMaxHp / 2, mc.user, "crash")
        }
        if (mc.move.data.selfdestruct == "ifHit") { /* no self-KO on miss */ }
        mc.user.volatiles.remove("lockedmove")
        mc.user.volatiles.remove("rollout")
    }

    fun isImmune(mc: MoveCtx, type: String): Boolean {
        val target = mc.target
        if (mc.move.isStatus && mc.move.id !in TYPE_CHECKED_STATUS) return false
        var override: Boolean? = null
        for (c in moveHandlers(mc)) c.effect.onImmunity(c, mc, type)?.let { r -> override = if (override == true) true else r }
        override?.let { return it }
        val ignore = mc.move.data.ignoreImmunity
        if (ignore == "true" || ignore?.split(',')?.contains(type) == true) return false
        if (type == "Ground" && !isGrounded(target)) return true
        if (type == "???") return false
        for (t in target.effectiveTypes()) if (dex.typeChart.effectiveness(type, t) == 0.0) {
            if (target.hasItem("ringtarget") && itemActive(target)) continue
            if (t == "Ghost" && (type == "Normal" || type == "Fighting") && (target.hasVolatile("foresight") || target.hasVolatile("odorsleuth"))) continue
            if (t == "Dark" && type == "Psychic" && target.hasVolatile("miracleeye")) continue
            if (t == "Flying" && type == "Ground" && isGrounded(target)) continue
            return true
        }
        return false
    }

    fun isGrounded(mon: BattleMon): Boolean {
        if (field.has("gravity")) return true
        if (mon.hasVolatile("ingrain") || mon.hasVolatile("smackdown")) return true
        if (mon.item == "ironball" && itemActive(mon)) return true
        for (c in targetHandlers(mon)) if (c.holder === mon || c.holder == null) c.effect.onGrounded(c, mon)?.let { return it }
        if (mon.hasType("Flying") && !mon.hasVolatile("roost")) return false
        if (mon.ability == "levitate" && abilityActive(mon)) return false
        if (mon.item == "airballoon" && itemActive(mon)) return false
        if (mon.hasVolatile("magnetrise") || mon.hasVolatile("telekinesis")) return false
        return true
    }

    private fun accuracyCheck(mc: MoveCtx): Boolean {
        val move = mc.move
        var acc = move.accuracy
        if (move.data.ohko != null) {
            acc = 30 + mc.user.level - mc.target.level
            if (move.data.ohko == "Ice" && !mc.user.hasType("Ice")) acc = 20 + mc.user.level - mc.target.level
            return rng.nextInt(100) < acc
        }
        if (acc <= 0) return true
        if (move.id in setOf("thunder", "hurricane", "bleakwindstorm", "wildboltstorm", "sandsearstorm") && isRain(mc.target)) return true
        if (move.id == "blizzard" && isSnow()) return true
        if (move.id in setOf("thunder", "hurricane") && isSun(mc.target)) acc = 50
        if (move.id == "toxic" && mc.user.hasType("Poison")) return true
        if (move.isMax || move.isZ) return true
        // Stage-based accuracy/evasion.
        val ignoreEva = move.data.ignoreEvasion || (mc.user.ability == "keeneye" && abilityActive(mc.user)) || (mc.user.ability == "mindseye" && abilityActive(mc.user)) ||
            mc.target.hasVolatile("foresight") || mc.target.hasVolatile("miracleeye") || (mc.user.ability == "unaware" && abilityActive(mc.user))
        val accStage = if (mc.target.ability == "unaware" && abilityActive(mc.target, mc)) 0 else mc.user.boost("accuracy")
        val evaStage = if (ignoreEva) 0 else mc.target.boost("evasion")
        val stage = (accStage - evaStage).coerceIn(-6, 6)
        val stageMult = if (stage >= 0) (3.0 + stage) / 3.0 else 3.0 / (3.0 - stage)
        val mod = Mod()
        for (c in moveHandlers(mc)) c.effect.onModifyAccuracy(c, mc, mod)
        val finalAcc = mod.apply(floor(acc * stageMult).toInt())
        return rng.nextInt(100) < finalAcc
    }

    private fun runDamagingHits(mc: MoveCtx, script: MoveScript?): Boolean {
        val user = mc.user
        val target = mc.target
        val move = mc.move
        var hits = script?.hitCount(this, mc) ?: move.multihit?.let { mh ->
            if (mh[0] == mh[1]) mh[0] else {
                if (user.ability == "skilllink" && abilityActive(user)) mh[1]
                else if (mh[0] == 2 && mh[1] == 5) {
                    if (user.item == "loadeddice" && itemActive(user)) 4 + rng.nextInt(2)
                    else listOf(2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 5, 5, 5)[rng.nextInt(20)]
                } else mh[0] + rng.nextInt(mh[1] - mh[0] + 1)
            }
        } ?: 1
        if (move.multihit == null && user.ability == "parentalbond" && abilityActive(user) && !move.isZ && !move.isMax && move.id !in setOf("fling", "selfdestruct", "explosion", "finalgambit", "endeavor", "uproar", "rollout", "iceball")) {
            hits = 2; move.data2["parentalbond"] = true
        }
        var hitCount = 0
        for (i in 0 until hits) {
            if (target.fainted || user.fainted) break
            if (i > 0 && move.data.multiaccuracy && !accuracyCheck(mc)) break
            mc.hitIndex = i
            move.hitCount = i + 1
            mc.crit = false
            mc.substituteHit = false
            val dmg = calcDamage(mc)
            if (dmg == null) { if (i == 0) return fail(); break }
            hitCount++
            var dealt: Int
            if (target.hasVolatile("substitute") && !move.hasFlag("bypasssub") && !move.infiltrates && user !== target) {
                val sub = target.volatiles.getValue("substitute")
                dealt = min(dmg, sub.hp)
                sub.hp -= dealt
                mc.substituteHit = true
                if (mc.crit) message("A critical hit!")
                message("The substitute took damage for ${nmL(target)}!")
                if (sub.hp <= 0) {
                    removeVolatile(target, "substitute")
                    message("${nm(target)}'s substitute faded!")
                }
            } else {
                dealt = applyMoveDamage(mc, dmg)
                if (mc.crit) { message("A critical hit!"); user.critsThisBattle++; user.mon.counters["crits"] = user.critsThisBattle }
            }
            move.totalDamage += dealt
            mc.damage = dealt
            if (hits == 1 || i == 0) effectivenessMessage(mc)
            // Drain.
            move.data.drain?.let { d ->
                if (dealt > 0 && !user.fainted) {
                    var amt = max(1, Mod.pokeRound(dealt.toDouble() * d[0] / d[1]))
                    if (user.item == "bigroot" && itemActive(user)) amt = Mod.pokeRound(amt * 1.3)
                    if (target.ability == "liquidooze" && abilityActive(target)) {
                        directDamage(user, amt, target, "liquidooze"); message("${nm(user)} sucked up the liquid ooze!")
                    } else if (heal(user, amt, "drain") > 0) message("${nm(target)} had its energy drained!")
                }
            }
            if (!mc.substituteHit) {
                target.timesAttacked++
                for (c in moveHandlers(mc)) c.effect.onDamagingHit(c, mc, dealt)
            }
            script?.onHit(this, mc)
            // Secondary effects for this hit.
            if (!target.fainted || move.secondaries.any { it.self != null }) applySecondaries(mc)
            runUpdate()
        }
        if (hitCount == 0) return false
        if (hits > 1 && hitCount > 0 && move.multihit != null) message("The Pokémon was hit $hitCount time${if (hitCount == 1) "" else "s"}!")
        // Primary non-damaging effects of damaging moves.
        if (!user.fainted) {
            move.data.self?.let { s ->
                if (s.boosts.isNotEmpty() && (move.data2["parentalbond"] != true || true)) boost(user, s.boosts, user, move.id)
                s.volatileStatus?.let { addVolatile(user, it, user, move.id) }
            }
            if (move.data.recoil != null && move.totalDamage > 0 && !(user.ability == "rockhead" && abilityActive(user)) && !(user.ability == "magicguard" && abilityActive(user))) {
                val r = move.data.recoil
                val amt = max(1, Mod.pokeRound(move.totalDamage.toDouble() * r[0] / r[1]))
                directDamage(user, amt, user, "recoil")
                message("${nm(user)} was damaged by the recoil!")
                user.mon.counters["recoil"] = (user.mon.counters["recoil"] ?: 0) + amt
            }
            if (move.data.struggleRecoil) {
                directDamage(user, max(1, Mod.pokeRound(user.baseMaxHp / 4.0)), user, "recoil")
                message("${nm(user)} was damaged by the recoil!")
            }
            if (move.data.mindBlownRecoil && !(user.ability == "magicguard" && abilityActive(user))) {
                directDamage(user, (user.baseMaxHp + 1) / 2, user, "mindblown")
            }
        }
        if (move.data.selfdestruct == "ifHit" && !user.fainted) directDamage(user, user.hp, user, move.id, silent = true)
        if (move.data.status != null && !target.fainted && !mc.substituteHit) trySetStatus(target, MajorStatus.byId(move.data.status)!!, user, move.id, primary = false)
        if (move.data.volatileStatus != null && !target.fainted && !mc.substituteHit && move.data.volatileStatus != "partiallytrapped") addVolatile(target, move.data.volatileStatus, user, move.id)
        if (move.data.volatileStatus == "partiallytrapped" && !target.fainted && !mc.substituteHit) addVolatile(target, "partiallytrapped", user, move.id)?.moveId = move.id
        for (c in moveHandlers(mc)) c.effect.onAfterMoveSecondary(c, mc)
        if (move.data.selfSwitch != null && !user.fainted && user.side.canSwitchTo().isNotEmpty()) user.switchFlag = move.data.selfSwitch
        if (move.data.forceSwitch && !target.fainted && !mc.substituteHit && target.side.canSwitchTo().isNotEmpty() && canDrag(target)) target.forceSwitchFlag = true
        return true
    }

    fun canDrag(mon: BattleMon): Boolean {
        if (mon.isDynamaxed) return false
        for (c in targetHandlers(mon)) if (!c.effect.onDragOut(c, mon)) return false
        return true
    }

    private fun effectivenessMessage(mc: MoveCtx) {
        val e = mc.effectiveness
        if (e > 1) { events.add(BattleEvent.Effectiveness(e)); message("It's super effective!") }
        else if (e < 1 && e > 0) { events.add(BattleEvent.Effectiveness(e)); message("It's not very effective...") }
    }

    private fun applySecondaries(mc: MoveCtx) {
        val move = mc.move
        if (move.secondaries.isEmpty()) return
        val list = move.secondaries.toMutableList()
        for (c in moveHandlers(mc)) c.effect.onModifySecondaries(c, mc, list)
        for (s in list) {
            if (s.chance < 100 && rng.nextInt(100) >= s.chance) continue
            s.self?.let { se ->
                if (!mc.user.fainted) {
                    if (se.boosts.isNotEmpty()) boost(mc.user, se.boosts, mc.user, move.id)
                    se.volatileStatus?.let { addVolatile(mc.user, it, mc.user, move.id) }
                }
            }
            if (mc.target.fainted || mc.substituteHit) continue
            s.status?.let { trySetStatus(mc.target, MajorStatus.byId(it)!!, mc.user, move.id, primary = false) }
            s.volatileStatus?.let { addVolatile(mc.target, it, mc.user, move.id) }
            if (s.boosts.isNotEmpty()) boost(mc.target, s.boosts, mc.user, move.id, secondary = true)
            // Secondary effects defined by a callback (Tri Attack, Dire Claw, Alluring Voice...).
            if (s.status == null && s.volatileStatus == null && s.boosts.isEmpty() && s.self == null) Effects.move(move.id)?.onSecondary(this, mc)
        }
    }

    /** Data-driven effects of status moves. Returns true if anything happened. */
    fun applyStatusMoveEffects(mc: MoveCtx, primary: Boolean): Boolean {
        val m = mc.move.data
        val target = mc.target
        val user = mc.user
        var ok = false
        if (m.boosts.isNotEmpty()) ok = boost(target, m.boosts, user, m.id, showFailure = true) || ok
        m.status?.let { s ->
            ok = trySetStatus(target, MajorStatus.byId(s)!!, user, m.id, primary = true) || ok
            if (!ok) return true.also { /* message already shown */ }
        }
        m.volatileStatus?.let { v -> ok = (addVolatile(target, v, user, m.id) != null) || ok.also { } }
        m.heal?.let { h ->
            var frac = h[0].toDouble() / h[1]
            if (m.id in setOf("moonlight", "synthesis", "morningsun")) frac = when {
                isSun(user) -> 2.0 / 3
                weather() != null && weather() != "deltastream" -> 0.25
                else -> 0.5
            }
            if (m.id == "shoreup" && isSand()) frac = 2.0 / 3
            val amt = Mod.pokeRound(target.baseMaxHp * frac)
            if (target.hp >= target.maxHp) message("${nm(target)}'s HP is full!")
            else if (heal(target, amt, m.id) > 0) { message("${nm(target)} had its HP restored."); ok = true }
            if (target.hp >= target.maxHp && !ok) return false.also { }
        }
        m.self?.let { s ->
            if (s.boosts.isNotEmpty()) ok = boost(user, s.boosts, user, m.id) || ok
            s.volatileStatus?.let { ok = (addVolatile(user, it, user, m.id) != null) || ok }
        }
        m.sideCondition?.let { ok = addSideCondition(if (mc.move.target == "foeSide" || target.side !== user.side) target.side else user.side, it, user) || ok }
        m.slotCondition?.let { ok = addSlotCondition(user.side, it, user) || ok }
        m.weather?.let { ok = setWeather(it, user) || ok }
        m.terrain?.let { ok = setTerrain(it, user) || ok }
        m.pseudoWeather?.let { ok = addPseudoWeather(it, user) || ok }
        if (m.forceSwitch) {
            if (target.side.canSwitchTo().isNotEmpty() && canDrag(target)) { target.forceSwitchFlag = true; ok = true }
            else if (target.side.isWild && rules.canRun && opponent.isWild) {
                // Roar/Whirlwind end wild battles.
                message("${nm(target)} fled!"); finish(BattleOutcome.FOE_FLED); ok = true
            }
        }
        if (m.selfSwitch != null && user.side.canSwitchTo().isNotEmpty()) { user.switchFlag = m.selfSwitch; ok = true }
        return ok
    }

    // ================================================================= damage

    /** Calculates damage for one hit, or null if the move fails/does nothing. Sets [MoveCtx.crit] and [MoveCtx.effectiveness]. */
    fun calcDamage(mc: MoveCtx, forPreview: Boolean = false): Int? {
        val user = mc.user
        val target = mc.target
        val move = mc.move
        val script = Effects.move(move.id)
        mc.effectiveness = typeEffectiveness(mc)
        if (mc.effectiveness == 0.0) return null
        script?.fixedDamage(this, mc)?.let { return it }
        move.data.fixedDamage?.let { fd ->
            return if (fd == "level") user.level else fd.toIntOrNull() ?: 0
        }
        if (move.data.ohko != null) { if (!forPreview) message("It's a one-hit KO!"); return target.hp }
        var bp = script?.basePower(this, mc, move.basePower) ?: move.basePower
        if (bp <= 0) return null
        val bpMod = Mod()
        for (c in moveHandlers(mc)) c.effect.onBasePower(c, mc, bpMod)
        bp = max(1, bpMod.apply(bp))
        // Critical hit.
        mc.crit = if (forPreview) false else rollCrit(mc)
        val physical = move.category == "Physical"
        val atkSource = if (move.data.overrideOffensivePokemon == "target") target else user
        val atkStatId = move.data.overrideOffensiveStat ?: if (physical) "atk" else "spa"
        val defStatId = move.data.overrideDefensiveStat ?: if (physical) "def" else "spd"
        // Wonder Room swaps the raw Defense and Sp. Def stats; stat stages and modifiers keep their original stat.
        val wonder = field.has("wonderroom")
        fun swapped(id: String) = if (!wonder) id else when (id) { "def" -> "spd"; "spd" -> "def"; else -> id }
        val atkStat = Stat.byId(swapped(atkStatId))!!
        val defStat = Stat.byId(swapped(defStatId))!!
        var atkBoost = atkSource.boost(atkStatId)
        var defBoost = target.boost(defStatId)
        if (target.ability == "unaware" && abilityActive(target, mc)) atkBoost = 0
        if (user.ability == "unaware" && abilityActive(user)) defBoost = 0
        if (move.data.ignoreDefensive) defBoost = 0
        if (mc.crit) { if (atkBoost < 0) atkBoost = 0; if (defBoost > 0) defBoost = 0 }
        var atk = applyBoost(atkSource.rawStat(atkStat), atkBoost)
        var def = applyBoost(target.rawStat(defStat), defBoost)
        val atkMod = Mod()
        for (c in moveHandlers(mc)) if (physical || atkStatId == "atk") c.effect.onModifyAtk(c, mc, atkMod) else c.effect.onModifySpA(c, mc, atkMod)
        atk = max(1, atkMod.apply(atk))
        val defMod = Mod()
        for (c in moveHandlers(mc)) if (defStatId == "def") c.effect.onModifyDef(c, mc, defMod) else c.effect.onModifySpD(c, mc, defMod)
        def = max(1, defMod.apply(def))
        val level = user.level
        var base = floor(floor(floor(2.0 * level / 5 + 2) * bp * atk / def) / 50).toInt() + 2
        // Weather.
        if (isSun(target) && !forPreview) { if (move.type == "Fire") base = Mod.pokeRound(base * 1.5); if (move.type == "Water" && move.id != "hydrosteam") base = Mod.pokeRound(base * 0.5) }
        else if (isSun(target)) { if (move.type == "Fire") base = Mod.pokeRound(base * 1.5); if (move.type == "Water") base = Mod.pokeRound(base * 0.5) }
        if (move.id == "hydrosteam" && isSun(target)) base = Mod.pokeRound(base * 1.5)
        if (isRain(target)) { if (move.type == "Water") base = Mod.pokeRound(base * 1.5); if (move.type == "Fire") base = Mod.pokeRound(base * 0.5) }
        if (mc.crit) base = floor(base * 1.5).toInt()
        // Random factor.
        val rand = forcedRoll ?: if (forPreview) 92 else 85 + rng.nextInt(16)
        base = floor(base * rand / 100.0).toInt()
        // STAB.
        var stab = 1.0
        val teraType = user.terastallized
        val originalTypes = dex.species(user.species.id).types
        if (teraType != null && teraType != "Stellar") {
            if (move.type == teraType) stab = if (move.type in user.types || move.type in originalTypes) 2.0 else 1.5
            else if (move.type in user.types || move.type in originalTypes) stab = 1.5
        } else if (teraType == "Stellar") {
            if (move.type !in user.stellarBoostedTypes || user.species.baseSpecies == "terapagos") {
                stab = if (move.type in user.types) 2.0 else 1.2
                if (!forPreview && user.species.baseSpecies != "terapagos") user.stellarBoostedTypes.add(move.type)
            } else if (move.type in user.types) stab = 1.5
        } else if (user.hasType(move.type)) stab = 1.5
        if (move.type == "???") stab = 1.0
        for (c in moveHandlers(mc)) if (c.holder === user) stab = c.effect.onModifyStab(c, mc, stab)
        if (stab != 1.0) base = Mod.pokeRound(base * stab)
        // Type effectiveness.
        base = floor(base * mc.effectiveness).toInt()
        // Burn.
        if (physical && user.status == MajorStatus.BURN && !(user.ability == "guts" && abilityActive(user)) && move.id != "facade") base = Mod.pokeRound(base * 0.5)
        // Final modifiers.
        val finalMod = Mod()
        for (c in moveHandlers(mc)) c.effect.onModifyDamage(c, mc, finalMod)
        if (target.isDynamaxed && move.id in setOf("behemothblade", "behemothbash", "dynamaxcannon")) finalMod.chain(2.0)
        if (move.data2["parentalbond"] == true && mc.hitIndex == 1) finalMod.chain(0.25)
        if (target.hasVolatile("protect") && (move.isZ || move.isMax)) finalMod.chain(0.25)
        base = finalMod.apply(base)
        return max(1, base)
    }

    fun applyBoost(stat: Int, stage: Int): Int {
        val s = stage.coerceIn(-6, 6)
        return if (s >= 0) stat * (2 + s) / 2 else stat * 2 / (2 - s)
    }

    private fun rollCrit(mc: MoveCtx): Boolean {
        forcedCrit?.let { if (!it) return false }
        for (c in moveHandlers(mc)) if (!c.effect.onCriticalHit(c, mc)) return false
        if (mc.move.willCrit || forcedCrit == true) return true
        var ratio = mc.move.critRatio
        for (c in moveHandlers(mc)) if (c.holder === mc.user || c.holder == null) ratio = c.effect.onModifyCritRatio(c, mc, ratio)
        val stage = (ratio - 1).coerceIn(0, 3)
        return when (stage) { 0 -> rng.nextInt(24) == 0; 1 -> rng.nextInt(8) == 0; 2 -> rng.nextInt(2) == 0; else -> true }
    }

    fun typeEffectiveness(mc: MoveCtx): Double {
        val target = mc.target
        val type = mc.move.type
        if (type == "???") return 1.0
        if (type == "Stellar") return if (target.terastallized != null) 2.0 else 1.0
        if (isImmune(mc, type)) return 0.0
        var total = 1.0
        for (t in target.effectiveTypes()) {
            var e = dex.typeChart.effectiveness(type, t)
            if (e == 0.0) e = 1.0 // immunity already resolved above (ring target, foresight, grounded flying)
            for (c in moveHandlers(mc)) e = c.effect.onEffectiveness(c, mc, t, e)
            total *= e
        }
        if (mc.move.id == "flyingpress") for (t in target.effectiveTypes()) total *= dex.typeChart.effectiveness("Flying", t)
        if (weather() == "deltastream" && target.hasType("Flying") && dex.typeChart.effectiveness(type, "Flying") > 1) total /= 2
        if (rules.inverse) total = if (total == 0.0) 2.0 else 1.0 / total
        return total
    }

    /** Applies move damage to HP, routed through onDamage hooks (Sturdy, Focus Sash, Disguise...). */
    private fun applyMoveDamage(mc: MoveCtx, damage: Int): Int {
        val target = mc.target
        var amount = min(damage, target.hp)
        for (c in targetHandlers(target)) amount = c.effect.onDamage(c, target, amount, mc.user, mc.move.id, true)
        // Endure / False Swipe style survival.
        if (amount >= target.hp && (target.hasVolatile("endure") || mc.move.id in setOf("falseswipe", "holdback"))) {
            amount = target.hp - 1
            if (target.hasVolatile("endure")) message("${nm(target)} endured the hit!")
        }
        amount = amount.coerceIn(0, target.hp)
        target.hp -= amount
        target.hurtThisTurn = true
        target.lastDamage = amount
        target.lastDamagedBy = DamageRecord(mc.user, amount, mc.move.category, turn)
        target.mon.counters["hplost"] = (target.mon.counters["hplost"] ?: 0) + amount
        hpEvent(target, -amount, "move")
        return amount
    }

    /** Indirect damage (recoil, weather, status, hazards...). Returns HP lost. */
    fun directDamage(target: BattleMon, amount: Int, source: BattleMon?, effectId: String, silent: Boolean = false): Int {
        if (target.fainted || amount <= 0) return 0
        var amt = amount
        for (c in targetHandlers(target)) amt = c.effect.onDamage(c, target, amt, source, effectId, false)
        amt = amt.coerceIn(0, target.hp)
        if (amt == 0) return 0
        target.hp -= amt
        hpEvent(target, -amt, effectId)
        return amt
    }

    fun heal(target: BattleMon, amount: Int, effectId: String): Int {
        if (target.fainted || amount <= 0 || target.hp >= target.maxHp) return 0
        var amt = amount
        for (c in targetHandlers(target)) amt = c.effect.onTryHeal(c, target, amt, effectId)
        amt = min(amt, target.maxHp - target.hp)
        if (amt <= 0) return 0
        target.hp += amt
        hpEvent(target, amt, effectId)
        return amt
    }

    fun getSpeed(mon: BattleMon): Int {
        var spe = applyBoost(mon.rawStat(Stat.SPE), mon.boost("spe"))
        val mod = Mod()
        for (c in targetHandlers(mon)) c.effect.onModifySpe(c, mon, mod)
        spe = mod.apply(spe)
        return spe.coerceAtMost(10000)
    }

    fun getWeight(mon: BattleMon): Double {
        var w = mon.species.weightkg
        for (c in targetHandlers(mon)) if (c.holder === mon) w = c.effect.onModifyWeight(c, mon, w)
        if (mon.hasVolatile("autotomize")) w = max(0.1, w - 100 * (mon.volatiles["autotomize"]?.counter ?: 1))
        return max(0.1, w)
    }

    // ================================================================= status, volatiles, boosts

    fun trySetStatus(target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?, primary: Boolean): Boolean {
        if (target.fainted) return false
        if (target.status != null) {
            if (primary) message(if (target.status == status || (status == MajorStatus.TOXIC && target.status == MajorStatus.POISON))
                "${nm(target)} is already ${statusAdjective(target.status!!)}!" else "But it failed!")
            return false
        }
        // Type immunities.
        val corrosion = source?.ability == "corrosion" && abilityActive(source)
        val immune = when (status) {
            MajorStatus.BURN -> target.hasType("Fire")
            MajorStatus.POISON, MajorStatus.TOXIC -> (target.hasType("Poison") || target.hasType("Steel")) && !corrosion
            MajorStatus.FREEZE -> target.hasType("Ice") || isSun(target)
            MajorStatus.PARALYSIS -> target.hasType("Electric")
            MajorStatus.SLEEP -> false
        }
        if (immune) { if (primary) message("It doesn't affect ${nmL(target)}..."); return false }
        if (status == MajorStatus.SLEEP && activeMons().any { it.hasVolatile("uproar") }) { if (primary) message("But the uproar kept ${nmL(target)} awake!"); return false }
        // Safeguard / abilities / terrain.
        for (c in targetHandlers(target)) {
            if (!c.effect.onSetStatus(c, target, status, source, sourceEffect)) {
                if (primary && !c.state.flag) Unit
                return false
            }
        }
        target.mon.status = status
        target.statusState = EffectState()
        if (status == MajorStatus.SLEEP) {
            val turns = if (sourceEffect == "rest") 3 else 1 + rng.nextInt(3)
            target.mon.sleepTurns = turns
            target.statusState.duration = turns
        }
        if (status == MajorStatus.TOXIC) target.statusState.counter = 0
        val text = when (status) {
            MajorStatus.BURN -> "${nm(target)} was burned!"
            MajorStatus.PARALYSIS -> "${nm(target)} is paralyzed! It may be unable to move!"
            MajorStatus.POISON -> "${nm(target)} was poisoned!"
            MajorStatus.TOXIC -> "${nm(target)} was badly poisoned!"
            MajorStatus.SLEEP -> if (sourceEffect == "rest") "${nm(target)} slept and became healthy!" else "${nm(target)} fell asleep!"
            MajorStatus.FREEZE -> "${nm(target)} was frozen solid!"
        }
        events.add(BattleEvent.StatusChange(target.side.index, teamIndex(target), status.id, text)); log.add(text)
        for (c in targetHandlers(target)) c.effect.onAfterSetStatus(c, target, status, source)
        if (source != null && source !== target && sourceEffect != null) {
            // Synchronize.
            if (target.ability == "synchronize" && abilityActive(target) && status != MajorStatus.SLEEP && status != MajorStatus.FREEZE) {
                message("[${target.name}'s Synchronize]")
                trySetStatus(source, if (status == MajorStatus.TOXIC) MajorStatus.TOXIC else status, target, "synchronize", primary = false)
            }
        }
        return true
    }

    fun statusAdjective(s: MajorStatus) = when (s) {
        MajorStatus.BURN -> "burned"; MajorStatus.PARALYSIS -> "paralyzed"; MajorStatus.POISON, MajorStatus.TOXIC -> "poisoned"
        MajorStatus.SLEEP -> "asleep"; MajorStatus.FREEZE -> "frozen solid"
    }

    fun cureStatus(target: BattleMon, silent: Boolean = false, reason: String? = null) {
        val s = target.status ?: return
        target.mon.status = null
        target.mon.sleepTurns = 0
        target.statusState = EffectState()
        val text = reason ?: when (s) {
            MajorStatus.BURN -> "${nm(target)}'s burn was healed."
            MajorStatus.PARALYSIS -> "${nm(target)} was cured of paralysis."
            MajorStatus.POISON, MajorStatus.TOXIC -> "${nm(target)} was cured of its poisoning."
            MajorStatus.SLEEP -> "${nm(target)} woke up!"
            MajorStatus.FREEZE -> "${nm(target)} thawed out!"
        }
        events.add(BattleEvent.StatusChange(target.side.index, teamIndex(target), null, if (silent) "" else text))
        if (!silent) log.add(text)
    }

    /** Adds a volatile condition; returns its state, or null if it failed or was already present. */
    fun addVolatile(target: BattleMon, id0: String, source: BattleMon?, sourceEffect: String?): EffectState? {
        if (target.fainted) return null
        val id = toId(id0)
        val eff = Effects.condition(id)
        val existing = target.volatiles[id]
        if (existing != null) {
            if (eff != null && eff.onRestart(EffectCtx(this, eff, target, null, existing), source)) return existing
            return null
        }
        for (c in targetHandlers(target)) if (!c.effect.onTryAddVolatile(c, target, id, source)) return null
        val st = EffectState(eff?.duration(this, target, source) ?: -1)
        st.source = source
        st.moveId = sourceEffect
        target.volatiles[id] = st
        if (eff != null) {
            eff.onStart(EffectCtx(this, eff, target, null, st), source)
            if (target.volatiles[id] !== st) return null // the effect removed itself (failed)
        }
        return st
    }

    fun removeVolatile(target: BattleMon, id: String): Boolean {
        val st = target.volatiles.remove(id) ?: return false
        Effects.condition(id)?.onEnd(EffectCtx(this, Effects.condition(id)!!, target, null, st))
        return true
    }

    fun addSideCondition(side: Side, id0: String, source: BattleMon?): Boolean {
        val id = toId(id0)
        val eff = Effects.condition(id)
        val existing = side.conditions[id]
        if (existing != null) {
            return eff != null && eff.onRestart(EffectCtx(this, eff, null, side, existing), source)
        }
        val st = EffectState(eff?.duration(this, null, source) ?: -1)
        st.source = source
        side.conditions[id] = st
        eff?.onStart(EffectCtx(this, eff, null, side, st), source)
        return side.conditions[id] === st
    }

    fun removeSideCondition(side: Side, id: String): Boolean {
        val st = side.conditions.remove(id) ?: return false
        Effects.condition(id)?.onEnd(EffectCtx(this, Effects.condition(id)!!, null, side, st))
        return true
    }

    fun addSlotCondition(side: Side, id0: String, source: BattleMon?): Boolean {
        val id = toId(id0)
        if (id in side.slotConditions) return false
        val eff = Effects.condition(id)
        val st = EffectState(eff?.duration(this, null, source) ?: -1)
        st.source = source
        side.slotConditions[id] = st
        eff?.onStart(EffectCtx(this, eff, null, side, st), source)
        return true
    }

    fun addPseudoWeather(id0: String, source: BattleMon?): Boolean {
        val id = toId(id0)
        val eff = Effects.condition(id)
        val existing = field.pseudoWeather[id]
        if (existing != null) {
            if (eff != null && eff.onRestart(EffectCtx(this, eff, null, null, existing), source)) return true
            return false
        }
        val st = EffectState(eff?.duration(this, null, source) ?: 5)
        st.source = source
        field.pseudoWeather[id] = st
        eff?.onStart(EffectCtx(this, eff, null, null, st), source)
        return true
    }

    fun removePseudoWeather(id: String) {
        val st = field.pseudoWeather.remove(id) ?: return
        Effects.condition(id)?.onEnd(EffectCtx(this, Effects.condition(id)!!, null, null, st))
    }

    private val strongWeathers = setOf("desolateland", "primordialsea", "deltastream")

    fun setWeather(id0: String, source: BattleMon?, abilityTriggered: Boolean = false): Boolean {
        val id = toId(id0)
        val current = field.weather
        if (current == id && field.weatherState.duration != 0) return false
        if (current in strongWeathers && id !in strongWeathers) {
            message(when (current) {
                "desolateland" -> "The extremely harsh sunlight was not lessened at all!"
                "primordialsea" -> "There is no relief from this heavy rain!"
                else -> "The mysterious strong winds blow on regardless!"
            })
            return false
        }
        val eff = Effects.condition(id)
        field.weather = id
        field.weatherState = EffectState(eff?.duration(this, null, source) ?: 5)
        field.weatherState.source = source
        if (source != null && id !in strongWeathers) {
            val rock = when (id) { "sunnyday" -> "heatrock"; "raindance" -> "damprock"; "sandstorm" -> "smoothrock"; "snowscape", "snow", "hail" -> "icyrock"; else -> null }
            if (rock != null && source.item == rock && itemActive(source)) field.weatherState.duration = 8
        }
        eff?.onStart(EffectCtx(this, eff, null, null, field.weatherState), source)
        for (c in allHandlers()) c.effect.onWeatherChange(c)
        return true
    }

    fun clearWeather() {
        val w = field.weather ?: return
        field.weather = null
        Effects.condition(w)?.onEnd(EffectCtx(this, Effects.condition(w)!!, null, null, field.weatherState))
        for (c in allHandlers()) c.effect.onWeatherChange(c)
    }

    fun setTerrain(id0: String, source: BattleMon?): Boolean {
        val id = toId(id0)
        if (field.terrain == id) return false
        val eff = Effects.condition(id)
        field.terrain = id
        field.terrainState = EffectState(eff?.duration(this, null, source) ?: 5)
        field.terrainState.source = source
        if (source != null && source.item == "terrainextender" && itemActive(source)) field.terrainState.duration = 8
        eff?.onStart(EffectCtx(this, eff, null, null, field.terrainState), source)
        for (c in allHandlers()) c.effect.onTerrainChange(c)
        return true
    }

    fun clearTerrain() {
        val t = field.terrain ?: return
        field.terrain = null
        Effects.condition(t)?.onEnd(EffectCtx(this, Effects.condition(t)!!, null, null, field.terrainState))
        for (c in allHandlers()) c.effect.onTerrainChange(c)
    }

    /**
     * Applies stat stage changes. Returns true if any stage changed.
     * [secondary] suppresses "won't go any higher" messages for secondary effects.
     */
    fun boost(target: BattleMon, boosts: Map<String, Int>, source: BattleMon?, sourceEffect: String?, secondary: Boolean = false, showFailure: Boolean = false): Boolean {
        if (target.fainted) return false
        if (target.hasVolatile("substitute") && source != null && source !== target && boosts.values.any { it < 0 } && sourceEffect != null && dex.moveOrNull(sourceEffect)?.hasFlag("bypasssub") != true && !secondary) {
            if (showFailure) message("But it failed!")
            return false
        }
        val map = boosts.toMutableMap()
        for (c in targetHandlers(target)) if (c.holder === target) c.effect.onChangeBoost(c, target, map)
        for (c in targetHandlers(target)) c.effect.onTryBoost(c, target, map, source, sourceEffect)
        var changed = false
        val applied = LinkedHashMap<String, Int>()
        for ((stat, amt0) in map) {
            if (amt0 == 0) continue
            val i = BattleMon.boostIndex(stat)
            val before = target.boosts[i]
            val after = (before + amt0).coerceIn(-6, 6)
            val delta = after - before
            if (delta == 0) {
                if (!secondary || showFailure) message("${nm(target)}'s ${BattleMon.boostLabel(stat)} won't go any ${if (amt0 > 0) "higher" else "lower"}!")
                continue
            }
            target.boosts[i] = after
            changed = true
            applied[stat] = delta
            if (delta > 0) target.statsRaisedThisTurn = true else target.statsLoweredThisTurn = true
            val adverb = when (kotlin.math.abs(delta)) { 1 -> ""; 2 -> " sharply"; else -> " drastically" }
            val text = if (delta > 0) "${nm(target)}'s ${BattleMon.boostLabel(stat)} rose$adverb!" else "${nm(target)}'s ${BattleMon.boostLabel(stat)}${if (adverb.isEmpty()) "" else adverb.replace(" sharply", " harshly").replace(" drastically", " severely")} fell!"
            events.add(BattleEvent.StatChange(target.side.index, stat, delta, text)); log.add(text)
        }
        if (changed) {
            for (c in targetHandlers(target)) c.effect.onAfterBoost(c, target, applied, source)
            // Opposing effects that react to boosts (Mirror Herb, Opportunist).
            val foe = target.side.foe.active
            if (foe != null && !foe.fainted && foe.isActive) {
                val l = ArrayList<EffectCtx>(); monEffects(foe, null, l)
                for (c in l) c.effect.onFoeAfterBoost(c, target, applied, source, sourceEffect)
            }
        }
        if (!changed && showFailure && map.isEmpty()) message("But it failed!")
        return changed
    }

    fun clearBoosts(mon: BattleMon) { mon.boosts.fill(0) }

    // ================================================================= items

    /** Removes the holder's item as consumed (berries, gems, single-use items). */
    fun consumeItem(mon: BattleMon) {
        val item = mon.item ?: return
        mon.lastItem = item
        mon.item = null
        mon.usedItemThisBattle = true
        mon.itemState = EffectState()
        if (dex.itemOrNull(item)?.isBerry == true) {
            for (c in targetHandlers(mon)) if (c.holder === mon) c.effect.onEatItem(c, mon, item)
            mon.volatiles["ateberry"] = EffectState()
        }
    }

    /** Removes an item by an opponent's action (Knock Off, Thief). Returns the removed item or null if prevented. */
    fun takeItem(mon: BattleMon, source: BattleMon?): String? {
        val item = mon.item ?: return null
        if (!canRemoveItem(mon, item)) return null
        for (c in targetHandlers(mon)) if (c.holder === mon && !c.effect.onTakeItem(c, mon, item, source)) return null
        mon.item = null
        mon.itemKnockedOff = true
        mon.usedItemThisBattle = true
        mon.itemState = EffectState()
        return item
    }

    fun canRemoveItem(mon: BattleMon, item: String): Boolean {
        val data = dex.itemOrNull(item) ?: return true
        if (data.megaStone.containsKey(toId(mon.species.baseSpecies)) || data.megaStone.isNotEmpty() && mon.species.formKind == FormKind.MEGA) return false
        if (data.category == ItemCategory.Z_CRYSTAL) return false
        if (data.onPlate != null && mon.species.baseSpecies == "arceus") return false
        if (data.onMemory != null && mon.species.baseSpecies == "silvally") return false
        if (data.onDrive != null && mon.species.baseSpecies == "genesect") return false
        if (item in setOf("blueorb", "redorb") && mon.species.baseSpecies in setOf("kyogre", "groudon")) return false
        if (item == "boosterenergy" && mon.species.tags.contains("Paradox")) return item != "boosterenergy" || true
        if (data.forcedForme != null && toId(mon.species.baseSpecies) == toId(dex.speciesOrNull(data.forcedForme)?.baseSpecies ?: "")) return false
        return true
    }

    // ================================================================= residual

    private fun residualPhase() {
        val list = ArrayList<EffectCtx>()
        fieldEffects(list)
        for (s in sides) sideEffects(s, list)
        for (s in sides) for ((id, st) in s.slotConditions.entries.toList()) Effects.condition(id)?.let { list.add(EffectCtx(this, it, s.active, s, st)) }
        for (m in activeMonsBySpeed()) monEffects(m, null, list)
        list.sortWith(compareBy { it.effect.residualOrder })
        for (c in list) {
            if (outcome != BattleOutcome.ONGOING) return
            if (c.holder != null && (c.holder.fainted || !c.holder.isActive)) continue
            c.effect.onResidual(c)
            checkFaints()
            runUpdate()
        }
        // Durations.
        tickDurations()
        for (m in activeMons()) {
            m.activeTurns++
            if (m.isDynamaxed) { if (m.dynamaxTurns <= 1) endDynamax(m) else m.dynamaxTurns-- }
        }
        checkFaints()
    }

    private fun tickDurations() {
        // Weather
        if (field.weather != null && field.weatherState.duration > 0) {
            field.weatherState.duration--
            if (field.weatherState.duration == 0) clearWeather()
        }
        if (field.terrain != null && field.terrainState.duration > 0) {
            field.terrainState.duration--
            if (field.terrainState.duration == 0) clearTerrain()
        }
        for ((id, st) in field.pseudoWeather.entries.toList()) if (st.duration > 0) { st.duration--; if (st.duration == 0) removePseudoWeather(id) }
        for (s in sides) {
            for ((id, st) in s.conditions.entries.toList()) if (st.duration > 0) { st.duration--; if (st.duration == 0) removeSideCondition(s, id) }
            for ((id, st) in s.slotConditions.entries.toList()) if (st.duration > 0) {
                st.duration--
                if (st.duration == 0) { s.slotConditions.remove(id); Effects.condition(id)?.onEnd(EffectCtx(this, Effects.condition(id)!!, s.active, s, st)) }
            }
        }
        for (m in activeMons()) for ((id, st) in m.volatiles.entries.toList()) if (st.duration > 0) {
            st.duration--
            if (st.duration == 0) removeVolatile(m, id)
        }
    }

    /** Runs onUpdate hooks (berries, herbs, form checks) for every active Pokémon. */
    fun runUpdate() {
        for (m in activeMonsBySpeed()) {
            if (m.fainted) continue
            for (c in targetHandlers(m)) if (c.holder === m) c.effect.onUpdate(c)
        }
    }

    // ================================================================= fainting / outcome

    private val faintProcessed = HashSet<BattleMon>()

    fun checkFaints() {
        for (m in sides.mapNotNull { it.active }) {
            if (!m.fainted || m in faintProcessed) continue
            faintProcessed.add(m)
            val text = "${nm(m)} fainted!"
            events.add(BattleEvent.Faint(m.side.index, teamIndex(m), text)); log.add(text)
            m.mon.fainted = true
            m.mon.status = null
            m.side.faintedThisTurn = true
            m.side.totalFainted++
            if (m.side.isPlayer) m.mon.friendship = max(0, m.mon.friendship - 1)
            val killer = m.lastDamagedBy?.takeIf { it.turn == turn }?.source
            for (c in targetHandlers(m)) if (c.holder === m) c.effect.onFaint(c, killer)
            if (killer != null && !killer.fainted && killer !== m) {
                val mv = (currentAction as? Action)?.let { a -> if (a.mon === killer) dex.moveOrNull(killer.lastMove)?.let { ActiveMove(it) } else null }
                for (c in targetHandlers(killer)) if (c.holder === killer) c.effect.onSourceFaint(c, m, mv)
                killer.mon.counters["defeat:${m.species.baseSpecies}"] = (killer.mon.counters["defeat:${m.species.baseSpecies}"] ?: 0) + 1
            }
            if (m.isDynamaxed) endDynamax(m, silent = true)
            m.volatiles.clear()
            if (!m.side.isPlayer && rules.expEnabled) awardExp(m)
        }
        // Allow a Pokémon revived mid-battle (Revival Blessing) to faint again later.
        faintProcessed.removeAll { !it.fainted }
    }

    private fun awardExp(defeated: BattleMon) {
        val receivers = player.mons.filter { !it.fainted && it.level < rules.levelCap && it.level < 100 }
        val participants = receivers.filter { defeated.mon.uid in it.facedFoes || it.isActive }
        val recipients = if (rules.expShareAll) receivers else participants
        for (r in recipients) {
            val participated = r in participants
            var amount = progression.expYield(defeated.mon, r.level, rules.isTrainerBattle, luckyEgg = r.item == "luckyegg")
            if (!participated) amount /= 2
            if (r.mon.otId != 0 && r.mon.otName.isNotEmpty() && false) amount = amount * 3 / 2
            val capExp = r.mon.species(dex).growthRate.expForLevel(rules.levelCap)
            amount = min(amount, max(0, capExp - r.mon.exp))
            if (amount <= 0) continue
            val ti = teamIndex(r)
            val text = "${r.name} gained $amount Exp. Points!"
            events.add(BattleEvent.ExpGain(ti, amount, text)); log.add(text)
            addEvs(r.mon, defeated.species.evYield)
            val levels = progression.addExp(r.mon, amount)
            for (lv in levels) {
                r.recomputeStats()
                val lt = "${r.name} grew to Lv. ${lv.newLevel}!"
                events.add(BattleEvent.LevelUp(ti, lv.newLevel, lt)); log.add(lt)
                events.add(BattleEvent.HpChange(r.side.index, ti, r.hp, r.maxHp, 0, "levelup"))
                r.mon.friendship = min(255, r.mon.friendship + if (r.mon.friendship < 100) 5 else if (r.mon.friendship < 200) 3 else 2)
                leveledUp.add(ti)
                for (mv in lv.newMoves) learnMoveDuringBattle(r, mv)
            }
        }
    }

    private fun learnMoveDuringBattle(r: BattleMon, moveId: String) {
        val mv = dex.moveOrNull(moveId) ?: return
        if (r.mon.moves.any { it.id == mv.id }) return
        val ti = teamIndex(r)
        if (r.mon.moves.size < 4) {
            r.mon.moves.add(MoveSlot(mv.id, mv.pp))
            message("${r.name} learned ${mv.name}!")
        } else {
            pendingMoveLearns.add(ti to mv.id)
            events.add(BattleEvent.LearnMovePrompt(ti, mv.id))
        }
    }

    fun addEvs(mon: Pokemon, yield: BaseStats) {
        val mult = if (mon.heldItem == "machobrace") 2 else 1
        for (s in Stat.entries) {
            var gain = yield[s] * mult
            val powerItem = mapOf(Stat.HP to "powerweight", Stat.ATK to "powerbracer", Stat.DEF to "powerbelt", Stat.SPA to "powerlens", Stat.SPD to "powerband", Stat.SPE to "poweranklet")
            if (mon.heldItem == powerItem[s]) gain += 8
            if (gain == 0) continue
            val room = min(252 - mon.evs[s], 510 - mon.evs.total)
            if (room > 0) mon.evs[s] = mon.evs[s] + min(room, gain)
        }
    }

    private fun checkWin(): Boolean {
        if (outcome != BattleOutcome.ONGOING) return true
        val pAlive = player.alive().isNotEmpty()
        val oAlive = opponent.alive().isNotEmpty()
        when {
            !pAlive && !oAlive -> finish(BattleOutcome.DRAW)
            !oAlive -> finish(BattleOutcome.WIN)
            !pAlive -> finish(BattleOutcome.LOSE)
            else -> return false
        }
        return true
    }

    fun finish(o: BattleOutcome) {
        if (outcome != BattleOutcome.ONGOING) return
        outcome = o
        cleanupAfterBattle()
        events.add(BattleEvent.End(o))
    }

    /** Restores battle-only formes, items and transformations on the persistent Pokémon. */
    private fun cleanupAfterBattle() {
        for (s in sides) for (m in s.mons) {
            if (m.isDynamaxed) endDynamax(m, silent = true)
            m.terastallized = null
            val keepForme = m.species.formKind != FormKind.MEGA && m.species.formKind != FormKind.PRIMAL &&
                m.species.formKind != FormKind.BATTLE_ONLY && m.species.formKind != FormKind.ULTRA_BURST && !m.transformed
            m.mon.speciesId = if (keepForme && m.species.id in PERSISTENT_OUT_OF_BATTLE_CHANGES) m.species.id else m.originalSpecies
            // Items removed by the opponent (Knock Off, Trick) are returned; consumed items stay consumed.
            m.mon.heldItem = when {
                m.itemKnockedOff -> m.originalItem
                m.item == null -> null
                s.isPlayer && m.originalItem == null && opponent.isWild -> m.item // stolen from a wild Pokémon
                else -> if (m.item == m.originalItem) m.originalItem else m.originalItem
            }
            if (m.mon.hp > m.mon.maxHp(dex)) m.mon.hp = m.mon.maxHp(dex)
            if (m.fainted) { m.mon.hp = 0; m.mon.status = null }
            if (m.moveSlots !== m.mon.moves) m.moveSlots = m.mon.moves
        }
    }

    // ================================================================= run / items / catch

    private fun attemptRun(side: Side) {
        val mon = side.active ?: return
        val foe = side.foe.active ?: return
        runAttempts++
        val canAlways = mon.hasType("Ghost") || (mon.item == "smokeball") || (mon.ability == "runaway" && abilityActive(mon))
        if (!canAlways && !canSwitchOut(mon) && side.canSwitchTo().isNotEmpty().let { true }) {
            var trapped = false
            for (c in allHandlers()) if (c.effect.onTrapPokemon(c, mon)) trapped = true
            if (trapped) { message("${nm(mon)} can't escape!"); return }
        }
        val ps = getSpeed(mon); val fs = max(1, getSpeed(foe))
        val escaped = canAlways || ps >= fs || rng.nextInt(256) < ((ps * 128 / fs + 30 * runAttempts) % 256)
        if (escaped) {
            message("Got away safely!")
            finish(BattleOutcome.RAN)
        } else message("Can't escape!")
    }

    private fun useBagItem(side: Side, c: Choice.UseItem) {
        val item = dex.itemOrNull(c.itemId) ?: return
        if (item.isPokeball) { throwBall(side, item); return }
        val target = side.mons.getOrNull(c.teamIndex) ?: side.active ?: return
        message("${if (side.isPlayer) "You" else side.name} used ${if (side.isPlayer) "the" else "a"} ${item.name}!".replace("You used", "${side.name} used"))
        val r = BagItemEffects.apply(this, target, item.id, c.moveIndex)
        if (!r) message("But it had no effect...")
    }

    private fun throwBall(side: Side, ball: ItemData) {
        val target = side.foe.active ?: return
        message("${side.name} threw a ${ball.name}!")
        if (!side.foe.isWild) {
            message("The Trainer blocked the Ball! Don't be a thief!")
            return
        }
        if (target.hasVolatile("semiinvulnerable")) { message("But it missed!"); return }
        val result = CatchCalc.attempt(this, side.active!!, target, ball.id)
        events.add(BattleEvent.CatchAttempt(ball.id, result.shakes, result.caught, result.critical))
        if (result.caught) {
            message("Gotcha! ${target.name} was caught!")
            target.mon.ball = ball.id
            if (ball.id == "healball") target.mon.healFully(dex)
            if (ball.id == "friendball") target.mon.friendship = 200
            caughtMon = target.mon
            finish(BattleOutcome.CAUGHT)
        } else {
            message(when (result.shakes) {
                0 -> "Oh no! The Pokémon broke free!"
                1 -> "Aww! It appeared to be caught!"
                2 -> "Aargh! Almost had it!"
                else -> "Gah! It was so close, too!"
            })
        }
    }

    // ================================================================= gimmicks

    fun canUseGimmickNow(mon: BattleMon, g: Gimmick): Boolean {
        if (!mon.side.canUseGimmick(g, rules)) return false
        return when (g) {
            Gimmick.MEGA -> Gimmicks.megaTarget(this, mon) != null
            Gimmick.ULTRA_BURST -> Gimmicks.ultraBurstTarget(this, mon) != null
            Gimmick.ZMOVE -> mon.moveSlots.any { Gimmicks.zMoveFor(this, mon, dex.move(it.id)) != null && it.pp > 0 }
            Gimmick.DYNAMAX -> !mon.isDynamaxed && !mon.species.cannotDynamax && mon.species.formKind != FormKind.MEGA && mon.species.formKind != FormKind.PRIMAL && mon.terastallized == null
            Gimmick.TERA -> mon.terastallized == null && !mon.isDynamaxed && mon.mon.teraType != null && mon.species.formKind != FormKind.MEGA
        }
    }

    private fun performGimmick(mon: BattleMon, g: Gimmick) {
        if (mon.fainted || !canUseGimmickNow(mon, g)) return
        when (g) {
            Gimmick.MEGA -> Gimmicks.megaEvolve(this, mon)
            Gimmick.ULTRA_BURST -> Gimmicks.ultraBurst(this, mon)
            Gimmick.DYNAMAX -> Gimmicks.dynamax(this, mon)
            Gimmick.TERA -> Gimmicks.terastallize(this, mon)
            Gimmick.ZMOVE -> {}
        }
        mon.side.usedGimmicks.add(g)
    }

    fun endDynamax(mon: BattleMon, silent: Boolean = false) = Gimmicks.endDynamax(this, mon, silent)

    private fun checkPrimalReversion(mon: BattleMon) = Gimmicks.checkPrimal(this, mon)

    fun formeChange(mon: BattleMon, speciesId: String, kind: String, text: String?, changeAbility: Boolean = true) {
        val sp = dex.species(speciesId)
        if (mon.preTransformSpecies == null && kind != "permanent") mon.preTransformSpecies = mon.species
        mon.setSpecies(sp, changeAbility)
        events.add(BattleEvent.FormeChange(mon.side.index, teamIndex(mon), sp.id, kind, text ?: ""))
        if (text != null) log.add(text)
    }

    companion object {
        val BATON_PASSABLE = setOf("substitute", "aquaring", "ingrain", "focusenergy", "magnetrise", "curse", "leechseed",
            "perishsong", "gastroacid", "embargo", "healblock", "confusion", "powertrick", "lockon", "telekinesis")
        /** Battle-only formes that persist on switching (they revert only at the end of battle). */
        val PERSISTENT_BATTLE_FORMES = setOf("palafinhero", "minior", "zygardecomplete", "necrozmaultra", "terapagosterastal", "terapagosstellar",
            "greninjaash", "greninjabond", "mimikyubusted", "mimikyubustedtotem", "cramorantgulping", "cramorantgorging", "eiscuenoice", "morpekohangry")
        val PERSISTENT_OUT_OF_BATTLE_CHANGES = emptySet<String>()
        /** Status moves whose type immunity is checked (Thunder Wave vs Ground, etc.). */
        /** Status moves normally ignore type immunity; these are the exceptions (Thunder Wave cannot affect Ground types). */
        val TYPE_CHECKED_STATUS = setOf("thunderwave")
    }
}
