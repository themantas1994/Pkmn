package pkmn.game.overworld

import pkmn.engine.world.Facing
import pkmn.engine.world.OverworldRules
import pkmn.engine.world.StepResult
import pkmn.game.PkmnGame
import pkmn.game.input.Button

/**
 * Walks the player to a target tile on the current map using BFS over [OverworldRules] (automation and tests).
 * Drives input like a player would, so encounters, triggers and warps behave normally.
 */
class Autopilot(private val game: PkmnGame) {
    private var target: Pair<Int, Int>? = null
    private var path = ArrayDeque<Facing>()
    private var holding: Button? = null
    var stuckFrames = 0
        private set

    val active get() = target != null

    fun goto(x: Int, y: Int) { target = x to y; path.clear(); stuckFrames = 0 }
    fun cancel() { target = null; release() }

    private fun release() { holding?.let { game.input.inject(it, false) }; holding = null }

    private fun overworld(): OverworldScreen? = game.screens().lastOrNull() as? OverworldScreen

    /** Call once per frame; returns true while still travelling. */
    fun tick(): Boolean {
        val t = target ?: return false
        val ow = overworld()
        if (ow == null) { release(); stuckFrames++; return stuckFrames < 3600 }
        val p = ow.player
        // Release as soon as a step starts so a held button never carries the player past the planned tile.
        if (p.moving) { release(); return true }
        if (p.x == t.first && p.y == t.second) { release(); target = null; return false }
        if (stuckFrames > 1800) { game.platform.log("autopilot: giving up at ${p.x},${p.y} -> $t"); release(); target = null; return false }
        if (path.isEmpty()) path = findPath(ow, t.first, t.second) ?: run { release(); target = null; return false }
        val dir = path.removeFirst()
        val b = when (dir) { Facing.UP -> Button.UP; Facing.DOWN -> Button.DOWN; Facing.LEFT -> Button.LEFT; Facing.RIGHT -> Button.RIGHT }
        if (holding != b) { release(); game.input.inject(b, true); holding = b }
        // Re-plan every step so NPC movement and map changes are respected.
        path.clear()
        stuckFrames++
        return true
    }

    private fun findPath(ow: OverworldScreen, tx: Int, ty: Int): ArrayDeque<Facing>? {
        val map = ow.map
        val rules = OverworldRules(ow.ctx)
        val start = ow.player.x to ow.player.y
        val prev = HashMap<Pair<Int, Int>, Pair<Pair<Int, Int>, Facing>>()
        val queue = ArrayDeque<Pair<Int, Int>>().apply { add(start) }
        val seen = HashSet<Pair<Int, Int>>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.first == tx && cur.second == ty) break
            for (d in Facing.entries) {
                val r = rules.step(map, cur.first, cur.second, d, ow.ctx.state.surfing) { _, _ -> null }
                val next = when (r) {
                    is StepResult.Walk -> r.x to r.y
                    is StepResult.Jump -> r.x to r.y
                    is StepResult.Warp -> if (r.warp.x == tx && r.warp.y == ty) tx to ty else null
                    is StepResult.Land -> r.x to r.y
                    else -> null
                } ?: continue
                if (next in seen) continue
                seen.add(next); prev[next] = cur to d; queue.add(next)
            }
        }
        if ((tx to ty) !in prev) return null
        val out = ArrayDeque<Facing>()
        var c = tx to ty
        while (c != start) { val (p, d) = prev.getValue(c); out.addFirst(d); c = p }
        return out
    }
}
