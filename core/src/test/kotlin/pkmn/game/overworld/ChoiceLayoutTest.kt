package pkmn.game.overworld

import pkmn.game.gfx.Renderer
import pkmn.game.ui.TextBox
import java.io.File
import kotlin.test.*

/** C5-2 / C5-3: every script `choice` menu must fit the 256x224 screen, above the prompt box, with its labels readable. */
class ChoiceLayoutTest {
    private val cellW = 8

    /** Every `choice "prompt" "a" "b" ...` line in world/scripts as (file, options). Tests run with the assets dir as cwd. */
    private fun allChoices(): List<Pair<String, List<String>>> {
        val dir = File(System.getProperty("pkmn.assets"), "scripts")
        val out = ArrayList<Pair<String, List<String>>>()
        for (f in dir.listFiles { x -> x.name.endsWith(".pks") }!!.sortedBy { it.name }) {
            f.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (!t.startsWith("choice ")) return@forEachIndexed
                val quoted = Regex("\"([^\"]*)\"").findAll(t).map { it.groupValues[1] }.toList()
                out.add("${f.name}:${i + 1}" to quoted.drop(1))   // first string is the prompt
            }
        }
        return out
    }

    @Test
    fun `there are choices to check`() = assertTrue(allChoices().size >= 20)

    @Test
    fun `every script choice frame fits the screen above the prompt box`() {
        for ((where, options) in allChoices()) {
            val longest = options.maxOf { it.length * cellW }
            val l = ChoiceLayout.place(options.size, longest)
            val frameH = l.visible * 16 + 16
            assertTrue(l.x >= 0 && l.x + l.width <= Renderer.W, "$where: x ${l.x}..${l.x + l.width} leaves the ${Renderer.W}px screen")
            assertTrue(l.y >= 0 && l.y + frameH <= TextBox.BOX_Y, "$where: y ${l.y}..${l.y + frameH} overlaps the prompt box at ${TextBox.BOX_Y}")
            assertTrue(l.width >= longest + 28, "$where: width ${l.width} cuts the longest label")
            assertTrue(l.visible == minOf(options.size, 8), "$where: ${l.visible} visible rows of ${options.size}")
        }
    }

    @Test
    fun `the 18 type menu shows eight rows`() {
        val l = ChoiceLayout.place(18, 8 * cellW)
        assertEquals(8, l.visible)
    }
}
