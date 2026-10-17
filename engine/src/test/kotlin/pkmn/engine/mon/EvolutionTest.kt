package pkmn.engine.mon

import pkmn.engine.TestSupport
import pkmn.engine.TestSupport.dex
import kotlin.test.*

class EvolutionTest {
    private val prog = Progression(dex)

    @Test
    fun `sylveon wins over espeon when a fairy move is known`() {
        val eevee = TestSupport.mon("eevee", level = 20, moves = listOf("tackle", "babydolleyes")).also { it.friendship = 220 }
        assertEquals("sylveon", prog.evolutionTarget(eevee, Progression.EvolutionContext(isNight = false)))
        val plain = TestSupport.mon("eevee", level = 20, moves = listOf("tackle")).also { it.friendship = 220 }
        assertEquals("espeon", prog.evolutionTarget(plain, Progression.EvolutionContext(isNight = false)))
        assertEquals("umbreon", prog.evolutionTarget(plain, Progression.EvolutionContext(isNight = true)))
    }

    @Test
    fun `stones, levels, trades and everstone`() {
        val pika = TestSupport.mon("pikachu")
        assertEquals("raichu", prog.evolutionTarget(pika, Progression.EvolutionContext(usedItem = "thunderstone")))
        val charm = TestSupport.mon("charmander", level = 16)
        assertEquals("charmeleon", prog.evolutionTarget(charm, Progression.EvolutionContext()))
        charm.heldItem = "everstone"
        assertNull(prog.evolutionTarget(charm, Progression.EvolutionContext()))
        val kad = TestSupport.mon("kadabra", level = 20)
        assertNull(prog.evolutionTarget(kad, Progression.EvolutionContext()))
        assertEquals("alakazam", prog.evolutionTarget(kad, Progression.EvolutionContext(linkingCord = true)))
    }

    @Test
    fun `regional evolutions do not trigger outside their region`() {
        val cubone = TestSupport.mon("cubone", level = 30)
        assertEquals("marowak", prog.evolutionTarget(cubone, Progression.EvolutionContext(isNight = true)))
    }

    @Test
    fun `wurmple branches by individual`() {
        val targets = (0 until 10).map { prog.evolutionTarget(TestSupport.mon("wurmple", level = 10), Progression.EvolutionContext()) }.toSet()
        assertEquals(setOf("silcoon", "cascoon"), targets)
    }

    @Test
    fun `milcery evolves when spun holding a sweet`() {
        val prog = Progression(dex)
        val milcery = TestSupport.factory.create("milcery", 30, TestSupport.rng(), item = "strawberrysweet")
        assertNull(prog.evolutionTarget(milcery, Progression.EvolutionContext()))
        assertEquals("alcremie", prog.evolutionTarget(milcery, Progression.EvolutionContext(spun = true)))
        milcery.heldItem = null
        assertNull(prog.evolutionTarget(milcery, Progression.EvolutionContext(spun = true)))
    }
}
