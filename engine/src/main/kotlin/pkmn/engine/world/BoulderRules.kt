package pkmn.engine.world

/**
 * Shared rules for Strength boulders (used by the overworld screen and the tools' puzzle analysis).
 *
 * A pushed boulder keeps its new position, so a careless push could wall the player into a pocket with no way out. A push is
 * therefore refused when, afterwards, the player's own region (starting on the tile the boulder just left) cannot reach a warp tile
 * or a map connection. Maps tagged `boulder_reset` additionally forget all boulder positions when entered from another map,
 * so leaving and returning always restores the puzzle.
 */
object BoulderRules {
    private fun key(x: Int, y: Int) = x * 4096 + y

    /** Tile the boulder may be pushed onto: in bounds, not solid or water, not a warp or script-trigger tile. NPC/item/obstacle occupancy is checked by the caller. */
    fun mayRestOn(map: MapData, x: Int, y: Int): Boolean {
        if (!map.inBounds(x, y)) return false
        val b = TileRules.behavior(map.tileAt(x, y))
        if (TileRules.isSolid(b) || b == TileBehavior.WATER || b == TileBehavior.WARP) return false
        if (map.warps.any { it.x == x && it.y == y }) return false
        return map.triggers.none { x in it.x until it.x + it.w && y in it.y until it.y + it.h }
    }

    /**
     * Whether a player standing on (sx, sy) can walk to a warp tile or a connected map edge when boulders occupy [boulders]
     * (encoded x*4096+y). Cut trees and rocks count as passable (they can be cleared) and NPCs are ignored, so this errs on allowing pushes.
     */
    fun reachesExit(map: MapData, boulders: Set<Int>, sx: Int, sy: Int): Boolean = ExitCheck(map).reaches(boulders, sx, sy)

    /** [reachesExit] with the map preprocessed once, for callers that test many boulder layouts (the tools' exhaustive search). */
    class ExitCheck(private val map: MapData) {
        private val w = map.width
        private val h = map.height
        private val passable = BooleanArray(w * h)
        private val exitAt = BooleanArray(w * h)
        private val stamp = IntArray(w * h)
        private var gen = 0
        private val stack = IntArray(w * h + 1)

        init {
            for (y in 0 until h) for (x in 0 until w) {
                val b = TileRules.behavior(map.tileAt(x, y))
                passable[y * w + x] = b != TileBehavior.WATER && b != TileBehavior.WARP && (!TileRules.isSolid(b) || b == TileBehavior.CUT_TREE || b == TileBehavior.ROCK_SMASH)
                exitAt[y * w + x] = b == TileBehavior.WARP
            }
            for (wp in map.warps) if (map.inBounds(wp.x, wp.y)) { exitAt[wp.y * w + wp.x] = true; passable[wp.y * w + wp.x] = false }
        }

        fun reaches(boulders: Set<Int>, sx: Int, sy: Int): Boolean {
            if (!map.inBounds(sx, sy) || key(sx, sy) in boulders) return false
            val edgeExit = map.connections.isNotEmpty()
            gen++
            var sp = 0
            stack[sp++] = sy * w + sx; stamp[sy * w + sx] = gen
            while (sp > 0) {
                val c = stack[--sp]; val x = c % w; val y = c / w
                for (d in 0 until 4) {
                    val nx = x + (if (d == 0) 1 else if (d == 1) -1 else 0); val ny = y + (if (d == 2) 1 else if (d == 3) -1 else 0)
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) { if (edgeExit) return true; continue }
                    val n = ny * w + nx
                    if (boulders.isNotEmpty() && key(nx, ny) in boulders) continue
                    if (exitAt[n]) return true
                    if (passable[n] && stamp[n] != gen) { stamp[n] = gen; stack[sp++] = n }
                }
            }
            return false
        }
    }
}
