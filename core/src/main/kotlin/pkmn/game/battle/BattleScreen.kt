package pkmn.game.battle

import com.badlogic.gdx.graphics.Color
import pkmn.engine.battle.*
import pkmn.engine.campaign.GameContext
import pkmn.game.PkmnGame
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.screens.BagScreen
import pkmn.game.screens.MoveLearnFlow
import pkmn.game.screens.PartyScreen
import pkmn.game.screens.Screen

/**
 * Battle presentation. The engine resolves whole turns instantly; this screen replays the resulting
 * [BattleEvent]s one at a time with text, HP-bar animation and simple sprite effects.
 */
class BattleScreen(
    game: PkmnGame,
    private val ctx: GameContext,
    private val battle: Battle,
    private val trainerId: String?,
    private val musicOverride: String?,
    private val onEnd: (BattleOutcome) -> Unit,
) : Screen(game) {
    private val dex = ctx.dex
    private val ai = BattleAI()
    private val trainer = trainerId?.let { ctx.content.trainers[it] }

    /** What is currently displayed for each side (lags behind the engine state while events play). */
    private class SideView {
        var teamIndex = -1
        var species: String? = null
        var name = ""
        var level = 1
        var hp = 0f
        var targetHp = 0
        var maxHp = 1
        var status: String? = null
        var visible = false
        var slide = 0f
        var faint = 0f
        var flash = 0f
        var shake = 0f
        var dynamax = false
        var tera: String? = null
        var gender = ""
    }
    private val views = arrayOf(SideView(), SideView())
    private var cursor = 0
    private var eventIndex = 0
    private var text: String? = null
    private var textShown = 0f
    private var textHold = 0f
    private var waitTimer = 0f
    private var mode = Mode.INTRO
    private var moveCursor = 0
    private var gimmickArmed: Gimmick? = null
    private var pendingSE = 1.0
    private var expShown = 0f
    private var ballAnim: BattleEvent.CatchAttempt? = null
    private var ballTimer = 0f
    private var introTimer = 1.2f
    private var portraitSlide = 0f
    private var ended = false
    private var flashScreen = 0f
    private var effectColor: Color? = null
    private var effectTimer = 0f
    private var blockedOnPrompt = false

    private enum class Mode { INTRO, EVENTS, ACTION, MOVES, BUSY, END }

    override fun onEnter() {
        val music = musicOverride ?: trainer?.music ?: trainer?.let { ctx.content.trainerClasses[it.trainerClass]?.music } ?: if (trainerId != null) "battle_trainer" else "battle_wild"
        game.audio.playMusic(music, fadeMs = 0)
        battle.start()
        val p = battle.player.active
        if (p != null) expShown = expFraction(p)
    }

    // ================================================================= update

    override fun update(dt: Float, input: InputState) {
        for (v in views) {
            if (v.hp != v.targetHp.toFloat()) {
                val step = maxOf(v.maxHp * 0.9f, 20f) * dt
                v.hp = if (v.hp < v.targetHp) minOf(v.targetHp.toFloat(), v.hp + step) else maxOf(v.targetHp.toFloat(), v.hp - step)
            }
            if (v.slide < 1f) v.slide = minOf(1f, v.slide + dt * 3f)
            if (v.flash > 0) v.flash -= dt
            if (v.shake > 0) v.shake -= dt
            if (v.faint in 0.001f..0.999f) v.faint = minOf(1f, v.faint + dt * 3f)
        }
        if (flashScreen > 0) flashScreen -= dt
        if (effectTimer > 0) effectTimer -= dt
        if (portraitSlide < 1f) portraitSlide = minOf(1f, portraitSlide + dt * 2.5f)
        battle.player.active?.let { p -> if (views[0].teamIndex == battle.player.mons.indexOf(p)) expShown += (expFraction(p) - expShown).coerceIn(-dt, dt * 0.8f) }
        ballAnim?.let { ballTimer += dt }

        when (mode) {
            Mode.INTRO -> {
                if (text == null) {
                    val opener = if (trainer != null) "${trainerTitle()} would like to battle!" else null
                    if (opener != null) showText(opener) else { mode = Mode.EVENTS }
                } else if (updateText(dt, input)) { mode = Mode.EVENTS }
            }
            Mode.EVENTS -> playEvents(dt, input)
            Mode.ACTION -> updateActionMenu(input)
            Mode.MOVES -> updateMoveMenu(input)
            Mode.BUSY -> {}
            Mode.END -> if (text == null || updateText(dt, input)) finish()
        }
    }

    private fun trainerTitle(): String {
        val t = trainer ?: return ""
        val cls = ctx.content.trainerClasses[t.trainerClass]?.name ?: ""
        return ctx.format("$cls ${t.name}").trim()
    }

    private fun showText(s: String) { text = s; textShown = 0f; textHold = 0f }

    /** Advances the typewriter; returns true when the text has been read. */
    private fun updateText(dt: Float, input: InputState): Boolean {
        val t = text ?: return true
        val cps = when (game.options.textSpeed) { 0 -> 30f; 1 -> 60f; 2 -> 120f; else -> 10000f }
        if (textShown < t.length) {
            textShown += dt * cps * if (input.pressed(Button.A) || input.pressed(Button.B)) 4 else 1
            return false
        }
        textHold += dt
        if (input.justPressed(Button.A) || input.justPressed(Button.B) || textHold > 1.1f) { text = null; return true }
        return false
    }

    private fun playEvents(dt: Float, input: InputState) {
        if (blockedOnPrompt) return
        if (text != null) { if (!updateText(dt, input)) return }
        if (waitTimer > 0) { waitTimer -= dt; return }
        if (views.any { it.hp != it.targetHp.toFloat() }) return
        if (ballAnim != null) { if (ballTimer < 0.6f + 0.55f * ballAnim!!.shakes) return; ballAnim = null }
        if (eventIndex < battle.events.size) {
            handle(battle.events[eventIndex++])
            return
        }
        // All events shown.
        if (battle.outcome != BattleOutcome.ONGOING) { beginEnd(); return }
        when (val req = battle.request(battle.player)) {
            is Request.Move -> {
                if (req.lockedMove != null) { submit(Choice.UseMove(0)); return }
                mode = Mode.ACTION
            }
            is Request.SwitchIn -> { mode = Mode.BUSY; openParty(forced = true) }
            Request.Wait -> { battle.advance(ai) }
        }
    }

    private fun handle(e: BattleEvent) {
        val anims = game.options.battleAnimations && !game.options.reduceMotion
        when (e) {
            is BattleEvent.Message -> showText(e.text)
            is BattleEvent.TurnStart -> {}
            is BattleEvent.SwitchIn -> {
                val v = views[e.side]
                val bm = battle.sides[e.side].mons[e.teamIndex]
                v.teamIndex = e.teamIndex; v.species = e.speciesId; v.name = bm.name; v.level = bm.level
                v.maxHp = e.maxHp; v.targetHp = e.hp; v.hp = e.hp.toFloat(); v.status = bm.mon.status?.id
                v.visible = true; v.slide = if (anims) 0f else 1f; v.faint = 0f; v.dynamax = false; v.tera = null
                v.gender = when (bm.mon.gender) { pkmn.engine.mon.Gender.MALE -> "♂"; pkmn.engine.mon.Gender.FEMALE -> "♀"; else -> "" }
                if (e.side == 0) expShown = expFraction(bm)
                showText(e.text)
            }
            is BattleEvent.SwitchOut -> { views[e.side].visible = false; showText(e.text) }
            is BattleEvent.MoveUsed -> {
                showText(e.text)
                if (anims && e.category != "Status") { views[1 - e.side].flash = 0.45f; effectColor = Palette.type(e.moveType); effectTimer = 0.25f }
                else if (anims) { views[e.side].flash = 0.25f }
            }
            is BattleEvent.HpChange -> {
                val v = views[e.side]
                if (v.teamIndex == e.teamIndex || v.teamIndex < 0) {
                    v.targetHp = e.hp; v.maxHp = e.maxHp
                    if (e.delta < 0 && e.cause !in setOf("brn", "psn", "sandstorm", "hail")) {
                        game.audio.playSfx(when { pendingSE > 1 -> "hit_super_effective"; pendingSE < 1 -> "hit_not_very_effective"; else -> "hit_normal" })
                        if (anims) v.shake = 0.3f
                    }
                    if (e.cause == "levelup" || e.cause == "dynamax") v.hp = e.hp.toFloat()
                }
                pendingSE = 1.0
            }
            is BattleEvent.Effectiveness -> pendingSE = e.multiplier
            is BattleEvent.Faint -> {
                val v = views[e.side]
                v.faint = 0.01f
                game.audio.playSfx("faint")
                showText(e.text)
            }
            is BattleEvent.StatusChange -> {
                val v = views[e.side]
                if (v.teamIndex == e.teamIndex) v.status = e.status
                if (e.status != null) game.audio.playSfx("status_inflicted")
                if (e.text.isNotEmpty()) showText(e.text)
            }
            is BattleEvent.StatChange -> { game.audio.playSfx(if (e.amount > 0) "stat_up" else "stat_down"); showText(e.text) }
            is BattleEvent.FormeChange -> {
                val v = views[e.side]
                when {
                    e.kind == "dynamax" || e.kind == "gigantamax" -> { v.dynamax = true; if (e.kind == "gigantamax") v.species = e.speciesId }
                    e.kind == "undynamax" -> { v.dynamax = false; v.species = battle.sides[e.side].mons[e.teamIndex].species.id }
                    e.kind.startsWith("tera:") -> v.tera = e.kind.removePrefix("tera:")
                    else -> v.species = e.speciesId
                }
                flashScreen = if (anims) 0.5f else 0f
                game.audio.playSfx("evolution_finish")
                if (e.text.isNotEmpty()) showText(e.text)
            }
            is BattleEvent.FieldChange -> showText(e.text)
            is BattleEvent.Animation -> { if (anims) flashScreen = 0.35f }
            is BattleEvent.CatchAttempt -> {
                ballAnim = e; ballTimer = 0f
                game.audio.playSfx("ball_throw")
                if (e.caught) views[1].visible = false
            }
            is BattleEvent.ExpGain -> showText(e.text)
            is BattleEvent.LevelUp -> {
                game.audio.playSfx("level_up")
                if (views[0].teamIndex == e.teamIndex) views[0].level = e.level
                showText(e.text)
            }
            is BattleEvent.LearnMovePrompt -> {
                val mon = battle.player.mons[e.teamIndex].mon
                blockedOnPrompt = true
                MoveLearnFlow.offer(game, ctx, mon, e.moveId) { blockedOnPrompt = false }
            }
            is BattleEvent.End -> {}
        }
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
        if (input.justPressed(Button.UP) || input.justPressed(Button.DOWN)) { cursor = cursor xor 2; game.audio.playSfx("menu_move") }
        if (input.justPressed(Button.LEFT) || input.justPressed(Button.RIGHT)) { cursor = cursor xor 1; game.audio.playSfx("menu_move") }
        if (!input.justPressed(Button.A)) return
        game.audio.playSfx("menu_select")
        when (cursor) {
            0 -> { mode = Mode.MOVES; gimmickArmed = null }
            1 -> { mode = Mode.BUSY; openParty(forced = false) }
            2 -> { mode = Mode.BUSY; openBag() }
            3 -> {
                if (!battle.opponent.isWild) { mode = Mode.EVENTS; showText("No! There's no running from a trainer battle!") }
                else submit(Choice.Run)
            }
        }
    }

    private fun availableGimmicks(): List<Gimmick> {
        val req = battle.request(battle.player) as? Request.Move ?: return emptyList()
        return req.gimmicks.toList().sortedBy { it.ordinal }
    }

    private fun updateMoveMenu(input: InputState) {
        val active = battle.player.active ?: return
        val slots = active.moveSlots
        if (input.repeat(Button.UP)) { moveCursor = (moveCursor - 1 + slots.size) % slots.size; game.audio.playSfx("menu_move") }
        if (input.repeat(Button.DOWN)) { moveCursor = (moveCursor + 1) % slots.size; game.audio.playSfx("menu_move") }
        if (input.justPressed(Button.SELECT)) {
            val g = availableGimmicks()
            if (g.isNotEmpty()) {
                val i = g.indexOf(gimmickArmed)
                gimmickArmed = if (i + 1 < g.size) g[i + 1] else null
                game.audio.playSfx("menu_select")
            }
        }
        if (input.justPressed(Button.B)) { mode = Mode.ACTION; game.audio.playSfx("menu_cancel"); return }
        if (input.justPressed(Button.A)) {
            val req = battle.request(battle.player) as? Request.Move ?: return
            val anyUsable = req.usableMoves.any { it }
            if (anyUsable && !req.usableMoves.getOrElse(moveCursor) { false }) {
                game.audio.playSfx("error")
                mode = Mode.EVENTS
                val slot = slots[moveCursor]
                showText(if (slot.pp <= 0) "There's no PP left for this move!" else "${active.name} can't use ${dex.move(slot.id).name}!")
                return
            }
            if (gimmickArmed == Gimmick.ZMOVE && Gimmicks.zMoveFor(battle, active, dex.move(slots[moveCursor].id)) == null) { game.audio.playSfx("error"); return }
            submit(Choice.UseMove(moveCursor, gimmickArmed))
        }
    }

    private fun openParty(forced: Boolean) {
        push(PartyScreen(game, ctx, PartyScreen.Mode.BATTLE, battle, forced) { chosen ->
            if (chosen == null) { mode = Mode.ACTION; return@PartyScreen }
            if (forced) {
                val err = battle.choose(battle.player, Choice.Switch(chosen))
                if (err != null) { openParty(true); return@PartyScreen }
                battle.advance(ai)
                mode = Mode.EVENTS
            } else submit(Choice.Switch(chosen))
        })
    }

    private fun openBag() {
        push(BagScreen(game, ctx, BagScreen.Mode.BATTLE, battle) { item, target ->
            if (item == null) { mode = Mode.ACTION; return@BagScreen }
            if (dex.itemOrNull(item)?.isPokeball == true && !battle.opponent.isWild) {
                mode = Mode.EVENTS; showText("The Trainer blocked the BALL!\nDon't be a thief!"); return@BagScreen
            }
            ctx.state.removeItem(item)
            submit(Choice.UseItem(item, target ?: battle.player.mons.indexOf(battle.player.active)))
        })
    }

    private fun submit(c: Choice) {
        val err = battle.choose(battle.player, c)
        if (err != null) { mode = Mode.EVENTS; showText(err); return }
        battle.advance(ai)
        mode = Mode.EVENTS
        gimmickArmed = null
    }

    // ================================================================= ending

    private fun beginEnd() {
        if (mode == Mode.END) return
        mode = Mode.END
        val o = battle.outcome
        if (o == BattleOutcome.WIN && trainer != null) {
            portraitSlide = 0f
            game.audio.playMusic(if ("leader" in trainer.trainerClass || trainer.trainerClass == "leader") "victory_gym" else "victory_trainer", 0)
            showText(ctx.format(trainer.defeat.ifEmpty { "..." }))
        } else if (o == BattleOutcome.WIN) {
            game.audio.playMusic("victory_wild", 0)
            text = null
        } else if (o == BattleOutcome.CAUGHT) {
            game.audio.playSfx("catch_success")
            text = null
        } else text = null
    }

    private fun finish() {
        if (ended) return
        ended = true
        pop()
        onEnd(battle.outcome)
    }

    override fun onBack(): Boolean {
        if (mode == Mode.MOVES) mode = Mode.ACTION
        return true
    }

    // ================================================================= drawing

    override fun draw(r: Renderer) {
        val bg = when (battle.environment) {
            "cave" -> Color.valueOf("c8b8a0"); "water" -> Color.valueOf("b8d0f0"); "snow" -> Color.valueOf("e8f0f8")
            "sand" -> Color.valueOf("f0e0b0"); "indoor", "building" -> Color.valueOf("e0d8d0"); else -> Color.valueOf("e8f0d8")
        }
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.rect(0, 0, Renderer.W, 160, bg)
        val ground = bg.cpy().mul(0.85f, 0.85f, 0.82f, 1f)
        ellipse(r, 196, 86, 52, 9, ground)
        ellipse(r, 64, 152, 60, 9, ground)

        // Trainer portrait during intro / at the end.
        if (trainer != null && (mode == Mode.INTRO || (mode == Mode.END && battle.outcome == BattleOutcome.WIN))) {
            val p = r.assets.trainerPortrait(trainer.portrait ?: ctx.content.trainerClasses[trainer.trainerClass]?.portrait)
            if (p != null) r.draw(p, Renderer.W - (Renderer.W - 160) * portraitSlide, 16f)
        }
        drawMon(r, 1, 148f, 0f)
        drawMon(r, 0, 16f, 64f)
        if (views[1].visible) drawFoeHud(r)
        if (views[0].visible) drawPlayerHud(r)
        ballAnim?.let { drawBall(r, it) }
        effectColor?.let { if (effectTimer > 0) r.rect(0f, 0f, Renderer.W.toFloat(), 160f, it.cpy().also { c -> c.a = 0.25f }) }
        if (flashScreen > 0 && ((game.timeMs / 60) % 2 == 0L)) r.rect(0, 0, Renderer.W, 160, Color(1f, 1f, 1f, 0.7f))

        // Text box / menus.
        r.frame(0, 160, Renderer.W, 64, alt = true)
        when (mode) {
            Mode.ACTION -> drawActionMenu(r)
            Mode.MOVES -> drawMoveMenu(r)
            else -> text?.let { t ->
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

    private fun ellipse(r: Renderer, cx: Int, cy: Int, rx: Int, ry: Int, c: Color) {
        for (dy in -ry..ry) {
            val w = (rx * kotlin.math.sqrt(1.0 - (dy * dy).toDouble() / (ry * ry))).toInt()
            r.rect(cx - w, cy + dy, w * 2, 1, c)
        }
    }

    private fun drawMon(r: Renderer, side: Int, x: Float, y: Float) {
        val v = views[side]
        if (!v.visible || v.species == null) return
        if (v.faint >= 1f) return
        if (v.flash > 0 && ((game.timeMs / 70) % 2 == 0L)) return
        val tex = r.assets.pokemon(v.species!!, when { side == 0 && shinyOf(side) -> "back-shiny"; side == 0 -> "back"; shinyOf(side) -> "front-shiny"; else -> "front" })
        val slideOff = (1f - v.slide) * (if (side == 0) -120f else 120f)
        val shakeOff = if (v.shake > 0) ((game.timeMs / 40) % 2 * 4 - 2).toFloat() else 0f
        val scale = if (v.dynamax) 1.35f else 1f
        val w = tex.regionWidth * scale; val h = tex.regionHeight * scale
        val dropped = v.faint * h
        val baseY = y + 96 - h + dropped
        val tint = when {
            v.dynamax -> Color(1f, 0.55f, 0.6f, 1f)
            v.tera != null -> Palette.type(v.tera!!).cpy().lerp(Color.WHITE, 0.55f)
            else -> Color.WHITE
        }
        r.tint(tint) { r.batch.draw(tex, x + slideOff + shakeOff - (w - tex.regionWidth) / 2, Renderer.H - baseY - h + dropped, w, h - dropped) }
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
        r.rect(159f, 142f, 80 * expShown, 2f, Palette.EXP_BLUE)
        r.rect(248, 96, 1, 52, Palette.TEXT)
        r.rect(136, 148, 113, 1, Palette.TEXT)
    }

    private fun drawBall(r: Renderer, e: BattleEvent.CatchAttempt) {
        val t = ballTimer
        val x: Float; val y: Float
        if (t < 0.6f) { val k = t / 0.6f; x = 60 + (190 - 60) * k; y = 120 - 90 * kotlin.math.sin(k * Math.PI).toFloat() - 30 * k }
        else {
            val shakeIdx = ((t - 0.6f) / 0.55f).toInt()
            val wob = if (shakeIdx < e.shakes) kotlin.math.sin((t - 0.6f) * 20).toFloat() * 3 else 0f
            x = 190 + wob; y = 74f
        }
        val icon = r.assets.ui("pokeball_icon")
        if (icon != null) r.draw(icon, x, y) else {
            r.rect(x, y, 8f, 4f, Palette.RED); r.rect(x, y + 4, 8f, 4f, Palette.WHITE); r.outline(x.toInt(), y.toInt(), 8, 8, Palette.TEXT)
        }
    }

    private fun drawActionMenu(r: Renderer) {
        val p = battle.player.active
        r.text("What will", 12, 176)
        r.text("${p?.name ?: ""} do?", 12, 192)
        r.frame(112, 160, 144, 64)
        val labels = listOf("FIGHT", "PKMN", "PACK", "RUN")
        labels.forEachIndexed { i, s ->
            val x = 136 + (i % 2) * 64; val y = 178 + (i / 2) * 18
            r.text(s, x, y)
            if (i == cursor) r.cursor(x - 10, y)
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
