package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.world.FieldMove
import pkmn.engine.world.ItemPickup
import pkmn.engine.world.MapData
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import java.io.File

/**
 * Strict item reachability audit (reports/items.json, reports/items.md). Uses [StrictReach] (ice, solid NPCs, flag tiles,
 * obstacles, boulders) per badge stage from [WorldAnalysis.stage], then applies the game's interaction rules:
 * * a visible item is picked up by facing it from an orthogonally adjacent standing tile (also while surfing);
 * * a hidden item is found by facing its tile and pressing A, but `OverworldScreen.interact` only reaches `checkHidden`
 *   when the faced tile holds no NPC, sign, obstacle or visible item and is not a PC, bookshelf, TV, water or waterfall tile.
 *
 * Verdicts: `reachable` (stage 0), `gated` (needs a later badge stage), `npc-dependent` (only if an NPC that a flag or script
 * can remove is gone), `unreachable` (never, even with all badges and every removable NPC gone).
 */
class ItemAudit(private val root: File, private val write: Boolean = true) {
    data class Defect(val code: String, val message: String, val error: Boolean)

    data class Row(
        val map: String, val id: String, val item: String, val qty: Int, val x: Int, val y: Int, val hidden: Boolean, val tile: String,
        val verdict: String, val stage: Int?, val stageIfNpcGone: Int?, val optimisticStage: Int?, val defects: List<Defect>,
    ) {
        val errors get() = defects.filter { it.error }
        fun toJson() = buildJsonObject {
            put("map", map); put("id", id); put("flag", "item:$id"); put("item", item); put("qty", qty); put("x", x); put("y", y)
            put("hidden", hidden); put("tile", tile); put("verdict", verdict)
            put("earliestStage", stage?.let { JsonPrimitive(it) } ?: JsonNull)
            put("earliestStageIfNpcGone", stageIfNpcGone?.let { JsonPrimitive(it) } ?: JsonNull)
            put("earliestStageOptimisticModel", optimisticStage?.let { JsonPrimitive(it) } ?: JsonNull)
            put("defects", JsonArray(defects.map { buildJsonObject { put("code", it.code); put("message", it.message); put("severity", if (it.error) "error" else "warning") } }))
        }
    }

    data class StageStat(val stage: Int, val moves: List<String>, val optimisticMaps: Int, val firmMaps: Int, val goneMaps: Int, val firmTiles: Int, val goneTiles: Int)
    var stageStats: List<StageStat> = emptyList()
        private set

    class Result(val rows: List<Row>) {
        val unreachable get() = rows.filter { it.verdict == "unreachable" }
        val withErrors get() = rows.filter { it.errors.isNotEmpty() || it.verdict == "unreachable" }
        fun count(verdict: String) = rows.count { it.verdict == verdict }
        fun defectCount(code: String) = rows.count { r -> r.defects.any { it.code == code } }
    }

    private val relevant = setOf(FieldMove.CUT, FieldMove.ROCK_SMASH, FieldMove.STRENGTH, FieldMove.SURF, FieldMove.WATERFALL)
    private val blockedForHidden = setOf(TileBehavior.WATER, TileBehavior.WATERFALL, TileBehavior.PC, TileBehavior.TV, TileBehavior.BOOKSHELF, TileBehavior.SIGN)
    private val dirs = listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0)

    private fun tilesAt(m: MapData, x: Int, y: Int) = (listOf(m.tileAt(x, y)) + m.flagTiles.filter { it.x == x && it.y == y }.map { it.tile }).distinct()

    fun audit(w: WorldAnalysis): Result = audit(w, (0..8).map { s -> w.stage(s).moves }, (0..8).map { s -> w.stage(s).reach })

    /**
     * [usableMoves] are the field moves usable at badge stages 0..8 (badge only; God Bidoof is the learner); [optimistic] is
     * [WorldAnalysis]'s reach per stage, used only to report where the strict model is stricter.
     */
    fun audit(w: ReachWorld, usableMoves: List<Set<FieldMove>>, optimistic: List<WorldAnalysis.Reach>): Result {
        val strict = StrictReach(w)
        val stageMoves = usableMoves.map { moves -> moves.filter { it in relevant }.toSet() }
        val cache = HashMap<Pair<Set<FieldMove>, Boolean>, StrictReach.Result>()
        fun reachAt(stage: Int, gone: Boolean) = cache.getOrPut(stageMoves[stage] to gone) { strict.reach(stageMoves[stage], gone) }
        if (System.getProperty("audit.debug") != null) {
            val firm0 = reachAt(0, false)
            for ((from, tos) in optimistic[0].mapEdges) if (from in firm0.maps) for (t in tos) if (t !in firm0.maps) println("  FRONTIER $from -> $t")
        }
        System.getProperty("audit.map")?.let { id ->
            val m = w.maps.getValue(id)
            println("  EARLIEST STAGE TO STAND ON EACH TILE of $id ('.' never, '#' solid):")
            for (y in 0 until m.height) println(String(CharArray(m.width) { x ->
                val st = (0..8).firstOrNull { reachAt(it, false).canStand(id, x, y) }
                st?.let { '0' + it } ?: if (TileRules.isSolid(TileRules.behavior(m.tileAt(x, y)))) '#' else '.'
            }))
        }
        if (System.getProperty("audit.debug") != null) {
            val last = reachAt(8, true)
            println("  MAPS NOT REACHED at stage 8: ${w.maps.keys.filter { it !in last.maps }}")
            for (st in listOf(3, 4)) println("  stage $st: optimistic-only maps ${optimistic[st].maps - reachAt(st, false).maps}; strict-only-with-NPCs-gone ${reachAt(st, true).maps - reachAt(st, false).maps}")
        }
        stageStats = (0..8).map { s ->
            val firm = reachAt(s, false); val gone = reachAt(s, true)
            StageStat(s, stageMoves[s].map { it.name.lowercase() }.sorted(), optimistic[s].maps.size, firm.maps.size, gone.maps.size, firm.nodes.size, gone.nodes.size)
        }

        // Item ids are map-prefixed by the compiler, but two maps could still collide (`a_b` + `c` vs `a` + `b_c`).
        val byId = w.maps.values.flatMap { m -> m.items.map { m.id to it } }.groupBy { it.second.id }

        val rows = ArrayList<Row>()
        for (m in w.maps.values.sortedBy { it.id }) for (it in m.items.sortedWith(compareBy({ it.y }, { it.x }, { it.id }))) {
            val defects = ArrayList<Defect>()
            fun err(code: String, msg: String) { defects += Defect(code, msg, true) }
            fun warn(code: String, msg: String) { defects += Defect(code, msg, false) }
            val inBounds = m.inBounds(it.x, it.y)
            if (!inBounds) err("out-of-bounds", "item at ${it.x},${it.y} is outside the ${m.width}x${m.height} map")
            val tiles = if (inBounds) tilesAt(m, it.x, it.y) else emptyList()
            val behaviors = tiles.map { t -> TileRules.behavior(t) }
            val npcsHere = m.npcs.filter { n -> n.x == it.x && n.y == it.y }
            val signHere = m.signs.any { s -> s.x == it.x && s.y == it.y }
            val obstacleHere = m.obstacles.any { o -> o.x == it.x && o.y == it.y }
            val visibleHere = m.items.any { o -> o !== it && !o.hidden && o.x == it.x && o.y == it.y }
            var interactable = true
            if (it.hidden) {
                behaviors.firstOrNull { b -> b in blockedForHidden }?.let { b ->
                    err("hidden-on-${b.name.lowercase()}", "hidden item on a ${b.name} tile: interact() never reaches the hidden-item check there"); interactable = false
                }
                if (npcsHere.isNotEmpty()) { err("hidden-under-npc", "hidden item under NPC ${npcsHere.first().id}: pressing A talks to the NPC"); interactable = false }
                if (signHere) { err("hidden-under-sign", "hidden item under a sign: pressing A reads the sign"); interactable = false }
                if (obstacleHere) { err("hidden-under-obstacle", "hidden item under a field obstacle: pressing A uses the obstacle"); interactable = false }
                if (visibleHere) { err("hidden-under-item", "hidden item under a visible item ball on the same tile"); interactable = false }
            } else {
                if (behaviors.isNotEmpty() && behaviors.all { b -> TileRules.isSolid(b) })
                    warn("visible-item-on-solid", "visible item ball drawn on a solid '${tiles.first()}' tile (reachable only by facing it from a neighbour)")
                npcsHere.firstOrNull { n -> n.movement != "wander" }?.let { n -> err("item-under-npc", "visible item under NPC ${n.id}: pressing A talks to the NPC, the item can't be taken") ; interactable = false }
            }
            if (inBounds) {
                if (m.warps.any { wp -> wp.x == it.x && wp.y == it.y } || behaviors.any { b -> b == TileBehavior.WARP })
                    err("item-on-warp", "item on a warp tile (${tiles.first()}): walking there teleports, and a door or stairs tile is a poor place for a pickup")
                m.triggers.filter { t -> it.x in t.x until t.x + t.w && it.y in t.y until t.y + t.h }.forEach { t ->
                    val warps = w.commands(t.script).any { c -> c.name == "warp" || c.name == "teleport" || c.name == "blackout" }
                    warn("item-on-trigger", "item inside the step trigger of script ${t.script}${if (warps) " (which warps the player)" else ""}")
                }
            }
            for ((otherMap, other) in byId[it.id].orEmpty()) if (otherMap != m.id || other !== it)
                err("duplicate-id", "pickup flag item:${it.id} is shared with the item at ${other.x},${other.y} on $otherMap: picking up one removes the other")

            // Reachability per stage.
            fun pick(res: StrictReach.Result): Boolean {
                if (!inBounds || !interactable) return false
                for ((dx, dy) in dirs) {
                    if (!res.canStand(m.id, it.x - dx, it.y - dy)) continue
                    // Talking over a counter takes precedence when someone stands behind it.
                    if (it.hidden && behaviors.any { b -> b == TileBehavior.COUNTER } && m.npcs.any { n -> n.x == it.x + dx && n.y == it.y + dy }) continue
                    return true
                }
                return false
            }
            val optimistic0 = (0..8).firstOrNull { s -> inBounds && (listOf(0 to 0) + dirs).any { (dx, dy) -> WorldAnalysis.Node(m.id, it.x + dx, it.y + dy) in optimistic[s].nodes } }
            val firm = (0..8).firstOrNull { s -> pick(reachAt(s, false)) }
            val gone = (0..8).firstOrNull { s -> pick(reachAt(s, true)) }
            val verdict = when {
                firm != null && firm == 0 -> "reachable"
                firm != null -> "gated"
                gone != null -> "npc-dependent"
                else -> "unreachable"
            }
            if (firm != null && optimistic0 != null && firm > optimistic0) warn("stricter-than-optimistic", "the optimistic model (ProgressionChecker) reaches it at stage $optimistic0, the strict model only at stage $firm")
            if (verdict == "npc-dependent") warn("npc-dependent", "only reachable if a possibly-gone NPC (hideif flag or hidden/moved by a script) is out of the way, from badge stage $gone")
            if (verdict == "unreachable") err("unreachable", reason(w, m, it, interactable))
            rows.add(Row(m.id, it.id, it.item, it.qty, it.x, it.y, it.hidden, tiles.firstOrNull() ?: "", verdict, firm, gone, optimistic0, defects))
        }
        return Result(rows)
    }

    /** Best-effort explanation of why nothing reaches an item. */
    private fun reason(w: ReachWorld, m: MapData, it: ItemPickup, interactable: Boolean): String {
        if (!interactable) return "cannot be picked up by interacting (see the other defects)"
        val final = StrictReach(w).reach(FieldMove.entries.filter { f -> f in relevant }.toSet(), true)
        val mapReached = m.id in final.maps
        if (!mapReached) return "the map ${m.id} is not reachable at all, even with every field move and every removable NPC gone"
        val neighbours = dirs.map { (dx, dy) -> it.x - dx to it.y - dy }.filter { (x, y) -> m.inBounds(x, y) }
        val described = neighbours.joinToString(", ") { (x, y) -> "$x,$y '${m.tileAt(x, y)}'" }
        return "no neighbouring tile can be stood on (neighbours: $described)"
    }

    fun run(): Boolean {
        val w = WorldAnalysis(root)
        val res = audit(w)
        if (write) writeReports(res)
        for (st in stageStats) println("  stage ${st.stage}: maps optimistic ${st.optimisticMaps}, strict ${st.firmMaps}, strict with removable NPCs gone ${st.goneMaps}; standing tiles ${st.firmTiles} / ${st.goneTiles}")
        println("Item audit: ${res.rows.size} items; reachable at start ${res.count("reachable")}, gated by badges ${res.count("gated")}, " +
            "npc-dependent ${res.count("npc-dependent")}, unreachable ${res.count("unreachable")}; " +
            "${res.rows.count { it.errors.isNotEmpty() }} item(s) with errors")
        res.withErrors.take(60).forEach { println("DEFECT ${it.map}:${it.id} (${it.item} at ${it.x},${it.y}): ${(it.errors.map { d -> "${d.code}: ${d.message}" } + if (it.verdict == "unreachable" && it.errors.none { d -> d.code == "unreachable" }) listOf("unreachable") else emptyList()).joinToString("; ")}") }
        return res.withErrors.isEmpty()
    }

    private fun writeReports(res: Result) {
        val ledger = loadLedger()
        val byKey = res.rows.associateBy { "${it.map}:${it.id}" }
        val codes = res.rows.flatMap { r -> r.defects.map { it.code } }.groupingBy { it }.eachCount().toSortedMap()
        val json = buildJsonObject {
            put("model", "strict graph reachability per badge stage (ice slides, solid NPCs, flag tiles after reachable setflag scripts, obstacles need the field move, boulders pushable if the tile behind is free)")
            put("items", res.rows.size)
            put("reachable", res.count("reachable")); put("gated", res.count("gated")); put("npcDependent", res.count("npc-dependent")); put("unreachable", res.count("unreachable"))
            put("itemsWithErrors", res.rows.count { it.errors.isNotEmpty() || it.verdict == "unreachable" })
            put("defectsByCode", JsonObject(codes.mapValues { JsonPrimitive(it.value) }))
            put("fixesApplied", JsonArray(ledger.map { e ->
                val now = byKey["${e.map}:${e.id}"]
                buildJsonObject {
                    put("map", e.map); put("id", e.id); put("item", e.item); put("defect", e.defect); put("reason", e.reason); put("fix", e.fix)
                    put("postFixVerdict", now?.verdict ?: "missing")
                    put("postFixEarliestStage", now?.stage?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("postFixErrors", strArray(now?.errors?.map { it.code }.orEmpty()))
                }
            }))
            put("rows", JsonArray(res.rows.map { it.toJson() }))
        }
        writeJson(File(root, "reports/items.json"), json)
        val sb = StringBuilder()
        sb.append("# Item reachability audit\n\nGenerated by `./gradlew :tools:auditItems`. Rules and model: [docs/WORLD_VALIDATION.md](../docs/WORLD_VALIDATION.md).\n\n")
        sb.append("| | |\n|---|---|\n| items | ${res.rows.size} |\n| reachable from the start | ${res.count("reachable")} |\n| gated (reachable at a later badge stage) | ${res.count("gated")} |\n")
        sb.append("| npc-dependent (only if a removable NPC is gone) | ${res.count("npc-dependent")} |\n| permanently unreachable | ${res.count("unreachable")} |\n")
        sb.append("| items with errors | ${res.rows.count { it.errors.isNotEmpty() || it.verdict == "unreachable" }} |\n\n")
        sb.append("Defects by code: ${if (codes.isEmpty()) "none" else codes.entries.joinToString(", ") { "${it.key} ${it.value}" }}\n\n")
        sb.append("## Defects found and fixes applied\n\n")
        if (ledger.isEmpty()) sb.append("None recorded.\n\n")
        else {
            sb.append("| item | defect | reason | fix | post-fix |\n|---|---|---|---|---|\n")
            for (e in ledger) {
                val now = byKey["${e.map}:${e.id}"]
                sb.append("| `${e.map}:${e.id}` (${e.item}) | ${e.defect} | ${e.reason} | ${e.fix} | ${now?.let { r -> "${r.verdict}${r.stage?.let { s -> ", stage $s" } ?: ""}${if (r.errors.isEmpty()) "" else ", ERRORS: " + r.errors.joinToString { d -> d.code }}" } ?: "missing"} |\n")
            }
            sb.append("\n")
        }
        sb.append("## Remaining findings\n\n")
        val remaining = res.rows.filter { it.defects.isNotEmpty() }
        if (remaining.isEmpty()) sb.append("None.\n\n")
        else for (r in remaining) for (d in r.defects) sb.append("- ${if (d.error) "ERROR" else "warning"} `${r.map}:${r.id}` (${r.item} at ${r.x},${r.y}) ${d.code}: ${d.message}\n")
        sb.append("\n## Gated items (reachable only from a later badge stage)\n\n")
        val gated = res.rows.filter { it.verdict == "gated" }.sortedWith(compareBy({ it.stage }, { it.map }))
        sb.append("| stage | item | map | x,y |\n|---|---|---|---|\n")
        for (r in gated) sb.append("| ${r.stage} | ${r.item}${if (r.hidden) " (hidden)" else ""} | ${r.map}:${r.id} | ${r.x},${r.y} |\n")
        sb.append("\n## Every item\n\n| map | id | item | x,y | hidden | tile | verdict | stage | stage if NPCs gone |\n|---|---|---|---|---|---|---|---|---|\n")
        for (r in res.rows) sb.append("| ${r.map} | ${r.id} | ${r.item} x${r.qty} | ${r.x},${r.y} | ${if (r.hidden) "yes" else ""} | ${r.tile} | ${r.verdict} | ${r.stage ?: "-"} | ${r.stageIfNpcGone ?: "-"} |\n")
        File(root, "reports/items.md").writeText(sb.toString())
    }

    data class LedgerEntry(val map: String, val id: String, val item: String, val defect: String, val reason: String, val fix: String)

    /** Fixes recorded when the defects were repaired (tools/src/main/resources/item-fixes.json); post-fix results are recomputed live. */
    private fun loadLedger(): List<LedgerEntry> {
        val text = ItemAudit::class.java.getResourceAsStream("/item-fixes.json")?.bufferedReader()?.readText() ?: return emptyList()
        return Json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            fun s(k: String) = o[k]!!.jsonPrimitive.content
            LedgerEntry(s("map"), s("id"), s("item"), s("defect"), s("reason"), s("fix"))
        }
    }
}
