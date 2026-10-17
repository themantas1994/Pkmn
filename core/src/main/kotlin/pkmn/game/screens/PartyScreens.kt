package pkmn.game.screens

import pkmn.engine.battle.Battle
import pkmn.engine.campaign.GameContext
import pkmn.engine.data.Stat
import pkmn.engine.mon.MoveSlot
import pkmn.engine.mon.Pokemon
import pkmn.game.PkmnGame
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.overworld.OverworldScreen
import pkmn.game.ui.Menu
import pkmn.game.ui.TextBox
import pkmn.game.ui.ask
import pkmn.game.ui.say

/** START menu (GSC style column on the right). */
class StartMenu(game: PkmnGame, private val ow: OverworldScreen) : Screen(game) {
    override val opaque = false
    private val ctx = ow.ctx
    private var cursor = 0
    private val items: List<String> get() = buildList {
        if (ctx.state.hasFlag("has_pokedex")) add("POKéDEX")
        if (ctx.state.party.isNotEmpty()) add("POKéMON")
        add("BAG"); add("MAP"); add("QUESTS"); add(ctx.state.playerName); add("SAVE"); add("OPTIONS"); add("EXIT")
    }

    override fun update(dt: Float, input: InputState) {
        val list = items
        if (input.repeat(Button.UP)) cursor = (cursor - 1 + list.size) % list.size
        if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % list.size
        if (input.justPressed(Button.B) || input.justPressed(Button.START)) { pop(); return }
        if (!input.justPressed(Button.A)) return
        game.audio.playSfx("menu_select")
        when (val sel = list[cursor]) {
            "POKéDEX" -> push(PokedexScreen(game, ctx))
            "POKéMON" -> push(PartyScreen(game, ctx, PartyScreen.Mode.OVERWORLD, null, false, ow) {})
            "BAG" -> push(BagScreen(game, ctx, BagScreen.Mode.OVERWORLD, null, ow) { _, _ -> })
            "MAP" -> push(TownMapScreen(game, ctx, ow, flyMode = false) {})
            "QUESTS" -> push(QuestScreen(game, ctx))
            "SAVE" -> game.ask("Would you like to save the game?") { yes ->
                if (yes) { val ok = ow.save(); game.audio.playSfx("save"); game.say(if (ok) "${ctx.state.playerName} saved the game." else "Saving failed! Please try again.") }
            }
            "OPTIONS" -> push(OptionsScreen(game) {})
            "EXIT" -> pop()
            else -> if (sel == ctx.state.playerName) push(TrainerCardScreen(game, ctx))
        }
    }

    override fun onBack(): Boolean { pop(); return true }

    override fun draw(r: Renderer) {
        val list = items
        val h = list.size * 16 + 16
        r.frame(Renderer.W - 96, 0, 96, h)
        list.forEachIndexed { i, s ->
            r.text(s, Renderer.W - 80, 10 + i * 16)
            if (i == cursor) r.cursor(Renderer.W - 90, 10 + i * 16)
        }
    }
}

/** Party list used in the overworld, in battle (switching) and for choosing item/TM targets. */
class PartyScreen(
    game: PkmnGame,
    private val ctx: GameContext,
    private val mode: Mode,
    private val battle: Battle?,
    private val forced: Boolean,
    private val ow: OverworldScreen? = null,
    private val prompt: String? = null,
    /** Shift battle style: a free switch before the foe's next Pokémon, cancellable and not blocked by trapping. */
    private val shift: Boolean = false,
    private val onChoose: (Int?) -> Unit,
) : Screen(game) {
    enum class Mode { OVERWORLD, BATTLE, CHOOSE }

    private var cursor = 0
    private var swapFrom = -1
    private val party get() = ctx.state.party
    private val dex = ctx.dex

    override fun update(dt: Float, input: InputState) {
        if (party.isEmpty()) { pop(); onChoose(null); return }
        if (input.repeat(Button.UP)) cursor = (cursor - 1 + party.size) % party.size
        if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % party.size
        if (input.justPressed(Button.B) && !forced) {
            if (swapFrom >= 0) { swapFrom = -1; return }
            pop(); onChoose(null); return
        }
        if (!input.justPressed(Button.A)) return
        game.audio.playSfx("menu_select")
        if (swapFrom >= 0) {
            if (swapFrom != cursor) { val t = party[swapFrom]; party[swapFrom] = party[cursor]; party[cursor] = t }
            swapFrom = -1; return
        }
        val mon = party[cursor]
        when (mode) {
            Mode.CHOOSE -> { pop(); onChoose(cursor) }
            Mode.BATTLE -> {
                val bm = battle!!.player.mons[cursor]
                val opts = listOf("SWITCH", "SUMMARY", "CANCEL")
                push(Menu(game, opts, 160, 112, 88, onCancel = {}) { i ->
                    when (opts[i]) {
                        "SWITCH" -> when {
                            bm.fainted -> game.say("There's no will to fight!")
                            bm === battle.player.active -> game.say("${bm.name} is already in battle!")
                            !forced && !shift && !battle.canSwitchOut(battle.player.active!!) -> game.say("${battle.player.active!!.name} can't be switched out!")
                            else -> { pop(); onChoose(cursor) }
                        }
                        "SUMMARY" -> push(SummaryScreen(game, ctx, mon))
                    }
                })
            }
            Mode.OVERWORLD -> {
                val field = fieldMovesFor(mon)
                // Only the original trainer may rename a Pokémon (traded ones keep their name, as in the official games).
                val own = mon.otId == ctx.state.trainerId && mon.otName == ctx.state.playerName
                val opts = field + listOf("SUMMARY", "SWITCH", "ITEM") + (if (own) listOf("NICKNAME") else emptyList()) + "CANCEL"
                push(Menu(game, opts, 152, 48, 96, onCancel = {}) { i ->
                    when (val o = opts[i]) {
                        "SUMMARY" -> push(SummaryScreen(game, ctx, mon))
                        "NICKNAME" -> renameMenu(mon)
                        "SWITCH" -> swapFrom = cursor
                        "ITEM" -> itemMenu(mon)
                        "CANCEL" -> {}
                        else -> useField(o)
                    }
                })
            }
        }
    }

    private fun fieldMovesFor(mon: Pokemon): List<String> = buildList {
        if (mon.moves.any { it.id == "fly" } && ctx.canUseFieldMove(pkmn.engine.world.FieldMove.FLY)) add("FLY")
        if (mon.moves.any { it.id == "flash" } && ctx.canUseFieldMove(pkmn.engine.world.FieldMove.FLASH)) add("FLASH")
        if (mon.moves.any { it.id == "teleport" }) add("TELEPORT")
        if (mon.moves.any { it.id == "dig" }) add("DIG")
        if (mon.moves.any { it.id == "softboiled" || it.id == "milkdrink" }) add("HEAL ALLY")
    }

    private fun useField(o: String) {
        val owScreen = ow ?: return
        when (o) {
            "FLY" -> if (owScreen.baseMap.indoor) game.say("You can't fly indoors.") else { pop(); game.push(TownMapScreen(game, ctx, owScreen, flyMode = true) {}) }
            "FLASH" -> if (owScreen.baseMap.dark) { owScreen.useFlash(); pop(); game.say("A blinding FLASH lights the area!") } else game.say("It's bright enough already.")
            "TELEPORT", "DIG" -> if (owScreen.baseMap.indoor && o == "TELEPORT") game.say("Can't use that here.") else {
                pop()
                val (m, x, y) = ctx.respawnPoint()
                owScreen.warpTo(m, x, y, pkmn.engine.world.Facing.DOWN) {}
            }
            "HEAL ALLY" -> {
                val user = party[cursor]
                val cost = user.maxHp(ctx.dex) / 5
                if (user.hp <= cost) { game.say("Not enough HP!"); return }
                push(PartyScreen(game, ctx, Mode.CHOOSE, null, false, null, "Heal which POKéMON?") { t ->
                    val target = t?.let { party[it] } ?: return@PartyScreen
                    if (target === user || target.isFainted || target.hp >= target.maxHp(ctx.dex)) { game.say("It won't have any effect."); return@PartyScreen }
                    user.hp -= cost
                    target.hp = minOf(target.maxHp(ctx.dex), target.hp + cost)
                    game.say("${target.displayName(ctx.dex)}'s HP was restored.")
                })
            }
        }
    }

    /** Rename, or restore the species name. Nickname and species stay separate: the summary always shows both. */
    private fun renameMenu(mon: Pokemon) {
        val species = mon.species(dex).name
        val opts = listOf("RENAME") + (if (mon.nickname != null) listOf("RESET NAME") else emptyList()) + "CANCEL"
        push(Menu(game, opts, 152, 96, 96, onCancel = {}) { i ->
            when (opts[i]) {
                "RENAME" -> push(NameInputScreen(game, "${species.uppercase()}'S NICKNAME?", mon.nickname ?: "", pkmn.engine.campaign.Nicknames.MAX_LENGTH,
                    onCancel = {}) { n -> ctx.rename(mon, n)?.let { game.say(it) } })
                "RESET NAME" -> { ctx.rename(mon, null); game.say("${mon.displayName(dex)} is called by its species name again.") }
            }
        })
    }

    private fun itemMenu(mon: Pokemon) {
        val opts = listOf("GIVE", "TAKE", "CANCEL")
        push(Menu(game, opts, 152, 96, 88, onCancel = {}) { i ->
            when (opts[i]) {
                "TAKE" -> {
                    val it = mon.heldItem
                    if (it == null) game.say("${mon.displayName(dex)} isn't holding anything.")
                    else { ctx.state.addItem(it); mon.heldItem = null; game.say("Took the ${ctx.itemName(it)} from ${mon.displayName(dex)}.") }
                }
                "GIVE" -> push(BagScreen(game, ctx, BagScreen.Mode.GIVE, null) { item, _ ->
                    if (item == null) return@BagScreen
                    val old = mon.heldItem
                    ctx.state.removeItem(item)
                    if (old != null) ctx.state.addItem(old)
                    mon.heldItem = item
                    game.say("${mon.displayName(dex)} is now holding the ${ctx.itemName(item)}." + if (old != null) "\nReturned the ${ctx.itemName(old)} to the BAG." else "")
                })
            }
        })
    }

    override fun onBack(): Boolean { if (!forced) { pop(); onChoose(null) }; return true }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        party.forEachIndexed { i, m ->
            val y = 4 + i * 28
            val sel = i == cursor
            if (sel) r.rect(0, y - 2, Renderer.W, 28, Palette.HIGHLIGHT)
            if (i == swapFrom) r.rect(0, y - 2, 4, 28, Palette.BLUE)
            val icon = r.assets.pokemon(m.speciesId, "icon")
            r.draw(icon, 8f, y.toFloat(), 24f, 24f)
            r.text(m.displayName(dex).take(pkmn.engine.campaign.Nicknames.MAX_LENGTH), 40, y + 2)
            r.text("L${m.level}", 144, y + 2)
            m.status?.let { r.text(it.label, 184, y + 2, Palette.RED) }
            if (m.isFainted) r.text("FNT", 184, y + 2, Palette.RED)
            m.heldItem?.let { r.text("●", 32, y + 14, Palette.BLUE, small = true) }
            val max = m.maxHp(dex)
            val f = m.hp.toFloat() / maxOf(1, max)
            r.rect(40, y + 15, 82, 5, Palette.TEXT)
            r.rect(41f, (y + 16).toFloat(), 80 * f, 3f, when { f > 0.5f -> Palette.HP_GREEN; f > 0.2f -> Palette.HP_YELLOW; else -> Palette.HP_RED })
            r.text("${m.hp}/$max", 136, y + 14, small = true)
            if (sel) r.cursor(0, y + 8)
        }
        val msg = prompt ?: if (swapFrom >= 0) "Move to where?" else if (mode == Mode.BATTLE) "Choose a POKéMON." else "Choose a POKéMON."
        r.frame(0, Renderer.H - 32, Renderer.W, 32)
        r.text(msg, 12, Renderer.H - 20)
    }
}

/** Pokémon summary: stats, moves, ability, item, nature. LEFT/RIGHT switch pages. */
class SummaryScreen(game: PkmnGame, private val ctx: GameContext, private val mon: Pokemon) : Screen(game) {
    private companion object { const val PAGES = 4 }
    private var page = 0
    private val dex = ctx.dex
    override fun update(dt: Float, input: InputState) {
        if (input.justPressed(Button.LEFT)) page = (page + PAGES - 1) % PAGES
        if (input.justPressed(Button.RIGHT) || input.justPressed(Button.A)) page = (page + 1) % PAGES
        if (input.justPressed(Button.B)) pop()
    }
    override fun onBack(): Boolean { pop(); return true }

    override fun draw(r: Renderer) {
        val sp = mon.species(dex)
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.draw(r.assets.pokemon(mon.speciesId, if (mon.shiny) "front-shiny" else "front"), 0f, 8f)
        r.text("No.${sp.num.toString().padStart(4, '0')}", 104, 8)
        r.text(mon.displayName(dex), 104, 20)
        r.text("${sp.name}  L${mon.level}", 104, 32)
        sp.types.forEachIndexed { i, t -> r.rect(104 + i * 56, 44, 52, 12, Palette.type(t)); r.text(t.uppercase().take(6), 108 + i * 56, 46, Palette.WHITE, small = true) }
        mon.teraType?.let { r.text("TERA:${it.uppercase()}", 104, 60, Palette.type(it).cpy().mul(0.7f, 0.7f, 0.7f, 1f), small = true) }
        if (mon.shiny) r.text("★", 236, 8, Palette.RED)
        r.text("${page + 1}/$PAGES ◀▶", 196, 88, small = true)
        r.frame(0, 100, Renderer.W, 124)
        when (page) {
            0 -> {
                r.text("OT/${mon.otName}  ID ${mon.otId.toString().padStart(5, '0')}", 12, 110)
                r.text("NATURE/${mon.nature.label.uppercase()}" + (mon.mintNature?.let { " (MINT ${it.label.uppercase()})" } ?: ""), 12, 124)
                r.text("ABILITY/${dex.abilityOrNull(mon.ability(dex))?.name ?: "-"}", 12, 138)
                r.text("ITEM/${mon.heldItem?.let { ctx.itemName(it) } ?: "NONE"}", 12, 152)
                val next = mon.expToNextLevel(dex)
                r.text("EXP ${mon.exp}", 12, 166)
                r.text("TO NEXT LV ${next}", 12, 180)
                r.text("BALL/${ctx.itemName(mon.ball).uppercase()}", 12, 194)
            }
            1 -> {
                r.text("HP     ${mon.hp}/${mon.maxHp(dex)}", 12, 110)
                for ((i, s) in Stat.NON_HP.withIndex()) {
                    val mark = when (s) { mon.statNature.plus -> "+"; mon.statNature.minus -> "-"; else -> " " }
                    r.text("${s.label.uppercase().padEnd(7)}${mon.stat(dex, s).toString().padStart(4)} $mark", 12, 124 + i * 14)
                }
                r.text("EV ${mon.evs.total}/510", 12, 196, small = true)
                r.text(dex.abilityOrNull(mon.ability(dex))?.shortDesc?.take(40) ?: "", 12, 208, small = true)
            }
            2 -> {
                // Individual values (0-31) and effort values (max 252 each, 510 total) behind the stats on page 2.
                r.text("STAT      IV   EV", 12, 110)
                for ((i, s) in Stat.entries.withIndex()) {
                    val mark = when (s) { mon.statNature.plus -> "+"; mon.statNature.minus -> "-"; else -> " " }
                    r.text("${s.label.uppercase().take(7).padEnd(7)}$mark ${mon.ivs[s].toString().padStart(3)}  ${mon.evs[s].toString().padStart(3)}", 12, 124 + i * 12)
                }
                r.text("TOTAL EV ${mon.evs.total}/510", 12, 198, small = true)
            }
            else -> mon.moves.forEachIndexed { i, s ->
                val m = dex.moveOrNull(s.id) ?: return@forEachIndexed
                r.text(m.name.uppercase(), 12, 110 + i * 26)
                r.text("${m.type.uppercase()} ${m.category.take(4).uppercase()}  PP ${s.pp}/${s.maxPp(dex)}", 20, 121 + i * 26, small = true)
            }
        }
    }
}

/** Shared "forget a move to learn a new one?" dialogue used by level-up, TMs, tutors and evolution. */
object MoveLearnFlow {
    fun offer(game: PkmnGame, ctx: GameContext, mon: Pokemon, moveId: String, then: () -> Unit) {
        val dex = ctx.dex
        val move = dex.moveOrNull(moveId) ?: run { then(); return }
        val name = mon.displayName(dex)
        if (mon.moves.any { it.id == move.id }) { game.say("$name already knows ${move.name}.") { then() }; return }
        if (mon.moves.size < 4) {
            mon.moves.add(MoveSlot(move.id, move.pp))
            game.audio.playSfx("level_up")
            game.say("$name learned ${move.name}!") { then() }
            return
        }
        game.ask("$name is trying to learn ${move.name}. But $name can't learn more than four moves. Delete an older move to make room for ${move.name}?") { yes ->
            if (!yes) {
                game.say("$name did not learn ${move.name}.") { then() }
                return@ask
            }
            val names = mon.moves.map { dex.moveOrNull(it.id)?.name ?: it.id } + "CANCEL"
            game.push(Menu(game, names, 96, 40, 156, onCancel = { game.say("$name did not learn ${move.name}.") { then() } }) { i ->
                if (i >= mon.moves.size) { game.say("$name did not learn ${move.name}.") { then() }; return@Menu }
                val old = names[i]
                mon.moves[i] = MoveSlot(move.id, move.pp)
                game.say("1, 2 and... Poof!", "$name forgot $old.", "And... $name learned ${move.name}!") { then() }
            }.also { it.prompt = "Which move should be forgotten?" })
        }
    }
}

/** Evolution sequence; B cancels while the animation runs (classic behaviour). */
class EvolutionScreen(game: PkmnGame, private val ctx: GameContext, private val mon: Pokemon, private val target: String, private val then: () -> Unit) : Screen(game) {
    private var t = 0f
    private var stage = 0 // 0 intro text, 1 animating, 2 done
    private var cancelled = false
    private val oldName = mon.displayName(ctx.dex)
    private val oldSpecies = mon.speciesId

    override fun onEnter() {
        game.audio.playMusic("evolution", 0)
        push(TextBox(game, "What? $oldName is evolving!") { stage = 1; game.audio.playSfx("evolution_start") })
    }

    override fun update(dt: Float, input: InputState) {
        if (stage != 1) return
        t += dt
        if (input.justPressed(Button.B) && t < 4f) {
            cancelled = true; stage = 2
            game.say("Huh? $oldName stopped evolving!") { pop(); then() }
            return
        }
        if (t >= 4.5f) {
            stage = 2
            val newSpecies = ctx.dexState(target) != pkmn.engine.campaign.DexState.CAUGHT
            val pending = ctx.evolve(mon, target)
            game.audio.playSfx("evolution_finish")
            val newName = ctx.dex.species(target).name
            game.say("Congratulations! Your $oldName evolved into $newName!") {
                fun learnNext(i: Int) {
                    if (i >= pending.size) { pop(); then(); return }
                    MoveLearnFlow.offer(game, ctx, mon, pending[i]) { learnNext(i + 1) }
                }
                // A first registration through evolution shows the Pokédex entry too.
                if (newSpecies) game.say("$newName's data was newly registered in the POKéDEX!") {
                    game.push(PokedexEntryScreen(game, ctx, mon) { learnNext(0) })
                } else learnNext(0)
            }
        }
    }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WHITE)
        val showNew = stage == 2 && !cancelled || (stage == 1 && ((t * (1 + t * 2)).toInt() % 2 == 1))
        val tex = r.assets.pokemon(if (showNew) target else oldSpecies, if (mon.shiny) "front-shiny" else "front")
        if (stage == 1) r.tint(com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 1f)) { r.draw(tex, 80f, 40f) } else r.draw(tex, 80f, 40f)
        if (stage == 1 && (t * 6).toInt() % 2 == 0) r.rect(80, 40, 96, 96, com.badlogic.gdx.graphics.Color(1f, 1f, 1f, 0.5f))
    }
}
