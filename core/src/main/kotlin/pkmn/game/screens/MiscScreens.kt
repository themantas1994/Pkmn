package pkmn.game.screens

import com.badlogic.gdx.graphics.Color
import pkmn.engine.campaign.GameContext
import pkmn.engine.mon.Pokemon
import pkmn.engine.world.Facing
import pkmn.game.PkmnGame
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.overworld.OverworldScreen
import pkmn.game.ui.Menu
import pkmn.game.ui.NumberPicker
import pkmn.game.ui.ask
import pkmn.game.ui.say

/** National Pokédex list with seen/caught markers and a detail page. */
class PokedexScreen(game: PkmnGame, private val ctx: GameContext) : Screen(game) {
    private val list = ctx.dex.nationalDex
    private var cursor = 0
    private var scroll = 0
    private var detail = false

    override fun update(dt: Float, input: InputState) {
        if (detail) { if (input.justPressed(Button.B) || input.justPressed(Button.A)) detail = false; return }
        if (input.repeat(Button.UP)) cursor = (cursor - 1 + list.size) % list.size
        if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % list.size
        if (input.repeat(Button.LEFT)) cursor = maxOf(0, cursor - 9)
        if (input.repeat(Button.RIGHT)) cursor = minOf(list.size - 1, cursor + 9)
        if (cursor < scroll) scroll = cursor
        if (cursor >= scroll + 11) scroll = cursor - 10
        if (input.justPressed(Button.A) && list[cursor].id in ctx.state.seen) detail = true
        if (input.justPressed(Button.B)) pop()
    }
    override fun onBack(): Boolean { if (detail) detail = false else pop(); return true }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        val st = ctx.state
        if (detail) {
            val sp = list[cursor]
            PokedexPage.draw(r, ctx, sp, caught = sp.id in st.caught, shiny = false)
            if (sp.id in st.caught) {
                val forms = st.formsCaught.count { ctx.dex.speciesOrNull(it)?.baseSpecies == sp.id && it != sp.id }
                val marks = listOfNotNull("CAUGHT", if (sp.id in st.shiniesCaught) "SHINY ★" else null, if (forms > 0) "+$forms FORM${if (forms > 1) "S" else ""}" else null)
                r.text(marks.joinToString("  "), 112, 100, Palette.RED, small = true)
            }
            return
        }
        r.frame(0, 0, Renderer.W, 24)
        r.text("POKéDEX  SEEN ${st.seen.size}  OWN ${st.caught.size}", 8, 8)
        for (i in 0 until 11) {
            val idx = scroll + i
            if (idx >= list.size) break
            val sp = list[idx]
            val y = 30 + i * 16
            val seen = sp.id in st.seen
            r.text(sp.num.toString().padStart(4, '0'), 18, y)
            if (sp.id in st.caught) r.text("●", 56, y, Palette.RED)
            r.text(if (seen) sp.name.uppercase().take(14) else "-----", 68, y)
            if (idx == cursor) r.cursor(8, y)
        }
        val sp = list[cursor]
        if (sp.id in st.seen) r.draw(r.assets.pokemon(sp.id), 168f, 60f, 80f, 80f)
    }
}

/** Mart: buy and sell. */
class ShopScreen(game: PkmnGame, private val ctx: GameContext, private val shopId: String, private val onClose: () -> Unit) : Screen(game) {
    override val opaque = false
    private val shop = ctx.content.shops[shopId]
    private var started = false

    private fun price(id: String): Int = shop?.prices?.get(id) ?: if (id.startsWith("tm:")) 3000 else ctx.dex.itemOrNull(id)?.price?.takeIf { it > 0 } ?: 100

    override fun update(dt: Float, input: InputState) {
        if (started) return
        started = true
        mainMenu("Welcome! How may I help you?")
    }

    private fun mainMenu(msg: String) {
        val opts = listOf("BUY", "SELL", "QUIT")
        push(Menu(game, opts, 8, 8, 72, onCancel = { close() }) { i ->
            when (i) { 0 -> buy(); 1 -> sell(); else -> close() }
        }.also { it.prompt = msg })
    }

    private fun close() { game.say("Please come again!") { pop(); onClose() } }

    private fun buy() {
        val items = shop?.items ?: emptyList()
        val labels = items.map { "${ctx.itemName(it).uppercase().take(14).padEnd(14)} ¥${price(it)}" } + "CANCEL"
        push(Menu(game, labels, 8, 8, 236, visible = minOf(8, labels.size), onCancel = { mainMenu("Is there anything else?") }) { i ->
            if (i >= items.size) { mainMenu("Is there anything else?"); return@Menu }
            val id = items[i]
            val p = price(id)
            val max = if (id.startsWith("tm:")) 1 else minOf(99, ctx.state.money / maxOf(1, p))
            if (max <= 0) { game.say("You don't have enough money.") { buy() }; return@Menu }
            push(NumberPicker(game, max, { "×$it  ¥${it * p}" }) { n ->
                if (n == null) { buy(); return@NumberPicker }
                game.ask("${ctx.itemName(id)}, and you want $n. That will be ¥${n * p}. OK?") { yes ->
                    if (yes) {
                        ctx.state.money -= n * p
                        ctx.state.addItem(id, n)
                        game.audio.playSfx("item_get")
                        val bonus = if (id == "pokeball" && n >= 10) { ctx.state.addItem("premierball", n / 10); "\nHere, have ${n / 10} PREMIER BALL as a bonus!" } else ""
                        game.say("Here you are! Thank you!$bonus") { buy() }
                    } else buy()
                }
            })
        }.also { it.prompt = "Money: ¥${ctx.state.money}" })
    }

    private fun sell() {
        val items = ctx.state.bag.keys.filter { ctx.dex.itemOrNull(it)?.let { d -> d.price > 0 && d.category != pkmn.engine.data.ItemCategory.KEY } == true }
        if (items.isEmpty()) { game.say("You don't have anything I can buy.") { mainMenu("Is there anything else?") }; return }
        val labels = items.map { "${ctx.itemName(it).uppercase().take(14).padEnd(14)} ×${ctx.state.itemCount(it)}" } + "CANCEL"
        push(Menu(game, labels, 8, 8, 236, visible = minOf(8, labels.size), onCancel = { mainMenu("Is there anything else?") }) { i ->
            if (i >= items.size) { mainMenu("Is there anything else?"); return@Menu }
            val id = items[i]
            val p = price(id) / 2
            push(NumberPicker(game, ctx.state.itemCount(id), { "×$it  ¥${it * p}" }) { n ->
                if (n == null) { sell(); return@NumberPicker }
                ctx.state.removeItem(id, n); ctx.state.money += n * p
                game.say("Sold for ¥${n * p}.") { sell() }
            })
        })
    }

    override fun draw(r: Renderer) {}
}

/** PC storage: withdraw, deposit, release. */
class PcScreen(game: PkmnGame, private val ctx: GameContext, private val onClose: () -> Unit = {}) : Screen(game) {
    override val opaque = false
    private var started = false
    override fun update(dt: Float, input: InputState) {
        if (started) return
        started = true
        game.say("${ctx.state.playerName} turned on the PC.") { menu() }
    }

    private fun menu() {
        val opts = listOf("WITHDRAW POKéMON", "DEPOSIT POKéMON", "RELEASE POKéMON", "LOG OFF")
        push(Menu(game, opts, 8, 8, 176, onCancel = { close() }) { i ->
            when (i) { 0 -> push(BoxScreen(game, ctx, BoxScreen.Op.WITHDRAW) { menu() }); 1 -> deposit(); 2 -> push(BoxScreen(game, ctx, BoxScreen.Op.RELEASE) { menu() }); else -> close() }
        })
    }

    private fun deposit() {
        if (ctx.state.party.size <= 1) { game.say("You can't deposit your last POKéMON!") { menu() }; return }
        push(PartyScreen(game, ctx, PartyScreen.Mode.CHOOSE, null, false, null, "Deposit which POKéMON?") { t ->
            if (t == null) { menu(); return@PartyScreen }
            val mon = ctx.state.party[t]
            val box = ctx.state.boxes.firstOrNull { b -> b.any { it == null } }
            if (box == null) { game.say("The PC is full!") { menu() }; return@PartyScreen }
            box[box.indexOf(null)] = mon
            ctx.state.party.removeAt(t)
            game.say("Stored ${mon.displayName(ctx.dex)} in the PC.") { menu() }
        })
    }

    private fun close() { game.audio.playSfx("pc_off"); pop(); onClose() }
    override fun draw(r: Renderer) {}
}

/** Box viewer for withdrawing/releasing. */
class BoxScreen(game: PkmnGame, private val ctx: GameContext, private val op: Op, private val onDone: () -> Unit) : Screen(game) {
    enum class Op { WITHDRAW, RELEASE }
    private var box = 0
    private var cursor = 0

    override fun update(dt: Float, input: InputState) {
        val boxes = ctx.state.boxes
        if (input.repeat(Button.LEFT)) cursor = (cursor + 29) % 30
        if (input.repeat(Button.RIGHT)) cursor = (cursor + 1) % 30
        if (input.repeat(Button.UP)) cursor = (cursor + 24) % 30
        if (input.repeat(Button.DOWN)) cursor = (cursor + 6) % 30
        if (input.justPressed(Button.SELECT) || input.justPressed(Button.START)) box = (box + 1) % boxes.size
        if (input.justPressed(Button.B)) { pop(); onDone(); return }
        if (!input.justPressed(Button.A)) return
        val mon = boxes[box][cursor] ?: return
        when (op) {
            Op.WITHDRAW -> {
                if (ctx.state.party.size >= 6) { game.say("Your party is full!"); return }
                boxes[box][cursor] = null
                ctx.state.party.add(mon)
                game.say("Withdrew ${mon.displayName(ctx.dex)}.")
            }
            Op.RELEASE -> game.ask("Release ${mon.displayName(ctx.dex)}? It won't come back.") { yes ->
                if (yes) { boxes[box][cursor] = null; game.say("${mon.displayName(ctx.dex)} was released outside. Bye-bye, ${mon.displayName(ctx.dex)}!") }
            }
        }
    }
    override fun onBack(): Boolean { pop(); onDone(); return true }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.frame(0, 0, Renderer.W, 24)
        r.text("BOX ${box + 1}  (SELECT: next box)", 8, 8)
        val b = ctx.state.boxes[box]
        for (i in 0 until 30) {
            val x = 8 + (i % 6) * 40; val y = 30 + (i / 6) * 28
            r.rect(x, y, 36, 26, if (i == cursor) Palette.HIGHLIGHT else Palette.SHADE.cpy().also { it.a = 0.3f })
            b[i]?.let { r.draw(r.assets.pokemon(it.speciesId, "icon"), x + 6f, y + 1f, 24f, 24f) }
        }
        val sel = b[cursor]
        r.frame(0, 172, Renderer.W, 52)
        if (sel != null) {
            r.text("${sel.displayName(ctx.dex)}  L${sel.level}", 12, 182)
            r.text(sel.species(ctx.dex).types.joinToString("/").uppercase(), 12, 196)
        } else r.text(if (op == Op.WITHDRAW) "Choose a POKéMON to withdraw." else "Choose a POKéMON to release.", 12, 182)
    }
}

/** Region map; in fly mode lets the player choose a visited town. */
class TownMapScreen(game: PkmnGame, private val ctx: GameContext, private val ow: OverworldScreen, private val flyMode: Boolean, private val onClose: () -> Unit) : Screen(game) {
    private val towns = ctx.content.index.maps.mapNotNull { ctx.content.mapOrNull(it) }.filter { it.flyX >= 0 && it.regionX >= 0 }
    private val visitedTowns get() = towns.filter { it.id in ctx.state.visited }

    /** The player's position on the region map: interiors resolve to the outdoor map they were entered from. */
    private val here: pkmn.engine.world.MapData? = ctx.content.mapOrNull(ctx.state.map)?.let { m ->
        if (m.regionX >= 0) m
        else ctx.content.mapOrNull(ctx.state.backMap)?.takeIf { it.regionX >= 0 }
            ?: towns.firstOrNull { it.location == m.location }
    }
    private var cursor = run {
        val list = if (flyMode) visitedTowns else towns
        here?.let { h -> list.indexOfFirst { it.id == h.id }.takeIf { it >= 0 } ?: list.indexOfFirst { it.location == h.location } }?.coerceAtLeast(0) ?: 0
    }

    override fun update(dt: Float, input: InputState) {
        val list = if (flyMode) visitedTowns else towns
        if (list.isNotEmpty()) {
            if (input.repeat(Button.UP) || input.repeat(Button.LEFT)) cursor = (cursor - 1 + list.size) % list.size
            if (input.repeat(Button.DOWN) || input.repeat(Button.RIGHT)) cursor = (cursor + 1) % list.size
        }
        if (input.justPressed(Button.B)) { pop(); onClose(); return }
        if (flyMode && input.justPressed(Button.A) && list.isNotEmpty()) {
            val t = list[cursor]
            pop()
            ctx.state.surfing = false
            ow.warpTo(t.id, t.flyX, t.flyY, Facing.DOWN) { onClose() }
        }
    }
    override fun onBack(): Boolean { pop(); onClose(); return true }

    override fun draw(r: Renderer) {
        val art = r.assets.ui("region_map")
        if (art != null) r.draw(art, 0, 0) else {
            r.rect(0, 0, Renderer.W, Renderer.H, com.badlogic.gdx.graphics.Color.valueOf("4878c8"))
            for (m in towns) r.rect(m.regionX * 8 - 6, m.regionY * 8 - 6, 20, 20, com.badlogic.gdx.graphics.Color.valueOf("78c060"))
        }
        for (m in towns) r.rect(m.regionX * 8 + 1, m.regionY * 8 + 1, 6, 6, if (m.id in ctx.state.visited) Palette.RED else Palette.GREY)
        if (here != null && here.regionX >= 0 && (game.timeMs / 300) % 2 == 0L) r.text("★", here.regionX * 8, here.regionY * 8 - 2, Palette.HIGHLIGHT)
        val list = if (flyMode) visitedTowns else towns
        val sel = list.getOrNull(cursor)
        r.frame(0, Renderer.H - 32, Renderer.W, 32)
        r.text(if (flyMode) "FLY TO: ${sel?.location ?: "-"}" else (sel?.location ?: here?.location ?: ""), 12, Renderer.H - 20)
        sel?.let { r.outline(it.regionX * 8 - 2, it.regionY * 8 - 2, 12, 12, Palette.HIGHLIGHT) }
    }
}

class QuestScreen(game: PkmnGame, private val ctx: GameContext) : Screen(game) {
    private var cursor = 0
    private val entries get() = ctx.state.quests.values.toList().sortedBy { it.completed }
    override fun update(dt: Float, input: InputState) {
        val n = entries.size
        if (n > 0) {
            if (input.repeat(Button.UP)) cursor = (cursor - 1 + n) % n
            if (input.repeat(Button.DOWN)) cursor = (cursor + 1) % n
        }
        if (input.justPressed(Button.B) || input.justPressed(Button.A)) pop()
    }
    override fun onBack(): Boolean { pop(); return true }

    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.frame(0, 0, Renderer.W, 24)
        r.text("QUESTS", 8, 8)
        val list = entries
        if (list.isEmpty()) { r.text("No quests yet.", 16, 40); return }
        list.take(7).forEachIndexed { i, q ->
            val def = ctx.content.quests[q.id]
            r.text((if (q.completed) "✓ " else "") + (def?.name ?: q.id).take(26), 18, 32 + i * 14, if (q.completed) Palette.GREY else Palette.TEXT)
            if (i == cursor) r.cursor(8, 32 + i * 14)
        }
        val q = list.getOrNull(cursor) ?: return
        val def = ctx.content.quests[q.id]
        r.frame(0, 136, Renderer.W, 88)
        val step = def?.steps?.getOrNull(q.step)
        val body = (def?.description ?: "") + if (!q.completed && step != null) "\nNEXT: $step" else if (q.completed) "\nCompleted!" else ""
        r.assets.font6.wrap(body, 40).take(6).forEachIndexed { i, l -> r.text(l, 10, 146 + i * 12, small = true) }
    }
}

private val LOCKED_BADGE = Color(0.22f, 0.22f, 0.26f, 1f)

class TrainerCardScreen(game: PkmnGame, private val ctx: GameContext) : Screen(game) {
    override fun update(dt: Float, input: InputState) { if (input.justPressed(Button.B) || input.justPressed(Button.A)) pop() }
    override fun onBack(): Boolean { pop(); return true }
    override fun draw(r: Renderer) {
        val s = ctx.state
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.WINDOW)
        r.frame(0, 0, Renderer.W, Renderer.H)
        r.text("TRAINER CARD", 16, 12)
        r.text("NAME/${s.playerName}", 16, 32)
        r.text("ID No./${s.trainerId.toString().padStart(5, '0')}", 16, 46)
        r.text("MONEY/¥${s.money}", 16, 60)
        r.text("POKéDEX/${s.caught.size}", 16, 74)
        val t = s.playTimeMs / 60000
        r.text("TIME/${t / 60}:${(t % 60).toString().padStart(2, '0')}", 16, 88)
        r.text("BADGES", 16, 112)
        for (i in 1..8) {
            val got = i in s.badges
            val x = 16 + (i - 1) * 28
            val art = r.assets.badge(i)
            if (art != null) {
                // Earned: full colour. Unearned: dark silhouette of the same shape.
                if (got) r.draw(art, x - 1, 124) else r.tint(LOCKED_BADGE) { r.draw(art, x - 1, 124) }
            } else {
                r.rect(x, 126, 22, 22, if (got) Palette.HIGHLIGHT else Palette.SHADE)
                r.text(i.toString(), x + 8, 132, if (got) Palette.TEXT else Palette.GREY)
            }
        }
        ctx.content.info.badges.lastOrNull { it.number in s.badges }?.let { r.text("Latest: ${it.name} Badge", 16, 160) }
        r.assets.trainerPortrait(if (s.playerGender == "f") "player_f" else "player_m")?.let { r.draw(it, 176, 20) }
    }
}

/** Move tutor: teaches moves from a fixed list (data: assets/world/tutors.json if present). */
class MoveTutorScreen(game: PkmnGame, private val ctx: GameContext, private val tutorId: String, private val onClose: () -> Unit) : Screen(game) {
    override val opaque = false
    private var started = false
    private val moves: List<String> = loadMoves()

    private fun loadMoves(): List<String> {
        val f = com.badlogic.gdx.Gdx.files.internal("world/tutors.json")
        if (f.exists()) {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(f.readString()) as? kotlinx.serialization.json.JsonObject
            val arr = o?.get(tutorId) as? kotlinx.serialization.json.JsonArray
            if (arr != null) return arr.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }
        return listOf("drainpunch", "heatwave", "icywind", "seedbomb", "zenheadbutt", "superpower", "aquatail", "gunkshot", "earthpower", "ironhead")
    }

    override fun update(dt: Float, input: InputState) {
        if (started) return
        started = true
        game.say("I can teach your POKéMON a special move. Which move would you like?") { pick() }
    }

    private fun pick() {
        val labels = moves.map { ctx.dex.moveOrNull(it)?.name?.uppercase() ?: it } + "CANCEL"
        push(Menu(game, labels, 8, 8, 168, visible = minOf(8, labels.size), onCancel = { done() }) { i ->
            if (i >= moves.size) { done(); return@Menu }
            val mv = moves[i]
            push(PartyScreen(game, ctx, PartyScreen.Mode.CHOOSE, null, false, null, "Teach which POKéMON?") { t ->
                val mon = t?.let { ctx.state.party[it] } ?: run { pick(); return@PartyScreen }
                if (!ctx.progression.canLearnMachine(mon, mv)) game.say("${mon.displayName(ctx.dex)} can't learn that move.") { pick() }
                else MoveLearnFlow.offer(game, ctx, mon, mv) { done() }
            })
        })
    }

    private fun done() { pop(); onClose() }
    override fun draw(r: Renderer) {}
}

/** Move relearner: re-teaches forgotten level-up moves. */
class MoveRelearnerScreen(game: PkmnGame, private val ctx: GameContext, private val onClose: () -> Unit) : Screen(game) {
    override val opaque = false
    private var started = false
    override fun update(dt: Float, input: InputState) {
        if (started) return
        started = true
        push(PartyScreen(game, ctx, PartyScreen.Mode.CHOOSE, null, false, null, "Remind which POKéMON?") { t ->
            val mon: Pokemon = t?.let { ctx.state.party[it] } ?: run { done(); return@PartyScreen }
            val moves = ctx.progression.relearnableMoves(mon)
            if (moves.isEmpty()) { game.say("There's no move I can remind it of.") { done() }; return@PartyScreen }
            val labels = moves.map { ctx.dex.moveOrNull(it)?.name?.uppercase() ?: it } + "CANCEL"
            push(Menu(game, labels, 8, 8, 168, visible = minOf(8, labels.size), onCancel = { done() }) { i ->
                if (i >= moves.size) done() else MoveLearnFlow.offer(game, ctx, mon, moves[i]) { done() }
            })
        })
    }
    private fun done() { pop(); onClose() }
    override fun draw(r: Renderer) {}
}

class HallOfFameScreen(game: PkmnGame, private val ctx: GameContext, private val onClose: () -> Unit) : Screen(game) {
    private var t = 0f
    override fun onEnter() { game.audio.playMusic("league_hall") }
    override fun update(dt: Float, input: InputState) {
        t += dt
        if (t > 2f && (input.justPressed(Button.A) || input.justPressed(Button.B))) { pop(); onClose() }
    }
    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.BLACK)
        r.text("HALL OF FAME", 80, 12, Palette.HIGHLIGHT)
        val party = ctx.state.party
        val shown = minOf(party.size, (t / 0.8f).toInt() + 1)
        for (i in 0 until shown) {
            val m = party[i]
            val x = 8 + (i % 3) * 82; val y = 32 + (i / 3) * 92
            r.draw(r.assets.pokemon(m.speciesId), x.toFloat(), y.toFloat(), 72f, 72f)
            r.text(m.displayName(ctx.dex).take(10), x, y + 74, Palette.WHITE)
        }
    }
}

class CreditsScreen(game: PkmnGame, private val ctx: GameContext, private val onClose: () -> Unit) : Screen(game) {
    private var y = Renderer.H.toFloat()
    private val lines = listOf(
        "POKéMON CRYSTALLINE", "", "A fan-made, non-commercial game", "", "Thank you for playing,", ctx.state.playerName, "",
        "Game data: Pokémon Showdown (MIT)", "and PokeAPI (BSD-3-Clause)", "", "Engine: Kotlin + libGDX", "", "World, music and art:", "original works", "",
        "Pokémon and all related names", "are trademarks of Nintendo,", "Creatures and GAME FREAK.", "", "THE END",
    )
    override fun onEnter() { game.audio.playMusic("ending_credits") }
    override fun update(dt: Float, input: InputState) {
        y -= dt * (if (input.pressed(Button.A)) 60 else 20)
        if (y < -lines.size * 16f - 20 || input.justPressed(Button.START)) { pop(); onClose() }
    }
    override fun draw(r: Renderer) {
        r.rect(0, 0, Renderer.W, Renderer.H, Palette.BLACK)
        lines.forEachIndexed { i, l -> r.text(l, (Renderer.W - r.textWidth(l)) / 2, (y + i * 16).toInt(), Palette.WHITE) }
    }
}
