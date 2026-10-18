package pkmn.game.ui

import pkmn.game.PkmnGame
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.screens.Screen

/**
 * GSC-style dialogue box at the bottom of the screen: typewriter text, two lines per page,
 * A/B to advance, blinking arrow when more text follows. `\u000C` (from `\p` in scripts) forces a new box.
 */
class TextBox(game: PkmnGame, text: String, private val autoClose: Boolean = false,
              /** Optional extra drawing behind the box (e.g. the badge art in the badge award). */
              private val decor: ((Renderer) -> Unit)? = null,
              /** Must stay the last parameter: `TextBox(game, "...") { ... }` binds the trailing lambda to it. */
              val onDone: () -> Unit = {}) : Screen(game) {
    override val opaque = false
    private val pages: List<List<String>>
    private var page = 0
    private var shown = 0f
    private var finishedPage = false
    private var autoTimer = 0f

    init {
        val font = game.assets.font8
        val maxChars = (Renderer.W - 24) / font.cellW
        pages = text.split('\u000C').flatMap { box -> font.wrap(box, maxChars).chunked(2) }.ifEmpty { listOf(listOf("")) }
    }

    private val charsPerSecond: Float get() = when (game.options.textSpeed) { 0 -> 30f; 1 -> 60f; 2 -> 120f; else -> 10000f }

    override fun update(dt: Float, input: InputState) {
        val total = pages[page].sumOf { it.length }
        if (!finishedPage) {
            // A/B while typing reveals the whole page at once.
            shown = if (input.justPressed(Button.A) || input.justPressed(Button.B)) total.toFloat() else shown + dt * charsPerSecond
            if (shown >= total) { shown = total.toFloat(); finishedPage = true }
            return
        }
        if (autoClose) { autoTimer += dt; if (autoTimer > 0.9f) advance() ; return }
        if (input.justPressed(Button.A) || input.justPressed(Button.B)) advance()
    }

    private fun advance() {
        autoTimer = 0f
        if (page < pages.size - 1) { page++; shown = 0f; finishedPage = false; game.audio.playSfx("menu_move") }
        else { pop(); onDone() }
    }

    override fun draw(r: Renderer) {
        decor?.invoke(r)
        drawBox(r, pages[page], shown.toInt(), finishedPage && page < pages.size - 1 && (game.timeMs / 400) % 2 == 0L)
    }

    companion object {
        const val BOX_Y = Renderer.H - 56
        fun drawBox(r: Renderer, lines: List<String>, chars: Int, arrow: Boolean) {
            r.frame(0, BOX_Y, Renderer.W, 56)
            var left = chars
            lines.forEachIndexed { i, line ->
                val n = left.coerceIn(0, line.length)
                r.text(line.substring(0, n), 12, BOX_Y + 14 + i * 16)
                left -= line.length
            }
            if (arrow) {
                val a = r.assets.downArrow
                if (a != null) r.draw(a, Renderer.W - 18, BOX_Y + 42) else r.text("▼", Renderer.W - 18, BOX_Y + 40)
            }
        }
    }
}

/** A vertical menu in a frame. [onSelect] receives the index; B calls [onCancel] (if non-null). */
open class Menu(
    game: PkmnGame,
    private val items: List<String>,
    private val x: Int, private val y: Int,
    private val width: Int = items.maxOf { game.assets.font8.width(it) } + 28,
    private val visible: Int = items.size,
    private val onCancel: (() -> Unit)? = null,
    private val keepOpen: Boolean = false,
    var cursor: Int = 0,
    private val disabled: Set<Int> = emptySet(),
    private val onSelect: (Int) -> Unit,
) : Screen(game) {
    override val opaque = false
    private var scroll = 0
    /** Optional text drawn in the bottom dialogue box while this menu is open. */
    var prompt: String? = null

    override fun update(dt: Float, input: InputState) {
        if (input.repeat(Button.UP)) { cursor = (cursor - 1 + items.size) % items.size; game.audio.playSfx("menu_move") }
        if (input.repeat(Button.DOWN)) { cursor = (cursor + 1) % items.size; game.audio.playSfx("menu_move") }
        if (cursor < scroll) scroll = cursor
        if (cursor >= scroll + visible) scroll = cursor - visible + 1
        if (input.justPressed(Button.A)) {
            if (cursor in disabled) { game.audio.playSfx("error"); return }
            game.audio.playSfx("menu_select")
            if (!keepOpen) pop()
            onSelect(cursor)
        } else if (input.justPressed(Button.B) && onCancel != null) {
            game.audio.playSfx("menu_cancel")
            pop(); onCancel.invoke()
        }
    }

    override fun onBack(): Boolean { if (onCancel != null) { pop(); onCancel.invoke() }; return true }

    override fun draw(r: Renderer) {
        prompt?.let { TextBox.drawBox(r, r.assets.font8.wrap(it, 29).take(2), 999, false) }
        val h = visible * 16 + 16
        r.frame(x, y, width, h)
        for (i in 0 until minOf(visible, items.size)) {
            val idx = scroll + i
            if (idx >= items.size) break
            val c = if (idx in disabled) Palette.SHADE else Palette.TEXT
            r.text(items[idx], x + 18, y + 10 + i * 16, c)
            if (idx == cursor) r.cursor(x + 8, y + 10 + i * 16)
        }
        if (scroll > 0) r.text("▲", x + width - 14, y + 4)
        if (scroll + visible < items.size) r.text("▼", x + width - 14, y + h - 12)
    }
}

/** YES/NO box shown above the dialogue box. */
class YesNo(game: PkmnGame, question: String?, onAnswer: (Boolean) -> Unit) :
    Menu(game, listOf("YES", "NO"), Renderer.W - 64, Renderer.H - 56 - 48, 56, onCancel = { onAnswer(false) }, onSelect = { onAnswer(it == 0) }) {
    init { prompt = question }
}

/** Shows a message, then a YES/NO prompt. */
fun PkmnGame.ask(question: String, onAnswer: (Boolean) -> Unit) {
    push(TextBox(this, question) { push(YesNo(this, question) { onAnswer(it) }) })
}

/** Shows a message (or a sequence of messages) then runs [then]. */
fun PkmnGame.say(vararg texts: String, then: () -> Unit = {}) {
    if (texts.isEmpty()) { then(); return }
    push(TextBox(this, texts[0]) { say(*texts.drop(1).toTypedArray(), then = then) })
}

/** Quantity picker (shop/bag): UP/DOWN ±1, LEFT/RIGHT ±10. */
class NumberPicker(game: PkmnGame, private val max: Int, private val label: (Int) -> String, private val onDone: (Int?) -> Unit) : Screen(game) {
    override val opaque = false
    private var n = 1
    override fun update(dt: Float, input: InputState) {
        if (input.repeat(Button.UP)) n = if (n >= max) 1 else n + 1
        if (input.repeat(Button.DOWN)) n = if (n <= 1) max else n - 1
        if (input.repeat(Button.RIGHT)) n = minOf(max, n + 10)
        if (input.repeat(Button.LEFT)) n = maxOf(1, n - 10)
        if (input.justPressed(Button.A)) { pop(); onDone(n) }
        if (input.justPressed(Button.B)) { pop(); onDone(null) }
    }
    override fun draw(r: Renderer) {
        val s = label(n)
        val w = r.textWidth(s) + 24
        r.frame(Renderer.W - w - 4, Renderer.H - 56 - 32, w, 32)
        r.text(s, Renderer.W - w + 8, Renderer.H - 56 - 20)
    }
}
