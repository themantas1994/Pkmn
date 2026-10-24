package pkmn.game.gfx

import kotlin.math.PI
import kotlin.math.sin
import pkmn.engine.data.SpeciesData

/** Body plans used by the placeholder sprites; pure data so they can be tested without a GL context. */
enum class Archetype { BLOB, BIPED, QUADRUPED, BIRD, FISH, SERPENT, INSECT, HUMANOID, PLANT }

object Silhouettes {
    fun classify(sp: SpeciesData?): Archetype {
        if (sp == null) return Archetype.BLOB
        val eggs = sp.eggGroups
        val types = sp.types
        val slender = sp.heightm >= 1.5 && sp.weightkg / sp.heightm < 40
        return when {
            "Flying" in eggs || (types.firstOrNull() == "Flying") -> Archetype.BIRD
            "Bug" in eggs -> Archetype.INSECT
            "Dragon" in eggs && slender || slender && "Field" !in eggs && "Human-Like" !in eggs -> Archetype.SERPENT
            eggs.any { it.startsWith("Water") } -> Archetype.FISH
            "Grass" in eggs -> Archetype.PLANT
            "Human-Like" in eggs -> Archetype.HUMANOID
            "Amorphous" in eggs || "Mineral" in eggs || "Ditto" in eggs -> Archetype.BLOB
            "Field" in eggs || "Fairy" in eggs -> Archetype.QUADRUPED
            "Monster" in eggs || "Dragon" in eggs -> Archetype.BIPED
            else -> Archetype.BLOB
        }
    }

    /** Filled-pixel mask (mask[y][x]) of the archetype on a [grid]x[grid] canvas; larger species fill more of it. */
    fun mask(archetype: Archetype, grid: Int, heightm: Double = 1.0): Array<BooleanArray> {
        val m = Array(grid) { BooleanArray(grid) }
        val u = grid / 32.0
        val s = (0.78 + 0.22 * heightm.coerceIn(0.2, 3.0)).coerceIn(0.78, 1.0)
        val c = grid / 2.0
        fun ell(dx: Double, cy: Double, rx: Double, ry: Double) {
            val ccx = c + dx * u * s
            val y0 = grid - 32 * u * s
            val cyy = y0 + cy * u * s
            for (y in 0 until grid) for (x in 0 until grid) {
                val a = (x + 0.5 - ccx) / (rx * u * s); val b = (y + 0.5 - cyy) / (ry * u * s)
                if (a * a + b * b <= 1.0) m[y][x] = true
            }
        }
        fun both(dx: Double, cy: Double, rx: Double, ry: Double) { ell(dx, cy, rx, ry); if (dx != 0.0) ell(-dx, cy, rx, ry) }
        when (archetype) {
            Archetype.BLOB -> { ell(0.0, 19.0, 11.0, 9.0); ell(0.0, 13.0, 7.0, 5.0) }
            Archetype.BIPED -> { ell(0.0, 9.0, 5.0, 5.0); ell(0.0, 19.0, 7.0, 8.0); both(4.0, 28.0, 2.5, 3.0); both(9.0, 18.0, 2.0, 4.0); ell(0.0, 27.0, 3.0, 2.0) }
            Archetype.QUADRUPED -> { ell(0.0, 21.0, 11.0, 6.0); ell(0.0, 12.0, 5.0, 5.0); both(4.0, 5.0, 2.0, 3.5); both(7.0, 28.0, 2.0, 3.5) }
            Archetype.BIRD -> { ell(0.0, 8.0, 4.0, 4.0); ell(0.0, 11.0, 1.5, 1.0); ell(0.0, 19.0, 6.0, 8.0); both(10.0, 17.0, 3.0, 7.0); both(3.0, 29.0, 1.0, 2.5) }
            Archetype.FISH -> { ell(0.0, 16.0, 6.0, 11.0); both(8.0, 18.0, 3.0, 4.0); ell(0.0, 29.0, 5.0, 2.5); ell(0.0, 4.0, 2.0, 2.0) }
            Archetype.SERPENT -> {
                ell(0.0, 6.0, 4.0, 4.0)
                var y = 8.0
                while (y <= 29.0) { ell(sin((y - 8) / 21.0 * 2 * PI) * 7.0, y, 3.0, 2.2); y += 1.5 }
            }
            Archetype.INSECT -> { ell(0.0, 7.0, 3.0, 3.0); both(3.0, 2.0, 0.8, 2.0); ell(0.0, 14.0, 4.0, 4.0); ell(0.0, 23.0, 5.0, 6.0); both(9.0, 13.0, 4.0, 6.0); both(7.0, 27.0, 1.0, 4.0) }
            Archetype.HUMANOID -> { ell(0.0, 6.0, 4.0, 4.0); ell(0.0, 15.0, 5.0, 6.0); both(8.0, 16.0, 1.8, 6.0); both(3.0, 26.0, 2.0, 5.0) }
            Archetype.PLANT -> { ell(0.0, 24.0, 8.0, 6.0); ell(0.0, 17.0, 1.8, 6.0); both(7.0, 10.0, 5.0, 4.0); ell(0.0, 5.0, 3.0, 3.0) }
        }
        return m
    }
}
