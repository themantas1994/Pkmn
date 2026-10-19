package pkmn.game.screens

import pkmn.game.PkmnGame
import pkmn.game.gfx.Renderer
import pkmn.game.input.InputState

/** A layer on the screen stack. Non-opaque screens draw over the screen beneath (dialogs, menus). */
abstract class Screen(val game: PkmnGame) {
    open val opaque: Boolean = true
    /** Called every frame only for the top screen. */
    abstract fun update(dt: Float, input: InputState)
    abstract fun draw(r: Renderer)
    /** True while this screen holds state that must not be saved (a battle in progress, an evolution being applied). */
    open val blocksAutosave: Boolean get() = false
    open fun onEnter() {}
    open fun onExit() {}
    /** Back/escape on Android while this screen is on top. Return true if handled. */
    open fun onBack(): Boolean = false

    fun push(s: Screen) = game.push(s)
    fun pop() = game.pop(this)
    fun replace(s: Screen) = game.replace(this, s)
}
