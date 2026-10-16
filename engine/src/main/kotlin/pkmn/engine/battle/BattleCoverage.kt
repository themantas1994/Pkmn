package pkmn.engine.battle

import pkmn.engine.data.Dex
import pkmn.engine.data.ItemCategory

/**
 * Honest implementation coverage of moves, abilities and items in single battles.
 *
 * The classification is driven by lists committed under `resources/pkmn/battle/callback_*.txt`, generated from
 * Pokémon Showdown's server data (data/moves.ts, abilities.ts, items.ts): an id listed there has behaviour that is
 * written as code (callbacks) in Showdown, so it can only count as implemented when this engine has code for it.
 * [Status.DATA_DRIVEN] is claimed only for moves without any Showdown callback.
 */
object BattleCoverage {
    enum class Status { IMPLEMENTED_SCRIPT, DATA_DRIVEN, NO_EFFECT_NEEDED, UNIMPLEMENTED }

    data class Entry(val status: Status, val reason: String)

    fun moves(dex: Dex): Map<String, Status> = movesDetailed(dex).mapValues { it.value.status }
    fun abilities(dex: Dex): Map<String, Status> = abilitiesDetailed(dex).mapValues { it.value.status }
    fun items(dex: Dex): Map<String, Status> = itemsDetailed(dex).mapValues { it.value.status }

    /** Per-status counts, e.g. for a report: `{IMPLEMENTED_SCRIPT=..., DATA_DRIVEN=..., ...}`. */
    fun counts(map: Map<String, Status>): Map<Status, Int> = Status.entries.associateWith { s -> map.values.count { it == s } }

    // ------------------------------------------------------------------ callback lists

    private class MoveCallbacks(val move: Set<String>, val condition: Boolean)

    private fun lines(name: String): List<List<String>> {
        val stream = BattleCoverage::class.java.getResourceAsStream("/pkmn/battle/$name") ?: error("Missing resource $name")
        return stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    }

    private val moveCallbacks: Map<String, MoveCallbacks> by lazy {
        lines("callback_moves.txt").associate { p ->
            p[0] to MoveCallbacks(if (p[1] == "-") emptySet() else p[1].split(',').toSet(), p[2] != "-")
        }
    }
    private val abilityCallbacks: Set<String> by lazy { lines("callback_abilities.txt").map { it[0] }.toSet() }
    private val itemCallbacks: Map<String, Set<String>> by lazy {
        lines("callback_items.txt").associate { p -> p[0] to (if (p[1] == "-") emptySet() else p[1].split(',').toSet()) }
    }

    // ------------------------------------------------------------------ moves

    /** Moves aimed only at an ally, or whose only effect requires one: they fail or do nothing in singles. */
    val MOVES_NO_SINGLES_EFFECT: Map<String, String> = mapOf(
        "afteryou" to "fails in singles", "quash" to "fails in singles", "allyswitch" to "fails in singles",
        "followme" to "fails in singles", "ragepowder" to "fails in singles", "spotlight" to "fails in singles",
        "helpinghand" to "targets an ally; no target in singles", "aromaticmist" to "targets an ally; no target in singles",
        "dragoncheer" to "targets an ally; no target in singles", "holdhands" to "targets an ally; no target in singles",
        "coaching" to "targets an ally; no target in singles",
    )

    /** Moves whose Showdown callbacks only do something together with an ally; their singles behaviour is plain data. */
    private val MOVES_ALLY_ONLY_CALLBACKS: Map<String, String> = mapOf(
        "firepledge" to "combination with an ally's Pledge", "grasspledge" to "combination with an ally's Pledge",
        "waterpledge" to "combination with an ally's Pledge", "round" to "power doubles after an ally's Round",
        "orderup" to "boost depends on a Commander Tatsugiri ally", "pollenpuff" to "heals when aimed at an ally",
        "flameburst" to "splash damage to the target's ally",
    )

    /** Moves whose callbacks are implemented directly in [Battle]. */
    private val MOVES_CORE: Map<String, String> = mapOf(
        "thunder" to "accuracy in rain/sun", "hurricane" to "accuracy in rain/sun", "blizzard" to "accuracy in snow",
        "bleakwindstorm" to "accuracy in rain", "wildboltstorm" to "accuracy in rain", "sandsearstorm" to "accuracy in rain",
        "struggle" to "typeless, recoil", "flyingpress" to "dual-type effectiveness", "falseswipe" to "leaves 1 HP", "holdback" to "leaves 1 HP",
        "batonpass" to "copyvolatile switch", "highjumpkick" to "crash damage", "jumpkick" to "crash damage", "axekick" to "crash damage",
        "supercellslam" to "crash damage", "moonlight" to "weather-dependent healing", "morningsun" to "weather-dependent healing",
        "synthesis" to "weather-dependent healing", "shoreup" to "sand healing", "geomancy" to "charge turn",
    )

    /** Moves whose own (non-condition) callbacks are only validity checks folded into their registered condition's onStart. */
    private val MOVES_CHECKED_BY_CONDITION = setOf(
        "attract", "craftyshield", "disable", "electrify", "foresight", "gastroacid", "leechseed", "magnetrise", "miracleeye",
        "octolock", "quickguard", "wideguard", "stockpile", "substitute", "telekinesis", "yawn", "odorsleuth",
    )

    fun movesDetailed(dex: Dex): Map<String, Entry> {
        Effects.ensureInit()
        val out = LinkedHashMap<String, Entry>()
        for (m in dex.moves.values.sortedBy { it.id }) {
            val id = m.id
            val cb = moveCallbacks[id]
            out[id] = when {
                id in MOVES_NO_SINGLES_EFFECT -> Entry(Status.NO_EFFECT_NEEDED, MOVES_NO_SINGLES_EFFECT.getValue(id))
                Effects.move(id) != null -> Entry(Status.IMPLEMENTED_SCRIPT, "move script")
                cb == null -> Entry(Status.DATA_DRIVEN, "no Showdown callbacks")
                id in MOVES_CORE -> Entry(Status.IMPLEMENTED_SCRIPT, "engine: ${MOVES_CORE.getValue(id)}")
                id in MOVES_ALLY_ONLY_CALLBACKS -> Entry(Status.NO_EFFECT_NEEDED, "callbacks need an ally (${MOVES_ALLY_ONLY_CALLBACKS.getValue(id)}); singles effect is data-driven")
                cb.condition && Effects.condition(id) != null && (cb.move.isEmpty() || id in MOVES_CHECKED_BY_CONDITION) ->
                    Entry(Status.IMPLEMENTED_SCRIPT, "condition '$id'")
                else -> Entry(Status.UNIMPLEMENTED, "Showdown callbacks: ${(cb.move + if (cb.condition) setOf("condition") else emptySet()).joinToString(",")}")
            }
        }
        return out
    }

    // ------------------------------------------------------------------ abilities

    fun abilitiesDetailed(dex: Dex): Map<String, Entry> {
        Effects.ensureInit()
        val out = LinkedHashMap<String, Entry>()
        for (a in dex.abilities.values.sortedBy { it.id }) {
            val id = a.id
            out[id] = when {
                id in Abilities.NO_SINGLES_EFFECT -> Entry(Status.NO_EFFECT_NEEDED, "no effect in single battles (ally-only or outside battle)")
                Effects.ability(id) != null -> Entry(Status.IMPLEMENTED_SCRIPT, "ability effect")
                id in Abilities.CORE_HANDLED -> Entry(Status.IMPLEMENTED_SCRIPT, "engine: ${Abilities.CORE_HANDLED.getValue(id)}")
                // Abilities are never pure data: without code they do nothing.
                else -> Entry(Status.UNIMPLEMENTED, if (id in abilityCallbacks) "Showdown callbacks present" else "implemented in Showdown's simulator core")
            }
        }
        return out
    }

    // ------------------------------------------------------------------ items

    fun itemsDetailed(dex: Dex): Map<String, Entry> {
        Effects.ensureInit()
        val out = LinkedHashMap<String, Entry>()
        for (it in dex.items.values.sortedBy { it.id }) {
            val id = it.id
            val cbs = itemCallbacks[id]
            out[id] = when {
                id in Items.NO_BATTLE_EFFECT -> Entry(Status.NO_EFFECT_NEEDED, "no held-item effect in battle")
                Effects.item(id) != null -> Entry(Status.IMPLEMENTED_SCRIPT, "item effect")
                id in Items.CORE_HANDLED -> Entry(Status.IMPLEMENTED_SCRIPT, "engine: ${Items.CORE_HANDLED.getValue(id)}")
                it.megaStone.isNotEmpty() -> Entry(Status.IMPLEMENTED_SCRIPT, "engine: Mega Evolution (Gimmicks) and removal protection")
                it.category == ItemCategory.Z_CRYSTAL || it.zMove != null -> Entry(Status.IMPLEMENTED_SCRIPT, "engine: Z-Moves (Gimmicks) and removal protection")
                it.onPlate != null -> Entry(Status.IMPLEMENTED_SCRIPT, "Judgment type, Multitype forme, removal protection")
                it.onMemory != null -> Entry(Status.IMPLEMENTED_SCRIPT, "Multi-Attack type, RKS System forme, removal protection")
                it.onDrive != null -> Entry(Status.IMPLEMENTED_SCRIPT, "Techno Blast type, removal protection")
                cbs == null -> Entry(Status.NO_EFFECT_NEEDED, if (it.hasBattleData) "no Showdown callbacks (fling/Natural Gift data only)" else "not a held battle item")
                else -> Entry(Status.UNIMPLEMENTED, "Showdown callbacks: ${cbs.joinToString(",")}")
            }
        }
        return out
    }
}
