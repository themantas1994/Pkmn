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
        override fun play(volume: Float) { events += "play:$name:$volume" }
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
        assertEquals(listOf("play:a:0.6", "stop:a", "play:b:0.6", "stop:b", "play:b:0.6"), events)
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
}
