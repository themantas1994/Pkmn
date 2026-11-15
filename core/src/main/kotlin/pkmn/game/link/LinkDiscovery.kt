package pkmn.game.link

import pkmn.engine.link.LinkAddress
import pkmn.engine.link.LinkProtocol
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Finding a host on the local network, with UDP broadcast from `java.net` only. No multicast, so Android needs no
 * permission beyond `INTERNET` (no multicast lock). It works both ways round, so one direction that a network filters
 * is enough:
 * - the host broadcasts an announcement every second to [ANNOUNCE_PORT] and answers queries on [QUERY_PORT];
 * - the guest listens on [ANNOUNCE_PORT] and broadcasts a query every second to [QUERY_PORT]; hosts answer it directly.
 *
 * An announcement names the trainer, the mode the host chose and the TCP port to connect to; the address is taken from
 * the datagram itself. Typing the code stays the fallback: nothing here is needed to link.
 */
object LinkDiscovery {
    const val ANNOUNCE_PORT = 47841
    const val QUERY_PORT = 47842
    private const val MAGIC = "PKMN-LINK"
    private const val QUERY = "$MAGIC?"
    /** A host not heard from for this long drops off the list. */
    const val STALE_MS = 4_000L
    /** Most hosts listed at once (a flood of fake announcements cannot grow the list without end). */
    const val MAX_HOSTS = 16
    private const val PERIOD_MS = 1_000L

    /** One host as the guest's list shows it. */
    data class Host(val name: String, val mode: String, val address: String, val port: Int, val seenAtMs: Long)

    /** The text of an announcement. Fields are `key=value` lines; unknown keys are ignored. */
    fun announcement(name: String, mode: String, port: Int): String =
        "$MAGIC\nproto=${LinkProtocol.VERSION}\nname=${name.take(LinkProtocol.MAX_NAME).replace('\n', ' ')}\nmode=$mode\nport=$port"

    /**
     * Parses an announcement from [address]; null for anything else, another protocol version, a bad port or an
     * address outside the local network.
     */
    fun parse(text: String, address: String, nowMs: Long): Host? {
        val lines = text.split('\n')
        if (lines.firstOrNull() != MAGIC || text.length > 512) return null
        val f = lines.drop(1).mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        if (f["proto"]?.toIntOrNull() != LinkProtocol.VERSION) return null
        val port = f["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val name = f["name"]?.takeIf { n -> n.isNotBlank() && n.length <= LinkProtocol.MAX_NAME && n.none { it.isISOControl() } } ?: return null
        val mode = f["mode"]?.takeIf { it == "BATTLE" || it == "TRADE" } ?: return null
        if (!LinkAddress.isLocal(address)) return null
        return Host(name, mode, address, port, nowMs)
    }

    /** Where broadcasts go: every interface's broadcast address, the limited broadcast and this device (two instances on one machine). */
    private fun targets(): List<InetAddress> {
        val bcast = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } }
        } catch (_: Exception) { emptyList() }
        return (bcast + InetAddress.getByName("255.255.255.255") + InetAddress.getLoopbackAddress()).distinct()
    }

    private fun socket(port: Int): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true; broadcast = true; soTimeout = 250
        bind(InetSocketAddress(port))
    }

    /**
     * The host side: announces every second and answers queries until closed. [mode] is read each time, so a change
     * while waiting is announced. Failures (no network, port in use) only mean nobody finds this host by discovery.
     */
    class Announcer(private val name: String, private val port: Int, private val mode: () -> String) : Closeable {
        @Volatile private var open = true
        private val thread = Thread({ run() }, "link-announce").apply { isDaemon = true; start() }

        private fun run() {
            val sock = try { socket(QUERY_PORT) } catch (_: Exception) { null }
            val send = sock ?: try { DatagramSocket().apply { broadcast = true } } catch (_: Exception) { return }
            try {
                var next = 0L
                val buf = ByteArray(512)
                while (open) {
                    val now = System.currentTimeMillis()
                    val data = announcement(name, mode(), port).toByteArray(Charsets.UTF_8)
                    if (now >= next) {
                        next = now + PERIOD_MS
                        for (t in targets()) try { send.send(DatagramPacket(data, data.size, t, ANNOUNCE_PORT)) } catch (_: Exception) {}
                    }
                    if (sock == null) { Thread.sleep(250); continue }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        // Answer only the local network (no reflection to anywhere else).
                        val from = p.address.hostAddress
                        if (from != null && LinkAddress.isLocal(from) && String(p.data, 0, p.length, Charsets.UTF_8) == QUERY) sock.send(DatagramPacket(data, data.size, p.address, p.port))
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: java.io.IOException) { Thread.sleep(250) } // a network change: keep going
                }
            } catch (_: Exception) {
            } finally { send.close(); sock?.close() }
        }

        override fun close() { open = false }
    }

    /** The guest side: collects hosts until closed. [hosts] is safe to read from the game thread. */
    class Browser(private val clock: () -> Long = System::currentTimeMillis, listen: Boolean = true) : Closeable {
        @Volatile private var open = true
        private val seen = ConcurrentHashMap<String, Host>()
        /** False when the discovery port could not be opened (another program has it): only the typed code works. */
        @Volatile var listening = true
            private set
        init { if (listen) Thread({ run() }, "link-browse").apply { isDaemon = true; start() } }

        /** Hosts heard from recently, by name. */
        val hosts: List<Host> get() {
            val now = clock()
            seen.entries.removeIf { now - it.value.seenAtMs >= STALE_MS }
            return seen.values.sortedWith(compareBy({ it.name }, { it.address }))
        }

        /** Records [h]: one entry per address and port; the same host heard on loopback and on its LAN address is one entry. */
        internal fun record(h: Host) {
            val now = h.seenAtMs
            seen.entries.removeIf { now - it.value.seenAtMs >= STALE_MS }
            val loop = h.address.startsWith("127.")
            val twin = seen.entries.firstOrNull { (_, o) -> o.name == h.name && o.port == h.port && (o.address.startsWith("127.") != loop) }
            when {
                twin != null && loop -> seen[twin.key] = twin.value.copy(seenAtMs = now) // keep the LAN address
                twin != null -> { seen.remove(twin.key); seen["${h.address}:${h.port}"] = h }
                seen.size < MAX_HOSTS || seen.containsKey("${h.address}:${h.port}") -> seen["${h.address}:${h.port}"] = h
            }
        }

        private fun run() {
            val sock = try { socket(ANNOUNCE_PORT) } catch (_: Exception) { listening = false; return }
            try {
                var next = 0L
                val buf = ByteArray(512)
                val q = QUERY.toByteArray(Charsets.UTF_8)
                while (open) {
                    val now = clock()
                    if (now >= next) {
                        next = now + PERIOD_MS
                        for (t in targets()) try { sock.send(DatagramPacket(q, q.size, t, QUERY_PORT)) } catch (_: Exception) {}
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val addr = p.address.hostAddress ?: continue
                        parse(String(p.data, 0, p.length, Charsets.UTF_8), addr, clock())?.let { record(it) }
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: java.io.IOException) { Thread.sleep(250) } // a network change: keep going
                }
            } catch (_: Exception) {
            } finally { sock.close() }
        }

        override fun close() { open = false }
    }
}
