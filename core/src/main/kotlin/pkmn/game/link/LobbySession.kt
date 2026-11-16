package pkmn.game.link

import pkmn.engine.battle.Gimmick
import pkmn.engine.link.LinkLobbyRules
import pkmn.engine.link.LinkProtocol
import pkmn.engine.link.LinkProtocolException
import pkmn.engine.link.LinkRole
import pkmn.engine.link.LinkRules
import pkmn.engine.link.LinkTransport
import pkmn.engine.link.LobbyCodec
import pkmn.engine.link.LobbyHi
import pkmn.engine.link.LobbyLeave
import pkmn.engine.link.LobbyMsg
import pkmn.engine.link.LobbyReady
import pkmn.engine.link.LobbyRules
import pkmn.engine.link.LobbyStart

/**
 * The lobby between two connected devices, before the battle: both say who they are ([LobbyHi]), the host picks the rules
 * from what both players have, the guest says it is ready and the host starts. No game objects and no clock of its own:
 * the screen calls [pump] each frame with the time and reads the fields. A peer that connects but sends no hello within
 * [helloTimeoutMs] ends the lobby, so a stray connection cannot hold the host's only slot forever. Pure logic over a [LinkTransport], so it is tested with a loopback link.
 *
 * Whatever goes wrong ends in [error] (a sentence for the player); the transport is closed and the session is finished.
 * When [start] is set, the battle exchange (or, when [Start.trade], the trade exchange) takes over the same transport.
 */
class LobbySession(
    val role: LinkRole,
    private val transport: LinkTransport,
    val localName: String,
    private val gameVersion: String,
    private val dataHash: String,
    private val localGimmicks: Set<Gimmick>,
    private val partySize: Int,
    private val helloTimeoutMs: Long = HELLO_TIMEOUT_MS,
) {
    companion object {
        /** How long a connected peer may stay silent before its hello. */
        const val HELLO_TIMEOUT_MS = 15_000L
    }

    /** What the battle starts with, once the lobby is done. */
    class Start(val rules: LinkRules, val flat: Boolean, val trade: Boolean = false)

    var remoteName: String? = null
        private set
    private var remoteGimmicks: Set<String> = emptySet()
    /** The other player's party size from its hello (a three-against-three battle needs three on both sides). */
    var remotePartySize = 0
        private set
    /** True once the other device's [LobbyHi] passed the checks. */
    var connected = false
        private set

    /** Host: the choices. Guest: what the host last sent. */
    var flat = false
        private set
    var gimmicksOn = true
        private set
    /** Host: each player picks this many ("three against three"), 0 for the whole team. Guest: what the host last sent. */
    var pick = 0
        private set
    /** The preset the current rules match. */
    val preset get() = LinkLobbyRules.Preset.entries.firstOrNull { it.flat == flat && it.pick == pick } ?: LinkLobbyRules.Preset.AS_THEY_ARE
    /** Host: trade instead of battle. Guest: what the host last sent. The battle rules are then ignored. */
    var trade = false
        private set
    var guestReady = false
        private set

    var error: String? = null
        private set
    var start: Start? = null
        private set
    val finished get() = error != null || start != null

    private var sentHi = false
    /** Time of the first [pump]; -1 before it. */
    private var startedAt = -1L
    private var rulesFromHost: LinkRules? = null

    /** Gimmicks both players have unlocked, sorted. */
    val commonGimmicks: Set<Gimmick> get() = LinkLobbyRules.common(localGimmicks.map { it.name }, remoteGimmicks)

    /** The rules as they stand now (host) or as the host last sent them (guest); null before the guest has any. */
    val rules: LinkRules? get() = if (role == LinkRole.HOST) LinkLobbyRules.rules(flat, if (gimmicksOn) commonGimmicks else emptySet(), pick) else rulesFromHost

    /** Reads what arrived and sends what is due. [nowMs] is a monotonic clock in milliseconds. */
    fun pump(nowMs: Long) {
        if (finished) return
        if (startedAt < 0) startedAt = nowMs
        try {
            if (!sentHi) { send(LobbyHi(LinkProtocol.VERSION, gameVersion, dataHash, localName, localGimmicks.map { it.name }.sorted(), partySize)); sentHi = true }
            while (!finished) {
                val bytes = transport.receive() ?: break
                handle(LobbyCodec.decode(bytes))
            }
            if (!finished && transport.isClosed) fail("${remoteName ?: "The other player"} left the lobby.")
            if (!finished && !connected && nowMs - startedAt >= helloTimeoutMs) fail("The other device didn't answer.", tell = true)
        } catch (e: LinkProtocolException) {
            fail("The link broke (${e.reason.name.lowercase().replace('_', ' ')}).", tell = true)
        }
    }

    /** Host: levels as they are or flat 50 (with the whole team). */
    fun setFlat(on: Boolean) = setPreset(if (on) LinkLobbyRules.Preset.FLAT_50 else LinkLobbyRules.Preset.AS_THEY_ARE)

    /** Host: one of the rule presets (levels as they are, flat 50, three against three). */
    fun setPreset(p: LinkLobbyRules.Preset) {
        if (role != LinkRole.HOST || !connected || start != null || p == preset) return
        flat = p.flat; pick = p.pick; changed()
    }

    /** Host: allow the gimmicks both players have, or none. */
    fun setGimmicks(on: Boolean) { if (role == LinkRole.HOST && connected && start == null && on != gimmicksOn) { gimmicksOn = on; changed() } }

    /** Host: a trade (true) or a battle (false). */
    fun setTrade(on: Boolean) { if (role == LinkRole.HOST && connected && start == null && on != trade) { trade = on; changed() } }

    /** Guest: ready (or not) to play with the rules shown. */
    fun setReady(ready: Boolean) {
        if (role != LinkRole.GUEST || !connected || rulesFromHost == null || start != null || ready == guestReady) return
        guestReady = ready
        send(LobbyReady(ready, rulesKey()))
    }

    /** Host: starts the battle. Only when the guest is ready; returns whether it started. */
    fun startBattle(): Boolean {
        if (role != LinkRole.HOST || !connected || !guestReady || finished) return false
        val r = rules ?: return false
        if (!trade) r.error()?.let { fail("These rules can't be played: $it."); return false }
        // Refused, not failed: the host can pick other rules.
        if (!trade && r.pick > 0 && (partySize < r.pick || remotePartySize < r.pick)) return false
        send(LobbyStart(r, flat, trade))
        start = Start(r, flat, trade)
        return true
    }

    /** Leaves the lobby; the other device is told. */
    fun leave() {
        if (finished) return
        error = "You left the lobby."
        send(LobbyLeave)
        transport.close()
    }

    // ------------------------------------------------------------------ internals

    /** Identifies the rules a Ready refers to, so a Ready that crosses a rules change on the wire is ignored. */
    private fun rulesKey(): String = (if (role == LinkRole.HOST) rules else rulesFromHost)?.let { "$it|$flat|$trade" } ?: ""

    private fun changed() { guestReady = false; sendRules() }

    private fun sendRules() { rules?.let { send(LobbyRules(it, flat, trade)) } }

    private fun send(m: LobbyMsg) { transport.send(LobbyCodec.encode(m)) }

    private fun fail(text: String, tell: Boolean = false) {
        if (finished) return
        error = text
        if (tell && !transport.isClosed) try { send(LobbyLeave) } catch (_: Exception) {}
        transport.close()
    }

    private fun handle(m: LobbyMsg) {
        when (m) {
            is LobbyHi -> onHi(m)
            is LobbyRules -> {
                if (role != LinkRole.GUEST || !connected) bad("rules")
                if (m.rules.error() != null || m.flat != (m.rules.levelCap == LinkLobbyRules.FLAT_LEVEL)) bad("rules")
                // Only the presets: 3 VS 3 is played at levels as they are.
                if (m.rules.pick !in setOf(0, LinkLobbyRules.PICK) || (m.rules.pick > 0 && m.flat)) bad("rules")
                // The host's rules may only use gimmicks this device really has.
                if (m.rules.gimmicks.any { g -> localGimmicks.none { it.name == g } }) bad("rules")
                rulesFromHost = m.rules; flat = m.flat; pick = m.rules.pick; trade = m.trade; guestReady = false
            }
            is LobbyReady -> {
                if (role != LinkRole.HOST || !connected) bad("ready")
                // A Ready sent before the guest saw the latest rules is stale: the host's change already reset it.
                if (m.rulesKey == rulesKey()) guestReady = m.ready
            }
            is LobbyStart -> {
                if (role != LinkRole.GUEST || !connected || rulesFromHost != m.rules || m.flat != flat || m.trade != trade || !guestReady) bad("start")
                start = Start(m.rules, m.flat, m.trade)
            }
            LobbyLeave -> fail("${remoteName ?: "The other player"} left the lobby.")
        }
    }

    private fun onHi(m: LobbyHi) {
        if (connected) bad("a second hello")
        when {
            m.protocol != LinkProtocol.VERSION -> return fail("The other game speaks a different link version.", tell = true)
            m.gameVersion != gameVersion -> return fail("The other game is a different build. Update both.", tell = true)
            m.dataHash != dataHash -> return fail("The other game has different content.", tell = true)
            m.name.isBlank() || m.name.length > LinkProtocol.MAX_NAME || m.name.any { it.isISOControl() } -> bad("name")
            m.partySize !in 1..6 -> return fail("${m.name} has no Pokémon that can battle.", tell = true)
            partySize !in 1..6 -> return fail("You need a Pokémon to link.", tell = true)
        }
        remoteName = m.name
        remoteGimmicks = m.gimmicks.toSet()
        remotePartySize = m.partySize
        connected = true
        if (role == LinkRole.HOST) sendRules()
    }

    private fun bad(what: String): Nothing = throw LinkProtocolException(pkmn.engine.link.VoidReason.PROTOCOL_ERROR, "unexpected $what in the lobby")
}
