package pkmn.game.link

import pkmn.engine.link.LinkFingerprint
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class LinkBuildTest {
    @Test
    fun `the game version carries the engine-rules id`() {
        assertTrue(LinkBuild.GAME_VERSION.endsWith("+r${LinkFingerprint.rulesId}"), LinkBuild.GAME_VERSION)
        assertTrue(LinkBuild.GAME_VERSION.substringBefore('+').isNotEmpty())
    }

    @Test
    fun `the local hash is pending until done and never blocks`() {
        val f = CompletableFuture<String>()
        val gate = LocalHash(f)
        assertEquals(LocalHash.State.PENDING, gate.poll()); assertNull(gate.value)
        f.complete("abc")
        assertEquals(LocalHash.State.READY, gate.poll()); assertEquals("abc", gate.value)
    }

    @Test
    fun `a failed hash is reported, not thrown`() {
        val f = CompletableFuture<String>()
        f.completeExceptionally(IllegalStateException("boom"))
        val gate = LocalHash(f)
        assertEquals(LocalHash.State.FAILED, gate.poll()); assertNull(gate.value)
    }
}
