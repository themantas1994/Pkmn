package pkmn.engine.battle

import pkmn.engine.mon.MajorStatus

enum class EffectKind { ABILITY, ITEM, STATUS, VOLATILE, SIDE, SLOT, WEATHER, TERRAIN, PSEUDO_WEATHER, MOVE }

/**
 * Where an effect is attached when a hook runs. For Pokémon-attached effects [holder] is set;
 * for side conditions [side] is set; field effects have neither.
 */
class EffectCtx(
    val b: Battle,
    val effect: Effect,
    val holder: BattleMon?,
    val side: Side?,
    val state: EffectState,
) {
    fun isUser(mc: MoveCtx) = holder != null && holder === mc.user
    fun isTarget(mc: MoveCtx) = holder != null && holder === mc.target
    /** Side condition belongs to the defending side of this move. */
    fun isTargetSide(mc: MoveCtx) = side != null && side === mc.target.side
    fun isUserSide(mc: MoveCtx) = side != null && side === mc.user.side
    val mon: BattleMon get() = holder!!
}

/**
 * Base class for every battle effect: abilities, held items, status conditions, volatile conditions,
 * side/slot conditions, weather, terrain, pseudo-weather and per-move scripts.
 *
 * Hooks are broadcast to all effects attached to the Pokémon involved, their sides and the field.
 * Each hook implementation checks the role of its holder (see [EffectCtx.isUser]/[EffectCtx.isTarget]).
 * All hooks default to "no effect".
 */
abstract class Effect(val id: String, val kind: EffectKind) {
    open val displayName: String get() = id

    /** Default duration in turns when the effect is added (-1 = indefinite). */
    open fun duration(b: Battle, target: BattleMon?, source: BattleMon?): Int = -1
    /** Order within the end-of-turn residual phase (lower runs first). */
    open val residualOrder: Int = 100

    // ---- lifecycle
    open fun onStart(c: EffectCtx, source: BattleMon?) {}
    /** Called when an already present condition is added again. Return false to report failure. */
    open fun onRestart(c: EffectCtx, source: BattleMon?): Boolean = false
    open fun onEnd(c: EffectCtx) {}
    open fun onResidual(c: EffectCtx) {}
    open fun onSwitchIn(c: EffectCtx) {}
    open fun onSwitchOut(c: EffectCtx) {}
    open fun onFaint(c: EffectCtx, source: BattleMon?) {}
    /** Called after every action; used by berries and similar threshold checks. */
    open fun onUpdate(c: EffectCtx) {}
    /** Any Pokémon (including the holder) entered the field. */
    open fun onAnySwitchIn(c: EffectCtx, mon: BattleMon) {}
    open fun onWeatherChange(c: EffectCtx) {}
    open fun onTerrainChange(c: EffectCtx) {}

    // ---- move flow (broadcast to user, target, both sides and field)
    /** Before the holder executes a move. Return false to prevent it (sleep, flinch, paralysis...). */
    open fun onBeforeMove(c: EffectCtx, mc: MoveCtx): Boolean = true
    /** Return true if the holder cannot select or use [moveId] (Taunt, Choice lock, Disable...). */
    open fun onDisableMove(c: EffectCtx, moveId: String): Boolean = false
    open fun onModifyPriority(c: EffectCtx, move: ActiveMove, user: BattleMon, priority: Int): Int = priority
    /** Fractional priority bonus within a bracket (Quick Claw, Lagging Tail): +0.1 moves first, -0.1 last. */
    open fun onFractionalPriority(c: EffectCtx, move: ActiveMove, user: BattleMon): Double = 0.0
    open fun onModifyMove(c: EffectCtx, mc: MoveCtx) {}
    open fun onModifyType(c: EffectCtx, mc: MoveCtx) {}
    /** Return false to make the move fail ("But it failed!"). Runs once per move use against the target. */
    open fun onTryMove(c: EffectCtx, mc: MoveCtx): Boolean = true
    /** Return false to block the hit (Protect, Volt Absorb, Levitate-style immunity, Bulletproof...). */
    open fun onTryHit(c: EffectCtx, mc: MoveCtx): Boolean = true
    /** Return true to make the target immune to the move's type, false to remove immunity, null for no opinion. */
    open fun onImmunity(c: EffectCtx, mc: MoveCtx, type: String): Boolean? = null
    open fun onModifyAccuracy(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
    /** Return true to force a hit (No Guard, Lock-On), false to force a miss (semi-invulnerable), null otherwise. */
    open fun onAccuracyOverride(c: EffectCtx, mc: MoveCtx): Boolean? = null
    open fun onModifyCritRatio(c: EffectCtx, mc: MoveCtx, ratio: Int): Int = ratio
    /** Return false to prevent a critical hit (Battle Armor, Lucky Chant). */
    open fun onCriticalHit(c: EffectCtx, mc: MoveCtx): Boolean = true
    open fun onBasePower(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
    open fun onModifyAtk(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
    open fun onModifySpA(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
    open fun onModifyDef(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
    open fun onModifySpD(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
    open fun onModifyStab(c: EffectCtx, mc: MoveCtx, stab: Double): Double = stab
    /** Change type effectiveness (returns the new multiplier). */
    open fun onEffectiveness(c: EffectCtx, mc: MoveCtx, type: String, multiplier: Double): Double = multiplier
    /** Final damage modifiers (Life Orb, Expert Belt, Multiscale, screens, resist berries...). */
    open fun onModifyDamage(c: EffectCtx, mc: MoveCtx, mod: Mod) {}
    /** Called after damage has been dealt by a hit that did not hit a substitute. */
    open fun onDamagingHit(c: EffectCtx, mc: MoveCtx, damage: Int) {}
    /** After all hits and secondary effects of the move. */
    open fun onAfterMove(c: EffectCtx, mc: MoveCtx) {}
    /** After the move's secondary effects on each target (Red Card, Eject Button, Color Change). */
    open fun onAfterMoveSecondary(c: EffectCtx, mc: MoveCtx) {}
    /** Holder's move knocked out [target] (Moxie, Beast Boost). */
    open fun onSourceFaint(c: EffectCtx, target: BattleMon, move: ActiveMove?) {}
    /** Modify secondary effects before they are rolled (Serene Grace, Shield Dust, Sheer Force). */
    open fun onModifySecondaries(c: EffectCtx, mc: MoveCtx, secondaries: MutableList<pkmn.engine.data.SecondaryEffect>) {}

    // ---- status / boosts / HP (broadcast to target, its side and the field)
    /** Return false to block [status] being set on [target]. */
    open fun onSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?, sourceEffect: String?): Boolean = true
    open fun onAfterSetStatus(c: EffectCtx, target: BattleMon, status: MajorStatus, source: BattleMon?) {}
    /** Return false to block a volatile condition (Own Tempo vs confusion, Oblivious vs attract...). */
    open fun onTryAddVolatile(c: EffectCtx, target: BattleMon, volatile: String, source: BattleMon?): Boolean = true
    /** Adjust or cancel boosts before they apply. Mutate [boosts]. */
    open fun onTryBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>, source: BattleMon?, sourceEffect: String?) {}
    /** Contrary / Simple. */
    open fun onChangeBoost(c: EffectCtx, target: BattleMon, boosts: MutableMap<String, Int>) {}
    open fun onAfterBoost(c: EffectCtx, target: BattleMon, boosts: Map<String, Int>, source: BattleMon?) {}
    /** Modify HP loss before it happens. Return the new amount (0 prevents it). [sourceEffect] is the move id or effect id. */
    open fun onDamage(c: EffectCtx, target: BattleMon, amount: Int, source: BattleMon?, sourceEffect: String, isMove: Boolean): Int = amount
    /** Return the amount actually healed (0 prevents healing, e.g. Heal Block). */
    open fun onTryHeal(c: EffectCtx, target: BattleMon, amount: Int, sourceEffect: String): Int = amount
    open fun onModifySpe(c: EffectCtx, mon: BattleMon, mod: Mod) {}
    open fun onModifyWeight(c: EffectCtx, mon: BattleMon, weight: Double): Double = weight
    /** Return true if [mon] cannot switch out (trapping). */
    open fun onTrapPokemon(c: EffectCtx, mon: BattleMon): Boolean = false
    /** Return false to prevent being forced out (Suction Cups, Ingrain). */
    open fun onDragOut(c: EffectCtx, mon: BattleMon): Boolean = true
    /** Return false to prevent the holder's item from being removed. */
    open fun onTakeItem(c: EffectCtx, mon: BattleMon, item: String, source: BattleMon?): Boolean = true
    open fun onEatItem(c: EffectCtx, mon: BattleMon, item: String) {}
    /** Is [mon] grounded? Return true/false to override, null for default. */
    open fun onGrounded(c: EffectCtx, mon: BattleMon): Boolean? = null
    /** Return false if the holder's protective stance does not stop [mc] (used by Protect variants via onTryHit). */
    open fun onFlinch(c: EffectCtx, mon: BattleMon): Boolean = true
}

/** Registry of effect implementations. Unknown ids resolve to a no-op effect so missing implementations never crash. */
object Effects {
    private val abilities = HashMap<String, Effect>()
    private val items = HashMap<String, Effect>()
    private val conditions = HashMap<String, Effect>()
    private val moves = HashMap<String, MoveScript>()
    @Volatile private var initialized = false
    private var initThread: Thread? = null

    fun registerAbility(e: Effect) { abilities[e.id] = e }
    fun registerItem(e: Effect) { items[e.id] = e }
    fun registerCondition(e: Effect) { conditions[e.id] = e }
    fun registerMove(m: MoveScript) { moves[m.id] = m }

    fun ensureInit() {
        // Registration code may look up effects registered earlier; allow re-entry from the initializing thread.
        if (initialized || initThread === Thread.currentThread()) return
        synchronized(this) {
            if (initialized) return
            initThread = Thread.currentThread()
            try {
                Conditions.registerAll()
                Abilities.registerAll()
                Items.registerAll()
                MoveScripts.registerAll()
            } finally {
                initThread = null
            }
            initialized = true
        }
    }

    fun ability(id: String): Effect? { ensureInit(); return abilities[id] }
    fun item(id: String): Effect? { ensureInit(); return items[id] }
    fun condition(id: String): Effect? { ensureInit(); return conditions[id] }
    fun move(id: String): MoveScript? { ensureInit(); return moves[id] }

    fun implementedAbilities(): Set<String> { ensureInit(); return abilities.keys }
    fun implementedItems(): Set<String> { ensureInit(); return items.keys }
    fun implementedConditions(): Set<String> { ensureInit(); return conditions.keys }
    fun implementedMoveScripts(): Set<String> { ensureInit(); return moves.keys }
}

/**
 * Optional custom behaviour for a specific move, layered on top of the data-driven move pipeline.
 * Moves without a script are fully described by their [pkmn.engine.data.MoveData].
 */
open class MoveScript(id: String) : Effect(id, EffectKind.MOVE) {
    /** Return false if the move fails before accuracy (e.g. Fake Out after the first turn). */
    open fun onTry(b: Battle, mc: MoveCtx): Boolean = true
    /** Override base power (Gyro Ball, Eruption, Acrobatics...). */
    open fun basePower(b: Battle, mc: MoveCtx, bp: Int): Int = bp
    /** Fully custom damage (Seismic Toss, Super Fang, Counter...). Return null to use the normal formula. */
    open fun fixedDamage(b: Battle, mc: MoveCtx): Int? = null
    /** Called after the move hits the target (per hit). */
    open fun onHit(b: Battle, mc: MoveCtx) {}
    /** For status moves: custom effect. Return false if it failed. Null means "use data-driven behaviour". */
    open fun onUseStatus(b: Battle, mc: MoveCtx): Boolean? = null
    /** Called after the move completes (all hits) regardless of outcome when the move was executed. */
    open fun onAfterUse(b: Battle, mc: MoveCtx, hitAny: Boolean) {}
    /** Moves that need a charge turn (Solar Beam etc.). Return true to skip the charge (e.g. in sun). */
    open fun skipCharge(b: Battle, user: BattleMon): Boolean = false
    open val chargeMessage: String? = null
    /** Number of hits override (e.g. Population Bomb, Triple Axel handled via data). */
    open fun hitCount(b: Battle, mc: MoveCtx): Int? = null
}
