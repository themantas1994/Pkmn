package pkmn.engine.battle

import pkmn.engine.TestSupport.dex
import pkmn.engine.link.LinkFingerprint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Link-play Phase 0: 400 seeded AI battles must hash identically on every run and, by hand, on every runtime
 * (desktop `--selftest=determinism`, Android log tag PkmnDeterminism). The combined hash is pinned in
 * [LinkFingerprint.PINNED_COMBINED_HASH] and sent to the other device as the engine-rules id, so it changes whenever battle
 * behaviour legitimately changes: re-pin with `-Dgolden.update=true`. Both CI JVMs check it.
 */
class DeterminismTest {
    @Test
    fun `400 seeded battles hash identically on two runs`() {
        val a = DeterminismSelfTest.run(dex)
        val b = DeterminismSelfTest.run(dex)
        println(DeterminismSelfTest.summary(a))
        assertEquals(DeterminismSelfTest.DEFAULT_BATTLES, a.battles)
        assertTrue(a.turns > 0)
        assertEquals(a.perBattle, b.perBattle, "per-battle hashes differ between two runs in one JVM")
        assertEquals(a.combined, b.combined)
    }

    @Test
    fun `the combined hash matches the pinned engine-rules hash`() {
        val combined = DeterminismSelfTest.run(dex).combined
        if (System.getProperty("golden.update") == "true" && combined != LinkFingerprint.PINNED_COMBINED_HASH) {
            val f = File("src/main/kotlin/pkmn/engine/link/LinkFingerprint.kt")
            f.writeText(f.readText().replace(Regex("""const val PINNED_COMBINED_HASH = "[0-9a-f]*""""), "const val PINNED_COMBINED_HASH = \"$combined\""))
            println("re-pinned PINNED_COMBINED_HASH = $combined (rebuild before the next run)")
            return
        }
        assertEquals(LinkFingerprint.PINNED_COMBINED_HASH, combined,
            "battle behaviour or content changed: two builds would desync. Re-pin with -Dgolden.update=true and say why in the PR")
        assertEquals(8, LinkFingerprint.rulesId.length)
    }

    @Test
    fun `stateHash is read-only and sensitive to state`() {
        val b = pkmn.engine.TestSupport.battle(listOf(pkmn.engine.TestSupport.mon("pikachu")), listOf(pkmn.engine.TestSupport.mon("eevee")), seed = 5)
        val before = b.rng.state
        val h1 = b.stateHash()
        assertEquals(before, b.rng.state, "stateHash must not draw random numbers")
        assertEquals(h1, b.stateHash())
        b.player.active!!.hp -= 1
        assertTrue(h1 != b.stateHash(), "HP change must change the hash")
    }
}
