package pkmn.game.link

import pkmn.engine.battle.BattleOutcome
import pkmn.engine.battle.Choice
import pkmn.engine.battle.Request
import pkmn.engine.data.Dex
import pkmn.engine.link.*
import pkmn.engine.mon.PokemonFactory
import pkmn.engine.util.Rng
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.*

/** Real sockets on localhost: the transport, a whole lockstep battle over TCP, and hostile peers. */
class LinkNetTest {
    companion object {
        val dex: Dex by lazy { Dex.loadDefault() }
        val factory by lazy { PokemonFactory(dex) }
        val hash by lazy { LinkFingerprint.dataHash(dex) }
    }

    /** Stands in for Gdx.app.postRunnable: the reader threads queue, the test thread (the "game thread") runs. */
    private class GameThread {
        val q = ConcurrentLinkedQueue<Runnable>()
        val post: (Runnable) -> Unit = { q.add(it) }
        fun drain() { while (true) (q.poll() ?: return).run() }
        fun until(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < end) { drain(); if (cond()) return true; Thread.sleep(2) }
            drain(); return cond()
        }
    }

    private class Pair(val host: TcpTransport, val guest: TcpTransport)

    private fun connect(gt: GameThread): Pair {
        var host: TcpTransport? = null; var guest: TcpTransport? = null; var err: String? = null
        val l = TcpTransport.listen(0, gt.post, { host = it }, { err = it })
        assertTrue(l.port > 0, err ?: "no port")
        TcpTransport.connect("127.0.0.1", l.port, 3000, gt.post, { guest = it }, { err = it })
        assertTrue(gt.until { host != null && guest != null }, "not connected: $err")
        return Pair(host!!, guest!!)
    }

    private fun frame(n: Int) = FrameCodec.encode(Frame(n.toLong(), Heartbeat))

    @Test
    fun `frames cross in order both ways and close flushes everything sent`() {
        val gt = GameThread(); val p = connect(gt)
        repeat(300) { p.host.send(frame(it)) }
        p.guest.send(frame(7))
        val got = ArrayList<Long>()
        assertTrue(gt.until { while (true) { val f = p.guest.receive() ?: break; got.add(FrameCodec.decode(f).seq) }; got.size == 300 })
        assertEquals((0L until 300L).toList(), got)
        assertTrue(gt.until { p.host.receive() != null })
        // Close right after sending: the peer still reads every frame, then sees the link closed.
        repeat(50) { p.host.send(frame(1000 + it)) }
        p.host.close()
        val rest = ArrayList<Long>()
        assertTrue(gt.until { while (true) { val f = p.guest.receive() ?: break; rest.add(FrameCodec.decode(f).seq) }; p.guest.isClosed })
        assertEquals((1000L until 1050L).toList(), rest)
        assertTrue(p.host.isClosed)
        p.guest.close()
    }

    @Test
    fun `a frame in several pieces is rebuilt and a big frame passes`() {
        val gt = GameThread()
        var accepted: TcpTransport? = null
        val l = TcpTransport.listen(0, gt.post, { accepted = it }, { fail(it) })
        val raw = Socket().apply { connect(InetSocketAddress("127.0.0.1", l.port), 2000); tcpNoDelay = true }
        assertTrue(gt.until { accepted != null })
        val f = frame(5)
        for (b in f) { raw.getOutputStream().write(b.toInt()); raw.getOutputStream().flush() }
        raw.getOutputStream().write(frame(6)); raw.getOutputStream().flush()
        val seqs = ArrayList<Long>()
        assertTrue(gt.until { accepted!!.receive()?.let { seqs.add(FrameCodec.decode(it).seq) }; seqs.size == 2 })
        assertEquals(listOf(5L, 6L), seqs)
        raw.close()
        assertTrue(gt.until { accepted!!.isClosed })
    }

    @Test
    fun `an oversized prefix is reported and closes the link`() {
        val gt = GameThread()
        var accepted: TcpTransport? = null
        val l = TcpTransport.listen(0, gt.post, { accepted = it }, { fail(it) })
        val raw = Socket().apply { connect(InetSocketAddress("127.0.0.1", l.port), 2000) }
        assertTrue(gt.until { accepted != null })
        raw.getOutputStream().write(byteArrayOf(0x00, 0x7f, -1, -1)); raw.getOutputStream().flush()
        var bytes: ByteArray? = null
        assertTrue(gt.until { bytes = accepted!!.receive(); bytes != null })
        assertEquals(VoidReason.OVERSIZED_FRAME, assertFailsWith<LinkProtocolException> { FrameCodec.decode(bytes!!) }.reason)
        assertTrue(gt.until { accepted!!.isClosed })
        raw.close()
    }

    @Test
    fun `a valid frame in the same read as an oversized prefix is delivered before the close`() {
        val gt = GameThread()
        var accepted: TcpTransport? = null
        val l = TcpTransport.listen(0, gt.post, { accepted = it }, { fail(it) })
        val raw = Socket().apply { connect(InetSocketAddress("127.0.0.1", l.port), 2000) }
        assertTrue(gt.until { accepted != null })
        // One write, so the reader gets the valid frame and the bad prefix in a single read.
        raw.getOutputStream().write(frame(9) + byteArrayOf(0x00, 0x7f, -1, -1)); raw.getOutputStream().flush()
        val got = ArrayList<ByteArray>()
        assertTrue(gt.until { accepted!!.receive()?.let { got.add(it) }; got.size == 2 })
        assertEquals(9L, FrameCodec.decode(got[0]).seq)
        assertEquals(VoidReason.OVERSIZED_FRAME, assertFailsWith<LinkProtocolException> { FrameCodec.decode(got[1]) }.reason)
        assertTrue(gt.until { accepted!!.isClosed })
        raw.close()
    }

    @Test
    fun `connecting to nothing reports an error`() {
        val gt = GameThread()
        val free = java.net.ServerSocket(0).use { it.localPort }
        var err: String? = null
        TcpTransport.connect("127.0.0.1", free, 1000, gt.post, { fail("connected?") }, { err = it })
        assertTrue(gt.until { err != null })
        assertTrue(err!!.startsWith("Couldn't connect"))
    }

    // ------------------------------------------------------------------ whole battles over TCP

    private fun team(seed: Long, n: Int) = (0 until n).map { i -> factory.create(listOf("pikachu", "charmander", "bulbasaur", "rattata", "pidgey", "geodude")[(i + seed.toInt()) % 6], 25 + i, Rng(seed + i), moves = listOf("tackle", "thundershock", "ember").take(1 + i % 3)) }

    /** Chooses for whatever the session asks: the first usable move, or the first Pokémon that can come in. */
    private fun act(lb: LockstepBattle, forfeitAt: Int? = null) {
        val b = lb.battle ?: return
        for (d in lb.pendingLocal()) {
            val side = b.sides[if (lb.role == LinkRole.HOST) 0 else 1]
            val c = when (val r = d.request) {
                is Request.SwitchIn -> Choice.Switch(side.mons.indexOfFirst { !it.fainted && !it.isActive })
                is Request.Move -> if (forfeitAt != null && b.turn >= forfeitAt) Choice.Forfeit else Choice.UseMove(r.usableMoves.indexOfFirst { it }.coerceAtLeast(0))
                else -> continue
            }
            if (lb.submit(d.slot, c) != null) { if (lb.submit(d.slot, Choice.UseMove(0)) != null) lb.submit(d.slot, Choice.Pass) }
        }
    }

    private fun play(a: TcpTransport, b: TcpTransport, gt: GameThread, forfeitGuestAt: Int? = null): Pair2 {
        val rules = LinkLobbyRules.rules(false, emptySet())
        val cfg = LinkConfig("test", rules, dataHash = hash)
        val host = LockstepBattle(LinkRole.HOST, a, dex, cfg, team(1, 3), "HOST")
        val guest = LockstepBattle(LinkRole.GUEST, b, dex, cfg, team(2, 3), "GUEST")
        assertTrue(gt.until(30_000) {
            val now = System.nanoTime() / 1_000_000
            host.pump(now); guest.pump(now); act(host); act(guest, forfeitGuestAt)
            host.isOver && guest.isOver
        }, "battle did not finish: ${host.result} / ${guest.result}")
        return Pair2(host, guest)
    }
    private class Pair2(val host: LockstepBattle, val guest: LockstepBattle)

    @Test
    fun `two devices finish a battle over tcp with the same result`() {
        val gt = GameThread(); val p = connect(gt)
        val r = play(p.host, p.guest, gt)
        val h = assertIs<LinkResult.Completed>(r.host.result); val g = assertIs<LinkResult.Completed>(r.guest.result)
        assertEquals(h.hash, g.hash); assertEquals(h.winnerSide, g.winnerSide); assertNotEquals(BattleOutcome.ONGOING, h.outcome)
    }

    @Test
    fun `a forfeit ends the battle for both`() {
        val gt = GameThread(); val p = connect(gt)
        val r = play(p.host, p.guest, gt, forfeitGuestAt = 0)
        val h = assertIs<LinkResult.Completed>(r.host.result); val g = assertIs<LinkResult.Completed>(r.guest.result)
        assertEquals(0, h.winnerSide); assertEquals(h.hash, g.hash)
    }

    @Test
    fun `a peer that vanishes mid battle voids it on the survivor`() {
        val gt = GameThread(); val p = connect(gt)
        val rules = LinkLobbyRules.rules(false, emptySet())
        val cfg = LinkConfig("test", rules, dataHash = hash)
        val host = LockstepBattle(LinkRole.HOST, p.host, dex, cfg, team(1, 3), "HOST")
        val guest = LockstepBattle(LinkRole.GUEST, p.guest, dex, cfg, team(2, 3), "GUEST")
        assertTrue(gt.until { host.pump(System.nanoTime() / 1_000_000); guest.pump(System.nanoTime() / 1_000_000); act(host); act(guest); host.battle != null && guest.battle != null && host.battle!!.turn >= 2 })
        p.guest.close() // the other device dies: its socket closes
        assertTrue(gt.until { host.pump(System.nanoTime() / 1_000_000); act(host); host.isOver })
        assertEquals(VoidReason.LINK_LOST, assertIs<LinkResult.Voided>(host.result).reason)
    }
}
