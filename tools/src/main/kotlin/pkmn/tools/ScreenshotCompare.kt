package pkmn.tools

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Visual regression check: compares freshly captured automation screenshots with committed baselines.
 * A screenshot fails when more than [maxDiffShare] of its pixels differ by more than [channelTolerance] in any channel,
 * or when its size changed. `--update` copies the new captures over the baselines.
 * Usage: tools screenshots <actualDir> <baselineDir> [--update]
 */
class ScreenshotCompare(private val actual: File, private val baseline: File, private val update: Boolean,
                        private val maxDiffShare: Double = 0.01, private val channelTolerance: Int = 24) {
    data class Result(val name: String, val status: String, val diffShare: Double)

    fun run(): Boolean {
        val shots = actual.listFiles { f -> f.extension == "png" }?.sortedBy { it.name }.orEmpty()
        if (shots.isEmpty()) { println("No screenshots in $actual"); return false }
        baseline.mkdirs()
        val results = shots.map { compare(it) }
        for (r in results) println("  %-32s %-9s %6.2f%% pixels differ".format(r.name, r.status, r.diffShare * 100))
        val missingNow = baseline.listFiles { f -> f.extension == "png" }.orEmpty().map { it.name }.filter { n -> shots.none { it.name == n } }
        missingNow.forEach { println("  %-32s %-9s".format(it, "NOT-CAPTURED")) }
        val failed = results.count { it.status == "CHANGED" || it.status == "SIZE" } + missingNow.size
        println("Screenshots: ${results.size} compared, $failed failing" + if (update) " (baselines updated)" else "")
        return update || failed == 0
    }

    private fun compare(f: File): Result {
        val base = File(baseline, f.name)
        if (!base.exists()) {
            if (update) f.copyTo(base, overwrite = true)
            return Result(f.name, if (update) "NEW" else "NO-BASE", 0.0)
        }
        val a = ImageIO.read(f); val b = ImageIO.read(base)
        if (a.width != b.width || a.height != b.height) {
            if (update) f.copyTo(base, overwrite = true)
            return Result(f.name, "SIZE", 1.0)
        }
        var diff = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            if (p == q) continue
            val d = maxOf(abs((p shr 16 and 255) - (q shr 16 and 255)), abs((p shr 8 and 255) - (q shr 8 and 255)), abs((p and 255) - (q and 255)))
            if (d > channelTolerance) diff++
        }
        val share = diff.toDouble() / (a.width.toLong() * a.height)
        val changed = share > maxDiffShare
        if (changed && update) f.copyTo(base, overwrite = true)
        return Result(f.name, if (changed) "CHANGED" else "ok", share)
    }
}
