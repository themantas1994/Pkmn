package pkmn.game.battle

import kotlin.test.*

class StatusFxTest {
    private val all = listOf("psn", "brn", "par", "slp", "frz")

    @Test
    fun `no status and unknown status have no effect`() {
        assertFalse(StatusFx.hasEffect(null))
        assertFalse(StatusFx.hasEffect("xyz"))
        assertEquals(1f, StatusFx.tint(null, 500).r)
        assertTrue(StatusFx.sparks(null, 500).isEmpty())
    }

    @Test
    fun `every status has a tint that differs from white and from the others`() {
        val tints = all.map { StatusFx.tint(it, 300) }
        tints.forEach { assertTrue(it.r < 1f || it.g < 1f || it.b < 1f, "tint must change the sprite") }
        for (i in all.indices) for (j in i + 1 until all.size) assertNotEquals(tints[i], tints[j], "${all[i]} vs ${all[j]}")
    }

    @Test
    fun `bad poison looks like poison`() {
        assertEquals(StatusFx.tint("psn", 700), StatusFx.tint("tox", 700))
    }

    @Test
    fun `particle sets are distinct per status over time and stay inside the sprite box`() {
        val sig = all.associateWith { s -> (0L..2000L step 50).map { StatusFx.sparks(s, it).map { p -> p.x to p.y } } }
        for (s in all) {
            assertTrue(sig.getValue(s).any { it.isNotEmpty() }, "$s emits particles")
            sig.getValue(s).flatten().forEach { (x, y) -> assertTrue(x in 0f..1f && y in 0f..1f, "$s particle at $x,$y") }
        }
        for (i in all.indices) for (j in i + 1 until all.size) assertNotEquals(sig[all[i]], sig[all[j]])
    }

    @Test
    fun `only sleep shows the Z glyph`() {
        assertEquals(listOf("slp"), all.filter { StatusFx.showsZ(it) })
    }
}
