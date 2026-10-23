package pkmn.game.overworld

import pkmn.engine.battle.BattleOutcome
import pkmn.engine.campaign.GameContext
import pkmn.engine.world.Facing
import pkmn.engine.world.ScriptRequest
import pkmn.engine.world.ScriptVM
import pkmn.game.PkmnGame
import pkmn.game.screens.*
import pkmn.game.ui.Menu
import pkmn.game.ui.TextBox
import pkmn.game.ui.YesNo
import pkmn.game.ui.say

/**
 * Executes a [ScriptVM] on the overworld: every [ScriptRequest] is turned into UI (dialogue, menus, battles,
 * movement, screens) and the VM is resumed with the result when that UI completes.
 */
class ScriptHost(
    private val game: PkmnGame,
    private val ow: OverworldScreen,
    private val ctx: GameContext,
    private val vm: ScriptVM,
    private val onFinish: () -> Unit,
) {
    private var finished = false

    fun step() {
        while (!finished) {
            val req = try { vm.next() } catch (e: Exception) {
                game.platform.log("Script error: ${e.message}")
                game.say("(Script error: ${e.message?.take(60)})") { finish() }
                return
            }
            if (req == null) { finish(); return }
            if (!req.blocking) { handleImmediate(req); continue }
            handleBlocking(req)
            return
        }
    }

    private fun finish() { if (!finished) { finished = true; onFinish() } }

    private fun resume(v: Int = 0) { vm.resume(v); step() }

    private fun handleImmediate(req: ScriptRequest) {
        when (req) {
            is ScriptRequest.FacePlayer -> req.npc?.let { id -> ow.actor(id)?.let { a ->
                a.facing = when {
                    ow.player.x > a.x -> Facing.RIGHT; ow.player.x < a.x -> Facing.LEFT
                    ow.player.y > a.y -> Facing.DOWN; else -> Facing.UP
                }
            } }
            is ScriptRequest.Face -> ow.actor(req.target)?.facing = req.dir
            is ScriptRequest.Music -> game.audio.playMusic(req.id ?: ow.baseMap.music)
            is ScriptRequest.Sfx -> game.audio.playSfx(req.id)
            is ScriptRequest.SetVisible -> ow.setNpcVisible(req.npc, req.visible)
            is ScriptRequest.Weather -> {}
            else -> {}
        }
    }

    private fun handleBlocking(req: ScriptRequest) {
        when (req) {
            is ScriptRequest.Say -> {
                // The `badge N` command announces "<player> received the <Name> Badge!": show the badge art with it.
                val badge = (1..8).firstOrNull { n -> req.text.contains("received the ${ctx.badgeName(n)}!") }?.let { game.assets.badge(it) }
                val decor: ((pkmn.game.gfx.Renderer) -> Unit)? = badge?.let { art ->
                    { r ->
                        val x = (pkmn.game.gfx.Renderer.W - 48) / 2; val y = TextBox.BOX_Y - 64
                        r.frame(x - 6, y - 6, 60, 60)
                        r.draw(art, x.toFloat(), y.toFloat(), 48f, 48f)
                    }
                }
                game.push(TextBox(game, req.text, onDone = { resume() }, decor = decor))
            }
            is ScriptRequest.Ask -> game.push(TextBox(game, req.text) { game.push(YesNo(game, req.text) { yes -> resume(if (yes) 1 else 0) }) })
            is ScriptRequest.Choice -> {
                val menu = Menu(game, req.options, 160, 40, onCancel = { resume(req.options.size - 1) }) { i -> resume(i) }
                menu.prompt = req.prompt
                game.push(menu)
            }
            is ScriptRequest.Move -> {
                val a = ow.actor(req.target)
                if (a == null || req.path.isEmpty()) { resume(); return }
                a.path.addAll(req.path)
                a.onPathDone = { resume() }
            }
            is ScriptRequest.WalkTo -> {
                val a = ow.actor(req.target)
                val path = ow.planWalk(req.target, req.x, req.y)
                if (a == null || path.isNullOrEmpty()) { resume(); return }
                a.path.addAll(path)
                a.onPathDone = { resume() }
            }
            is ScriptRequest.Wait -> ow.wait(req.ms / 1000f) { resume() }
            is ScriptRequest.TrainerBattle -> ow.startTrainerBattle(req.trainerId, req.canLose) { win -> resume(if (win) 1 else 0) }
            is ScriptRequest.WildBattle -> ow.startWildBattle(req.mon, req.canRun, req.music) { outcome ->
                // startWildBattle reports RAN without a fight when no Pokémon can battle; that is not an escape for the script.
                val noFighters = ctx.state.party.none { !it.isFainted }
                resume(when (outcome) { BattleOutcome.CAUGHT -> 1; BattleOutcome.WIN -> 2; BattleOutcome.RAN -> if (noFighters) 0 else 3; BattleOutcome.FOE_FLED -> 3; else -> 0 })
            }
            is ScriptRequest.Warp -> ow.warpTo(req.map, req.x, req.y, req.facing) { resume() }
            is ScriptRequest.Cry -> { game.audio.playCry(req.species); ow.wait(0.4f) { resume() } }
            is ScriptRequest.Fade -> if (req.out) ow.fadeOut { resume() } else ow.fadeIn { resume() }
            is ScriptRequest.Emote -> {
                ow.actor(req.target)?.let { it.emote = when (req.emote) { "heart" -> "♥"; "music" -> "♪"; "dots" -> "…"; else -> req.emote }; it.emoteTimer = 0.9f }
                ow.wait(0.9f) { resume() }
            }
            is ScriptRequest.Shop -> game.push(ShopScreen(game, ctx, req.id) { resume() })
            ScriptRequest.Pc -> game.push(PcScreen(game, ctx) { resume() })
            is ScriptRequest.HealAnimation -> {
                game.audio.playSfx("heal")
                if (req.atCenter) game.say("Your POKéMON are now fully healed!") { ow.wait(0.2f) { resume() } }
                else ow.wait(1.0f) { resume() }
            }
            is ScriptRequest.MoveTutor -> game.push(MoveTutorScreen(game, ctx, req.id) { resume() })
            ScriptRequest.MoveRelearner -> game.push(MoveRelearnerScreen(game, ctx) { resume() })
            is ScriptRequest.Nickname -> {
                val mon = ctx.state.findByUid(req.uid)
                if (mon == null || req.uid !in ctx.state.pendingNicknames) resume()
                else NicknameFlow.offer(game, ctx, mon) { resume() }
            }
            is ScriptRequest.NameInput -> game.push(NameInputScreen(game, "${req.what.uppercase()}'S NAME?", ctx.state.rivalName, onDone = { n ->
                if (req.what == "rival") ctx.state.rivalName = n
                resume()
            }))
            ScriptRequest.Credits -> game.push(CreditsScreen(game, ctx) { resume() })
            ScriptRequest.HallOfFame -> game.push(HallOfFameScreen(game, ctx) { resume() })
            ScriptRequest.CheckEvolution -> ow.runEvolutions(ctx.state.party.indices.toList()) { resume() }
            is ScriptRequest.ScreenEffect -> { if (req.kind == "shake") ow.shakeScreen(); game.audio.playSfx("hit_normal"); ow.wait(0.5f) { resume() } }
            ScriptRequest.Save -> {
                val ok = ow.save()
                game.audio.playSfx("save")
                game.say(if (ok) "${ctx.state.playerName} saved the game." else "The game could not be saved!") { resume() }
            }
            ScriptRequest.Fly -> game.push(TownMapScreen(game, ctx, ow, flyMode = true) { resume() })
            is ScriptRequest.Special -> special(req.name, req.args)
            is ScriptRequest.Trade -> trade(req)
            else -> resume()
        }
    }

    private fun trade(req: ScriptRequest.Trade) {
        if (ctx.tradeCandidates(req.want).isEmpty()) { resume(0); return }
        val wantName = ctx.dex.speciesOrNull(req.want)?.name ?: req.want
        game.push(PartyScreen(game, ctx, PartyScreen.Mode.CHOOSE, null, false, null, "Trade which POKéMON?") { t ->
            if (t == null) { resume(0); return@PartyScreen }
            if (t !in ctx.tradeCandidates(req.want)) { game.say("That's not ${wantName.uppercase()}.") { resume(0) }; return@PartyScreen }
            val given = ctx.state.party[t].displayName(ctx.dex)
            val evolveTo = ctx.performTrade(t, req.want, req.offer)
            game.audio.playSfx("trade")
            game.say("${ctx.state.playerName} traded $given for ${req.offer.displayName(ctx.dex)}!") {
                if (evolveTo == null) resume(1) else game.push(EvolutionScreen(game, ctx, req.offer, evolveTo) { resume(1) })
            }
        })
    }

    /** Lets the player pick a party member whose Tera Type becomes [type]; result 1 if changed. */
    private fun setTeraType(type: String) {
        game.push(PartyScreen(game, ctx, PartyScreen.Mode.CHOOSE, null, false, null, "Change whose TERA TYPE?") { t ->
            val mon = t?.let { ctx.state.party[it] } ?: run { resume(0); return@PartyScreen }
            val fixed = mon.species(ctx.dex).requiredTeraType
            if (fixed != null) { game.say("${mon.displayName(ctx.dex)}'s TERA TYPE can't be changed.") { resume(0) }; return@PartyScreen }
            mon.teraType = type
            game.audio.playSfx("heal")
            game.say("${mon.displayName(ctx.dex)}'s TERA TYPE became ${type.uppercase()}!") { resume(1) }
        })
    }

    private fun special(name: String, args: List<String>) {
        when (name) {
            "bike" -> { ow.toggleBike(); resume() }
            "flash" -> { ow.useFlash(); resume() }
            "set_tera_type" -> setTeraType(args.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Normal")
            else -> { game.platform.log("Unknown special '$name'"); resume() }
        }
    }
}
