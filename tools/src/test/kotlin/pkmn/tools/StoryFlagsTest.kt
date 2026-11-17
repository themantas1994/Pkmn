package pkmn.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** L1 (W7-3, W7-4, W7-6, W5-3, W5-4): main quests end, Mom reacts to 8 badges and the Hall of Fame, the story scripts agree. */
class StoryFlagsTest {
    private val world = File(System.getProperty("pkmn.assets")).parentFile.resolve("world")
    private val scripts = world.resolve("scripts").listFiles { f -> f.extension == "pks" }!!.sortedBy { it.name }
    private val all = scripts.joinToString("\n") { it.readText() }
    private fun script(file: String, name: String): String {
        val text = world.resolve("scripts/$file").readText()
        val start = text.indexOf("script $name\n")
        assertTrue(start >= 0, "script $name missing in $file")
        return text.substring(start).substringBefore("\nend\n")
    }

    @Test fun everyStartedMainQuestHasADone() {
        val mainIds = (listOf(world.resolve("quests.txt")) + world.resolve("quests").listFiles()!!)
            .flatMap { it.readLines() }
            .mapNotNull { Regex("""^quest (\w+) ".*" main$""").find(it)?.groupValues?.get(1) }
        val started = mainIds.filter { Regex("""quest $it start""").containsMatchIn(all) }
        assertTrue(started.isNotEmpty())
        for (id in started) assertTrue(Regex("""quest $id done""").containsMatchIn(all), "main quest $id is started but never completed")
    }

    @Test fun momKnowsEightBadgesAndTheHallOfFame() {
        val mom = script("p1_hometown.pks", "house_mom")
        assertTrue("badges >= 8" in mom, "house_mom has no 8-badge line")
        assertTrue("flag hall_of_fame" in mom, "house_mom has no Hall of Fame line")
        assertTrue("flag hall_of_fame" in script("p1_hometown.pks", "rival_mom"), "rival_mom has no Hall of Fame line")
    }

    @Test fun ashfallRivalBattleCanBeLost() {
        val s = script("p3a_ashfall.pks", "p3a_ash_rival_battle")
        assertTrue(Regex("""battle p3a_rival_ashfall_\w+ canlose""").findAll(s).count() == 3, "all three Ashfall rival battles need canlose")
        assertTrue("result win" in s && "else" in s.substringAfter("result win"), "Ashfall rival needs win and lose lines")
    }

    @Test fun victoryRoadRivalRespectsTheCapstone() {
        assertTrue("p5b_kai_done" in script("p3b_road.pks", "p3b_vr_rival"), "p3b_vr_rival ignores the p5b capstone")
    }

    @Test fun dragonspireGatekeeperNamesMissingGyms() {
        assertTrue("badge 5" in script("p3b_dragonspire.pks", "p3b_gym_door"), "p3b_gym_door does not name missing gyms")
    }
}
