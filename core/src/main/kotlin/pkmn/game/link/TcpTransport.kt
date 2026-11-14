package pkmn.game.link

import com.badlogic.gdx.Gdx
import pkmn.engine.link.FrameBuffer
import pkmn.engine.link.LinkAddress
import pkmn.engine.link.LinkProtocolException
import pkmn.engine.link.LinkTransport
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * A [LinkTransport] over one TCP connection (IPv4, local network, `java.net` only). A reader thread splits the byte
 * stream into frames with [FrameBuffer] and hands each to the game thread through [post] (`Gdx.app.postRunnable`), so
 * [receive], [isClosed] and [close] are only ever touched from the game thread, as [LinkTransport] expects. A writer
 * thread owns the output stream, so [send] never blocks the frame loop.
 *
 * An oversized length prefix stops the reader before it buffers anything: the four prefix bytes are delivered as a
 * frame (the session's decoder then reports the oversized frame) and the connection is closed.
 */
/** A host waiting for its one guest. [port] is the port actually bound (0 when binding failed). */
class Listener(val port: Int, private val onClose: () -> Unit) : Closeable { override fun close() = onClose() }

class TcpTransport(private val socket: Socket, private val post: (Runnable) -> Unit = { r -> Gdx.app.postRunnable(r) }) : LinkTransport {
    // Game thread only.
    private val inbox = ArrayDeque<ByteArray>()
    private var remoteClosed = false
    private var closedHere = false

    private val outbox = LinkedBlockingQueue<ByteArray>()

    init {
        socket.tcpNoDelay = true
        Thread(::readLoop, "link-reader").apply { isDaemon = true; start() }
        Thread(::writeLoop, "link-writer").apply { isDaemon = true; start() }
    }

    override fun send(frame: ByteArray) { if (!closedHere && !remoteClosed) outbox.add(frame) }

    override fun receive(): ByteArray? = inbox.removeFirstOrNull()

    override val isClosed: Boolean get() = inbox.isEmpty() && (remoteClosed || closedHere)

    /** Frames already sent are written first; then the sending side shuts down and the socket closes once the peer has too. */
    override fun close() {
        if (closedHere) return
        closedHere = true
        outbox.add(CLOSE)
        // If the writer is stuck in a write (the peer stopped reading), force the socket shut after a grace period.
        Thread({ try { Thread.sleep(CLOSE_GRACE_MS * 2) } catch (_: InterruptedException) {}; quietly { socket.close() } }, "link-closer").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        val buf = ByteArray(8192)
        val frames = FrameBuffer()
        try {
            val input = socket.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (f in frames.feed(buf.copyOf(n))) post { inbox.addLast(f) }
            }
        } catch (e: LinkProtocolException) {
            // Oversized prefix: let the session see (and report) it, then drop the connection.
            post { inbox.addLast(OVERSIZED_PREFIX) }
        } catch (_: IOException) {
        } finally {
            post { remoteClosed = true }
            quietly { socket.close() }
        }
    }

    private fun writeLoop() {
        try {
            val out = socket.getOutputStream()
            while (true) {
                val f = outbox.take()
                if (f === CLOSE) break
                out.write(f); out.flush()
            }
            // Half-close so everything sent reaches the peer, then give it a moment to close its end before the socket goes.
            quietly { socket.shutdownOutput() }
            Thread.sleep(CLOSE_GRACE_MS)
        } catch (_: IOException) {
            post { remoteClosed = true }
        } catch (_: InterruptedException) {
        } finally {
            quietly { socket.close() }
        }
    }

    companion object {
        private val CLOSE = ByteArray(0)
        /** A length prefix of 0x7fffffff: far over the frame cap, so the session's decoder reports an oversized frame. */
        private val OVERSIZED_PREFIX = byteArrayOf(0x7f, -1, -1, -1)
        private const val CLOSE_GRACE_MS = 1500L
        private inline fun quietly(f: () -> Unit) { try { f() } catch (_: Exception) {} }

        /**
         * Accepts exactly one connection on [port] (all IPv4 interfaces) in the background, then stops listening.
         * [onAccepted] and [onError] run on the game thread through [post]. Close the result to stop waiting.
         */
        fun listen(port: Int, post: (Runnable) -> Unit = { r -> Gdx.app.postRunnable(r) }, onAccepted: (TcpTransport) -> Unit, onError: (String) -> Unit): Listener {
            val server = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 1) }
            } catch (e: IOException) { post { onError("Can't listen on port $port: ${e.message}") }; return Listener(0) {} }
            Thread({
                try {
                    // Only a device on a local network may join; anything else is dropped and the host keeps waiting.
                    while (true) {
                        val s = server.accept()
                        if (!LinkAddress.isLocal(s.inetAddress.hostAddress ?: "")) { quietly { s.close() }; continue }
                        quietly { server.close() }
                        post { onAccepted(TcpTransport(s, post)) }
                        break
                    }
                } catch (e: IOException) {
                    if (!server.isClosed) post { onError("Listening failed: ${e.message}") }
                } finally { quietly { server.close() } }
            }, "link-accept").apply { isDaemon = true; start() }
            return Listener(server.localPort) { quietly { server.close() } }
        }

        /** Connects to [host]:[port] in the background. [onConnected] and [onError] run on the game thread through [post]. Close the result to give up. */
        fun connect(host: String, port: Int, timeoutMs: Int = 5000, post: (Runnable) -> Unit = { r -> Gdx.app.postRunnable(r) }, onConnected: (TcpTransport) -> Unit, onError: (String) -> Unit): Closeable {
            val s = Socket()
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread({
                try {
                    s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
                    post { onConnected(TcpTransport(s, post)) }
                } catch (e: IOException) {
                    quietly { s.close() }
                    if (!cancelled.get()) post { onError("Couldn't connect to $host: ${e.message ?: e.javaClass.simpleName}") }
                }
            }, "link-connect").apply { isDaemon = true; start() }
            return Closeable { cancelled.set(true); quietly { s.close() } }
        }
    }
}
