package pkmn.tools

import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.Dex
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.util.toId
import pkmn.engine.world.FieldMove
import pkmn.engine.world.Instr
import pkmn.engine.world.MapData
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import pkmn.engine.world.Warp
import java.io.File

/**
 * Static model of the compiled world used by the progression checker and the playtime estimator.
 *
 * Reachability is optimistic: script gates (step triggers that turn the player back, NPCs that block a path until a
 * flag is set) are treated as passable, flag-dependent tiles count as passable if any variant is, and ice is walked.
 * Field moves, ledges, water, warps, seamless connections and script warps are modelled exactly. So "unreachable"
 * findings are real; "reachable" means reachable once the story flags along the way are set.
 */
class WorldAnalysis(root: File) {
    val content = WorldContent(DirectoryContentSource(File(root, "assets")))
    val dex: Dex = Dex.load(DirectoryContentSource(File(root, "assets")))
    val maps: Map<String, MapData> = content.index.maps.associateWith { content.map(it) }

    data class Node(val map: String, val x: Int, val y: Int)

    /** Where a script is referenced from. [x]/[y] is the NPC/sign tile or the trigger's top-left (-1 for on-enter). */
    data class ScriptRef(val map: String, val script: String, val kind: String, val x: Int, val y: Int, val w: Int = 1, val h: Int = 1)

    val scriptRefs: List<ScriptRef> = maps.values.flatMap { m ->
        buildList {
            m.npcs.forEach { n -> n.script?.let { add(ScriptRef(m.id, it, "npc", n.x, n.y)) } }
            m.signs.forEach { s -> s.script?.let { add(ScriptRef(m.id, it, "sign", s.x, s.y)) } }
            m.triggers.forEach { t -> add(ScriptRef(m.id, t.script, "trigger", t.x, t.y, t.w, t.h)) }
            m.onEnter?.let { add(ScriptRef(m.id, it, "enter", -1, -1)) }
        }
    }

    private val cmdCache = HashMap<String, List<Instr.Cmd>>()

    /** Every command a script can execute, following `call`s. */
    fun commands(scriptId: String): List<Instr.Cmd> = cmdCache.getOrPut(scriptId) {
        val out = ArrayList<Instr.Cmd>()
        val seen = HashSet<String>()
        fun walk(id: String) {
            if (!seen.add(id)) return
            val s = content.scripts[id] ?: return
            for (i in s.code) if (i is Instr.Cmd) {
                out.add(i)
                if (i.name == "call") walk(i.args[0])
            }
        }
        walk(scriptId)
        out
    }

    fun commandsOnMap(mapId: String, kinds: Set<String>? = null): List<Pair<ScriptRef, Instr.Cmd>> =
        scriptRefs.filter { it.map == mapId && (kinds == null || it.kind in kinds) }.flatMap { r -> commands(r.script).map { r to it } }

    /** Script reference that awards each badge number. */
    val badgeRefs: Map<Int, ScriptRef> = buildMap {
        for (r in scriptRefs) for (c in commands(r.script)) if (c.name == "badge") c.args[0].toIntOrNull()?.let { putIfAbsent(it, r) }
    }

    val hallOfFameRef: ScriptRef? = scriptRefs.firstOrNull { r -> commands(r.script).any { it.name == "halloffame" } }

    /**
     * Trainers fought by scripts on a map. Starter-dependent variants (several `battle` commands in one script sharing
     * an id prefix, e.g. rival_lab_chikorita / rival_lab_cyndaquil) count once.
     */
    fun scriptedTrainers(mapId: String): List<String> {
        val out = ArrayList<String>()
        for (r in scriptRefs.filter { it.map == mapId }) {
            val ids = commands(r.script).filter { it.name == "battle" }.map { it.args[0] }.distinct()
            ids.groupBy { id -> if (ids.count { o -> o.substringBeforeLast('_') == id.substringBeforeLast('_') } > 1) id.substringBeforeLast('_') else id }
                .values.forEach { out.add(it.first()) }
        }
        return out.distinct()
    }

    fun sightTrainers(mapId: String): List<String> = maps.getValue(mapId).npcs.mapNotNull { it.trainer }

    /** Species obtainable on a map: wild encounters (any method) and script gifts/trades. */
    fun speciesOn(mapId: String): Set<String> {
        val m = maps.getValue(mapId)
        val out = HashSet<String>()
        m.encounters?.let { content.encounters[it] }?.methods?.values?.forEach { byTime -> byTime.values.forEach { slots -> slots.forEach { out.add(it.species) } } }
        for ((_, c) in commandsOnMap(mapId)) when (c.name) {
            "givemon", "giveegg" -> out.add(toId(c.args[0]))
            "trade" -> c.args.getOrNull(1)?.let { out.add(toId(it)) }
        }
        return out
    }

    /** Adds every species reachable by evolution. */
    fun withEvolutions(species: Set<String>): Set<String> {
        val out = HashSet(species)
        val queue = ArrayDeque(species)
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            dex.speciesOrNull(s)?.evolutions?.forEach { e -> if (out.add(e.target)) queue.add(e.target) }
        }
        return out
    }

    fun canLearn(speciesId: String, move: String): Boolean {
        var sp = dex.speciesOrNull(speciesId)
        while (sp != null) {
            val ls = dex.learnset(sp.id)
            if (ls != null && (move in ls.machine || ls.levelUp.any { it.move == move })) return true
            sp = sp.prevo?.let { dex.speciesOrNull(it) }
        }
        return false
    }

    // ------------------------------------------------------------------ reachability

    class Reach(val nodes: Set<Node>, val edges: Map<Node, List<Node>>, val mapEdges: Map<String, Set<String>>) {
        val maps: Set<String> by lazy { nodes.mapTo(HashSet()) { it.map } }
        fun tilesOn(map: String) = nodes.count { it.map == map }
    }

    private fun behaviors(m: MapData, x: Int, y: Int): Set<TileBehavior> {
        val base = TileRules.behavior(m.tileAt(x, y))
        val alt = m.flagTiles.filter { it.x == x && it.y == y }.map { TileRules.behavior(it.tile) }
        return (alt + base).toSet()
    }

    private fun warpAt(m: MapData, x: Int, y: Int): Warp? = m.warps.firstOrNull { it.x == x && it.y == y }

    /** Targets of a warp; `@back` returns to any door that leads into this map. */
    private fun warpTargets(from: MapData, w: Warp): List<Node> =
        if (w.toMap == Warp.BACK) maps.values.flatMap { q -> q.warps.filter { it.toMap == from.id }.map { Node(q.id, it.x, it.y) } }
        else if (w.toMap in maps) listOf(Node(w.toMap, w.toX, w.toY)) else emptyList()

    private fun connectionTarget(m: MapData, nx: Int, ny: Int, dx: Int, dy: Int): Node? {
        val dir = when { dy < 0 -> "north"; dy > 0 -> "south"; dx < 0 -> "west"; else -> "east" }
        val c = m.connections.firstOrNull { it.direction == dir } ?: return null
        val t = maps[c.map] ?: return null
        val (tx, ty) = when (dir) {
            "north" -> (nx - c.offset) to (t.height - 1)
            "south" -> (nx - c.offset) to 0
            "west" -> (t.width - 1) to (ny - c.offset)
            else -> 0 to (ny - c.offset)
        }
        return if (t.inBounds(tx, ty)) Node(t.id, tx, ty) else null
    }

    private val obstacleMove = mapOf("cut_tree" to FieldMove.CUT, "rock_smash" to FieldMove.ROCK_SMASH, "boulder" to FieldMove.STRENGTH)

    private fun neighbours(n: Node, moves: Set<FieldMove>): List<Node> {
        val m = maps.getValue(n.map)
        val out = ArrayList<Node>(4)
        val hereWarp = warpAt(m, n.x, n.y)
        val hereIsWarpTile = TileBehavior.WARP in behaviors(m, n.x, n.y)
        for ((dx, dy) in listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0)) {
            val nx = n.x + dx; val ny = n.y + dy
            if (!m.inBounds(nx, ny)) {
                if (hereWarp != null) out.addAll(warpTargets(m, hereWarp))
                else connectionTarget(m, nx, ny, dx, dy)?.let { out.add(it) }
                continue
            }
            val obstacle = m.obstacles.firstOrNull { it.x == nx && it.y == ny }
            if (obstacle != null && obstacleMove[obstacle.kind]?.let { it in moves } != true) continue
            val bs = behaviors(m, nx, ny)
            var moved = false
            for (b in bs) {
                when (b) {
                    TileBehavior.LEDGE_S, TileBehavior.LEDGE_E, TileBehavior.LEDGE_W -> {
                        val ok = (b == TileBehavior.LEDGE_S && dy == 1) || (b == TileBehavior.LEDGE_E && dx == 1) || (b == TileBehavior.LEDGE_W && dx == -1)
                        val lx = nx + dx; val ly = ny + dy
                        if (ok && m.inBounds(lx, ly) && !TileRules.isSolid(TileRules.behavior(m.tileAt(lx, ly)))) { out.add(Node(m.id, lx, ly)); moved = true }
                    }
                    TileBehavior.WATER -> if (FieldMove.SURF in moves) { out.add(Node(m.id, nx, ny)); moved = true }
                    TileBehavior.WATERFALL -> if (FieldMove.WATERFALL in moves && FieldMove.SURF in moves) { out.add(Node(m.id, nx, ny)); moved = true }
                    else -> if (!TileRules.isSolid(b)) {
                        val w = warpAt(m, nx, ny)
                        if (w != null && TileBehavior.WARP in bs) out.addAll(warpTargets(m, w)) else out.add(Node(m.id, nx, ny))
                        moved = true
                    }
                }
                if (moved) break
            }
            // Walking into a wall from a stairs/door tile triggers its warp.
            if (!moved && hereWarp != null && hereIsWarpTile && bs.all { TileRules.isSolid(it) }) out.addAll(warpTargets(m, hereWarp))
        }
        return out
    }

    /** Script warp edges: when a map is reachable, every `warp` its scripts perform is too. */
    private val scriptWarps: Map<String, List<Node>> = scriptRefs.groupBy { it.map }.mapValues { (_, refs) ->
        refs.flatMap { r -> commands(r.script).filter { it.name == "warp" && it.args.size >= 3 && it.args[0] in maps }
            .map { Node(it.args[0], it.args[1].toIntOrNull() ?: 0, it.args[2].toIntOrNull() ?: 0) } }
    }

    fun reach(moves: Set<FieldMove>): Reach {
        val start = Node(content.index.startMap, content.index.startX, content.index.startY)
        val seen = HashSet<Node>().apply { add(start) }
        val edges = HashMap<Node, List<Node>>()
        val mapEdges = HashMap<String, MutableSet<String>>()
        val queue = ArrayDeque<Node>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            // A script warp (ferry, cutscene) is treated as usable from anywhere on its map.
            val next = neighbours(n, moves) + scriptWarps[n.map].orEmpty()
            edges[n] = next
            for (t in next) {
                if (t.map != n.map) mapEdges.getOrPut(n.map) { HashSet() }.add(t.map)
                if (seen.add(t)) queue.add(t)
            }
        }
        return Reach(seen, edges, mapEdges)
    }

    /** Whether a script reference can be activated from reachable tiles (adjacent to an NPC/sign, across a counter, or inside a trigger). */
    fun activatable(r: ScriptRef, reach: Reach): Boolean = when (r.kind) {
        "enter" -> r.map in reach.maps
        "trigger" -> (r.x until r.x + r.w).any { x -> (r.y until r.y + r.h).any { y -> Node(r.map, x, y) in reach.nodes } }
        else -> listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0).any { (dx, dy) ->
            Node(r.map, r.x + dx, r.y + dy) in reach.nodes || Node(r.map, r.x + 2 * dx, r.y + 2 * dy) in reach.nodes
        }
    }

    data class Stage(val badges: Int, val moves: Set<FieldMove>, val reach: Reach, val species: Set<String>)

    /**
     * Reachability with [badges] badges: field moves allowed by badges, restricted to those some obtainable Pokémon can
     * learn (iterated to a fixpoint, since a field move can open the way to its own learners).
     */
    fun stage(badges: Int): Stage {
        var moves = FieldMove.entries.filter { it.badgeRequired <= badges }.toSet()
        while (true) {
            val reach = reach(moves)
            val species = withEvolutions(reach.maps.flatMap { speciesOn(it) }.toSet())
            val usable = moves.filter { fm -> species.any { canLearn(it, fm.moveId) } }.toSet()
            if (usable == moves) return Stage(badges, moves, reach, species)
            moves = usable
        }
    }

    /** Shortest map-to-map route within a reach. */
    fun mapPath(reach: Reach, from: String, to: String): List<String>? {
        if (from == to) return listOf(from)
        val prev = HashMap<String, String>()
        val queue = ArrayDeque<String>().apply { add(from) }
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val m = queue.removeFirst()
            for (t in reach.mapEdges[m].orEmpty()) if (seen.add(t)) {
                prev[t] = m
                if (t == to) return generateSequence(to) { prev[it] }.toList().reversed()
                queue.add(t)
            }
        }
        return null
    }
}
