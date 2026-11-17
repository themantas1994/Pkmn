package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.world.FieldMove
import java.io.File

/**
 * Checks that the campaign can be completed in badge order without softlocks:
 * * every gym leader is reachable with the badges earned before it (field moves need only the badge: God Bidoof
 *   performs them, no party Pokémon is required),
 * * the Hall of Fame is reachable with eight badges,
 * * every map, item and trainer is reachable by the end of the game,
 * * no sequence of Strength-boulder pushes permanently loses a route or reward (see [BoulderPuzzles]),
 * * no reachable tile is a trap (a place from which no Pokémon Center or the start can be reached again).
 * Writes reports/progression.json.
 */
/** What the progression checks need from a world; [WorldAnalysis] is the real one, tests build small ones. */
interface ProgressWorld : ReachWorld {
    val badgeRefs: Map<Int, WorldAnalysis.ScriptRef>
    val hallOfFameRef: WorldAnalysis.ScriptRef?
}

class ProgressionChecker(private val root: File) {
    /** The strict reach per badge count (0..8) and everything the checks found. */
    class Analysis(val issues: Issues, val stages: List<Stage>) {
        class Stage(val badges: Int, val moves: Set<FieldMove>, val reach: WorldAnalysis.Reach, val closedGates: Set<String>)
    }

    companion object {
        /**
         * The checks, on the strict model ([StrictReach] with ice slides, solid guard NPCs, flag-dependent tiles and turn-back
         * triggers that stay closed until the story allows them).
         */
        fun analyze(w: ProgressWorld): Analysis {
            val issues = Issues()
            val sr = StrictReach(w)
            val stages = (0..8).map { b ->
                val moves = WorldAnalysis.fieldMovesAt(b)
                val r = sr.reach(moves, false, b)
                Analysis.Stage(b, moves, r.fullReach(), r.closedGates)
            }
            val final = stages.last()

            // Gyms in badge order.
            for (n in 1..8) {
                val ref = w.badgeRefs[n]
                if (ref == null) { issues.error("badge.missing", "badge $n", "no script awards badge $n"); continue }
                val first = stages.firstOrNull { w.activatable(ref, it.reach) }?.badges
                when {
                    first == null -> issues.error("gym.unreachable", ref.map, "badge $n (script ${ref.script}) is unreachable even with all badges")
                    first > n - 1 -> issues.error("gym.softlock", ref.map,
                        "badge $n needs $first badges to reach (field moves: ${stages[first].moves.joinToString { it.name }}), but only ${n - 1} are available")
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

            // Traps: reachable tiles with no way back to a Pokémon Center or the start (all field moves, ice slides, script warps).
            val safeMaps = w.maps.values.filter { "pokecenter" in it.tags || it.id == w.start.map }.map { it.id }.toSet()
            val reverse = HashMap<WorldAnalysis.Node, MutableList<WorldAnalysis.Node>>()
            for ((from, tos) in final.reach.edges) for (t in tos) reverse.getOrPut(t) { ArrayList() }.add(from)
            val canReturn = HashSet<WorldAnalysis.Node>()
            val queue = ArrayDeque(final.reach.nodes.filter { it.map in safeMaps })
            canReturn.addAll(queue)
            while (queue.isNotEmpty()) for (p in reverse[queue.removeFirst()].orEmpty()) if (canReturn.add(p)) queue.add(p)
            val trapped = (final.reach.nodes - canReturn).groupBy { it.map }
            for ((map, nodes) in trapped) issues.error("trap", map, "${nodes.size} reachable tile(s) with no walking route back to a Pokémon Center " +
                "(${nodes.take(4).joinToString(" ") { "${it.x},${it.y}" }}); add an exit, or a script that warps out")

            // Strength boulders persist where they are pushed, so every boulder map is searched exhaustively for a push that loses a route or reward.
            for (r in BoulderPuzzles(w.maps).analyzeAll()) for (sl in r.softlocks)
                issues.error("boulder.softlock", r.map, "boulder pushes can permanently lose ${sl.lost.joinToString()} (e.g. ${sl.pushes.take(6).joinToString("; ")}); tag the map boulder_reset or reshape it")

            // Gate seals: while a turn-back trigger is closed and the player can walk up to it, opening it must reach something new.
            // If not, the gate guards nothing: there is a way around it (or it leads nowhere).
            for (g in sr.gates) {
                val leak = stages.firstOrNull { s ->
                    g.id in s.closedGates && g.tiles.any { (x, y) -> listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0).any { (dx, dy) -> WorldAnalysis.Node(g.map, x + dx, y + dy) in s.reach.nodes } } &&
                        sr.reach(s.moves, false, s.badges, setOf(g.id)).nodes.count { n -> n.map != g.map || (n.x to n.y) !in g.tiles } <= s.reach.nodes.size
                }
                if (leak != null) issues.error("gate.leaky", "${g.map}:${g.script}",
                    "gate ${g.script} closed at ${leak.badges} badge(s) but opening it reaches nothing new: the tiles beyond it can be reached another way (or it guards nothing)")
            }
            return Analysis(issues, stages)
        }
    }

    fun run(): Boolean {
        val w = WorldAnalysis(root)
        val a = analyze(w)
        val issues = a.issues
        val stages = a.stages

        val gymJson = buildJsonArray {
            for (n in 1..8) {
                val ref = w.badgeRefs[n] ?: continue
                val first = stages.firstOrNull { w.activatable(ref, it.reach) }?.badges
                add(buildJsonObject {
                    put("badge", n); put("map", ref.map); put("script", ref.script); put("firstReachableWithBadges", first?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        }

        // Field moves per stage and who can learn them.
        val stageJson = buildJsonArray {
            for (s in stages) add(buildJsonObject {
                put("badges", s.badges)
                put("fieldMoves", JsonArray(s.moves.sortedBy { it.badgeRequired }.map { JsonPrimitive(it.name) }))
                put("blockedFieldMoves", JsonArray(FieldMove.entries.filter { it.badgeRequired <= s.badges && it !in s.moves }.map { JsonPrimitive(it.name) }))
                put("maps", s.reach.maps.size)
                put("tiles", s.reach.nodes.size)
                put("closedGates", s.closedGates.size)
                put("obtainableSpecies", w.withEvolutions(s.reach.maps.flatMap { w.speciesOn(it) }.toSet()).size)
            })
        }

        val json = issues.toJson {
            put("model", "strict static reachability: ice slides, solid guard NPCs, flag tiles, script conditions on badges and flags, turn-back triggers closed until their script lets the player through")
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
