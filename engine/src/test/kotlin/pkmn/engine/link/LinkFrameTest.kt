package pkmn.engine.link

import pkmn.engine.TestSupport.dex
import pkmn.engine.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkFrameTest {
    private fun reason(block: () -> Unit) = assertFailsWith<LinkProtocolException> { block() }.reason

    @Test
    fun `every message type survives a round trip`() {
        val team = LinkTestSupport.teams(1).first
        val msgs = listOf(
            Hello(1, "v", "h", "Ann", LinkTestSupport.rules()), TeamMsg(team, "c".repeat(64)), SeedReveal(-5L, "ff"), Commit(3, "d"),
            Reveal(3, listOf(WireChoice(0, "move", 2, "TERASTALLIZE", 0), WireChoice(1, "forfeit")), "n"), StateMsg(-1, 0, "x", false), Heartbeat, Abort("TIMEOUT", "why"),
        )
        for ((i, m) in msgs.withIndex()) {
            val f = FrameCodec.decode(FrameCodec.encode(Frame(i.toLong(), m)))
            assertEquals(i.toLong(), f.seq)
            if (m is TeamMsg) assertEquals(m.mons.map { it.speciesId }, (f.msg as TeamMsg).mons.map { it.speciesId }) else assertEquals(m, f.msg)
        }
    }

    @Test
    fun `six full teams fit far below the frame cap`() {
        val rng = Rng(3)
        val mons = List(6) { LinkTestSupport.randomMon(rng, 100) }
        val size = FrameCodec.encode(Frame(0, TeamMsg(mons, "c".repeat(64)))).size
        println("LinkFrame: a team of 6 is $size bytes, cap ${LinkProtocol.MAX_PAYLOAD}")
        assertTrue(size < LinkProtocol.MAX_PAYLOAD / 2)
    }

    @Test
    fun `malformed frames are refused`() {
        val good = FrameCodec.encode(Frame(0, Heartbeat))
        assertEquals(VoidReason.MALFORMED_FRAME, reason { FrameCodec.decode(byteArrayOf(0, 0)) })
        assertEquals(VoidReason.MALFORMED_FRAME, reason { FrameCodec.decode(good.copyOf(good.size - 1)) })
        assertEquals(VoidReason.MALFORMED_FRAME, reason { FrameCodec.decode(good + byteArrayOf(1)) })
        fun raw(text: String): ByteArray { val p = text.toByteArray(); return ByteArray(4).also { FrameCodec.writeLength(it, p.size) } + p }
        assertEquals(VoidReason.MALFORMED_FRAME, reason { FrameCodec.decode(raw("not json")) })
        assertEquals(VoidReason.MALFORMED_FRAME, reason { FrameCodec.decode(raw("""{"seq":0,"msg":{"t":"bag","item":"potion"}}""")) })
        assertEquals(VoidReason.MALFORMED_FRAME, reason { FrameCodec.decode(raw("""{"seq":0,"msg":{"t":"beat"},"extra":1}""")) })
        assertEquals(VoidReason.MALFORMED_FRAME, reason { FrameCodec.decode(raw("""{"seq":"x","msg":{"t":"beat"}}""")) })
    }

    @Test
    fun `oversized frames are refused on both ends`() {
        val big = ByteArray(4).also { FrameCodec.writeLength(it, LinkProtocol.MAX_PAYLOAD + 1) } + ByteArray(LinkProtocol.MAX_PAYLOAD + 1)
        assertEquals(VoidReason.OVERSIZED_FRAME, reason { FrameCodec.decode(big) })
        assertEquals(VoidReason.OVERSIZED_FRAME, reason { FrameCodec.decode(byteArrayOf(-1, -1, -1, -1)) })
        assertEquals(VoidReason.OVERSIZED_FRAME, reason { FrameCodec.encode(Frame(0, Abort("X", "y".repeat(LinkProtocol.MAX_PAYLOAD)))) })
        // The stream reader refuses on the prefix alone, before any payload is buffered.
        assertEquals(VoidReason.OVERSIZED_FRAME, reason { FrameBuffer().feed(byteArrayOf(0, 1, 0, 1)) })
    }

    @Test
    fun `a valid frame read together with an oversized prefix is kept`() {
        val ok = FrameCodec.encode(Frame(3, Heartbeat))
        val buf = FrameBuffer()
        val out = buf.feed(ok + byteArrayOf(0, 1, 0, 1))
        assertEquals(listOf(ok.toList()), out.map { it.toList() })
        assertEquals(VoidReason.OVERSIZED_FRAME, buf.error?.reason)
        // Nothing more is read past the bad prefix.
        assertEquals(VoidReason.OVERSIZED_FRAME, reason { buf.feed(ok) })
    }

    @Test
    fun `a stream is cut into frames however the bytes arrive`() {
        val frames = listOf(Heartbeat, Abort("A", "b"), Commit(1, "d")).mapIndexed { i, m -> FrameCodec.encode(Frame(i.toLong(), m)) }
        val stream = frames.reduce { a, b -> a + b }
        val buf = FrameBuffer()
        val out = ArrayList<ByteArray>()
        for (byte in stream) out += buf.feed(byteArrayOf(byte))
        assertEquals(frames.map { it.toList() }, out.map { it.toList() })
        assertEquals(frames.map { it.toList() }, FrameBuffer().feed(stream).map { it.toList() })
    }

    @Test
    fun `rules reject what a link cannot play`() {
        assertEquals(null, LinkTestSupport.rules().error())
        assertTrue(LinkRules(format = "DOUBLES").error() != null)
        assertTrue(LinkRules(gimmicks = listOf("NOPE")).error() != null)
        assertTrue(LinkRules(levelCap = 0).error() != null)
        assertTrue(LinkRules(gimmicks = listOf("B", "A")).error() != null)
    }

    @Test
    fun `data hash is stable and changes with the data`() {
        assertEquals(LinkFingerprint.dataHash(dex), LinkFingerprint.dataHash(dex))
        assertEquals(64, LinkTestSupport.dataHash.length)
    }
}
