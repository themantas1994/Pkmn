package pkmn.engine.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pkmn.engine.battle.BattleFormat
import pkmn.engine.battle.BattleRules
import pkmn.engine.battle.Choice
import pkmn.engine.battle.Gimmick
import pkmn.engine.mon.Pokemon
import java.security.MessageDigest

/** Constants of the link protocol. */
object LinkProtocol {
    /** Bumped whenever a message or a rule of the exchange changes; both devices must run the same one. */
    const val VERSION = 2

    /** Bytes in the length prefix of a frame. */
    const val HEADER_BYTES = 4

    /**
     * Largest frame payload accepted or produced. Six full Pokémon serialize to a few kilobytes, so this leaves room
     * without letting the other device make us buffer megabytes (the reader checks the prefix before it buffers).
     */
    const val MAX_PAYLOAD = 64 * 1024

    /** Longest accepted trainer name. */
    const val MAX_NAME = 24
}

/** Why a link session ended without a result. The same set is sent to the other device in [Abort]. */
enum class VoidReason {
    /** The connection closed or was cut. */
    LINK_LOST,
    /** Nothing arrived for too long (a silent cut, or the other device froze). */
    TIMEOUT,
    PROTOCOL_VERSION,
    GAME_VERSION,
    DATA_MISMATCH,
    RULES_MISMATCH,
    /** A frame that is not valid JSON of a known message, or has the wrong length. */
    MALFORMED_FRAME,
    /** A frame larger than [LinkProtocol.MAX_PAYLOAD]. */
    OVERSIZED_FRAME,
    /** A valid message at the wrong time, repeated, out of order or missing a predecessor. */
    PROTOCOL_ERROR,
    /** The other device's team is not acceptable. */
    BAD_TEAM,
    /** A reveal that does not match its commitment. */
    COMMIT_MISMATCH,
    /** The other device chose something the battle does not allow. */
    ILLEGAL_CHOICE,
    /** The devices computed different battles. */
    HASH_MISMATCH,
    /** The other device ended the session; its own reason is in the detail. */
    REMOTE_ABORT,
    /** A bug on this device. */
    INTERNAL_ERROR,
}

/** A session that cannot go on: [reason] is what is reported, [message] is for logs. */
class LinkProtocolException(val reason: VoidReason, message: String) : Exception(message)

/**
 * The battle rules both devices must agree on, in wire form. [toBattleRules] builds [BattleRules.link], so any rule the
 * link does not support is rejected in one place. Every field is compared on both devices during the handshake.
 */
@Serializable
data class LinkRules(
    /** Allowed gimmicks by [Gimmick] name, sorted. */
    val gimmicks: List<String> = emptyList(),
    val oneGimmickPerBattle: Boolean = true,
    val levelCap: Int = 100,
    val inverse: Boolean = false,
    val format: String = BattleFormat.SINGLES.name,
    /** Most Pokémon one trainer may bring. */
    val maxTeam: Int = 6,
) {
    /** Null when the rules can be played, otherwise why not. */
    fun error(): String? {
        if (gimmicks != gimmicks.distinct().sorted()) return "gimmicks must be sorted and unique"
        if (gimmicks.any { n -> Gimmick.entries.none { it.name == n } }) return "unknown gimmick"
        if (levelCap !in 1..100) return "level cap must be 1..100"
        if (maxTeam !in 1..6) return "team size must be 1..6"
        val f = BattleFormat.entries.firstOrNull { it.name == format } ?: return "unknown format"
        if (f != BattleFormat.SINGLES) return "only singles can be played over a link yet"
        return null
    }

    fun toBattleRules(): BattleRules {
        error()?.let { throw IllegalArgumentException(it) }
        return BattleRules.link(
            allowedGimmicks = gimmicks.map { Gimmick.valueOf(it) }.toSet(), oneGimmickPerBattle = oneGimmickPerBattle,
            levelCap = levelCap, inverse = inverse, format = BattleFormat.valueOf(format),
        )
    }

    companion object {
        fun of(rules: BattleRules, maxTeam: Int = 6): LinkRules {
            require(rules.link) { "Not link rules" }
            return LinkRules(
                gimmicks = rules.allowedGimmicks.map { it.name }.sorted(), oneGimmickPerBattle = rules.oneGimmickPerBattle,
                levelCap = rules.levelCap, inverse = rules.inverse, format = rules.format.name, maxTeam = maxTeam,
            )
        }
    }
}

/** A choice on the wire. Only moves, switches, passes and forfeits exist in a link battle: no bag, no running. */
@Serializable
data class WireChoice(val slot: Int, val kind: String, val index: Int = 0, val gimmick: String? = null, val target: Int = 0) {
    /** Text that goes into the commitment hash: one fixed form per choice. */
    fun canonical() = "$slot,$kind,$index,${gimmick ?: "-"},$target"

    fun toChoice(): Choice = when (kind) {
        "move" -> Choice.UseMove(index, gimmick?.let { g -> Gimmick.entries.firstOrNull { it.name == g } ?: throw LinkProtocolException(VoidReason.ILLEGAL_CHOICE, "unknown gimmick") }, target)
        "switch" -> Choice.Switch(index)
        "pass" -> Choice.Pass
        "forfeit" -> Choice.Forfeit
        else -> throw LinkProtocolException(VoidReason.ILLEGAL_CHOICE, "unknown choice kind")
    }

    companion object {
        /** Null for a choice that cannot be sent (bag item, run). */
        fun of(slot: Int, c: Choice): WireChoice? = when (c) {
            is Choice.UseMove -> WireChoice(slot, "move", c.moveIndex, c.gimmick?.name, c.target)
            is Choice.Switch -> WireChoice(slot, "switch", c.teamIndex)
            Choice.Pass -> WireChoice(slot, "pass")
            Choice.Forfeit -> WireChoice(slot, "forfeit")
            is Choice.UseItem, Choice.Run -> null
        }
    }
}

/** The closed set of messages. Anything else on the wire is a malformed frame. */
@Serializable
sealed interface LinkMessage

/** First message from each device: who, which build, which data, which rules. All must match before anything else happens. */
@Serializable @SerialName("hello")
data class Hello(val protocol: Int, val gameVersion: String, val dataHash: String, val name: String, val rules: LinkRules) : LinkMessage

/** A team (full-health copies) and the commitment to this device's share of the battle seed. */
@Serializable @SerialName("team")
data class TeamMsg(val mons: List<Pokemon>, val seedCommit: String) : LinkMessage

/** Reveals the seed share behind [TeamMsg.seedCommit]. */
@Serializable @SerialName("seed")
data class SeedReveal(val part: Long, val nonce: String) : LinkMessage

/** Hash of this device's choices for decision point [point], sent before it learns the other device's choices. */
@Serializable @SerialName("commit")
data class Commit(val point: Int, val digest: String) : LinkMessage

/** The choices behind [Commit.digest], with the nonce that was hashed with them. */
@Serializable @SerialName("reveal")
data class Reveal(val point: Int, val choices: List<WireChoice>, val nonce: String) : LinkMessage

/** The battle state after decision point [point] (-1: after the start), compared with the other device's. */
@Serializable @SerialName("state")
data class StateMsg(val point: Int, val turn: Int, val hash: String, val over: Boolean) : LinkMessage

/** Sent when nothing else was, so silence means a dead link. */
@Serializable @SerialName("beat")
object Heartbeat : LinkMessage

/** The app on the sending device went to the background (Android). The other device shows that instead of a dead link; no grace period is added. */
@Serializable @SerialName("pause")
object PauseNotice : LinkMessage

/** Ends the session on purpose; [reason] is a [VoidReason] name. */
@Serializable @SerialName("abort")
data class Abort(val reason: String, val detail: String = "") : LinkMessage

/** A message with its position in the sender's stream, so a duplicated or missing frame is noticed. */
@Serializable
data class Frame(val seq: Long, val msg: LinkMessage)

/** Frames: a 4-byte big-endian payload length, then the JSON of a [Frame]. */
object FrameCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(frame: Frame): ByteArray {
        val payload = json.encodeToString(Frame.serializer(), frame).toByteArray(Charsets.UTF_8)
        if (payload.size > LinkProtocol.MAX_PAYLOAD)
            throw LinkProtocolException(VoidReason.OVERSIZED_FRAME, "frame of ${payload.size} bytes exceeds ${LinkProtocol.MAX_PAYLOAD}")
        val out = ByteArray(LinkProtocol.HEADER_BYTES + payload.size)
        writeLength(out, payload.size)
        payload.copyInto(out, LinkProtocol.HEADER_BYTES)
        return out
    }

    /** [bytes] is one whole frame, prefix included. Throws [LinkProtocolException] for anything that is not a valid frame. */
    fun decode(bytes: ByteArray): Frame {
        if (bytes.size < LinkProtocol.HEADER_BYTES) throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "frame shorter than its header")
        val len = readLength(bytes)
        if (len < 0 || len > LinkProtocol.MAX_PAYLOAD) throw LinkProtocolException(VoidReason.OVERSIZED_FRAME, "declared length $len")
        if (bytes.size - LinkProtocol.HEADER_BYTES != len) throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "declared length $len, got ${bytes.size - LinkProtocol.HEADER_BYTES}")
        return try {
            json.decodeFromString(Frame.serializer(), String(bytes, LinkProtocol.HEADER_BYTES, len, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "not a valid message: ${e.message?.take(120)}")
        } catch (e: IllegalArgumentException) {
            throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "not a valid message: ${e.message?.take(120)}")
        }
    }

    internal fun writeLength(out: ByteArray, n: Int) { for (i in 0 until 4) out[i] = (n ushr (8 * (3 - i))).toByte() }
    internal fun readLength(b: ByteArray): Int = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
}

/**
 * Cuts a byte stream into frames, for a transport that reads from a socket. The length prefix is checked as soon as its
 * four bytes are in, so an oversized frame is refused before any of its payload is buffered.
 *
 * Frames that are complete before a bad prefix are still returned: a valid frame that shares a read with the bad prefix
 * that ends the link must not be lost. The error is then held in [error] and thrown by the next [feed].
 */
class FrameBuffer {
    private var buf = ByteArray(0)

    /** Set once a bad prefix was seen; the stream cannot be read past it. */
    var error: LinkProtocolException? = null
        private set

    /**
     * Adds received bytes; returns every frame that is now complete. On an oversized prefix, returns the frames before it
     * and sets [error]; throws [LinkProtocolException] when no frame comes first, or on any call after [error] is set.
     */
    fun feed(chunk: ByteArray): List<ByteArray> {
        error?.let { throw it }
        buf += chunk
        val out = ArrayList<ByteArray>()
        while (buf.size >= LinkProtocol.HEADER_BYTES) {
            val len = FrameCodec.readLength(buf)
            if (len < 0 || len > LinkProtocol.MAX_PAYLOAD) {
                val e = LinkProtocolException(VoidReason.OVERSIZED_FRAME, "declared length $len")
                error = e
                buf = ByteArray(0)
                if (out.isEmpty()) throw e
                return out
            }
            val total = LinkProtocol.HEADER_BYTES + len
            if (buf.size < total) break
            out.add(buf.copyOfRange(0, total))
            buf = buf.copyOfRange(total, buf.size)
        }
        return out
    }
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
