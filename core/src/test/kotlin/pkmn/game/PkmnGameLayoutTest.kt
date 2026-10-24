package pkmn.game

import pkmn.game.gfx.Renderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkmnGameLayoutTest {
    private val portrait = listOf(
        540 to 1080, 720 to 1280, 720 to 1600, 1080 to 1920, 1080 to 2400, 1080 to 2340, 1440 to 3120, 1440 to 2560,
    )

    @Test fun fillUsesAtLeast95PercentOfWidthInPortrait() {
        for ((w, h) in portrait) {
            val l = DisplayLayout.compute(w, h, showTouch = true, mode = "FILL", mobile = true)
            assertTrue(l.w >= 0.95f * w, "${w}x$h fills only ${l.w / w}")
            assertTrue(l.x >= 0f && l.x + l.w <= w + 0.01f, "${w}x$h outside screen")
            assertTrue(l.y >= h * 0.39f - 1f && l.y + l.h <= h, "${w}x$h overlaps the controls area")
            assertEquals(Renderer.W.toFloat() / Renderer.H, l.w / l.h, 1e-3f)
        }
    }

    @Test fun crispIsTheIntegerLayout() {
        for ((w, h) in portrait) {
            val l = DisplayLayout.compute(w, h, showTouch = true, mode = "CRISP", mobile = true)
            val scale = maxOf(1, minOf(w / Renderer.W, (h * 0.58f).toInt() / Renderer.H))
            assertEquals(0, l.prescale)
            assertEquals((Renderer.W * scale).toFloat(), l.w)
            assertEquals((Renderer.H * scale).toFloat(), l.h)
            assertEquals((w - l.w) / 2f, l.x)
            assertEquals(h - l.h - h * 0.03f, l.y)
        }
    }

    @Test fun autoIsFillOnlyOnPhonesWithPoorIntegerCoverage() {
        assertTrue(DisplayLayout.compute(720, 1600, true, "AUTO", mobile = true).prescale > 0)
        assertEquals(0, DisplayLayout.compute(1080, 2400, true, "AUTO", mobile = true).prescale)
        assertEquals(0, DisplayLayout.compute(720, 1600, true, "AUTO", mobile = false).prescale)
    }

    @Test fun exactIntegerScaleNeedsNoPrescale() {
        assertEquals(0, DisplayLayout.compute(512, 1100, true, "FILL", mobile = true).prescale)
    }

    @Test fun landscapeWithoutControlsFitsHeight() {
        val l = DisplayLayout.compute(1280, 720, false, "FILL", mobile = false)
        assertEquals(720f, l.h, 0.01f)
        assertEquals((1280 - l.w) / 2f, l.x, 0.01f)
    }
}
