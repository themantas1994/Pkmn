package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.world.Entrances
import pkmn.engine.world.Instr
import pkmn.engine.world.MapData
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import pkmn.engine.world.Warp
import java.io.File

/**
 * Audits every warp of the compiled world (assets/maps) and writes reports/entrances.json and reports/entrances.md:
 * * the entrance tile must be a warp tile (or become one through a `tileif` flag tile: a gated warp) and its kind must match
 *   the destination class ([Entrances.compatible]);
 * * the warp tile must be approachable and the landing tile must be walkable and not enclosed;
 * * the destination must lead back (a warp into the source map, or `@back`), landing near the door it came from.
 */
class EntranceAudit(private val root: File, private val write: Boolean = true) {
    /** [tile] is the ground tile; [gatedTile] the warp tile a flag turns it into (null for an ordinary warp). */
    data class Row(
        val map: String, val x: Int, val y: Int, val tile: String, val gatedTile: String?, val kind: String?,
        val destMap: String, val destClass: String, val destX: Int, val destY: Int, val destTile: String,
        val fromClass: String, val reverse: String, val reverseDistance: Int?, val traversable: Boolean, val problems: List<String>,
    ) {
        val verdict get() = if (problems.isEmpty()) "ok" else "conflict"
        fun toJson() = buildJsonObject {
            put("map", map); put("x", x); put("y", y); put("tile", tile)
            gatedTile?.let { put("gatedTile", it) }
            put("kind", kind?.let { JsonPrimitive(it) } ?: JsonNull)
            put("fromClass", fromClass)
            put("destMap", destMap); put("destClass", destClass); put("destX", destX); put("destY", destY); put("destTile", destTile)
            put("reverse", reverse)
            put("reverseDistance", reverseDistance?.let { JsonPrimitive(it) } ?: JsonNull)
            put("traversable", traversable)
            put("verdict", verdict)
            put("problems", strArray(problems))
        }
    }

    class Result(val rows: List<Row>) {
        val conflicts get() = rows.filter { it.problems.isNotEmpty() }
        fun countBy(prefix: String) = rows.count { r -> r.problems.any { it.startsWith(prefix) } }
    }

    /** Solid unless the ground tile or any `tileif` replacement of it can be walked (a gate that a flag opens is approachable). */
    private fun solid(m: MapData, x: Int, y: Int) = !m.inBounds(x, y) ||
        (listOf(m.tileAt(x, y)) + m.flagTiles.filter { it.x == x && it.y == y }.map { it.tile }).all { TileRules.isSolid(TileRules.behavior(it)) }

    fun audit(maps: Map<String, MapData>, scriptWarpTargets: Set<String> = emptySet()): Result {
        val rows = ArrayList<Row>()
        for (m in maps.values.sortedBy { it.id }) for (wp in m.warps) {
            val problems = ArrayList<String>()
            val target = if (wp.toMap == Warp.BACK) null else maps[wp.toMap]
            val fromClass = Entrances.destinationKind(m).name
            val base = if (m.inBounds(wp.x, wp.y)) m.tileAt(wp.x, wp.y) else ""
            if (!m.inBounds(wp.x, wp.y)) problems += "warp out of bounds"
            val warpTiles = if (m.inBounds(wp.x, wp.y)) Entrances.warpTilesAt(m, wp.x, wp.y) else emptyList()
            val gated = warpTiles.isNotEmpty() && TileRules.behavior(base) != TileBehavior.WARP
            val shown = if (gated) warpTiles.first() else base
            if (warpTiles.isEmpty()) problems += "not-warp-tile: '$base' is not a warp tile"
            if (wp.toMap != Warp.BACK && target == null) problems += "unknown destination map '${wp.toMap}'"
            if (target != null) for (t in warpTiles) if (!Entrances.compatible(t, m, target))
                problems += "incompatible-entrance: '$t' (${Entrances.kindOfTile(t)}) does not match ${target.id} (${Entrances.destinationKind(target)}, from $fromClass)"

            // Is the warp tile approachable (some non-solid neighbour, or the map edge)?
            var traversable = true
            if (m.inBounds(wp.x, wp.y)) {
                val edge = wp.x == 0 || wp.y == 0 || wp.x == m.width - 1 || wp.y == m.height - 1
                val approach = listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0).any { (dx, dy) -> !solid(m, wp.x + dx, wp.y + dy) }
                if (!approach && !edge) { traversable = false; problems += "unapproachable: every neighbour of the warp tile is solid" }
            }
            var destTile = ""
            if (target != null) {
                if (!target.inBounds(wp.toX, wp.toY)) { traversable = false; problems += "landing out of bounds" }
                else {
                    destTile = target.tileAt(wp.toX, wp.toY)
                    val b = TileRules.behavior(destTile)
                    if (TileRules.isSolid(b) || b == TileBehavior.WATER) { traversable = false; problems += "landing-not-walkable: landing on '$destTile'" }
                    else {
                        val exit = b == TileBehavior.WARP || listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0).any { (dx, dy) -> !solid(target, wp.toX + dx, wp.toY + dy) }
                        if (!exit) { traversable = false; problems += "landing-enclosed: every neighbour of the landing tile is solid" }
                    }
                }
            }

            // Reverse transition.
            var reverse = "n/a"
            var dist: Int? = null
            if (wp.toMap == Warp.BACK) {
                val entered = maps.values.any { q -> q.warps.any { it.toMap == m.id } }
                reverse = if (entered) "ok" else "missing"
                if (!entered) problems += "no-reverse: @back warp in a map nothing leads into"
            } else if (target != null) {
                val back = target.warps.filter { it.toMap == m.id }
                val backBack = target.warps.any { it.toMap == Warp.BACK }
                if (back.isNotEmpty()) {
                    dist = back.minOf { Math.abs(it.toX - wp.x) + Math.abs(it.toY - wp.y) }
                    reverse = if (dist <= 3) "ok" else "far"
                } else if (backBack) reverse = "ok"
                else if ("league" in m.tags && "league" in target.tags) reverse = "one-way (league chain)"
                else if (m.id in scriptWarpTargets) reverse = "one-way (entered by script)"
                else { reverse = "missing"; problems += "no-reverse: ${target.id} has no warp back to ${m.id}" }
            }
            rows.add(Row(m.id, wp.x, wp.y, base, if (gated) shown else null, Entrances.kindOfTile(shown)?.name,
                wp.toMap, target?.let { Entrances.destinationKind(it).name } ?: "(entry map)", wp.toX, wp.toY, destTile, fromClass,
                reverse, dist, traversable, problems))
        }
        return Result(rows)
    }

    /** Audits the compiled world under [root]/assets (maps plus the scripts that `warp` the player into maps). */
    fun auditWorld(): Pair<Result, Map<String, MapData>> {
        val content = WorldContent(DirectoryContentSource(File(root, "assets")))
        val maps = content.index.maps.associateWith { content.map(it) }
        val scriptWarpTargets = content.scripts.values.flatMap { sc -> sc.code.filterIsInstance<Instr.Cmd>().filter { it.name == "warp" }.map { it.args[0] } }.toSet()
        return audit(maps, scriptWarpTargets) to maps
    }

    fun run(): Boolean {
        val (res, maps) = auditWorld()
        if (write) writeReports(res, maps)
        val c = res.conflicts
        println("Entrance audit: ${res.rows.size} warps in ${maps.size} maps; ${c.size} conflicts " +
            "(incompatible ${res.countBy("incompatible-entrance")}, not-warp-tile ${res.countBy("not-warp-tile")}, no-reverse ${res.countBy("no-reverse")}, " +
            "untraversable ${res.rows.count { !it.traversable }}), ${res.rows.count { it.reverse == "far" }} far reverse landings, ${res.rows.count { it.gatedTile != null }} gated")
        c.take(40).forEach { println("CONFLICT ${it.map} ${it.x},${it.y} ${it.tile} -> ${it.destMap}: ${it.problems.joinToString("; ")}") }
        return c.isEmpty()
    }

    private fun writeReports(res: Result, maps: Map<String, MapData>) {
        val counts = res.rows.groupingBy { it.kind ?: "none" }.eachCount().toSortedMap()
        val json = buildJsonObject {
            put("warps", res.rows.size)
            put("maps", maps.size)
            put("conflicts", res.conflicts.size)
            put("incompatibleEntrance", res.countBy("incompatible-entrance"))
            put("notWarpTile", res.countBy("not-warp-tile"))
            put("noReverse", res.countBy("no-reverse"))
            put("untraversable", res.rows.count { !it.traversable })
            put("farReverse", res.rows.count { it.reverse == "far" })
            put("gated", res.rows.count { it.gatedTile != null })
            put("byEntranceKind", JsonObject(counts.mapValues { JsonPrimitive(it.value) }))
            put("rows", JsonArray(res.rows.map { it.toJson() }))
        }
        writeJson(File(root, "reports/entrances.json"), json)
        val sb = StringBuilder()
        sb.append("# Entrance audit\n\nGenerated by `./gradlew :tools:auditEntrances` from `assets/maps`. Rules: [docs/WORLD_VALIDATION.md](../docs/WORLD_VALIDATION.md).\n\n")
        sb.append("| | |\n|---|---|\n")
        sb.append("| warps audited | ${res.rows.size} |\n| conflicts | ${res.conflicts.size} |\n")
        sb.append("| incompatible entrance tile | ${res.countBy("incompatible-entrance")} |\n| not a warp tile | ${res.countBy("not-warp-tile")} |\n")
        sb.append("| no reverse transition | ${res.countBy("no-reverse")} |\n| not traversable | ${res.rows.count { !it.traversable }} |\n")
        sb.append("| reverse lands more than 3 tiles from the door (informational) | ${res.rows.count { it.reverse == "far" }} |\n")
        sb.append("| gated warps (wall until a flag turns it into stairs) | ${res.rows.count { it.gatedTile != null }} |\n\n")
        sb.append("Entrance tiles by kind: ${counts.entries.joinToString(", ") { "${it.key} ${it.value}" }}\n\n")
        sb.append("## Conflicts\n\n")
        if (res.conflicts.isEmpty()) sb.append("None.\n\n")
        else for (r in res.conflicts) sb.append("- `${r.map}` ${r.x},${r.y} `${r.tile}` -> `${r.destMap}`: ${r.problems.joinToString("; ")}\n")
        sb.append("\n## Every warp\n\n")
        sb.append("| map | x,y | tile | kind | from | destination | dest class | dest x,y | reverse | traversable | verdict |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
        for (r in res.rows) {
            val tile = if (r.gatedTile != null) "${r.gatedTile} (gated, ground ${r.tile})" else r.tile
            sb.append("| ${r.map} | ${r.x},${r.y} | $tile | ${r.kind ?: "-"} | ${r.fromClass} | ${r.destMap} | ${r.destClass} | ${r.destX},${r.destY} | " +
                "${r.reverse}${r.reverseDistance?.let { " ($it)" } ?: ""} | ${if (r.traversable) "yes" else "NO"} | ${r.verdict} |\n")
        }
        File(root, "reports/entrances.md").writeText(sb.toString())
    }
}
