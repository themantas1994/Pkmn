package pkmn.engine.world

import pkmn.engine.campaign.GameContext

/** Result of trying to take one step. */
sealed class StepResult {
    object Blocked : StepResult()
    /** Bumped into something interactable or impassable (plays the bump sound). */
    data class Bump(val reason: String) : StepResult()
    data class Walk(val x: Int, val y: Int) : StepResult()
    /** Ledge jump: moves two tiles in one hop. */
    data class Jump(val x: Int, val y: Int) : StepResult()
    data class Warp(val warp: pkmn.engine.world.Warp) : StepResult()
    /** Crossing a seamless map edge connection. */
    data class Transfer(val map: String, val x: Int, val y: Int) : StepResult()
    /** Asks the player to start surfing (stepping toward water from land). */
    object OfferSurf : StepResult()
    /** Leaves water onto land. */
    data class Land(val x: Int, val y: Int) : StepResult()
}

/** A dynamic object occupying a tile (NPC, item ball, removable obstacle). */
data class Occupant(val id: String, val x: Int, val y: Int, val kind: String)

/**
 * Pure movement and interaction rules for the overworld. The renderer animates whatever these rules decide.
 */
class OverworldRules(private val ctx: GameContext) {

    fun behavior(map: MapData, x: Int, y: Int): TileBehavior = TileRules.behavior(map.tileAt(x, y))

    fun warpAt(map: MapData, x: Int, y: Int): Warp? = map.warps.firstOrNull { it.x == x && it.y == y }

    fun obstacleActive(o: ObstacleDef) = !ctx.state.hasFlag(o.flag)

    /**
     * Resolves a step from (x, y) facing [dir]. [occupied] reports NPCs/objects on a tile.
     */
    fun step(map: MapData, x: Int, y: Int, dir: Facing, surfing: Boolean, occupied: (Int, Int) -> Occupant?): StepResult {
        val nx = x + dir.dx
        val ny = y + dir.dy
        if (!map.inBounds(nx, ny)) {
            // Door mats / stairs at the map edge exit when walking out.
            warpAt(map, x, y)?.let { return StepResult.Warp(it) }
            return connectionTarget(map, nx, ny, dir)?.let { StepResult.Transfer(it.first, it.second, it.third) } ?: StepResult.Bump("edge")
        }
        occupied(nx, ny)?.let { return StepResult.Bump(it.kind) }
        map.obstacles.firstOrNull { it.x == nx && it.y == ny && obstacleActive(it) }?.let { return StepResult.Bump(it.kind) }
        val b = behavior(map, nx, ny)
        val here = behavior(map, x, y)
        // Ledges: only passable in their direction, landing two tiles away.
        when (b) {
            TileBehavior.LEDGE_S, TileBehavior.LEDGE_E, TileBehavior.LEDGE_W -> {
                val ok = (b == TileBehavior.LEDGE_S && dir == Facing.DOWN) || (b == TileBehavior.LEDGE_E && dir == Facing.RIGHT) || (b == TileBehavior.LEDGE_W && dir == Facing.LEFT)
                if (!ok || surfing) return StepResult.Bump("ledge")
                val lx = nx + dir.dx; val ly = ny + dir.dy
                if (!map.inBounds(lx, ly) || TileRules.isSolid(behavior(map, lx, ly)) || occupied(lx, ly) != null) return StepResult.Bump("ledge")
                return StepResult.Jump(lx, ly)
            }
            else -> {}
        }
        if (b == TileBehavior.WATER) {
            return if (surfing) StepResult.Walk(nx, ny) else StepResult.OfferSurf
        }
        if (TileRules.isSolid(b)) {
            // Walking "into" a warp from a warp tile (stairs against a wall) still triggers the warp.
            if (here == TileBehavior.WARP) warpAt(map, x, y)?.let { return StepResult.Warp(it) }
            return StepResult.Bump(b.name.lowercase())
        }
        if (surfing) return StepResult.Land(nx, ny)
        warpAt(map, nx, ny)?.let { return StepResult.Warp(it) }
        return StepResult.Walk(nx, ny)
    }

    /** Map and position reached by stepping off the edge at (nx, ny), using seamless connections. */
    fun connectionTarget(map: MapData, nx: Int, ny: Int, dir: Facing): Triple<String, Int, Int>? {
        val dirName = when (dir) { Facing.UP -> "north"; Facing.DOWN -> "south"; Facing.LEFT -> "west"; Facing.RIGHT -> "east" }
        val c = map.connections.firstOrNull { it.direction == dirName } ?: return null
        val target = ctx.content.mapOrNull(c.map) ?: return null
        return when (dir) {
            Facing.UP -> Triple(target.id, nx - c.offset, target.height - 1)
            Facing.DOWN -> Triple(target.id, nx - c.offset, 0)
            Facing.LEFT -> Triple(target.id, target.width - 1, ny - c.offset)
            Facing.RIGHT -> Triple(target.id, 0, ny - c.offset)
        }.takeIf { (_, tx, ty) -> target.inBounds(tx, ty) }
    }

    /** Trainers that can see the player at (px, py): straight line of sight within range, no obstacles between. */
    fun spottingTrainer(map: MapData, px: Int, py: Int, npcs: List<Pair<NpcDef, Pair<Int, Int>>>, facingOf: (NpcDef) -> Facing,
                        blocked: (Int, Int) -> Boolean): NpcDef? {
        for ((npc, pos) in npcs) {
            val trainer = npc.trainer ?: continue
            if (trainer in ctx.state.defeatedTrainers || npc.sight <= 0) continue
            val f = facingOf(npc)
            var x = pos.first; var y = pos.second
            for (i in 1..npc.sight) {
                x += f.dx; y += f.dy
                if (x == px && y == py) return npc
                if (!map.inBounds(x, y) || TileRules.isSolid(behavior(map, x, y)) || blocked(x, y)) break
            }
        }
        return null
    }

    fun encounterMethod(map: MapData, x: Int, y: Int, surfing: Boolean): String? = when {
        surfing -> "surf"
        behavior(map, x, y) == TileBehavior.TALL_GRASS -> "grass"
        map.environment == "cave" && behavior(map, x, y) in setOf(TileBehavior.CAVE_FLOOR, TileBehavior.FLOOR) -> "cave"
        else -> null
    }

    /** NPC visibility from show/hide flags. */
    fun npcVisible(map: MapData, n: NpcDef): Boolean {
        if (n.hideIf != null && ctx.state.hasFlag(n.hideIf)) return false
        if (n.showIf != null && !ctx.state.hasFlag(n.showIf)) return false
        if (ctx.state.hasFlag(hiddenFlag(map.id, n.id))) return false
        return true
    }

    companion object {
        /** Flag persisted by the script commands `hide`/`show`. */
        fun hiddenFlag(mapId: String, npcId: String) = "hidden:$mapId:$npcId"
    }

    fun itemVisible(map: MapData, item: ItemPickup) = !ctx.state.hasFlag("item:${item.id}")
}
