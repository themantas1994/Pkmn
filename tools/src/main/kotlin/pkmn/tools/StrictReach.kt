package pkmn.tools

import pkmn.engine.world.Cond
import pkmn.engine.world.FieldMove
import pkmn.engine.world.Instr
import pkmn.engine.world.MapData
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import pkmn.engine.world.Warp

/**
 * Strict, step-by-step model of where the player can stand, mirroring engine `OverworldRules.step` and the renderer's
 * `OverworldScreen.onPlayerArrive` / `interact` rules. It is stricter than [WorldAnalysis.reach] in these ways:
 *
 * * **Ice** slides: after arriving on an ice tile (by a step, ledge jump or landing) the player keeps moving in the same
 *   direction while the next step is a plain walk; the slide stops in front of anything else (also in front of a visible item
 *   ball, which is an occupant until picked up: both stopping and sliding on count).
 * * **NPCs** with `static`, `look` or `path` movement are solid. `wander` NPCs are not (they move). An NPC that a flag can hide
 *   (`hideif`) or a script on the same map hides or moves (`hide <npc>`, `move <npc> ...`) is "possibly gone": it stays solid
 *   until its `hideif` flag has been set by a reachable script or a reachable script hides/moves it (fixpoint). With
 *   `npcsGone = true` every possibly-gone NPC is absent from the start (optimistic upper bound).
 * * **Flag tiles** (`tileif`) only count once the flag can have been set by a script the player can reach (fixpoint); before
 *   that only the ground tile applies. Afterwards both variants are allowed (the player may act before or after).
 * * **Obstacles** need the field move with its badge (God Bidoof performs it; the stage's unlocked moves).
 * * **Boulders** can be pushed in a direction if the tile behind them is free floor. Approximation: the boulder is not moved,
 *   so a boulder pushed into a corridor never blocks later and repeated pushes along a line are each checked in isolation.
 * * **Warp tiles** are never stood on (arriving fires the warp) except as a spawn point; walking into a wall from a warp tile
 *   triggers it; `@back` returns to a door through which the player entered the map.
 * * **Surf**: land to water needs Surf; surfing onto land lands; waterfalls need Surf and Waterfall (climb up).
 *
 * Script `warp` commands (ferries, cutscenes) count only when the script is one the player can trigger from reachable tiles.
 *
 * With `badges` given (the progression checker), two more rules apply:
 *
 * * **Script conditions** are evaluated per stage ([ScriptPaths]): `badges`/`badge` exactly, `flag` against the flags set so far,
 *   everything else as "maybe". Only commands on paths that can run count (a `setflag` or `hide` behind `if badges >= 5` does
 *   nothing at 2 badges).
 * * **Gate triggers** (a trigger whose script can `move player`) keep their tiles closed until some path of the script ends
 *   without turning the player back, or until their `unless` flag is set (or their `if` flag is unset).
 *
 * Not modelled (kept optimistic, like [WorldAnalysis]): Fly, items blocking (a visible item can always be picked up from where
 * the player stands, which removes it). Without `badges`, conditions and turn-back triggers stay optimistic too (ItemAudit).
 */
class StrictReach(private val w: ReachWorld) {
    data class P(val map: String, val x: Int, val y: Int, val surf: Boolean)

    /** [edges] (step outcomes between standing tiles) and [scriptWarps] (source map to landing spot of a reachable `warp` script) are only filled by the final pass. */
    class Result(val stand: Set<P>, val flags: Set<String>, private val edges: Map<WorldAnalysis.Node, List<WorldAnalysis.Node>> = emptyMap(),
                 private val scriptWarps: Set<Pair<String, WorldAnalysis.Node>> = emptySet(), val closedGates: Set<String> = emptySet()) {
        private val tiles: Set<WorldAnalysis.Node> by lazy { stand.mapTo(HashSet()) { WorldAnalysis.Node(it.map, it.x, it.y) } }
        val nodes: Set<WorldAnalysis.Node> get() = tiles
        fun canStand(map: String, x: Int, y: Int) = WorldAnalysis.Node(map, x, y) in tiles
        val maps: Set<String> by lazy { tiles.mapTo(HashSet()) { it.map } }
        fun asReach() = WorldAnalysis.Reach(tiles, emptyMap(), emptyMap(), emptyMap())

        /** Reach with walking edges and script-warp edges (a script warp is usable from anywhere on its map, like [WorldAnalysis.reach]). */
        fun fullReach(): WorldAnalysis.Reach {
            val all = HashMap<WorldAnalysis.Node, List<WorldAnalysis.Node>>(edges)
            val mapEdges = HashMap<String, MutableSet<String>>()
            val walkEdges = HashMap<String, MutableSet<String>>()
            for ((n, tos) in edges) for (t in tos) if (t.map != n.map) { walkEdges.getOrPut(n.map) { HashSet() }.add(t.map); mapEdges.getOrPut(n.map) { HashSet() }.add(t.map) }
            for ((map, target) in scriptWarps) {
                for (n in tiles) if (n.map == map) all[n] = all[n].orEmpty() + target
                if (target.map != map) mapEdges.getOrPut(map) { HashSet() }.add(target.map)
            }
            return WorldAnalysis.Reach(tiles, all, mapEdges, walkEdges)
        }
    }

    /** A trigger whose script can turn the player back: its tiles stay closed until a path of the script lets the player through. */
    class Gate(val id: String, val map: String, val tiles: List<Pair<Int, Int>>, val script: String, val unlessFlag: String?, val ifFlag: String?)

    /** Every turn-back trigger in the world. */
    val gates: List<Gate> = w.maps.values.flatMap { m ->
        m.triggers.filter { t -> w.commands(t.script).any { it.name == "move" && it.args.firstOrNull() == "player" } }.map { t ->
            Gate("${m.id}:${t.x},${t.y}:${t.script}", m.id, (t.x until t.x + t.w).flatMap { x -> (t.y until t.y + t.h).map { y -> x to y } }, t.script, t.unlessFlag, t.ifFlag)
        }
    }

    private val paths = ScriptPaths(w)

    private val maps = w.maps
    private val dirs = listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0)

    /** NPC ids per map that a script on that map hides or moves. */
    private val scriptHidden: Map<String, Set<String>> = maps.keys.associateWith { id ->
        w.scriptRefs.filter { it.map == id }.flatMap { r -> w.commands(r.script) }.filter { c -> c.name == "hide" || c.name == "move" }.map { c -> c.args[0] }.toSet()
    }

    fun possiblyGone(m: MapData, npcId: String): Boolean {
        val n = m.npcs.first { it.id == npcId }
        return n.hideIf != null || npcId in scriptHidden[m.id].orEmpty()
    }

    private fun key(x: Int, y: Int) = x * 4096 + y

    private class Prepared(val m: MapData, val firmNpc: Set<Int>, val soft: List<SoftNpc>) {
        val obstacles = m.obstacles.associateBy { it.x * 4096 + it.y }
        val warps = m.warps.associateBy { it.x * 4096 + it.y }
        val flagTiles = m.flagTiles.groupBy { it.x * 4096 + it.y }
        /** Visible item balls are occupants: an ice slide stops in front of one until it is picked up. */
        val visibleItems = m.items.filter { !it.hidden }.map { it.x * 4096 + it.y }.toSet()
    }

    private class SoftNpc(val id: String, val key: Int, val hideIf: String?)

    private val prepared: Map<String, Prepared> = maps.mapValues { (_, m) ->
        val firm = HashSet<Int>(); val soft = ArrayList<SoftNpc>()
        for (n in m.npcs) {
            if (n.movement == "wander") continue
            if (possiblyGone(m, n.id)) soft.add(SoftNpc(n.id, n.x * 4096 + n.y, n.hideIf)) else firm.add(n.x * 4096 + n.y)
        }
        Prepared(m, firm, soft)
    }

    /**
     * Computes the standing positions reachable with [moves]; see the class comment for [npcsGone]. [badges] switches on script
     * conditions and turn-back triggers; [openGates] are gate ids treated as passable (the gate-seal check).
     */
    fun reach(moves: Set<FieldMove>, npcsGone: Boolean, badges: Int? = null, openGates: Set<String> = emptySet()): Result {
        val flags = HashSet<String>()
        val gone = HashSet<String>()   // "map:npc" removed by a reachable script
        val extra = HashSet<P>()       // landing spots of `warp` commands in scripts the player can trigger (ferries, cutscenes)
        val warpFrom = HashSet<Pair<String, WorldAnalysis.Node>>()
        while (true) {
            val closed = closedGates(badges, flags, openGates)
            val stand = bfs(moves, npcsGone, flags, gone, extra, closed, null)
            val reach = Result(stand, flags).asReach()
            val before = flags.size + gone.size + extra.size + warpFrom.size
            for (r in w.scriptRefs) if (r.map in reach.maps && w.activatable(r, reach))
                for (c in (if (badges == null) w.commands(r.script) else paths.explore(r.script, badges, flags).commands)) when (c.name) {
                    "setflag" -> flags.add(c.args[0])
                    // Commands that set a flag implicitly (ScriptVM / GameContext.recordHallOfFame).
                    "halloffame" -> flags.add("hall_of_fame")
                    "pokedex" -> flags.add("has_pokedex")
                    "unlock" -> flags.add("unlock_${c.args[0]}")
                    "hide", "move" -> gone.add("${r.map}:${c.args[0]}")
                    "warp" -> if (c.args.size >= 3 && c.args[0] in maps) {
                        val t = P(c.args[0], c.args[1].toIntOrNull() ?: 0, c.args[2].toIntOrNull() ?: 0, false)
                        extra.add(t); warpFrom.add(r.map to WorldAnalysis.Node(t.map, t.x, t.y))
                    }
                }
            if (flags.size + gone.size + extra.size + warpFrom.size == before) {
                val edges = HashMap<WorldAnalysis.Node, MutableList<WorldAnalysis.Node>>()
                val finalStand = bfs(moves, npcsGone, flags, gone, extra, closed, edges)
                return Result(finalStand, HashSet(flags), edges, warpFrom, closedIds(badges, flags, openGates))
            }
        }
    }

    private fun gateOpen(g: Gate, badges: Int?, flags: Set<String>, openGates: Set<String>): Boolean =
        badges == null || g.id in openGates || (g.unlessFlag != null && g.unlessFlag in flags) || (g.ifFlag != null && g.ifFlag !in flags) ||
            paths.explore(g.script, badges, flags).cleanEnd

    private fun closedIds(badges: Int?, flags: Set<String>, openGates: Set<String>): Set<String> =
        gates.filter { !gateOpen(it, badges, flags, openGates) }.mapTo(HashSet()) { it.id }

    /** Tile keys per map that a closed gate trigger blocks. */
    private fun closedGates(badges: Int?, flags: Set<String>, openGates: Set<String>): Map<String, Set<Int>> =
        gates.filter { !gateOpen(it, badges, flags, openGates) }.groupBy { it.map }.mapValues { (_, gs) -> gs.flatMap { g -> g.tiles.map { (x, y) -> key(x, y) } }.toSet() }

    private fun behaviors(p: Prepared, x: Int, y: Int, flags: Set<String>): List<TileBehavior> {
        val base = TileRules.behavior(p.m.tileAt(x, y))
        val alts = p.flagTiles[key(x, y)] ?: return listOf(base)
        val extra = alts.filter { it.flag in flags }.map { TileRules.behavior(it.tile) }
        return if (extra.isEmpty()) listOf(base) else (listOf(base) + extra).distinct()
    }

    private fun bfs(moves: Set<FieldMove>, npcsGone: Boolean, flags: Set<String>, goneNpcs: Set<String>, extraStarts: Set<P>,
                    closedGateKeys: Map<String, Set<Int>>, edgesOut: HashMap<WorldAnalysis.Node, MutableList<WorldAnalysis.Node>>?): Set<P> {
        val solidKeys: Map<String, Set<Int>> = prepared.mapValues { (id, pm) ->
            val gate = closedGateKeys[id].orEmpty()
            if (pm.soft.isEmpty()) pm.firmNpc + gate
            else pm.firmNpc + gate + pm.soft.filter { !npcsGone && !(it.hideIf != null && it.hideIf in flags) && "$id:${it.id}" !in goneNpcs }.map { it.key }
        }
        val seen = HashSet<P>()
        val queue = ArrayDeque<P>()
        val entries = HashMap<String, MutableSet<P>>()   // door tiles through which each map was entered (for @back)
        val backUsers = HashSet<String>()                // maps in which a @back warp can be taken
        val mapsSeen = HashSet<String>()
        fun add(p: P) {
            if (!maps.containsKey(p.map) || !maps.getValue(p.map).inBounds(p.x, p.y)) return
            if (seen.add(p)) {
                queue.add(p)
                mapsSeen.add(p.map)
            }
        }
        fun warp(from: MapData, wp: Warp): List<P> {
            if (wp.toMap == Warp.BACK) {
                backUsers.add(from.id)
                return entries[from.id].orEmpty().toList()
            }
            val door = P(from.id, wp.x, wp.y, false)
            val set = entries.getOrPut(wp.toMap) { HashSet() }
            if (set.add(door) && wp.toMap in backUsers) add(door)
            return listOf(P(wp.toMap, wp.toX, wp.toY, false))
        }

        fun solidNpc(pm: Prepared, x: Int, y: Int) = key(x, y) in solidKeys.getValue(pm.m.id)

        fun isFree(pm: Prepared, x: Int, y: Int): Boolean =
            pm.m.inBounds(x, y) && behaviors(pm, x, y, flags).any { !TileRules.isSolid(it) && it != TileBehavior.WATER } && !solidNpc(pm, x, y) && key(x, y) !in pm.obstacles

        /** Where the player ends up after arriving at (x, y) on a tile behaving as [b]; ice keeps sliding in direction [d]. */
        fun arrive(pm: Prepared, x: Int, y: Int, d: Pair<Int, Int>, surf: Boolean, b: TileBehavior, out: MutableList<P>) {
            if (b != TileBehavior.ICE || surf) { out.add(P(pm.m.id, x, y, surf)); return }
            val nx = x + d.first; val ny = y + d.second
            var slid = false
            if (pm.m.inBounds(nx, ny) && key(nx, ny) in pm.visibleItems) out.add(P(pm.m.id, x, y, false))
            if (pm.m.inBounds(nx, ny) && !solidNpc(pm, nx, ny) && key(nx, ny) !in pm.obstacles) {
                for (nb in behaviors(pm, nx, ny, flags)) {
                    val plain = nb != TileBehavior.WATER && nb != TileBehavior.LEDGE_S && nb != TileBehavior.LEDGE_E && nb != TileBehavior.LEDGE_W && !TileRules.isSolid(nb)
                    if (!plain || pm.warps.containsKey(key(nx, ny))) continue
                    slid = true
                    arrive(pm, nx, ny, d, false, nb, out)
                }
            }
            if (!slid) out.add(P(pm.m.id, x, y, false))
        }

        fun outcomes(n: P, d: Pair<Int, Int>): List<P> {
            val pm = prepared.getValue(n.map)
            val m = pm.m
            val (dx, dy) = d
            val nx = n.x + dx; val ny = n.y + dy
            val here = pm.warps[key(n.x, n.y)]
            if (!m.inBounds(nx, ny)) {
                if (here != null) return warp(m, here)
                return w.connectionTarget(m, nx, ny, dx, dy)?.let { t ->
                    val tm = maps.getValue(t.map)
                    listOf(P(t.map, t.x, t.y, n.surf && TileRules.behavior(tm.tileAt(t.x, t.y)) == TileBehavior.WATER))
                } ?: emptyList()
            }
            if (solidNpc(pm, nx, ny)) return emptyList()
            val ob = pm.obstacles[key(nx, ny)]
            if (ob != null) when (ob.kind) {
                "cut_tree" -> if (FieldMove.CUT !in moves) return emptyList()
                "rock_smash" -> if (FieldMove.ROCK_SMASH !in moves) return emptyList()
                "boulder" -> if (FieldMove.STRENGTH !in moves || !isFree(pm, nx + dx, ny + dy)) return emptyList()
                else -> return emptyList()
            }
            val out = ArrayList<P>()
            val hereWarpTile = behaviors(pm, n.x, n.y, flags).contains(TileBehavior.WARP)
            for (b in behaviors(pm, nx, ny, flags)) {
                when (b) {
                    TileBehavior.LEDGE_S, TileBehavior.LEDGE_E, TileBehavior.LEDGE_W -> {
                        val ok = (b == TileBehavior.LEDGE_S && dy == 1) || (b == TileBehavior.LEDGE_E && dx == 1) || (b == TileBehavior.LEDGE_W && dx == -1)
                        val lx = nx + dx; val ly = ny + dy
                        if (ok && !n.surf && m.inBounds(lx, ly) && !solidNpc(pm, lx, ly) &&
                            behaviors(pm, lx, ly, flags).any { !TileRules.isSolid(it) }) {
                            for (lb in behaviors(pm, lx, ly, flags).filter { !TileRules.isSolid(it) }) {
                                if (pm.warps.containsKey(key(lx, ly)) && lb == TileBehavior.WARP) out.addAll(warp(m, pm.warps.getValue(key(lx, ly))))
                                else arrive(pm, lx, ly, d, false, lb, out)
                            }
                        }
                    }
                    TileBehavior.WATER -> if (n.surf) out.add(P(m.id, nx, ny, true)) else if (FieldMove.SURF in moves) out.add(P(m.id, nx, ny, true))
                    else -> if (TileRules.isSolid(b)) {
                        if (hereWarpTile && here != null) out.addAll(warp(m, here))
                    } else {
                        val wp = pm.warps[key(nx, ny)]
                        if (wp != null && b == TileBehavior.WARP) out.addAll(warp(m, wp))
                        else arrive(pm, nx, ny, d, false, b, out)
                    }
                }
            }
            return out
        }

        add(P(w.start.map, w.start.x, w.start.y, false))
        extraStarts.forEach { add(it) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            for (d in dirs) for (t in outcomes(n, d)) {
                add(t)
                edgesOut?.getOrPut(WorldAnalysis.Node(n.map, n.x, n.y)) { ArrayList() }?.add(WorldAnalysis.Node(t.map, t.x, t.y))
            }
            // Waterfall: climb from the water below it while surfing.
            if (n.surf && FieldMove.WATERFALL in moves && FieldMove.SURF in moves) {
                val pm = prepared.getValue(n.map)
                if (pm.m.inBounds(n.x, n.y - 1) && TileRules.behavior(pm.m.tileAt(n.x, n.y - 1)) == TileBehavior.WATERFALL) {
                    var y = n.y
                    while (pm.m.inBounds(n.x, y - 1) && TileRules.behavior(pm.m.tileAt(n.x, y - 1)) == TileBehavior.WATERFALL) y--
                    if (pm.m.inBounds(n.x, y - 1) && !TileRules.isSolid(TileRules.behavior(pm.m.tileAt(n.x, y - 1)))) {
                        val top = P(n.map, n.x, y - 1, TileRules.behavior(pm.m.tileAt(n.x, y - 1)) == TileBehavior.WATER)
                        add(top)
                        edgesOut?.getOrPut(WorldAnalysis.Node(n.map, n.x, n.y)) { ArrayList() }?.add(WorldAnalysis.Node(top.map, top.x, top.y))
                    }
                }
            }
        }
        // `entries` registered after a back user was expanded are handled in warp(); add the ones known now for completeness.
        return seen
    }
}

/**
 * Which commands of a script can run, and whether it can finish without turning the player back (`move player`), given the number
 * of badges and the flags that may be set. Conditions are three-valued: `badges`/`badge` are exact, `flag X` is false while X cannot
 * be set yet and "maybe" afterwards, every other condition is "maybe" (both branches are followed).
 */
class ScriptPaths(private val w: ReachWorld) {
    class Outcome(val commands: List<Instr.Cmd>, val cleanEnd: Boolean)

    private enum class Tri { T, F, M }

    fun explore(scriptId: String, badges: Int, flags: Set<String>, depth: Int = 0): Outcome {
        val code = w.code(scriptId)
        val cmds = LinkedHashSet<Instr.Cmd>()
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Pair<Int, Boolean>>()
        stack.add(0 to false)
        var clean = false
        while (stack.isNotEmpty()) {
            val (pc, back) = stack.removeLast()
            if (pc >= code.size) { if (!back) clean = true; continue }
            if (!seen.add(pc * 2 + if (back) 1 else 0)) continue
            when (val i = code[pc]) {
                is Instr.Cmd -> {
                    cmds.add(i)
                    when {
                        i.name == "return" -> if (!back) clean = true
                        i.name == "move" && i.args.firstOrNull() == "player" -> stack.add(pc + 1 to true)
                        i.name == "call" && depth < 8 -> {
                            val sub = explore(i.args[0], badges, flags, depth + 1)
                            cmds.addAll(sub.commands)
                            stack.add(pc + 1 to (back || !sub.cleanEnd))
                        }
                        else -> stack.add(pc + 1 to back)
                    }
                }
                is Instr.Jump -> stack.add(i.target to back)
                is Instr.JumpIfNot -> when (tri(i.cond, badges, flags)) {
                    Tri.T -> stack.add(pc + 1 to back)
                    Tri.F -> stack.add(i.target to back)
                    Tri.M -> { stack.add(pc + 1 to back); stack.add(i.target to back) }
                }
            }
        }
        return Outcome(cmds.toList(), clean)
    }

    private fun tri(c: Cond, badges: Int, flags: Set<String>): Tri = when (c) {
        is Cond.Not -> when (tri(c.c, badges, flags)) { Tri.T -> Tri.F; Tri.F -> Tri.T; Tri.M -> Tri.M }
        is Cond.And -> tri(c.a, badges, flags).let { a -> val b = tri(c.b, badges, flags); if (a == Tri.F || b == Tri.F) Tri.F else if (a == Tri.T && b == Tri.T) Tri.T else Tri.M }
        is Cond.Or -> tri(c.a, badges, flags).let { a -> val b = tri(c.b, badges, flags); if (a == Tri.T || b == Tri.T) Tri.T else if (a == Tri.F && b == Tri.F) Tri.F else Tri.M }
        is Cond.Atom -> atom(c.words, badges, flags)
    }

    private fun atom(w: List<String>, badges: Int, flags: Set<String>): Tri = when (w[0]) {
        "true" -> Tri.T
        "false" -> Tri.F
        "flag" -> if (w[1] in flags) Tri.M else Tri.F
        "badges" -> if (cmp(badges, w[1], w[2].toInt())) Tri.T else Tri.F
        "badge" -> if (w[1].toInt() <= badges) Tri.T else Tri.F
        else -> Tri.M
    }

    private fun cmp(value: Int, op: String, rhs: Int) = when (op) {
        ">=" -> value >= rhs; "<=" -> value <= rhs; ">" -> value > rhs; "<" -> value < rhs; "==", "=" -> value == rhs; "!=" -> value != rhs
        else -> true
    }
}
