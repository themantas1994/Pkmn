package pkmn.engine.battle

import pkmn.engine.TestSupport.dex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Link-play Phase 0: 400 seeded AI battles must hash identically on every run and, by hand, on every runtime
 * (desktop `--selftest=determinism`, Android log tag PkmnDeterminism). The combined hash is printed, not pinned: it is
 * compared across devices, and it changes whenever battle behaviour legitimately changes.
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
