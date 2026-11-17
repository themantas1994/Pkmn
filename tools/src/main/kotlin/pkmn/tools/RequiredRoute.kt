package pkmn.tools

/**
 * The map route a player must take between two maps: the shortest route, plus a detour to the map of every script that sets a
 * flag the goal depends on (a gate trigger's `unless` flag, a guard's `hideif` flag, a flag tile, a `flag` condition in a gate or
 * guard script). A flag is required when the goal becomes unreachable on the strict model ([StrictReach]) with that flag banned.
 * Flags in [route]'s `done` (needed by earlier legs) are already set and cost nothing.
 */
class RequiredRoute(private val w: ProgressWorld) {
    class Route(val maps: List<String>, val requiredFlags: List<String>)

    private val sr = StrictReach(w)

    fun route(badges: Int, from: String, to: String, done: Set<String> = emptySet()): Route? {
        val moves = WorldAnalysis.fieldMovesAt(badges)
        val full = sr.reach(moves, false, badges)
        if (to !in full.maps) return null
        val reach = full.fullReach()
        val required = full.flags.filter { it in sr.gatingFlags && it !in done }
            .filter { f -> to !in sr.reach(moves, false, badges, bannedFlags = setOf(f)).maps }
        val stops = required.mapNotNull { full.flagOrigin[it] }
        val path = ArrayList<String>().apply { add(from) }
        var cur = from
        for (stop in stops + to) {
            val seg = WorldAnalysis.shortestMapPath(reach, cur, stop) ?: continue
            path.addAll(seg.drop(1))
            cur = stop
        }
        return Route(path, required)
    }
}
