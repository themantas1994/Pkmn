package pkmn.tools

import kotlinx.serialization.json.*
import pkmn.engine.battle.Effects
import pkmn.engine.data.Dex

/**
 * Battle-mechanics coverage reports.
 *
 * `pkmn.engine.battle.BattleCoverage` (an `object` with `moves(dex)`, `abilities(dex)`, `items(dex)` returning
 * `Map<String, Status>`) is provided by the battle engine. It is looked up reflectively so these tools compile and
 * run whether or not it exists; when absent the reports say "unavailable" instead of guessing.
 */
object BattleCoverageReport {
    const val CLASS_NAME = "pkmn.engine.battle.BattleCoverage"
    val KINDS = listOf("moves", "abilities", "items")

    /** kind -> (id -> status name), or null when BattleCoverage is not present / failed. */
    fun load(dex: Dex, className: String = CLASS_NAME): Result<Map<String, Map<String, String>>> = runCatching {
        val cls = Class.forName(className)
        val instance = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()
        KINDS.associateWith { kind ->
            val m = cls.methods.first { it.name == kind && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(Dex::class.java) }
            val raw = m.invoke(instance, dex) as Map<*, *>
            raw.entries.associate { (k, v) -> k.toString() to statusName(v) }.toSortedMap()
        }
    }

    private fun statusName(v: Any?): String = when (v) {
        null -> "null"
        is Enum<*> -> v.name
        else -> v.toString()
    }

    fun coverageJson(kind: String, statuses: Map<String, String>, total: Int) = buildJsonObject {
        put("available", true)
        put("source", CLASS_NAME)
        put("kind", kind)
        put("totalInDex", total)
        put("reported", statuses.size)
        put("byStatus", countBy(statuses.values))
        putJsonObject("idsByStatus") {
            statuses.entries.groupBy({ it.value }, { it.key }).toSortedMap().forEach { (s, ids) -> put(s, strArray(ids.sorted())) }
        }
    }

    fun unavailableJson(kind: String, reason: String) = buildJsonObject {
        put("available", false)
        put("source", CLASS_NAME)
        put("kind", kind)
        put("reason", reason)
    }

    /** Ids registered in [Effects] versus the totals in the Dex. */
    fun registeredJson(dex: Dex): JsonObject {
        fun section(registered: Set<String>, universe: Set<String>?, universeLabel: String, known: Set<String>? = universe) = buildJsonObject {
            put("registered", registered.size)
            if (universe != null) {
                put("total", universe.size)
                put("totalDefinition", universeLabel)
                put("registeredInDex", registered.count { it in universe })
                put("percent", if (universe.isEmpty()) 0.0 else Math.round(1000.0 * registered.count { it in universe } / universe.size) / 10.0)
                put("unknownIds", strArray(registered.filter { it !in known.orEmpty() }.sorted()))
                (registered.filter { it !in universe && it in known.orEmpty() }).takeIf { it.isNotEmpty() }?.let { put("registeredOutsideTotal", strArray(it.sorted())) }
            } else put("totalDefinition", universeLabel)
            put("ids", strArray(registered.sorted()))
        }
        val battleItems = dex.items.values.filter { it.hasBattleData }.map { it.id }.toSet()
        return buildJsonObject {
            put("source", "pkmn.engine.battle.Effects")
            put("note", "Registered = has a dedicated Effect/MoveScript implementation. Moves without a script may still be fully handled by the data-driven move pipeline.")
            put("abilities", section(Effects.implementedAbilities().toSet(), dex.abilities.keys, "abilities in abilities.json"))
            put("items", section(Effects.implementedItems().toSet(), battleItems, "items with battle-simulator data (hasBattleData)", dex.items.keys))
            put("moveScripts", section(Effects.implementedMoveScripts().toSet(), dex.moves.keys, "moves in moves.json"))
            put("conditions", section(Effects.implementedConditions().toSet(), null, "no fixed universe (statuses, volatiles, weather, terrain, side conditions)"))
        }
    }
}
