package pkmn.engine.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pkmn.engine.data.Dex
import pkmn.engine.mon.Pokemon
import pkmn.engine.save.PendingTrade
import pkmn.engine.save.TradeJournalState
import java.security.SecureRandom

/**
 * The messages of a link trade. They travel in the same length-prefixed frames as the lobby messages and follow
 * [LobbyStart] in trade mode on the same transport. A closed set: anything else is a malformed frame.
 */
@Serializable
sealed interface TradeMsg

/** One party member as the other player sees it before any offer: enough to draw the party list. */
@Serializable
data class TradePartyEntry(val speciesId: String, val level: Int, val nickname: String? = null, val shiny: Boolean = false)

/**
 * First message of each device: the trade journal it still has open, if any (the reconnect rule), and a summary of its
 * party so each screen can show both parties side by side.
 */
@Serializable @SerialName("sync")
data class TradeSync(val openId: String? = null, val openState: String? = null, val party: List<TradePartyEntry> = emptyList()) : TradeMsg

/** The Pokémon this device offers, and its half of the trade id. */
@Serializable @SerialName("offer")
data class TradeOffer(val mon: Pokemon, val nonce: String) : TradeMsg

/** The player accepts (or declines) the trade as shown. */
@Serializable @SerialName("confirm")
data class TradeConfirm(val accept: Boolean) : TradeMsg

/** This device has saved its journal as PREPARED. */
@Serializable @SerialName("prepared")
data class TradePrepared(val tradeId: String) : TradeMsg

/** This device has applied the swap and saved it (journal COMMITTED). */
@Serializable @SerialName("committed")
data class TradeCommitted(val tradeId: String) : TradeMsg

/** This device ends the trade before it prepared (the player backed out, a bad offer, a failed save). */
@Serializable @SerialName("cancel")
data class TradeCancel(val reason: String) : TradeMsg

/** Keeps a quiet link alive while the players choose. */
@Serializable @SerialName("beat")
object TradeHeartbeat : TradeMsg

/** Frames for [TradeMsg]: a 4-byte big-endian payload length, then JSON. Same size cap as every link frame. */
object TradeCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }

    fun encode(msg: TradeMsg): ByteArray {
        val payload = json.encodeToString(TradeMsg.serializer(), msg).toByteArray(Charsets.UTF_8)
        if (payload.size > LinkProtocol.MAX_PAYLOAD) throw LinkProtocolException(VoidReason.OVERSIZED_FRAME, "trade frame of ${payload.size} bytes")
        val out = ByteArray(LinkProtocol.HEADER_BYTES + payload.size)
        FrameCodec.writeLength(out, payload.size)
        payload.copyInto(out, LinkProtocol.HEADER_BYTES)
        return out
    }

    /** Throws [LinkProtocolException] for anything that is not one whole valid trade frame. */
    fun decode(bytes: ByteArray): TradeMsg {
        if (bytes.size < LinkProtocol.HEADER_BYTES) throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "frame shorter than its header")
        val len = FrameCodec.readLength(bytes)
        if (len < 0 || len > LinkProtocol.MAX_PAYLOAD) throw LinkProtocolException(VoidReason.OVERSIZED_FRAME, "declared length $len")
        if (bytes.size - LinkProtocol.HEADER_BYTES != len) throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "declared length $len, got ${bytes.size - LinkProtocol.HEADER_BYTES}")
        return try {
            json.decodeFromString(TradeMsg.serializer(), String(bytes, LinkProtocol.HEADER_BYTES, len, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "not a trade message: ${e.message?.take(120)}")
        } catch (e: IllegalArgumentException) {
            throw LinkProtocolException(VoidReason.MALFORMED_FRAME, "not a trade message: ${e.message?.take(120)}")
        }
    }
}

/**
 * Where the save behind a trade lives. Each call that changes the journal saves before it returns, and throws when the
 * save fails (after putting the game state back as it was). [commit] applies the swap and sets the journal to COMMITTED
 * in one atomic save: that is what makes a killed process safe.
 */
interface TradeStore {
    /** The journal in the save, or null. */
    val journal: PendingTrade?
    /** The party the player can trade from (summarised for the other device). */
    val party: List<Pokemon>
    /** Why the local Pokémon with [uid] may not be traded away, or null. */
    fun refusal(uid: Long): String?
    /** Saves [journal] (state PREPARED). The party is unchanged. */
    fun prepare(journal: PendingTrade)
    /** Applies the swap of [journal] and saves it with the journal set to COMMITTED, in one save. */
    fun commit(journal: PendingTrade)
    /** Removes the journal and saves. */
    fun clear()
    /** Resolves a journal that belongs to no running trade, as a load does; returns a sentence for the player or null. */
    fun resolveStale(): String?
}

/** Timeouts of a trade, in milliseconds of the clock passed to [TradeTransaction.pump]. */
data class TradeTimeouts(val heartbeatMs: Long = 3_000, val voidMs: Long = 30_000)

/**
 * One link trade between two devices, over a [LinkTransport] ("Trade protocol" in the link play plan):
 *
 * 1. SYNC: each device reports the journal it still has open. With the same trade id on both, a device that is still
 *    PREPARED while the other is COMMITTED completes the swap; every other open journal is resolved as on load.
 * 2. OFFER: each device sends the Pokémon it offers. A received offer must pass [MonValidator].
 * 3. CONFIRM: each player accepts or declines after seeing both offers.
 * 4. PREPARED: each device saves the journal as PREPARED, then sends PREPARED.
 * 5. On the other's PREPARED: apply the swap and mark the journal COMMITTED in one atomic save, then send COMMITTED.
 * 6. On the other's COMMITTED: clear the journal and save.
 *
 * A link lost before this device saved PREPARED cancels the trade with nothing saved. Lost after that, the journal stays
 * as it is: a PREPARED one is completed by a reconnect with the same partner, or cancelled on the next load or trade;
 * a COMMITTED one is already applied. No clock of its own: the screen calls [pump] each frame with the time.
 */
class TradeTransaction(
    val role: LinkRole,
    private val transport: LinkTransport,
    private val dex: Dex,
    private val store: TradeStore,
    val localName: String,
    val remoteName: String,
    private val timeouts: TradeTimeouts = TradeTimeouts(),
    private val nonceSource: () -> String = { java.lang.Long.toHexString(SecureRandom().nextLong()) },
) {
    enum class Stage { SYNC, OFFER, CONFIRM, PREPARED, COMMITTED, DONE }

    sealed class Result {
        /** The swap is applied and saved on this device. [confirmed]: the other device reported it too. */
        data class Completed(val received: Pokemon, val givenUid: Long, val confirmed: Boolean) : Result()
        /** Nothing changed on this device. */
        data class Cancelled(val reason: String) : Result()
        /** The link was lost after this device saved PREPARED: the trade did not happen here; the journal stays open. */
        data class Interrupted(val reason: String) : Result()
    }

    var stage = Stage.SYNC
        private set
    var result: Result? = null
        private set
    val isOver get() = result != null
    /** Something the player must be told before trading (an earlier trade finished or was cancelled at SYNC). */
    var notice: String? = null
        private set

    /** The validated offers, once each is in. */
    var localOffer: Pokemon? = null
        private set
    var remoteOffer: Pokemon? = null
        private set
    /** The other player's party summary, once its SYNC is in. */
    var remoteParty: List<TradePartyEntry> = emptyList()
        private set
    var localConfirmed = false
        private set
    var remoteConfirmed = false
        private set
    /** Set while the device saves (prepare or commit): the screen shows "Saving...". */
    val saving get() = stage == Stage.PREPARED || (stage == Stage.CONFIRM && localConfirmed && remoteConfirmed)

    private val validator = MonValidator(dex)
    private var sentSync = false
    private var remoteSynced = false
    private val localNonce = nonceSource()
    private var remoteNonce: String? = null
    private var localConfirm: Boolean? = null
    private var remoteConfirm: Boolean? = null
    private var journal: PendingTrade? = null
    private var started = false
    private var now = 0L
    private var lastSent = 0L
    private var lastRecv = 0L

    /** The trade id both devices compute from the two nonces (host first). */
    val tradeId: String? get() {
        val r = remoteNonce ?: return null
        val (h, g) = if (role == LinkRole.HOST) localNonce to r else r to localNonce
        return sha256Hex("trade|$h|$g").take(16)
    }

    /** Offers the party Pokémon [mon] (a copy is sent). Returns why not, or null. Only once, after SYNC. */
    fun offer(mon: Pokemon): String? {
        if (isOver || stage != Stage.OFFER || localOffer != null) return "Not now."
        store.refusal(mon.uid)?.let { return it }
        val copy = mon.copy()
        validator.check(copy, forTrade = true)?.let { return "That POKéMON can't be traded (${it.reason})." }
        localOffer = copy
        guarded { send(TradeOffer(copy, localNonce)); afterOffers() }
        return null
    }

    /** The local player accepts or declines the trade shown. Only once both offers are in. */
    fun confirm(accept: Boolean) {
        if (isOver || stage != Stage.CONFIRM || localConfirm != null) return
        localConfirm = accept
        localConfirmed = accept
        send(TradeConfirm(accept))
        guarded { afterConfirms() }
    }

    /**
     * The local player backs out. Allowed until this device has saved PREPARED, also after confirming (while waiting for
     * the other player): the other device prepares only after both confirms, and a Cancel that reaches it while PREPARED
     * clears its journal, because this device never sends PREPARED.
     */
    fun cancel() {
        if (isOver || stage > Stage.CONFIRM) return
        sendQuietly(TradeCancel("declined"))
        end(Result.Cancelled("You cancelled the trade."))
    }

    /** Reads what arrived and sends what is due. [nowMs] is a monotonic clock in milliseconds. */
    fun pump(nowMs: Long) {
        if (isOver) return
        now = nowMs
        if (!started) { started = true; lastSent = nowMs; lastRecv = nowMs }
        guarded {
            if (!sentSync) {
                val j = store.journal
                val party = store.party.take(6).map { TradePartyEntry(it.speciesId, it.level, it.nickname, it.shiny) }
                send(TradeSync(j?.tradeId, j?.state?.name, party)); sentSync = true
            }
            while (!isOver) {
                val bytes = transport.receive() ?: break
                lastRecv = now
                handle(TradeCodec.decode(bytes))
            }
            if (!isOver && transport.isClosed) lost("The link to $remoteName was lost.")
            if (!isOver && now - lastRecv >= timeouts.voidMs) lost("$remoteName stopped answering.")
            if (!isOver && now - lastSent >= timeouts.heartbeatMs) send(TradeHeartbeat)
        }
    }

    // ------------------------------------------------------------------ internals

    private fun handle(m: TradeMsg) {
        when (m) {
            TradeHeartbeat -> {}
            is TradeSync -> onSync(m)
            is TradeOffer -> {
                if (stage != Stage.OFFER || remoteOffer != null) protocol("offer")
                if (m.nonce.isEmpty() || m.nonce.length > 32) protocol("nonce")
                validator.check(m.mon, forTrade = true)?.let { throw LinkProtocolException(VoidReason.BAD_TEAM, "offer refused: ${it.reason}") }
                remoteOffer = m.mon; remoteNonce = m.nonce
                afterOffers()
            }
            is TradeConfirm -> {
                if (stage != Stage.CONFIRM || remoteConfirm != null) protocol("confirm")
                remoteConfirm = m.accept; remoteConfirmed = m.accept
                afterConfirms()
            }
            is TradePrepared -> {
                if (stage != Stage.PREPARED || m.tradeId != tradeId) protocol("prepared")
                val j = journal!!
                // The swap and the COMMITTED journal go into one save. If it fails, nothing changed here.
                try { store.commit(j) } catch (e: Exception) { return interrupted("The trade could not be saved (${e.message}).") }
                journal = j.copy(state = TradeJournalState.COMMITTED)
                stage = Stage.COMMITTED
                send(TradeCommitted(j.tradeId))
            }
            is TradeCommitted -> {
                if (stage != Stage.COMMITTED || m.tradeId != tradeId) protocol("committed")
                store.clear()
                end(Result.Completed(remoteOffer!!, localOffer!!.uid, confirmed = true))
            }
            is TradeCancel -> when {
                stage <= Stage.CONFIRM -> end(Result.Cancelled(if (m.reason == "declined") "$remoteName cancelled the trade." else "$remoteName ended the trade."))
                // The other device sends Cancel only before it prepared, so it never commits: the journal can go.
                stage == Stage.PREPARED -> { store.clear(); end(Result.Cancelled("$remoteName could not save the trade.")) }
                else -> protocol("cancel")
            }
        }
    }

    private fun onSync(m: TradeSync) {
        if (stage != Stage.SYNC || remoteSynced) protocol("sync")
        if (m.party.size !in 1..6) throw LinkProtocolException(VoidReason.BAD_TEAM, "party of ${m.party.size}")
        m.party.firstNotNullOfOrNull { validator.checkPartyEntry(it) }?.let { throw LinkProtocolException(VoidReason.BAD_TEAM, "party: ${it.reason}") }
        remoteSynced = true
        remoteParty = m.party
        val mine = store.journal
        if (mine != null && m.openId == mine.tradeId) {
            // The reconnect rule. Both devices see both states, so they decide the same way.
            if (mine.state == TradeJournalState.PREPARED && m.openState == TradeJournalState.COMMITTED.name) {
                notice = try {
                    store.commit(mine)
                    "The trade with ${mine.partnerName} was completed. You received ${mine.received.displayName(dex)}."
                } catch (e: Exception) {
                    // The Pokémon to give is no longer in the party: the trade cannot finish here.
                    "The trade with ${mine.partnerName} could not be finished. You kept ${mine.givenName.ifEmpty { mine.givenSpecies.uppercase() }}."
                }
            } else if (mine.state == TradeJournalState.PREPARED) {
                notice = "The trade with ${mine.partnerName} was not finished. You kept ${mine.givenName.ifEmpty { mine.givenSpecies.uppercase() }}."
            }
            store.clear()
        } else if (mine != null) {
            notice = store.resolveStale()
        }
        stage = Stage.OFFER
    }

    private fun afterOffers() {
        if (localOffer != null && remoteOffer != null && stage == Stage.OFFER) stage = Stage.CONFIRM
    }

    private fun afterConfirms() {
        if (remoteConfirm == false) return end(Result.Cancelled("$remoteName cancelled the trade."))
        if (localConfirm == false) return end(Result.Cancelled("You cancelled the trade."))
        if (localConfirm == null || remoteConfirm == null) return
        val given = localOffer!!
        val j = PendingTrade(
            tradeId = tradeId!!, state = TradeJournalState.PREPARED, partnerName = remoteName, givenUid = given.uid,
            givenSlot = -1, givenSpecies = given.speciesId, givenName = given.displayName(dex), received = remoteOffer!!.copy(),
        )
        try { store.prepare(j) } catch (e: Exception) {
            sendQuietly(TradeCancel("save failed"))
            return end(Result.Cancelled("The trade could not be saved (${e.message})."))
        }
        journal = j
        stage = Stage.PREPARED
        send(TradePrepared(j.tradeId))
    }

    private fun lost(reason: String) {
        when (stage) {
            Stage.SYNC, Stage.OFFER, Stage.CONFIRM -> end(Result.Cancelled(reason))
            Stage.PREPARED -> interrupted(reason)
            // Applied and saved here; the other device may not know yet. The journal clears on the next load or trade.
            Stage.COMMITTED -> end(Result.Completed(remoteOffer!!, localOffer!!.uid, confirmed = false))
            Stage.DONE -> {}
        }
    }

    private fun interrupted(reason: String) = end(Result.Interrupted(reason))

    private fun end(r: Result) {
        if (isOver) return
        result = r
        stage = Stage.DONE
        transport.close()
    }

    private fun send(m: TradeMsg) { transport.send(TradeCodec.encode(m)); lastSent = now }

    private fun sendQuietly(m: TradeMsg) { try { if (!transport.isClosed) send(m) } catch (_: Exception) {} }

    private fun protocol(what: String): Nothing = throw LinkProtocolException(VoidReason.PROTOCOL_ERROR, "unexpected $what in the trade")

    private fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            // A bad message (or a bug): before PREPARED the trade is cancelled on both sides; after it, as if the link was lost.
            val why = (e as? LinkProtocolException)?.reason ?: VoidReason.INTERNAL_ERROR
            if (stage <= Stage.CONFIRM) sendQuietly(TradeCancel(why.name))
            lost("The trade was stopped: ${why.name.lowercase().replace('_', ' ')}.")
        }
    }
}
