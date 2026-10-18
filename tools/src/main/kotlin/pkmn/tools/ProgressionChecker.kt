package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.world.FieldMove
import java.io.File

/**
 * Checks that the campaign can be completed in badge order without softlocks:
 * * every gym leader is reachable with the badges earned before it (field moves need the badge AND an obtainable
 *   Pokémon that can learn the move),
 * * the Hall of Fame is reachable with eight badges,
 * * every map, item and trainer is reachable by the end of the game,
 * * no reachable tile is a trap (a place from which no Pokémon Center or the start can be reached again).
 * Writes reports/progression.json.
 */
class ProgressionChecker(private val root: File) {
    fun run(): Boolean {
        val w = WorldAnalysis(root)
        val issues = Issues()
        val stages = (0..8).map { w.stage(it) }
        val final = stages.last()

        // Gyms in badge order.
        val gymJson = buildJsonArray {
            for (n in 1..8) {
                val ref = w.badgeRefs[n]
                if (ref == null) { issues.error("badge.missing", "badge $n", "no script awards badge $n"); continue }
                val first = stages.firstOrNull { w.activatable(ref, it.reach) }?.badges
                when {
                    first == null -> issues.error("gym.unreachable", ref.map, "badge $n (script ${ref.script}) is unreachable even with all badges")
                    first > n - 1 -> issues.error("gym.softlock", ref.map,
                        "badge $n needs $first badges to reach (field moves: ${stages[first].moves.joinToString { it.name }}), but only ${n - 1} are available")
                }
                add(buildJsonObject {
                    put("badge", n); put("map", ref.map); put("script", ref.script); put("firstReachableWithBadges", first?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        }
        val hof = w.hallOfFameRef
        if (hof == null) issues.warn("league.missing", "halloffame", "no script runs `halloffame` yet", "the League may still be in progress")
        else if (!w.activatable(hof, final.reach)) issues.error("league.unreachable", hof.map, "Hall of Fame script ${hof.script} is unreachable with 8 badges")

        // Content reachable by the end.
        for (id in w.maps.keys) if (id !in final.reach.maps) {
            if (id.startsWith("zz_stub_")) issues.warn("map.stub", id, "merge stub still present", "placeholder for a part not merged yet")
            else issues.error("map.orphan", id, "map is unreachable with all badges and field moves")
        }
        for (m in w.maps.values) {
            if (m.id !in final.reach.maps) continue
            for (it in m.items) if (listOf(0 to 0, 0 to 1, 0 to -1, -1 to 0, 1 to 0).none { (dx, dy) -> WorldAnalysis.Node(m.id, it.x + dx, it.y + dy) in final.reach.nodes })
                issues.warn("item.unreachable", "${m.id}:${it.id}", "item ${it.item} at ${it.x},${it.y} cannot be reached", "may sit behind a flag-gated tile the static model can't open")
            for (n in m.npcs) if (n.trainer != null && !w.activatable(WorldAnalysis.ScriptRef(m.id, "", "npc", n.x, n.y), final.reach))
                issues.warn("trainer.unreachable", "${m.id}:${n.id}", "trainer ${n.trainer} cannot be reached", "may be a decorative or flag-gated trainer")
        }

        // Traps: reachable tiles with no way back to a Pokémon Center or the start (checked with all field moves).
        val safeMaps = w.maps.values.filter { "pokecenter" in it.tags || it.id == w.content.index.startMap }.map { it.id }.toSet()
        val reverse = HashMap<WorldAnalysis.Node, MutableList<WorldAnalysis.Node>>()
        for ((from, tos) in final.reach.edges) for (t in tos) reverse.getOrPut(t) { ArrayList() }.add(from)
        val canReturn = HashSet<WorldAnalysis.Node>()
        val queue = ArrayDeque(final.reach.nodes.filter { it.map in safeMaps })
        canReturn.addAll(queue)
        while (queue.isNotEmpty()) for (p in reverse[queue.removeFirst()].orEmpty()) if (canReturn.add(p)) queue.add(p)
        val trapped = (final.reach.nodes - canReturn).groupBy { it.map }
        for ((map, nodes) in trapped) issues.warn("trap", map, "${nodes.size} reachable tile(s) with no walking route back to a Pokémon Center " +
            "(e.g. ${nodes.first().x},${nodes.first().y})", "fine if a script, Escape Rope, Fly or a ferry leads out; otherwise a softlock")

        // Field moves per stage and who can learn them.
        val stageJson = buildJsonArray {
            for (s in stages) add(buildJsonObject {
                put("badges", s.badges)
                put("fieldMoves", JsonArray(s.moves.sortedBy { it.badgeRequired }.map { JsonPrimitive(it.name) }))
                put("blockedFieldMoves", JsonArray(FieldMove.entries.filter { it.badgeRequired <= s.badges && it !in s.moves }.map { JsonPrimitive(it.name) }))
                put("maps", s.reach.maps.size)
                put("tiles", s.reach.nodes.size)
                put("obtainableSpecies", s.species.size)
            })
        }

        val json = issues.toJson {
            put("model", "optimistic static reachability; script gates assumed passable once their flags are set")
            put("gyms", gymJson)
            put("stages", stageJson)
        }
        writeJson(File(root, "reports/progression.json"), json)
        println("Progression: ${w.maps.size} maps; reachable at 0..8 badges: ${stages.joinToString(" ") { it.reach.maps.size.toString() }}; " +
            "${issues.errors.size} errors, ${issues.warnings.size} warnings")
        for (s in stages) println("  ${s.badges} badges: field moves ${s.moves.sortedBy { it.badgeRequired }.joinToString(",") { it.name.lowercase() }.ifEmpty { "-" }}")
        issues.errors.forEach { println("ERROR ${it.subject}: ${it.message}") }
        issues.warnings.groupBy { it.code }.forEach { (code, list) -> println("warn  $code x${list.size} (e.g. ${list.first().subject}: ${list.first().message})") }
        return issues.ok
    }
}
