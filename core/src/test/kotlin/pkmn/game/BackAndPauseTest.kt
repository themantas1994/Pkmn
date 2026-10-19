package pkmn.game

import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.screens.Screen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Android BACK dispatch (AN-1), pause cleanup (AN-6) and the pause-autosave decision (S-6), without any GL context. */
class BackAndPauseTest {
    private val platform = object : Platform {
        override val name = "test"
        override val isMobile = true
        override val saveDir: File = File(System.getProperty("java.io.tmpdir"))
        override val importedAssetsDir: File? = null
    }
    private val game = PkmnGame(platform)

    /** Records how often B was seen as a fresh press while it was the top screen. */
    private open inner class Probe(val handlesBack: Boolean, val closesOnBack: Boolean = false) : Screen(game) {
        var backCalls = 0
        var bPresses = 0
        override fun update(dt: Float, input: InputState) { if (input.justPressed(Button.B)) bPresses++ }
        override fun draw(r: Renderer) {}
        override fun onBack(): Boolean { backCalls++; if (closesOnBack) pop(); return handlesBack }
    }

    private fun frame() {
        game.flushPending()
        game.input.update(0.016f)
        game.top()?.update(0.016f, game.input)
    }

    @Test
    fun backClosingTheTopScreenDoesNotPressBOnTheScreenBelow() {
        val below = Probe(handlesBack = true); val top = Probe(handlesBack = true, closesOnBack = true)
        game.push(below); game.push(top); game.flushPending()
        game.handleBack()
        frame(); frame()
        assertEquals(1, top.backCalls)
        assertSame(below, game.top())
        assertEquals(0, below.bPresses, "one BACK press must not also be B on the screen below")
        assertEquals(0, below.backCalls)
    }

    @Test
    fun unhandledBackBecomesExactlyOneBPress() {
        val only = Probe(handlesBack = false)
        game.push(only); game.flushPending()
        game.handleBack()
        frame(); frame(); frame()
        assertEquals(1, only.backCalls)
        assertEquals(1, only.bPresses)
    }

    @Test
    fun threeNestedScreensEachConsumeOneBackPress() {
        val a = Probe(true); val b = Probe(true, closesOnBack = true); val c = Probe(true, closesOnBack = true)
        game.push(a); game.push(b); game.push(c); game.flushPending()
        game.handleBack(); frame()
        assertSame(b, game.top())
        game.handleBack(); frame()
        assertSame(a, game.top())
        assertEquals(listOf(0, 0, 0), listOf(a.bPresses, b.bPresses, c.bPresses))
        assertEquals(listOf(0, 1, 1), listOf(a.backCalls, b.backCalls, c.backCalls))
    }

    @Test
    fun backOnAnEmptyStackIsIgnored() {
        game.handleBack(); frame()
        assertNull(game.top())
    }

    @Test
    fun pauseDropsHeldTouchPointers() {
        game.touch.layout(700, 1400, com.badlogic.gdx.math.Rectangle(0f, 700f, 640f, 576f), 1f)
        game.touch.setPointer(0, game.touch.a.x + 2, game.touch.a.y + 2)
        assertTrue(game.touch.isDown(Button.A))
        game.touch.releaseAll()
        assertFalse(game.touch.isDown(Button.A)); assertFalse(game.touch.anyPointer())
    }

    // ---- S-6: which screen autosaves on pause

    private class SaveProbe(game: PkmnGame) : Screen(game), AutosaveAware {
        var saves = 0
        override fun autosave() { saves++ }
        override fun update(dt: Float, input: InputState) {}
        override fun draw(r: Renderer) {}
    }

    @Test
    fun pauseSavesFromTheOverworldBeneathMenusAndDialogs() {
        val ow = SaveProbe(game)
        assertSame(ow, autosaveTarget(listOf(ow)))
        assertSame(ow, autosaveTarget(listOf(ow, Probe(false), Probe(false)))) // start menu + bag on top
    }

    @Test
    fun pauseDoesNotSaveWhileABattleOrEvolutionIsOnTopOfTheOverworld() {
        val ow = SaveProbe(game)
        val battle = object : Screen(game) {
            override val blocksAutosave get() = true
            override fun update(dt: Float, input: InputState) {}
            override fun draw(r: Renderer) {}
        }
        assertNull(autosaveTarget(listOf(ow, battle)))
        assertNull(autosaveTarget(listOf(ow, battle, Probe(false)))) // a dialog over the battle
        assertSame(ow, autosaveTarget(listOf(battle, ow))) // a battle *below* the overworld is finished history
    }

    @Test
    fun pauseWithoutAnAutosaveScreenSavesNothing() {
        assertNull(autosaveTarget(emptyList()))
        assertNull(autosaveTarget(listOf(Probe(false), Probe(false))))
    }
}
