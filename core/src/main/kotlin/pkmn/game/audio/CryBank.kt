package pkmn.game.audio

import java.io.File

/** One loaded cry that can be started, stopped and released. Backed by a LibGDX `Sound` in the game, fakes in tests. */
interface CryVoice {
    /** Returns false if no stream started (e.g. the platform has not finished loading the sound yet). */
    fun play(volume: Float): Boolean
    fun stop()
    fun dispose()
}

/**
 * Finds the cry file of a species. Official cries are never bundled in public builds: they only exist in the local
 * override directory (`assets-local/audio/cries` on desktop, `imported/audio/cries` on Android, which a personal APK
 * unpacks there). Lookup: `<speciesId>.ogg`, then the same for the base species (forms share their base's cry).
 */
class CryResolver(private val dirs: List<File>, private val baseOf: (String) -> String? = { null }) {
    /** `.ogg` first; a few upstream cries are MP3 and keep `.mp3` (both are decoded by LibGDX and Android). */
    fun resolve(speciesId: String): File? {
        var id: String? = speciesId
        val seen = HashSet<String>()
        while (id != null && seen.add(id)) {
            for (d in dirs) for (ext in EXTENSIONS) File(d, "$id.$ext").takeIf { it.isFile }?.let { return it }
            id = baseOf(id)
        }
        return null
    }
}

private val EXTENSIONS = listOf("ogg", "mp3")

/**
 * Plays species cries: one at a time (a new cry cuts the previous one), loaded voices cached (small LRU) and released
 * by [dispose]. A species without a cry file returns false from [play] so the caller can use the generic blip; the
 * misses are counted ([missCount], [missedSpecies]) and logged once per species.
 */
class CryBank(
    private val resolver: CryResolver,
    private val load: (File) -> CryVoice?,
    private val log: (String) -> Unit = {},
    private val maxCached: Int = 16,
) {
    private val cache = LinkedHashMap<File, CryVoice>(16, 0.75f, true)
    private var current: CryVoice? = null
    private val misses = LinkedHashMap<String, Int>()
    var playCount = 0
        private set
    val missCount: Int get() = misses.values.sum()
    val missedSpecies: Set<String> get() = misses.keys

    /** Returns true if a real cry was started. [volume] is already scaled by the caller (SFX volume, mute). */
    fun play(speciesId: String, volume: Float): Boolean {
        val voice = voiceFor(speciesId)
        if (voice == null) {
            if (misses.merge(speciesId, 1, Int::plus) == 1) log("no cry file for '$speciesId': using the generic cry")
            return false
        }
        if (current !== voice) current?.stop() else voice.stop()
        current = voice
        if (volume > 0f && !voice.play(volume.coerceIn(0f, 1f))) {
            log("cry for '$speciesId' not ready to play: using the generic cry")
            return false
        }
        playCount++
        return true
    }

    /**
     * Loads the cry ahead of time. Android decodes sounds asynchronously, so a cry loaded in the same call that plays
     * it is silent; battles prefetch their Pokémon on entry so the first cry already plays.
     */
    fun prefetch(speciesId: String) { voiceFor(speciesId) }

    private fun voiceFor(speciesId: String): CryVoice? {
        val f = resolver.resolve(speciesId) ?: return null
        cache[f]?.let { return it }
        val v = runCatching { load(f) }.onFailure { log("cry load failed for $speciesId: $it") }.getOrNull() ?: return null
        cache[f] = v
        while (cache.size > maxCached) {
            val eldest = cache.entries.first { it.value !== current && it.value !== v }
            cache.remove(eldest.key); eldest.value.dispose()
        }
        return v
    }

    fun stop() { current?.stop(); current = null }

    fun dispose() {
        current = null
        cache.values.forEach { runCatching { it.dispose() } }
        cache.clear()
    }
}
