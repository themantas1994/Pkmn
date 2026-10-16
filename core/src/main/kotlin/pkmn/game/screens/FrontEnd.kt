package pkmn.game.screens

import pkmn.engine.campaign.GameContext
import pkmn.engine.save.LoadResult
import pkmn.game.PkmnGame
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.overworld.OverworldScreen
import pkmn.game.ui.Menu
import pkmn.game.ui.TextBox
import pkmn.game.ui.say

class LoadingScreen(game: PkmnGame) : Screen(game) {
    private var t = 0f
    override fun update(dt: Float, input: InputState) {
        t += dt
        if (game.loadError != null) return
        if (game.dex != null && game.content != null && t > 0.2f) {
            game.attachDexToAssets()
            val quick = game.quickstart
            if (quick != null) {
                // Debug/automation: skip the intro with a fresh game.
                val state = GameContext.newGame(game.content!!, quick, "m", "KAI", 12345)
                game.resetTo(OverworldScreen(game, game.startSession(state)))
            } else replace(TitleScreen(game))
        }
    }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.BLACK)
        val err = game.loadError
        if (err != null) {
            r.text("Failed to load game data.", 16, 80, Palette.WHITE)
            r.text((err.message ?: err.toString()).take(30), 16, 96, Palette.WHITE)
            return
        }
        val dots = ".".repeat(((t * 3).toInt() % 4))
        r.text("Loading$dots", 96, 104, Palette.WHITE)
    }
}

class TitleScreen(game: PkmnGame) : Screen(game) {
    private var t = 0f
    private var menuOpen = false

    override fun onEnter() { game.audio.playMusic("title") }

    override fun update(dt: Float, input: InputState) {
        t += dt
        if (!menuOpen && (input.justPressed(Button.A) || input.justPressed(Button.START))) openMenu()
    }

    private fun openMenu() {
        menuOpen = true
        val hasSave = game.saves.exists()
        val items = buildList { if (hasSave) add("CONTINUE"); add("NEW GAME"); add("OPTIONS") }
        push(Menu(game, items, 8, 120, 112, onCancel = { menuOpen = false }) { i ->
            when (items[i]) {
                "CONTINUE" -> continueGame()
                "NEW GAME" -> if (hasSave) {
                    push(TextBox(game, "There is already a saved adventure. Starting a new game will overwrite it when you save.") {
                        push(Menu(game, listOf("START NEW", "CANCEL"), 8, 120, 112, onCancel = { menuOpen = false }) { k ->
                            if (k == 0) game.resetTo(IntroScreen(game)) else menuOpen = false
                        })
                    })
                } else game.resetTo(IntroScreen(game))
                "OPTIONS" -> push(OptionsScreen(game) { menuOpen = false })
            }
        })
    }

    private fun continueGame() {
        when (val res = game.saves.load()) {
            is LoadResult.Ok -> {
                val ctx = game.startSession(res.state)
                val warn = res.warnings + listOfNotNull(if (res.fromBackup) "The latest save was damaged, so the backup save was loaded." else null)
                val start = { game.resetTo(OverworldScreen(game, ctx)) }
                if (warn.isNotEmpty()) game.say(*warn.toTypedArray()) { start() } else start()
            }
            is LoadResult.Failed -> game.say("The save file could not be loaded.\n${res.reason}",
                if (res.preservedFiles.isNotEmpty()) "The damaged files were kept as ${res.preservedFiles.joinToString()} so nothing is lost." else "No file was deleted.") { menuOpen = false }
            LoadResult.NoSave -> { menuOpen = false }
        }
    }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WHITE)
        val logo = r.assets.ui("title_logo")
        if (logo != null) r.draw(logo, (Renderer.W - logo.regionWidth) / 2, 24)
        else {
            r.text("POKéMON", 100, 32, Palette.RED)
            r.text("CRYSTALLINE", 84, 48, Palette.BLUE)
        }
        // Blinking crystal sparkle.
        if ((t * 2).toInt() % 2 == 0) r.text("★", 200, 30, Palette.EXP_BLUE)
        if (!menuOpen && (t * 2).toInt() % 2 == 0) r.text("PRESS START", 84, 150)
        r.text("Fan game. Pokémon (c) Nintendo, Creatures,", 4, 196, Palette.GREY, small = true)
        r.text("GAME FREAK. Not affiliated or endorsed.", 4, 206, Palette.GREY, small = true)
    }
}

/** Professor introduction, player gender and names. */
class IntroScreen(game: PkmnGame) : Screen(game) {
    private var started = false
    private var gender = "m"
    private var name = "ARI"
    private var rival = "KAI"
    private var stage = 0

    override fun onEnter() { game.audio.playMusic("intro_professor") }

    override fun update(dt: Float, input: InputState) {
        if (started) return
        started = true
        game.say(
            "Hello there! Welcome to the world of POKéMON!",
            "My name is LINDEN. People call me the POKéMON PROF.",
            "This world is inhabited by creatures called POKéMON. In the TESSERA region, people and POKéMON live side by side.",
            "Now, tell me a little about yourself.",
        ) { askGender() }
    }

    private fun askGender() {
        stage = 1
        game.push(TextBox(game, "Are you a boy or a girl?") {
            game.push(Menu(game, listOf("BOY", "GIRL"), 160, 96, 72) { i ->
                gender = if (i == 0) "m" else "f"
                name = if (gender == "m") "ARI" else "REN"
                game.push(NameInputScreen(game, "YOUR NAME?", name) { n ->
                    name = n
                    stage = 2
                    game.say("This is my grandson... er, my neighbour's kid. They've been your rival since you were babies.", "...Erm, what was their name again?") {
                        game.push(NameInputScreen(game, "RIVAL'S NAME?", rival) { rn ->
                            rival = rn
                            stage = 3
                            game.say("$name! Your very own POKéMON legend is about to unfold!", "A world of dreams and adventures with POKéMON awaits! Let's go!") { begin() }
                        })
                    }
                })
            })
        })
    }

    private fun begin() {
        val content = game.content!!
        val seed = System.nanoTime()
        val state = GameContext.newGame(content, name, gender, rival, seed)
        val ctx = game.startSession(state)
        game.resetTo(OverworldScreen(game, ctx))
    }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WHITE)
        val portrait = when (stage) {
            1 -> r.assets.trainerPortrait(if (gender == "m") "player_m" else "player_f")
            2 -> r.assets.trainerPortrait("rival")
            3 -> r.assets.trainerPortrait(if (gender == "m") "player_m" else "player_f")
            else -> r.assets.trainerPortrait("professor")
        }
        if (portrait != null) r.draw(portrait, (Renderer.W - 64) / 2, 48)
        else r.rect(96, 48, 64, 64, Palette.SHADE)
    }
}

/** On-screen keyboard for naming (works with touch D-pad and keyboard arrows). */
class NameInputScreen(game: PkmnGame, private val title: String, initial: String, private val maxLen: Int = 10, private val onDone: (String) -> Unit) : Screen(game) {
    private val rows = listOf("ABCDEFGHI", "JKLMNOPQR", "STUVWXYZ ", "abcdefghi", "jklmnopqr", "stuvwxyz.", "0123456789".take(9), "-'!?♂♀é,&")
    private var cx = 0
    private var cy = 0
    private var text = StringBuilder(initial)
    // The bottom row holds DEL and END.
    private val bottom get() = cy == rows.size

    override fun update(dt: Float, input: InputState) {
        if (input.repeat(Button.UP)) cy = (cy - 1 + rows.size + 1) % (rows.size + 1)
        if (input.repeat(Button.DOWN)) cy = (cy + 1) % (rows.size + 1)
        if (input.repeat(Button.LEFT)) cx = (cx - 1 + 9) % 9
        if (input.repeat(Button.RIGHT)) cx = (cx + 1) % 9
        if (input.justPressed(Button.START)) finish()
        if (input.justPressed(Button.B)) { if (text.isNotEmpty()) text.deleteCharAt(text.length - 1) }
        if (input.justPressed(Button.A)) {
            if (bottom) { if (cx < 4) { if (text.isNotEmpty()) text.deleteCharAt(text.length - 1) } else finish() }
            else if (text.length < maxLen) {
                val ch = rows[cy][cx]
                if (!(ch == ' ' && text.isEmpty())) text.append(ch)
                game.audio.playSfx("menu_move")
            }
        }
    }

    private fun finish() {
        val t = text.toString().trim()
        if (t.isEmpty()) { game.audio.playSfx("error"); return }
        pop(); onDone(t)
    }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.text(title, 16, 10)
        r.frame(8, 24, Renderer.W - 16, 24)
        r.text(text.toString() + if ((game.timeMs / 400) % 2 == 0L && text.length < maxLen) "_" else "", 20, 32)
        r.frame(8, 52, Renderer.W - 16, 168)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> r.text(ch.toString(), 32 + x * 22, 64 + y * 16) } }
        r.text("DEL", 40, 64 + rows.size * 16)
        r.text("END", 150, 64 + rows.size * 16)
        if (bottom) r.cursor(if (cx < 4) 30 else 140, 64 + rows.size * 16) else r.cursor(22 + cx * 22, 64 + cy * 16)
    }
}

/** Options and accessibility settings. */
class OptionsScreen(game: PkmnGame, private val onClose: () -> Unit) : Screen(game) {
    private var cursor = 0
    private val o get() = game.options
    private val entries: List<Pair<String, () -> String>> = listOf(
        "TEXT SPEED" to { listOf("SLOW", "MID", "FAST", "INSTANT")[o.textSpeed] },
        "BATTLE ANIM" to { if (o.battleAnimations) "ON" else "OFF" },
        "BATTLE STYLE" to { o.battleStyle.uppercase() },
        "MUSIC" to { "${(o.musicVolume * 10).toInt()}" },
        "SOUND FX" to { "${(o.sfxVolume * 10).toInt()}" },
        "EXP. SHARE" to { if (o.expShare) "ON" else "OFF" },
        "DIFFICULTY" to { listOf("CASUAL", "NORMAL", "CHALLENGE")[o.difficulty] },
        "AUTO RUN" to { if (o.autoRun) "ON" else "OFF" },
        "CLOCK" to { if (o.realTimeClock) "REAL TIME" else "ALWAYS DAY" },
        "TOUCH PAD" to { if (o.showTouchControls) "ON" else "OFF" },
        "PAD SIZE" to { "${(o.controlScale * 100).toInt()}%" },
        "SWAP A/B" to { if (o.leftHanded) "ON" else "OFF" },
        "VIBRATION" to { if (o.haptics) "ON" else "OFF" },
        "REDUCE MOTION" to { if (o.reduceMotion) "ON" else "OFF" },
    )

    private fun change(i: Int, d: Int) {
        when (i) {
            0 -> o.textSpeed = (o.textSpeed + d + 4) % 4
            1 -> o.battleAnimations = !o.battleAnimations
            2 -> o.battleStyle = if (o.battleStyle == "shift") "set" else "shift"
            3 -> o.musicVolume = ((o.musicVolume * 10 + d).toInt().coerceIn(0, 10)) / 10f
            4 -> o.sfxVolume = ((o.sfxVolume * 10 + d).toInt().coerceIn(0, 10)) / 10f
            5 -> o.expShare = !o.expShare
            6 -> o.difficulty = (o.difficulty + d + 3) % 3
            7 -> o.autoRun = !o.autoRun
            8 -> o.realTimeClock = !o.realTimeClock
            9 -> o.showTouchControls = !o.showTouchControls
            10 -> o.controlScale = (o.controlScale + 0.1f * d).coerceIn(0.6f, 1.6f)
            11 -> o.leftHanded = !o.leftHanded
            12 -> o.haptics = !o.haptics
            13 -> o.reduceMotion = !o.reduceMotion
        }
        game.applyOptions()
    }

    override fun update(dt: Float, input: InputState) {
        if (input.repeat(Button.UP)) cursor = (cursor - 1 + entries.size) % entries.size
        if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % entries.size
        if (input.repeat(Button.LEFT)) change(cursor, -1)
        if (input.repeat(Button.RIGHT) || input.justPressed(Button.A)) change(cursor, 1)
        if (input.justPressed(Button.B) || input.justPressed(Button.START)) close()
    }

    private fun close() { game.saveOptions(); pop(); onClose() }
    override fun onBack(): Boolean { close(); return true }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.frame(0, 0, Renderer.W, Renderer.H)
        r.text("OPTIONS", 16, 10)
        entries.forEachIndexed { i, (label, value) ->
            val y = 26 + i * 13
            r.text(label, 20, y)
            r.text(value(), 150, y)
            if (i == cursor) r.cursor(10, y)
        }
    }
}
