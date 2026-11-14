package pkmn.engine.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackTransportTest {
    private fun f(n: Int) = byteArrayOf(n.toByte())
    private fun drain(t: LinkTransport) = generateSequence { t.receive() }.map { it[0].toInt() }.toList()

    @Test
    fun `frames arrive in order`() {
        val l = LoopbackLink({ 0 })
        for (i in 0..4) l.a.send(f(i))
        l.b.send(f(9))
        assertEquals(listOf(0, 1, 2, 3, 4), drain(l.b)); assertEquals(listOf(9), drain(l.a))
    }

    @Test
    fun `drop duplicate and delay`() {
        var now = 0L
        val l = LoopbackLink({ now }, LinkFaults(drop = { n, _ -> n == 1 }, duplicate = { n, _ -> n == 2 }, delayMs = { n, _ -> if (n == 3) 500 else 0 }))
        for (i in 0..4) l.a.send(f(i))
        assertEquals(listOf(0, 2, 2), drain(l.b))
        now = 499; assertNull(l.b.receive())
        now = 500; assertEquals(listOf(3, 4), drain(l.b)) // the frame behind a delayed one waits for it
    }

    @Test
    fun `a cut loses the frame and everything after it`() {
        val l = LoopbackLink({ 0 }, LinkFaults(cutAt = 2))
        l.a.send(f(0)); l.b.send(f(1)); l.a.send(f(2)); l.b.send(f(3))
        assertFalse(l.a.isClosed); assertFalse(l.b.isClosed) // frames are still unread
        assertEquals(listOf(0), drain(l.b)); assertEquals(listOf(1), drain(l.a))
        assertTrue(l.a.isClosed); assertTrue(l.b.isClosed)
    }

    @Test
    fun `a silent cut never signals closure`() {
        val l = LoopbackLink({ 0 }, LinkFaults(cutAt = 0, silentCut = true))
        l.a.send(f(0)); assertNull(l.b.receive()); assertFalse(l.a.isClosed); assertFalse(l.b.isClosed)
    }

    @Test
    fun `closing shows the other side after it has read what was sent`() {
        val l = LoopbackLink({ 0 })
        l.a.send(f(7)); l.a.close()
        assertFalse(l.b.isClosed); assertEquals(listOf(7), drain(l.b)); assertTrue(l.b.isClosed)
    }
}
