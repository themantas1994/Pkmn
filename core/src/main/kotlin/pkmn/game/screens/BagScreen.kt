package pkmn.game.screens

import pkmn.engine.battle.BagItemEffects
import pkmn.engine.battle.Battle
import pkmn.engine.campaign.FieldItems
import pkmn.engine.campaign.GameContext
import pkmn.engine.data.BagPocket
import pkmn.engine.data.ItemCategory
import pkmn.game.PkmnGame
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.overworld.OverworldScreen
import pkmn.game.ui.Menu
import pkmn.game.ui.say

/**
 * The bag, organised in pockets. Modes: OVERWORLD (use/give/toss), BATTLE (usable battle items and balls),
 * GIVE (pick a holdable item). [onPick] receives (itemId, targetTeamIndex) or (null, null) on cancel.
 */
class BagScreen(
    game: PkmnGame,
    private val ctx: GameContext,
    private val mode: Mode,
    private val battle: Battle?,
    private val ow: OverworldScreen? = null,
    private val onPick: (String?, Int?) -> Unit,
) : Screen(game) {
    enum class Mode { OVERWORLD, BATTLE, GIVE }

    private val pockets = listOf(BagPocket.ITEMS, BagPocket.MEDICINE, BagPocket.BALLS, BagPocket.BERRIES, BagPocket.TMS, BagPocket.KEY)
    private var pocket = if (mode == Mode.BATTLE) 1 else 0
    private var cursor = 0
    private var scroll = 0
    private val dex = ctx.dex
    private val field = FieldItems(ctx)

    private fun pocketOf(id: String): BagPocket = if (id.startsWith("tm:")) BagPocket.TMS else dex.itemOrNull(id)?.pocket ?: BagPocket.ITEMS

    private fun items(): List<Pair<String, Int>> = ctx.state.bag.entries
        .filter { pocketOf(it.key) == pockets[pocket] }
        .map { it.key to it.value }
        .sortedBy { if (it.first.startsWith("tm:")) it.first else (dex.itemOrNull(it.first)?.num ?: 0).toString().padStart(6, '0') }

    override fun update(dt: Float, input: InputState) {
        val list = items()
        if (input.justPressed(Button.LEFT)) { pocket = (pocket + pockets.size - 1) % pockets.size; cursor = 0; scroll = 0 }
        if (input.justPressed(Button.RIGHT)) { pocket = (pocket + 1) % pockets.size; cursor = 0; scroll = 0 }
        val n = list.size + 1 // last entry = CANCEL
        if (input.repeat(Button.UP)) cursor = (cursor - 1 + n) % n
        if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % n
        if (cursor < scroll) scroll = cursor
        if (cursor >= scroll + 8) scroll = cursor - 7
        if (input.justPressed(Button.B)) { close(null, null); return }
        if (!input.justPressed(Button.A)) return
        if (cursor >= list.size) { close(null, null); return }
        val id = list[cursor].first
        game.audio.playSfx("menu_select")
        when (mode) {
            Mode.GIVE -> {
                val d = dex.itemOrNull(id)
                if (id.startsWith("tm:") || d == null || d.category == ItemCategory.KEY) game.say("That can't be held.") else close(id, null)
            }
            Mode.BATTLE -> battleUse(id)
            Mode.OVERWORLD -> overworldMenu(id)
        }
    }

    private fun close(item: String?, target: Int?) { pop(); onPick(item, target) }

    private fun battleUse(id: String) {
        val d = dex.itemOrNull(id)
        if (d?.isPokeball == true) {
            if (battle?.opponent?.isWild == true) close(id, null) else game.say("You can't use that here!")
            return
        }
        if (!BagItemEffects.isUsableInBattle(id)) { game.say("${ctx.state.playerName}: This isn't the time to use that!"); return }
        if (BagItemEffects.needsTarget(id)) {
            push(PartyScreen(game, ctx, PartyScreen.Mode.CHOOSE, null, false, null, "Use on which POKéMON?") { t -> if (t != null) close(id, t) })
        } else close(id, battle?.player?.mons?.indexOf(battle.player.active))
    }

    private fun overworldMenu(id: String) {
        val opts = buildList {
            add("USE")
            val d = dex.itemOrNull(id)
            if (d != null && d.category != ItemCategory.KEY && !id.startsWith("tm:")) add("GIVE")
            if (d?.category == ItemCategory.KEY && id in setOf("bicycle")) add("REGISTER")
            if (d != null && d.category != ItemCategory.KEY && !id.startsWith("tm:")) add("TOSS")
            add("CANCEL")
        }
        push(Menu(game, opts, 152, 80, 88, onCancel = {}) { i ->
            when (opts[i]) {
                "USE" -> use(id)
                "GIVE" -> push(PartyScreen(game, ctx, PartyScreen.Mode.CHOOSE, null, false, null, "Give to which POKéMON?") { t ->
                    val mon = t?.let { ctx.state.party[it] } ?: return@PartyScreen
                    val old = mon.heldItem
                    if (old != null && old != id && ctx.state.itemRoom(old) < 1) { game.say("The BAG has no room for the ${ctx.itemName(old)}."); return@PartyScreen }
                    ctx.state.removeItem(id)
                    if (old != null) ctx.state.addItem(old)
                    mon.heldItem = id
                    game.say("${mon.displayName(dex)} is now holding the ${ctx.itemName(id)}.")
                })
                "REGISTER" -> { ctx.state.registeredItem = id; game.say("Registered the ${ctx.itemName(id)} to SELECT.") }
                "TOSS" -> push(pkmn.game.ui.NumberPicker(game, ctx.state.itemCount(id), { "TOSS ×$it" }) { n ->
                    if (n != null) { ctx.state.removeItem(id, n); game.say("Threw away ${ctx.itemName(id)}.") }
                })
            }
        })
    }

    private fun use(id: String) {
        val owScreen = ow
        when {
            id == "bicycle" -> { pop(); owScreen?.toggleBike(); return }
            id in setOf("oldrod", "goodrod", "superrod") -> { pop(); owScreen?.fish(id); return }
            id == "escaperope" -> {
                if (owScreen == null || !(owScreen.baseMap.environment == "cave" || owScreen.baseMap.dark)) { game.say("This isn't the time to use that!"); return }
                ctx.state.removeItem(id); pop()
                val (m, x, y) = ctx.respawnPoint()
                owScreen.warpTo(m, x, y, pkmn.engine.world.Facing.DOWN) {}
                return
            }
            id == "townmap" -> { if (owScreen != null) push(TownMapScreen(game, ctx, owScreen, false) {}); return }
            id == "expshare" -> {
                ctx.state.options.expShare = !ctx.state.options.expShare
                game.options.expShare = ctx.state.options.expShare
                game.say("The Exp. Share was turned ${if (game.options.expShare) "on" else "off"}."); return
            }
        }
        field.useDirect(id)?.let { r -> if (r.ok && r.consumed) ctx.state.removeItem(id); game.say(r.message); return }
        if (!field.needsTarget(id)) { game.say("${ctx.state.playerName}: This isn't the time to use that!"); return }
        push(PartyScreen(game, ctx, PartyScreen.Mode.CHOOSE, null, false, null, "Use on which POKéMON?") { t ->
            val mon = t?.let { ctx.state.party[it] } ?: return@PartyScreen
            val r = field.useOn(mon, id)
            if (!r.ok) { game.say(r.message); return@PartyScreen }
            if (r.consumed) ctx.state.removeItem(id)
            if (id in setOf("hpup", "protein", "iron", "calcium", "zinc", "carbos") || id.endsWith("potion") || r.message.isNotEmpty()) game.audio.playSfx("heal")
            val evolveTo = r.evolveTo
            val teach = r.teach
            val afterMoves: () -> Unit = {
                // Stones and the Linking Cord are spent only if the evolution completes; B-cancel keeps the item.
                if (evolveTo != null) push(EvolutionScreen(game, ctx, mon, evolveTo, spendOnEvolve = id.takeIf { r.consumeOnEvolve }) {})
            }
            when {
                teach != null -> MoveLearnFlow.offer(game, ctx, mon, teach) {}
                r.newMoves.isNotEmpty() -> game.say(r.message) {
                    fun next(i: Int) { if (i >= r.newMoves.size) afterMoves() else MoveLearnFlow.offer(game, ctx, mon, r.newMoves[i]) { next(i + 1) } }
                    next(0)
                }
                r.message.isEmpty() -> afterMoves()
                else -> game.say(r.message) { afterMoves() }
            }
        })
    }

    override fun onBack(): Boolean { close(null, null); return true }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        val names = listOf("ITEMS", "MEDICINE", "BALLS", "BERRIES", "TMs", "KEY ITEMS")
        r.frame(0, 0, Renderer.W, 24)
        r.text("◀ ${names[pocket]} ▶", 12, 8)
        val list = items()
        r.frame(0, 24, Renderer.W, 144)
        for (i in 0 until 8) {
            val idx = scroll + i
            if (idx > list.size) break
            val y = 34 + i * 16
            if (idx == list.size) r.text("CANCEL", 20, y)
            else {
                val (id, qty) = list[idx]
                val ballIcon = if (dex.itemOrNull(id)?.isPokeball == true) r.assets.ball(id) else null
                if (ballIcon != null) {
                    r.draw(ballIcon, 20, y - 4)
                    r.text(ctx.itemName(id).uppercase().take(18), 40, y)
                } else r.text(ctx.itemName(id).uppercase().take(20), 20, y)
                if (dex.itemOrNull(id)?.category != ItemCategory.KEY && !id.startsWith("tm:")) r.text("×${qty.toString().padStart(3)}", 204, y)
            }
            if (idx == cursor) r.cursor(10, y)
        }
        r.frame(0, 168, Renderer.W, 56)
        val sel = list.getOrNull(cursor)?.first
        val desc = when {
            sel == null -> "Close the BAG."
            sel.startsWith("tm:") -> dex.moveOrNull(sel.removePrefix("tm:"))?.shortDesc ?: ""
            else -> dex.itemOrNull(sel)?.shortDesc ?: ""
        }
        r.assets.font6.wrap(desc, 40).take(3).forEachIndexed { i, l -> r.text(l, 10, 178 + i * 12, small = true) }
    }
}
