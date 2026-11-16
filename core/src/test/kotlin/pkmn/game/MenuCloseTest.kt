package pkmn.game

import pkmn.game.gfx.Renderer
import pkmn.game.input.InputState
import pkmn.game.screens.Screen
import java.io.File
import kotlin.test.*

/** C5-4: menu actions that start overworld effects must leave the overworld on top of the screen stack. */
class MenuCloseTest {
    private val platform = object : Platform {
        override val name = "test"
        override val isMobile = false
        override val saveDir: File = File(System.getProperty("java.io.tmpdir"))
        override val importedAssetsDir: File? = null
    }
    private val game = PkmnGame(platform)

    private inner class Probe : Screen(game) {
        var exits = 0
        override fun update(dt: Float, input: InputState) {}
        override fun draw(r: Renderer) {}
        override fun onExit() { exits++ }
    }

    @Test
    fun `closeAbove leaves the given screen on top and exits everything above it`() {
        val overworld = Probe(); val start = Probe(); val party = Probe()
        game.push(overworld); game.push(start); game.push(party); game.flushPending()
        game.closeAbove(overworld); game.flushPending()
        assertSame(overworld, game.top())
        assertEquals(1, game.screens().size)
        assertEquals(listOf(0, 1, 1), listOf(overworld.exits, start.exits, party.exits))
    }

    @Test
    fun `a screen pushed after closeAbove in the same frame stays on top`() {
        val overworld = Probe(); val start = Probe(); val dialog = Probe()
        game.push(overworld); game.push(start); game.flushPending()
        game.closeAbove(overworld); game.push(dialog); game.flushPending()
        assertSame(dialog, game.top())
        assertEquals(listOf(overworld, dialog), game.screens())
    }

    @Test
    fun `closeAbove on a screen that is not on the stack changes nothing`() {
        val a = Probe(); val stray = Probe()
        game.push(a); game.flushPending()
        game.closeAbove(stray); game.flushPending()
        assertEquals(listOf<Screen>(a), game.screens())
    }
}
