package pkmn.engine.save

import kotlinx.serialization.Serializable
import pkmn.engine.mon.Pokemon

@Serializable
data class Options(
    /** 0 slow, 1 medium, 2 fast, 3 instant. */
    var textSpeed: Int = 2,
    var battleAnimations: Boolean = true,
    /** "shift" asks to switch after a foe faints; "set" does not. */
    var battleStyle: String = "shift",
    var musicVolume: Float = 0.8f,
    var sfxVolume: Float = 0.9f,
    var haptics: Boolean = true,
    /** Touch control scale multiplier. */
    var controlScale: Float = 1.0f,
    /** Swap A/B buttons. */
    var leftHanded: Boolean = false,
    var largeText: Boolean = false,
    /** Hold B to run / auto-run toggle. */
    var autoRun: Boolean = false,
    /**
     * Party-wide Exp. Share. Off by default (Gold/Silver/Crystal pacing): with it on, the optional content leaves the
     * party 8-23 levels above the bosses (see docs/PLAYTIME.md). Options files written before this default changed,
     * which never stored the old default, keep it on through [Options.decodeLegacyAware].
     */
    var expShare: Boolean = false,
    /** Difficulty: 0 casual (AI lvl-1, no set mode), 1 normal, 2 challenge (smarter AI, level caps). */
    var difficulty: Int = 1,
    /** Use the device clock for day/night (true) or keep permanent daytime (false). */
    var realTimeClock: Boolean = true,
    var reduceMotion: Boolean = false,
    /** Battle animation speed: 0 normal, 1 fast, 2 fastest. Nonessential animations are skipped when [battleAnimations] is off. */
    var animSpeed: Int = 0,
    var showTouchControls: Boolean = true,
) {
    companion object {
        /**
         * Reads an options file. Fields missing from older files take the default they had when the file was written,
         * so changing a default never silently changes an existing player's game.
         */
        fun decodeLegacyAware(json: kotlinx.serialization.json.Json, text: String): Options {
            val o = json.decodeFromString(serializer(), text)
            val keys = (json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.keys ?: emptySet()
            if ("expShare" !in keys) o.expShare = true
            return o
        }
    }
}

@Serializable
data class QuestState(val id: String, var step: Int = 0, var completed: Boolean = false)

/**
 * Everything that is persisted in a save file. Kept as a plain serializable model; game logic lives elsewhere.
 * Schema changes must bump [SaveManager.CURRENT_VERSION] and add a migration.
 */
@Serializable
class GameState(
    var playerName: String = "GOLD",
    var playerGender: String = "m",
    var rivalName: String = "SILVER",
    var trainerId: Int = 0,
    var money: Int = 3000,
    var coins: Int = 0,
    val party: MutableList<Pokemon> = mutableListOf(),
    /** PC storage boxes (fixed size boxes, nulls are empty slots). */
    val boxes: MutableList<MutableList<Pokemon?>> = MutableList(16) { MutableList(30) { null } },
    val bag: MutableMap<String, Int> = linkedMapOf(),
    val flags: MutableSet<String> = linkedSetOf(),
    val vars: MutableMap<String, Int> = linkedMapOf(),
    val badges: MutableSet<Int> = sortedSetOf(),
    /** Pokédex: base species ids the player has seen (encountered) and caught (obtained). Caught is a subset of seen. */
    val seen: MutableSet<String> = linkedSetOf(),
    val caught: MutableSet<String> = linkedSetOf(),
    /** Pokédex form records: exact species/form ids seen and obtained (e.g. `raichualola`), and base species obtained shiny. */
    val formsSeen: MutableSet<String> = linkedSetOf(),
    val formsCaught: MutableSet<String> = linkedSetOf(),
    val shiniesCaught: MutableSet<String> = linkedSetOf(),
    /**
     * Pokémon (by uid) obtained but not yet offered a nickname. Saved with the Pokémon itself, so an interrupted
     * capture or gift resumes at the nickname prompt instead of losing or duplicating anything.
     */
    val pendingNicknames: MutableList<Long> = mutableListOf(),
    val defeatedTrainers: MutableSet<String> = linkedSetOf(),
    val quests: MutableMap<String, QuestState> = linkedMapOf(),
    var map: String = "",
    var x: Int = 0,
    var y: Int = 0,
    var facing: String = "down",
    var surfing: Boolean = false,
    var biking: Boolean = false,
    var respawnMap: String = "",
    var respawnX: Int = 0,
    var respawnY: Int = 0,
    var playTimeMs: Long = 0,
    var steps: Long = 0,
    var repelSteps: Int = 0,
    var options: Options = Options(),
    /** Towns the player has visited (fly destinations). */
    val visited: MutableSet<String> = linkedSetOf(),
    /** Seed used for the game's deterministic randomness (shiny rolls, encounters). */
    var rngState: Long = System.nanoTime(),
    var startedAt: Long = System.currentTimeMillis(),
    var hallOfFame: MutableList<List<String>> = mutableListOf(),
    /** Last-visited Pokémon Center for the "blackout" return. */
    var lastCenterMap: String = "",
    /** Where `-> @back` warps return to (set when entering a map through a warp). */
    var backMap: String = "",
    var backX: Int = 0,
    var backY: Int = 0,
    /** Key item registered to SELECT (e.g. bicycle). */
    var registeredItem: String? = null,
) {
    fun hasFlag(f: String) = f in flags
    fun setFlag(f: String) { flags.add(f) }
    fun clearFlag(f: String) { flags.remove(f) }
    fun itemCount(id: String) = bag[id] ?: 0

    fun addItem(id: String, qty: Int = 1) {
        bag[id] = ((bag[id] ?: 0) + qty).coerceAtMost(999)
    }

    fun removeItem(id: String, qty: Int = 1): Boolean {
        val have = bag[id] ?: 0
        if (have < qty) return false
        if (have == qty) bag.remove(id) else bag[id] = have - qty
        return true
    }

    /** Adds a Pokémon to the party, or to the first free PC slot. Returns "party", "box:<n>" or null if storage is full. */
    fun addPokemon(mon: Pokemon): String? {
        if (party.size < 6) { party.add(mon); return "party" }
        for ((bi, box) in boxes.withIndex()) {
            val slot = box.indexOfFirst { it == null }
            if (slot >= 0) { box[slot] = mon; return "box:$bi" }
        }
        return null
    }

    fun badgeCount() = badges.size

    /** Every Pokémon the player owns, party first, then boxes in order. */
    fun ownedPokemon(): List<Pokemon> = party + boxes.flatMap { it.filterNotNull() }

    fun findByUid(uid: Long): Pokemon? = ownedPokemon().firstOrNull { it.uid == uid }

    /** Where a Pokémon is stored: "party", "box:<n>" or null. */
    fun locationOf(uid: Long): String? {
        if (party.any { it.uid == uid }) return "party"
        for ((bi, box) in boxes.withIndex()) if (box.any { it?.uid == uid }) return "box:$bi"
        return null
    }
}
