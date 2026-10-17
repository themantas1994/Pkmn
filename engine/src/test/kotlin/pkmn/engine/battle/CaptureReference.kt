package pkmn.engine.battle

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/**
 * Independent Scarlet/Violet capture reference used to check the engine. It is a line-by-line transcription of the
 * Cave of Dragonflies Gen IX calculator worker (https://www.dragonflycave.com/gen9capture-worker.js, v3, retrieved
 * 2026-10-17; analysis by Anubis), written without calling any engine code. Inputs are plain numbers.
 */
object CaptureReference {
    data class Input(
        val maxHp: Int,
        val curHp: Int,
        val catchRate: Int,
        val level: Int,
        val ball: String = "pokeball",
        val status: String = "none", // none, psn, par, brn, slp, frz
        val badges: Int = 8,
        val pokedex: Int = 0,
        val catchingCharm: Boolean = false,
        val lowLevelBonus: Boolean = true,
        val weightKg: Double = 50.0,
        val waterOrBug: Boolean = false,
        val turn: Int = 1,
        val dark: Boolean = false,
        val ultraBeast: Boolean = false,
    )

    private val statusMods = mapOf("none" to 1.0, "psn" to 1.5, "par" to 1.5, "brn" to 1.5, "slp" to 2.5, "frz" to 2.5)
    private val pvals = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5)
    private val badgeLevels = mapOf(0 to 25, 1 to 30, 2 to 35, 3 to 40, 4 to 45, 5 to 50, 6 to 55, 7 to 60, 8 to 100)

    private fun down(x: Double) = floor(x * 4096) / 4096
    // JavaScript Math.round: halves round up.
    private fun round(x: Double) = floor(x * 4096 + 0.5) / 4096
    private fun fround(x: Double) = x.toFloat().toDouble()

    private fun dexCat(n: Int): Int {
        var c = 0
        if (n > 30) c++; if (n > 150) c++; if (n > 300) c++; if (n > 450) c++; if (n > 600) c++
        return c
    }

    fun ballBonus(i: Input): Double {
        if (i.ultraBeast && i.ball != "beastball" && i.ball != "masterball") return 410.0 / 4096
        return when (i.ball) {
            "greatball" -> 1.5
            "ultraball" -> 2.0
            "masterball" -> -1.0
            "netball" -> if (i.waterOrBug) 3.5 else 1.0
            "nestball" -> if (i.level < 30) maxOf(1.0, (41 - i.level) / 10.0) else 1.0
            "timerball" -> min(4.0, 1 + (i.turn - 1) * 1229.0 / 4096)
            "quickball" -> if (i.turn == 1) 5.0 else 1.0
            "duskball" -> if (i.dark) 3.0 else 1.0
            "beastball" -> if (i.ultraBeast) 5.0 else 410.0 / 4096
            else -> 1.0
        }
    }

    fun x(i: Input): Double {
        var c = i.catchRate
        if (i.ball == "heavyball") {
            val w = i.weightKg * 10
            c += when { w >= 3000 -> 30; w >= 2000 -> 20; w < 1000 -> -20; else -> 0 }
            c = maxOf(c, 1)
        }
        val b = ballBonus(i)
        if (b == -1.0) return 256.0
        val s = statusMods.getValue(i.status)
        val m = i.maxHp.toDouble()
        val h = i.curHp.toDouble()
        val g = 1.0
        val l = if (i.lowLevelBonus && i.level < 13) (36 - 2 * i.level).toDouble() else 10.0
        var bp = 1.0
        if (i.badges < 8) {
            var k = 0
            while (badgeLevels.getValue(i.badges + k) < i.level) { bp = fround(bp * 0.8); k++ }
            bp = round(bp)
        }
        val d = 1.0
        return min(255.0, round(round(down(down(round(round(round((3 * m - 2 * h) * g) * c * b) * bp) / (3 * m)) * l / 10) * s) * d))
    }

    fun y(x: Double): Double = if (x == 0.0) 0.0 else floor(round(65536 / round(fround(round(255 / x).pow(3.0 / 16)))))

    /** Probability that one throw succeeds, critical captures included. */
    fun success(i: Input): Double {
        val x = x(i)
        if (x >= 255) return 1.0
        val yc = min(1.0, y(x) / 65536)
        val p = pvals[dexCat(i.pokedex)]
        val cc = floor(round(min(x, 255.0) * p * (if (i.catchingCharm) 2 else 1)) / 6)
        val crit = min(1.0, cc / 256)
        return crit * yc + (1 - crit) * yc.pow(4)
    }

}
