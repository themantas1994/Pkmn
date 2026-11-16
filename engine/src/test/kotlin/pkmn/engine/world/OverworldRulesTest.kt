package pkmn.engine.world

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import java.io.File
import kotlin.test.*

class OverworldRulesTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    /** 11x11 floor map with the given tile overrides. */
    private fun map(overrides: Map<Pair<Int, Int>, String> = emptyMap()): MapData {
        val palette = listOf("floor", "wall", "bush", "tall_grass", "water")
        val ground = MutableList(121) { 0 }
        for ((p, t) in overrides) ground[p.second * 11 + p.first] = palette.indexOf(t)
        return MapData("t", "t", 11, 11, tilePalette = palette, ground = ground)
    }

    @Test
    fun `a trainer on land does not walk onto the water in front of a surfing player`() {
        val m = map(mapOf((3 to 5) to "water", (4 to 5) to "water", (5 to 5) to "water"))
        // Trainer at (2,5) facing right, player surfing at (6,5): dist-1 = 3 steps, all onto water.
        assertEquals(0, OverworldRules(newCtx()).approachSteps(m, 2, 5, Facing.RIGHT, 3) { _, _ -> false })
    }

    @Test
    fun `the approach stops at the first water tile`() {
        val m = map(mapOf((4 to 5) to "water", (5 to 5) to "water"))
        assertEquals(1, OverworldRules(newCtx()).approachSteps(m, 2, 5, Facing.RIGHT, 3) { _, _ -> false })
    }

    @Test
    fun `on pure land the trainer walks the whole distance minus one`() {
        val m = map()
        assertEquals(3, OverworldRules(newCtx()).approachSteps(m, 2, 5, Facing.RIGHT, 3) { _, _ -> false })
        assertEquals(0, OverworldRules(newCtx()).approachSteps(m, 2, 5, Facing.RIGHT, 0) { _, _ -> false })
    }

    @Test
    fun `an occupied tile ends the approach`() {
        assertEquals(1, OverworldRules(newCtx()).approachSteps(map(), 2, 5, Facing.RIGHT, 3) { x, y -> x == 4 && y == 5 })
    }

    @Test
    fun `stepping into water without surfing is a plain bump, not a SURF offer`() {
        val m = map(mapOf((4 to 5) to "water"))
        val r = OverworldRules(newCtx()).step(m, 3, 5, Facing.RIGHT, surfing = false) { _, _ -> null }
        assertTrue(r is StepResult.Bump, "expected a bump, got $r")
    }

    @Test
    fun `stepping into water while surfing still walks`() {
        val m = map(mapOf((4 to 5) to "water"))
        val r = OverworldRules(newCtx()).step(m, 3, 5, Facing.RIGHT, surfing = true) { _, _ -> null }
        assertTrue(r is StepResult.Walk, "expected a walk, got $r")
    }
}
