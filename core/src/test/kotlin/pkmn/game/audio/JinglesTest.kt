package pkmn.game.audio

import pkmn.game.Jingles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JinglesTest {
    private val musicDir = File(File(System.getProperty("pkmn.assets") ?: "assets").absoluteFile, "audio/music")

    @Test
    fun everyRequestedJingleResolvesToAShippedFile() {
        val missing = Jingles.files.filterValues { !File(musicDir, "$it.mml").exists() }
        assertTrue(missing.isEmpty(), "jingle ids with no file in assets/audio/music: $missing")
    }

    @Test
    fun levelUpCatchBadgeAndItemUseTheirJingles() {
        assertEquals("jingle_levelup", Jingles.files["level_up"])
        assertEquals("jingle_caught", Jingles.files["caught"])
        assertEquals("jingle_badge", Jingles.files["badge"])
        assertEquals("jingle_item", Jingles.files["item_get"])
    }
}
