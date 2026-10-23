package pkmn.game.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.math.Rectangle

enum class Button { UP, DOWN, LEFT, RIGHT, A, B, START, SELECT }

/**
 * Logical button state merged from keyboard, touch controls and scripted input (tests/screenshots).
 * Screens read [pressed], [justPressed] and [repeat] once per frame after [update].
 */
class InputState {
    private val down = BooleanArray(Button.entries.size)
    private val prev = BooleanArray(Button.entries.size)
    private val held = FloatArray(Button.entries.size)
    private val injected = BooleanArray(Button.entries.size)
    private val pulse = BooleanArray(Button.entries.size)
    /** Keyboard/touch state of each physical control, before the left-handed swap (drives the touch overlay). */
    private val raw = BooleanArray(Button.entries.size)
    private val repeatFire = BooleanArray(Button.entries.size)
    var touch: TouchControls? = null
    /** Swaps A and B (left-handed option). */
    var swapAB = false

    fun update(dt: Float) {
        for (b in Button.entries) raw[b.ordinal] = keyboard(b) || (touch?.isDown(b) ?: false)
        for (b in Button.entries) {
            val i = b.ordinal
            prev[i] = down[i]
            down[i] = raw[physical(b).ordinal] || injected[i] || pulse[i]
            pulse[i] = false
            if (down[i]) held[i] += dt else held[i] = 0f
            repeatFire[i] = justPressed(b) || (down[i] && held[i] > 0.35f && ((held[i] - 0.35f) % 0.09f) < dt)
        }
    }

    /**
     * The physical key or touch control that drives logical [b]. Only these two sources are swapped (left-handed
     * option); [inject] and [tap] are logical, so BACK stays a B press. All stored state is logical and never swapped.
     */
    private fun physical(b: Button) = if (!swapAB) b else when (b) { Button.A -> Button.B; Button.B -> Button.A; else -> b }

    /** Whether the physical control drawn as [b] is down, for the touch overlay highlight. */
    fun controlPressed(b: Button) = raw[b.ordinal]

    // Keys.BACK is deliberately not polled for B: PkmnGame.handleBack() handles it once per press (Screen.onBack, falling
    // back to a single B tap), otherwise one press would pop a screen and also press B on the screen below.
    private fun keyboard(b: Button): Boolean {
        val i = Gdx.input ?: return false
        return when (b) {
            Button.UP -> i.isKeyPressed(Keys.UP) || i.isKeyPressed(Keys.W) || i.isKeyPressed(Keys.DPAD_UP)
            Button.DOWN -> i.isKeyPressed(Keys.DOWN) || i.isKeyPressed(Keys.S) || i.isKeyPressed(Keys.DPAD_DOWN)
            Button.LEFT -> i.isKeyPressed(Keys.LEFT) || i.isKeyPressed(Keys.A) || i.isKeyPressed(Keys.DPAD_LEFT)
            Button.RIGHT -> i.isKeyPressed(Keys.RIGHT) || i.isKeyPressed(Keys.D) || i.isKeyPressed(Keys.DPAD_RIGHT)
            Button.A -> i.isKeyPressed(Keys.Z) || i.isKeyPressed(Keys.J) || i.isKeyPressed(Keys.SPACE) || i.isKeyPressed(Keys.BUTTON_A) || i.isKeyPressed(Keys.DPAD_CENTER)
            Button.B -> i.isKeyPressed(Keys.X) || i.isKeyPressed(Keys.K) || i.isKeyPressed(Keys.BACKSPACE) || i.isKeyPressed(Keys.ESCAPE) || i.isKeyPressed(Keys.BUTTON_B)
            Button.START -> i.isKeyPressed(Keys.ENTER) || i.isKeyPressed(Keys.BUTTON_START)
            Button.SELECT -> i.isKeyPressed(Keys.SHIFT_RIGHT) || i.isKeyPressed(Keys.TAB) || i.isKeyPressed(Keys.BUTTON_SELECT)
        }
    }

    fun pressed(b: Button) = down[b.ordinal]
    fun justPressed(b: Button) = down[b.ordinal] && !prev[b.ordinal]
    /** True on press and then periodically while held (menu scrolling). */
    fun repeat(b: Button) = repeatFire[b.ordinal]
    fun heldTime(b: Button) = held[b.ordinal]

    /** For automation: hold or release a button. */
    fun inject(b: Button, isDown: Boolean) { injected[b.ordinal] = isDown }
    /** For automation: press for exactly one frame. */
    fun tap(b: Button) { pulse[b.ordinal] = true }

    fun direction(): Button? = listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT).firstOrNull { pressed(it) }
    fun clearAll() { injected.fill(false); pulse.fill(false) }
}

/**
 * On-screen D-pad and buttons for touch screens. Positions are recomputed by [layout] whenever the screen
 * size, orientation or control scale changes. Multi-touch: each pointer maps to at most one control.
 */
class TouchControls : InputAdapter() {
    val dpad = Rectangle(); val a = Rectangle(); val b = Rectangle(); val start = Rectangle(); val select = Rectangle()
    // Fixed slots instead of a map of boxed pairs: touch events and isDown() run every frame and must not allocate.
    private val ptrActive = BooleanArray(MAX_POINTERS)
    private val ptrX = FloatArray(MAX_POINTERS)
    private val ptrY = FloatArray(MAX_POINTERS)
    var enabled = true
    var hapticHook: (() -> Unit)? = null

    fun layout(screenW: Int, screenH: Int, game: Rectangle, scale: Float) {
        val unit = minOf(screenW, screenH) / 7f * scale
        val portrait = screenH > screenW
        if (portrait) {
            val cy = (game.y) / 2f // centre of the area below the game view (y-up)
            val ctrlY = maxOf(unit * 1.6f, cy)
            dpad.set(unit * 0.4f, ctrlY - unit * 1.25f, unit * 2.5f, unit * 2.5f)
            a.set(screenW - unit * 1.35f, ctrlY - unit * 0.1f, unit * 1.05f, unit * 1.05f)
            b.set(screenW - unit * 2.55f, ctrlY - unit * 0.85f, unit * 1.05f, unit * 1.05f)
            start.set(screenW / 2f + unit * 0.1f, unit * 0.25f, unit * 1.1f, unit * 0.45f)
            select.set(screenW / 2f - unit * 1.2f, unit * 0.25f, unit * 1.1f, unit * 0.45f)
        } else {
            dpad.set(unit * 0.3f, screenH / 2f - unit * 1.25f, unit * 2.5f, unit * 2.5f)
            a.set(screenW - unit * 1.35f, screenH / 2f, unit * 1.05f, unit * 1.05f)
            b.set(screenW - unit * 2.4f, screenH / 2f - unit * 0.9f, unit * 1.05f, unit * 1.05f)
            start.set(screenW - unit * 1.5f, unit * 0.3f, unit * 1.1f, unit * 0.45f)
            select.set(unit * 0.4f, unit * 0.3f, unit * 1.1f, unit * 0.45f)
        }
    }

    fun isDown(btn: Button): Boolean {
        if (!enabled) return false
        for (i in 0 until MAX_POINTERS) {
            if (!ptrActive[i]) continue
            val x = ptrX[i]; val y = ptrY[i]
            when (btn) {
                Button.A -> if (a.contains(x, y)) return true
                Button.B -> if (b.contains(x, y)) return true
                Button.START -> if (start.contains(x, y)) return true
                Button.SELECT -> if (select.contains(x, y)) return true
                else -> if (dpadDir(x, y) == btn) return true
            }
        }
        return false
    }

    private fun dpadDir(x: Float, y: Float): Button? {
        val grow = dpad.width * 0.25f
        if (x < dpad.x - grow || x > dpad.x + dpad.width + grow || y < dpad.y - grow || y > dpad.y + dpad.height + grow) return null
        val dx = x - (dpad.x + dpad.width / 2); val dy = y - (dpad.y + dpad.height / 2)
        if (dx * dx + dy * dy < (dpad.width * 0.12f).let { it * it }) return null
        return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) (if (dx > 0) Button.RIGHT else Button.LEFT) else (if (dy > 0) Button.UP else Button.DOWN)
    }

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val y = Gdx.graphics.height - screenY.toFloat()
        setPointer(pointer, screenX.toFloat(), y)
        if (enabled && (a.contains(screenX.toFloat(), y) || b.contains(screenX.toFloat(), y))) hapticHook?.invoke()
        return false
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        setPointer(pointer, screenX.toFloat(), Gdx.graphics.height - screenY.toFloat()); return false
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean { if (pointer in 0 until MAX_POINTERS) ptrActive[pointer] = false; return false }

    /** Records a pointer at y-up coordinates [x], [y] (touch events, tests). */
    fun setPointer(pointer: Int, x: Float, y: Float) {
        if (pointer !in 0 until MAX_POINTERS) return
        ptrActive[pointer] = true; ptrX[pointer] = x; ptrY[pointer] = y
    }

    /**
     * Forgets every pointer. Called when the app is paused: Android does not deliver the touch-up of a finger that was
     * down when the activity went to the background, which would leave a button held ("stuck") after resuming.
     */
    fun releaseAll() { ptrActive.fill(false) }

    /** Touch position mapped to game coordinates if it lies inside the game view (for tap-to-advance), else null. */
    fun anyPointer(): Boolean { for (a in ptrActive) if (a) return true; return false }

    private companion object { const val MAX_POINTERS = 10 }
}
