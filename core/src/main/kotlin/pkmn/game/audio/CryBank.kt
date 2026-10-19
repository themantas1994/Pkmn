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

/** What [CryBank.playOrDefer] / [CryBank.poll] decided for a cry request. */
enum class CryOutcome {
    /** A real cry started. */
    PLAYED,
    /** The sound is still loading (Android SoundPool decodes asynchronously); [CryBank.poll] retries for a short time. */
    DEFERRED,
    /** No cry will play (no file, failed load, or the load did not finish in time): use the generic blip. */
    FALLBACK,
}

/**
 * Plays species cries: one at a time, loaded voices cached (small LRU) and released by [dispose]. A species without a
 * cry file returns false from [play] so the caller can use the generic blip; the misses are counted ([missCount],
 * [missedSpecies]) and logged once per species.
 *
 * Replacement rule: a new cry cuts the previous one only once the new one is known to have started. A request whose
 * sound is not loaded yet never silences a cry that is still playing.
 * Nothing here blocks: loading is whatever [load] does (LibGDX decodes on Android asynchronously) and an unready sound
 * is retried by [poll], called once per frame by the audio layer.
 */
class CryBank(
    private val resolver: CryResolver,
    private val load: (File) -> CryVoice?,
    private val log: (String) -> Unit = {},
    private val maxCached: Int = 16,
    /** After this many failed loads of one file it is treated as missing (no retry storm on a corrupt file). */
    private val maxLoadFailures: Int = 2,
) {
    private val cache = LinkedHashMap<File, CryVoice>(16, 0.75f, true)
    private val loadFailures = HashMap<File, Int>()
    private var current: CryVoice? = null
    private val misses = LinkedHashMap<String, Int>()

    private class Pending(val speciesId: String, val voice: CryVoice, val volume: Float, val deadlineMs: Long)
    private var pending: Pending? = null
    val hasPending: Boolean get() = pending != null
    var playCount = 0
        private set
    val missCount: Int get() = misses.values.sum()
    val missedSpecies: Set<String> get() = misses.keys

    /** Returns true if a real cry was started. [volume] is already scaled by the caller (SFX volume, mute). */
    fun play(speciesId: String, volume: Float): Boolean {
        pending = null
        val voice = voiceFor(speciesId)
        if (voice == null) { noteMiss(speciesId); return false }
        if (!start(voice, volume)) {
            log("cry for '$speciesId' not ready to play: using the generic cry")
            return false
        }
        playCount++
        return true
    }

    /**
     * Like [play] but a sound that is still loading is retried by [poll] until [nowMs] + [waitMs] instead of failing at
     * once, so cries outside battles (scripts, events) that had no prefetch lead time are not lost on Android.
     */
    fun playOrDefer(speciesId: String, volume: Float, nowMs: Long, waitMs: Long = 600): CryOutcome {
        pending = null
        val voice = voiceFor(speciesId)
        if (voice == null) { noteMiss(speciesId); return CryOutcome.FALLBACK }
        if (start(voice, volume)) { playCount++; return CryOutcome.PLAYED }
        pending = Pending(speciesId, voice, volume, nowMs + waitMs)
        return CryOutcome.DEFERRED
    }

    /** Retries the deferred cry. Returns null while nothing changed, otherwise the final outcome of that request. */
    fun poll(nowMs: Long): CryOutcome? {
        val p = pending ?: return null
        if (start(p.voice, p.volume)) { pending = null; playCount++; return CryOutcome.PLAYED }
        if (nowMs < p.deadlineMs) return null
        pending = null
        log("cry for '${p.speciesId}' did not load in time: using the generic cry")
        return CryOutcome.FALLBACK
    }

    /** Starts [voice]; the cry that was playing is stopped only after the new one reports a started stream. */
    private fun start(voice: CryVoice, volume: Float): Boolean {
        val prev = current
        if (prev === voice) voice.stop() // restart the same cry (its old stream must not overlap the new one)
        if (volume <= 0f) { if (prev !== voice) prev?.stop(); current = voice; return true }
        val started = runCatching { voice.play(volume.coerceIn(0f, 1f)) }.getOrElse { log("cry play failed: $it"); false }
        if (!started) return false
        if (prev !== voice) prev?.stop()
        current = voice
        return true
    }

    private fun noteMiss(speciesId: String) {
        if (misses.merge(speciesId, 1, Int::plus) == 1) log("no cry file for '$speciesId': using the generic cry")
    }

    /**
     * Loads the cry ahead of time. Android decodes sounds asynchronously, so a cry loaded in the same call that plays
     * it may be silent; battles prefetch their Pokémon on entry, scripts and events when they start.
     */
    fun prefetch(speciesId: String) { voiceFor(speciesId) }

    private fun voiceFor(speciesId: String): CryVoice? {
        val f = resolver.resolve(speciesId) ?: return null
        cache[f]?.let { return it }
        if ((loadFailures[f] ?: 0) >= maxLoadFailures) return null
        val v = runCatching { load(f) }.onFailure { log("cry load failed for $speciesId: $it") }.getOrNull()
        if (v == null) { loadFailures.merge(f, 1, Int::plus); return null }
        cache[f] = v
        while (cache.size > maxCached) {
            val eldest = cache.entries.firstOrNull { it.value !== current && it.value !== v && it.value !== pending?.voice } ?: break
            cache.remove(eldest.key); eldest.value.dispose()
        }
        return v
    }

    fun stop() { pending = null; current?.stop(); current = null }

    fun dispose() {
        pending = null
        current = null
        cache.values.forEach { runCatching { it.dispose() } }
        cache.clear()
    }
}
