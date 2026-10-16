package pkmn.game.overworld

import pkmn.engine.world.Facing
import pkmn.engine.world.NpcDef

/** Something that walks on the tile grid (the player or an NPC). Positions are tiles; [offsetX]/[offsetY] are pixels. */
class Actor(val id: String, var sprite: String, var x: Int, var y: Int, var facing: Facing, val npc: NpcDef? = null) {
    var visible = true
    var fromX = x
    var fromY = y
    /** 0..1 progress of the current step. */
    var progress = 1f
    var tilesPerSecond = 3.75f
    var jumping = false
    var stepParity = false
    var emote: String? = null
    var emoteTimer = 0f
    /** Queued scripted steps. */
    val path = ArrayDeque<Facing>()
    var onPathDone: (() -> Unit)? = null
    var wanderTimer = 2f

    val moving get() = progress < 1f

    fun startStep(dir: Facing, toX: Int, toY: Int, speed: Float, jump: Boolean = false) {
        facing = dir
        fromX = x; fromY = y
        x = toX; y = toY
        progress = 0f
        tilesPerSecond = speed
        jumping = jump
        stepParity = !stepParity
    }

    /** Advances movement; returns true when a step finished this frame. */
    fun update(dt: Float): Boolean {
        if (emoteTimer > 0) { emoteTimer -= dt; if (emoteTimer <= 0) emote = null }
        if (!moving) return false
        val dist = if (jumping) 2f else 1f
        progress = (progress + dt * tilesPerSecond / dist).coerceAtMost(1f)
        if (progress >= 1f) { jumping = false; return true }
        return false
    }

    /** Pixel position of the sprite's top-left in map space. */
    fun pixelX(): Float = (fromX + (x - fromX) * progress) * 16f
    fun pixelY(): Float {
        val base = (fromY + (y - fromY) * progress) * 16f
        return if (jumping) base - 10f * kotlin.math.sin(progress * Math.PI).toFloat() else base
    }

    /** Animation frame: 0 standing, 1/2 alternate stepping feet. */
    fun frame(): Int = if (!moving || progress > 0.5f) 0 else if (stepParity) 1 else 2

    fun teleport(nx: Int, ny: Int) { x = nx; y = ny; fromX = nx; fromY = ny; progress = 1f; jumping = false }
}
