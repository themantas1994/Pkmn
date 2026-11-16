package pkmn.tools

import pkmn.engine.world.CompiledScript
import pkmn.engine.world.Facing
import pkmn.engine.world.Instr
import pkmn.engine.world.MapData
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import pkmn.engine.world.Warp
import java.io.File

/**
 * Placement checks on the compiled world: things the reference checks of `compileMaps` cannot see because every id exists, but the
 * object stands where the player can never use it (C7-5 and its classes). Pure functions over [MapData] and scripts.
 *
 * Rules (each violation has a stable [Violation.key] that the baseline file lists):
 * * `npc.enclosed`, `sign.enclosed`: a non-wandering NPC or a sign stands on a solid tile with no standable neighbour (counters excepted);
 * * `sign.out-of-bounds`, `trigger.out-of-bounds`: a sign, or any tile of a trigger rectangle, lies outside the map;
 * * `sign.no-tile`: a sign on a wall, rock or statue tile that no `tile X Y sign` override turns into a sign tile (it is not drawn);
 * * `trainer.short-sight`: a standing trainer whose line of sight is 0-1 tiles (it can never spot the player on approach);
 * * `warp.arrival`: a warp lands on a non-walkable tile, in tall grass, or farther than one tile from the door that leads back;
 * * `ledge.dead`: a ledge tile whose jump-off or landing tile is solid or out of bounds;
 * * `choice.no-cancel`: a `choice` whose last option is not a cancel while the script goes on to give something or set a flag
 *   (B on a menu picks the last option).
 */
object WorldPlacementChecks {
    data class Violation(val rule: String, val where: String, val pos: String, val detail: String) {
        /** Baseline line: `rule where pos`. */
        val key get() = "$rule $where $pos"
        override fun toString() = "$key: $detail"
    }

    /** Last options that mean "back out": the only ones that make B on a `choice` menu harmless. */
    val CANCEL_OPTIONS = setOf("cancel", "not now", "not yet", "no")

    private val GIVING = setOf("giveitem", "givemon", "giveegg", "tm", "setflag", "trade", "badge")

    private val DIRS = listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0)

    /** Behaviours of the ground tile and of every `tileif` replacement: a gate that a flag opens counts as open. */
    private fun variants(m: MapData, x: Int, y: Int): List<TileBehavior> =
        (listOf(m.tileAt(x, y)) + m.flagTiles.filter { it.x == x && it.y == y }.map { it.tile }).map { TileRules.behavior(it) }

    private fun solidEverywhere(m: MapData, x: Int, y: Int) = !m.inBounds(x, y) || variants(m, x, y).all { TileRules.isSolid(it) }

    private fun standableNeighbour(m: MapData, x: Int, y: Int) = DIRS.any { (dx, dy) -> !solidEverywhere(m, x + dx, y + dy) }

    private fun obstacleAt(m: MapData, x: Int, y: Int) = m.obstacles.any { it.x == x && it.y == y }

    /** Mirror of `Overworld.walkableTerrain` on the ground tile, plus water (a surfing player is spotted across it). */
    private fun sightTerrain(m: MapData, x: Int, y: Int): Boolean {
        val b = TileRules.behavior(m.tileAt(x, y))
        if (b == TileBehavior.WATER) return true
        return b !in setOf(TileBehavior.WARP, TileBehavior.LEDGE_S, TileBehavior.LEDGE_E, TileBehavior.LEDGE_W, TileBehavior.ICE) && !TileRules.isSolid(b)
    }

    fun check(maps: Collection<MapData>, scripts: Map<String, CompiledScript>): List<Violation> {
        val byId = maps.associateBy { it.id }
        val out = ArrayList<Violation>()
        for (m in maps.sortedBy { it.id }) {
            enclosed(m, out); signs(m, out); triggers(m, out); trainers(m, out); ledges(m, out); arrivals(m, byId, out)
        }
        for (s in scripts.values.sortedBy { it.id }) choices(s, out)
        return out
    }

    private fun enclosed(m: MapData, out: MutableList<Violation>) {
        for (n in m.npcs) {
            if (n.movement == "wander" || !m.inBounds(n.x, n.y)) continue
            val own = variants(m, n.x, n.y)
            if (own.all { TileRules.isSolid(it) } && TileBehavior.COUNTER !in own && !standableNeighbour(m, n.x, n.y))
                out += Violation("npc.enclosed", m.id, "${n.x},${n.y}", "npc ${n.id} stands on solid '${m.tileAt(n.x, n.y)}' with every neighbour solid")
        }
        for (s in m.signs) {
            if (!m.inBounds(s.x, s.y)) continue
            val own = variants(m, s.x, s.y)
            if (own.all { TileRules.isSolid(it) } && TileBehavior.COUNTER !in own && !standableNeighbour(m, s.x, s.y))
                out += Violation("sign.enclosed", m.id, "${s.x},${s.y}", "sign on solid '${m.tileAt(s.x, s.y)}' with every neighbour solid")
        }
    }

    private fun signs(m: MapData, out: MutableList<Violation>) {
        for (s in m.signs) {
            if (!m.inBounds(s.x, s.y)) { out += Violation("sign.out-of-bounds", m.id, "${s.x},${s.y}", "sign outside the ${m.width}x${m.height} map"); continue }
            // SOLID is a plain wall, rock, statue, tree, bed...; SIGN, PC, TV, bookshelf, counter and the healing machine are drawn as what they are.
            if (variants(m, s.x, s.y).any { it == TileBehavior.SOLID } && variants(m, s.x, s.y).none { it == TileBehavior.SIGN })
                out += Violation("sign.no-tile", m.id, "${s.x},${s.y}", "sign on '${m.tileAt(s.x, s.y)}' is not drawn as a sign (add 'tile ${s.x} ${s.y} sign')")
        }
    }

    private fun triggers(m: MapData, out: MutableList<Violation>) {
        for (t in m.triggers) if (!m.inBounds(t.x, t.y) || !m.inBounds(t.x + t.w - 1, t.y + t.h - 1))
            out += Violation("trigger.out-of-bounds", m.id, "${t.x},${t.y}", "trigger ${t.w}x${t.h} (${t.script}) reaches outside the ${m.width}x${m.height} map")
    }

    /** Tiles a standing trainer could see ahead: walkable terrain (or water) until a solid tile, obstacle, warp, ledge or the map edge. */
    fun sightLine(m: MapData, n: pkmn.engine.world.NpcDef): Int {
        val f = Facing.parse(n.facing)
        var x = n.x; var y = n.y; var len = 0
        while (true) {
            x += f.dx; y += f.dy
            if (!m.inBounds(x, y) || obstacleAt(m, x, y) || !sightTerrain(m, x, y)) return len
            len++
        }
    }

    private fun trainers(m: MapData, out: MutableList<Violation>) {
        for (n in m.npcs) {
            if (n.trainer == null || n.sight <= 0 || n.movement != "static" || !m.inBounds(n.x, n.y)) continue
            val len = sightLine(m, n)
            if (len < 2) out += Violation("trainer.short-sight", m.id, "${n.x},${n.y}", "trainer ${n.id} facing ${n.facing} sees $len tile(s)")
        }
    }

    private fun ledges(m: MapData, out: MutableList<Violation>) {
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val (dx, dy) = when (TileRules.behavior(m.tileAt(x, y))) {
                TileBehavior.LEDGE_S -> 0 to 1; TileBehavior.LEDGE_E -> 1 to 0; TileBehavior.LEDGE_W -> -1 to 0; else -> continue
            }
            val from = x - dx to y - dy
            val land = x + dx to y + dy
            val why = when {
                solidEverywhere(m, from.first, from.second) -> "the tile to jump from (${from.first},${from.second}) is solid or outside the map"
                solidEverywhere(m, land.first, land.second) -> "the landing tile (${land.first},${land.second}) is solid or outside the map"
                else -> continue
            }
            out += Violation("ledge.dead", m.id, "$x,$y", "ledge '${m.tileAt(x, y)}': $why")
        }
    }

    private fun arrivals(m: MapData, byId: Map<String, MapData>, out: MutableList<Violation>) {
        for (w in m.warps) {
            if (w.toMap == Warp.BACK) continue
            val t = byId[w.toMap] ?: continue
            if (!t.inBounds(w.toX, w.toY)) continue
            val pos = "${w.x},${w.y}"
            val tile = t.tileAt(w.toX, w.toY)
            val b = TileRules.behavior(tile)
            val problem = when {
                b != TileBehavior.WARP && (TileRules.isSolid(b) || b == TileBehavior.WATER) -> "lands on '$tile', which cannot be walked"
                b == TileBehavior.TALL_GRASS -> "lands in tall grass (a wild encounter on the first step)"
                else -> {
                    val doors = t.warps.filter { it.toMap == m.id }
                    if (doors.isNotEmpty() && doors.none { Math.abs(it.x - w.toX) + Math.abs(it.y - w.toY) <= 1 })
                        "lands at ${w.toX},${w.toY}, more than one tile from the door back to ${m.id} (${doors.joinToString { "${it.x},${it.y}" }})"
                    else null
                }
            } ?: continue
            out += Violation("warp.arrival", m.id, "$pos->${w.toMap}", "warp $pos $problem in ${w.toMap}")
        }
    }

    private fun choices(s: CompiledScript, out: MutableList<Violation>) {
        for ((i, ins) in s.code.withIndex()) {
            if (ins !is Instr.Cmd || ins.name != "choice" || ins.args.size < 3) continue
            val last = ins.args.last().trim().lowercase()
            if (last in CANCEL_OPTIONS) continue
            val gives = s.code.drop(i + 1).filterIsInstance<Instr.Cmd>().firstOrNull { it.name in GIVING } ?: continue
            out += Violation("choice.no-cancel", s.id, "line ${ins.line}",
                "choice '${ins.args.first()}' ends in '${ins.args.last()}' (B picks it) and the script goes on to ${gives.name} (line ${gives.line})")
        }
    }

    // ------------------------------------------------------------------ baseline

    const val BASELINE_PATH = "tools/world-placement-baseline.txt"

    /** Baseline lines: `rule where pos`, blank lines and `#` comments ignored. */
    fun parseBaseline(text: String): Set<String> =
        text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSortedSet()

    fun loadBaseline(f: File): Set<String> = if (f.exists()) parseBaseline(f.readText()) else emptySet()

    /** Baseline entries that no longer occur: fixing a violation must remove its line, so the baseline only shrinks. */
    fun stale(baseline: Set<String>, violations: List<Violation>): Set<String> = baseline - violations.map { it.key }.toSet()

    fun render(violations: List<Violation>): String = buildString {
        appendLine("# World placement baseline: violations of tools/WorldPlacementChecks that exist today.")
        appendLine("# Format: rule where pos. A line may only be removed (fix the map), never added; CI fails on a stale line.")
        for (v in violations.sortedBy { it.key }) appendLine(v.key)
    }
}
