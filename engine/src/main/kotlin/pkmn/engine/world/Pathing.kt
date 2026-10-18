package pkmn.engine.world

/** Grid path search shared by scripted walks (`walkto`) and trainers returning to their home tile. */
object Pathing {
    /**
     * Shortest 4-connected route from (fromX, fromY) to (toX, toY), as the list of steps to take; empty when already there,
     * null when unreachable. [passable] says whether a tile may be stepped on (the start tile is never tested). The search gives up
     * after [maxNodes] tiles so a large open map cannot stall a frame.
     */
    fun findPath(fromX: Int, fromY: Int, toX: Int, toY: Int, maxNodes: Int = 1500, passable: (Int, Int) -> Boolean): List<Facing>? {
        if (fromX == toX && fromY == toY) return emptyList()
        if (!passable(toX, toY)) return null
        val prev = HashMap<Long, Pair<Long, Facing>>()
        fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
        val start = key(fromX, fromY)
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(fromX, fromY))
        val seen = HashSet<Long>().apply { add(start) }
        while (queue.isNotEmpty() && seen.size <= maxNodes) {
            val (x, y) = queue.removeFirst()
            for (d in Facing.entries) {
                val nx = x + d.dx; val ny = y + d.dy
                val k = key(nx, ny)
                if (k in seen || !passable(nx, ny)) continue
                seen.add(k)
                prev[k] = key(x, y) to d
                if (nx == toX && ny == toY) {
                    val out = ArrayList<Facing>()
                    var cur = k
                    while (cur != start) { val (p, f) = prev.getValue(cur); out.add(f); cur = p }
                    return out.reversed()
                }
                queue.add(intArrayOf(nx, ny))
            }
        }
        return null
    }
}
