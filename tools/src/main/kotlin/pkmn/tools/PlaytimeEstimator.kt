package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.data.GrowthRate
import pkmn.engine.world.MapData
import pkmn.engine.world.TileBehavior
import pkmn.engine.world.TileRules
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Estimates campaign playtime from content with an explicit, documented model (it is not a measurement):
 * the critical path between milestones (start → gym 1 → … → gym 8 → Hall of Fame) is found on the map graph, and
 * each map on it costs traversal + trainer battles + expected wild battles + dialogue. A party experience simulation
 * adds grinding when the party would arrive at a gym under-levelled. "Full" additionally counts every newly
 * reachable map per stage (side areas, interiors, optional trainers). Writes reports/playtime.json.
 */
class PlaytimeEstimator(private val root: File) {
    /** Model constants (seconds). Tuned for a GSC-paced game with fast text. */
    private object K {
        const val SECONDS_PER_TILE = 0.27        // walking speed 3.75 tiles/s
        const val CROSSING_FACTOR = 1.3          // a crossing is (width + height) × this many steps
        const val EXPLORED_TILE_SHARE = 0.25     // plus this share of the walkable tiles (detours, items, dead ends)
        const val TRAINER_BASE = 25.0            // spotting walk, intro/outro text, transitions, prize
        const val TRAINER_PER_MON = 40.0         // ~2 turns of ~15 s plus faint/exp/level-up text
        const val BOSS_BASE = 45.0               // leaders, rivals, admins, E4
        const val BOSS_PER_MON = 65.0            // more turns, healing items, gimmicks
        const val WILD_FOUGHT = 32.0
        const val WILD_FLED = 9.0
        const val WILD_FIGHT_SHARE = 0.6
        const val SAY = 3.0                      // one dialogue command (1–2 text boxes)
        const val INTERIOR_VISIT = 35.0          // entering a building on the path (center, mart, house)
        const val GYM_PUZZLE = 150.0
        const val TOWN_SERVICES = 90.0           // healing, shopping, PC, party management per town
        const val OVERHEAD = 0.10                // menus, saving, retries after a loss
        const val TRAINERS_FOUGHT_ON_PATH = 0.85 // sight trainers on the critical path the player actually battles
        const val TRAINERS_FOUGHT_OPTIONAL = 0.6
        const val NPC_TALK_SHARE = 0.55          // share of optional NPC dialogue a typical player reads
        const val LEVEL_MARGIN = 2               // party may be this many levels below the leader's ace
    }

    private lateinit var w: WorldAnalysis

    data class MapCost(val map: String, val traversal: Double, val trainers: Double, val wild: Double, val dialogue: Double, val extra: Double,
                       val trainerIds: List<String>, val wildBattles: Double) {
        val total get() = traversal + trainers + wild + dialogue + extra
    }

    fun run(): Boolean {
        w = WorldAnalysis(root)
        val stages = (0..8).map { w.stage(it) }
        val milestones = buildList {
            add("start" to w.content.index.startMap)
            for (n in 1..8) w.badgeRefs[n]?.let { add("gym $n" to it.map) } ?: break
            if (size == 9) w.hallOfFameRef?.let { add("hall of fame" to it.map) }
        }

        var party = PartySim()
        val legs = ArrayList<JsonObject>()
        var criticalTotal = 0.0
        var fullTotal = 0.0
        val counted = HashSet<String>()
        val seenOptional = HashSet<String>()
        val paths = (1 until milestones.size).map { i -> w.mapPath(stages[i - 1].reach, milestones[i - 1].second, milestones[i].second) }
        val legOfPathMap = HashMap<String, Int>()
        paths.forEachIndexed { leg, p -> p?.forEach { legOfPathMap.putIfAbsent(it, leg + 1) } }
        val optionalLeg = attachOptional(stages.last().reach, legOfPathMap)
        for (i in 1 until milestones.size) {
            val (fromLabel, from) = milestones[i - 1]
            val (toLabel, to) = milestones[i]
            val stage = stages[i - 1]
            val path = paths[i - 1] ?: run {
                println("warn  no route $fromLabel ($from) -> $toLabel ($to) with ${i - 1} badges; leg skipped")
                continue
            }
            // Maps passed again on a later leg cost traversal only.
            val costs = path.map { m -> mapCost(m, critical = true, revisit = m in counted) }
            counted.addAll(path)
            val optional = optionalLeg.filter { (m, leg) -> leg == i && m !in counted && m !in seenOptional && m in stage.reach.maps }.keys
            seenOptional.addAll(optional)
            val optionalCosts = optional.map { mapCost(it, critical = false, revisit = false) }

            // Experience from the leg; grind if the party arrives under-levelled for the leader (or the Champion).
            for (c in costs) for (t in c.trainerIds) party.defeatTrainer(t)
            party.defeatWild(costs.sumOf { it.wildBattles } * K.WILD_FIGHT_SHARE, stage.reach.maps)
            val bossLevel = bossAceLevel(to, toLabel)
            val grind = party.grindTo(bossLevel - K.LEVEL_MARGIN, stage.reach.maps)
            val legCritical = costs.sumOf { it.total } + grind.seconds
            val legFull = legCritical + optionalCosts.sumOf { it.total }
            criticalTotal += legCritical
            fullTotal += legFull
            legs.add(buildJsonObject {
                put("leg", "$fromLabel -> $toLabel"); put("badges", i - 1)
                put("path", JsonArray(path.map { JsonPrimitive(it) }))
                put("criticalMinutes", (legCritical / 60).roundToInt())
                put("fullMinutes", (legFull / 60).roundToInt())
                put("trainersOnPath", costs.sumOf { it.trainerIds.size })
                put("expectedWildEncounters", costs.sumOf { it.wildBattles }.roundToInt())
                put("partyLevelOnArrival", party.level); put("bossAceLevel", bossLevel)
                put("grindMinutes", (grind.seconds / 60).roundToInt()); put("grindBattles", grind.battles)
                put("optionalMaps", optional.size)
            })
            party = party.grown(i)
        }
        criticalTotal *= 1 + K.OVERHEAD
        fullTotal *= 1 + K.OVERHEAD
        val reachedLeague = milestones.lastOrNull()?.first == "hall of fame"
        val postGameMaps = stages.last().reach.maps.filter { it !in counted && it !in seenOptional && !it.startsWith("zz_stub_") }
        val postGame = postGameMaps.sumOf { mapCost(it, critical = false, revisit = false).total } * (1 + K.OVERHEAD)

        val json = buildJsonObject {
            put("model", "content-derived estimate, not a measurement; constants listed under 'constants'")
            put("complete", reachedLeague)
            put("milestones", JsonArray(milestones.map { JsonPrimitive("${it.first}: ${it.second}") }))
            put("criticalPathHours", (criticalTotal / 360).roundToInt() / 10.0)
            put("withOptionalContentHours", (fullTotal / 360).roundToInt() / 10.0)
            put("postGameHours", (postGame / 360).roundToInt() / 10.0)
            put("legs", JsonArray(legs))
            put("constants", buildJsonObject {
                K::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name != "INSTANCE" }
                    .forEach { f -> f.isAccessible = true; put(f.name, JsonPrimitive(f.get(null) as Number)) }
            })
        }
        writeJson(File(root, "reports/playtime.json"), json)
        println("Playtime estimate (${if (reachedLeague) "start to Hall of Fame" else "partial: up to ${milestones.lastOrNull()?.first}"}):")
        for (l in legs) println("  %-26s %4d min critical, %4d min with optional (party L%d vs ace L%d, grind %d min)".format(
            l["leg"]!!.jsonPrimitive.content, l["criticalMinutes"]!!.jsonPrimitive.int, l["fullMinutes"]!!.jsonPrimitive.int,
            l["partyLevelOnArrival"]!!.jsonPrimitive.int, l["bossAceLevel"]!!.jsonPrimitive.int, l["grindMinutes"]!!.jsonPrimitive.int))
        println("  critical path %.1f h, with optional content %.1f h, post-game %.1f h".format(criticalTotal / 3600, fullTotal / 3600, postGame / 3600))
        return true
    }

    /**
     * Assigns every map off the critical path to the leg of the nearest critical-path map (BFS on the map graph from
     * all path maps at once), so side areas are counted where a player would naturally visit them.
     */
    private fun attachOptional(reach: WorldAnalysis.Reach, legOfPathMap: Map<String, Int>): Map<String, Int> {
        val leg = HashMap(legOfPathMap)
        val undirected = HashMap<String, MutableSet<String>>()
        for ((a, bs) in reach.mapEdges) for (b in bs) { undirected.getOrPut(a) { HashSet() }.add(b); undirected.getOrPut(b) { HashSet() }.add(a) }
        val queue = ArrayDeque(legOfPathMap.entries.sortedBy { it.value }.map { it.key })
        while (queue.isNotEmpty()) {
            val m = queue.removeFirst()
            for (t in undirected[m].orEmpty()) if (t !in leg && !t.startsWith("zz_stub_")) { leg[t] = leg.getValue(m); queue.add(t) }
        }
        return leg.filterKeys { it !in legOfPathMap }
    }

    private fun isBoss(trainerId: String): Boolean {
        val t = w.content.trainers[trainerId] ?: return false
        return listOf("leader", "rival", "admin", "boss", "elite", "champion").any { it in t.trainerClass || it in trainerId }
    }

    private fun battleSeconds(trainerId: String): Double {
        val n = w.content.trainers[trainerId]?.team?.size ?: 3
        return if (isBoss(trainerId)) K.BOSS_BASE + K.BOSS_PER_MON * n else K.TRAINER_BASE + K.TRAINER_PER_MON * n
    }

    private fun bossAceLevel(map: String, label: String): Int {
        val ids = w.scriptedTrainers(map) + w.sightTrainers(map)
        val leader = ids.filter { isBoss(it) }.ifEmpty { ids }
        val ace = leader.flatMap { w.content.trainers[it]?.team.orEmpty() }.maxOfOrNull { it.level }
        val badge = Regex("gym (\\d)").find(label)?.groupValues?.get(1)?.toInt()
        return ace ?: badge?.let { b -> w.content.info.badges.firstOrNull { it.number == b }?.levelCap } ?: 50
    }

    private fun mapCost(id: String, critical: Boolean, revisit: Boolean): MapCost {
        val m = w.maps.getValue(id)
        val steps = (m.width + m.height) * K.CROSSING_FACTOR + (if (revisit) 0.0 else K.EXPLORED_TILE_SHARE * walkableTiles(m))
        val traversal = steps * K.SECONDS_PER_TILE
        if (revisit) return MapCost(id, traversal, 0.0, 0.0, 0.0, 0.0, emptyList(), 0.0)
        val share = if (critical) K.TRAINERS_FOUGHT_ON_PATH else K.TRAINERS_FOUGHT_OPTIONAL
        val sight = w.sightTrainers(id)
        val scripted = w.scriptedTrainers(id)
        val fought = scripted + sight.take((sight.size * share).roundToInt())
        val trainers = fought.sumOf { battleSeconds(it) }
        val encounters = expectedEncounters(m, steps)
        val wild = encounters * (K.WILD_FIGHT_SHARE * K.WILD_FOUGHT + (1 - K.WILD_FIGHT_SHARE) * K.WILD_FLED)
        val story = w.commandsOnMap(id, setOf("trigger", "enter")).count { it.second.name == "say" }
        val talk = w.commandsOnMap(id, setOf("npc", "sign")).count { it.second.name == "say" }
        val dialogue = K.SAY * (story + talk * K.NPC_TALK_SHARE * (if (critical) 1.0 else 0.8))
        var extra = 0.0
        if (m.indoor) extra += K.INTERIOR_VISIT
        if ("gym" in m.tags) extra += K.GYM_PUZZLE
        if ("town" in m.tags) extra += K.TOWN_SERVICES
        return MapCost(id, traversal, trainers, wild, dialogue, extra, fought, encounters)
    }

    private fun walkableTiles(m: MapData): Int {
        var n = 0
        for (y in 0 until m.height) for (x in 0 until m.width) if (!TileRules.isSolid(TileRules.behavior(m.tileAt(x, y)))) n++
        return n
    }

    /** Expected wild encounters for [steps] steps, proportional to the share of encounter tiles on the map. */
    private fun expectedEncounters(m: MapData, steps: Double): Double {
        val table = m.encounters?.let { w.content.encounters[it] } ?: return 0.0
        var walkable = 0; var grass = 0; var water = 0
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val b = TileRules.behavior(m.tileAt(x, y))
            if (b == TileBehavior.WATER) water++ else if (!TileRules.isSolid(b)) { walkable++; if (b == TileBehavior.TALL_GRASS) grass++ }
        }
        val total = (walkable + water).coerceAtLeast(1).toDouble()
        val cave = "cave" in table.methods && (m.environment == "cave" || m.dark)
        val landShare = if (cave) walkable / total else grass / total
        val landRate = (table.rates[if (cave) "cave" else "grass"] ?: if (cave) 12 else 25) / 256.0
        val surfRate = if ("surf" in table.methods) (table.rates["surf"] ?: 10) / 256.0 else 0.0
        return steps * (landShare * landRate * (if (cave || "grass" in table.methods) 1.0 else 0.0) + water / total * surfRate)
    }

    // ------------------------------------------------------------------ party experience model

    data class Grind(val seconds: Double, val battles: Int)

    /** Average party level under the modern Exp. Share (lead earns full, the others half). */
    private inner class PartySim(var exp: Double = GrowthRate.MEDIUM_SLOW.expForLevel(5).toDouble(), val size: Int = 2) {
        val level: Int get() = (1..100).last { GrowthRate.MEDIUM_SLOW.expForLevel(it) <= exp }
        private val shareFactor get() = (1 + (size - 1) * 0.5) / size

        fun grown(badges: Int) = PartySim(exp, (2 + (badges + 1) / 2).coerceAtMost(6))

        private fun yieldOf(species: String, foeLevel: Int, trainer: Boolean): Double {
            val base = w.dex.speciesOrNull(species)?.baseExp?.toDouble() ?: 64.0
            val l = foeLevel.toDouble(); val lp = level.toDouble()
            val e = base * l / 5 * ((2 * l + 10) / (l + lp + 10)).pow(2.5) + 1
            return e * (if (trainer) 1.5 else 1.0) * shareFactor
        }

        fun defeatTrainer(id: String) {
            for (mon in w.content.trainers[id]?.team.orEmpty()) exp += yieldOf(mon.species, mon.level, true)
        }

        /** Average wild opponent (species, level) across the encounter tables of reachable maps. */
        private fun wildPool(maps: Set<String>): List<Pair<String, Int>> = maps.mapNotNull { w.maps[it]?.encounters?.let { e -> w.content.encounters[e] } }
            .flatMap { t -> t.methods.filterKeys { it in setOf("grass", "cave", "surf") }.values.flatMap { it.values.flatten() } }
            .map { it.species to (it.minLevel + it.maxLevel) / 2 }

        fun defeatWild(count: Double, maps: Set<String>) {
            val pool = wildPool(maps).ifEmpty { return }
            val avg = pool.map { (s, l) -> yieldOf(s, l, false) }.average()
            exp += avg * count
        }

        /** Battles against the strongest wild Pokémon available until the party reaches [target]. */
        fun grindTo(target: Int, maps: Set<String>): Grind {
            if (level >= target) return Grind(0.0, 0)
            val pool = wildPool(maps).sortedByDescending { it.second }.take(8).ifEmpty { return Grind(0.0, 0) }
            var battles = 0
            while (level < target && battles < 2000) {
                exp += pool.map { (s, l) -> yieldOf(s, l, false) }.average()
                battles++
            }
            return Grind(battles * (K.WILD_FOUGHT + 6.0), battles)
        }
    }
}
