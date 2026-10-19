package pkmn.tools

import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.Dex
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.save.GameState
import pkmn.engine.world.FieldMove
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FM-2: the static analysis uses the engine's field-move rule (God Bidoof), not "an obtainable learner exists". */
class FieldMoveStageTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile

    @Test
    fun unlockedMovesMatchTheEngineRule() {
        val ctx = GameContext(Dex.load(DirectoryContentSource(File(repo, "assets"))), WorldContent(DirectoryContentSource(File(repo, "assets"))), GameState())
        for (n in 0..8) {
            ctx.state.badges.clear(); ctx.state.badges.addAll(1..n)
            assertEquals(FieldMove.entries.filter { ctx.canUseFieldMove(it) }.toSet(), WorldAnalysis.fieldMovesAt(n), "badges=$n")
        }
    }

    @Test
    fun stageDoesNotNeedAnyEligibleLearner() {
        // Copy the world with every wild encounter table emptied: no wild Pokémon can learn anything, yet badge-unlocked moves still apply.
        val tmp = Files.createTempDirectory("noleaners").toFile()
        try {
            File(repo, "assets").copyRecursively(File(tmp, "assets")) { _, _ -> kotlin.io.OnErrorAction.SKIP }
            File(tmp, "assets/world/encounters.json").writeText("[]")
            val w = WorldAnalysis(tmp)
            for (n in 0..8) assertEquals(WorldAnalysis.fieldMovesAt(n), w.stage(n).moves, "badges=$n")
            assertTrue(FieldMove.WATERFALL in w.stage(7).moves && FieldMove.CUT in w.stage(1).moves)
            assertTrue(w.stage(8).reach.maps.size >= w.stage(0).reach.maps.size)
        } finally { tmp.deleteRecursively() }
    }
}
