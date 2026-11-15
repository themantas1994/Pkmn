package pkmn.game.link

import pkmn.engine.link.LinkProtocol
import kotlin.test.*

/** The announcement a host broadcasts and what the guest's list accepts from it. */
class LinkDiscoveryTest {
    @Test
    fun `an announcement is read back with the sender's address`() {
        val text = LinkDiscovery.announcement("ARI", "TRADE", 47900)
        val h = assertNotNull(LinkDiscovery.parse(text, "192.168.1.20", 5))
        assertEquals(LinkDiscovery.Host("ARI", "TRADE", "192.168.1.20", 47900, 5), h)
    }

    @Test
    fun `anything else is ignored`() {
        val ok = LinkDiscovery.announcement("ARI", "BATTLE", 47840)
        assertNull(LinkDiscovery.parse(ok, "8.8.8.8", 0), "not on the local network")
        assertNull(LinkDiscovery.parse(ok.replace("proto=${LinkProtocol.VERSION}", "proto=1"), "10.0.0.2", 0), "another link version")
        assertNull(LinkDiscovery.parse(ok.replace("port=47840", "port=0"), "10.0.0.2", 0))
        assertNull(LinkDiscovery.parse(ok.replace("mode=BATTLE", "mode=RACE"), "10.0.0.2", 0))
        assertNull(LinkDiscovery.parse(ok.replace("name=ARI", "name=\u0007"), "10.0.0.2", 0))
        assertNull(LinkDiscovery.parse("PKMN-LINK?", "10.0.0.2", 0), "a query is not a host")
        assertNull(LinkDiscovery.parse("hello", "10.0.0.2", 0))
        assertNull(LinkDiscovery.parse(ok + "\n" + "x".repeat(600), "10.0.0.2", 0), "too long")
    }

    @Test
    fun `a long name is cut to the link limit`() {
        val h = assertNotNull(LinkDiscovery.parse(LinkDiscovery.announcement("A".repeat(40), "BATTLE", 1), "10.0.0.2", 0))
        assertEquals(LinkProtocol.MAX_NAME, h.name.length)
    }

    @Test
    fun `the list keeps one entry per host, merges loopback with the LAN address, drops silent hosts and is capped`() {
        var now = 0L
        val b = LinkDiscovery.Browser({ now }, listen = false)
        b.record(LinkDiscovery.Host("ARI", "BATTLE", "192.168.1.20", 47840, now))
        b.record(LinkDiscovery.Host("ARI", "TRADE", "192.168.1.21", 47840, now))
        assertEquals(listOf("192.168.1.20", "192.168.1.21"), b.hosts.map { it.address }, "two hosts with the same name stay apart")
        b.record(LinkDiscovery.Host("ARI", "BATTLE", "127.0.0.1", 47840, now))
        assertEquals(2, b.hosts.size, "loopback copy of a LAN host merges into it")
        assertTrue(b.hosts.none { it.address.startsWith("127.") })
        now = LinkDiscovery.STALE_MS + 1
        assertTrue(b.hosts.isEmpty(), "silent hosts drop off")
        repeat(40) { i -> b.record(LinkDiscovery.Host("H$i", "BATTLE", "10.0.0.${i + 1}", 47840, now)) }
        assertEquals(LinkDiscovery.MAX_HOSTS, b.hosts.size)
    }
}

