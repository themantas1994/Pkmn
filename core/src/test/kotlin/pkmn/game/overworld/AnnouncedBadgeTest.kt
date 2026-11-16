package pkmn.game.overworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnouncedBadgeTest {
    private val names = { n: Int -> "Badge$n" }

    @Test
    fun badgeAnnouncementYieldsItsNumber() {
        assertEquals(3, announcedBadge("RED received the Badge3!", names))
        assertEquals(8, announcedBadge("RED received the Badge8!", names))
    }

    @Test
    fun otherTextYieldsNull() {
        assertNull(announcedBadge("RED received the Potion!", names))
        assertNull(announcedBadge("Take this Badge3.", names))
    }
}
