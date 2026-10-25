package pkmn.game.gfx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pkmn.engine.data.Dex

class SpriteFallbackTest {
    private val dex: Dex by lazy { Dex.loadDefault() }
    private fun key(m: Array<BooleanArray>) = m.joinToString("|") { r -> r.joinToString("") { if (it) "1" else "0" } }

    @Test
    fun everyArchetypeIsNonEmptyAndDistinct() {
        for (grid in listOf(16, 32)) {
            val masks = Archetype.entries.associateWith { Silhouettes.mask(it, grid) }
            masks.forEach { (a, m) -> assertTrue(m.sumOf { r -> r.count { it } } > grid, "$a empty at $grid") }
            assertEquals(Archetype.entries.size, masks.values.map(::key).toSet().size, "duplicate silhouettes at $grid")
        }
    }

    @Test
    fun hundredSpeciesSheetShowsAtLeastFiveSilhouettes() {
        val sheet = dex.species.values.filter { it.id == it.baseSpecies && it.num in 1..151 }.sortedBy { it.num }.take(100)
        assertEquals(100, sheet.size)
        val shapes = sheet.map { key(Silhouettes.mask(Silhouettes.classify(it), 32, it.heightm)) }.toSet()
        assertTrue(shapes.size >= 5, "only ${shapes.size} distinct silhouettes")
        assertTrue(sheet.map { Silhouettes.classify(it) }.toSet().size >= 5)
    }

    @Test
    fun classificationIsDeterministicAndSpeciesDataMissingFallsBackToBlob() {
        val sp = dex.species("pidgey")
        assertEquals(Silhouettes.classify(sp), Silhouettes.classify(sp))
        assertEquals(Archetype.BIRD, Silhouettes.classify(sp))
        assertEquals(Archetype.BLOB, Silhouettes.classify(null))
    }
}
