package pkmn.tools

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CI-5: the visual regression guard must fail on a real change, a size change or a missing capture, and pass within its allowance. */
class ScreenshotCompareTest {
    private fun png(f: File, w: Int = 100, h: Int = 100, paint: (BufferedImage) -> Unit = {}) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, 0x808080)
        paint(img); ImageIO.write(img, "png", f)
    }

    private fun withDirs(body: (actual: File, base: File) -> Unit) {
        val d = Files.createTempDirectory("shots").toFile()
        try { body(File(d, "a").also { it.mkdirs() }, File(d, "b").also { it.mkdirs() }) } finally { d.deleteRecursively() }
    }

    private fun changePixels(n: Int, color: Int = 0xFFFFFF): (BufferedImage) -> Unit = { img -> for (i in 0 until n) img.setRGB(i % img.width, i / img.width, color) }

    @Test
    fun identicalAndSmallDriftPass() = withDirs { a, b ->
        png(File(b, "s.png")); png(File(a, "s.png"), paint = changePixels(25)) // within the ~30 px allowance
        assertTrue(ScreenshotCompare(a, b, update = false).run())
    }

    @Test
    fun changeBeyondAllowanceFails() = withDirs { a, b ->
        png(File(b, "s.png")); png(File(a, "s.png"), paint = changePixels(200))
        assertFalse(ScreenshotCompare(a, b, update = false).run())
        // Tiny per-channel noise below the tolerance (8) never counts.
        png(File(a, "s.png"), paint = changePixels(5000, 0x868686))
        assertTrue(ScreenshotCompare(a, b, update = false).run())
        png(File(a, "s.png"), paint = changePixels(5000, 0x8A8A8A)) // 10 per channel: counts, far over the allowance
        assertFalse(ScreenshotCompare(a, b, update = false).run())
    }

    /** C7-8: 40 changed pixels (a small sprite or tile glitch) must fail; the old 1 % allowance let ~570 of 57,344 through. */
    @Test
    fun aFortyPixelChangeFails() = withDirs { a, b ->
        png(File(b, "s.png")); png(File(a, "s.png"), paint = changePixels(40))
        assertFalse(ScreenshotCompare(a, b, update = false).run())
        png(File(b, "s.png"), 256, 224); png(File(a, "s.png"), 256, 224, paint = changePixels(60))
        assertFalse(ScreenshotCompare(a, b, update = false).run())
    }

    /** C7-8: a captured scene with no committed baseline fails, unless the run is `--update`, which adds it. */
    @Test
    fun sceneWithoutBaselineFailsUnlessUpdating() = withDirs { a, b ->
        png(File(a, "new.png"))
        assertFalse(ScreenshotCompare(a, b, update = false).run())
        assertFalse(File(b, "new.png").exists())
        assertTrue(ScreenshotCompare(a, b, update = true).run())
        assertTrue(File(b, "new.png").exists())
        assertTrue(ScreenshotCompare(a, b, update = false).run())
    }

    @Test
    fun sizeChangeMissingCaptureAndEmptyCaptureFail() = withDirs { a, b ->
        png(File(b, "s.png")); png(File(a, "s.png"), w = 120)
        assertFalse(ScreenshotCompare(a, b, update = false).run(), "size change")
        png(File(a, "s.png")); png(File(b, "gone.png"))
        assertFalse(ScreenshotCompare(a, b, update = false).run(), "baseline scene not captured")
        File(a, "s.png").delete()
        assertFalse(ScreenshotCompare(a, b, update = false).run(), "no screenshots at all")
        assertEquals(EXIT_FAILED, runTool(arrayOf("screenshots", a.path, b.path)))
    }
}
