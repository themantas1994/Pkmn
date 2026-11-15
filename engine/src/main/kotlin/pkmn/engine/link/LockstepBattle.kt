package pkmn.engine.link

import pkmn.engine.battle.Battle
import pkmn.engine.battle.BattleOutcome
import pkmn.engine.battle.Choice
import pkmn.engine.battle.Controller
import pkmn.engine.battle.Gimmick
import pkmn.engine.battle.Request
import pkmn.engine.battle.Side
import pkmn.engine.data.Dex
import pkmn.engine.mon.Pokemon
import java.security.SecureRandom

/** Which device this is. The host is side 0 on both devices and the guest is side 1: random draws depend on that order. */
enum class LinkRole { HOST, GUEST }

/** How long silence is tolerated, in milliseconds of the clock passed to [LockstepBattle.pump]. */
data class LinkTimeouts(
    /** Send a heartbeat when nothing else was sent for this long. */
    val heartbeatMs: Long = 3_000,
    /** After this much silence the UI may show "Waiting for ...". See [LockstepBattle.silentForMs]. */
    val silenceNoticeMs: Long = 10_000,
    /** After this much silence the battle is void (before it started), or the link counts as lost (after: see [graceMs]). */
    val voidMs: Long = 60_000,
    /**
     * Once the battle has started, a lost link (closed, or silent for [silenceNoticeMs]) pauses it for this long while
     * the owner reconnects ([LockstepBattle.resume]); after that it is void.
     */
    val graceMs: Long = 20_000,
    /** A connection offered during the grace period must bring a valid Resume within this time, or it is dropped. */
    val resumeAnswerMs: Long = 3_000,
    /**
     * After this long without a real message (heartbeats and repeats do not count) from a device that owes us something
     * (a team, a choice, a state), the battle is void. Covers a peer that only sends heartbeats. Generous: it includes
     * the other player's thinking time.
     */
    val decisionMs: Long = 180_000,
)

/** What both devices must agree on, and the local build's identity. */
class LinkConfig(
    val gameVersion: String,
    val rules: LinkRules,
    val timeouts: LinkTimeouts = LinkTimeouts(),
    /** [LinkFingerprint.dataHash] of the local content; computed from the Dex when null. */
    val dataHash: String? = null,
)

/** How a link session ended. */
sealed interface LinkResult {
    // Two-generals note: if the very last frame of a battle is lost, one device can end with Completed while the other
    // voids. The two never end with different results, but a caller must not write anything permanent from Completed
    // that the other side could not also write (Phase 2 writes nothing at all).
    /**
     * Both devices ended the battle in the same state (equal hashes). [outcome] is seen from side 0, the host, on both devices:
     * [BattleOutcome.WIN] means the host won; [winnerSide] is 0, 1 or null for a draw.
     */
    data class Completed(val outcome: BattleOutcome, val winnerSide: Int?, val hash: String) : LinkResult

    /** No result: nobody wins or loses and nothing is written anywhere. */
    data class Voided(val reason: VoidReason, val detail: String) : LinkResult
}

/** A decision this device still owes the battle. */
data class LocalDecision(val slot: Int, val request: Request)

/**
 * One device's end of a lockstep link battle (docs: Phase 2 of the link play plan). Both devices build the same [Battle]
 * from the same teams, rules and seed and exchange only choices; each runs the turns itself.
 *
 * Exchange: [Hello] (version, data hash, full rules, name) on both devices; [TeamMsg] with a commitment to a seed share;
 * [SeedReveal]; then for every decision point [Commit] (hash of my choices), [Reveal] (the choices), and after both
 * were applied [StateMsg] (state hash). A mismatch, a lost link, a malformed frame or an illegal choice voids the
 * battle; nothing is written anywhere, so a void battle costs nothing.
 *
 * With [LinkRules.pick] ("three against three") each player picks from both teams after [TeamMsg]: [PickCommit] on
 * both devices first, then [PickReveal], then [SeedReveal]; the battle uses the picked Pokémon in the order picked.
 *
 * Once both teams are in (also while picking), a lost link does not void the session at once: the battle pauses ([reconnecting]) for
 * [LinkTimeouts.graceMs] while the owner opens a new connection to the same peer and hands it to [resume] (the host
 * listens again, the guest connects again). The guest sends [Resume] (session token, next expected frame) first; the
 * host answers with its own only after the token matched, so a stranger who connects learns nothing and is dropped
 * without ending the battle ([needsTransport] asks the owner for another connection). Each device then repeats every
 * frame the other has not seen, so the battle goes on at the same point and the state hashes are compared as before.
 *
 * Not thread-safe and not blocking: the owner calls [pump] regularly (for example every frame) with the current time in
 * milliseconds, and [submit] when the local player has chosen. All I/O goes through the [LinkTransport].
 */
class LockstepBattle(
    val role: LinkRole,
    private var transport: LinkTransport,
    private val dex: Dex,
    private val config: LinkConfig,
    localTeam: List<Pokemon>,
    val localName: String,
    /** Source of the seed share and nonces. Real play uses the default; tests pass a seeded one. */
    private val entropy: () -> Long = SecureRandom()::nextLong,
) {
    private enum class Stage { HANDSHAKE, PLAY, DONE }
    private companion object { const val MAX_LEAD = 4 }

    /** One decision point: every side that must choose before the battle can go on. */
    private class Point(val n: Int, val localSlots: List<Int>, val remoteSlots: List<Int>) {
        val localChoices = HashMap<Int, Choice>()
        var nonce = ""
        var commitSent = false
        var revealSent = false
        var remoteApplied = false
    }

    private val localSide = if (role == LinkRole.HOST) 0 else 1
    private val remoteSide = 1 - localSide
    private val rules = config.rules.also { r -> r.error()?.let { throw IllegalArgumentException("unplayable link rules: $it") } }
    private val dataHash = config.dataHash ?: LinkFingerprint.dataHash(dex)
    private val timeouts = config.timeouts

    // The teams: this device heals a copy of every Pokémon it receives, so nobody relies on the sender's HP or PP.
    private val validator = MonValidator(dex)
    private val localMons: List<Pokemon> = localTeam.map { it.copy().also { m -> m.healFully(dex) } }
        .also { t -> validator.checkTeam(rules, t)?.let { throw IllegalArgumentException("local team rejected: $it") } }
    private val seedPart = entropy()
    private val seedNonce = entropy().toString(16)

    private var stage = Stage.HANDSHAKE
    var result: LinkResult? = null
        private set
    val isOver get() = result != null

    /** The shared battle, once both teams and the seed are in. Read it to draw the screen; change it only through [submit]. */
    var battle: Battle? = null
        private set
    var remoteName: String? = null
        private set

    // Stream state. Every numbered frame sent is kept so it can be sent again after a reconnect.
    private val sentFrames = ArrayList<ByteArray>()
    private var sendSeq = 0L
    private var recvSeq = 0L
    private var started = false
    private var lastSent = 0L
    private var lastRecv = 0L
    private var now = 0L
    private var lastProgress = 0L
    private var owedSince = -1L

    // Handshake state.
    private var sentHello = false
    private var sentTeam = false
    private var sentSeed = false
    private var remoteHello: Hello? = null
    private var remoteMons: List<Pokemon>? = null
    private var remoteSeedCommit: String? = null
    private var remoteSeed: SeedReveal? = null

    // Play state.
    private var point: Point? = null
    private var nextPoint = 0
    private val remoteCommits = HashMap<Int, Commit>()
    private val remoteReveals = HashMap<Int, Reveal>()
    private val remoteStates = HashMap<Int, StateMsg>()
    private val localStates = HashMap<Int, StateMsg>()
    private var lastStatePoint = -2

    // Picks ("three against three").
    private var localPick: List<Int>? = null
    private val pickNonce = entropy().toString(16) + entropy().toString(16)
    private var sentPickCommit = false
    private var sentPickReveal = false
    private var remotePickCommit: String? = null
    private var remotePick: List<Int>? = null

    // Reconnect.
    /** True while the link is lost and the battle waits for [resume]; it is void after [LinkTimeouts.graceMs]. */
    var reconnecting = false
        private set
    private var lostAt = 0L
    private var resumeSent = false
    /** When the connection being tried was handed to [resume]. */
    private var offeredAt = 0L
    /** Times the battle went on after a lost link. */
    var resumes = 0
        private set

    /** How many Pokémon each player picks (0: the whole team). */
    val pickCount get() = rules.pick
    /** This device's team (healed copies), in team order: what [pick] indexes. */
    val localTeam: List<Pokemon> get() = localMons
    /** The other device's whole team, once it arrived (shown while picking). */
    val remoteTeam: List<Pokemon>? get() = remoteMons
    /** True while the local player must pick ([pick]): both teams are in and this device has not picked yet. */
    val awaitingLocalPick get() = !isOver && rules.pick > 0 && remoteMons != null && localPick == null
    /** True once this device has picked and waits for the other player's picks. */
    val awaitingRemotePick get() = !isOver && rules.pick > 0 && localPick != null && remotePick == null

    /** True while [reconnecting] and the connection to try is gone: the owner should listen or connect again. */
    val needsTransport get() = reconnecting && !isOver && transport.isClosed

    /** Milliseconds of the grace period left while [reconnecting]. */
    fun reconnectLeftMs(): Long = if (reconnecting) (timeouts.graceMs - (now - lostAt)).coerceAtLeast(0) else 0

    /**
     * The local player's picks: [LinkRules.pick] distinct team indices, in battle order. Returns null when accepted,
     * otherwise why not. Nothing about them leaves this device but a hash until both devices have committed.
     */
    fun pick(indices: List<Int>): String? {
        if (!awaitingLocalPick) return "Nothing to pick now"
        if (indices.size != rules.pick || indices.distinct().size != indices.size || indices.any { it !in localMons.indices }) return "Pick ${rules.pick} different Pokémon"
        localPick = indices.toList()
        guarded { progress() }
        return null
    }

    /**
     * True once both teams (and so both seed commitments) are in: from then on a lost link pauses instead of voiding,
     * also while the players pick ("three against three").
     */
    val canResume get() = remoteSeedCommit != null && sentTeam

    /**
     * A new connection to the same peer after the link was lost: the session goes on over [t] once both devices have
     * exchanged [Resume]. Only once [canResume]; ignored once it is over. A connection that brings no valid [Resume]
     * within [LinkTimeouts.resumeAnswerMs] is closed, so a silent stranger cannot hold the host's only slot.
     */
    fun resume(t: LinkTransport) {
        if (isOver || !canResume) { t.close(); return }
        try { transport.close() } catch (_: Exception) {}
        transport = t
        if (!reconnecting) { reconnecting = true; lostAt = now }
        offeredAt = now
        resumeSent = false
        // The guest speaks first; the host answers only a valid Resume.
        if (role == LinkRole.GUEST) guarded { sendResume() }
    }

    /** True from a [PauseNotice] of the other device until it sends anything else. */
    var remotePaused = false
        private set

    /** The app went to the background: tells the other device, so its player sees "paused" rather than a silent link. */
    fun notifyPaused() { if (!isOver && started) guarded { send(PauseNotice) } }

    /** Milliseconds since anything arrived from the other device; the UI shows "Waiting for NAME..." after [LinkTimeouts.silenceNoticeMs]. */
    fun silentForMs(): Long = if (!started || reconnecting) 0 else now - lastRecv

    /** Decisions this device still owes, with the engine's request for each. Empty when waiting for the other device. */
    fun pendingLocal(): List<LocalDecision> {
        val p = point ?: return emptyList()
        val b = battle ?: return emptyList()
        if (isOver) return emptyList()
        return p.localSlots.filter { it !in p.localChoices }.map { LocalDecision(it, b.request(b.sides[localSide], it)) }
    }

    /**
     * The local player's choice for [slot]. Returns null when accepted, otherwise the engine's reason (the player picks
     * again). Only moves, switches, pass and forfeit exist in a link battle.
     */
    fun submit(slot: Int, choice: Choice): String? {
        val p = point
        val b = battle
        if (isOver || p == null || b == null) return "No decision is open"
        if (slot !in p.localSlots) return "Nothing to decide for that slot"
        if (slot in p.localChoices) return "Already chosen"
        if (WireChoice.of(slot, choice) == null) return "Not available in a link battle"
        passError(b, localSide, slot, choice)?.let { return it }
        var err: String? = null
        guarded {
            // The engine applies it now, but nothing leaves this device until the commitment is sent, and the battle cannot
            // run a turn until the other side has chosen too.
            err = b.choose(b.sides[localSide], choice, slot)
            if (err == null) {
                p.localChoices[slot] = choice
                b.advance()
                progress()
            }
        }
        return err
    }

    /** Ends the session from this side (the player left); the other device is told. */
    fun abort(detail: String = "left the battle") { if (!isOver) fail(VoidReason.REMOTE_ABORT, "this device: $detail", notify = true, notifyReason = VoidReason.REMOTE_ABORT) }

    /** Reads what arrived, sends what is due and advances the battle. Call it often, with the current time in milliseconds. */
    fun pump(nowMs: Long) {
        if (isOver) return
        now = nowMs
        if (!started) { started = true; lastSent = nowMs; lastRecv = nowMs; lastProgress = nowMs }
        guarded {
            // Hello goes out before anything is read, so a device that finds a mismatch has already told the other its own side.
            if (!sentHello) { send(Hello(LinkProtocol.VERSION, config.gameVersion, dataHash, localName, rules)); sentHello = true }
            while (!isOver) {
                val bytes = transport.receive() ?: break
                if (reconnecting) { if (!acceptResume(bytes)) break; continue }
                lastRecv = now
                handle(FrameCodec.decode(bytes))
            }
            if (reconnecting) {
                if (!isOver && now - lostAt >= timeouts.graceMs) fail(VoidReason.LINK_LOST, "no reconnect within ${timeouts.graceMs} ms", notify = false)
                // Nothing valid on this connection in time: drop it so the owner can listen or connect again.
                else if (!transport.isClosed && now - offeredAt >= timeouts.resumeAnswerMs) try { transport.close() } catch (_: Exception) {}
                return@guarded
            }
            if (!isOver) progress()
            if (!isOver && transport.isClosed) { if (canResume) linkLost() else fail(VoidReason.LINK_LOST, "the connection closed", notify = false) }
            if (!isOver && !reconnecting && canResume && now - lastRecv >= timeouts.silenceNoticeMs && !remotePaused) linkLost()
            if (!isOver && !reconnecting) checkDecisionTimeout()
            if (!isOver && !reconnecting && now - lastRecv >= timeouts.voidMs) fail(VoidReason.TIMEOUT, "no message for ${now - lastRecv} ms", notify = true)
            if (!isOver && !reconnecting && now - lastSent >= timeouts.heartbeatMs) send(Heartbeat)
        }
    }

    /** The battle has started and the link is gone: pause and wait for [resume] for up to [LinkTimeouts.graceMs]. */
    private fun linkLost() {
        if (reconnecting) return
        reconnecting = true
        resumeSent = false
        lostAt = now
        try { transport.close() } catch (_: Exception) {}
    }

    private fun sendResume() {
        if (resumeSent) return
        transport.send(FrameCodec.encode(Frame(-1, Resume(sessionToken(), recvSeq))))
        resumeSent = true
        lastSent = now
    }

    /** Identifies this battle on both devices: both seed commitments, host first. A peer that knows it is the same peer. */
    private fun sessionToken(): String {
        val hostCommit = if (role == LinkRole.HOST) seedCommit(seedPart, seedNonce) else remoteSeedCommit!!
        val guestCommit = if (role == LinkRole.HOST) remoteSeedCommit!! else seedCommit(seedPart, seedNonce)
        return sha256Hex("resume|$hostCommit|$guestCommit")
    }

    /**
     * The first frame on a connection offered while [reconnecting]: only a [Resume] of this session is accepted. Anything
     * else (a stranger, garbage, the wrong session) closes that connection and the battle keeps waiting. Returns whether
     * the battle goes on over it.
     */
    private fun acceptResume(bytes: ByteArray): Boolean {
        val m = try { FrameCodec.decode(bytes).takeIf { it.seq == -1L }?.msg as? Resume } catch (_: LinkProtocolException) { null }
        if (m == null || m.token != sessionToken() || m.nextSeq < 0 || m.nextSeq > sendSeq) {
            try { transport.close() } catch (_: Exception) {}
            return false
        }
        lastRecv = now
        onResume(m)
        return true
    }

    private fun onResume(m: Resume) {
        sendResume()
        // Everything the other device has not seen, in order; it ignores frames it already has.
        for (i in m.nextSeq.toInt() until sentFrames.size) transport.send(sentFrames[i])
        reconnecting = false
        resumes++
        // The decision timeout keeps running across reconnects, so dropping the link cannot stall a battle for ever.
        lastRecv = now; lastSent = now
    }

    /** True while the other device must send something before anything can go on. */
    private fun peerOwes(): Boolean {
        if (rules.pick > 0 && remoteMons != null && remoteSeed == null) {
            // Picking: the other device owes its commitment; once this device has picked, its reveal and seed too.
            if (remotePickCommit == null) return true
            return localPick != null
        }
        if (remoteSeed == null) return true
        val p = point
        if (p != null) return p.remoteSlots.isNotEmpty() && !p.remoteApplied
        val b = battle ?: return true
        return b.outcome != BattleOutcome.ONGOING && remoteStates[lastStatePoint] == null
    }

    private fun checkDecisionTimeout() {
        if (!peerOwes()) { owedSince = -1; return }
        if (owedSince < 0) owedSince = now
        if (now - maxOf(owedSince, lastProgress) >= timeouts.decisionMs) fail(VoidReason.TIMEOUT, "the other device sent nothing but heartbeats for ${now - maxOf(owedSince, lastProgress)} ms", notify = true)
    }

    // ------------------------------------------------------------------ errors

    private fun guarded(block: () -> Unit) {
        try { block() }
        catch (e: LinkProtocolException) { fail(e.reason, e.message ?: "", notify = true) }
        catch (e: Exception) { fail(VoidReason.INTERNAL_ERROR, "$e", notify = true) }
    }

    private fun fail(reason: VoidReason, detail: String, notify: Boolean, notifyReason: VoidReason = reason) {
        if (isOver) return
        result = LinkResult.Voided(reason, detail)
        stage = Stage.DONE
        if (notify && !transport.isClosed) try { send(Abort(notifyReason.name, detail.take(200))) } catch (_: Exception) { }
        transport.close()
    }

    private fun protocolError(text: String): Nothing = throw LinkProtocolException(VoidReason.PROTOCOL_ERROR, text)

    // ------------------------------------------------------------------ sending

    private fun send(msg: LinkMessage) {
        val bytes = FrameCodec.encode(Frame(sendSeq++, msg))
        sentFrames.add(bytes)
        // While the link is down the frame waits in [sentFrames]; [onResume] sends it.
        if (!reconnecting) transport.send(bytes)
        lastSent = now
    }

    // ------------------------------------------------------------------ receiving

    private fun handle(frame: Frame) {
        // A Resume belongs only at the start of a new connection ([acceptResume]).
        if (frame.msg is Resume) protocolError("Resume on a running connection")
        // One stream, one counter: a repeated frame is ignored, a missing one ends the session.
        if (frame.seq < recvSeq) return
        if (frame.seq > recvSeq) protocolError("frame ${frame.seq} arrived, expected $recvSeq: a frame was lost")
        recvSeq++
        if (frame.msg != Heartbeat) lastProgress = now
        remotePaused = frame.msg == PauseNotice
        when (val m = frame.msg) {
            Heartbeat, PauseNotice -> {}
            is Abort -> fail(VoidReason.REMOTE_ABORT, "${m.reason}: ${m.detail}".take(200), notify = false)
            is Hello -> onHello(m)
            is TeamMsg -> onTeam(m)
            is SeedReveal -> onSeed(m)
            is Commit -> onCommit(m)
            is Reveal -> onReveal(m)
            is StateMsg -> onState(m)
            is PickCommit -> onPickCommit(m)
            is PickReveal -> onPickReveal(m)
            is Resume -> {}
        }
    }

    private fun onPickCommit(m: PickCommit) {
        if (rules.pick == 0 || remoteMons == null) protocolError("PickCommit without a pick rule or before Team")
        if (remotePickCommit != null) protocolError("second PickCommit")
        if (m.digest.length != 64) protocolError("bad pick digest")
        remotePickCommit = m.digest
    }

    private fun onPickReveal(m: PickReveal) {
        val commit = remotePickCommit ?: protocolError("PickReveal before PickCommit")
        // The other device may reveal only after it saw this device's commitment.
        if (!sentPickCommit) protocolError("PickReveal before this device committed")
        if (remotePick != null) protocolError("second PickReveal")
        if (pickDigest(m.indices, m.nonce) != commit) throw LinkProtocolException(VoidReason.COMMIT_MISMATCH, "picks do not match their commitment")
        val team = remoteMons!!
        if (m.indices.size != rules.pick || m.indices.distinct().size != m.indices.size || m.indices.any { it !in team.indices }) throw LinkProtocolException(VoidReason.BAD_TEAM, "bad picks")
        remotePick = m.indices
    }

    private fun onHello(m: Hello) {
        if (remoteHello != null) protocolError("second Hello")
        if (m.protocol != LinkProtocol.VERSION) throw LinkProtocolException(VoidReason.PROTOCOL_VERSION, "protocol ${m.protocol}, ours ${LinkProtocol.VERSION}")
        if (m.gameVersion != config.gameVersion) throw LinkProtocolException(VoidReason.GAME_VERSION, "game version ${m.gameVersion}, ours ${config.gameVersion}")
        if (m.dataHash != dataHash) throw LinkProtocolException(VoidReason.DATA_MISMATCH, "different game data")
        if (m.rules != rules) throw LinkProtocolException(VoidReason.RULES_MISMATCH, "different battle rules")
        if (!validName(m.name)) throw LinkProtocolException(VoidReason.PROTOCOL_ERROR, "bad trainer name")
        remoteHello = m
        remoteName = m.name
    }

    private fun onTeam(m: TeamMsg) {
        if (remoteHello == null) protocolError("Team before Hello")
        if (remoteMons != null) protocolError("second Team")
        if (m.seedCommit.length != 64) throw LinkProtocolException(VoidReason.BAD_TEAM, "bad seed commitment")
        validator.checkTeam(rules, m.mons)?.let { throw LinkProtocolException(VoidReason.BAD_TEAM, it) }
        remoteMons = m.mons.map { it.also { p -> p.healFully(dex) } }
        remoteSeedCommit = m.seedCommit
    }

    private fun onSeed(m: SeedReveal) {
        if (remoteMons == null) protocolError("Seed before Team")
        if (rules.pick > 0 && remotePick == null) protocolError("Seed before the picks")
        if (remoteSeed != null) protocolError("second Seed")
        if (seedCommit(m.part, m.nonce) != remoteSeedCommit) throw LinkProtocolException(VoidReason.COMMIT_MISMATCH, "seed does not match its commitment")
        remoteSeed = m
    }

    private fun onCommit(m: Commit) {
        checkPointNumber(m.point, "Commit")
        if (m.digest.length != 64) protocolError("bad commit digest")
        if (remoteCommits.put(m.point, m) != null) protocolError("second Commit for point ${m.point}")
    }

    private fun onReveal(m: Reveal) {
        checkPointNumber(m.point, "Reveal")
        if (m.point !in remoteCommits) protocolError("Reveal without Commit for point ${m.point}")
        if (remoteReveals.put(m.point, m) != null) protocolError("second Reveal for point ${m.point}")
    }

    private fun onState(m: StateMsg) {
        if (remoteSeed == null) protocolError("State before the seeds were exchanged")
        if (m.point < -1 || m.point > nextPoint + MAX_LEAD) protocolError("State for point ${m.point}, we are at point $nextPoint")
        if (remoteStates.put(m.point, m) != null) protocolError("second State for point ${m.point}")
        compareState(m.point)
    }

    /**
     * The other device can be ahead of us: a point where only it decides closes at once on its side, and a short chain of
     * those (a self-switch, then a replacement) can pass before it needs anything from us. [MAX_LEAD] bounds that.
     */
    private fun checkPointNumber(n: Int, what: String) {
        if (remoteSeed == null) protocolError("$what before the seeds were exchanged")
        val open = point?.n ?: nextPoint
        if (n < open || n > open + MAX_LEAD) protocolError("$what for point $n, we are at point $open")
    }

    // ------------------------------------------------------------------ progress

    /** Sends whatever is due and moves the battle on, until nothing more can happen without a message or a local choice. */
    private fun progress() {
        if (!sentHello) {
            send(Hello(LinkProtocol.VERSION, config.gameVersion, dataHash, localName, rules)); sentHello = true
        }
        if (remoteHello != null && !sentTeam) {
            send(TeamMsg(localMons, seedCommit(seedPart, seedNonce))); sentTeam = true
        }
        if (remoteMons != null && rules.pick > 0) {
            val mine = localPick ?: return
            if (!sentPickCommit) { send(PickCommit(pickDigest(mine, pickNonce))); sentPickCommit = true }
            if (remotePickCommit == null) return
            if (!sentPickReveal) { send(PickReveal(mine, pickNonce)); sentPickReveal = true }
            if (remotePick == null) return
        }
        if (remoteMons != null && !sentSeed) {
            send(SeedReveal(seedPart, seedNonce)); sentSeed = true
        }
        if (remoteSeed != null && battle == null) buildBattle()
        if (battle != null) play()
    }

    private fun buildBattle() {
        val mine = localPick?.map { localMons[it] } ?: localMons
        val theirs = remotePick?.map { remoteMons!![it] } ?: remoteMons!!
        val hostMons = if (role == LinkRole.HOST) mine else theirs
        val guestMons = if (role == LinkRole.HOST) theirs else mine
        val hostName = if (role == LinkRole.HOST) localName else remoteName!!
        val guestName = if (role == LinkRole.HOST) remoteName!! else localName
        val hostPart = if (role == LinkRole.HOST) seedPart else remoteSeed!!.part
        val guestPart = if (role == LinkRole.HOST) remoteSeed!!.part else seedPart
        val battleRules = rules.toBattleRules()
        val gimmicks = battleRules.allowedGimmicks
        val s0 = Side(0, hostName, hostMons, if (role == LinkRole.HOST) Controller.PLAYER else Controller.REMOTE, dex, gimmicks = gimmicks)
        val s1 = Side(1, guestName, guestMons, if (role == LinkRole.HOST) Controller.REMOTE else Controller.PLAYER, dex, gimmicks = gimmicks)
        val seed = java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256")
            .digest("pkmn-link-seed|$hostPart|$guestPart".toByteArray(Charsets.UTF_8))).long
        val b = Battle(dex, listOf(s0, s1), battleRules, seed)
        b.start()
        battle = b
        stage = Stage.PLAY
        sendState(-1)
    }

    private fun play() {
        val b = battle!!
        while (!isOver) {
            var p = point
            if (p == null) {
                if (b.outcome != BattleOutcome.ONGOING) { finishIfConfirmed(); return }
                p = openPoint(b)
                point = p
            }
            // Everything this device owes for the point: commit when its choices are in, reveal when the other side committed.
            if (p.localSlots.isNotEmpty() && !p.commitSent && p.localChoices.size == p.localSlots.size) {
                val wire = p.localSlots.sorted().map { WireChoice.of(it, p.localChoices.getValue(it))!! }
                p.nonce = entropy().toString(16) + entropy().toString(16)
                send(Commit(p.n, digest(p.n, wire, p.nonce))); p.commitSent = true
            }
            if (p.commitSent && !p.revealSent && (p.remoteSlots.isEmpty() || remoteCommits.containsKey(p.n))) {
                val wire = p.localSlots.sorted().map { WireChoice.of(it, p.localChoices.getValue(it))!! }
                send(Reveal(p.n, wire, p.nonce)); p.revealSent = true
            }
            if (p.remoteSlots.isNotEmpty() && !p.remoteApplied) {
                val r = remoteReveals[p.n] ?: return
                applyRemote(b, p, r)
            }
            val localDone = p.localSlots.isEmpty() || p.revealSent
            val remoteDone = p.remoteSlots.isEmpty() || p.remoteApplied
            if (!(localDone && remoteDone)) return
            // Point complete on both devices: run whatever the engine can, then compare states.
            b.advance()
            sendState(p.n)
            point = null
            nextPoint = p.n + 1
            remoteCommits.remove(p.n); remoteReveals.remove(p.n)
        }
    }

    private fun openPoint(b: Battle): Point {
        val local = ArrayList<Int>(); val remote = ArrayList<Int>()
        for (si in b.sides.indices) for (slot in b.sides[si].actives.indices) {
            if (b.request(b.sides[si], slot) == Request.Wait) continue
            (if (si == localSide) local else remote).add(slot)
        }
        if (local.isEmpty() && remote.isEmpty()) throw LinkProtocolException(VoidReason.INTERNAL_ERROR, "nobody has a decision but the battle is ongoing")
        val p = Point(nextPoint, local, remote)
        // The other device may not send choices for a point where it has nothing to decide.
        if (remote.isEmpty() && (remoteCommits.containsKey(p.n) || remoteReveals.containsKey(p.n))) protocolError("choices for point ${p.n}, where the other device has no decision")
        return p
    }

    /** [Choice.Pass] stands in for a locked move or Struggle only; as a free choice it would be an invisible Struggle. */
    private fun passError(b: Battle, side: Int, slot: Int, c: Choice): String? {
        if (c != Choice.Pass) return null
        val r = b.request(b.sides[side], slot) as? Request.Move ?: return "Pass is only for a move turn"
        return if (r.lockedMove != null || r.usableMoves.none { it }) null else "Pass is only allowed when no move can be chosen"
    }

    private fun applyRemote(b: Battle, p: Point, r: Reveal) {
        val commit = remoteCommits.getValue(p.n)
        if (digest(p.n, r.choices, r.nonce) != commit.digest) throw LinkProtocolException(VoidReason.COMMIT_MISMATCH, "choices do not match their commitment")
        if (r.choices.map { it.slot }.sorted() != p.remoteSlots.sorted()) throw LinkProtocolException(VoidReason.ILLEGAL_CHOICE, "choices for the wrong slots")
        for (w in r.choices) {
            val c = w.toChoice()
            passError(b, remoteSide, w.slot, c)?.let { throw LinkProtocolException(VoidReason.ILLEGAL_CHOICE, it) }
            val err = b.choose(b.sides[remoteSide], c, w.slot)
            if (err != null) throw LinkProtocolException(VoidReason.ILLEGAL_CHOICE, "$c: $err")
            b.advance()
        }
        p.remoteApplied = true
    }

    private fun sendState(n: Int) {
        val b = battle!!
        val s = StateMsg(n, b.turn, b.stateHash(), b.outcome != BattleOutcome.ONGOING)
        localStates[n] = s
        lastStatePoint = n
        send(s)
        compareState(n)
    }

    private fun compareState(n: Int) {
        val mine = localStates[n] ?: return
        val theirs = remoteStates[n] ?: return
        if (mine != theirs) throw LinkProtocolException(VoidReason.HASH_MISMATCH, "state after point $n differs (turn ${mine.turn}/${theirs.turn})")
        if (b0Over() && n == lastStatePoint) finishIfConfirmed()
    }

    private fun b0Over() = battle?.outcome.let { it != null && it != BattleOutcome.ONGOING }

    /** The battle is over locally; it counts only once the other device's final state matched ours. */
    private fun finishIfConfirmed() {
        val b = battle ?: return
        if (isOver || b.outcome == BattleOutcome.ONGOING || point != null) return
        val mine = localStates[lastStatePoint] ?: return
        val theirs = remoteStates[lastStatePoint] ?: return
        if (mine != theirs) return
        val winner = when (b.outcome) { BattleOutcome.WIN -> 0; BattleOutcome.LOSE -> 1; else -> null }
        result = LinkResult.Completed(b.outcome, winner, mine.hash)
        stage = Stage.DONE
        transport.close()
    }

    // ------------------------------------------------------------------ hashing

    private fun seedCommit(part: Long, nonce: String) = sha256Hex("seed|$part|$nonce")

    private fun pickDigest(indices: List<Int>, nonce: String) = sha256Hex("pick|${indices.joinToString(",")}|$nonce")

    private fun digest(point: Int, choices: List<WireChoice>, nonce: String) =
        sha256Hex("choices|$point|" + choices.sortedBy { it.slot }.joinToString(";") { it.canonical() } + "|$nonce")

    private fun validName(n: String) = n.isNotBlank() && n.length <= LinkProtocol.MAX_NAME && n.none { it.isISOControl() }
}
