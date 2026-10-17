package pkmn.game.screens

import pkmn.engine.campaign.Acquisition
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.Nicknames
import pkmn.engine.data.SpeciesData
import pkmn.engine.mon.Pokemon
import pkmn.game.PkmnGame
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.ui.ask
import pkmn.game.ui.say

/** Draws a Pokédex page (shared by the Pokédex and the registration screen after a capture). */
object PokedexPage {
    fun draw(r: Renderer, ctx: GameContext, sp: SpeciesData, caught: Boolean, shiny: Boolean = false) {
        val text = ctx.dex.dexText[sp.num]
        r.draw(r.assets.pokemon(sp.id, if (shiny) "front-shiny" else "front"), 8f, 16f)
        r.text("No.${sp.num.toString().padStart(4, '0')}", 112, 16)
        r.text(sp.name.uppercase(), 112, 30)
        text?.genus?.takeIf { it.isNotEmpty() }?.let { r.text(it.uppercase().replace("POKÉMON", "POKéMON").take(18), 112, 44, small = true) }
        sp.types.forEachIndexed { i, t -> r.rect(112 + i * 56, 56, 52, 12, Palette.type(t)); r.text(t.uppercase().take(6), 116 + i * 56, 58, Palette.WHITE, small = true) }
        r.text("HT ${sp.heightm} m", 112, 76); r.text("WT ${sp.weightkg} kg", 112, 88)
        r.frame(0, 116, Renderer.W, 108)
        if (!caught) { r.text("Catch it to learn more!", 12, 130); return }
        val lines = wrap(text?.text ?: "", 30).take(6)
        if (lines.isEmpty()) r.text("No data.", 12, 128)
        lines.forEachIndexed { i, l -> r.text(l, 12, 126 + i * 14) }
    }

    fun wrap(s: String, width: Int): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (w in s.split(' ').filter { it.isNotEmpty() }) {
            if (line.isNotEmpty() && line.length + 1 + w.length > width) { out += line.toString(); line = StringBuilder() }
            if (line.isNotEmpty()) line.append(' ')
            line.append(w)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }
}

/**
 * The Pokédex entry shown when a species is registered for the first time. A or B returns to the game through
 * [onClose]; Android Back does the same, so the player can never be stranded here.
 */
class PokedexEntryScreen(game: PkmnGame, private val ctx: GameContext, private val mon: Pokemon, private val onClose: () -> Unit) : Screen(game) {
    private var closed = false
    private var t = 0f
    override fun update(dt: Float, input: InputState) {
        t += dt
        if (t > 0.3f && (input.justPressed(Button.A) || input.justPressed(Button.B) || input.justPressed(Button.START))) close()
    }
    override fun onBack(): Boolean { close(); return true }
    private fun close() { if (closed) return; closed = true; pop(); onClose() }
    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        PokedexPage.draw(r, ctx, mon.species(ctx.dex), caught = true, shiny = mon.shiny)
        r.text("▼", Renderer.W - 16, Renderer.H - 14, small = true)
    }
}

/** The nickname offer made for every Pokémon the player obtains (starter, gifts, captures, duplicates included). */
object NicknameFlow {
    /**
     * "Would you like to give X a nickname?" YES opens the name entry (B on an empty name returns to this question);
     * NO keeps the species name. The choice is applied through the engine, which validates it and closes the pending
     * offer, so a Pokémon is never left half-named.
     */
    fun offer(game: PkmnGame, ctx: GameContext, mon: Pokemon, then: () -> Unit) {
        val name = mon.species(ctx.dex).name
        game.ask("Would you like to give ${mon.displayName(ctx.dex)} a nickname?") { yes ->
            if (!yes) { ctx.resolveNickname(mon.uid, null); then(); return@ask }
            game.push(NameInputScreen(game, "${name.uppercase()}'S NICKNAME?", "", Nicknames.MAX_LENGTH,
                onCancel = { offer(game, ctx, mon, then) }) { entered ->
                val err = ctx.resolveNickname(mon.uid, entered)
                if (err != null) game.say(err) { offer(game, ctx, mon, then) } else then()
            })
        }
    }

    /** Offers a nickname for every pending Pokémon in turn (used to resume after an interruption). */
    fun offerAllPending(game: PkmnGame, ctx: GameContext, then: () -> Unit) {
        val mon = ctx.nextPendingNickname() ?: run { then(); return }
        offer(game, ctx, mon) { offerAllPending(game, ctx, then) }
    }
}

/**
 * After a successful capture (the engine has already stored the Pokémon and registered it): a first registration shows
 * the Pokédex entry, a repeat shows a one-line summary; then the nickname offer and where the Pokémon went.
 * [save] is called once the state is final.
 */
object CaptureFlow {
    fun present(game: PkmnGame, ctx: GameContext, acq: Acquisition, save: () -> Unit, then: () -> Unit) {
        val mon = acq.mon
        val sp = mon.species(ctx.dex)
        val finish = {
            val where = acq.where
            val stored = if (where != null && where.startsWith("box:")) {
                val box = where.removePrefix("box:").toInt() + 1
                "${mon.displayName(ctx.dex)} was sent to BOX $box on the PC."
            } else null
            save()
            if (stored != null) game.say(stored) { then() } else then()
        }
        val nickname = {
            if (mon.uid in ctx.state.pendingNicknames) NicknameFlow.offer(game, ctx, mon) { finish() } else finish()
        }
        if (acq.newSpecies) {
            game.say("${sp.name}'s data was newly registered in the POKéDEX!") {
                game.push(PokedexEntryScreen(game, ctx, mon) { nickname() })
            }
        } else {
            val num = sp.num.toString().padStart(4, '0')
            game.say("${sp.name} (No.$num) is already registered in the POKéDEX." + if (acq.newForm) "\nA new form was recorded!" else "") { nickname() }
        }
    }
}
