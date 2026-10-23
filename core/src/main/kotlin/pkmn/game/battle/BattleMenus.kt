package pkmn.game.battle

import pkmn.engine.battle.Gimmick
import pkmn.engine.battle.Request
import pkmn.game.input.Button

/** Pure layout and navigation rules for the battle action menu (two columns; GIMMICK sits alone on the third row). */
object BattleMenus {
    fun actions(hasGimmick: Boolean): List<String> = listOf("FIGHT", "PKMN", "PACK", "RUN") + if (hasGimmick) listOf("GIMMICK") else emptyList()

    /** Gimmicks the current request offers, in a stable order. Primal Reversion is automatic and never listed. */
    fun gimmicks(req: Request): List<Gimmick> = (req as? Request.Move)?.gimmicks?.sortedBy { it.ordinal } ?: emptyList()

    /** Keeps a list cursor inside [count] entries; the move cursor outlives a switch to a Pokémon with fewer moves. */
    fun clampCursor(cursor: Int, count: Int): Int = cursor.coerceIn(0, maxOf(count - 1, 0))

    /** New cursor after pressing [dir]; stays inside the [count] entries laid out in two columns. */
    fun navigate(cursor: Int, dir: Button, count: Int): Int {
        val rows = (count + 1) / 2
        val col = cursor % 2; val row = cursor / 2
        return when (dir) {
            Button.LEFT, Button.RIGHT -> if ((cursor xor 1) < count) cursor xor 1 else cursor
            Button.UP, Button.DOWN -> {
                val r = (row + if (dir == Button.DOWN) 1 else rows - 1) % rows
                minOf(r * 2 + col, count - 1)
            }
            else -> cursor
        }
    }
}
