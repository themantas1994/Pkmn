package pkmn.game.overworld

/**
 * Back-to-front draw order for the overworld: NPCs sorted by their pixel Y (stable, so equal rows keep their
 * insertion order), then the player last so the player's sprite always overlaps any NPC it touches.
 */
fun drawOrder(npcs: List<Actor>, player: Actor): List<Actor> = npcs.filter { it !== player }.sortedBy { it.pixelY() } + player
