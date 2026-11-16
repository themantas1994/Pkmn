package pkmn.engine.save

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import pkmn.engine.data.Dex
import pkmn.engine.mon.Gender
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.Nature
import pkmn.engine.mon.Pokemon
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** On-disk envelope: version + checksum of the payload so corruption and truncation are detected. */
@Serializable
data class SaveEnvelope(
    val format: String = SaveManager.FORMAT,
    val version: Int,
    val savedAt: Long,
    val sha256: String,
    val payload: JsonElement,
)

sealed class LoadResult {
    data class Ok(val state: GameState, val fromBackup: Boolean, val migratedFrom: Int?, val warnings: List<String>) : LoadResult()
    object NoSave : LoadResult()
    /** No main, temporary or backup copy could be read. The main and backup files are preserved (renamed with .corrupt). */
    data class Failed(val reason: String, val preservedFiles: List<String>) : LoadResult()
    /**
     * A copy of the save was written by a newer game version ([version] > [SaveManager.CURRENT_VERSION]). Nothing is
     * loaded (an older copy would be a downgrade), no file is renamed or changed, and [SaveManager.save] refuses to
     * write until the game is updated.
     */
    data class Unsupported(val version: Int, val reason: String) : LoadResult()
}

sealed class SaveResult {
    object Saved : SaveResult()
    /** Nothing was written: a copy from a newer game version, or one this version cannot load, would have been overwritten. */
    data class Refused(val reason: String) : SaveResult()
}

/**
 * Versioned, atomic, checksummed save storage.
 *
 * Write protocol: serialise -> write `save.json.tmp` -> fsync -> verify -> rotate `save.json` to `save.json.bak`
 * (only if it is a valid save; a damaged one is moved aside as `.corrupt-*` so it never replaces the backup) ->
 * rename tmp to `save.json` -> fsync the directory where the platform supports it.
 *
 * Recovery: a complete, checksummed tmp is always the most recent save attempt (every successful save renames it
 * away), and the main save is always newer than the backup, so loading tries tmp, then main, then backup and takes
 * the first copy that verifies and decodes. A crash at any point therefore yields the newest complete state; a
 * partial or corrupt copy is never chosen. If any copy is from a newer game version the result is
 * [LoadResult.Unsupported] and nothing is touched.
 *
 * Loading migrates old versions and validates content against the [Dex] (unknown species/moves/items are repaired
 * or reported, never silently dropped).
 */
class SaveManager(private val dir: File, private val dex: Dex? = null, private val slot: String = "save") {
    private val main get() = File(dir, "$slot.json")
    private val backup get() = File(dir, "$slot.json.bak")
    private val tmp get() = File(dir, "$slot.json.tmp")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** What a file on disk holds. A newer-version file is never classed as corrupt. */
    private sealed class Copy {
        object Missing : Copy()
        object Corrupt : Copy()
        data class Newer(val version: Int) : Copy()
        data class Valid(val env: SaveEnvelope) : Copy()
    }

    fun exists() = main.exists() || backup.exists() || inspect(tmp).let { (it is Copy.Valid && decodes(it.env)) || it is Copy.Newer }

    @Synchronized
    fun save(state: GameState): SaveResult {
        dir.mkdirs()
        val mainCopy = inspect(main)
        newestUnsupported(listOf(mainCopy, inspect(tmp), inspect(backup)))?.let { v ->
            return SaveResult.Refused("A save from a newer game version (v$v) exists; update the game to keep playing it.")
        }
        // A copy that load refuses (unknown species) is still the player's data: rotating it into .bak or replacing it
        // would destroy it or an older backup, so refuse and leave every file untouched.
        for (c in listOf(inspect(tmp), mainCopy)) {
            val unknown = unknownSpeciesIn(c)
            if (unknown.isNotEmpty()) return SaveResult.Refused(
                "The existing save has Pokémon this version does not know (${unknown.joinToString { "'$it'" }}), " +
                    "so the game was not saved. No save file was changed; use a game version that has them.")
        }
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
        check(inspect(tmp) is Copy.Valid) { "Save verification failed" }
        if (main.exists()) {
            // Only a save that would load may become the backup; a damaged main is kept aside instead.
            if (mainCopy is Copy.Valid && decodes(mainCopy.env)) replace(main, backup)
            else replace(main, File(dir, main.name + ".corrupt-" + System.currentTimeMillis()))
        }
        replace(tmp, main)
        syncDir()
        return SaveResult.Saved
    }

    @Synchronized
    fun load(): LoadResult {
        val copies = listOf(tmp, main, backup).map { it to inspect(it) }
        newestUnsupported(copies.map { it.second })?.let { v ->
            return LoadResult.Unsupported(v, "This save was made by a newer version of the game (save format v$v). Update the game to continue; the save was not changed.")
        }
        for ((f, c) in copies) {
            if (c !is Copy.Valid) continue
            val r = try { decode(c.env, fromBackup = f == backup) } catch (e: UnloadableSave) {
                // Refused, not corrupt: nothing is renamed or rewritten, and an older copy is not loaded instead.
                return LoadResult.Failed(e.message ?: "This save cannot be loaded.", emptyList())
            } as? LoadResult.Ok ?: continue
            return when (f) {
                tmp -> r.copy(warnings = r.warnings + "The last save was interrupted; it was recovered from the temporary save file.")
                backup -> r.copy(warnings = r.warnings + "Main save was missing or unreadable; restored the backup.")
                else -> r
            }
        }
        // A leftover tmp on its own is an interrupted first save, not lost progress.
        if (!main.exists() && !backup.exists()) return LoadResult.NoSave
        val preserved = ArrayList<String>()
        for (f in listOf(main, backup)) if (f.exists()) {
            val dest = File(dir, f.name + ".corrupt-" + System.currentTimeMillis())
            if (f.renameTo(dest)) preserved.add(dest.name)
        }
        return LoadResult.Failed("Save data is corrupted.", preserved)
    }

    /** The save decodes but holds data that cannot be repaired; [load] refuses it without touching any file. */
    private class UnloadableSave(message: String) : RuntimeException(message)

    private fun newestUnsupported(copies: List<Copy>): Int? = copies.filterIsInstance<Copy.Newer>().maxOfOrNull { it.version }

    /**
     * Classifies a file. The version is read before the checksum is checked so a file from a newer game (which may
     * use a different payload or checksum scheme) is reported as [Copy.Newer], never as corrupt.
     */
    private fun inspect(f: File): Copy = try {
        if (!f.exists()) Copy.Missing
        else if (f.length() == 0L) Copy.Corrupt
        else {
            val root = json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
            val format = (root["format"] as? JsonPrimitive)?.contentOrNull ?: FORMAT
            val version = (root["version"] as? JsonPrimitive)?.intOrNull
            when {
                format != FORMAT || version == null -> Copy.Corrupt
                version > CURRENT_VERSION -> Copy.Newer(version)
                else -> {
                    val env = json.decodeFromJsonElement(SaveEnvelope.serializer(), root)
                    val text = json.encodeToString(JsonElement.serializer(), env.payload)
                    if (env.payload is JsonObject && sha256(text) == env.sha256) Copy.Valid(env) else Copy.Corrupt
                }
            }
        }
    } catch (e: Exception) { Copy.Corrupt }

    /** Unknown species [load] would refuse in [copy]; empty when there is no [dex], the copy is not valid, or it does not decode. */
    private fun unknownSpeciesIn(copy: Copy): List<String> {
        val d = dex ?: return emptyList()
        if (copy !is Copy.Valid) return emptyList()
        return try { SaveValidator(d).unknownSpecies(decodeState(copy.env, ArrayList())) } catch (e: Exception) { emptyList() }
    }

    private fun decodes(env: SaveEnvelope): Boolean = try { decodeState(env, ArrayList()); true } catch (e: Exception) { false }

    private fun decodeState(env: SaveEnvelope, warnings: MutableList<String>): GameState {
        var payload = env.payload.jsonObject
        var v = env.version
        while (v < CURRENT_VERSION) { payload = migrate(v, payload); v++ }
        return json.decodeFromJsonElement(GameState.serializer(), coerceUnknownEnums(payload, warnings))
    }

    private fun decode(env: SaveEnvelope, fromBackup: Boolean): LoadResult = try {
        val warnings = ArrayList<String>()
        val state = decodeState(env, warnings)
        dex?.let { d ->
            val v = SaveValidator(d)
            val unknown = v.unknownSpecies(state)
            if (unknown.isNotEmpty()) throw UnloadableSave(
                "This save has Pokémon this version does not know (${unknown.joinToString { "'$it'" }}), " +
                    "so it was not loaded. The save file was not changed; use a game version that has them.")
            warnings += v.repair(state)
        }
        LoadResult.Ok(state, fromBackup, if (env.version != CURRENT_VERSION) env.version else null, warnings)
    } catch (e: UnloadableSave) {
        throw e
    } catch (e: Exception) {
        LoadResult.Failed("Could not decode save: ${e.message}", emptyList())
    }

    /** Atomically replaces [dst] with [src] (rename; falls back to a copy where rename cannot replace, e.g. Windows). */
    private fun replace(src: File, dst: File) {
        if (src.renameTo(dst)) return
        try {
            java.nio.file.Files.move(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (e: Exception) {
        } catch (e: LinkageError) {} // java.nio.file is missing below Android API 26
        src.copyTo(dst, overwrite = true)
        FileOutputStream(dst, true).use { it.fd.sync() }
        src.delete()
    }

    /**
     * Makes the renames durable by fsyncing the directory. Not every platform can open a directory (Windows, some
     * Android versions); there the rename is already as durable as the platform allows, so failure is ignored.
     */
    private fun syncDir() {
        try {
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
        } catch (e: LinkageError) {}
    }

    companion object {
        const val FORMAT = "pkmn-crystalline-save"

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

        /**
         * Enum values this version does not know (a newer game, a hand-edited file) are coerced to a neutral value with
         * a warning instead of failing the whole save: nature -> Hardy (no stat change), gender -> genderless, status ->
         * healthy (with its sleep counter cleared), Mint nature -> none (the original nature applies again). Every other
         * field keeps strict decoding, because guessing a level, species or count could create invalid game state.
         * Names are matched case-insensitively.
         */
        fun coerceUnknownEnums(payload: JsonObject, warnings: MutableList<String>): JsonObject {
            fun known(v: JsonElement?, names: Collection<String>): String? =
                (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { s -> names.firstOrNull { it.equals(s, ignoreCase = true) } }
            val natures = Nature.entries.map { it.name }
            val genders = Gender.entries.map { it.name }
            val statuses = MajorStatus.entries.map { it.name }
            fun fix(mon: JsonElement): JsonElement {
                val o = mon as? JsonObject ?: return mon
                val out = LinkedHashMap<String, JsonElement>(o)
                val who = (o["nickname"] as? JsonPrimitive)?.contentOrNull ?: (o["speciesId"] as? JsonPrimitive)?.contentOrNull ?: "?"
                fun coerce(key: String, names: List<String>, default: String?, label: String) {
                    val v = o[key] ?: return
                    if (v is JsonNull && default == null) return
                    val name = known(v, names)
                    if (name != null) { out[key] = JsonPrimitive(name); return }
                    warnings.add("$who: unknown $label $v replaced with ${default?.lowercase() ?: "none"}.")
                    if (default == null) out.remove(key) else out[key] = JsonPrimitive(default)
                    if (key == "status") out["sleepTurns"] = JsonPrimitive(0)
                }
                coerce("nature", natures, Nature.HARDY.name, "nature")
                coerce("gender", genders, Gender.NONE.name, "gender")
                coerce("status", statuses, null, "status")
                coerce("mintNature", natures, null, "Mint nature")
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
    private companion object {
        const val BOX_COUNT = 16
        const val BOX_SIZE = 30
        const val PARTY_SIZE = 6
    }

    private fun baseMatch(id: String): String? = id.takeWhile { it.isLetter() }.let { base -> dex.species.keys.firstOrNull { it == base } }

    /**
     * Species ids of owned Pokémon that are not in the Dex and have no base species to fall back to. [repair] cannot
     * fix these (every later `species(dex)` call on them would throw), so a save holding one must not be loaded.
     */
    fun unknownSpecies(state: GameState): List<String> =
        (state.party + state.boxes.flatten().filterNotNull())
            .filter { dex.speciesOrNull(it.speciesId) == null && baseMatch(it.speciesId) == null }
            .map { it.speciesId }.distinct()

    /**
     * Brings the party and PC to their fixed shape: 16 boxes of 30 slots, at most 6 party members, at most 4 moves per
     * Pokémon. Pokémon that no longer fit are moved to the first free PC slot (a new box if the PC is full), never dropped.
     */
    private fun repairStructure(state: GameState): List<String> {
        val warnings = ArrayList<String>()
        while (state.boxes.size < BOX_COUNT) state.boxes.add(MutableList(BOX_SIZE) { null })
        val overflow = ArrayList<Pokemon>()
        for ((i, box) in state.boxes.withIndex()) {
            if (box.size > BOX_SIZE) {
                val extra = box.subList(BOX_SIZE, box.size)
                overflow.addAll(extra.filterNotNull())
                extra.clear()
                warnings.add("Box ${i + 1} held more than ${BOX_SIZE} Pokémon slots; the extra Pokémon were moved to free PC slots.")
            }
            while (box.size < BOX_SIZE) box.add(null)
        }
        if (state.party.size > PARTY_SIZE) {
            val extra = state.party.subList(PARTY_SIZE, state.party.size)
            overflow.addAll(extra)
            extra.clear()
            warnings.add("The party held more than ${PARTY_SIZE} Pokémon; the extra Pokémon were moved to the PC.")
        }
        for (mon in overflow) {
            val box = state.boxes.firstOrNull { b -> b.any { it == null } }
                ?: MutableList<Pokemon?>(BOX_SIZE) { null }.also { state.boxes.add(it) }
            box[box.indexOf(null)] = mon
        }
        for (mon in state.party + state.boxes.flatten().filterNotNull()) {
            if (mon.moves.size > 4) {
                warnings.add("${mon.speciesId}: knew ${mon.moves.size} moves; kept the first 4.")
                mon.moves.subList(4, mon.moves.size).clear()
            }
        }
        return warnings
    }

    fun repair(state: GameState): List<String> {
        val warnings = ArrayList<String>()
        warnings += repairStructure(state)
        val all = state.party + state.boxes.flatten().filterNotNull()
        for (mon in all) {
            if (dex.speciesOrNull(mon.speciesId) == null) {
                val fallback = baseMatch(mon.speciesId)
                if (fallback != null) {
                    warnings.add("Unknown species '${mon.speciesId}' replaced with '$fallback'.")
                    mon.speciesId = fallback
                }
                continue
            }
            val bad = mon.moves.filter { dex.moveOrNull(it.id) == null }
            if (bad.isNotEmpty()) {
                warnings.add("${mon.speciesId}: removed unknown moves ${bad.map { it.id }}")
                mon.moves.removeAll(bad)
                if (mon.moves.isEmpty()) mon.moves.add(pkmn.engine.mon.MoveSlot("tackle", 35))
            }
            val held = mon.heldItem
            if (held != null && dex.itemOrNull(held) == null) {
                warnings.add("${mon.speciesId}: unknown held item '$held' moved to the bag as unknown data was removed.")
                // Written directly: addItem would refuse an id the Dex does not know, and the item must not be lost.
                state.bag[held] = (state.bag[held] ?: 0) + 1
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
