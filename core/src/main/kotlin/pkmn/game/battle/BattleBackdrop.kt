package pkmn.game.battle

import com.badlogic.gdx.graphics.Color

/** Battle backdrop choice (GBA spec section 5): which `gfx/battle/bg_<id>.png` an environment draws, and its night tint. */
object BattleBackdrop {
    /** The eleven generated backdrops (art/battle.py ENVS). */
    val IDS = listOf("grass", "tall_grass", "forest", "cave", "water", "snow", "sand", "indoor", "building", "gym", "league")

    /** Environments that are never dimmed at night. */
    private val INDOOR = setOf("cave", "indoor", "building", "gym", "league")

    fun idFor(environment: String): String = when {
        environment in IDS -> environment
        environment == "fishing" -> "water"
        environment.startsWith("gym_") -> "gym"
        else -> "grass" // plain and anything unknown
    }

    fun dimmedAtNight(id: String): Boolean = id !in INDOOR

    /** Same factors as art/palettes.py NIGHT_MUL, so the swap matches the documented night palettes. */
    fun nightTint(): Color = Color(0.72f, 0.76f, 0.95f, 1f)
}
