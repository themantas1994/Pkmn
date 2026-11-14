package pkmn.engine.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pkmn.engine.battle.BattleFormat
import pkmn.engine.battle.Gimmick
import pkmn.engine.data.Dex
import pkmn.engine.mon.Pokemon

/**
 * The messages two devices exchange in the lobby, before the battle starts. They travel in the same length-prefixed
 * frames as [LinkMessage] (so one transport carries both) but are a separate closed set: [LockstepBattle] only ever
 * sees frames sent after [LobbyStart], and a lobby frame arriving mid-battle is a malformed frame there.
 */
@Serializable
sealed interface LobbyMsg

/** First message of each device: who it is and what it can play. Both sides compare version and data right away. */
@Serializable @SerialName("hi")
data class LobbyHi(val protocol: Int, val gameVersion: String, val dataHash: String, val name: String, val gimmicks: List<String>, val partySize: Int) : LobbyMsg

/** The host's current rule choice. [flat] raises or lowers every Pokémon to level 50 ([LinkRules.levelCap] is then 50). */
@Serializable @SerialName("rules")
data class LobbyRules(val rules: LinkRules, val flat: Boolean) : LobbyMsg

/** The guest is (not) ready to start with the rules shown. */
@Serializable @SerialName("ready")
data class LobbyReady(val ready: Boolean, val rulesKey: String = "") : LobbyMsg

/** The host starts the battle with exactly these rules; the battle exchange ([Hello]) follows on the same transport. */
@Serializable @SerialName("start")
data class LobbyStart(val rules: LinkRules, val flat: Boolean) : LobbyMsg

/** Leaving the lobby on purpose. */
@Serializable @SerialName("leave")
object LobbyLeave : LobbyMsg

/** Frames for [LobbyMsg]: a 4-byte big-endian payload length, then JSON. Same size cap as the battle frames. */
object LobbyCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(msg: LobbyMsg): ByteArray {
        val payload = json.encodeToString(LobbyMsg.serializer(), msg).toByteArray(Charsets.UTF_8)
        if (payload.size > LinkProtocol.MAX_PAYLOAD) throw LinkProtocolException(VoidReason.OVERSIZED_FRAME, "lobby frame of ${payload.size} bytes")
        val out = ByteArray(LinkProtocol.HEADER_BYTES + payload.size)
        FrameCodec.writeLength(out, payload.size)
        payload.copyInto(out, LinkProtocol.HEADER_BYTES)
        return out
    }

    /** Throws [LinkProtocolException] for anything that is not one whole valid lobby frame. */
    fun decode(bytes: ByteArray): LobbyMsg {
        if (bytes.size < LinkProtocol.HEADER_BYTES) throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "frame shorter than its header")
        val len = FrameCodec.readLength(bytes)
        if (len < 0 || len > LinkProtocol.MAX_PAYLOAD) throw LinkProtocolException(VoidReason.OVERSIZED_FRAME, "declared length $len")
        if (bytes.size - LinkProtocol.HEADER_BYTES != len) throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "declared length $len, got ${bytes.size - LinkProtocol.HEADER_BYTES}")
        return try {
            json.decodeFromString(LobbyMsg.serializer(), String(bytes, LinkProtocol.HEADER_BYTES, len, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "not a lobby message: ${e.message?.take(120)}")
        } catch (e: IllegalArgumentException) {
            throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "not a lobby message: ${e.message?.take(120)}")
        }
    }
}

/** An IPv4 address and port to connect to. */
data class LinkEndpoint(val host: String, val port: Int)

/**
 * The short code a host shows: its IPv4 address as 7 characters (32 bits in base 32). The alphabet leaves out 0, 1, I and
 * O so a code read aloud or typed on the name keyboard cannot be confused. The port is the fixed [DEFAULT_PORT]: a code
 * names a device, never a port. Anything that looks like a dotted address ("192.168.0.7", "192.168.0.7:47840") is also
 * accepted, as the manual fallback.
 */
object LinkAddress {
    const val DEFAULT_PORT = 47840
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 7

    fun encode(ipv4: String): String? {
        val o = octets(ipv4) ?: return null
        var v = 0L
        for (x in o) v = (v shl 8) or x.toLong()
        val sb = StringBuilder()
        repeat(CODE_LENGTH) { sb.append(ALPHABET[(v and 31).toInt()]); v = v shr 5 }
        return sb.reverse().toString()
    }

    fun decode(code: String): String? {
        val c = code.trim().uppercase()
        if (c.length != CODE_LENGTH) return null
        var v = 0L
        for (ch in c) { val i = ALPHABET.indexOf(ch); if (i < 0) return null; v = (v shl 5) or i.toLong() }
        if (v > 0xFFFF_FFFFL) return null
        return "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
    }

    /** A typed code or a dotted address with an optional ":port". Null when it is neither. */
    fun parse(text: String): LinkEndpoint? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if ('.' in t) {
            val host = t.substringBefore(':')
            val port = if (':' in t) t.substringAfter(':').toIntOrNull() ?: return null else DEFAULT_PORT
            if (octets(host) == null || port !in 1..65535) return null
            return LinkEndpoint(host, port)
        }
        return decode(t)?.let { LinkEndpoint(it, DEFAULT_PORT) }
    }

    /** Whether [ip] is on a local network: private ranges (10/8, 172.16/12, 192.168/16), link-local (169.254/16) or loopback. Link play connects nowhere else. */
    fun isLocal(ip: String): Boolean {
        val o = octets(ip) ?: return false
        return o[0] == 10 || o[0] == 127 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) || (o[0] == 169 && o[1] == 254)
    }

    private fun octets(ip: String): List<Int>? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val o = parts.map { p -> if (p.isEmpty() || p.length > 3 || p.any { !it.isDigit() }) return null else p.toInt() }
        return if (o.all { it in 0..255 }) o else null
    }
}

/** What the lobby turns into battle input: rules from the host's choices and the local party as healed battle copies. */
object LinkLobbyRules {
    const val FLAT_LEVEL = 50

    /** Rules for a battle: [gimmicks] are the ones both players have unlocked (the host decides whether to allow them). */
    fun rules(flat: Boolean, gimmicks: Set<Gimmick>): LinkRules = LinkRules(
        gimmicks = gimmicks.map { it.name }.sorted(), levelCap = if (flat) FLAT_LEVEL else 100,
        format = BattleFormat.SINGLES.name,
    )

    /** Gimmicks present in both lists, ignoring names this build does not know. */
    fun common(a: Collection<String>, b: Collection<String>): Set<Gimmick> =
        Gimmick.entries.filter { it.name in a && it.name in b }.toSet()

    /**
     * Healed copies of the party for a link battle, at most six. With [flat] every Pokémon is set to level 50 (up or
     * down, with the experience of that level). Nothing here touches the originals, so a link battle leaves the save alone.
     */
    fun team(dex: Dex, party: List<Pokemon>, flat: Boolean): List<Pokemon> = party.take(6).map { p ->
        p.copy().also { m ->
            if (flat) { m.level = FLAT_LEVEL; m.exp = m.species(dex).growthRate.expForLevel(FLAT_LEVEL) }
            m.healFully(dex)
        }
    }
}
