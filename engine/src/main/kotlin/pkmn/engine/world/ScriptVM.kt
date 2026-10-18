package pkmn.engine.world

import pkmn.engine.campaign.GameContext
import pkmn.engine.mon.Nature
import pkmn.engine.mon.Pokemon
import pkmn.engine.util.toId

/** A presentation or world action the host (UI layer) must perform. */
sealed class ScriptRequest {
    /** Blocking requests wait for the host to call [ScriptVM.resume]. */
    open val blocking: Boolean = true
    /** Whether the value passed to [ScriptVM.resume] becomes `result` (otherwise the previous result is kept). */
    open val setsResult: Boolean = false

    data class Say(val text: String) : ScriptRequest()
    data class Ask(val text: String) : ScriptRequest() { override val setsResult = true }
    data class Choice(val prompt: String, val options: List<String>) : ScriptRequest() { override val setsResult = true }
    data class FacePlayer(val npc: String?) : ScriptRequest() { override val blocking = false }
    data class Face(val target: String, val dir: Facing) : ScriptRequest() { override val blocking = false }
    data class Move(val target: String, val path: List<Facing>) : ScriptRequest()
    /** Walk [target] (npc id or `player`) to tile (x, y) along a collision-respecting route; the host resumes at once when no route exists. */
    data class WalkTo(val target: String, val x: Int, val y: Int) : ScriptRequest()
    data class Wait(val ms: Int) : ScriptRequest()
    data class TrainerBattle(val trainerId: String, val canLose: Boolean) : ScriptRequest() { override val setsResult = true }
    data class WildBattle(val mon: Pokemon, val canRun: Boolean, val music: String?) : ScriptRequest() { override val setsResult = true }
    data class Warp(val map: String, val x: Int, val y: Int, val facing: Facing?) : ScriptRequest()
    data class Music(val id: String?) : ScriptRequest() { override val blocking = false }
    data class Sfx(val id: String) : ScriptRequest() { override val blocking = false }
    data class Cry(val species: String) : ScriptRequest()
    data class Fade(val out: Boolean) : ScriptRequest()
    data class SetVisible(val npc: String, val visible: Boolean) : ScriptRequest() { override val blocking = false }
    data class Emote(val target: String, val emote: String) : ScriptRequest()
    data class Shop(val id: String) : ScriptRequest()
    object Pc : ScriptRequest()
    data class HealAnimation(val atCenter: Boolean) : ScriptRequest()
    data class MoveTutor(val id: String) : ScriptRequest()
    object MoveRelearner : ScriptRequest()
    /** Offer a nickname for the owned Pokémon [uid] (queued automatically after `givemon`/`giveegg`). */
    data class Nickname(val uid: Long) : ScriptRequest()
    data class NameInput(val what: String) : ScriptRequest()
    data class Weather(val id: String?) : ScriptRequest() { override val blocking = false }
    object Credits : ScriptRequest()
    object HallOfFame : ScriptRequest()
    object CheckEvolution : ScriptRequest()
    data class ScreenEffect(val kind: String) : ScriptRequest()
    object Save : ScriptRequest()
    object Fly : ScriptRequest()
    data class Special(val name: String, val args: List<String>) : ScriptRequest() { override val setsResult = true }
    /** In-game trade: the player offers a party member of species [want] and receives [offer]. Resume with 1 if traded. */
    data class Trade(val want: String, val offer: Pokemon) : ScriptRequest() { override val setsResult = true }
}

/**
 * Executes a compiled script against a [GameContext]. Pure state changes are applied immediately;
 * everything visual is returned as a [ScriptRequest] from [next].
 *
 * Usage: `while (true) { val r = vm.next() ?: break; host.handle(r); if (r.blocking) vm.resume(result) }`
 */
class ScriptVM(private val ctx: GameContext, script: CompiledScript, private val lookup: (String) -> CompiledScript?, val npcId: String? = null) {
    private class Frame(val script: CompiledScript, var pc: Int)
    private val stack = ArrayList<Frame>().apply { add(Frame(script, 0)) }
    /** Last result from ask/choice/battle (yes=1/no=0, option index, win=1/lose=0, wild: 1 caught, 2 defeated, 3 ran, 0 lost). */
    var result: Int = 0
        private set
    private var waiting = false
    var finished = false
        private set
    private var steps = 0

    private var pending: ScriptRequest? = null
    /** Requests that follow the current command (the nickname offer after a gift's message). */
    private val queued = ArrayDeque<ScriptRequest>()

    /** Continues after a blocking request. Text boxes and animations keep the result of the last givemon/takeitem/ask… */
    fun resume(value: Int = 0) { if (pending?.setsResult == true) result = value; pending = null; waiting = false }

    /** Runs until the next host request; returns null when the script has finished. */
    fun next(): ScriptRequest? {
        check(!waiting) { "VM is waiting for resume()" }
        queued.removeFirstOrNull()?.let { req -> if (req.blocking) { waiting = true; pending = req }; return req }
        while (stack.isNotEmpty()) {
            val f = stack.last()
            if (f.pc >= f.script.code.size) { stack.removeAt(stack.size - 1); continue }
            if (++steps > 100_000) throw ScriptError("script exceeded step limit (infinite loop?)", f.script.file, f.script.code[f.pc].line)
            when (val ins = f.script.code[f.pc++]) {
                is Instr.Jump -> f.pc = ins.target
                is Instr.JumpIfNot -> if (!eval(ins.cond)) f.pc = ins.target
                is Instr.Cmd -> {
                    val req = try { exec(ins) } catch (e: ScriptError) { throw e } catch (e: Exception) {
                        throw ScriptError("${ins.name}: ${e.message}", f.script.file, ins.line)
                    }
                    if (req != null) { if (req.blocking) { waiting = true; pending = req }; return req }
                }
            }
        }
        finished = true
        return null
    }

    private fun text(s: String): String = ctx.format(s)

    private fun opt(args: List<String>, key: String): String? = args.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    private fun exec(c: Instr.Cmd): ScriptRequest? {
        val a = c.args
        val st = ctx.state
        return when (c.name) {
            "say" -> ScriptRequest.Say(text(a.joinToString(" ")))
            "ask" -> ScriptRequest.Ask(text(a[0]))
            "choice" -> ScriptRequest.Choice(text(a[0]), a.drop(1).map { text(it) })
            "faceplayer" -> ScriptRequest.FacePlayer(npcId)
            "face" -> ScriptRequest.Face(a[0], Facing.parse(a[1]))
            "playerface" -> ScriptRequest.Face("player", Facing.parse(a[0]))
            "move" -> ScriptRequest.Move(a[0], a.drop(1).flatMap { expandPath(it) })
            "walkto" -> ScriptRequest.WalkTo(a[0], a[1].toInt(), a[2].toInt())
            "wait" -> ScriptRequest.Wait(a[0].toInt())
            "lock", "release" -> null
            "setflag" -> { st.setFlag(a[0]); null }
            "clearflag" -> { st.clearFlag(a[0]); null }
            "setvar" -> { st.vars[a[0]] = a[1].toInt(); null }
            "addvar" -> { st.vars[a[0]] = (st.vars[a[0]] ?: 0) + a[1].toInt(); null }
            "giveitem" -> {
                val item = toId(a[0]); val qty = a.getOrNull(1)?.toIntOrNull() ?: 1
                ctx.dex.item(item)
                st.addItem(item, qty)
                val name = ctx.itemName(item)
                ScriptRequest.Say(text(if (qty > 1) "{player} obtained $qty× $name!" else "{player} obtained ${article(name)} $name!"))
                    .also { result = 1 }
            }
            "tm" -> {
                val move = toId(a[0])
                ctx.dex.move(move)
                val key = "tm:$move"
                if (st.itemCount(key) == 0) st.addItem(key, 1)
                ScriptRequest.Say(text("{player} obtained a TM containing ${ctx.dex.move(move).name}!"))
            }
            "takeitem" -> { result = if (st.removeItem(toId(a[0]), a.getOrNull(1)?.toIntOrNull() ?: 1)) 1 else 0; null }
            "givemoney" -> { st.money = (st.money + a[0].toInt()).coerceAtMost(9_999_999); ScriptRequest.Say(text("{player} received ¥${a[0]}!")) }
            "takemoney" -> { val n = a[0].toInt(); result = if (st.money >= n) { st.money -= n; 1 } else 0; null }
            "givemon" -> {
                val mon = buildMon(a)
                val where = ctx.receivePokemon(mon, offerNickname = !a.contains("nonick"))
                result = if (where == null) 0 else 1
                if (where != null && mon.uid in st.pendingNicknames) queued.add(ScriptRequest.Nickname(mon.uid))
                when {
                    where == null -> ScriptRequest.Say(text("There's no room for more Pokémon!"))
                    where == "party" -> ScriptRequest.Say(text("{player} received ${mon.species(ctx.dex).name}!"))
                    else -> ScriptRequest.Say(text("{player} received ${mon.species(ctx.dex).name}!\n${mon.species(ctx.dex).name} was sent to the PC."))
                }
            }
            "giveegg" -> {
                // Eggs are simplified: the Pokémon is received at level 1 and treated as hatched.
                val mon = buildMon(listOf(a[0], "1") + a.drop(2))
                mon.outsider = false // hatched by the player: met at level 1
                val where = ctx.receivePokemon(mon)
                result = if (where == null) 0 else 1
                if (where != null && mon.uid in st.pendingNicknames) queued.add(ScriptRequest.Nickname(mon.uid))
                ScriptRequest.Say(text(if (where == null) "There's no room!" else "{player} received an Egg! It hatched into ${mon.species(ctx.dex).name}!"))
            }
            "battle" -> ScriptRequest.TrainerBattle(a[0], a.contains("canlose"))
            "wildbattle" -> ScriptRequest.WildBattle(buildMon(a, wild = true), !a.contains("norun"), opt(a, "music"))
            "heal" -> { ctx.healParty(); ScriptRequest.HealAnimation(false) }
            "healcenter" -> { ctx.healParty(); ctx.setRespawnHere(); ScriptRequest.HealAnimation(true) }
            "warp" -> ScriptRequest.Warp(a[0], a[1].toInt(), a[2].toInt(), a.getOrNull(3)?.let { Facing.parse(it) })
            "music" -> ScriptRequest.Music(a[0])
            "restoremusic" -> ScriptRequest.Music(null)
            "sfx" -> ScriptRequest.Sfx(a[0])
            "cry" -> ScriptRequest.Cry(toId(a[0]))
            "fadeout" -> ScriptRequest.Fade(true)
            "fadein" -> ScriptRequest.Fade(false)
            "show" -> ScriptRequest.SetVisible(a[0], true)
            "hide" -> ScriptRequest.SetVisible(a[0], false)
            "emote" -> ScriptRequest.Emote(a[0], a[1])
            "badge" -> {
                val n = a[0].toInt()
                st.badges.add(n)
                ScriptRequest.Say(text("{player} received the ${ctx.badgeName(n)}!"))
            }
            "shop" -> ScriptRequest.Shop(a[0])
            "pc" -> ScriptRequest.Pc
            "setrespawn" -> { ctx.setRespawnHere(); null }
            "call" -> {
                val s = lookup(a[0]) ?: throw ScriptError("unknown script '${a[0]}'", stack.last().script.file, c.line)
                stack.add(Frame(s, 0)); null
            }
            "return" -> { stack.removeAt(stack.size - 1); null }
            "quest" -> { ctx.updateQuest(a[0], a[1]); if (a[1] == "start") ScriptRequest.Say(text("New quest: ${ctx.questName(a[0])}")) else null }
            "pokedex" -> { st.setFlag("has_pokedex"); null }
            "movetutor" -> ScriptRequest.MoveTutor(a[0])
            "moverelearner" -> ScriptRequest.MoveRelearner
            // Explicit offer for the most recently obtained Pokémon (givemon already queues one automatically).
            "nickname" -> (st.pendingNicknames.lastOrNull() ?: st.party.lastOrNull()?.uid)?.let { uid ->
                if (uid !in st.pendingNicknames) st.pendingNicknames.add(uid)
                ScriptRequest.Nickname(uid)
            }
            "nameinput" -> ScriptRequest.NameInput(a[0])
            "weather" -> ScriptRequest.Weather(a[0].takeIf { it != "none" })
            "credits" -> ScriptRequest.Credits
            "halloffame" -> { ctx.recordHallOfFame(); ScriptRequest.HallOfFame }
            "evolve" -> ScriptRequest.CheckEvolution
            "flash", "shake" -> ScriptRequest.ScreenEffect(c.name)
            "settime" -> null
            "sign" -> ScriptRequest.Say(text(a[0]))
            "unlock" -> { st.setFlag("unlock_${a[0]}"); null }
            "setmonflag" -> null
            "teleport" -> { val (m, x, y) = ctx.respawnPoint(); ScriptRequest.Warp(m, x, y, Facing.DOWN) }
            "blackout" -> { val (m, x, y) = ctx.respawnPoint(); ctx.healParty(); ScriptRequest.Warp(m, x, y, Facing.DOWN) }
            "save" -> ScriptRequest.Save
            "fly" -> ScriptRequest.Fly
            "trade" -> {
                // trade <want> <give> <level> [nick=] [item=] ...: the received Pokémon belongs to the NPC trainer.
                val mon = buildMon(a.drop(1))
                mon.otName = opt(a, "ot") ?: "TRADER"; mon.otId = ctx.rng.nextInt(65536)
                ScriptRequest.Trade(toId(a[0]), mon)
            }
            "special" -> ScriptRequest.Special(a[0], a.drop(1))
            else -> throw ScriptError("command '${c.name}' not implemented", stack.last().script.file, c.line)
        }
    }

    private fun article(name: String) = if (name.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

    private fun expandPath(tok: String): List<Facing> {
        // "up3" or "up" or "u3"
        val m = Regex("([a-z]+)(\\d*)").matchEntire(tok.lowercase()) ?: error("bad path '$tok'")
        val n = m.groupValues[2].ifEmpty { "1" }.toInt()
        return List(n) { Facing.parse(m.groupValues[1]) }
    }

    /** givemon/wildbattle args: species level [shiny] [item=] [ability=] [moves=a,b] [nature=] [ivs=] [gmax] [tera=] [nick=] [ball=] */
    private fun buildMon(a: List<String>, wild: Boolean = false): Pokemon {
        val species = toId(a[0])
        val level = a[1].toInt()
        val moves = opt(a, "moves")?.split(',')?.map { toId(it) }
        val mon = ctx.factory.create(
            species, level, ctx.rng, shinyOdds = if (a.contains("shiny")) 1 else 4096,
            perfectIvs = opt(a, "ivs")?.toIntOrNull() ?: if (wild) 3 else 0,
            moves = moves, abilitySlot = opt(a, "ability"), item = opt(a, "item")?.let { toId(it) },
            nature = opt(a, "nature")?.let { Nature.valueOf(it.uppercase()) },
        )
        if (a.contains("gmax")) mon.gmaxFactor = true
        opt(a, "tera")?.let { mon.teraType = it.replaceFirstChar { c -> c.uppercase() } }
        opt(a, "nick")?.let { mon.nickname = it }
        opt(a, "ball")?.let { mon.ball = toId(it) }
        if (!wild) {
            mon.otName = ctx.state.playerName; mon.otId = ctx.state.trainerId
            // Gifts, starters, fossils and trades are outsiders received at this level (badge obedience reference).
            mon.outsider = true; mon.metLevel = level
        }
        return mon
    }

    // --------------------------------------------------------------- conditions

    private fun eval(c: Cond): Boolean = when (c) {
        is Cond.Not -> !eval(c.c)
        is Cond.And -> eval(c.a) && eval(c.b)
        is Cond.Or -> eval(c.a) || eval(c.b)
        is Cond.Atom -> atom(c.words)
    }

    private fun cmp(value: Int, op: String, rhs: Int) = when (op) {
        ">=" -> value >= rhs; "<=" -> value <= rhs; ">" -> value > rhs; "<" -> value < rhs; "==", "=" -> value == rhs; "!=" -> value != rhs
        else -> error("bad operator '$op'")
    }

    private fun atom(w: List<String>): Boolean {
        val st = ctx.state
        return when (w[0]) {
            "true" -> true
            "false" -> false
            "flag" -> st.hasFlag(w[1])
            "result" -> when (w[1]) { "yes", "win", "caught" -> result == 1; "no", "lose" -> result == 0; "defeated" -> result == 2; "ran" -> result == 3; else -> result == w[1].toInt() }
            "badges" -> cmp(st.badgeCount(), w[1], w[2].toInt())
            "badge" -> w[1].toInt() in st.badges
            "item" -> if (w.size >= 4) cmp(st.itemCount(toId(w[1])), w[2], w[3].toInt()) else st.itemCount(toId(w[1])) > 0
            "money" -> cmp(st.money, w[1], w[2].toInt())
            "var" -> cmp(st.vars[w[1]] ?: 0, w[2], w[3].toInt())
            "party" -> when (w[1]) { "has" -> st.party.any { it.speciesId == toId(w[2]) || it.species(ctx.dex).baseSpecies == toId(w[2]) }; else -> false }
            "hasmon" -> st.party.any { it.speciesId == toId(w[1]) } || st.boxes.any { b -> b.any { it?.speciesId == toId(w[1]) } }
            "partysize" -> cmp(st.party.size, w[1], w[2].toInt())
            "time" -> ctx.timeOfDay() == w[1]
            "caught" -> toId(w[1]) in st.caught
            "seen" -> toId(w[1]) in st.seen
            "caughtcount" -> cmp(st.caught.size, w[1], w[2].toInt())
            "trainer" -> w[1] in st.defeatedTrainers
            "gender" -> st.playerGender == w[1]
            "field" -> ctx.canUseFieldMove(FieldMove.valueOf(w[1].uppercase().replace("ROCKSMASH", "ROCK_SMASH")))
            "random" -> ctx.rng.nextInt(w[1].toInt()) == 0
            "quest" -> {
                val q = st.quests[w[1]]
                when (w.getOrNull(2)) { "done" -> q?.completed == true; "started" -> q != null; "step" -> cmp(q?.step ?: -1, w[3], w[4].toInt()); else -> q != null }
            }
            "gimmick" -> st.hasFlag("unlock_${w[1]}")
            "visited" -> w[1] in st.visited
            "playerx" -> cmp(st.x, w[1], w[2].toInt())
            "playery" -> cmp(st.y, w[1], w[2].toInt())
            else -> error("unknown condition '${w[0]}'")
        }
    }
}
