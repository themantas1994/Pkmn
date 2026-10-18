package pkmn.game.overworld

import pkmn.engine.world.Facing
import kotlin.test.*

class DrawOrderTest {
    private fun actor(id: String, x: Int, y: Int) = Actor(id, "s", x, y, Facing.DOWN)

    @Test
    fun `player is drawn after every npc even when an npc is south of the player`() {
        val player = actor("player", 5, 5)
        val south = actor("south", 5, 6)
        val north = actor("north", 5, 4)
        val order = drawOrder(listOf(south, north), player)
        assertEquals(player, order.last())
        assertEquals(listOf("north", "south", "player"), order.map { it.id })
    }

    @Test
    fun `npcs keep their y order and the player stays on top while walking`() {
        val player = actor("player", 3, 3)
        val a = actor("a", 3, 2); val b = actor("b", 3, 4); val c = actor("c", 1, 4)
        player.startStep(Facing.DOWN, 3, 4, 3.75f)
        player.update(0.1f)
        val order = drawOrder(listOf(b, c, a), player)
        assertEquals(listOf("a", "b", "c", "player"), order.map { it.id })
    }
}
