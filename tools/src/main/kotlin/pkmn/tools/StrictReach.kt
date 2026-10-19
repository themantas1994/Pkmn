package pkmn.tools

import pkmn.engine.world.FieldMove
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
 * Not modelled (kept optimistic, like [WorldAnalysis]): conditions inside scripts, step-trigger scripts that turn the player
 * back, Fly, items blocking (a visible item can always be picked up from where the player stands, which removes it).
 */
class StrictReach(private val w: ReachWorld) {
    data class P(val map: String, val x: Int, val y: Int, val surf: Boolean)

    class Result(val stand: Set<P>, val flags: Set<String>) {
        private val tiles: Set<WorldAnalysis.Node> by lazy { stand.mapTo(HashSet()) { WorldAnalysis.Node(it.map, it.x, it.y) } }
        val nodes: Set<WorldAnalysis.Node> get() = tiles
        fun canStand(map: String, x: Int, y: Int) = WorldAnalysis.Node(map, x, y) in tiles
        val maps: Set<String> by lazy { tiles.mapTo(HashSet()) { it.map } }
        fun asReach() = WorldAnalysis.Reach(tiles, emptyMap(), emptyMap(), emptyMap())
    }

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

    /** Computes the standing positions reachable with [moves]; see the class comment for [npcsGone]. */
    fun reach(moves: Set<FieldMove>, npcsGone: Boolean): Result {
        val flags = HashSet<String>()
        val gone = HashSet<String>()   // "map:npc" removed by a reachable script
        val extra = HashSet<P>()       // landing spots of `warp` commands in scripts the player can trigger (ferries, cutscenes)
        while (true) {
            val stand = bfs(moves, npcsGone, flags, gone, extra)
            val reach = Result(stand, flags).asReach()
            val before = flags.size + gone.size + extra.size
            for (r in w.scriptRefs) if (r.map in reach.maps && w.activatable(r, reach))
                for (c in w.commands(r.script)) when (c.name) {
                    "setflag" -> flags.add(c.args[0])
                    // Commands that set a flag implicitly (ScriptVM / GameContext.recordHallOfFame).
                    "halloffame" -> flags.add("hall_of_fame")
                    "pokedex" -> flags.add("has_pokedex")
                    "unlock" -> flags.add("unlock_${c.args[0]}")
                    "hide", "move" -> gone.add("${r.map}:${c.args[0]}")
                    "warp" -> if (c.args.size >= 3 && c.args[0] in maps) extra.add(P(c.args[0], c.args[1].toIntOrNull() ?: 0, c.args[2].toIntOrNull() ?: 0, false))
                }
            if (flags.size + gone.size + extra.size == before) return Result(stand, HashSet(flags))
        }
    }

    private fun behaviors(p: Prepared, x: Int, y: Int, flags: Set<String>): List<TileBehavior> {
        val base = TileRules.behavior(p.m.tileAt(x, y))
        val alts = p.flagTiles[key(x, y)] ?: return listOf(base)
        val extra = alts.filter { it.flag in flags }.map { TileRules.behavior(it.tile) }
        return if (extra.isEmpty()) listOf(base) else (listOf(base) + extra).distinct()
    }

    private fun bfs(moves: Set<FieldMove>, npcsGone: Boolean, flags: Set<String>, goneNpcs: Set<String>, extraStarts: Set<P>): Set<P> {
        val solidKeys: Map<String, Set<Int>> = prepared.mapValues { (id, pm) ->
            if (pm.soft.isEmpty()) pm.firmNpc
            else pm.firmNpc + pm.soft.filter { !npcsGone && !(it.hideIf != null && it.hideIf in flags) && "$id:${it.id}" !in goneNpcs }.map { it.key }
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
            for (d in dirs) for (t in outcomes(n, d)) add(t)
            // Waterfall: climb from the water below it while surfing.
            if (n.surf && FieldMove.WATERFALL in moves && FieldMove.SURF in moves) {
                val pm = prepared.getValue(n.map)
                if (pm.m.inBounds(n.x, n.y - 1) && TileRules.behavior(pm.m.tileAt(n.x, n.y - 1)) == TileBehavior.WATERFALL) {
                    var y = n.y
                    while (pm.m.inBounds(n.x, y - 1) && TileRules.behavior(pm.m.tileAt(n.x, y - 1)) == TileBehavior.WATERFALL) y--
                    if (pm.m.inBounds(n.x, y - 1)) {
                        val top = P(n.map, n.x, y - 1, TileRules.behavior(pm.m.tileAt(n.x, y - 1)) == TileBehavior.WATER)
                        add(top)
                    }
                }
            }
        }
        // `entries` registered after a back user was expanded are handled in warp(); add the ones known now for completeness.
        return seen
    }
}
