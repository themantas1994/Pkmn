package pkmn.game.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.AudioDevice
import java.util.concurrent.ConcurrentLinkedQueue

/** Destination for rendered PCM. Implementations may block in [write] to pace the audio thread. */
interface PcmOutput {
    fun write(samples: ShortArray, offset: Int, count: Int)
    fun pause() {}
    fun resume() {}
    fun dispose() {}
}

/** [PcmOutput] backed by a LibGDX [AudioDevice] (blocking writes on desktop and Android). */
class GdxPcmOutput(private val device: AudioDevice) : PcmOutput {
    override fun write(samples: ShortArray, offset: Int, count: Int) = device.writeSamples(samples, offset, count)
    override fun pause() = device.pause()
    override fun resume() = device.resume()
    override fun dispose() = device.dispose()

    companion object {
        fun create(sampleRate: Int, stereo: Boolean): GdxPcmOutput =
            GdxPcmOutput(Gdx.audio.newAudioDevice(sampleRate, !stereo))
    }
}

/**
 * Game audio: one music track (with fades and jingle interruption) plus up to [SFX_SLOTS]
 * simultaneous sound effects, synthesised on a dedicated daemon thread and streamed to a
 * [PcmOutput]. All public methods are non-blocking and thread-safe; they enqueue commands that the
 * audio thread applies at the next block boundary.
 *
 * @param loader returns the MML text for a song id (e.g. `Gdx.files.internal("audio/music/$id.mml").readString()`),
 *               or null if missing. Exceptions are caught and logged.
 * @param output where to send PCM; by default a LibGDX AudioDevice. If creating it fails the engine
 *               runs silently (paced by the wall clock).
 * @param startThread false for tests/offline rendering: drive the engine with [renderBlock].
 */
class AudioEngine(
    private val loader: (String) -> String?,
    val sampleRate: Int = 22050,
    val stereo: Boolean = true,
    output: PcmOutput? = null,
    private val log: (String) -> Unit = ::defaultLog,
    startThread: Boolean = true,
    val blockFrames: Int = 512,
) {
    private sealed class Cmd {
        class PlayMusic(val id: String, val fadeMs: Int) : Cmd()
        class StopMusic(val fadeMs: Int) : Cmd()
        class PlaySfx(val sfx: Sfx) : Cmd()
        class PlayJingle(val id: String) : Cmd()
        object StopSfx : Cmd()
    }

    private val queue = ConcurrentLinkedQueue<Cmd>()
    private val lock = Object()
    private val channels = if (stereo) 2 else 1

    @Volatile private var musicVolume = 1f
    @Volatile private var sfxVolume = 1f
    /** When true everything keeps running but the output is silent. */
    @Volatile var muted = false
    @Volatile private var paused = false
    @Volatile private var running = true

    private val out: PcmOutput? = output ?: try {
        GdxPcmOutput.create(sampleRate, stereo)
    } catch (t: Throwable) {
        log("AudioEngine: no audio device (${t.message}); running silently"); null
    }

    // ---- audio-thread state ------------------------------------------------------------------
    private val music = SongPlayer(ChipSynth(sampleRate, stereo))
    private val jingle = SongPlayer(ChipSynth(sampleRate, stereo))
    private val sfx = Array(SFX_SLOTS) { SongPlayer(ChipSynth(sampleRate, stereo)) }
    private val sfxStarted = LongArray(SFX_SLOTS)
    private val sfxIds = arrayOfNulls<Sfx>(SFX_SLOTS)
    private var sfxCounter = 0L
    private val songCache = HashMap<String, Song?>()

    private var currentMusic: String? = null
    private var pendingMusic: String? = null
    private var pendingStop = false
    private var fadeGain = 1f
    private var fadeStep = 0f // gain change per frame (negative = fading out)
    private var jingleActive = false

    private val mix = FloatArray(blockFrames * channels)
    private val musicBuf = FloatArray(blockFrames * channels)
    private val pcm = ShortArray(blockFrames * channels)

    /** Id of the music currently playing (or about to after a fade), as seen by the audio thread. */
    val currentMusicId: String? get() = pendingMusic ?: currentMusic

    private val thread: Thread? = if (startThread) Thread(::runLoop, "pkmn-audio").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    } else null

    // ---- public API -----------------------------------------------------------------------------

    /** Switches music, fading the current track out over [fadeMs]. No-op if [id] is already playing. */
    fun playMusic(id: String, fadeMs: Int = 300) { queue.add(Cmd.PlayMusic(id, fadeMs)) }

    fun stopMusic(fadeMs: Int = 300) { queue.add(Cmd.StopMusic(fadeMs)) }

    fun playSfx(sfx: Sfx) { queue.add(Cmd.PlaySfx(sfx)) }

    fun stopAllSfx() { queue.add(Cmd.StopSfx) }

    /** Plays a one-shot song (e.g. `jingle_item`), pausing the music and resuming it afterwards. */
    fun playJingle(id: String) { queue.add(Cmd.PlayJingle(id)) }

    fun setMusicVolume(v: Float) { musicVolume = v.coerceIn(0f, 1f) }
    fun setSfxVolume(v: Float) { sfxVolume = v.coerceIn(0f, 1f) }
    fun getMusicVolume(): Float = musicVolume
    fun getSfxVolume(): Float = sfxVolume

    /** Suspends rendering (Android onPause / audio-focus loss). */
    fun pause() {
        paused = true
        try { out?.pause() } catch (t: Throwable) { log("AudioEngine: pause failed: ${t.message}") }
    }

    fun resume() {
        try { out?.resume() } catch (t: Throwable) { log("AudioEngine: resume failed: ${t.message}") }
        synchronized(lock) { paused = false; lock.notifyAll() }
    }

    val isPaused: Boolean get() = paused

    fun dispose() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread?.let { it.interrupt(); try { it.join(1000) } catch (_: InterruptedException) {} }
        try { out?.dispose() } catch (t: Throwable) { log("AudioEngine: dispose failed: ${t.message}") }
    }

    // ---- audio thread -----------------------------------------------------------------------------

    private fun runLoop() {
        val blockNanos = blockFrames * 1_000_000_000L / sampleRate
        while (running) {
            try {
                if (paused) {
                    synchronized(lock) { while (paused && running) lock.wait(200) }
                    continue
                }
                val n = renderBlock(pcm)
                val o = out
                if (o != null) o.write(pcm, 0, n) else Thread.sleep(blockNanos / 1_000_000L, (blockNanos % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                if (!running) break
            } catch (t: Throwable) {
                log("AudioEngine: audio thread error: $t")
                try { Thread.sleep(50) } catch (_: InterruptedException) {}
            }
        }
    }

    /**
     * Applies pending commands and renders one block of [blockFrames] frames into [dst]
     * (interleaved if stereo). Returns the number of samples written. Called by the audio thread;
     * call it directly only when constructed with `startThread = false`.
     */
    fun renderBlock(dst: ShortArray): Int {
        processCommands()
        val frames = blockFrames
        val n = frames * channels
        java.util.Arrays.fill(mix, 0, n, 0f)

        if (jingleActive) {
            jingle.render(mix, 0, frames, musicVolume)
            if (!jingle.isActive) {
                jingleActive = false
                jingle.stop()
                fadeGain = 0f
                fadeStep = 1f / (0.25f * sampleRate) // fade the music back in over 250 ms
            }
        } else if (music.song != null) {
            java.util.Arrays.fill(musicBuf, 0, n, 0f)
            music.render(musicBuf, 0, frames)
            val mv = musicVolume
            var gain = fadeGain
            val step = fadeStep
            var i = 0
            while (i < n) {
                if (step != 0f) gain = (gain + step).coerceIn(0f, 1f)
                val g = gain * mv
                mix[i] += musicBuf[i] * g
                if (channels == 2) mix[i + 1] += musicBuf[i + 1] * g
                i += channels
            }
            fadeGain = gain
            if (step > 0f && gain >= 1f) fadeStep = 0f
            if (step < 0f && gain <= 0f) { fadeStep = 0f; onFadeOutComplete() }
        } else if (pendingMusic != null || pendingStop) {
            onFadeOutComplete()
        }

        val sv = sfxVolume
        for (i in 0 until SFX_SLOTS) {
            val p = sfx[i]
            if (p.song == null) continue
            p.render(mix, 0, frames, sv)
            if (!p.isActive) { p.stop(); sfxIds[i] = null }
        }

        if (muted) java.util.Arrays.fill(dst, 0, n, 0) else ChipSynth.floatToPcm(mix, 0, dst, 0, n)
        return n
    }

    private fun processCommands() {
        while (true) {
            val c = queue.poll() ?: break
            when (c) {
                is Cmd.PlayMusic -> requestMusic(c.id, c.fadeMs)
                is Cmd.StopMusic -> {
                    pendingMusic = null
                    if (music.song == null) { currentMusic = null; continue }
                    pendingStop = true
                    startFadeOut(c.fadeMs)
                }
                is Cmd.PlaySfx -> startSfx(c.sfx)
                is Cmd.StopSfx -> for (i in 0 until SFX_SLOTS) { sfx[i].stop(); sfxIds[i] = null }
                is Cmd.PlayJingle -> {
                    val s = loadSong(c.id) ?: continue
                    jingle.load(s)
                    jingleActive = true
                }
            }
        }
    }

    private fun requestMusic(id: String, fadeMs: Int) {
        if (pendingMusic == null && !pendingStop && currentMusic == id && music.song != null) return
        if (pendingMusic == id) return
        pendingStop = false
        if (music.song == null || fadeMs <= 0 || jingleActive) {
            pendingMusic = null
            startMusicNow(id)
        } else {
            pendingMusic = id
            startFadeOut(fadeMs)
        }
    }

    private fun startFadeOut(fadeMs: Int) {
        if (fadeMs <= 0) { fadeGain = 0f; fadeStep = 0f; onFadeOutComplete(); return }
        fadeStep = -1f / (fadeMs / 1000f * sampleRate)
    }

    private fun onFadeOutComplete() {
        if (pendingStop) {
            pendingStop = false
            music.stop(); currentMusic = null; fadeGain = 1f
        }
        val next = pendingMusic ?: return
        pendingMusic = null
        startMusicNow(next)
    }

    private fun startMusicNow(id: String) {
        currentMusic = id
        fadeGain = 1f
        fadeStep = 0f
        music.load(loadSong(id)) // null → silence
    }

    private fun loadSong(id: String): Song? {
        if (songCache.containsKey(id)) return songCache[id]
        val song = try {
            val text = loader(id)
            if (text == null) { log("AudioEngine: song '$id' not found; playing silence"); null }
            else MmlParser.parse(text, id)
        } catch (e: MmlException) {
            log("AudioEngine: song '$id' failed to parse: ${e.message}"); null
        } catch (t: Throwable) {
            log("AudioEngine: song '$id' could not be loaded: $t"); null
        }
        songCache[id] = song
        return song
    }

    private fun startSfx(s: Sfx) {
        val song = try { SoundEffects.song(s) } catch (t: Throwable) { log("AudioEngine: sfx ${s.id} invalid: $t"); return }
        // retrigger the same effect in place, else a free slot, else steal the oldest
        var slot = sfxIds.indexOf(s)
        if (slot < 0) slot = (0 until SFX_SLOTS).firstOrNull { sfx[it].song == null } ?: -1
        if (slot < 0) slot = (0 until SFX_SLOTS).minBy { sfxStarted[it] }
        sfx[slot].load(song)
        sfxIds[slot] = s
        sfxStarted[slot] = ++sfxCounter
    }

    /** Number of sound effects currently sounding (audio-thread view; for tests/diagnostics). */
    val activeSfxCount: Int get() = sfx.count { it.song != null }

    companion object {
        const val SFX_SLOTS = 2

        private fun defaultLog(msg: String) {
            val app = try { Gdx.app } catch (_: Throwable) { null }
            if (app != null) app.log("audio", msg) else System.err.println(msg)
        }
    }
}
