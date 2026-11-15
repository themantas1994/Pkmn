package pkmn.game.link

import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.GameTradeStore
import pkmn.engine.link.LinkRole
import pkmn.engine.link.LinkTransport
import pkmn.engine.link.TradeTransaction
import pkmn.engine.mon.Pokemon
import pkmn.engine.save.GameState
import pkmn.engine.save.SaveResult
import pkmn.game.PkmnGame
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.overworld.OverworldScreen
import pkmn.game.screens.EvolutionScreen
import pkmn.game.screens.Screen
import pkmn.game.ui.say

/**
 * A link trade, on the transport the lobby handed over: pick one party Pokémon, see both offers, confirm, the two
 * Pokémon swap on screen, then the result (and an evolution the trade causes). The rules of the trade, and the save
 * that makes it safe against a crash or a lost link, live in [TradeTransaction] and [GameTradeStore]; this screen only
 * shows them and feeds in the player's choices. While the game saves, nothing is accepted. Closing this screen before
 * the trade is prepared ends it with nothing changed.
 */
class LinkTradeScreen(
    game: PkmnGame,
    private val ctx: GameContext,
    role: LinkRole,
    private val transport: LinkTransport,
    remoteName: String,
    /** Automation fault only ([AutoLink.dropFromPrepared]). */
    dropFromPrepared: Boolean = false,
) : Screen(game), LinkAware, LinkDebug {
    private enum class Phase { SYNC, PICK, WAIT_OFFER, CONFIRM, WAIT_CONFIRM, SAVING, SCENE, MESSAGE, CLOSING }

    override val blocksAutosave get() = true
    private val dex = ctx.dex
    private val store = GameTradeStore(ctx) { state -> saveGame(state) }
    private val tx = TradeTransaction(role, if (dropFromPrepared) DropFromPrepared(transport) else transport, dex, store, ctx.state.playerName, remoteName)
    private var phase = Phase.SYNC
    private var cursor = 0
    private var noticeShown = false
    private var savingTicks = 0
    private var sceneT = 0f
    private val party get() = ctx.state.party

    private val sceneSeconds get() = if (game.options.reduceMotion) 1.2f else SCENE_SECONDS

    private companion object {
        /** "Saving..." stays up at least this many frames, so the player can read it (a local save takes a few). */
        const val MIN_SAVING_TICKS = 40
        const val SCENE_SECONDS = 3.4f
    }

    private fun now() = System.nanoTime() / 1_000_000

    /** The save the trade journal goes through: the same file as the menu's SAVE, with the player's current place. */
    private fun saveGame(state: GameState): SaveResult {
        game.screens().filterIsInstance<OverworldScreen>().lastOrNull()?.let { ow ->
            state.map = ow.baseMap.id; state.x = ow.player.x; state.y = ow.player.y; state.facing = ow.player.facing.name.lowercase()
        }
        ctx.syncRng()
        return game.saves.save(state)
    }

    /** Saves after the evolution that followed the trade; a failure is told to the player. */
    private fun saveAgain() {
        val ok = try { saveGame(ctx.state) == SaveResult.Saved } catch (e: Exception) { game.platform.log("Save failed: $e"); false }
        if (!ok) game.say("The game could not be saved. Save again from the menu.")
    }

    // ================================================================= update

    override fun linkTick() { tx.pump(now()) }
    override fun onAppPaused() {}

    override fun debugState(): String = "trade=${view()} stage=${tx.stage}"

    private fun view(): Phase {
        if (phase >= Phase.SAVING) return phase
        // A trade that ended without a swap is only waiting for its message (also while a text box covers this screen).
        tx.result?.let { if (it !is TradeTransaction.Result.Completed) return Phase.CLOSING }
        val p = when {
            tx.stage == TradeTransaction.Stage.SYNC -> Phase.SYNC
            tx.stage == TradeTransaction.Stage.OFFER -> if (tx.localOffer == null) Phase.PICK else Phase.WAIT_OFFER
            tx.stage == TradeTransaction.Stage.CONFIRM && !tx.saving -> if (tx.localConfirmed) Phase.WAIT_CONFIRM else Phase.CONFIRM
            else -> Phase.SAVING
        }
        if (p == Phase.SAVING) { phase = Phase.SAVING; savingTicks = 0 }
        return p
    }

    override fun update(dt: Float, input: InputState) {
        tx.pump(now())
        if (phase == Phase.CLOSING || phase == Phase.MESSAGE) return
        if (!noticeShown) tx.notice?.let { n ->
            noticeShown = true
            // A trade finished by the reconnect rule still gets its trade evolution.
            val lt = store.lastTrade; val to = lt?.evolveTo
            game.say(n) { if (lt != null && to != null) game.push(EvolutionScreen(game, ctx, lt.received, to, tradeEvolution = true) { saveAgain() }) }
            return
        }
        val v = view()
        tx.result?.let { res ->
            if (res is TradeTransaction.Result.Completed) {
                // The swap went through (possibly within one pump): "Saving..." still gets its moment.
                if (phase < Phase.SAVING) { phase = Phase.SAVING; savingTicks = 0 }
                if (phase == Phase.SAVING && ++savingTicks >= MIN_SAVING_TICKS) startScene()
                else if (phase == Phase.SCENE) updateScene(dt)
            } else if (phase != Phase.SCENE) end(res)
            return
        }
        when (v) {
            Phase.SYNC, Phase.WAIT_OFFER -> {}
            // Confirmed and waiting for the other player: B still backs out until this device has saved.
            Phase.WAIT_CONFIRM -> if (input.justPressed(Button.B)) tx.cancel()
            Phase.SAVING -> savingTicks++
            Phase.PICK -> updatePick(input)
            Phase.CONFIRM -> updateConfirm(input)
            Phase.SCENE -> updateScene(dt)
            else -> {}
        }
    }

    private fun updatePick(input: InputState) {
        val n = party.size
        if (input.repeat(Button.UP)) cursor = (cursor + n - 1) % n
        if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % n
        if (input.justPressed(Button.B)) { tx.cancel(); return }
        if (!input.justPressed(Button.A)) return
        val refusal = tx.offer(party[cursor])
        if (refusal != null) { game.audio.playSfx("error"); game.say(refusal) } else { game.audio.playSfx("menu_select"); cursor = 0 }
    }

    private fun updateConfirm(input: InputState) {
        if (input.repeat(Button.UP) || input.repeat(Button.DOWN)) cursor = 1 - cursor.coerceIn(0, 1)
        if (input.justPressed(Button.B)) { tx.cancel(); return }
        if (!input.justPressed(Button.A)) return
        game.audio.playSfx("menu_select")
        if (cursor == 0) tx.confirm(true) else tx.cancel()
    }

    private fun startScene() {
        if (phase == Phase.SCENE) return
        phase = Phase.SCENE; sceneT = 0f
        game.audio.playSfx("trade")
    }

    private fun updateScene(dt: Float) {
        sceneT += dt
        if (sceneT < sceneSeconds) return
        phase = Phase.MESSAGE
        val given = tx.localOffer?.displayName(dex) ?: "a POKéMON"
        val lt = store.lastTrade
        val received = lt?.received?.displayName(dex) ?: (tx.result as? TradeTransaction.Result.Completed)?.received?.displayName(dex) ?: "a POKéMON"
        game.say("${ctx.state.playerName} traded $given for $received!") {
            val to = lt?.evolveTo
            if (lt != null && to != null) game.push(EvolutionScreen(game, ctx, lt.received, to, tradeEvolution = true) { saveAgain(); finish() })
            else finish()
        }
    }

    private fun end(res: TradeTransaction.Result) {
        phase = Phase.CLOSING
        val text = when (res) {
            is TradeTransaction.Result.Cancelled -> res.reason
            is TradeTransaction.Result.Interrupted -> res.reason + " Link with ${tx.remoteName} again to finish the trade. It stays open until then, also if the game is closed."
            is TradeTransaction.Result.Completed -> return
        }
        game.audio.playSfx("error")
        game.say(text) { finish() }
    }

    private fun finish() { phase = Phase.CLOSING; pop() }

    override fun onBack(): Boolean {
        when (view()) {
            Phase.SYNC, Phase.PICK, Phase.WAIT_OFFER, Phase.CONFIRM, Phase.WAIT_CONFIRM -> tx.cancel()
            else -> {}
        }
        return true
    }

    override fun onExit() {
        // Closed from outside before the trade ended: back out if it is still allowed, else just drop the link
        // (a prepared trade keeps its journal and finishes on the next link with the same friend).
        if (!tx.isOver) { tx.cancel(); if (!tx.isOver) transport.close() }
    }

    // ================================================================= drawing

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        when (val v = view()) {
            Phase.SYNC -> { r.frame(0, 0, Renderer.W, Renderer.H); r.text("TRADE", 16, 10); r.text("Checking with ${tx.remoteName}...", 16, 40) }
            Phase.PICK -> drawPick(r)
            Phase.SCENE, Phase.MESSAGE -> drawScene(r)
            Phase.CLOSING -> { r.frame(0, 0, Renderer.W, Renderer.H); r.text("TRADE", 16, 10) }
            else -> {
                drawOffers(r)
                r.frame(0, 168, Renderer.W, 56)
                when (v) {
                    Phase.WAIT_OFFER -> r.text("Waiting for ${tx.remoteName.take(12)}...", 12, 184)
                    Phase.CONFIRM -> {
                        r.text("CONFIRM", 32, 180); r.text("CANCEL", 32, 198); r.cursor(18, 180 + cursor.coerceIn(0, 1) * 18)
                        r.text("Trade these two?", 112, 188, small = true)
                    }
                    Phase.WAIT_CONFIRM -> { r.text("Waiting for ${tx.remoteName.take(12)}...", 12, 180); r.text("B: CANCEL", 12, 200, small = true) }
                    Phase.SAVING -> { r.text("Saving...", 12, 180); r.text("Please wait. Don't turn the game off.", 12, 200, small = true) }
                    else -> {}
                }
            }
        }
    }

    /** Your party on the left (pick with the cursor), the friend's on the right (read-only). */
    private fun drawPick(r: Renderer) {
        r.text(tx.localName.take(20), 8, 3, small = true)
        r.text(tx.remoteName.take(20), 136, 3, small = true)
        r.rect(127, 12, 1, 154, Palette.SHADE)
        party.forEachIndexed { i, m ->
            val y = 14 + i * 25
            val sel = i == cursor
            if (sel) r.rect(0, y - 2, 127, 25, Palette.HIGHLIGHT)
            val icon = r.assets.icon(m.speciesId, if (game.options.reduceMotion || !sel) 0 else (game.timeMs / 400).toInt())
            r.draw(icon, 10f, y.toFloat(), 24f, 24f)
            r.text(m.displayName(dex).take(10), 38, y + 1)
            val tail = if (m.isFainted) "FNT" else m.status?.label
            r.text("L${m.level}", 38, y + 13, small = true)
            if (tail != null) r.text(tail, 68, y + 13, Palette.RED, small = true)
            else m.heldItem?.let { id -> r.text(ctx.itemName(id).take(10), 68, y + 13, small = true) }
            if (sel) r.cursor(0, y + 8)
        }
        tx.remoteParty.forEachIndexed { i, e ->
            val y = 14 + i * 25
            val name = e.nickname ?: dex.speciesOrNull(e.speciesId)?.name ?: e.speciesId
            r.draw(r.assets.icon(e.speciesId, 0), 138f, y.toFloat(), 24f, 24f)
            r.text(name.take(10), 166, y + 1)
            r.text("L${e.level}" + if (e.shiny) " *" else "", 166, y + 13, small = true)
        }
        if (tx.remoteParty.isEmpty()) r.text("...", 170, 40)
        r.frame(0, 168, Renderer.W, 56)
        r.text("Choose a POKéMON to trade.", 12, 180)
        r.text("B: CANCEL", 12, 204, small = true)
    }

    /** Local offer on the left, the friend's on the right (once it is in). */
    private fun drawOffers(r: Renderer) {
        drawOffer(r, 0, tx.localName, tx.localOffer)
        r.rect(127, 12, 1, 152, Palette.SHADE)
        drawOffer(r, 128, tx.remoteName, tx.remoteOffer)
    }

    private fun drawOffer(r: Renderer, x0: Int, owner: String, mon: Pokemon?) {
        r.text(owner.take(20), x0 + 8, 3, small = true)
        if (mon == null) { r.text("...", x0 + 52, 40); return }
        r.draw(r.assets.pokemon(mon.speciesId, if (mon.shiny) "front-shiny" else "front"), (x0 + 40).toFloat(), 14f, 48f, 48f)
        r.text(mon.displayName(dex).take(15), x0 + 8, 66)
        r.text("${mon.species(dex).name.take(12)} L${mon.level}", x0 + 8, 80, small = true)
        r.text("OT: ${mon.otName.take(15)}", x0 + 8, 92, small = true)
        r.text("ITEM: ${mon.heldItem?.let { ctx.itemName(it) }?.take(14) ?: "-"}", x0 + 8, 104, small = true)
        mon.moves.take(4).forEachIndexed { i, m -> r.text("- ${(dex.moveOrNull(m.id)?.name ?: m.id).take(17)}", x0 + 8, 118 + i * 12, small = true) }
    }

    /** The two Pokémon cross over a short arc and end up on the other side. */
    private fun drawScene(r: Renderer) {
        r.frame(0, 0, Renderer.W, Renderer.H)
        r.text("TRADE", 16, 10)
        val mine = tx.localOffer ?: return
        val theirs = tx.remoteOffer ?: return
        val dur = sceneSeconds
        val p = (sceneT / dur).coerceIn(0f, 1f)
        val m = ((p - 0.2f) / 0.6f).coerceIn(0f, 1f)
        val e = m * m * (3 - 2 * m)
        val still = game.options.reduceMotion
        val eased = if (still) (if (p >= 0.5f) 1f else 0f) else e
        val arc = if (still) 0f else Math.sin(Math.PI * m).toFloat() * 30f
        val leftX = 24f; val rightX = 168f
        r.draw(r.assets.pokemon(mine.speciesId, if (mine.shiny) "front-shiny" else "front"), leftX + (rightX - leftX) * eased, 64f - arc, 64f, 64f)
        r.draw(r.assets.pokemon(theirs.speciesId, if (theirs.shiny) "front-shiny" else "front"), rightX + (leftX - rightX) * eased, 64f + arc, 64f, 64f)
        r.text(tx.localName.take(12), 24, 142, small = true)
        r.text(tx.remoteName.take(12), 168, 142, small = true)
        if (p < 1f) r.text("Trading" + ".".repeat(((sceneT * 3).toInt() % 3) + 1), 16, 170)
    }
}

/**
 * Automation fault for the interrupted-trade scene: passes trade frames through until this device sends PREPARED, then
 * silently drops everything it sends (the frames from the other device still arrive). The other device never gets this
 * PREPARED, so it never commits, while this device commits on the other's PREPARED: the one-message window, on purpose.
 */
private class DropFromPrepared(private val inner: LinkTransport) : LinkTransport by inner {
    private var dropping = false
    override fun send(frame: ByteArray) {
        if (!dropping && pkmn.engine.link.TradeCodec.decode(frame) is pkmn.engine.link.TradePrepared) dropping = true
        if (!dropping) inner.send(frame)
    }
}
