package pkmn.engine.campaign

import pkmn.engine.TestSupport
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.save.GameState
import java.io.File
import kotlin.test.*

class ReleaseTest {
    private val assets = File(System.getProperty("pkmn.assets"))
    private val content by lazy { WorldContent(DirectoryContentSource(assets)) }
    private fun newCtx() = GameContext(TestSupport.dex, content, GameContext.newGame(content, "ARI", "f", "KAI", 99))

    @Test
    fun `releasing a boxed Pokemon returns its held item to the bag`() {
        val c = newCtx()
        val mon = TestSupport.mon("chikorita", 10, item = "oranberry")
        c.state.boxes[2][4] = mon
        val before = c.state.itemCount("oranberry")
        val r = c.releaseFromBox(2, 4)
        assertTrue(r.released)
        assertNull(c.state.boxes[2][4])
        assertEquals(before + 1, c.state.itemCount("oranberry"))
        assertTrue("returned to the bag" in r.message, r.message)
    }

    @Test
    fun `releasing a Pokemon without an item just releases it`() {
        val c = newCtx()
        c.state.boxes[0][0] = TestSupport.mon("chikorita", 10)
        val bag = c.state.bag.toMap()
        assertTrue(c.releaseFromBox(0, 0).released)
        assertNull(c.state.boxes[0][0])
        assertEquals(bag, c.state.bag.toMap())
    }

    @Test
    fun `a full bag stack refuses the release and keeps the Pokemon and its item`() {
        val c = newCtx()
        val mon = TestSupport.mon("chikorita", 10, item = "oranberry")
        c.state.boxes[0][0] = mon
        c.state.bag["oranberry"] = GameState.MAX_STACK
        val r = c.releaseFromBox(0, 0)
        assertFalse(r.released)
        assertSame(mon, c.state.boxes[0][0])
        assertEquals("oranberry", mon.heldItem)
        assertEquals(GameState.MAX_STACK, c.state.itemCount("oranberry"))
        assertTrue("bag" in r.message && "full" in r.message, r.message)
    }

    @Test
    fun `an empty slot releases nothing`() {
        assertFalse(newCtx().releaseFromBox(0, 0).released)
    }
}
