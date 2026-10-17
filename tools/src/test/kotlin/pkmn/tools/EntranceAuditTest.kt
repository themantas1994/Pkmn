package pkmn.tools

import pkmn.engine.world.FlagTile
import pkmn.engine.world.MapData
import pkmn.engine.world.Warp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntranceAuditTest {
    private val repo = File(System.getProperty("pkmn.assets")).parentFile

    private fun grid(id: String, rows: List<String>, environment: String = "plain", indoor: Boolean = false,
                     zoneClass: String? = null, music: String = "route_early", warps: List<Warp> = emptyList(), flagTiles: List<FlagTile> = emptyList()): MapData {
        val palette = ArrayList<String>()
        val ground = ArrayList<Int>()
        for (r in rows) for (ch in r) {
            val t = legend.getValue(ch)
            if (t !in palette) palette.add(t)
            ground.add(palette.indexOf(t))
        }
        return MapData(id, id, rows[0].length, rows.size, "world", palette, ground, music = music, environment = environment, indoor = indoor,
            warps = warps, flagTiles = flagTiles, zoneClass = zoneClass)
    }

    private val legend = mapOf('.' to "grass", 'D' to "door", 'F' to "forest_entrance", 'C' to "cave_entrance", '#' to "tree", 'w' to "ruins_wall", '~' to "water", 'R' to "rug_mat", 'f' to "floor_wood")

    private fun conflicts(vararg maps: MapData) = EntranceAudit(repo, write = false).audit(maps.associateBy { it.id }).conflicts

    @Test
    fun houseDoorIntoAForestIsAConflict() {
        val route = grid("route", listOf("...D."), warps = listOf(Warp(3, 0, "wood", 2, 0)))
        val wood = grid("wood", listOf("..F.."), music = "forest", warps = listOf(Warp(2, 0, "route", 3, 0)))
        val bad = conflicts(route, wood)
        assertEquals(1, bad.size)
        assertTrue(bad.single().problems.single().startsWith("incompatible-entrance"), bad.single().problems.toString())
        // Fixing the tile on the route side makes the pair clean.
        val fixedRoute = grid("route", listOf("...F."), warps = listOf(Warp(3, 0, "wood", 2, 0)))
        assertEquals(emptyList(), conflicts(fixedRoute, wood))
    }

    @Test
    fun warpOnAWallIsAConflictUnlessAFlagOpensIt() {
        val a = grid("a", listOf("..w.."), environment = "cave", warps = listOf(Warp(2, 0, "b", 0, 0)))
        val b = grid("b", listOf("C...."), environment = "cave", warps = listOf(Warp(0, 0, "a", 1, 0)))
        assertTrue(conflicts(a, b).any { p -> p.problems.any { it.startsWith("not-warp-tile") } })
        val gated = a.copy(flagTiles = listOf(FlagTile("open", 2, 0, "stairs_down")))
        assertEquals(emptyList(), conflicts(gated, b))
        assertEquals(1, EntranceAudit(repo, write = false).audit(mapOf("a" to gated, "b" to b)).rows.count { it.gatedTile == "stairs_down" })
    }

    @Test
    fun missingReverseAndWaterLandingAreConflicts() {
        val a = grid("a", listOf("..C.."), environment = "cave", warps = listOf(Warp(2, 0, "b", 1, 0)))
        val b = grid("b", listOf(".~..."), environment = "cave")
        val problems = conflicts(a, b).flatMap { it.problems }
        assertTrue(problems.any { it.startsWith("no-reverse") }, problems.toString())
        assertTrue(problems.any { it.startsWith("landing-not-walkable") }, problems.toString())
    }

    @Test
    fun unapproachableWarpTileIsAConflict() {
        val a = grid("a", listOf("###", "#C#", "###"), environment = "cave", warps = listOf(Warp(1, 1, "b", 0, 0)))
        val b = grid("b", listOf("...."), environment = "cave", warps = listOf(Warp(3, 0, "a", 1, 1)))
        assertTrue(conflicts(a, b).any { c -> c.problems.any { it.startsWith("unapproachable") } })
    }

    @Test
    fun theWorldHasNoEntranceConflicts() {
        val (res, _) = EntranceAudit(repo, write = false).auditWorld()
        assertEquals(emptyList(), res.conflicts.map { "${it.map} ${it.x},${it.y} ${it.tile} -> ${it.destMap}: ${it.problems}" })
        assertTrue(res.rows.size > 400, "every warp is audited")
    }
}
