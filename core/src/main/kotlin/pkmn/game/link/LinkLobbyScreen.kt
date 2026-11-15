package pkmn.game.link

import pkmn.engine.battle.BattleOutcome
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.GameTradeStore
import pkmn.engine.link.LinkAddress
import pkmn.engine.link.LinkConfig
import pkmn.engine.link.LinkEndpoint
import pkmn.engine.link.LinkFingerprint
import pkmn.engine.link.LinkLobbyRules
import pkmn.engine.link.LinkResult
import pkmn.engine.link.LinkRole
import pkmn.engine.link.LockstepBattle
import pkmn.engine.link.VoidReason
import pkmn.game.PkmnGame
import pkmn.game.battle.BattleScreen
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.overworld.OverworldScreen
import pkmn.game.screens.NameInputScreen
import pkmn.game.screens.Screen
import java.io.Closeable
import java.util.concurrent.CompletableFuture

/** The build identity both devices must share. Keep in step with the Gradle `version` (a different build refuses to battle). */
object LinkBuild { const val GAME_VERSION = "0.1.0" }

/**
 * What the desktop automation (`link host`, `link join`) asks of the lobby: skip the menus and start with default rules.
 * [dropFromPrepared] is a fault for the interrupted-trade scene: in a trade, this device's frames are dropped from its
 * PREPARED on (as if the link died one way), so the other device never commits while this one does.
 */
class AutoLink(val host: Boolean, val endpoint: LinkEndpoint? = null, val port: Int = LinkAddress.DEFAULT_PORT, val flat: Boolean = false, val trade: Boolean = false,
    val dropFromPrepared: Boolean = false)

/**
 * Link play, from the start menu: host or join, the lobby (the host picks battle or trade and the rules, the guest says
 * ready), then the battle and its result, or the trade. Everything runs on the local network. A battle writes nothing to the save (it plays healed
 * copies of the party); a trade saves through its journal ([LinkTradeScreen]). Closing this screen at any point closes the connection.
 */
class LinkLobbyScreen(game: PkmnGame, private val ctx: GameContext, private val auto: AutoLink? = null, private val onClose: (() -> Unit)? = null) : Screen(game), LinkAware, LinkDebug {
    private enum class Stage { MENU, UNFINISHED, HOSTING, CONNECTING, LOBBY, STARTING, BATTLE, DONE }

    override val blocksAutosave get() = true
    private val dex = ctx.dex
    private var stage = Stage.MENU
    private var cursor = 0
    private var notice: String? = null
    private var pendingListen: Closeable? = null
    private var transport: TcpTransport? = null
    private var session: LobbySession? = null
    private var battle: LockstepBattle? = null
    private var role = LinkRole.HOST
    private var addresses: List<String> = emptyList()
    private var hostPort = LinkAddress.DEFAULT_PORT
    private var autoDone = false
    /** The content hash costs a full serialization: computed once, off the game thread, while the player chooses. */
    private val dataHash: CompletableFuture<String> = CompletableFuture.supplyAsync { LinkFingerprint.dataHash(dex) }
    /** UNFINISHED TRADE (the manual way out of an open trade journal) is listed only while one is open. */
    private val menuItems get() = listOfNotNull("HOST A GAME", "JOIN WITH A CODE", "JOIN BY ADDRESS", UNFINISHED.takeIf { ctx.state.pendingTrade != null }, "CANCEL")

    override fun debugState(): String = "lobby=$stage menu=${menuItems.getOrNull(cursor)?.replace(' ', '_')} connected=${session?.connected == true} mode=${if (session?.trade == true) "TRADE" else "BATTLE"}"

    override fun onEnter() {
        if (ctx.state.party.isEmpty()) { stage = Stage.DONE; notice = "You need a POKéMON to link." ; return }
        auto?.let { a -> if (a.host) beginHost(a.port) else a.endpoint?.let { beginJoin(it) } }
    }

    // ================================================================= connecting

    private fun beginHost(port: Int) {
        role = LinkRole.HOST
        hostPort = port
        addresses = game.platform.localAddresses()
        stage = Stage.HOSTING; notice = null
        pendingListen = TcpTransport.listen(port, onAccepted = { t -> pendingListen = null; connected(t) }, onError = { msg -> pendingListen = null; stage = Stage.MENU; notice = msg })
        // Automation reads this line to find the code.
        if (auto != null) game.platform.log("LINK host code=${addresses.firstOrNull()?.let { LinkAddress.encode(it) }} addr=${addresses.firstOrNull()}:$port")
    }

    private fun beginJoin(ep: LinkEndpoint) {
        if (!LinkAddress.isLocal(ep.host)) { stage = Stage.MENU; notice = "Link play only works on the local network (192.168.x.x, 10.x.x.x, 172.16-31.x.x)."; return }
        role = LinkRole.GUEST
        stage = Stage.CONNECTING; notice = null
        pendingListen = TcpTransport.connect(ep.host, ep.port, onConnected = { t -> pendingListen = null; connected(t) }, onError = { msg -> pendingListen = null; stage = Stage.MENU; notice = msg })
    }

    private fun connected(t: TcpTransport) {
        if (stage != Stage.HOSTING && stage != Stage.CONNECTING) { t.close(); return }
        transport = t
        session = LobbySession(role, t, ctx.state.playerName, LinkBuild.GAME_VERSION, dataHash.get(), ctx.unlockedGimmicks(), ctx.state.party.size.coerceAtMost(6))
        stage = Stage.LOBBY; cursor = 0
    }

    private fun askCode(manual: Boolean) {
        game.push(NameInputScreen(game, if (manual) "HOST'S ADDRESS?" else "HOST'S CODE?", "", if (manual) 21 else LinkAddress.CODE_LENGTH, onCancel = {}) { text ->
            val ep = LinkAddress.parse(text)
            val shapeOk = ep != null && ('.' in text.trim()) == manual
            if (ep == null || !shapeOk) notice = if (manual) "That isn't an address like 192.168.0.7." else "That isn't a valid code."
            else beginJoin(ep)
        })
    }

    // ================================================================= update

    override fun update(dt: Float, input: InputState) {
        session?.pump(System.nanoTime() / 1_000_000)
        when (stage) {
            Stage.MENU -> updateMenu(input)
            Stage.UNFINISHED -> updateUnfinished(input)
            Stage.HOSTING, Stage.CONNECTING -> if (input.justPressed(Button.B)) cancelConnect()
            Stage.LOBBY -> updateLobby(input)
            Stage.STARTING -> if (input.justPressed(Button.B)) battle?.let { it.abort("left before the battle"); endBattle(it) } else updateStarting()
            Stage.BATTLE -> {}
            Stage.DONE -> if (input.justPressed(Button.A) || input.justPressed(Button.B)) pop()
        }
    }

    private fun updateMenu(input: InputState) {
        if (input.repeat(Button.UP)) cursor = (cursor + menuItems.size - 1) % menuItems.size
        if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % menuItems.size
        if (input.justPressed(Button.B)) { pop(); return }
        if (!input.justPressed(Button.A)) return
        game.audio.playSfx("menu_select")
        notice = null
        when (menuItems[cursor]) {
            "HOST A GAME" -> beginHost(LinkAddress.DEFAULT_PORT)
            "JOIN WITH A CODE" -> askCode(manual = false)
            "JOIN BY ADDRESS" -> askCode(manual = true)
            UNFINISHED -> { stage = Stage.UNFINISHED; cursor = 0 }
            else -> pop()
        }
    }

    /**
     * The manual way out, for a partner who will never come back: one page with what is open and the warning that names
     * what can be lost, then KEEP WAITING (the default) or CANCEL IT / END IT. The latter removes the journal (cancels a
     * PREPARED one, ends a COMMITTED one) and saves; the party stays as it is.
     */
    private fun updateUnfinished(input: InputState) {
        if (input.repeat(Button.UP) || input.repeat(Button.DOWN)) cursor = 1 - cursor.coerceIn(0, 1)
        if (input.justPressed(Button.B) || (input.justPressed(Button.A) && cursor == 0)) { stage = Stage.MENU; cursor = 0; return }
        if (!input.justPressed(Button.A)) return
        game.audio.playSfx("menu_select")
        notice = try {
            GameTradeStore(ctx) { state -> saveHere(state) }.abandon()
        } catch (e: Exception) {
            game.platform.log("Save failed: $e")
            "The game could not be saved, so the trade is still open."
        }
        stage = Stage.MENU; cursor = 0
    }

    private fun drawUnfinished(r: Renderer) {
        val summary = ctx.linkTradeSummary() ?: return
        val warning = ctx.linkTradeAbandonWarning() ?: return
        val committed = ctx.state.pendingTrade?.state == pkmn.engine.save.TradeJournalState.COMMITTED
        r.text("UNFINISHED TRADE", 16, 10)
        r.assets.font6.wrap(summary, 38).take(4).forEachIndexed { i, l -> r.text(l, 10, 30 + i * 12, small = true) }
        r.assets.font6.wrap(warning, 38).take(6).forEachIndexed { i, l -> r.text(l, 10, 84 + i * 12, Palette.RED, small = true) }
        r.text("KEEP WAITING", 32, 164); r.text(if (committed) "END IT" else "CANCEL IT", 32, 182)
        r.cursor(18, 164 + cursor.coerceIn(0, 1) * 18)
        r.text("B: BACK", 16, 204, small = true)
    }

    /** Saves with the player's current place, as the menu's SAVE does. */
    private fun saveHere(state: pkmn.engine.save.GameState): pkmn.engine.save.SaveResult {
        game.screens().filterIsInstance<OverworldScreen>().lastOrNull()?.let { ow ->
            state.map = ow.baseMap.id; state.x = ow.player.x; state.y = ow.player.y; state.facing = ow.player.facing.name.lowercase()
        }
        ctx.syncRng()
        return game.saves.save(state)
    }

    private fun cancelConnect() { pendingListen?.close(); pendingListen = null; stage = Stage.MENU; notice = null }

    private fun updateLobby(input: InputState) {
        val s = session ?: return
        s.error?.let { finishWith(it); return }
        s.start?.let { if (it.trade) startTrade(s) else startBattle(s); return }
        if (!s.connected) { if (input.justPressed(Button.B)) { s.leave(); finishWith("You left the lobby.") }; return }
        val rows = hostRows(s).size.takeIf { role == LinkRole.HOST } ?: 1
        if (input.repeat(Button.UP)) cursor = (cursor + rows - 1) % rows
        if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % rows
        if (input.justPressed(Button.B)) { s.leave(); finishWith("You left the lobby."); return }
        // Automation waits a moment first, so a script can capture the lobby.
        if (auto != null && !autoDone) {
            if (role == LinkRole.HOST && !autoRulesSet) { autoRulesSet = true; s.setFlat(auto.flat); s.setTrade(auto.trade) }
            if (++autoFrames > AUTO_LOBBY_FRAMES) autoStep(s)
            return
        }
        if (!input.justPressed(Button.A)) return
        game.audio.playSfx("menu_select")
        if (role == LinkRole.HOST) when (hostRows(s).getOrNull(cursor)) {
            Row.MODE -> s.setTrade(!s.trade)
            Row.LEVELS -> s.setFlat(!s.flat)
            Row.GIMMICKS -> s.setGimmicks(!s.gimmicksOn)
            else -> if (!s.startBattle()) game.audio.playSfx("error")
        } else s.setReady(!s.guestReady)
    }

    /** The host's menu rows: a trade has no battle rules to choose. */
    private enum class Row { MODE, LEVELS, GIMMICKS, START }
    private fun hostRows(s: LobbySession) = if (s.trade) listOf(Row.MODE, Row.START) else listOf(Row.MODE, Row.LEVELS, Row.GIMMICKS, Row.START)

    /** Automation: the guest is ready as soon as it has rules; the host starts as soon as the guest is ready. */
    private fun autoStep(s: LobbySession) {
        if (role == LinkRole.HOST) {
            if (s.startBattle()) autoDone = true
        } else if (s.rules != null) { s.setReady(true) }
    }
    /** The screen this one was replaced with (trade or result); it inherits [onClose]. */
    private var handOff: LinkSessionScreen? = null
    private var autoRulesSet = false
    private var autoFrames = 0
    private companion object {
        const val AUTO_LOBBY_FRAMES = 90
        const val UNFINISHED = "UNFINISHED TRADE"
    }

    private fun startBattle(s: LobbySession) {
        val st = s.start ?: return
        try {
            val team = LinkLobbyRules.team(dex, ctx.state.party, st.flat)
            battle = LockstepBattle(role, transport!!, dex, LinkConfig(LinkBuild.GAME_VERSION, st.rules, dataHash = dataHash.get()), team, ctx.state.playerName)
            stage = Stage.STARTING
        } catch (e: IllegalArgumentException) {
            transport?.close()
            finishWith("Your party can't be used for a link battle: ${e.message?.take(60)}")
        }
    }

    /** The trade takes over the lobby's connection; this screen is replaced by the trade screen and leaves the link open. */
    private fun startTrade(s: LobbySession) {
        val name = s.remoteName ?: "your friend"
        stage = Stage.DONE
        replace(LinkTradeScreen(game, ctx, role, transport!!, name, dropFromPrepared = auto?.dropFromPrepared == true).also { handOff = it })
    }

    private fun updateStarting() {
        val lb = battle ?: return
        lb.pump(System.nanoTime() / 1_000_000)
        if (lb.isOver) { endBattle(lb); return }
        val b = lb.battle ?: return
        stage = Stage.BATTLE
        game.push(BattleScreen(game, ctx, b, trainerId = null, musicOverride = "battle_trainer", onEnd = { _ -> endBattle(lb) }, link = lb))
    }

    private fun endBattle(lb: LockstepBattle) {
        if (stage == Stage.DONE) return
        // The session may still be settling its last state message; the result screen waits for it.
        stage = Stage.DONE
        replace(LinkResultScreen(game, lb).also { handOff = it })
    }

    private fun finishWith(text: String) {
        stage = Stage.DONE; notice = text
        transport?.close()
    }

    /** While the battle screen is up it sends the notice itself; this covers the moment between the lobby and the first frame. */
    override fun onAppPaused() { if (stage == Stage.STARTING) battle?.notifyPaused() }

    override fun onExit() {
        // replace() hands over to the trade or result screen; the attendant's script still waits for the link to end.
        handOff?.let { next -> next.onClose = onClose } ?: onClose?.invoke()
        pendingListen?.close()
        if (stage != Stage.DONE && stage != Stage.BATTLE) transport?.close()
    }

    override fun onBack(): Boolean {
        when (stage) {
            Stage.MENU, Stage.DONE -> pop()
            Stage.UNFINISHED -> { stage = Stage.MENU; cursor = 0 }
            Stage.HOSTING, Stage.CONNECTING -> cancelConnect()
            Stage.LOBBY -> { session?.leave(); finishWith("You left the lobby.") }
            else -> {}
        }
        return true
    }

    // ================================================================= drawing

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.frame(0, 0, Renderer.W, Renderer.H)
        if (stage != Stage.UNFINISHED) r.text("LINK", 16, 10)
        when (stage) {
            Stage.MENU -> {
                val items = menuItems
                items.forEachIndexed { i, s -> r.text(s, 32, 40 + i * 18, if (s == UNFINISHED) Palette.RED else Palette.TEXT); if (i == cursor) r.cursor(18, 40 + i * 18) }
                if (ctx.state.pendingTrade == null) {
                    r.text("Same Wi-Fi or hotspot only.", 16, 124, small = true)
                    r.text("Nothing is saved or lost.", 16, 136, small = true)
                } else r.text("A link trade is unfinished.", 16, 136, small = true)
            }
            Stage.UNFINISHED -> { drawUnfinished(r); return }
            Stage.HOSTING -> {
                r.text("Tell your friend this CODE:", 16, 36, small = true)
                val code = addresses.firstOrNull()?.let { LinkAddress.encode(it) } ?: "-------"
                r.text(code, 16, 52)
                r.text(addresses.firstOrNull() ?: "", 16, 70, small = true)
                addresses.drop(1).take(3).forEachIndexed { i, a -> r.text("${LinkAddress.encode(a)}  $a", 16, 84 + i * 12, small = true) }
                if (addresses.firstOrNull() == "127.0.0.1") r.text("No network found: this device only.", 16, 128, small = true)
                r.text("Waiting for a friend...", 16, 160 + 4)
                r.text("B: CANCEL", 16, 200, small = true)
            }
            Stage.CONNECTING -> { r.text("Connecting...", 16, 40); r.text("B: CANCEL", 16, 200, small = true) }
            Stage.LOBBY -> drawLobby(r)
            Stage.STARTING -> r.text("Starting the battle...", 16, 40)
            Stage.BATTLE -> {}
            Stage.DONE -> {}
        }
        notice?.let { n -> r.frame(0, 168, Renderer.W, 56); r.font(n) { lines -> lines.take(3).forEachIndexed { i, l -> r.text(l, 12, 178 + i * 14) } } }
    }

    private fun Renderer.font(t: String, f: (List<String>) -> Unit) = f(assets.font8.wrap(t, 29))

    private fun drawLobby(r: Renderer) {
        val s = session ?: return
        if (!s.connected) { r.text("Checking the other game...", 16, 40); return }
        r.text("YOU:    ${ctx.state.playerName}", 16, 28)
        r.text("FRIEND: ${s.remoteName}", 16, 42)
        val rules = s.rules
        val host = role == LinkRole.HOST
        val levels = "LEVELS: " + if (s.flat) "FLAT 50" else "AS THEY ARE"
        val gim = "GIMMICKS: " + (rules?.gimmicks?.joinToString(" ") { it.take(4) }?.ifEmpty { "NONE" } ?: "-")
        val mode = "MODE: " + if (s.trade) "TRADE" else "BATTLE"
        val start = if (s.guestReady) "START" else "START (friend not ready)"
        if (host) {
            val rows = hostRows(s)
            rows.forEachIndexed { i, row ->
                r.text(when (row) { Row.MODE -> mode; Row.LEVELS -> levels; Row.GIMMICKS -> gim; Row.START -> start }, 32, 66 + i * 18)
            }
            r.cursor(18, 66 + cursor.coerceIn(0, rows.size - 1) * 18)
            r.text("Friend: " + if (s.guestReady) "READY" else "choosing...", 16, 142, small = true)
        } else if (rules == null) {
            r.text("Waiting for the host's rules...", 16, 66)
            r.text("READY", 32, 108); r.cursor(18, 108)
        } else {
            r.text(mode, 16, 66)
            if (!s.trade) { r.text(levels, 16, 84); r.text(gim, 16, 102) }
            r.text(if (s.guestReady) "READY!  (A: not ready)" else "READY", 32, 126); r.cursor(18, 126)
            if (s.guestReady) r.text("Waiting for the host to start...", 16, 148, small = true)
        }
        r.text("B: LEAVE", 16, 200, small = true)
    }
}

/** A screen that ends a link session; [onClose] runs once when it is removed (the attendant's script resumes then). */
interface LinkSessionScreen { var onClose: (() -> Unit)? }

/** How a link session ended, as both players see it: the result (or why there is none) and the shared state hash. */
class LinkResultScreen(game: PkmnGame, private val lb: LockstepBattle) : Screen(game), LinkSessionScreen {
    override val blocksAutosave get() = true
    override var onClose: (() -> Unit)? = null
    override fun onExit() { onClose?.invoke(); onClose = null }
    private var started = false

    /** Null while the other device's last state message is still on its way. */
    private val result get() = lb.result

    override fun onEnter() { if (lb.result == null) started = true }

    override fun update(dt: Float, input: InputState) {
        lb.pump(System.nanoTime() / 1_000_000)
        if (result != null && (input.justPressed(Button.A) || input.justPressed(Button.B))) pop()
    }

    override fun onBack(): Boolean { if (result != null) pop(); return true }

    /** One line for automation logs: both devices must print the same hash and opposite winners. */
    fun summary(): String = when (val r = result) {
        null -> "link=pending"
        is LinkResult.Completed -> "link=completed winnerSide=${r.winnerSide} mySide=${if (lb.role == LinkRole.HOST) 0 else 1} hash=${r.hash}"
        is LinkResult.Voided -> "link=void reason=${r.reason}"
    }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.frame(0, 0, Renderer.W, Renderer.H)
        r.text("LINK BATTLE", 16, 10)
        val res = result
        val foe = lb.remoteName ?: "your friend"
        val mySide = if (lb.role == LinkRole.HOST) 0 else 1
        when (res) {
            null -> r.text("Waiting for $foe...", 16, 60)
            is LinkResult.Completed -> {
                r.text(when (res.winnerSide) { null -> "IT'S A DRAW"; mySide -> "YOU WON!"; else -> "YOU LOST..." }, 16, 50)
                r.text("vs ${foe.take(18)}", 16, 70)
                r.text("Both games agree:", 16, 100, small = true)
                r.text(res.hash.take(16), 16, 114, small = true)
                r.text("Nothing was saved or lost.", 16, 140, small = true)
            }
            is LinkResult.Voided -> {
                r.text("NO RESULT", 16, 50)
                r.text(voidText(res.reason), 16, 70, small = true)
                r.text("The battle doesn't count.", 16, 100, small = true)
                r.text("Nothing was saved or lost.", 16, 114, small = true)
            }
        }
        if (res != null) r.text("A: OK", 16, 200, small = true)
    }

    private fun voidText(v: VoidReason) = when (v) {
        VoidReason.LINK_LOST -> "The link was lost."
        VoidReason.TIMEOUT -> "No answer for too long."
        VoidReason.REMOTE_ABORT -> "The other player left."
        VoidReason.GAME_VERSION, VoidReason.PROTOCOL_VERSION -> "Different game versions."
        VoidReason.DATA_MISMATCH -> "Different game content."
        VoidReason.HASH_MISMATCH -> "The games disagreed."
        else -> "The link failed (${v.name.lowercase().replace('_', ' ')})."
    }
}
