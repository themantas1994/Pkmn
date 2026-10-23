package pkmn.game.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Left-handed mode (A/B swap) edge detection and logical inputs, without any GL context. */
class InputStateTest {
    private fun touchWithPointerOnA(): TouchControls = TouchControls().apply {
        a.set(0f, 0f, 10f, 10f); b.set(20f, 0f, 10f, 10f)
        setPointer(0, 5f, 5f)
    }

    @Test fun `swapped A touch is one fresh B press however long it is held`() {
        val s = InputState().apply { swapAB = true; touch = touchWithPointerOnA() }
        val fresh = (1..5).map { s.update(.016f); s.justPressed(Button.B) }
        assertEquals(listOf(true, false, false, false, false), fresh)
        assertFalse(s.pressed(Button.A))
        assertTrue(s.pressed(Button.B))
    }

    @Test fun `swapped hold time and repeat follow the logical button`() {
        val s = InputState().apply { swapAB = true; touch = touchWithPointerOnA() }
        repeat(40) { s.update(.016f) }
        assertEquals(0f, s.heldTime(Button.A))
        assertTrue(s.heldTime(Button.B) > .6f, "held ${s.heldTime(Button.B)}")
        assertFalse(s.justPressed(Button.B))
    }

    @Test fun `tap B stays a logical B when swapped`() {
        val s = InputState().apply { swapAB = true }
        s.tap(Button.B); s.update(.016f)
        assertTrue(s.justPressed(Button.B))
        assertFalse(s.justPressed(Button.A))
    }

    @Test fun `injected A stays a logical A when swapped`() {
        val s = InputState().apply { swapAB = true }
        s.inject(Button.A, true); s.update(.016f); s.update(.016f)
        assertTrue(s.pressed(Button.A)); assertFalse(s.pressed(Button.B)); assertFalse(s.justPressed(Button.A))
    }

    @Test fun `unswapped touch A is plain A`() {
        val s = InputState().apply { touch = touchWithPointerOnA() }
        s.update(.016f); s.update(.016f)
        assertTrue(s.pressed(Button.A)); assertFalse(s.pressed(Button.B)); assertFalse(s.justPressed(Button.A))
    }

    @Test fun `overlay highlight follows the physical control, not a logical tap`() {
        val s = InputState().apply { swapAB = true }
        s.tap(Button.B); s.update(.016f)
        assertTrue(s.justPressed(Button.B))
        assertFalse(s.controlPressed(Button.A)); assertFalse(s.controlPressed(Button.B))
        val t = InputState().apply { swapAB = true; touch = touchWithPointerOnA() }
        t.update(.016f)
        assertTrue(t.controlPressed(Button.A)); assertFalse(t.controlPressed(Button.B))
    }
}
