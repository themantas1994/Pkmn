package pkmn.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutotileTest {
    private val families by lazy {
        // Gradle runs tests from the module directory.
        val f = listOf(File("world/autotiles.json"), File("../world/autotiles.json")).first { it.exists() }
        Autotile.load(f).associateBy { it.name }
    }

    @Test fun `47 distinct masks`() = assertEquals(47, Autotile.uniqueMasks().size)

    @Test fun `diagonal needs both orthogonals`() {
        val only = setOf(0 to -1, 1 to -1) // N and NE set, E not set: NE must not count
        val m = Autotile.mask({ x, y -> (x to y) in only }, 0, 0)
        assertEquals(Autotile.N, m)
    }

    @Test fun `water picks the legacy variants`() {
        val w = families.getValue("water")
        assertEquals("water", w.resolve(255))
        assertEquals("water_nw", w.resolve(Autotile.E or Autotile.S or Autotile.SE))
        assertEquals("water_n", w.resolve(Autotile.E or Autotile.S or Autotile.SE or Autotile.SW or Autotile.W))
        assertEquals("water_ine", w.resolve(255 and Autotile.NE.inv()))
    }

    @Test fun `every mask resolves to a declared tile`() {
        val sheet = File(listOf("assets", "../assets").first { File(it).exists() }, "gfx/tiles/world.json").readText()
        for (f in families.values) for (m in Autotile.uniqueMasks()) {
            val t = f.resolve(m)
            assertTrue(t in f.outputs(), "${f.name} mask $m -> $t")
            assertTrue("\"$t\"" in sheet, "${f.name}: tile $t missing from world.json")
        }
    }
}
