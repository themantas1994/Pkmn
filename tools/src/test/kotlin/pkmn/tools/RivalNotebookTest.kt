package pkmn.tools

import pkmn.engine.world.FieldMove
import pkmn.engine.world.TileRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Side quest p5b (the rival's notebook): its first page in Brindle City can be reached and read (W1-1), the florist door
 * next to it has a path to the road (W1-6), and the quest text never names or genders the player-named rival (W7-2).
 */
class RivalNotebookTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile
    private val world by lazy { WorldAnalysis(repo) }
    private val dirs = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

    @Test fun firstPageStandsOnOpenGroundReachableFromTheTownsDoors() {
        val m = world.maps.getValue("brindle_city")
        val page = m.npcs.first { it.id == "p5b_page" }
        assertTrue(!TileRules.isSolid(TileRules.behavior(m.tileAt(page.x, page.y))),
            "p5b_page at ${page.x},${page.y} stands on solid ${m.tileAt(page.x, page.y)}")
        assertTrue(m.warps.isNotEmpty())
        for (door in m.warps) {
            val fromDoor = object : ReachWorld by world { override val start = WorldAnalysis.Node(m.id, door.x, door.y) }
            val res = StrictReach(fromDoor).reach(emptySet<FieldMove>(), false)
            assertTrue(dirs.any { (dx, dy) -> res.canStand(m.id, page.x + dx, page.y + dy) },
                "p5b_page at ${page.x},${page.y} has no neighbour reachable from the ${door.toMap} door at ${door.x},${door.y}")
        }
    }

    @Test fun floristDoorHasAPathToTheRoad() {
        val m = world.maps.getValue("brindle_city")
        val door = m.warps.first { it.toMap == "p4a_brindle_florist" }
        fun isPath(x: Int, y: Int) = m.inBounds(x, y) && m.tileAt(x, y) == "path"
        val start = door.x to door.y + 1
        assertTrue(isPath(start.first, start.second), "no path in front of the florist door at ${door.x},${door.y}")
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            for ((dx, dy) in dirs) {
                val n = x + dx to y + dy
                if (isPath(n.first, n.second) && seen.add(n)) queue.add(n)
            }
        }
        // The main east-west road is the full-width path at row 11.
        assertTrue(seen.any { it.second == 11 }, "the path from the florist door does not reach the road (reached ${seen.size} tiles)")
    }

    @Test fun questTextNeverNamesOrGendersTheRival() {
        val quest = world.content.quests.getValue("p5b_kai_notebook")
        val questText = listOf(quest.name, quest.description) + quest.steps
        val scriptText = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(File(repo, "assets/scripts/p5b_kai.pks").readText())
            .map { it.groupValues[1] }.toList()
        assertTrue(scriptText.isNotEmpty())
        val kai = Regex("\\bKai\\b")
        val male = Regex("\\b(he|his|him|himself)\\b", RegexOption.IGNORE_CASE)
        for (t in questText + scriptText) {
            assertTrue(!kai.containsMatchIn(t), "literal rival name in: $t")
            assertTrue(!male.containsMatchIn(t), "gendered rival in: $t")
        }
        // The starter is a gift from the Professor (p1_lab), not caught.
        assertTrue(scriptText.none { "caught my starter" in it }, "apology says the rival caught their starter")
    }
}
