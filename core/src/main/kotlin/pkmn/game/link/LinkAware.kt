package pkmn.game.link

import java.net.Inet4Address
import java.net.NetworkInterface

/** Implemented by screens that hold a link session, so the app can tell them it is going to the background. */
interface LinkAware {
    fun onAppPaused()
    /** Called every frame while this screen is under another one: pump the session. */
    fun linkTick() {}
}

/** The device's IPv4 addresses on the local network (site-local ones first), from `java.net` alone. */
object LocalAddresses {
    fun ipv4(): List<String> {
        val all = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { runCatching { it.isUp && !it.isVirtual }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        } catch (_: Exception) { emptyList() }
        val lan = all.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        // Private ranges (the local network) before anything else, so the first code shown is the one a friend can reach.
        val sorted = lan.sortedBy { if (it.isSiteLocalAddress) 0 else 1 }.mapNotNull { it.hostAddress }.distinct()
        return sorted.ifEmpty { listOf("127.0.0.1") }
    }
}
