package pkmn.game.ui

import pkmn.game.screens.listWindowTop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C5-7: the quest list draws 7 rows but the cursor walks every entry; the cursor row must always be on screen. */
class QuestListWindowTest {
    @Test fun cursorRowIsAlwaysVisibleForTenEntries() {
        for (cursor in 0 until 10) {
            val top = listWindowTop(cursor, 10, 7)
            assertTrue(cursor in top until top + 7, "cursor $cursor not in window starting at $top")
        }
    }

    @Test fun windowStaysInsideTheList() {
        for (size in 0..20) for (cursor in 0 until maxOf(size, 1)) {
            val top = listWindowTop(cursor, size, 7)
            assertTrue(top >= 0 && top + 7 >= minOf(size, 7) && top <= maxOf(0, size - 7), "size $size cursor $cursor top $top")
        }
    }

    @Test fun shortListsDoNotScroll() = assertEquals(0, listWindowTop(3, 5, 7))
}
