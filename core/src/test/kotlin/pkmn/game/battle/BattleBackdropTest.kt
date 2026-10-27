package pkmn.game.battle

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattleBackdropTest {
    private fun asset(name: String) = File("../assets/gfx/battle/$name")

    @Test
    fun environmentsMapToBackdrops() {
        assertEquals("grass", BattleBackdrop.idFor("plain"))
        assertEquals("grass", BattleBackdrop.idFor("grass"))
        assertEquals("water", BattleBackdrop.idFor("fishing"))
        assertEquals("gym", BattleBackdrop.idFor("gym_tide"))
        assertEquals("cave", BattleBackdrop.idFor("cave"))
        assertEquals("grass", BattleBackdrop.idFor("something_new"))
    }

    @Test
    fun indoorBackdropsAreNotDimmedAtNight() {
        assertTrue(BattleBackdrop.dimmedAtNight("grass"))
        assertFalse(BattleBackdrop.dimmedAtNight("cave"))
        assertFalse(BattleBackdrop.dimmedAtNight("indoor"))
    }

    @Test
    fun everyBackdropAndPlatformIsBundledAtTheSpecSize() {
        assertEquals(11, BattleBackdrop.IDS.size)
        for (id in BattleBackdrop.IDS) {
            for ((name, w, h) in listOf(Triple("bg_$id.png", 256, 160), Triple("platform_foe_$id.png", 112, 24), Triple("platform_player_$id.png", 128, 24))) {
                val img = ImageIO.read(asset(name))
                assertEquals(w to h, img.width to img.height, name)
            }
        }
    }
}
