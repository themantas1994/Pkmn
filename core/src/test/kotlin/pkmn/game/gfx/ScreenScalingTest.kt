package pkmn.game.gfx

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenScalingTest {
    private fun s(m: ScalingMode, w: Int, h: Int) = ScreenScaling.scale(m, w, h, 256, 224)

    @Test fun integerKeepsWholeScale() {
        assertEquals(4f, s(ScalingMode.INTEGER, 1080, 1392)) // 1080x2400 portrait, 58% for the game
        assertEquals(1f, s(ScalingMode.INTEGER, 200, 100))
        assertEquals(3f, s(ScalingMode.INTEGER, 800, 720))
    }

    @Test fun sharpFillsWindowWithFractionalScale() {
        assertEquals(1080f / 256, s(ScalingMode.SHARP, 1080, 1392), 1e-4f) // 4.21875
        assertEquals(3f, s(ScalingMode.SHARP, 768, 700), 1e-4f)
        assertEquals(1f, s(ScalingMode.SHARP, 100, 100))
    }

    @Test fun autoResolvesPerPlatform() {
        assertEquals(ScalingMode.SHARP, ScalingMode.resolve("auto", true))
        assertEquals(ScalingMode.INTEGER, ScalingMode.resolve("auto", false))
        assertEquals(ScalingMode.INTEGER, ScalingMode.resolve("integer", true))
        assertEquals(ScalingMode.SHARP, ScalingMode.resolve("sharp", false))
        assertEquals(ScalingMode.INTEGER, ScalingMode.resolve("bogus", false))
    }
}
