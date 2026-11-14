package pkmn.engine.link

/**
 * One end of the connection between two devices of a link battle. Pure interface: no sockets live in the engine. A real
 * transport (Phase 3: TCP) hands over complete frames, length prefix included (see [FrameCodec]), and splits the byte
 * stream itself with [FrameBuffer]. Everything is non-blocking and called from one thread; [LockstepBattle] polls it.
 *
 * A transport delivers frames in order and unharmed, as TCP does. When it cannot (a duplicated or lost frame), the
 * session notices through the frame sequence number and ends the battle instead of guessing.
 */
interface LinkTransport {
    /** Queues one complete frame for the other device. Never blocks; ignored once the link is closed. */
    fun send(frame: ByteArray)

    /** The next received frame, or null when none is available right now. Never blocks. */
    fun receive(): ByteArray?

    /** True once the link is gone (closed here or by the other side) and every frame that arrived has been read. */
    val isClosed: Boolean

    /** Ends the link. Frames already sent may still reach the other side; it then sees [isClosed]. */
    fun close()
}

/**
 * What a [LoopbackLink] does to the frames sent through it. Frames are numbered 0, 1, 2... in the order they are
 * sent, both directions counted together; the lambdas get that number and the sending end (0 = [LoopbackLink.a], 1 = [LoopbackLink.b]).
 */
class LinkFaults(
    /** True: the frame never arrives. */
    val drop: (frame: Int, from: Int) -> Boolean = { _, _ -> false },
    /** True: the frame arrives twice. */
    val duplicate: (frame: Int, from: Int) -> Boolean = { _, _ -> false },
    /** Milliseconds (of the link's clock) until the frame may be received. Order is kept: a frame never overtakes an earlier one. */
    val delayMs: (frame: Int, from: Int) -> Long = { _, _ -> 0L },
    /** The link breaks as frame number [cutAt] is sent: it and every later frame are lost. Null: never. */
    val cutAt: Int? = null,
    /** With a cut: false = both ends see the link close (a reset connection); true = nothing is signalled (a dead Wi-Fi). */
    val silentCut: Boolean = false,
)

/**
 * Two [LinkTransport]s wired to each other in memory, for tests and for a battle against a local peer. Time comes from
 * [clock] (milliseconds), so a test controls delay and timeouts exactly; nothing here reads the system clock.
 */
class LoopbackLink(private val clock: () -> Long, val faults: LinkFaults = LinkFaults()) {
    private class Pending(val bytes: ByteArray, val readyAt: Long)

    /** Number of frames sent so far, dropped ones included. */
    var framesSent = 0
        private set
    private var cut = false

    val a: LinkTransport = End(0)
    val b: LinkTransport = End(1)

    private inner class End(val index: Int) : LinkTransport {
        val inbox = ArrayDeque<Pending>()
        var closedHere = false
        private var lastReadyAt = 0L
        private val peer get() = if (index == 0) b as End else a as End

        override fun send(frame: ByteArray) {
            if (closedHere) return
            val n = framesSent++
            if (faults.cutAt != null && n >= faults.cutAt) { cut = true; return }
            if (faults.drop(n, index)) return
            // A frame never overtakes an earlier one, as on a stream.
            val readyAt = maxOf(peer.lastReadyAt, clock() + faults.delayMs(n, index))
            peer.lastReadyAt = readyAt
            peer.inbox.addLast(Pending(frame.copyOf(), readyAt))
            if (faults.duplicate(n, index)) peer.inbox.addLast(Pending(frame.copyOf(), readyAt))
        }

        override fun receive(): ByteArray? {
            if (inbox.isEmpty() || inbox.first().readyAt > clock()) return null
            return inbox.removeFirst().bytes
        }

        override val isClosed: Boolean
            get() = inbox.isEmpty() && (closedHere || peer.closedHere || (cut && !faults.silentCut))

        override fun close() { closedHere = true }
    }
}
