package pkmn.game.battle

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.TextureRegion
import pkmn.engine.battle.*
import pkmn.engine.campaign.GameContext
import pkmn.game.PkmnGame
import pkmn.game.anim.AnimationPlayer
import pkmn.game.anim.Clip
import pkmn.game.anim.Ease
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.screens.BagScreen
import pkmn.game.screens.MoveLearnFlow
import pkmn.game.screens.PartyScreen
import pkmn.game.screens.Screen
import pkmn.game.ui.TextBox
import pkmn.game.ui.YesNo

/**
 * Battle presentation. The engine resolves whole turns instantly; [BattleFlow] decides what the player is asked next,
 * and this screen replays the engine's [BattleEvent]s through a step sequencer of text, elapsed-time animation clips
 * and HP/EXP bar tweens. Animations only mirror events that happened; skipping them (B, or animations off) applies
 * each clip's end state, so the screen always matches the engine.
 */
class BattleScreen(
    game: PkmnGame,
    private val ctx: GameContext,
    private val battle: Battle,
    private val trainerId: String?,
    private val musicOverride: String?,
    private val onEnd: (BattleOutcome) -> Unit,
) : Screen(game) {
    override val blocksAutosave get() = true
    private val dex = ctx.dex
    private val ai = BattleAI()
    private val flow = BattleFlow(battle, ai)
    private val trainer = trainerId?.let { ctx.content.trainers[it] }
    private val animData = BattleAnimData.load(game.contentSource)
    private val anim = AnimationPlayer()

    /** What is currently displayed for each side (lags behind the engine state while events play). */
    private class SideView {
        var teamIndex = -1
        var species: String? = null
        var name = ""
        var level = 1
        var hp = 0f
        var maxHp = 1
        var status: String? = null
        var visible = false
        var hud = false
        var gender = ""
        var dynamax = false
        var tera: String? = null
        // Animation parameters (all neutral at rest).
        var offX = 0f
        var offY = 0f
        var scale = 1f
        var drop = 0f
        var alpha = 1f
        var whiten = 0f
        var blink = false
        var exp = 0f
    }
    private val views = arrayOf(SideView(), SideView())

    /** Presentation-side party indicator state per side: 0 healthy, 1 status, 2 fainted (lags the engine like [views]). */
    private val balls = Array(2) { s -> IntArray(battle.sides[s].mons.size) }

    // Text box
    private var text: String? = null
    private var textShown = 0f
    private var textHold = 0f
    private var textRead = true

    // Step sequencer
    private sealed class Step {
        class Show(val text: String) : Step()
        object WaitText : Step()
        class Play(val clip: Clip) : Step()
        object WaitAnim : Step()
        class Do(val f: () -> Unit) : Step()
        /** Waits for an external prompt (move learning) to finish. */
        object WaitPrompt : Step()
    }
    private val steps = ArrayDeque<Step>()
    private var promptOpen = false

    // Menus
    private var cursor = 0
    private var moveCursor = 0
    private var gimmickArmed: Gimmick? = null
    private var menu = Menu.NONE
    private enum class Menu { NONE, ACTION, GIMMICKS, MOVES }
    private var gimmickCursor = 0
    /** True when the armed gimmick was chosen from the GIMMICK list (B returns there) rather than the SELECT shortcut. */
    private var fromGimmickMenu = false

    // Effects
    private var pendingSE = 1.0
    private var fx: MoveFx? = null
    private var ball: BallSprite? = null
    private var flashScreen = 0f
    private class DamageNumber(val side: Int, val amount: Int, val crit: Boolean, var t: Float = 0f)
    private var damageNumber: DamageNumber? = null
    private var shake = 0f
    private var portrait = 0f // 0 hidden right, 1 fully in
    private var showBalls = true
    private var ended = false
    private var finishing = false

    private class BallSprite(var x: Float, var y: Float, var open: Boolean = false, var wobble: Float = 0f, var stars: Float = 0f, val itemId: String? = null)

    override fun onEnter() {
        val music = musicOverride ?: trainer?.music ?: trainer?.let { ctx.content.trainerClasses[it.trainerClass]?.music } ?: if (trainerId != null) "battle_trainer" else "battle_wild"
        game.audio.playMusic(music, fadeMs = 0)
        for (s in 0..1) battle.sides[s].mons.forEachIndexed { i, m -> balls[s][i] = if (m.fainted) 2 else if (m.status != null) 1 else 0 }
        for (side in battle.sides) side.mons.forEach { game.audio.prefetchCry(it.mon.speciesId) }
        battle.start()
        applyAnimOptions()
        intro()
    }

    private fun applyAnimOptions() {
        anim.cosmetic = game.options.battleAnimations && !game.options.reduceMotion
        anim.speed = when (game.options.animSpeed) { 1 -> 1.6f; 2 -> 2.5f; else -> 1f }
    }

    private fun clipTime(name: String, default: Float) = animData.clip(name, default)

    // ================================================================= intro

    private fun intro() {
        if (trainer != null) {
            play(Clip("intro_slide", clipTime("intro_slide", 0.9f), onUpdate = { portrait = Ease.outQuad(it) }, onEnd = { portrait = 1f }))
            say("${trainerTitle()} wants to battle!")
            steps.add(Step.Do { flow.startPresenting() })
            // The trainer steps aside before throwing the first Poké Ball.
            play(Clip("intro_out", clipTime("victory_slide", 0.7f) * 0.6f, onUpdate = { portrait = 1f - Ease.inQuad(it) }, onEnd = { portrait = 0f }))
        } else steps.add(Step.Do { flow.startPresenting() })
    }

    private fun trainerTitle(): String {
        val t = trainer ?: return ""
        val cls = ctx.content.trainerClasses[t.trainerClass]?.name ?: ""
        return ctx.format("$cls ${t.name}").trim()
    }

    // ================================================================= sequencer helpers

    private fun say(t: String) { steps.add(Step.Show(t)); steps.add(Step.WaitText) }
    private fun show(t: String) { steps.add(Step.Show(t)) }
    private fun play(c: Clip) { steps.add(Step.Play(c)); steps.add(Step.WaitAnim) }
    private fun playAsync(c: Clip) { steps.add(Step.Play(c)) }
    private fun doNow(f: () -> Unit) { steps.add(Step.Do(f)) }

    private fun showText(s: String) {
        text = s; textHold = 0f; textRead = false
        textShown = if (game.options.textSpeed >= 3) s.length.toFloat() else 0f
    }

    /** Typewriter + read detection; called every frame. */
    private fun tickText(dt: Float, input: InputState) {
        val t = text ?: return
        if (textRead) return
        val cps = when (game.options.textSpeed) { 0 -> 30f; 1 -> 60f; 2 -> 120f; else -> 10000f }
        if (textShown < t.length) {
            textShown = if (input.justPressed(Button.A)) t.length.toFloat() else textShown + dt * cps
            return
        }
        textHold += dt
        if (input.justPressed(Button.A) || input.justPressed(Button.B) || textHold > 1.1f) textRead = true
    }

    /** Runs steps until one blocks. Returns true when the sequencer is idle. */
    private fun runSteps(): Boolean {
        var guard = 0
        while (steps.isNotEmpty() && guard++ < 200) {
            when (val s = steps.first()) {
                is Step.Show -> { if (text != null && !textRead) return false; showText(s.text) }
                Step.WaitText -> if (text != null && !textRead) return false
                is Step.Play -> anim.enqueue(s.clip)
                Step.WaitAnim -> if (anim.busy) return false
                is Step.Do -> s.f()
                Step.WaitPrompt -> if (promptOpen) return false
            }
            steps.removeFirst()
        }
        return steps.isEmpty() && !anim.busy && (text == null || textRead)
    }

    // ================================================================= update

    override fun update(dt: Float, input: InputState) {
        applyAnimOptions()
        // B fast-forwards running animations; the end states keep the screen consistent with the engine.
        if (anim.busy && input.justPressed(Button.B)) anim.skipAll()
        anim.update(dt)
        tickText(dt, input)
        if (flashScreen > 0) flashScreen -= dt
        if (shake > 0) shake -= dt

        when (menu) {
            Menu.ACTION -> { updateActionMenu(input); return }
            Menu.GIMMICKS -> { updateGimmickMenu(input); return }
            Menu.MOVES -> { updateMoveMenu(input); return }
            Menu.NONE -> {}
        }
        if (promptOpen) return
        if (!runSteps()) return

        when (flow.phase) {
            BattleFlow.Phase.INTRO -> {}
            BattleFlow.Phase.PRESENTING -> {
                val e = flow.nextEvent()
                if (e != null) { present(e); return }
                flow.settle()
                onSettled()
            }
            BattleFlow.Phase.ENDING, BattleFlow.Phase.DONE -> beginEnd()
            else -> onSettled()
        }
    }

    /** Opens whatever the flow's new phase asks of the player. */
    private fun onSettled() {
        when (flow.phase) {
            BattleFlow.Phase.CHOOSE_ACTION -> { text = null; menu = Menu.ACTION }
            BattleFlow.Phase.FORCED_SWITCH -> openForcedParty()
            BattleFlow.Phase.NEXT_MON_PROMPT -> askYesNo("Use next POKéMON?") { yes -> flow.answerNextMon(yes) }
            BattleFlow.Phase.SHIFT_PROMPT -> {
                val name = flow.upcomingFoeName() ?: "a POKéMON"
                askYesNo("Will ${ctx.state.playerName} change POKéMON?", lead = "${battle.opponent.name} is about to use $name.") { yes ->
                    flow.answerShift(yes)
                    if (yes) openShiftParty()
                }
            }
            BattleFlow.Phase.SHIFT_PARTY -> openShiftParty()
            BattleFlow.Phase.ENDING -> beginEnd()
            else -> {}
        }
    }

    private fun askYesNo(question: String, lead: String? = null, then: (Boolean) -> Unit) {
        promptOpen = true
        text = null
        // The YES/NO box appears together with the question (as in GSC), so answering takes one press.
        val ask = { game.push(YesNo(game, question) { yes -> promptOpen = false; then(yes) }) }
        if (lead != null) game.push(TextBox(game, lead) { ask() }) else ask()
    }

    // ================================================================= event presentation

    private fun sideCenter(side: Int) = if (side == 1) 196f to 50f else 64f to 112f

    private fun present(e: BattleEvent) {
        when (e) {
            is BattleEvent.Message -> say(e.text)
            is BattleEvent.TurnStart -> {}
            is BattleEvent.SwitchIn -> presentSwitchIn(e)
            is BattleEvent.SwitchOut -> {
                val v = views[e.side]
                show(e.text)
                play(Clip("recall", clipTime("recall", 0.3f), onUpdate = { v.scale = 1f - Ease.inQuad(it); v.whiten = it }, onEnd = {
                    v.visible = false; v.hud = false; v.scale = 1f; v.whiten = 0f
                }))
                steps.add(Step.WaitText)
            }
            is BattleEvent.MoveUsed -> show(e.text)
            is BattleEvent.MoveEffect -> presentMoveEffect(e)
            is BattleEvent.HpChange -> presentHp(e)
            is BattleEvent.Effectiveness -> pendingSE = e.multiplier
            is BattleEvent.Faint -> {
                val v = views[e.side]
                steps.add(Step.WaitText)
                doNow { game.audio.playSfx("faint") }
                // Sink, slide away from the centre (player left, foe right) and fade out before the faint message.
                val dir = if (e.side == 0) -1f else 1f
                play(Clip("faint", clipTime("faint", 0.45f), essential = true, onUpdate = { t ->
                    v.drop = Ease.inQuad(t); v.offX = dir * 24f * Ease.inQuad(t); v.alpha = 1f - t
                }, onEnd = {
                    v.visible = false; v.hud = false; v.drop = 0f; v.offX = 0f; v.alpha = 1f
                }))
                // Gold/Silver/Crystal: the wild victory theme starts as soon as the wild Pokémon faints.
                if (e.side == 1 && battle.opponent.isWild) doNow { game.audio.playMusic("victory_wild", 0) }
                // Party indicators update the moment the faint is shown.
                doNow { balls[e.side].let { if (e.teamIndex in it.indices) it[e.teamIndex] = 2 } }
                say(e.text)
            }
            is BattleEvent.StatusChange -> {
                val v = views[e.side]
                doNow {
                    if (v.teamIndex == e.teamIndex) v.status = e.status
                    balls[e.side].let { if (e.teamIndex in it.indices && it[e.teamIndex] != 2) it[e.teamIndex] = if (e.status != null) 1 else 0 }
                }
                val st = e.status
                if (st != null) {
                    doNow { game.audio.playSfx("status_inflicted") }
                    val (cx, cy) = sideCenter(e.side)
                    play(fxClip("status", MoveFx("rings", statusColor(st), cx, cy, cx, cy), clipTime("status", 0.45f)))
                }
                if (e.text.isNotEmpty()) say(e.text)
            }
            is BattleEvent.StatChange -> {
                doNow { game.audio.playSfx(if (e.amount > 0) "stat_up" else "stat_down") }
                val v = views[e.side]
                play(Clip("stat", clipTime("stat", 0.5f), onUpdate = { v.offY = (if (e.amount > 0) -3f else 3f) * Ease.pulse(it) }, onEnd = { v.offY = 0f }))
                say(e.text)
            }
            is BattleEvent.FormeChange -> {
                val v = views[e.side]
                doNow { game.audio.playSfx("evolution_finish") }
                play(Clip("transform", clipTime("transform", 0.8f), onStart = { flashScreen = 0f }, onUpdate = { t ->
                    v.whiten = Ease.pulse(t)
                }, onEnd = {
                    v.whiten = 0f
                    when {
                        e.kind == "dynamax" || e.kind == "gigantamax" -> { v.dynamax = true; if (e.kind == "gigantamax") v.species = e.speciesId }
                        e.kind == "undynamax" -> { v.dynamax = false; v.species = battle.sides[e.side].mons[e.teamIndex].species.id }
                        e.kind.startsWith("tera:") -> v.tera = e.kind.removePrefix("tera:")
                        e.kind == "illusion" -> { val bm = battle.sides[e.side].mons[e.teamIndex]; v.species = e.speciesId; v.name = bm.name; v.level = bm.level }
                        else -> v.species = e.speciesId
                    }
                }))
                if (e.text.isNotEmpty()) say(e.text)
            }
            is BattleEvent.FieldChange -> say(e.text)
            is BattleEvent.Animation -> {
                // Mega Evolution, Primal Reversion, Ultra Burst and Z-Power: only emitted when they really happen.
                val (cx, cy) = sideCenter(e.side)
                play(fxClip(e.id, MoveFx("aura", Palette.HIGHLIGHT, cx, cy, cx, cy), clipTime("transform", 0.8f), flash = true))
            }
            is BattleEvent.CatchAttempt -> presentCatch(e)
            is BattleEvent.ExpGain -> presentExp(e)
            is BattleEvent.LevelUp -> {
                doNow { game.audio.playSfx("level_up") }
                val v = views[0]
                if (v.teamIndex == e.teamIndex) {
                    play(Clip("level_up", clipTime("level_up", 0.45f), onUpdate = { v.whiten = Ease.pulse(it) * 0.6f }, onEnd = {
                        v.whiten = 0f; v.level = e.level; v.exp = 0f
                    }))
                }
                say(e.text)
            }
            is BattleEvent.LearnMovePrompt -> {
                steps.add(Step.WaitText)
                doNow {
                    val mon = battle.player.mons[e.teamIndex].mon
                    promptOpen = true
                    text = null
                    MoveLearnFlow.offer(game, ctx, mon, e.moveId) { promptOpen = false }
                }
                steps.add(Step.WaitPrompt)
            }
            is BattleEvent.End -> {}
        }
    }

    private fun statusColor(s: String) = when (s) {
        "brn" -> Palette.type("Fire"); "par" -> Palette.type("Electric"); "psn", "tox" -> Palette.type("Poison")
        "slp" -> Palette.GREY; "frz" -> Palette.type("Ice"); else -> Palette.SHADE
    }

    private fun fxClip(name: String, f: MoveFx, duration: Float, flash: Boolean = false) = Clip(name, duration,
        onStart = { fx = f; if (flash && anim.cosmetic) flashScreen = 0.3f }, onUpdate = { f.t = it }, onEnd = { if (fx === f) fx = null })

    private fun presentSwitchIn(e: BattleEvent.SwitchIn) {
        val v = views[e.side]
        val bm = battle.sides[e.side].mons[e.teamIndex]
        val atStart = flow.phase == BattleFlow.Phase.INTRO || !v.hasAppeared()
        val wildLead = battle.sides[e.side].isWild
        steps.add(Step.WaitText)
        doNow {
            v.teamIndex = e.teamIndex; v.species = e.speciesId; v.name = bm.shownName; v.level = bm.illusion?.level ?: bm.level
            v.maxHp = e.maxHp; v.hp = e.hp.toFloat(); v.status = bm.mon.status?.id
            v.dynamax = false; v.tera = null; v.drop = 0f; v.alpha = 1f; v.whiten = 0f; v.offX = 0f; v.offY = 0f
            v.gender = when (bm.mon.gender) { pkmn.engine.mon.Gender.MALE -> "♂"; pkmn.engine.mon.Gender.FEMALE -> "♀"; else -> "" }
            if (e.side == 0) v.exp = expFraction(bm)
            appeared[e.side] = true
        }
        if (wildLead && atStart) {
            // GSC: the wild Pokémon slides in, then "Wild X appeared!".
            play(Clip("wild_slide", clipTime("wild_slide", 0.7f), onStart = { v.visible = true; v.scale = 1f }, onUpdate = { v.offX = -140f * (1f - Ease.outQuad(it)) }, onEnd = {
                v.offX = 0f; v.hud = true; game.audio.playCry(e.speciesId)
            }))
            say(e.text)
            return
        }
        // Trainer / player: "X sent out Y!" while the ball flies, opens, and the Pokémon grows out of it.
        show(e.text)
        val (cx, cy) = sideCenter(e.side)
        val fromX = if (e.side == 1) 256f else 0f
        val fromY = if (e.side == 1) 20f else 150f
        play(Clip("ball_throw", clipTime("ball_throw", 0.35f), onStart = { game.audio.playSfx("ball_throw"); ball = BallSprite(fromX, fromY) }, onUpdate = { t ->
            ball?.let { it.x = fromX + (cx - 8 - fromX) * t; it.y = fromY + (cy + 10 - fromY) * t - 24 * Ease.pulse(t) }
        }, onEnd = { ball = null }))
        play(Clip("ball_pop", clipTime("ball_pop", 0.3f), essential = true, onStart = { v.visible = true; v.scale = 0f; v.whiten = 1f; if (anim.cosmetic) flashScreen = 0.12f }, onUpdate = { t ->
            v.scale = Ease.outQuad(t); v.whiten = 1f - t
        }, onEnd = { v.scale = 1f; v.whiten = 0f; v.visible = true; v.hud = true; game.audio.playCry(e.speciesId) }))
        steps.add(Step.WaitText)
    }

    private val appeared = BooleanArray(2)
    private fun SideView.hasAppeared() = appeared[views.indexOf(this)]

    private fun presentMoveEffect(e: BattleEvent.MoveEffect) {
        val user = views[e.side]
        val self = e.targetSide == e.side
        val style = animData.styleFor(e.moveId, e.moveType, e.category, self)
        val (ux, uy) = sideCenter(e.side)
        val (tx, ty) = sideCenter(e.targetSide)
        val dir = if (e.side == 0) 1f else -1f
        if (e.category != "Status") {
            play(Clip("lunge", clipTime("lunge", 0.14f), onUpdate = { user.offX = dir * 8f * Ease.pulse(it) }, onEnd = { user.offX = 0f }))
        }
        val f = MoveFx(style, Palette.type(e.moveType), ux, uy, tx, ty)
        play(fxClip("move:${e.moveId}", f, animData.styleDuration(style)).let { c ->
            if (style == "quake") Clip(c.name, c.duration, onStart = { c.onStart(); shake = c.duration }, onUpdate = c.onUpdate, onEnd = { c.onEnd(); shake = 0f }) else c
        })
    }

    private fun presentHp(e: BattleEvent.HpChange) {
        val v = views[e.side]
        if (e.cause == "levelup" || e.cause == "dynamax" || e.cause == "undynamax") {
            doNow { if (v.teamIndex == e.teamIndex) { v.maxHp = e.maxHp; v.hp = e.hp.toFloat() } }
            return
        }
        // A revived Pokémon (Revival Blessing) is usable again.
        if (e.delta > 0 && e.hp == e.delta) doNow { balls[e.side].let { if (e.teamIndex in it.indices) it[e.teamIndex] = 0 } }
        val se = pendingSE
        pendingSE = 1.0
        val isAttack = e.delta < 0 && e.cause == "move"
        if (isAttack) {
            doNow { game.audio.playSfx(when { se > 1 -> "hit_super_effective"; se < 1 -> "hit_not_very_effective"; else -> "hit_normal" }) }
            val crit = HitFx.critFollows(battle.events, flow.cursor)
            val strength = HitFx.flashStrength(kotlin.math.abs(e.delta).toFloat() / maxOf(1, e.maxHp), crit)
            val number = DamageNumber(e.side, -e.delta, crit)
            if (crit) doNow { if (anim.cosmetic) { flashScreen = 0.2f; shake = 0.2f } }
            play(Clip("hit_blink", clipTime("hit_blink", 0.32f), onStart = { damageNumber = number }, onUpdate = { t ->
                v.blink = (t * 6).toInt() % 2 == 0 && t < 1f
                number.t = t
            }, onEnd = { v.blink = false }))
            if (anim.cosmetic) {
                // White-out pulse after the blink, then fade back; the number floats up during the HP drain.
                play(Clip("hit_flash", clipTime("hit_flash", if (crit) 0.3f else 0.18f), onUpdate = { t -> v.whiten = strength * Ease.pulse(t) }, onEnd = { v.whiten = 0f }))
            }
        }
        var from = 0f
        val frac = kotlin.math.abs(e.delta).toFloat() / maxOf(1, e.maxHp)
        val d = clipTime("hp_drain_min", 0.25f) + clipTime("hp_drain_full", 0.9f) * frac.coerceIn(0f, 1f)
        play(Clip("hp", d, essential = true, onStart = {
            if (v.teamIndex == e.teamIndex) { from = v.hp; v.maxHp = e.maxHp }
        }, onUpdate = { t -> if (v.teamIndex == e.teamIndex) v.hp = from + (e.hp - from) * t }, onEnd = {
            if (v.teamIndex == e.teamIndex) { v.hp = e.hp.toFloat(); v.maxHp = e.maxHp }
            damageNumber = null
        }))
    }

    private fun presentCatch(e: BattleEvent.CatchAttempt) {
        val v = views[1]
        val (cx, cy) = sideCenter(1)
        steps.add(Step.WaitText)
        play(Clip("catch_throw", clipTime("catch_throw", 0.55f), essential = true, onStart = { game.audio.playSfx("ball_throw"); ball = BallSprite(40f, 130f, itemId = e.ball) }, onUpdate = { t ->
            ball?.let { it.x = 40f + (cx - 8 - 40f) * t; it.y = 130f + (cy + 4 - 130f) * t - 50f * Ease.pulse(t) }
        }))
        play(Clip("catch_absorb", clipTime("catch_absorb", 0.35f), onStart = { ball?.open = true }, onUpdate = { t -> v.scale = 1f - t; v.whiten = t }, onEnd = {
            v.scale = 0f; v.whiten = 0f; v.visible = false; ball?.open = false
        }))
        play(Clip("catch_drop", 0.2f, onUpdate = { t -> ball?.let { it.y = cy + 4 + 30 * Ease.inQuad(t) } }))
        repeat(e.shakes.coerceIn(0, 3)) {
            play(Clip("catch_shake", clipTime("catch_shake", 0.55f), essential = true, onStart = { game.audio.playSfx("ball_shake") },
                onUpdate = { t -> ball?.wobble = kotlin.math.sin(t * 2 * Math.PI).toFloat() * 3f }, onEnd = { ball?.wobble = 0f }))
        }
        if (e.caught) {
            play(Clip("catch_click", clipTime("catch_result", 0.45f), essential = true, onStart = { game.audio.playSfx("catch_success") }, onUpdate = { t -> ball?.stars = t }, onEnd = {
                ball?.stars = 0f; v.visible = false; v.hud = false; balls[1].fill(0)
            }))
        } else {
            play(Clip("catch_break", clipTime("catch_result", 0.45f), essential = true, onStart = { ball?.open = true; if (anim.cosmetic) flashScreen = 0.1f }, onUpdate = { t ->
                v.visible = true; v.scale = t
            }, onEnd = { ball = null; v.visible = true; v.scale = 1f; v.hud = true }))
        }
    }

    private fun presentExp(e: BattleEvent.ExpGain) {
        say(e.text)
        val v = views[0]
        if (v.teamIndex != e.teamIndex) return // only the Pokémon on the field shows its EXP bar
        // Fill to the end of the bar when a level-up follows for the same Pokémon, otherwise to the final value.
        val levelsUp = upcomingLevelUp(e.teamIndex)
        val target = if (levelsUp) 1f else expFraction(battle.player.mons[e.teamIndex])
        var from = 0f
        play(Clip("exp", clipTime("exp_fill_full", 0.8f), essential = true, onStart = { from = v.exp }, onUpdate = { t ->
            if (v.teamIndex == e.teamIndex) v.exp = from + (target - from) * t
        }, onEnd = { if (v.teamIndex == e.teamIndex) v.exp = target }))
    }

    /** True if a LevelUp for [teamIndex] follows before the next ExpGain/turn. */
    private fun upcomingLevelUp(teamIndex: Int): Boolean {
        for (i in flow.cursor until battle.events.size) when (val ev = battle.events[i]) {
            is BattleEvent.LevelUp -> if (ev.teamIndex == teamIndex) return true
            is BattleEvent.ExpGain, is BattleEvent.TurnStart, is BattleEvent.SwitchIn -> return false
            else -> {}
        }
        return false
    }

    private fun expFraction(bm: BattleMon): Float {
        val m = bm.mon
        if (m.level >= 100) return 1f
        val gr = m.species(dex).growthRate
        val lo = gr.expForLevel(m.level); val hi = gr.expForLevel(m.level + 1)
        return ((m.exp - lo).toFloat() / maxOf(1, hi - lo)).coerceIn(0f, 1f)
    }

    // ================================================================= menus

    private fun updateActionMenu(input: InputState) {
        val count = BattleMenus.actions(availableGimmicks().isNotEmpty()).size
        if (cursor >= count) cursor = count - 1
        for (b in listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT)) if (input.justPressed(b)) {
            val n = BattleMenus.navigate(cursor, b, count)
            if (n != cursor) { cursor = n; game.audio.playSfx("menu_move") }
        }
        if (!input.justPressed(Button.A)) return
        game.audio.playSfx("menu_select")
        when (cursor) {
            0 -> { menu = Menu.MOVES; gimmickArmed = null; fromGimmickMenu = false }
            1 -> { menu = Menu.NONE; openParty() }
            2 -> { menu = Menu.NONE; openBag() }
            3 -> {
                if (!battle.opponent.isWild) { menu = Menu.NONE; flow.resume(); say("No! There's no running from a trainer battle!") }
                else submit(Choice.Run)
            }
            4 -> { menu = Menu.GIMMICKS; gimmickCursor = 0; gimmickArmed = null; fromGimmickMenu = false }
        }
    }

    private fun availableGimmicks(): List<Gimmick> {
        return BattleMenus.gimmicks(battle.request(battle.player))
    }

    private fun updateGimmickMenu(input: InputState) {
        val g = availableGimmicks()
        if (g.isEmpty()) { menu = Menu.ACTION; return }
        if (input.repeat(Button.UP)) { gimmickCursor = (gimmickCursor - 1 + g.size) % g.size; game.audio.playSfx("menu_move") }
        if (input.repeat(Button.DOWN)) { gimmickCursor = (gimmickCursor + 1) % g.size; game.audio.playSfx("menu_move") }
        if (input.justPressed(Button.B)) { menu = Menu.ACTION; game.audio.playSfx("menu_cancel"); return }
        if (input.justPressed(Button.A)) {
            gimmickArmed = g[gimmickCursor.coerceIn(0, g.size - 1)]
            fromGimmickMenu = true
            menu = Menu.MOVES
            game.audio.playSfx("menu_select")
        }
    }

    private fun updateMoveMenu(input: InputState) {
        val active = battle.player.active ?: return
        val slots = active.moveSlots
        moveCursor = BattleMenus.clampCursor(moveCursor, slots.size)
        if (input.repeat(Button.UP)) { moveCursor = (moveCursor - 1 + slots.size) % slots.size; game.audio.playSfx("menu_move") }
        if (input.repeat(Button.DOWN)) { moveCursor = (moveCursor + 1) % slots.size; game.audio.playSfx("menu_move") }
        if (input.justPressed(Button.SELECT)) {
            val g = availableGimmicks()
            if (g.isNotEmpty()) {
                val i = g.indexOf(gimmickArmed)
                gimmickArmed = if (i + 1 < g.size) g[i + 1] else null
                fromGimmickMenu = false
                game.audio.playSfx("menu_select")
            }
        }
        if (input.justPressed(Button.B)) {
            // Backs out to the gimmick list when a gimmick was picked there, otherwise to the action menu.
            menu = if (gimmickArmed != null && availableGimmicks().isNotEmpty() && fromGimmickMenu) Menu.GIMMICKS else Menu.ACTION
            gimmickArmed = null; game.audio.playSfx("menu_cancel"); return
        }
        if (input.justPressed(Button.A)) {
            val req = battle.request(battle.player) as? Request.Move ?: return
            val anyUsable = req.usableMoves.any { it }
            if (anyUsable && !req.usableMoves.getOrElse(moveCursor) { false }) {
                game.audio.playSfx("error")
                menu = Menu.NONE; flow.resume()
                val slot = slots[moveCursor]
                say(if (slot.pp <= 0) "There's no PP left for this move!" else "${active.name} can't use ${dex.move(slot.id).name}!")
                return
            }
            if (gimmickArmed == Gimmick.ZMOVE && Gimmicks.zMoveFor(battle, active, dex.move(slots[moveCursor].id)) == null) { game.audio.playSfx("error"); return }
            submit(Choice.UseMove(moveCursor, gimmickArmed))
        }
    }

    private fun openParty() {
        promptOpen = true
        push(PartyScreen(game, ctx, PartyScreen.Mode.BATTLE, battle, forced = false) { chosen ->
            promptOpen = false
            if (chosen == null) { menu = Menu.ACTION; return@PartyScreen }
            submit(Choice.Switch(chosen))
        })
    }

    private fun openForcedParty() {
        promptOpen = true
        push(PartyScreen(game, ctx, PartyScreen.Mode.BATTLE, battle, forced = true) { chosen ->
            promptOpen = false
            // The party cannot be cancelled here; a rejected pick keeps the flow in FORCED_SWITCH and the party reopens.
            val err = if (chosen == null) "Choose a POKéMON." else flow.forcedPick(chosen)
            if (err != null) say(err)
        })
    }

    private fun openShiftParty() {
        promptOpen = true
        push(PartyScreen(game, ctx, PartyScreen.Mode.BATTLE, battle, forced = false, shift = true) { chosen ->
            promptOpen = false
            val err = flow.shiftPick(chosen)
            if (err != null) say(err)
        })
    }

    private fun openBag() {
        promptOpen = true
        push(BagScreen(game, ctx, BagScreen.Mode.BATTLE, battle) { item, target ->
            promptOpen = false
            if (item == null) { menu = Menu.ACTION; return@BagScreen }
            if (dex.itemOrNull(item)?.isPokeball == true && !battle.opponent.isWild) {
                flow.resume(); say("The Trainer blocked the BALL!\nDon't be a thief!"); return@BagScreen
            }
            if (dex.itemOrNull(item)?.isPokeball == true && ctx.storageFull()) {
                flow.resume(); say("The PC BOXES are full!\nYou can't catch any more POKéMON."); return@BagScreen
            }
            if (!ctx.canSpendBattleItem(battle, item)) { flow.resume(); say("You don't have any left!"); return@BagScreen }
            // The item is charged only if the battle accepts it and it takes effect ([GameContext.settleBattleItems]);
            // a rejected choice ("It won't have any effect.") leaves the bag untouched.
            submit(Choice.UseItem(item, target ?: battle.player.mons.indexOf(battle.player.active)))
        })
    }

    private fun submit(c: Choice) {
        menu = Menu.NONE
        val err = flow.submit(c)
        ctx.settleBattleItems(battle)
        gimmickArmed = null
        if (err != null) { flow.resume(); say(err) }
    }

    // ================================================================= ending

    private fun beginEnd() {
        if (finishing) return
        val outcome = flow.takeOutcome() ?: return
        finishing = true
        text = null
        if (outcome == BattleOutcome.WIN && trainer != null) {
            doNow { game.audio.playMusic(if ("leader" in trainer.trainerClass || trainer.trainerClass == "leader") "victory_gym" else "victory_trainer", 0) }
            play(Clip("victory_slide", clipTime("victory_slide", 0.7f), onUpdate = { portrait = Ease.outQuad(it) }, onEnd = { portrait = 1f }))
            say(ctx.format(trainer.defeat.ifEmpty { "..." }))
        }
        doNow { finish(outcome) }
    }

    private fun finish(outcome: BattleOutcome) {
        if (ended) return
        ended = true
        anim.skipAll()
        pop()
        onEnd(outcome)
    }

    /** One-line state summary for automation logs. */
    fun debugState(): String = "phase=${flow.phase} cursor=${flow.cursor}/${battle.events.size} steps=${steps.size}:${steps.firstOrNull()?.javaClass?.simpleName} " +
        "anim=${anim.currentName} prompt=$promptOpen menu=$menu text=${text?.take(20)} read=$textRead outcome=${battle.outcome}"

    override fun onBack(): Boolean {
        if (menu == Menu.MOVES || menu == Menu.GIMMICKS) { menu = if (menu == Menu.MOVES && gimmickArmed != null && fromGimmickMenu) Menu.GIMMICKS else Menu.ACTION; gimmickArmed = null }
        else if (anim.busy) anim.skipAll()
        return true
    }

    // ================================================================= drawing

    override fun draw(r: Renderer) {
        val bg = when (battle.environment) {
            "cave" -> Color.valueOf("c8b8a0"); "water" -> Color.valueOf("b8d0f0"); "snow" -> Color.valueOf("e8f0f8")
            "sand" -> Color.valueOf("f0e0b0"); "indoor", "building" -> Color.valueOf("e0d8d0"); else -> Color.valueOf("e8f0d8")
        }
        val sx = if (shake > 0) ((game.timeMs / 40) % 3 - 1) * 2f else 0f
        // Outdoor battles at night use a dimmer backdrop (the overworld clock decided isNight when the battle began).
        if (battle.isNight && battle.environment !in setOf("cave", "indoor", "building")) bg.mul(0.72f, 0.76f, 0.95f, 1f)
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.rect(0, 0, Renderer.W, 160, bg)
        val ground = bg.cpy().mul(0.85f, 0.85f, 0.82f, 1f)
        ellipse(r, 196 + sx.toInt(), 86, 52, 9, ground)
        ellipse(r, 64 + sx.toInt(), 152, 60, 9, ground)

        if (trainer != null && portrait > 0f) {
            val p = r.assets.trainerPortrait(trainer.portrait ?: ctx.content.trainerClasses[trainer.trainerClass]?.portrait)
            if (p != null) r.draw(p, Renderer.W - (Renderer.W - 160) * portrait, 16f)
        }
        drawMon(r, 1, 148f + sx, 0f)
        drawMon(r, 0, 16f + sx, 64f)
        fx?.draw(r)
        damageNumber?.let { drawDamageNumber(r, it) }
        ball?.let { drawBall(r, it) }
        if (views[1].hud) drawFoeHud(r)
        if (views[0].hud) drawPlayerHud(r)
        drawPartyBalls(r)
        if (flashScreen > 0 && ((game.timeMs / 60) % 2 == 0L)) r.rect(0, 0, Renderer.W, 160, Color(1f, 1f, 1f, 0.7f))

        r.frame(0, 160, Renderer.W, 64, alt = true)
        when (menu) {
            Menu.ACTION -> drawActionMenu(r)
            Menu.GIMMICKS -> drawGimmickMenu(r)
            Menu.MOVES -> drawMoveMenu(r)
            Menu.NONE -> text?.let { t ->
                val lines = r.assets.font8.wrap(t, 29)
                var left = textShown.toInt()
                lines.take(3).forEachIndexed { i, line ->
                    val n = left.coerceIn(0, line.length)
                    r.text(line.substring(0, n), 12, 172 + i * 14)
                    left -= line.length
                }
            }
        }
    }

    private fun drawDamageNumber(r: Renderer, n: DamageNumber) {
        if (!anim.cosmetic) return
        val (cx, cy) = sideCenter(n.side)
        val label = if (n.crit) "-${n.amount}!" else "-${n.amount}"
        val rise = Ease.outQuad(n.t.coerceIn(0f, 1f)) * 8f
        r.text(label, cx.toInt() - r.textWidth(label) / 2, (cy - 24f - rise).toInt().coerceAtLeast(14), if (n.crit) Color(0.95f, 0.5f, 0f, 1f) else Palette.TEXT)
    }

    private fun ellipse(r: Renderer, cx: Int, cy: Int, rx: Int, ry: Int, c: Color) {
        for (dy in -ry..ry) {
            val w = (rx * kotlin.math.sqrt(1.0 - (dy * dy).toDouble() / (ry * ry))).toInt()
            r.rect(cx - w, cy + dy, w * 2, 1, c)
        }
    }

    private fun drawMon(r: Renderer, side: Int, x: Float, y: Float) {
        val v = views[side]
        if (!v.visible || v.species == null || v.blink || v.scale <= 0f) return
        val tex = r.assets.pokemon(v.species!!, when { side == 0 && shinyOf(side) -> "back-shiny"; side == 0 -> "back"; shinyOf(side) -> "front-shiny"; else -> "front" })
        val scale = (if (v.dynamax) 1.35f else 1f) * v.scale
        val w = tex.regionWidth * scale; val h = tex.regionHeight * scale
        val dropped = v.drop * h
        // Anchored at the bottom centre of the sprite box (y + 96).
        val left = x + v.offX + (tex.regionWidth - w) / 2
        val top = y + 96 - h + dropped + v.offY
        val visibleH = h - dropped
        if (visibleH <= 0f) return
        val region = if (v.drop > 0f) TextureRegion(tex, 0, 0, tex.regionWidth, (tex.regionHeight * (1f - v.drop)).toInt().coerceAtLeast(1)) else tex
        val tint = when {
            v.dynamax -> Color(1f, 0.55f, 0.6f, 1f)
            v.tera != null -> Palette.type(v.tera!!).cpy().lerp(Color.WHITE, 0.55f)
            else -> Color.WHITE
        }.let { if (v.alpha < 1f) it.cpy().apply { a = v.alpha.coerceIn(0f, 1f) } else it }
        r.tint(tint) { r.draw(region, left, top, w, visibleH) }
        if (v.whiten > 0.01f) {
            // Additive pass brightens the sprite toward white (send-out, recall, transformations).
            r.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
            r.tint(Color(1f, 1f, 1f, v.whiten.coerceIn(0f, 1f))) { r.draw(region, left, top, w, visibleH) }
            r.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    private fun shinyOf(side: Int): Boolean {
        val v = views[side]
        return battle.sides[side].mons.getOrNull(v.teamIndex)?.mon?.shiny == true
    }

    private fun hpBar(r: Renderer, x: Int, y: Int, w: Int, hp: Float, max: Int) {
        r.text("HP", x - 16, y - 2, small = true)
        r.rect(x - 1, y - 1, w + 2, 5, Palette.TEXT)
        r.rect(x, y, w, 3, Palette.SHADE)
        val f = (hp / maxOf(1, max)).coerceIn(0f, 1f)
        val c = when { f > 0.5f -> Palette.HP_GREEN; f > 0.2f -> Palette.HP_YELLOW; else -> Palette.HP_RED }
        r.rect(x.toFloat(), y.toFloat(), w * f, 3f, c)
    }

    private fun statusTag(r: Renderer, s: String?, x: Int, y: Int) {
        if (s == null) return
        val label = when (s) { "brn" -> "BRN"; "par" -> "PAR"; "psn", "tox" -> "PSN"; "slp" -> "SLP"; "frz" -> "FRZ"; else -> s.uppercase() }
        r.text(label, x, y, Palette.RED, small = true)
    }

    private fun drawFoeHud(r: Renderer) {
        val v = views[1]
        r.text(v.name.take(12), 8, 8)
        r.text("${v.gender}:L${v.level}", 24, 18)
        statusTag(r, v.status, 80, 18)
        hpBar(r, 32, 30, 80, v.hp, v.maxHp)
        r.rect(8, 36, 112, 1, Palette.TEXT)
        if (battle.opponent.isWild && v.species != null) {
            val base = dex.speciesOrNull(v.species!!)?.baseSpecies
            if (base != null && base in ctx.state.caught) r.text("●", 108, 8, Palette.RED, small = true)
        }
    }

    private fun drawPlayerHud(r: Renderer) {
        val v = views[0]
        r.text(v.name.take(12), 136, 96)
        r.text("${v.gender}:L${v.level}", 152, 106)
        statusTag(r, v.status, 208, 106)
        hpBar(r, 160, 118, 80, v.hp, v.maxHp)
        r.text("${v.hp.toInt().toString().padStart(3)}/${v.maxHp.toString().padStart(3)}", 168, 126)
        r.text("EXP", 136, 140, small = true)
        r.rect(158, 141, 82, 4, Palette.TEXT)
        r.rect(159f, 142f, 80 * v.exp.coerceIn(0f, 1f), 2f, Palette.EXP_BLUE)
        r.rect(248, 96, 1, 52, Palette.TEXT)
        r.rect(136, 148, 113, 1, Palette.TEXT)
    }

    /**
     * Classic party indicators: one Poké Ball per party member (up to six), healthy / status / fainted, with empty
     * slots for a party under six. Trainer battles show both sides throughout; wild battles show the player's during the
     * intro only. They reveal party size and remaining Pokémon, never species.
     */
    private fun drawPartyBalls(r: Renderer) {
        if (!showBalls) return
        val trainerBattle = !battle.opponent.isWild
        if (trainerBattle) drawBallRow(r, 1, 10, 40, rightToLeft = false)
        if (trainerBattle || flow.phase == BattleFlow.Phase.INTRO) drawBallRow(r, 0, 196, 150, rightToLeft = true)
    }

    private val ballCells: Array<TextureRegion>? by lazy {
        val sheet = game.assets.ui("ball_icons") ?: return@lazy null
        Array(4) { i -> TextureRegion(sheet, i * 8, 0, 8, 8) }
    }

    private fun drawBallRow(r: Renderer, side: Int, x0: Int, y: Int, rightToLeft: Boolean) {
        val states = balls[side]
        for (slot in 0 until 6) {
            val x = if (rightToLeft) x0 + (5 - slot) * 9 else x0 + slot * 9
            val state = if (slot < states.size) states[slot] else -1
            val cells = ballCells
            if (cells != null) {
                // ball_icons order: full, empty, fainted, status
                val cell = when (state) { 0 -> cells[0]; 1 -> cells[3]; 2 -> cells[2]; else -> cells[1] }
                r.draw(cell, x, y)
            } else when (state) {
                0 -> { r.rect(x, y, 7, 3, Palette.RED); r.rect(x, y + 4, 7, 3, Palette.WHITE); r.outline(x, y, 7, 7, Palette.TEXT) }
                1 -> { r.rect(x, y, 7, 7, Palette.SHADE); r.outline(x, y, 7, 7, Palette.TEXT) }
                2 -> { r.rect(x, y, 7, 7, Palette.TEXT) }
                else -> r.outline(x, y, 7, 7, Palette.SHADE)
            }
        }
    }

    private fun drawBall(r: Renderer, b: BallSprite) {
        val icon = r.assets.ball(b.itemId)
        val x = b.x + b.wobble; val y = b.y
        if (icon != null) r.draw(icon, x, y) else {
            r.rect(x, y, 8f, 4f, Palette.RED); r.rect(x, y + 4, 8f, 4f, Palette.WHITE); r.outline(x.toInt(), y.toInt(), 8, 8, Palette.TEXT)
        }
        if (b.open) for (i in 0 until 6) {
            val a = i * Math.PI / 3 + game.timeMs / 200.0
            r.rect(x + 8 + kotlin.math.cos(a).toFloat() * 10, y + 8 + kotlin.math.sin(a).toFloat() * 10, 2f, 2f, Palette.HIGHLIGHT)
        }
        if (b.stars > 0f) for (i in 0 until 3) {
            val sx = x + 2 + i * 6f; val sy = y - 4 - b.stars * 10 - i * 2
            r.rect(sx, sy, 3f, 3f, Palette.HP_YELLOW)
        }
    }

    private fun drawActionMenu(r: Renderer) {
        val p = battle.player.active
        val name = p?.name ?: ""
        if (r.textWidth("$name do?") <= 96) { r.text("What will", 12, 176); r.text("$name do?", 12, 192) }
        else { r.text("What will", 12, 170); r.text(name, 12, 184); r.text("do?", 12, 198) }
        r.frame(112, 160, 144, 64)
        val labels = BattleMenus.actions(availableGimmicks().isNotEmpty())
        // Three rows when GIMMICK is present, so the 144x64 frame keeps its two-column layout.
        val rowH = if (labels.size > 4) 14 else 18; val y0 = if (labels.size > 4) 172 else 178
        labels.forEachIndexed { i, s ->
            val x = (if (labels.size > 4) 128 else 136) + (i % 2) * 64; val y = y0 + (i / 2) * rowH
            r.text(s, x, y)
            if (i == cursor) r.cursor(x - 10, y)
        }
    }

    private fun drawGimmickMenu(r: Renderer) {
        r.frame(0, 160, 160, 64)
        availableGimmicks().forEachIndexed { i, g ->
            r.text(gimmickLabel(g), 18, 168 + i * 13)
            if (i == gimmickCursor) r.cursor(8, 168 + i * 13)
        }
    }

    private fun drawMoveMenu(r: Renderer) {
        val active = battle.player.active ?: return
        val slots = active.moveSlots
        r.frame(0, 160, 160, 64)
        slots.forEachIndexed { i, s ->
            val base = dex.move(s.id)
            val name = when (gimmickArmed) {
                Gimmick.ZMOVE -> Gimmicks.zMoveFor(battle, active, base)?.name ?: base.name
                Gimmick.DYNAMAX -> Gimmicks.maxMoveFor(battle, active, base).name
                else -> base.name
            }
            r.text(name.uppercase().take(16), 18, 168 + i * 13, if (gimmickArmed == Gimmick.ZMOVE && Gimmicks.zMoveFor(battle, active, base) == null) Palette.SHADE else Palette.TEXT, small = name.length > 14)
            if (i == moveCursor) r.cursor(8, 168 + i * 13)
        }
        val s = slots.getOrNull(moveCursor) ?: return
        val m = dex.move(s.id)
        r.frame(160, 112, 96, 48)
        r.text("TYPE/", 168, 120)
        r.text(m.type.uppercase(), 176, 132, Palette.type(m.type).cpy().mul(0.7f, 0.7f, 0.7f, 1f))
        r.text("${s.pp}/${s.maxPp(dex)}", 200, 144)
        r.frame(160, 160, 96, 64)
        r.text(when (m.category) { "Physical" -> "PHYSICAL"; "Special" -> "SPECIAL"; else -> "STATUS" }, 168, 168, small = true)
        r.text(if (m.basePower > 0) "POW ${m.basePower}" else "POW ---", 168, 180, small = true)
        r.text(if (m.accuracy > 0) "ACC ${m.accuracy}" else "ACC ---", 168, 190, small = true)
        val g = availableGimmicks()
        if (g.isNotEmpty()) {
            val label = gimmickArmed?.let { "◆" + gimmickLabel(it) } ?: "SEL:" + gimmickLabel(g.first())
            r.text(label, 166, 206, if (gimmickArmed != null) Palette.RED else Palette.BLUE, small = true)
        }
    }

    private fun gimmickLabel(g: Gimmick) = when (g) {
        Gimmick.MEGA -> "MEGA EVOLVE"; Gimmick.ZMOVE -> "Z-MOVE"; Gimmick.DYNAMAX -> "DYNAMAX"; Gimmick.TERA -> "TERASTAL"; Gimmick.ULTRA_BURST -> "ULTRA BURST"
    }
}
