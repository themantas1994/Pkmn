package pkmn.game.audio

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryBankTest {
    private class FakeVoice(val name: String, val events: MutableList<String>) : CryVoice {
        var ready = true
        var throwOnPlay = false
        override fun play(volume: Float): Boolean { if (throwOnPlay) error("boom"); events += "play:$name:$volume"; return ready }
        override fun stop() { events += "stop:$name" }
        override fun dispose() { events += "dispose:$name" }
    }

    private fun dirWith(vararg ids: String): File = Files.createTempDirectory("cries").toFile().also { d -> ids.forEach { File(d, "$it.ogg").writeText("x") } }
    private val bases = mapOf("charizardmegax" to "charizard", "charizardgmax" to "charizard")

    @Test
    fun resolvesOwnFileThenBaseSpeciesThenNull() {
        val d = dirWith("charizard", "charizardmegax", "pikachu")
        val r = CryResolver(listOf(d)) { bases[it] }
        assertEquals("charizardmegax.ogg", r.resolve("charizardmegax")!!.name)
        assertEquals("charizard.ogg", r.resolve("charizardgmax")!!.name) // form -> base fallback
        assertEquals("pikachu.ogg", r.resolve("pikachu")!!.name)
        assertNull(r.resolve("mew"))
        File(d, "raichu.mp3").writeText("x")
        assertEquals("raichu.mp3", r.resolve("raichu")!!.name) // upstream MP3 cries
        assertNull(CryResolver(emptyList()) { bases[it] }.resolve("pikachu"))
        assertNull(CryResolver(listOf(File(d, "nope"))) { bases[it] }.resolve("pikachu"))
    }

    @Test
    fun cycleInBaseChainTerminates() {
        assertNull(CryResolver(listOf(dirWith())) { if (it == "a") "b" else "a" }.resolve("a"))
    }

    @Test
    fun missingFileFallsBackAndIsCounted() {
        val logs = ArrayList<String>()
        val bank = CryBank(CryResolver(listOf(dirWith("pikachu"))), { FakeVoice(it.nameWithoutExtension, ArrayList()) }, { logs += it })
        assertTrue(bank.play("pikachu", 0.5f))
        assertFalse(bank.play("mew", 0.5f)); assertFalse(bank.play("mew", 0.5f)); assertFalse(bank.play("ditto", 0.5f))
        assertEquals(3, bank.missCount); assertEquals(setOf("mew", "ditto"), bank.missedSpecies); assertEquals(1, bank.playCount)
        assertEquals(2, logs.size) // logged once per species
    }

    @Test
    fun newCryInterruptsPreviousAndDisposeReleasesAll() {
        val events = ArrayList<String>()
        val bank = CryBank(CryResolver(listOf(dirWith("a", "b"))), { FakeVoice(it.nameWithoutExtension, events) })
        bank.play("a", 0.6f); bank.play("b", 0.6f); bank.play("b", 0.6f)
        assertEquals(listOf("play:a:0.6", "play:b:0.6", "stop:a", "stop:b", "play:b:0.6"), events)
        events.clear(); bank.dispose()
        assertEquals(setOf("dispose:a", "dispose:b"), events.toSet())
    }

    @Test
    fun cacheIsBoundedAndSilentVolumeDoesNotPlay() {
        val events = ArrayList<String>()
        val ids = (1..5).map { "s$it" }.toTypedArray()
        val bank = CryBank(CryResolver(listOf(dirWith(*ids))), { FakeVoice(it.nameWithoutExtension, events) }, maxCached = 2)
        ids.forEach { bank.play(it, 1f) }
        assertEquals(3, events.count { it.startsWith("dispose:") })
        events.clear(); assertTrue(bank.play("s5", 0f)); assertTrue(events.none { it.startsWith("play:") })
    }

    @Test
    fun voiceStillLoadingFallsBackInsteadOfCountingAsPlayedThenPlaysOnceReady() {
        // Android's SoundPool loads asynchronously: play() right after newSound() returns no stream.
        val events = ArrayList<String>(); val logs = ArrayList<String>()
        val voices = HashMap<String, FakeVoice>()
        val bank = CryBank(CryResolver(listOf(dirWith("pikachu"))), { f -> FakeVoice(f.nameWithoutExtension, events).also { it.ready = false; voices[f.nameWithoutExtension] = it } }, { logs += it })
        assertFalse(bank.play("pikachu", 0.6f)) // caller uses the generic blip
        assertEquals(0, bank.playCount); assertEquals(1, logs.size)
        voices["pikachu"]!!.ready = true
        assertTrue(bank.play("pikachu", 0.6f)); assertEquals(1, bank.playCount)
    }

    @Test
    fun prefetchLoadsWithoutPlayingSoFirstRealPlayIsReady() {
        val events = ArrayList<String>(); var loads = 0
        val bank = CryBank(CryResolver(listOf(dirWith("a"))), { loads++; FakeVoice(it.nameWithoutExtension, events) })
        bank.prefetch("a"); bank.prefetch("a"); bank.prefetch("missing")
        assertEquals(1, loads); assertTrue(events.isEmpty()); assertEquals(0, bank.missCount) // not a gameplay miss
        assertTrue(bank.play("a", 0.6f)); assertEquals(1, loads); assertEquals(listOf("play:a:0.6"), events)
    }

    // ---- AN-5: readiness, replacement rule, failures, resources

    private fun bankWith(events: MutableList<String>, voices: MutableMap<String, FakeVoice>, vararg ids: String, logs: MutableList<String> = ArrayList(), loadBlock: (File) -> CryVoice? = { null }, max: Int = 16) =
        CryBank(CryResolver(listOf(dirWith(*ids))), { f -> loadBlock(f) ?: FakeVoice(f.nameWithoutExtension, events).also { voices[f.nameWithoutExtension] = it } }, { logs += it }, max)

    @Test
    fun unreadyReplacementDoesNotSilenceTheCryThatIsStillPlaying() {
        val events = ArrayList<String>(); val voices = HashMap<String, FakeVoice>()
        val bank = bankWith(events, voices, "a", "b")
        assertTrue(bank.play("a", 0.6f))
        bank.prefetch("b"); voices["b"]!!.ready = false
        events.clear()
        assertFalse(bank.play("b", 0.6f))
        assertTrue("stop:a" !in events, "a was cut for a cry that never started: $events")
        voices["b"]!!.ready = true
        assertTrue(bank.play("b", 0.6f)); assertTrue("stop:a" in events)
    }

    @Test
    fun deferredCryPlaysWhenTheSoundBecomesReady() {
        val events = ArrayList<String>(); val voices = HashMap<String, FakeVoice>()
        val bank = bankWith(events, voices, "a")
        bank.prefetch("a"); voices["a"]!!.ready = false
        assertEquals(CryOutcome.DEFERRED, bank.playOrDefer("a", 0.5f, nowMs = 1000))
        assertTrue(bank.hasPending); assertNull(bank.poll(1100)) // still loading
        voices["a"]!!.ready = true
        assertEquals(CryOutcome.PLAYED, bank.poll(1200))
        assertFalse(bank.hasPending); assertEquals(1, bank.playCount)
        assertNull(bank.poll(1300))
    }

    @Test
    fun deferredCryFallsBackToTheBlipAfterTheDeadline() {
        val events = ArrayList<String>(); val voices = HashMap<String, FakeVoice>()
        val bank = bankWith(events, voices, "a")
        bank.prefetch("a"); voices["a"]!!.ready = false
        bank.playOrDefer("a", 0.5f, 1000, waitMs = 300)
        assertNull(bank.poll(1299))
        assertEquals(CryOutcome.FALLBACK, bank.poll(1300))
        assertFalse(bank.hasPending); assertEquals(0, bank.playCount)
    }

    @Test
    fun newRequestStopAndDisposeDropAPendingCry() {
        val events = ArrayList<String>(); val voices = HashMap<String, FakeVoice>()
        val bank = bankWith(events, voices, "a", "b")
        bank.prefetch("a"); voices["a"]!!.ready = false
        bank.playOrDefer("a", 0.5f, 0)
        assertEquals(CryOutcome.PLAYED, bank.playOrDefer("b", 0.5f, 10)) // newest request wins, the stale one is dropped
        assertFalse(bank.hasPending)
        voices["a"]!!.ready = true; assertNull(bank.poll(20))
        voices["b"]!!.ready = false; bank.playOrDefer("b", 0.5f, 30); bank.stop(); assertFalse(bank.hasPending)
        bank.playOrDefer("b", 0.5f, 40); bank.dispose(); assertFalse(bank.hasPending)
    }

    @Test
    fun missingFileIsGenuineFallbackNotDeferred() {
        val bank = bankWith(ArrayList(), HashMap(), "a")
        assertEquals(CryOutcome.FALLBACK, bank.playOrDefer("mew", 0.5f, 0)); assertEquals(1, bank.missCount)
    }

    @Test
    fun repeatedPlaysReuseTheLoadedVoiceAndRestartIt() {
        val events = ArrayList<String>(); var loads = 0
        val bank = CryBank(CryResolver(listOf(dirWith("a"))), { loads++; FakeVoice("a", events) })
        repeat(5) { assertTrue(bank.play("a", 0.6f)) }
        assertEquals(1, loads); assertEquals(5, bank.playCount); assertEquals(4, events.count { it == "stop:a" })
    }

    @Test
    fun failedLoadsAreRetriedOnlyBrieflyThenTreatedAsMissingWithoutCrashing() {
        val logs = ArrayList<String>(); var attempts = 0
        val bank = CryBank(CryResolver(listOf(dirWith("bad"))), { attempts++; error("corrupt") }, { logs += it }, maxLoadFailures = 2)
        repeat(6) { assertFalse(bank.play("bad", 0.6f)) }
        assertEquals(2, attempts); assertEquals(2, logs.count { it.startsWith("cry load failed") })
        assertEquals(CryOutcome.FALLBACK, bank.playOrDefer("bad", 0.6f, 0))
    }

    @Test
    fun nullLoadCountsAsFailureAndAThrowingPlayIsContained() {
        val events = ArrayList<String>(); val voices = HashMap<String, FakeVoice>()
        val nullBank = CryBank(CryResolver(listOf(dirWith("n"))), { null })
        assertFalse(nullBank.play("n", 0.6f))
        val bank = bankWith(events, voices, "t")
        bank.prefetch("t"); voices["t"]!!.throwOnPlay = true
        assertFalse(bank.play("t", 0.6f)) // does not throw into the render loop
    }

    @Test
    fun evictionNeverDisposesTheCurrentOrPendingVoice() {
        val events = ArrayList<String>(); val voices = HashMap<String, FakeVoice>()
        val ids = (1..6).map { "s$it" }.toTypedArray()
        val bank = bankWith(events, voices, *ids, max = 2)
        bank.prefetch("s1"); voices["s1"]!!.ready = false
        bank.playOrDefer("s1", 1f, 0) // pending
        for (id in ids.drop(1)) bank.prefetch(id)
        assertTrue("dispose:s1" !in events, "pending voice was released: $events")
        voices["s1"]!!.ready = true
        assertEquals(CryOutcome.PLAYED, bank.poll(1))
        bank.dispose()
        assertEquals(1, events.count { it == "dispose:s1" })
    }
}
