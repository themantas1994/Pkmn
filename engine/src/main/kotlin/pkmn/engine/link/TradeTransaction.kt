package pkmn.engine.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pkmn.engine.data.Dex
import pkmn.engine.mon.Pokemon
import pkmn.engine.save.PendingTrade
import pkmn.engine.save.TradeJournal
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
 * First message of each device: the trade journal it still has open, if any (the reconnect rule), a summary of its
 * party so each screen can show both parties side by side, and its trainer id, so a journal recognises the same
 * partner after a restart ([PendingTrade.partnerTrainerId]).
 */
@Serializable @SerialName("sync")
data class TradeSync(val openId: String? = null, val openState: String? = null, val party: List<TradePartyEntry> = emptyList(), val trainerId: Int? = null) : TradeMsg

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

/**
 * This device ends the trade before it prepared (the player backed out, a bad offer, a failed save), or at SYNC:
 * [BUSY] (it has an open journal with another partner) or [RESUME_FAILED] (it could not save the finish of the earlier
 * trade with this partner; its journal stays open).
 */
@Serializable @SerialName("cancel")
data class TradeCancel(val reason: String) : TradeMsg {
    companion object {
        const val DECLINED = "declined"
        const val BUSY = "busy"
        const val RESUME_FAILED = "resume failed"
    }
}

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
    /** This player's trainer id, sent at SYNC so the partner's journal can recognise this player. */
    val trainerId: Int
    /** Why the local Pokémon with [uid] may not be traded away, or null. */
    fun refusal(uid: Long): String?
    /** Saves [journal] (state PREPARED). The party is unchanged. */
    fun prepare(journal: PendingTrade)
    /**
     * Applies the swap of [journal] and saves it with the journal set to COMMITTED, in one save. [resume]: finishing an
     * open journal after a reconnect (the "last Pokémon that can battle" check, made when the offer was allowed, is
     * not repeated).
     */
    fun commit(journal: PendingTrade, resume: Boolean = false)
    /** Removes the journal and saves. */
    fun clear()
}

/** Timeouts of a trade, in milliseconds of the clock passed to [TradeTransaction.pump]. */
data class TradeTimeouts(val heartbeatMs: Long = 3_000, val voidMs: Long = 30_000)

/**
 * One link trade between two devices, over a [LinkTransport] ("Trade protocol" in the link play plan):
 *
 * 1. SYNC: each device reports the journal it still has open and its trainer id, and resolves its own journal if it was
 *    written with this partner (the rules in [onSync]). A journal with another partner is left alone and this device
 *    refuses the trade until it is resolved. A trade starts only when no journal is open on either device.
 * 2. OFFER: each device sends the Pokémon it offers. A received offer must pass [MonValidator].
 * 3. CONFIRM: each player accepts or declines after seeing both offers.
 * 4. PREPARED: each device saves the journal as PREPARED, then sends PREPARED.
 * 5. On the other's PREPARED: apply the swap and mark the journal COMMITTED in one atomic save, then send COMMITTED.
 * 6. On the other's COMMITTED: clear the journal and save.
 *
 * A link lost before this device saved PREPARED cancels the trade with nothing saved. Lost after that, the journal stays
 * as it is, through restarts, until the next SYNC with the same partner (or the player's manual way out): no device
 * ever decides alone whether a swap the other may have applied happened. No clock of its own: the screen calls [pump]
 * each frame with the time.
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
        /**
         * The session ended while a journal stays open on this device: the link was lost after this device saved
         * PREPARED (the trade did not happen here), or an earlier trade could not be resolved at SYNC.
         */
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
    /** The other player's trainer id from its SYNC (null when it sent none). */
    private var remoteTrainerId: Int? = null
    /**
     * Set at SYNC while this device holds a COMMITTED journal and the partner a PREPARED one for the same trade: the
     * partner completes the swap and sends COMMITTED, and only then may this journal go.
     */
    private var awaitingCommit: String? = null

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
        sendQuietly(TradeCancel(TradeCancel.DECLINED))
        // Leaving during SYNC never touches a journal: an earlier trade still open here waits for the next link.
        if (stage == Stage.SYNC && store.journal != null) return interrupted("You left before the earlier trade with $remoteName was resolved.")
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
                send(TradeSync(j?.tradeId, j?.state?.name, party, store.trainerId)); sentSync = true
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
                val waited = awaitingCommit
                if (stage == Stage.SYNC && waited != null) {
                    if (m.tradeId != waited) protocol("committed")
                    // The partner finished the earlier trade: both devices hold the swap, the journal can go.
                    store.clear()
                    awaitingCommit = null
                    notice = "$remoteName's game has finished your earlier trade too."
                    stage = Stage.OFFER
                    return
                }
                if (stage != Stage.COMMITTED || m.tradeId != tradeId) protocol("committed")
                store.clear()
                end(Result.Completed(remoteOffer!!, localOffer!!.uid, confirmed = true))
            }
            is TradeCancel -> when {
                stage == Stage.SYNC && awaitingCommit != null ->
                    end(Result.Interrupted("$remoteName's game could not finish your earlier trade."))
                stage <= Stage.CONFIRM -> end(Result.Cancelled(when (m.reason) {
                    TradeCancel.DECLINED -> "$remoteName cancelled the trade."
                    TradeCancel.BUSY -> "$remoteName has an unfinished trade with someone else and can't trade until it is finished."
                    TradeCancel.RESUME_FAILED -> "$remoteName's game could not finish your earlier trade."
                    else -> "$remoteName ended the trade."
                }))
                // The other device sends Cancel only before it prepared, so it never commits: the journal can go.
                stage == Stage.PREPARED -> { store.clear(); end(Result.Cancelled("$remoteName could not save the trade.")) }
                else -> protocol("cancel")
            }
        }
    }

    /**
     * The reconnect rules. Only a journal written with this partner (same name and trainer id) is resolved here; both
     * devices see both journals, so they decide the same way:
     *
     * | this device | the partner's SYNC          | outcome                                                    |
     * |-------------|-----------------------------|------------------------------------------------------------|
     * | PREPARED    | COMMITTED, same trade id    | complete the swap, send COMMITTED, clear                   |
     * | PREPARED    | PREPARED, same trade id     | cancel (both do)                                           |
     * | PREPARED    | no journal for that id      | cancel                                                     |
     * | COMMITTED   | COMMITTED, same trade id    | clear (the partner's COMMITTED has arrived)                |
     * | COMMITTED   | PREPARED, same trade id     | wait for the partner's COMMITTED, then clear               |
     * | COMMITTED   | no journal for that id      | clear (the partner has resolved it)                        |
     *
     * Cancelling a PREPARED journal when the partner holds none is safe because a COMMITTED journal is never dropped
     * without seeing the partner's state: not on load, not at a SYNC with someone else, and with this partner only when
     * the partner is COMMITTED too or has no journal for that id. A partner that has none therefore never applied the
     * swap or has already seen this device finish. (The exceptions are outside the protocol: the manual way out, a save
     * rolled back to its backup, a new game over the save.) A failed save while resolving ends the session with the
     * journal still open. A journal with another partner stays open and this device refuses the trade ([TradeCancel.BUSY]).
     */
    private fun onSync(m: TradeSync) {
        if (stage != Stage.SYNC || remoteSynced) protocol("sync")
        if (m.party.size !in 1..6) throw LinkProtocolException(VoidReason.BAD_TEAM, "party of ${m.party.size}")
        m.party.firstNotNullOfOrNull { validator.checkPartyEntry(it) }?.let { throw LinkProtocolException(VoidReason.BAD_TEAM, "party: ${it.reason}") }
        remoteSynced = true
        remoteParty = m.party
        remoteTrainerId = m.trainerId
        val mine = store.journal
        if (mine != null && TradeJournal.isPartner(mine, remoteName, m.trainerId)) {
            val peerState = if (m.openId == mine.tradeId) m.openState else null
            val kept = TradeJournal.givenName(mine)
            when (mine.state) {
                TradeJournalState.PREPARED -> when (peerState) {
                    TradeJournalState.COMMITTED.name -> {
                        try { store.commit(mine, resume = true) } catch (e: Exception) {
                            // Not applied here (the store restored memory); the partner keeps its COMMITTED and waits.
                            sendQuietly(TradeCancel(TradeCancel.RESUME_FAILED))
                            return interrupted("The trade with $remoteName could not be finished here (${e.message}). Link again to try once more.")
                        }
                        send(TradeCommitted(mine.tradeId))
                        store.clear()
                        notice = "The trade with $remoteName was completed. You received ${mine.received.displayName(dex)}."
                    }
                    TradeJournalState.PREPARED.name -> {
                        store.clear()
                        notice = "The interrupted trade with $remoteName didn't happen on either side. You kept $kept."
                    }
                    else -> {
                        store.clear()
                        notice = "The interrupted trade with $remoteName didn't happen. You kept $kept."
                    }
                }
                TradeJournalState.COMMITTED -> {
                    if (peerState == TradeJournalState.PREPARED.name) { awaitingCommit = mine.tradeId; return }
                    store.clear()
                }
            }
        }
        store.journal?.let { open ->
            sendQuietly(TradeCancel(TradeCancel.BUSY))
            return end(Result.Cancelled("You have an unfinished trade with ${open.partnerName}. Link with ${open.partnerName} to finish it, or end it from the LINK menu."))
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
            partnerTrainerId = remoteTrainerId,
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
            // A journal still open here (waiting for the partner, or a resolve whose save failed) stays for the next link.
            Stage.SYNC -> if (store.journal != null) interrupted(reason) else end(Result.Cancelled(reason))
            Stage.OFFER, Stage.CONFIRM -> end(Result.Cancelled(reason))
            Stage.PREPARED -> interrupted(reason)
            // Applied and saved here; the other device may not know yet. The journal stays until the next link with it.
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
