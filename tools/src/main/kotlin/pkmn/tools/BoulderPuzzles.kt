package pkmn.tools

import pkmn.engine.world.BoulderRules
import pkmn.engine.world.MapData
import pkmn.engine.world.NpcDef
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import pkmn.engine.world.Warp

/**
 * Exhaustive model of Strength boulders (OverworldScreen.pushBoulder): the player pushes a boulder one tile in the direction of
 * travel onto any non-solid, non-water tile without an NPC, visible item or other obstacle (warp tiles are allowed), and the new
 * position persists across map re-entry (state var `pos:<map>:<flag>`); there is no pull and no reset.
 *
 * For one map the states are the boulder positions. The player may leave and re-enter through any entrance, so in a state the
 * usable region is the union of the walkable components of all entrances not covered by a boulder. A *target* (warp tile,
 * connection edge, item, trigger, NPC to talk to) is obtainable in a state when it lies in that region. The map has a boulder
 * softlock when a state is reachable by pushing from which some target obtainable elsewhere can never be obtained again.
 *
 * Optimistic approximations (never create false alarms): cut/smash obstacles are treated as cleared, flag tiles ignored, NPCs that can hide
 * (`hideif`) absent, wandering NPCs ignored, water impassable for the player.
 */
class BoulderPuzzles(private val maps: Map<String, MapData>) {
    class Softlock(val map: String, val lost: List<String>, val pushes: List<String>)
    class MapReport(val map: String, val states: Int, val targets: Int, val softlocks: List<Softlock>)

    private val dirs = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
    private val dirName = mapOf(0 to -1 to "up", 0 to 1 to "down", -1 to 0 to "left", 1 to 0 to "right")

    fun mapsWithBoulders(): List<String> = maps.values.filter { m -> m.obstacles.any { it.kind == "boulder" } }.map { it.id }.sorted()

    fun analyzeAll(): List<MapReport> = mapsWithBoulders().map { analyze(maps.getValue(it)) }

    private fun key(x: Int, y: Int) = x * 4096 + y

    fun analyze(m: MapData, maxStates: Int = 400_000): MapReport {
        val exit = BoulderRules.ExitCheck(m)
        val walk = BooleanArray(m.width * m.height)       // plain floor the player can stand on
        val warpAt = HashMap<Int, Warp>()
        for (w in m.warps) warpAt[key(w.x, w.y)] = w
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val b = TileRules.behavior(m.tileAt(x, y))
            walk[y * m.width + x] = !TileRules.isSolid(b) && b != TileBehavior.WATER && b != TileBehavior.WARP && key(x, y) !in warpAt &&
                b != TileBehavior.LEDGE_S && b != TileBehavior.LEDGE_E && b != TileBehavior.LEDGE_W
        }
        val solidNpc = HashSet<Int>()
        for (n: NpcDef in m.npcs) if (n.movement != "wander" && n.hideIf == null) solidNpc.add(key(n.x, n.y))
        for (k in solidNpc) walk[(k % 4096) * m.width + k / 4096] = false
        val itemCells = m.items.filter { !it.hidden }.map { key(it.x, it.y) }.toSet()
        val boulders0 = m.obstacles.filter { it.kind == "boulder" }.sortedBy { it.flag }
        val otherObstacles = m.obstacles.filter { it.kind != "boulder" }.map { key(it.x, it.y) }.toSet() // treated as cleared for the player
        // A boulder may only be pushed onto: non-solid non-water, no NPC, no visible item, no warp, no trigger tile (mirrors OverworldScreen.pushBoulder), no boulder.
        fun dest(x: Int, y: Int): Boolean = BoulderRules.mayRestOn(m, x, y) && key(x, y) !in solidNpc && key(x, y) !in itemCells
        fun cell(x: Int, y: Int) = y * m.width + x

        // Entrances: arrival tiles of warps from any map into this one, plus border cells of connected sides.
        val entrances = LinkedHashSet<Int>()
        for (o in maps.values) for (w in o.warps) if (w.toMap == m.id && m.inBounds(w.toX, w.toY)) entrances.add(key(w.toX, w.toY))
        for (c in m.connections) for (i in 0 until (if (c.direction == "north" || c.direction == "south") m.width else m.height)) {
            val (x, y) = when (c.direction) { "north" -> i to 0; "south" -> i to m.height - 1; "west" -> 0 to i; else -> m.width - 1 to i }
            entrances.add(key(x, y))
        }
        for (o in maps.values) for (c in o.connections) if (c.map == m.id) for (i in 0 until (if (c.direction == "north" || c.direction == "south") m.width else m.height)) {
            val (x, y) = when (c.direction) { "north" -> i to m.height - 1; "south" -> i to 0; "west" -> m.width - 1 to i; else -> 0 to i }
            entrances.add(key(x, y))
        }

        // Targets, as (label, test over region+boulders).
        class Target(val label: String, val cells: List<Int>, val standOn: Boolean)
        val targets = ArrayList<Target>()
        for (w in m.warps) targets.add(Target("warp ${w.x},${w.y}", listOf(key(w.x, w.y)), false))
        for (i in m.items) // hidden items are found by facing their tile: any neighbour will do
            targets.add(Target("item ${i.id}", if (i.hidden) dirs.map { (dx, dy) -> key(i.x + dx, i.y + dy) } else listOf(key(i.x, i.y)), true))
        for (t in m.triggers) targets.add(Target("trigger ${t.script}", listOf(key(t.x, t.y)), true))
        for (n in m.npcs) if (n.script != null || n.trainer != null || n.text != null) targets.add(Target("npc ${n.id}", dirs.map { (dx, dy) -> key(n.x + dx, n.y + dy) }, true))
        for (c in m.connections) {
            val edge = when (c.direction) {
                "north" -> (0 until m.width).map { key(it, 0) }; "south" -> (0 until m.width).map { key(it, m.height - 1) }
                "west" -> (0 until m.height).map { key(0, it) }; else -> (0 until m.height).map { key(m.width - 1, it) }
            }
            targets.add(Target("connection ${c.direction}", edge, true))
        }

        // State = sorted boulder positions encoded as one Long-list key.
        fun encode(b: IntArray) = b.joinToString(",")
        class Node(val boulders: IntArray, var mask: BitSetLite, val succ: ArrayList<Int> = ArrayList(), var parent: Int = -1, var via: String = "")
        val index = HashMap<String, Int>()
        val nodes = ArrayList<Node>()

        fun region(b: IntArray): BooleanArray {
            val blocked = b.toSet()
            val reg = BooleanArray(m.width * m.height)
            val q = ArrayDeque<Int>()
            for (e in entrances) {
                val x = e / 4096; val y = e % 4096
                if (m.inBounds(x, y) && walk[cell(x, y)] && e !in blocked && !reg[cell(x, y)]) { reg[cell(x, y)] = true; q.add(e) }
            }
            while (q.isNotEmpty()) {
                val k = q.removeFirst(); val x = k / 4096; val y = k % 4096
                for ((dx, dy) in dirs) {
                    val nx = x + dx; val ny = y + dy
                    if (m.inBounds(nx, ny) && walk[cell(nx, ny)] && !reg[cell(nx, ny)] && key(nx, ny) !in blocked) { reg[cell(nx, ny)] = true; q.add(key(nx, ny)) }
                }
            }
            return reg
        }

        fun maskOf(reg: BooleanArray, b: IntArray): BitSetLite {
            val blocked = b.toSet(); val bits = BitSetLite(targets.size)
            fun inReg(k: Int) = m.inBounds(k / 4096, k % 4096) && reg[cell(k / 4096, k % 4096)]
            for ((i, t) in targets.withIndex()) {
                val ok = if (t.standOn) t.cells.any { it !in blocked && inReg(it) }
                else t.cells.any { c -> c !in blocked && dirs.any { (dx, dy) -> inReg(key(c / 4096 + dx, c % 4096 + dy)) } }
                if (ok) bits.set(i)
            }
            return bits
        }

        fun add(b: IntArray, parent: Int, via: String): Int {
            val k = encode(b)
            index[k]?.let { return it }
            val reg = region(b)
            val n = Node(b, maskOf(reg, b), parent = parent, via = via)
            nodes.add(n); index[k] = nodes.size - 1
            return nodes.size - 1
        }

        if ("boulder_reset" in m.tags) return MapReport(m.id, 1, targets.size, visitSoftlocks(m, entrances, exit))
        val start = IntArray(boulders0.size) { key(boulders0[it].x, boulders0[it].y) }
        add(start, -1, "")
        var head = 0
        while (head < nodes.size && nodes.size < maxStates) {
            val id = head++
            val n = nodes[id]
            val reg = region(n.boulders)
            val blocked = n.boulders.toSet()
            for ((bi, bk) in n.boulders.withIndex()) {
                val bx = bk / 4096; val by = bk % 4096
                for ((dx, dy) in dirs) {
                    val px = bx - dx; val py = by - dy
                    val qx = bx + dx; val qy = by + dy
                    if (!m.inBounds(px, py) || !reg[cell(px, py)]) continue
                    if (!dest(qx, qy) || key(qx, qy) in blocked) continue
                    val nb = n.boulders.copyOf(); nb[bi] = key(qx, qy)
                    if (!exit.reaches(nb.toSet(), bx, by)) continue // the game refuses a push that walls the player in
                    val to = add(nb, id, "push boulder ${boulders0[bi].flag} from $bx,$by ${dirName.getValue(dx to dy)} to $qx,$qy")
                    n.succ.add(to)
                }
            }
        }


        // closure(S) = union of masks over everything reachable from S (fixpoint over successors).
        val closure = nodes.map { it.mask.copy() }
        var changed = true
        while (changed) {
            changed = false
            for (i in nodes.indices.reversed()) for (s in nodes[i].succ) if (closure[i].orIn(closure[s])) changed = true
        }
        val all = closure[0]
        val softlocks = ArrayList<Softlock>()
        for (i in nodes.indices) {
            val lost = (0 until targets.size).filter { all.get(it) && !closure[i].get(it) }
            if (lost.isEmpty()) continue
            val path = ArrayList<String>(); var c = i
            while (nodes[c].parent >= 0) { path.add(nodes[c].via); c = nodes[c].parent }
            softlocks.add(Softlock(m.id, lost.map { targets[it].label }, path.reversed()))
        }
        // Report the shortest push sequences only (the first few are enough to reproduce).
        return MapReport(m.id, nodes.size, targets.size, softlocks.sortedBy { it.pushes.size }.take(3))
    }

    /**
     * Maps tagged `boulder_reset` forget their boulder positions whenever the player enters from another map, so only one visit
     * matters. The game refuses any push after which the player's region has no way out ([BoulderRules.reachesExit]), so every
     * position reachable by pushing keeps a way out by construction; what remains to verify is that each entrance starts with one.
     */
    private fun visitSoftlocks(m: MapData, entrances: Set<Int>, exit: BoulderRules.ExitCheck): List<Softlock> {
        val b0 = m.obstacles.filter { it.kind == "boulder" }.map { it.x * 4096 + it.y }.toSet()
        return entrances.filter { m.inBounds(it / 4096, it % 4096) && it !in b0 && !exit.reaches(b0, it / 4096, it % 4096) }
            .map { Softlock(m.id, listOf("exit from entrance ${it / 4096},${it % 4096}"), emptyList()) }
    }

    /** Minimal growable bitset (avoids java.util.BitSet.or allocations in the fixpoint). */
    class BitSetLite(private val n: Int) {
        private val w = LongArray((n + 63) / 64)
        fun set(i: Int) { w[i shr 6] = w[i shr 6] or (1L shl (i and 63)) }
        fun get(i: Int) = (w[i shr 6] ushr (i and 63)) and 1L != 0L
        fun copy() = BitSetLite(n).also { System.arraycopy(w, 0, it.w, 0, w.size) }
        fun orIn(o: BitSetLite): Boolean { var ch = false; for (i in w.indices) { val v = w[i] or o.w[i]; if (v != w[i]) { w[i] = v; ch = true } }; return ch }
    }
}
