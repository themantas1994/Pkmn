package pkmn.engine.save

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import pkmn.engine.data.Dex
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** On-disk envelope: version + checksum of the payload so corruption and truncation are detected. */
@Serializable
data class SaveEnvelope(
    val format: String = "pkmn-crystalline-save",
    val version: Int,
    val savedAt: Long,
    val sha256: String,
    val payload: JsonElement,
)

sealed class LoadResult {
    data class Ok(val state: GameState, val fromBackup: Boolean, val migratedFrom: Int?, val warnings: List<String>) : LoadResult()
    object NoSave : LoadResult()
    /** Neither the main save nor the backup could be read. The files are preserved (renamed with .corrupt). */
    data class Failed(val reason: String, val preservedFiles: List<String>) : LoadResult()
}

/**
 * Versioned, atomic, checksummed save storage.
 *
 * Write protocol: serialise -> write `save.json.tmp` -> fsync -> rotate current `save.json` to `save.json.bak`
 * -> rename tmp to `save.json`. A crash at any point leaves either the old or the new save intact.
 * Loading verifies the checksum, falls back to the backup, migrates old versions, and validates content
 * against the [Dex] (unknown species/moves/items are repaired or reported, never silently dropped).
 */
class SaveManager(private val dir: File, private val dex: Dex? = null, private val slot: String = "save") {
    private val main get() = File(dir, "$slot.json")
    private val backup get() = File(dir, "$slot.json.bak")
    private val tmp get() = File(dir, "$slot.json.tmp")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun exists() = main.exists() || backup.exists()

    @Synchronized
    fun save(state: GameState) {
        dir.mkdirs()
        val payload = json.encodeToJsonElement(GameState.serializer(), state)
        val payloadText = json.encodeToString(JsonElement.serializer(), payload)
        val env = SaveEnvelope(version = CURRENT_VERSION, savedAt = System.currentTimeMillis(), sha256 = sha256(payloadText), payload = payload)
        val bytes = json.encodeToString(SaveEnvelope.serializer(), env).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        // Verify what we wrote before replacing the good save.
        check(readEnvelope(tmp) != null) { "Save verification failed" }
        if (main.exists()) {
            if (backup.exists()) backup.delete()
            if (!main.renameTo(backup)) main.copyTo(backup, overwrite = true)
        }
        if (!tmp.renameTo(main)) {
            tmp.copyTo(main, overwrite = true)
            tmp.delete()
        }
    }

    @Synchronized
    fun load(): LoadResult {
        if (!main.exists() && !backup.exists()) {
            // A leftover tmp means a crash during the very first save; try it.
            if (tmp.exists()) readEnvelope(tmp)?.let { env -> return decode(env, fromBackup = true) }
            return LoadResult.NoSave
        }
        readEnvelope(main)?.let { env ->
            val r = decode(env, fromBackup = false)
            if (r is LoadResult.Ok) return r
        }
        readEnvelope(backup)?.let { env ->
            val r = decode(env, fromBackup = true)
            if (r is LoadResult.Ok) return r.copy(warnings = r.warnings + "Main save was unreadable; restored the backup.")
        }
        val preserved = ArrayList<String>()
        for (f in listOf(main, backup)) if (f.exists()) {
            val dest = File(dir, f.name + ".corrupt-" + System.currentTimeMillis())
            if (f.renameTo(dest)) preserved.add(dest.name)
        }
        return LoadResult.Failed("Save data is corrupted or from an unsupported version.", preserved)
    }

    private fun readEnvelope(f: File): SaveEnvelope? = try {
        if (!f.exists() || f.length() == 0L) null else {
            val env = json.decodeFromString(SaveEnvelope.serializer(), f.readText(Charsets.UTF_8))
            val text = json.encodeToString(JsonElement.serializer(), env.payload)
            if (env.format != "pkmn-crystalline-save" || sha256(text) != env.sha256) null else env
        }
    } catch (e: Exception) { null }

    private fun decode(env: SaveEnvelope, fromBackup: Boolean): LoadResult {
        if (env.version > CURRENT_VERSION) return LoadResult.Failed("Save is from a newer game version (v${env.version}).", emptyList())
        return try {
            var payload = env.payload.jsonObject
            var v = env.version
            while (v < CURRENT_VERSION) { payload = migrate(v, payload); v++ }
            val state = json.decodeFromJsonElement(GameState.serializer(), payload)
            val warnings = dex?.let { SaveValidator(it).repair(state) } ?: emptyList()
            LoadResult.Ok(state, fromBackup, if (env.version != CURRENT_VERSION) env.version else null, warnings)
        } catch (e: Exception) {
            LoadResult.Failed("Could not decode save: ${e.message}", emptyList())
        }
    }

    companion object {
        /**
         * 1: original release. 2: Pokédex form/shiny records, pending nickname offers and unique Pokémon ids
         * (new fields default to empty; [SaveValidator] rebuilds the form records from the Pokémon the player owns).
         */
        /*
         * 3: badge obedience. Every Pokémon records whether it is an outsider (gift/trade) next to its met level. Old
         * Pokémon become outsider = false; a Pokémon saved without a met level gets its current level as the reference.
         */
        const val CURRENT_VERSION = 3

        /** Migration from version [from] to from+1 on the raw JSON payload. */
        fun migrate(from: Int, payload: JsonObject): JsonObject = when (from) {
            // Version 0 was an internal pre-release format without options; add defaults.
            0 -> buildJsonObject {
                payload.forEach { (k, v) -> put(k, v) }
                if ("options" !in payload) put("options", Json.encodeToJsonElement(Options.serializer(), Options()))
            }
            // 2 -> 3: obedience fields on every party and PC Pokémon.
            2 -> migrateObedience(payload)
            // 1 -> 2: the new fields have safe defaults; nothing to rewrite in the raw payload.
            else -> payload
        }

        /**
         * Adds `outsider = false` to every stored Pokémon that lacks it and, if `metLevel` is missing, sets it to the
         * Pokémon's current level. Met levels that were saved are kept: they have been recorded since the first save
         * version (the level at capture, or the level at which a gift or trade was received).
         */
        private fun migrateObedience(payload: JsonObject): JsonObject {
            fun fix(mon: JsonElement): JsonElement {
                val o = mon as? JsonObject ?: return mon
                val out = LinkedHashMap<String, JsonElement>(o)
                if ("outsider" !in o) out["outsider"] = JsonPrimitive(false)
                if ("metLevel" !in o) out["metLevel"] = o["level"] ?: JsonPrimitive(1)
                return JsonObject(out)
            }
            val out = LinkedHashMap<String, JsonElement>(payload)
            (payload["party"] as? JsonArray)?.let { party -> out["party"] = JsonArray(party.map(::fix)) }
            (payload["boxes"] as? JsonArray)?.let { boxes ->
                out["boxes"] = JsonArray(boxes.map { box -> (box as? JsonArray)?.let { JsonArray(it.map(::fix)) } ?: box })
            }
            return JsonObject(out)
        }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Validates loaded content against the current game data and repairs what it safely can. */
class SaveValidator(private val dex: Dex) {
    fun repair(state: GameState): List<String> {
        val warnings = ArrayList<String>()
        val all = state.party + state.boxes.flatten().filterNotNull()
        for (mon in all) {
            if (dex.speciesOrNull(mon.speciesId) == null) {
                val base = mon.speciesId.takeWhile { it.isLetter() }
                val fallback = dex.species.keys.firstOrNull { it == base } ?: "missingno"
                warnings.add("Unknown species '${mon.speciesId}' kept as '${if (fallback == "missingno") mon.speciesId else fallback}'.")
                if (fallback != "missingno") mon.speciesId = fallback
                continue
            }
            val bad = mon.moves.filter { dex.moveOrNull(it.id) == null }
            if (bad.isNotEmpty()) {
                warnings.add("${mon.speciesId}: removed unknown moves ${bad.map { it.id }}")
                mon.moves.removeAll(bad)
                if (mon.moves.isEmpty()) mon.moves.add(pkmn.engine.mon.MoveSlot("tackle", 35))
            }
            if (mon.heldItem != null && dex.itemOrNull(mon.heldItem) == null) {
                warnings.add("${mon.speciesId}: unknown held item '${mon.heldItem}' moved to the bag as unknown data was removed.")
                mon.heldItem = null
            }
            mon.level = mon.level.coerceIn(1, 100)
            // The obedience reference level can never exceed the current level (levels only go up).
            if (mon.metLevel !in 1..mon.level) {
                warnings.add("${mon.speciesId}: met level ${mon.metLevel} out of range; set to ${mon.metLevel.coerceIn(1, mon.level)}.")
                mon.metLevel = mon.metLevel.coerceIn(1, mon.level)
            }
            val max = mon.maxHp(dex)
            if (mon.hp > max) mon.hp = max
            if (mon.hp < 0) mon.hp = max
        }
        // TMs bought before the shop fix were stored as "tm<move>" instead of "tm:<move>": convert them.
        for (key in state.bag.keys.toList()) {
            if (key.startsWith("tm") && !key.startsWith("tm:") && dex.itemOrNull(key) == null && dex.moveOrNull(key.removePrefix("tm")) != null) {
                val fixed = "tm:" + key.removePrefix("tm")
                state.bag[fixed] = ((state.bag[fixed] ?: 0) + (state.bag.remove(key) ?: 0)).coerceAtMost(999)
                warnings.add("Converted shop TM '$key' to '$fixed'.")
            }
        }
        val unknownItems = state.bag.keys.filter { dex.itemOrNull(it) == null && !it.startsWith("tm") && !it.startsWith("key:") }
        if (unknownItems.isNotEmpty()) warnings.add("Bag contains items not in this version: $unknownItems (kept).")
        warnings += repairIdentityAndDex(state)
        if (state.party.isEmpty() && all.isNotEmpty()) {
            val first = state.boxes.flatten().firstOrNull { it != null }
            if (first != null) {
                state.boxes.forEach { box -> val i = box.indexOf(first); if (i >= 0) box[i] = null }
                state.party.add(first)
                warnings.add("Party was empty; moved a Pokémon from the PC into the party.")
            }
        }
        return warnings
    }

    /**
     * Every owned Pokémon gets a unique non-zero uid (version 1 could contain zeros from hand-built Pokémon), and the
     * Pokédex is made consistent with what the player owns: an owned species or form is always caught, and anything
     * caught is also seen. Records are only ever added, never removed, so no progress is lost.
     */
    fun repairIdentityAndDex(state: GameState): List<String> {
        val warnings = ArrayList<String>()
        val owned = state.ownedPokemon()
        val used = HashSet<Long>()
        var next = (owned.maxOfOrNull { it.uid } ?: 0L).coerceAtLeast(0L) + 1
        for (m in owned) {
            if (m.uid == 0L || !used.add(m.uid)) {
                state.pendingNicknames.remove(m.uid)
                m.uid = next++
                used.add(m.uid)
            }
        }
        var added = 0
        for (m in owned) {
            val sp = dex.speciesOrNull(m.speciesId) ?: continue
            if (state.caught.add(sp.baseSpecies)) added++
            state.formsCaught.add(sp.id)
            if (m.shiny) state.shiniesCaught.add(sp.baseSpecies)
        }
        for (f in state.formsCaught.toList()) dex.speciesOrNull(f)?.let { state.caught.add(it.baseSpecies) }
        state.seen.addAll(state.caught)
        state.formsSeen.addAll(state.formsCaught)
        if (added > 0) warnings.add("Pokédex: registered $added owned species that were missing from the caught list.")
        state.pendingNicknames.retainAll { uid -> owned.any { it.uid == uid } }
        return warnings
    }
}
