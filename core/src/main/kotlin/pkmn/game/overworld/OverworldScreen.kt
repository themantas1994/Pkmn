package pkmn.game.overworld

import com.badlogic.gdx.graphics.Color
import pkmn.engine.battle.Battle
import pkmn.engine.battle.BattleOutcome
import pkmn.engine.campaign.GameContext
import pkmn.engine.mon.MajorStatus
import pkmn.engine.mon.Pokemon
import pkmn.engine.save.SaveResult
import pkmn.engine.world.*
import pkmn.game.AutosaveAware
import pkmn.game.PkmnGame
import pkmn.game.anim.AnimationPlayer
import pkmn.game.anim.Clip
import pkmn.game.anim.Ease
import pkmn.engine.campaign.LightPhase
import pkmn.engine.campaign.WorldEventDef
import pkmn.game.battle.BattleScreen
import pkmn.game.gfx.Palette
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.screens.*
import pkmn.game.ui.ask
import pkmn.game.ui.say

/**
 * The exploration screen: tile map rendering, grid movement, NPCs, interactions, encounters and the
 * host side of the event-script VM. Game rules come from [OverworldRules] / [GameContext].
 */
class OverworldScreen(game: PkmnGame, val ctx: GameContext) : Screen(game), AutosaveAware {
    private val content = ctx.content
    private val rules = OverworldRules(ctx)
    private val state get() = ctx.state
    lateinit var baseMap: MapData
        private set
    /** The map with flag-dependent tiles applied (used for both rules and rendering). */
    lateinit var map: MapData
        private set
    val player = Actor("player", "player_m", 0, 0, Facing.DOWN)
    private val actors = LinkedHashMap<String, Actor>()
    private var scriptBusy = false
    private val autosaveGate = AutosaveGate()
    private var lockInput = 0f
    private var turnGrace = 0f
    private var bumpCooldown = 0f
    private var fade = 0f
    private var fadeTarget = 0f
    private var fadeDone: (() -> Unit)? = null
    private var waitTimer = 0f
    private var waitDone: (() -> Unit)? = null
    private var popupText: String? = null
    private var popupTimer = 0f
    private var flashUsed = false
    private var stepsSinceSave = 0
    private var shake = 0f
    private val weatherParticles = FloatArray(80)
    private var pendingTrainerCheck = false
    /** Cosmetic overworld clips (door opening, item pickup, cut/smash). Never gate movement except where noted. */
    private val fx = AnimationPlayer()
    /** Tall-grass rustles: x, y, age (s). */
    private val rustles = ArrayList<FloatArray>()
    private var fadeColor = Color.BLACK
    /** Door being opened before a warp: x, y, progress 0..1 (null = none). */
    private var doorAnim: FloatArray? = null
    /** Item ball being picked up: x, y, progress. */
    private var pickupAnim: FloatArray? = null
    /** Obstacle being cleared (cut/smash): x, y, progress, kind. */
    private var clearAnim: Pair<ObstacleDef, FloatArray>? = null
    /** Seconds until the next wall-clock check (period, lighting, scheduled events). */
    private var clockCheck = 0f
    private var worldTimeKey = ""
    /** Event Pokémon appearing: actor id -> appear progress. */
    private val appearing = HashMap<String, Float>()
    /** Battle-start transition progress (null = none): flashes, then the screen closes to black. */
    private var battleIntro: Float? = null

    override fun onEnter() {
        player.sprite = playerSprite()
        loadMap(state.map, state.x, state.y, Facing.parse(state.facing), initial = true)
    }

    // ================================================================= map loading

    fun loadMap(id: String, x: Int, y: Int, facing: Facing?, initial: Boolean = false, viaWarp: Boolean = false) {
        val prevLocation = if (::baseMap.isInitialized) baseMap.location else ""
        val newMap = content.mapOrNull(id) ?: content.map(content.info.startMap)
        if (viaWarp && ::baseMap.isInitialized && !baseMap.indoor) {
            state.backMap = baseMap.id; state.backX = player.x; state.backY = player.y
        }
        baseMap = newMap
        state.map = newMap.id; state.x = x; state.y = y
        facing?.let { player.facing = it; state.facing = it.name.lowercase() }
        player.teleport(x, y)
        flashUsed = false
        if (state.surfing && !rulesWater(x, y)) state.surfing = false
        if (newMap.indoor) state.biking = false
        refreshMap()
        rebuildActors()
        val music = when {
            state.surfing -> "ocean_surf"
            state.biking -> "bike"
            else -> newMap.music
        }
        game.audio.playMusic(music)
        if ("town" in newMap.tags || newMap.flyX >= 0) state.visited.add(newMap.id)
        if (!newMap.indoor && newMap.location != prevLocation) { popupText = newMap.location; popupTimer = 2.2f }
        if (!initial || newMap.onEnter != null) newMap.onEnter?.let { runScriptById(it, null) }
        // A trainer may already see the player on the arrival tile (door, edge): challenge without waiting for the first step.
        pendingTrainerCheck = true
    }

    private fun rulesWater(x: Int, y: Int) = TileRules.behavior(content.mapOrNull(state.map)?.tileAt(x, y) ?: "") == TileBehavior.WATER

    /** Applies flag-dependent tile replacements and dynamic obstacle positions. */
    fun refreshMap() {
        val m = baseMap
        val active = m.flagTiles.filter { state.hasFlag(it.flag) }
        val obstacles = m.obstacles.mapNotNull { o ->
            if (state.hasFlag(o.flag)) null
            else {
                val pos = state.vars["pos:${m.id}:${o.flag}"]
                if (pos != null) o.copy(x = pos / 1000, y = pos % 1000) else o
            }
        }
        if (active.isEmpty() && obstacles == m.obstacles) { map = m; return }
        val palette = m.tilePalette.toMutableList()
        val ground = m.ground.toMutableList()
        for (ft in active) {
            var idx = palette.indexOf(ft.tile)
            if (idx < 0) { palette.add(ft.tile); idx = palette.size - 1 }
            ground[ft.y * m.width + ft.x] = idx
        }
        // Cleared obstacles are filtered out; their flags are checked by OverworldRules too.
        map = m.copy(tilePalette = palette, ground = ground, obstacles = obstacles)
    }

    private fun rebuildActors() {
        actors.clear()
        for (n in map.npcs) {
            if (!rules.npcVisible(map, n)) continue
            val a = Actor(n.id, n.sprite, n.x, n.y, Facing.parse(n.facing), n)
            actors[n.id] = a
        }
        addEventActors()
        worldTimeKey = worldTimeKey()
    }

    // ================================================================= clock-driven world state

    private fun eventActorId(e: WorldEventDef) = "event:${e.id}"

    /** Scheduled event Pokémon (e.g. the Thursday-night visitor) are actors on their map while their schedule is active. */
    private fun addEventActors() {
        for (e in ctx.activeEvents(baseMap.id)) {
            val id = eventActorId(e)
            if (actors.containsKey(id)) continue
            val npc = NpcDef(id, e.x, e.y, "@mon:${e.species}", e.facing)
            actors[id] = Actor(id, npc.sprite, e.x, e.y, Facing.parse(e.facing), npc)
        }
    }

    private fun worldTimeKey(): String = "${ctx.lightPhase()}|" + ctx.activeEvents(baseMap.id).joinToString(",") { it.id }

    /**
     * Re-reads the device clock: lighting phase and scheduled events. Called on map load, every few seconds while
     * exploring, and immediately when the app resumes, so events appear and vanish on time without a restart.
     */
    fun refreshWorldTime(force: Boolean = false) {
        clockCheck = 10f
        if (!::baseMap.isInitialized) return
        val key = worldTimeKey()
        if (!force && key == worldTimeKey) return
        worldTimeKey = key
        val active = ctx.activeEvents(baseMap.id).map { eventActorId(it) }.toSet()
        // Remove events that ended (unless the player is mid-interaction), add new ones with a short appear effect.
        if (!scriptBusy) actors.keys.filter { it.startsWith("event:") && it !in active }.forEach { actors.remove(it) }
        val before = actors.keys.toSet()
        addEventActors()
        for (id in actors.keys - before) {
            appearing[id] = 0f
            fx.spawn(Clip("event_appear", 0.8f, onUpdate = { appearing[id] = it }, onEnd = { appearing.remove(id) }))
        }
    }

    private fun startEventEncounter(a: Actor) {
        val def = ctx.content.events.defs.firstOrNull { eventActorId(it) == a.id } ?: return
        // The schedule may have ended while the player walked up: re-check on interaction.
        if (def !in ctx.activeEvents(baseMap.id)) { actors.remove(a.id); return }
        scriptBusy = true
        val mon = ctx.factory.create(def.species, def.level, ctx.rng, moves = def.moves.ifEmpty { null })
        game.audio.playCry(def.species)
        game.say(*def.intro.map { ctx.format(it) }.toTypedArray()) {
            val now = ctx.now()
            startWildBattle(mon, canRun = true, music = def.music) { outcome ->
                scriptBusy = false // also covers startWildBattle's early return when no Pokémon can battle
                if (outcome == BattleOutcome.RAN && state.party.none { !it.isFainted }) return@startWildBattle
                ctx.content.events.markEncountered(def, state, now)
                if (outcome == BattleOutcome.CAUGHT) ctx.content.events.markCaught(def, state)
                actors.remove(a.id)
                if (outcome != BattleOutcome.CAUGHT && outcome != BattleOutcome.LOSE) game.say("The ${mon.species(ctx.dex).name} vanished into the night...")
            }
        }
    }

    private fun playerSprite(): String {
        val g = if (state.playerGender == "f") "player_f" else "player_m"
        return when { state.surfing -> "${g}_surf"; state.biking -> "${g}_bike"; else -> g }
    }

    // ================================================================= update

    /** Nickname offers left open by an interrupted capture or gift are resumed once, when the overworld first runs. */
    private var pendingNicknamesChecked = false

    override fun update(dt: Float, input: InputState) {
        if (!pendingNicknamesChecked && !scriptBusy) {
            pendingNicknamesChecked = true
            if (ctx.nextPendingNickname() != null) {
                scriptBusy = true
                NicknameFlow.offerAllPending(game, ctx) { scriptBusy = false; save() }
                return
            }
        }
        fx.cosmetic = !game.options.reduceMotion
        fx.update(dt)
        rustles.forEach { it[2] += dt }
        rustles.removeAll { it[2] > 0.3f }
        clockCheck -= dt
        if (clockCheck <= 0f) refreshWorldTime()
        if (fade != fadeTarget) {
            fade = if (fadeTarget > fade) minOf(fadeTarget, fade + dt * 4) else maxOf(fadeTarget, fade - dt * 4)
            if (fade == fadeTarget) { val f = fadeDone; fadeDone = null; f?.invoke() }
        }
        if (waitTimer > 0) { waitTimer -= dt; if (waitTimer <= 0) { val w = waitDone; waitDone = null; w?.invoke() } }
        if (popupTimer > 0) popupTimer -= dt
        if (shake > 0) shake -= dt
        bumpCooldown -= dt
        updateActors(dt)
        val stepped = player.update(dt)
        if (stepped) onPlayerArrive()
        if (lockInput > 0) { lockInput -= dt; return }
        if (scriptBusy || player.moving || fade != fadeTarget || waitTimer > 0) return
        if (pendingTrainerCheck) { pendingTrainerCheck = false; if (checkTrainerSight()) return }

        if (input.justPressed(Button.START)) { push(StartMenu(game, this)); return }
        if (input.justPressed(Button.SELECT)) { useRegisteredItem(); return }
        if (input.justPressed(Button.A)) { interact(); return }
        val dir = input.direction()
        if (dir == null) { turnGrace = 0f; return }
        val facing = buttonToFacing(dir)
        if (facing != player.facing && !input.pressed(Button.B) && input.heldTime(dir) < 0.1f) {
            player.facing = facing; turnGrace = 0.08f
            if (registerTurn(facing)) runEvolutions(state.party.indices.toList(), spun = true) {}
            return
        }
        if (turnGrace > 0) { turnGrace -= dt; return }
        tryStep(facing, running = input.pressed(Button.B) != state.options.autoRun)
    }

    private val recentTurns = ArrayDeque<Pair<Long, Facing>>()

    /** Records a turn on the spot; true when the last four turns form a full spin (same rotation, within 2 s). */
    private fun registerTurn(f: Facing): Boolean {
        recentTurns.addLast(game.timeMs to f)
        while (recentTurns.size > 4 || (recentTurns.isNotEmpty() && game.timeMs - recentTurns.first().first > 2000)) recentTurns.removeFirst()
        if (recentTurns.size < 4) return false
        val cw = listOf(Facing.UP, Facing.RIGHT, Facing.DOWN, Facing.LEFT)
        val steps = recentTurns.map { cw.indexOf(it.second) }.zipWithNext { a, b -> (b - a + 4) % 4 }
        val spun = steps.all { it == 1 } || steps.all { it == 3 }
        if (spun) recentTurns.clear()
        return spun
    }

    private fun buttonToFacing(b: Button) = when (b) { Button.UP -> Facing.UP; Button.DOWN -> Facing.DOWN; Button.LEFT -> Facing.LEFT; else -> Facing.RIGHT }

    private fun speed(running: Boolean) = when {
        state.surfing -> 4f
        state.biking -> 8.5f
        running -> 7.5f
        else -> 3.75f
    }

    private fun occupant(x: Int, y: Int): Occupant? {
        for (a in actors.values) if (a.visible && ((a.x == x && a.y == y) || (a.moving && a.fromX == x && a.fromY == y))) return Occupant(a.id, x, y, "npc")
        for (it in map.items) if (!it.hidden && rules.itemVisible(map, it) && it.x == x && it.y == y) return Occupant(it.id, x, y, "item")
        return null
    }

    private fun tryStep(dir: Facing, running: Boolean) {
        player.facing = dir
        state.facing = dir.name.lowercase()
        when (val r = rules.step(map, player.x, player.y, dir, state.surfing) { x, y -> occupant(x, y) }) {
            is StepResult.Walk -> { player.startStep(dir, r.x, r.y, speed(running)) }
            is StepResult.Jump -> { game.audio.playSfx("ledge"); player.startStep(dir, r.x, r.y, speed(running) * 0.9f, jump = true) }
            is StepResult.Land -> { state.surfing = false; player.sprite = playerSprite(); game.audio.playMusic(baseMap.music); player.startStep(dir, r.x, r.y, speed(false)) }
            is StepResult.Warp -> {
                val w = r.warp
                if (w.x == player.x && w.y == player.y) doWarp(w)
                else player.startStep(dir, w.x, w.y, speed(running))
            }
            is StepResult.Transfer -> {
                val fromMap = baseMap.id
                loadMap(r.map, r.x, r.y, dir)
                player.fromX = r.x - dir.dx; player.fromY = r.y - dir.dy
                player.progress = 0f; player.tilesPerSecond = speed(running); player.stepParity = !player.stepParity
                if (fromMap == baseMap.id) player.teleport(r.x, r.y)
            }
            StepResult.OfferSurf -> offerSurf()
            is StepResult.Bump -> {
                if (r.reason == "boulder" && pushBoulder(dir)) return
                if (bumpCooldown <= 0) { game.audio.playSfx("bump"); bumpCooldown = 0.3f }
            }
            StepResult.Blocked -> {}
        }
    }

    private fun pushBoulder(dir: Facing): Boolean {
        if (!state.hasFlag("strength_on:${baseMap.id}")) return false
        val bx = player.x + dir.dx; val by = player.y + dir.dy
        val o = map.obstacles.firstOrNull { it.x == bx && it.y == by && it.kind == "boulder" } ?: return false
        val nx = bx + dir.dx; val ny = by + dir.dy
        if (!map.inBounds(nx, ny) || TileRules.isSolid(rules.behavior(map, nx, ny)) || rules.behavior(map, nx, ny) == TileBehavior.WATER ||
            occupant(nx, ny) != null || map.obstacles.any { it.x == nx && it.y == ny }) return false
        state.vars["pos:${baseMap.id}:${o.flag}"] = nx * 1000 + ny
        game.audio.playSfx("bump")
        refreshMap()
        return true
    }

    private fun onPlayerArrive() {
        state.x = player.x; state.y = player.y
        state.steps++
        stepsSinceSave++
        stepEffects()
        if (rules.behavior(map, player.x, player.y) == TileBehavior.TALL_GRASS) rustles.add(floatArrayOf(player.x.toFloat(), player.y.toFloat(), 0f))
        // Warp tiles reached by walking.
        rules.warpAt(map, player.x, player.y)?.let { w ->
            val b = rules.behavior(map, player.x, player.y)
            if (b == TileBehavior.WARP) { doWarp(w); return }
        }
        // Ice sliding.
        if (!state.surfing && rules.behavior(map, player.x, player.y) == TileBehavior.ICE) {
            val r = rules.step(map, player.x, player.y, player.facing, false) { x, y -> occupant(x, y) }
            if (r is StepResult.Walk) { player.startStep(player.facing, r.x, r.y, 8f); return }
        }
        // Scripted walks (move/walkto) never fire step triggers, trainer sight or encounters: the running script owns the player.
        if (scriptBusy) return
        // Step triggers.
        rules.triggerAt(map, player.x, player.y)?.let { runScriptById(it.script, null); return }
        if (checkTrainerSight()) return
        // Wild encounters.
        val method = rules.encounterMethod(map, player.x, player.y, state.surfing) ?: return
        val wild = ctx.rollEncounter(map, method) ?: return
        if (ctx.repelBlocks(wild)) return
        startWildBattle(wild, canRun = true, music = null, environment = if (method == "surf") "water" else null) {}
    }

    private fun stepEffects() {
        if (state.repelSteps > 0) {
            state.repelSteps--
            if (state.repelSteps == 0) game.say("REPEL's effect wore off.")
        }
        // Field poison (Gen V+): 1 HP every 4 steps, cured at 1 HP instead of fainting.
        if (state.steps % 4 == 0L) {
            for (m in state.party) if ((m.status == MajorStatus.POISON || m.status == MajorStatus.TOXIC) && m.hp > 1) {
                m.hp--
                if (m.hp == 1) { m.status = null; game.say("${m.displayName(ctx.dex)} survived the poisoning!\nThe poison faded away!") }
            }
        }
        if (state.steps % 128 == 0L) for (m in state.party) m.friendship = minOf(255, m.friendship + 1)
        for (m in state.party) m.counters["steps"] = (m.counters["steps"] ?: 0) + 1
    }

    private fun updateActors(dt: Float) {
        // Path callbacks resume scripts, which may show/hide NPCs or change maps; iterate over a snapshot.
        for (a in actors.values.toList()) {
            if (actors[a.id] !== a) continue
            val done = a.update(dt)
            if (!a.moving && a.path.isNotEmpty()) {
                val d = a.path.first()
                if (a.returningHome && !tileFree(a, a.x + d.dx, a.y + d.dy)) {
                    // Something (the player, another NPC) is in the way: wait a moment, then give up and retry later; never teleport or push through.
                    a.homeWait += dt
                    if (a.homeWait > 1.5f) { a.path.clear(); a.returningHome = false; a.homeWait = 0f; a.homeRetry = 1.5f }
                } else {
                    a.homeWait = 0f
                    a.path.removeFirst()
                    a.startStep(d, a.x + d.dx, a.y + d.dy, 3.75f)
                }
            } else if (done && a.path.isEmpty()) {
                a.onPathDone?.let { cb -> a.onPathDone = null; cb() }
            } else if (!a.moving && a.path.isEmpty() && a.onPathDone != null) {
                a.onPathDone?.let { cb -> a.onPathDone = null; cb() }
            }
            if (a.returningHome && !a.moving && a.path.isEmpty()) {
                a.returningHome = false
                a.npc?.let { if (a.x == it.x && a.y == it.y) a.facing = Facing.parse(it.facing) }
            }
            if (!scriptBusy && !a.moving && a.path.isEmpty() && a.onPathDone == null && !a.returningHome) {
                val n = a.npc
                if (n != null && rules.trainerNeedsHome(n, a.x, a.y)) {
                    // A defeated trainer that walked up to the player goes back to where the map put it (positions are never saved, so a
                    // reload or map re-entry also starts from home). If no route is free it stays put and tries again later.
                    a.homeRetry -= dt
                    if (a.homeRetry <= 0f) { a.homeRetry = 2f; startReturnHome(a) }
                } else idleMove(a, dt)
            }
        }
        // Player path (scripted movement).
        if (!player.moving && player.path.isNotEmpty()) {
            val d = player.path.removeFirst()
            player.startStep(d, player.x + d.dx, player.y + d.dy, 3.75f)
        } else if (!player.moving && player.path.isEmpty() && player.onPathDone != null) {
            val cb = player.onPathDone; player.onPathDone = null
            state.x = player.x; state.y = player.y
            cb?.invoke()
        }
    }

    private fun idleMove(a: Actor, dt: Float) {
        val mv = a.npc?.movement ?: return
        if (mv == "static") return
        a.wanderTimer -= dt
        if (a.wanderTimer > 0) return
        a.wanderTimer = 1.5f + ctx.rng.nextDouble().toFloat() * 2.5f
        val dir = Facing.entries[ctx.rng.nextInt(4)]
        if (mv == "look") { a.facing = dir; if (a.npc.trainer != null) pendingTrainerCheck = true; return }
        if (mv == "wander") {
            a.facing = dir
            if (a.npc.trainer != null) pendingTrainerCheck = true // a trainer that turns or walks into the player's line spots them at once
            val nx = a.x + dir.dx; val ny = a.y + dir.dy
            val home = a.npc
            if (kotlin.math.abs(nx - home.x) > 2 || kotlin.math.abs(ny - home.y) > 2) return
            if (!map.inBounds(nx, ny) || TileRules.isSolid(rules.behavior(map, nx, ny)) || rules.behavior(map, nx, ny) in setOf(TileBehavior.WATER, TileBehavior.WARP, TileBehavior.LEDGE_S, TileBehavior.LEDGE_E, TileBehavior.LEDGE_W)) return
            if (occupant(nx, ny) != null || (player.x == nx && player.y == ny) || (player.fromX == nx && player.fromY == ny)) return
            if (map.triggers.any { nx in it.x until it.x + it.w && ny in it.y until it.y + it.h }) return
            a.startStep(dir, nx, ny, 3f)
        }
    }

    /** Whether [self] may enter (x, y): walkable terrain with no player, NPC or item ball on it (or arriving there). */
    private fun tileFree(self: Actor, x: Int, y: Int): Boolean {
        if (!rules.walkableTerrain(map, x, y)) return false
        if (self !== player && ((player.x == x && player.y == y) || (player.moving && player.fromX == x && player.fromY == y))) return false
        for (o in actors.values) if (o !== self && o.visible && ((o.x == x && o.y == y) || (o.moving && o.fromX == x && o.fromY == y))) return false
        return map.items.none { !it.hidden && rules.itemVisible(map, it) && it.x == x && it.y == y }
    }

    /** Starts a collision-checked walk of [a] back to its home tile; false (and no movement) when no free route exists. */
    private fun startReturnHome(a: Actor): Boolean {
        val n = a.npc ?: return false
        val path = Pathing.findPath(a.x, a.y, n.x, n.y) { x, y -> tileFree(a, x, y) }
        if (path.isNullOrEmpty()) return false
        a.path.clear(); a.path.addAll(path); a.returningHome = true
        return true
    }

    /** Route for the script command `walkto`: null/empty when the actor is unknown, already there, or no free route exists. */
    fun planWalk(targetId: String, x: Int, y: Int): List<Facing>? {
        val a = actor(targetId) ?: return null
        return Pathing.findPath(a.x, a.y, x, y) { px, py -> tileFree(a, px, py) }
    }

    // ================================================================= warps & transitions

    fun doWarp(w: Warp) {
        game.audio.playSfx("door")
        val target = if (w.toMap == Warp.BACK) Triple(state.backMap.ifEmpty { content.info.startMap }, state.backX, state.backY) else Triple(w.toMap, w.toX, w.toY)
        val facing = w.facing?.let { Facing.parse(it) } ?: if (w.toMap == Warp.BACK) Facing.DOWN else player.facing
        val go = {
            // Gold/Silver/Crystal fade to white on warps (doors, stairs, caves).
            fadeColor = Color.WHITE
            fadeOut {
                doorAnim = null
                loadMap(target.first, target.second, target.third, facing, viaWarp = true)
                fadeIn { fadeColor = Color.BLACK }
            }
        }
        val tile = map.tileAt(w.x, w.y)
        val isDoor = tile == "door" || tile.endsWith("_door")
        if (isDoor && !game.options.reduceMotion) {
            // The door slides open before the fade; input waits on the timer, so nothing can move meanwhile.
            doorAnim = floatArrayOf(w.x.toFloat(), w.y.toFloat(), 0f)
            fx.enqueue(Clip("door_open", 0.22f, onUpdate = { t -> doorAnim?.set(2, t) }))
            wait(0.22f) { go() }
        } else go()
    }

    fun fadeOut(then: () -> Unit) { fadeTarget = 1f; fadeDone = then; if (fade == 1f) { fadeDone = null; then() } }
    fun fadeIn(then: () -> Unit) { fadeTarget = 0f; fadeDone = then; if (fade == 0f) { fadeDone = null; then() } }

    fun warpTo(mapId: String, x: Int, y: Int, facing: Facing?, then: () -> Unit) {
        fadeOut { loadMap(mapId, x, y, facing); fadeIn(then) }
    }

    // ================================================================= interaction

    private fun front(): Pair<Int, Int> = (player.x + player.facing.dx) to (player.y + player.facing.dy)

    private fun interact() {
        var (fx, fy) = front()
        // Talk over counters.
        if (rules.behavior(map, fx, fy) == TileBehavior.COUNTER) { fx += player.facing.dx; fy += player.facing.dy }
        actors.values.firstOrNull { it.visible && it.x == fx && it.y == fy }?.let { talkTo(it); return }
        map.items.firstOrNull { it.x == front().first && it.y == front().second && rules.itemVisible(map, it) }?.let { pickUp(it); return }
        val (tx, ty) = front()
        map.signs.firstOrNull { it.x == tx && it.y == ty }?.let { s ->
            val sc = s.script
            if (sc != null) runScriptById(sc, null) else game.say(ctx.format(s.text ?: "")); return
        }
        map.obstacles.firstOrNull { it.x == tx && it.y == ty }?.let { useObstacle(it); return }
        when (rules.behavior(map, tx, ty)) {
            TileBehavior.PC -> { game.audio.playSfx("pc_on"); push(PcScreen(game, ctx)) }
            TileBehavior.BOOKSHELF -> game.say("It's crammed full of POKéMON books.")
            TileBehavior.TV -> game.say("There's a movie on TV. Four kids are walking along a railroad track... I'd better go too.")
            TileBehavior.WATER -> offerSurf()
            TileBehavior.WATERFALL -> offerWaterfall()
            else -> checkHidden()
        }
    }

    private fun talkTo(a: Actor) {
        if (a.id.startsWith("event:")) { startEventEncounter(a); return }
        val n = a.npc ?: return
        val trainer = n.trainer
        val script = n.script
        val text = n.text
        if (!a.moving) a.facing = player.facing.opposite
        when {
            trainer != null && trainer !in state.defeatedTrainers -> startTrainerEncounter(a, approach = false)
            trainer != null && script == null -> game.say(ctx.format(content.trainers[trainer]?.after?.ifEmpty { "..." } ?: "..."))
            script != null -> runScriptById(script, n.id)
            text != null -> game.say(ctx.format(text))
        }
    }

    private fun pickUp(item: ItemPickup) {
        if (state.hasFlag("item:${item.id}")) return
        state.setFlag("item:${item.id}")
        state.addItem(item.item, item.qty)
        game.audio.playSfx("item_get")
        if (!item.hidden) {
            // The ball pops and vanishes; the item is already in the bag (the animation is cosmetic only).
            val a = floatArrayOf(item.x.toFloat(), item.y.toFloat(), 0f)
            pickupAnim = a
            fx.spawn(Clip("item_pickup", 0.35f, onUpdate = { t -> a[2] = t }, onEnd = { if (pickupAnim === a) pickupAnim = null }))
        } else {
            val a = floatArrayOf(item.x.toFloat(), item.y.toFloat(), 0f)
            pickupAnim = a
            fx.spawn(Clip("hidden_sparkle", 0.45f, onUpdate = { t -> a[2] = t }, onEnd = { if (pickupAnim === a) pickupAnim = null }))
        }
        val name = ctx.itemName(item.item)
        val pocket = when (if (item.item.startsWith("tm:")) pkmn.engine.data.BagPocket.TMS else ctx.dex.itemOrNull(item.item)?.pocket) {
            pkmn.engine.data.BagPocket.MEDICINE -> "MEDICINE POCKET"; pkmn.engine.data.BagPocket.BALLS -> "BALL POCKET"
            pkmn.engine.data.BagPocket.BERRIES -> "BERRY POCKET"; pkmn.engine.data.BagPocket.TMS -> "TM POCKET"
            pkmn.engine.data.BagPocket.KEY -> "KEY POCKET"; else -> "ITEM POCKET"
        }
        game.say("${state.playerName} found ${if (item.qty > 1) "${item.qty}× " else ""}$name!", "${state.playerName} put the $name in the $pocket.")
    }

    /** Hidden items are found by interacting with their tile (checked when facing an empty tile). */
    private fun checkHidden(): Boolean {
        val (tx, ty) = front()
        val it = map.items.firstOrNull { i -> i.hidden && i.x == tx && i.y == ty && rules.itemVisible(map, i) } ?: return false
        pickUp(it); return true
    }

    private fun useObstacle(o: ObstacleDef) {
        val (fm, verb, msg) = when (o.kind) {
            "cut_tree" -> Triple(FieldMove.CUT, "CUT", "This tree looks like it can be CUT down!")
            "rock_smash" -> Triple(FieldMove.ROCK_SMASH, "ROCK SMASH", "This rock looks breakable.")
            "boulder" -> Triple(FieldMove.STRENGTH, "STRENGTH", "It's a big boulder. A POKéMON may be able to push it.")
            else -> return
        }
        if (o.kind == "boulder" && state.hasFlag("strength_on:${baseMap.id}")) { game.say("Boulders may now be pushed."); return }
        if (!ctx.canUseFieldMove(fm)) { game.say(msg); return }
        game.ask("$msg Want to use $verb?") { yes ->
            if (!yes) return@ask
            game.say(ctx.bidoofLine(fm)) {
                if (o.kind == "boulder") state.setFlag("strength_on:${baseMap.id}")
                else {
                    state.setFlag(o.flag)
                    game.audio.playSfx(if (o.kind == "cut_tree") "cut" else "rock_smash")
                    val a = floatArrayOf(0f)
                    clearAnim = o to a
                    fx.spawn(Clip("clear_obstacle", 0.4f, onUpdate = { t -> a[0] = t }, onEnd = { if (clearAnim?.second === a) clearAnim = null }))
                    refreshMap()
                    if (o.kind == "rock_smash") ctx.rollEncounter(map, "rocksmash", forceEncounter = ctx.rng.nextInt(4) == 0)?.let { startWildBattle(it, true, null) {} }
                }
            }
        }
    }

    private fun offerSurf() {
        if (state.surfing) return
        if (!ctx.canUseFieldMove(FieldMove.SURF)) { if (!checkHidden()) game.say("The water is a deep blue colour..."); return }
        game.ask("The water is calm. Want to SURF?") { yes ->
            if (!yes) return@ask
            game.say(ctx.bidoofLine(FieldMove.SURF)) {
                state.surfing = true; state.biking = false
                player.sprite = playerSprite()
                game.audio.playMusic("ocean_surf")
                val (fx, fy) = front()
                player.startStep(player.facing, fx, fy, 4f)
            }
        }
    }

    private fun offerWaterfall() {
        if (!state.surfing || !ctx.canUseFieldMove(FieldMove.WATERFALL)) { game.say("It's a large waterfall."); return }
        game.ask("Want to use WATERFALL?") { yes ->
            if (!yes) return@ask
            game.say(ctx.bidoofLine(FieldMove.WATERFALL)) {
                // Climb until the tile above is no longer waterfall.
                var y = player.y
                while (map.inBounds(player.x, y - 1) && rules.behavior(map, player.x, y - 1) == TileBehavior.WATERFALL) y--
                if (map.inBounds(player.x, y - 1)) y--
                player.teleport(player.x, y); state.y = y
            }
        }
    }

    private fun useRegisteredItem() {
        if (state.itemCount("bicycle") > 0 && !baseMap.indoor && !state.surfing) toggleBike()
        else game.say("An item in the BAG can be registered to SELECT.")
    }

    fun toggleBike() {
        if (baseMap.indoor || state.surfing) { game.say("There's a time and place for everything! But not now."); return }
        state.biking = !state.biking
        player.sprite = playerSprite()
        game.audio.playMusic(if (state.biking) "bike" else baseMap.music)
    }

    /** Fishing from the bag. */
    fun fish(rod: String) {
        val (fx, fy) = front()
        if (rules.behavior(map, fx, fy) != TileBehavior.WATER) { game.say("There's a time and place for everything! But not now."); return }
        game.say("${state.playerName} used the ${ctx.itemName(rod)}!") {
            val mon = if (ctx.rng.nextInt(100) < 75) ctx.rollEncounter(map, rod, forceEncounter = true) else null
            if (mon == null) game.say("Not even a nibble!") else game.say("Oh! A bite!") { startWildBattle(mon, true, null, environment = "fishing") {} }
        }
    }

    fun useFlash() { flashUsed = true }

    // ================================================================= trainers

    private fun checkTrainerSight(): Boolean {
        val list = actors.values.filter { it.visible && it.npc?.trainer != null }.map { it.npc!! to (it.x to it.y) }
        val npc = rules.spottingTrainer(map, player.x, player.y, list, { n -> actors[n.id]?.facing ?: Facing.parse(n.facing) },
            inView = { x, y -> OverworldRules.inViewport(player.x, player.y, x, y, Renderer.W, Renderer.H) }, surfing = state.surfing) { x, y ->
            occupant(x, y) != null
        } ?: return false
        startTrainerEncounter(actors[npc.id]!!, approach = true)
        return true
    }

    private fun startTrainerEncounter(a: Actor, approach: Boolean) {
        val trainerId = a.npc?.trainer ?: return
        val t = content.trainers[trainerId] ?: return
        scriptBusy = true
        val begin = {
            a.facing = facingTowards(a.x, a.y, player.x, player.y)
            player.facing = a.facing.opposite
            val intro = ctx.format(t.intro.ifEmpty { "Let's battle!" })
            game.say(intro) {
                startTrainerBattle(trainerId, canLose = false) { scriptBusy = false }
            }
        }
        if (!approach) { begin(); return }
        a.emote = "!"; a.emoteTimer = 0.8f
        game.audio.playSfx("trainer_spotted")
        waitTimer = 0.8f
        waitDone = {
            val steps = (kotlin.math.abs(player.x - a.x) + kotlin.math.abs(player.y - a.y)) - 1
            repeat(steps) { a.path.addLast(a.facing) }
            if (steps <= 0) begin() else a.onPathDone = { begin() }
        }
    }

    private fun facingTowards(x: Int, y: Int, tx: Int, ty: Int): Facing = when {
        tx > x -> Facing.RIGHT; tx < x -> Facing.LEFT; ty > y -> Facing.DOWN; else -> Facing.UP
    }

    // ================================================================= battles

    fun startTrainerBattle(trainerId: String, canLose: Boolean, onDone: (Boolean) -> Unit) {
        val battle = ctx.createTrainerBattle(trainerId, map)
        runBattle(battle, trainerId, canLose) { outcome -> onDone(outcome == BattleOutcome.WIN) }
    }

    fun startWildBattle(mon: Pokemon, canRun: Boolean, music: String?, environment: String? = null, onDone: (BattleOutcome) -> Unit) {
        if (state.party.none { !it.isFainted }) { onDone(BattleOutcome.RAN); return }
        val battle = ctx.createWildBattle(mon, map, canRun, environment = environment)
        runBattle(battle, null, canLose = false, music = music ?: if (mon.species(ctx.dex).isLegendary || mon.species(ctx.dex).isMythical) "battle_legendary" else null, onDone = onDone)
    }

    private fun runBattle(battle: Battle, trainerId: String?, canLose: Boolean, music: String? = null, onDone: (BattleOutcome) -> Unit) {
        scriptBusy = true
        game.audio.playSfx("battle_start")
        val screen = BattleScreen(game, ctx, battle, trainerId, music) { outcome ->
            battleIntro = null
            val msgs = ctx.applyBattleResult(battle, trainerId, canLose)
            val lost = outcome == BattleOutcome.LOSE || outcome == BattleOutcome.DRAW
            val after = {
                if (lost && !canLose) blackout { scriptBusy = false; onDone(outcome) }
                else {
                    if (lost) ctx.healParty()
                    game.audio.playMusic(if (state.surfing) "ocean_surf" else if (state.biking) "bike" else baseMap.music)
                    runEvolutions(battle.leveledUp.toList()) {
                        scriptBusy = false
                        pendingTrainerCheck = true
                        if (stepsSinceSave > 0) autosave()
                        onDone(outcome)
                    }
                }
            }
            val showMessages = { if (msgs.isEmpty()) after() else game.say(*msgs.toTypedArray()) { after() } }
            val capture = ctx.lastCapture
            if (capture != null && capture.where != null && !capture.duplicate) {
                // The capture is already stored and registered; save now so an interruption resumes at the nickname
                // prompt (the offer is saved with the Pokémon) instead of losing the Pokémon.
                save()
                CaptureFlow.present(game, ctx, capture, save = { save() }) { showMessages() }
            } else showMessages()
        }
        if (game.options.reduceMotion) { push(screen); return }
        // Gold/Silver/Crystal-style transition: the screen flashes, then closes in; input is already locked (scriptBusy).
        battleIntro = 0f
        fx.enqueue(Clip("battle_transition", 0.85f, onUpdate = { t -> battleIntro = t }))
        wait(0.85f) { push(screen) }
    }

    private fun blackout(then: () -> Unit) {
        ctx.healParty()
        val (m, x, y) = ctx.respawnPoint()
        warpTo(m, x, y, Facing.DOWN) { then() }
    }

    /** Runs evolution screens one after another for the given party indices. */
    fun runEvolutions(indices: List<Int>, spun: Boolean = false, then: () -> Unit) {
        val pending = ctx.pendingEvolutions(indices, map, spun)
        fun next(i: Int) {
            if (i >= pending.size) { then(); return }
            val (idx, target) = pending[i]
            val mon = state.party[idx]
            push(EvolutionScreen(game, ctx, mon, target) { next(i + 1) })
        }
        next(0)
    }

    // ================================================================= scripts

    fun runScriptById(id: String, npcId: String?, then: () -> Unit = {}) {
        val s = content.scripts[id]
        if (s == null) { game.say("(Missing script: $id)") { then() }; return }
        scriptBusy = true
        autosaveGate.scriptStarted()
        ScriptHost(game, this, ctx, ScriptVM(ctx, s, { content.scripts[it] }, npcId)) {
            scriptBusy = false
            val saveNow = autosaveGate.scriptEnded()
            refreshMap()
            rebuildActorsKeepingPositions()
            pendingTrainerCheck = true
            if (saveNow) autosave()
            then()
        }.step()
    }

    private fun rebuildActorsKeepingPositions() {
        val old = HashMap(actors)
        rebuildActors()
        for ((id, a) in actors) old[id]?.let { o -> a.teleport(o.x, o.y); a.facing = o.facing }
    }

    fun actor(id: String): Actor? = if (id == "player") player else actors[id]

    fun setNpcVisible(id: String, visible: Boolean) {
        val flag = OverworldRules.hiddenFlag(baseMap.id, id)
        if (visible) state.clearFlag(flag) else state.setFlag(flag)
        actors[id]?.visible = visible
        if (visible && actors[id] == null) map.npcs.firstOrNull { it.id == id }?.let { n ->
            if (rules.npcVisible(map, n)) actors[n.id] = Actor(n.id, n.sprite, n.x, n.y, Facing.parse(n.facing), n)
        }
    }

    fun wait(seconds: Float, then: () -> Unit) { waitTimer = seconds; waitDone = then }
    fun shakeScreen() { shake = 0.5f }

    // ================================================================= saving

    override fun autosave() {
        if (!autosaveGate.request(scriptBusy)) return
        save()
    }

    fun save(): Boolean = try {
        state.map = baseMap.id; state.x = player.x; state.y = player.y; state.facing = player.facing.name.lowercase()
        ctx.syncRng()
        when (val r = game.saves.save(state)) {
            SaveResult.Saved -> { stepsSinceSave = 0; true }
            // A newer game's save is on disk: it is never overwritten, so report failure.
            is SaveResult.Refused -> { game.platform.log("Save refused: ${r.reason}"); false }
        }
    } catch (e: Exception) { game.platform.log("Save failed: $e"); false }

    override fun onBack(): Boolean { if (!scriptBusy && !player.moving) push(StartMenu(game, this)); return true }

    // ================================================================= drawing

    override fun draw(r: Renderer) {
        val camX = player.pixelX() + 8 - Renderer.W / 2 + if (shake > 0) ((game.timeMs / 30) % 3 - 1) * 2 else 0
        val camY = player.pixelY().let { if (player.jumping) (player.fromY + (player.y - player.fromY) * player.progress) * 16f else it } + 8 - Renderer.H / 2
        r.rect(0, 0, Renderer.W, Renderer.H, if (baseMap.indoor || baseMap.environment == "cave") Palette.BLACK else Color.valueOf("306830"))
        val outdoor = !baseMap.indoor && baseMap.environment != "cave"
        val light = if (outdoor) lightTint(ctx.lightPhase()) else Color.WHITE
        r.tint(light) {
            drawMap(r, map, 0, 0, camX, camY, border = true)
            for (c in map.connections) {
                val other = content.mapOrNull(c.map) ?: continue
                val (ox, oy) = when (c.direction) {
                    "north" -> c.offset to -other.height
                    "south" -> c.offset to map.height
                    "west" -> -other.width to c.offset
                    else -> map.width to c.offset
                }
                drawMap(r, other, ox, oy, camX, camY, border = false)
            }
        }
        if (outdoor && ctx.lightPhase() == LightPhase.NIGHT) drawWindowLights(r, camX, camY)
        r.tint(light.cpy().lerp(Color.WHITE, 0.35f)) {
            // Items and obstacles.
            for (it in map.items) if (!it.hidden && rules.itemVisible(map, it)) {
                r.draw(r.assets.character("itemball")[0][0], it.x * 16f - camX, it.y * 16f - camY)
            }
            for (o in map.obstacles) r.draw(r.assets.character(o.kind)[0][0], o.x * 16f - camX, o.y * 16f - camY)
            clearAnim?.let { (o, a) ->
                // The cleared tree/rock flickers and shrinks away.
                val t = a[0]
                if ((t * 8).toInt() % 2 == 0) {
                    val sz = 16f * (1f - t)
                    r.draw(r.assets.character(o.kind)[0][0], o.x * 16f - camX + (16 - sz) / 2, o.y * 16f - camY + (16 - sz), sz, sz)
                }
            }
            // NPCs sorted by y, then the player on top of them (see drawOrder). WorldModel.overlay (a foreground layer) is not rendered.
            val all = drawOrder(actors.values.filter { it.visible }, player)
            for (a in all) drawActor(r, a, camX, camY)
            // Tall grass hides the lower part of anyone standing in it (as in the Game Boy games).
            val grass = r.assets.tile("tall_grass").frame(game.timeMs)
            val grassFeet = com.badlogic.gdx.graphics.g2d.TextureRegion(grass, 0, 10, 16, 6)
            for (a in all) if (!a.moving && !a.sprite.startsWith("@mon:") && rules.behavior(map, a.x, a.y) == TileBehavior.TALL_GRASS)
                r.draw(grassFeet, a.x * 16f - camX, a.y * 16f - camY + 10)
        }
        doorAnim?.let { d ->
            // The player steps into the opened, dark doorway (drawn over them as it opens), then the warp fades to white.
            r.rect(d[0] * 16f - camX + 2, d[1] * 16f - camY - 4, 12f, 19f, Color(0.09f, 0.06f, 0.06f, 0.85f * d[2]))
        }
        pickupAnim?.let { a ->
            val t = a[2]
            val px = a[0] * 16f - camX + 8; val py = a[1] * 16f - camY + 8
            for (i in 0 until 4) {
                val ang = i * Math.PI / 2 + t * 3
                val rad = 4 + 10 * t
                r.rect(px + (kotlin.math.cos(ang) * rad).toFloat() - 1, py + (kotlin.math.sin(ang) * rad).toFloat() - 1 - 6 * t, 2f, 2f, Palette.HIGHLIGHT)
            }
        }
        // Tall grass covers the walker's feet and rustles when stepped in.
        for (g in rustles) {
            val gx = g[0] * 16f - camX; val gy = g[1] * 16f - camY
            val k = 1f - g[2] / 0.3f
            for (i in 0 until 3) r.rect(gx + 2 + i * 5, gy + 10 - 3 * k - (i % 2) * 2, 2f, 3f, Color(0.18f, 0.45f, 0.2f, k))
        }
        drawAtmosphere(r)
        if (popupTimer > 0 && popupText != null) {
            val w = r.textWidth(popupText!!) + 20
            r.frame(4, 4, w, 24)
            r.text(popupText!!, 14, 12)
        }
        if (fade > 0) r.rect(0f, 0f, Renderer.W.toFloat(), Renderer.H.toFloat(), Color(fadeColor.r, fadeColor.g, fadeColor.b, fade))
        battleIntro?.let { t ->
            if (t < 0.45f) {
                if ((t * 13).toInt() % 2 == 0) r.rect(0f, 0f, Renderer.W.toFloat(), Renderer.H.toFloat(), Color(1f, 1f, 1f, 0.75f))
            } else {
                // Horizontal bands close from the top and bottom toward the middle.
                val k = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
                val bands = 7
                val bh = Renderer.H / (2f * bands)
                for (i in 0 until bands) {
                    val h = bh * (k * bands - i).coerceIn(0f, 1f)
                    r.rect(0f, i * bh, Renderer.W.toFloat(), h, Palette.BLACK)
                    r.rect(0f, Renderer.H - i * bh - h, Renderer.W.toFloat(), h, Palette.BLACK)
                }
            }
        }
    }

    private fun drawMap(r: Renderer, m: MapData, ox: Int, oy: Int, camX: Float, camY: Float, border: Boolean) {
        val x0 = ((camX / 16).toInt() - 1 - ox).coerceAtLeast(if (border) -2 else 0)
        val y0 = ((camY / 16).toInt() - 1 - oy).coerceAtLeast(if (border) -2 else 0)
        val x1 = x0 + Renderer.W / 16 + 3
        val y1 = y0 + Renderer.H / 16 + 3
        val t = game.timeMs
        val borderTile = if (m.indoor || m.environment == "cave") null else r.assets.tile("tree")
        for (ty in y0..y1) for (tx in x0..x1) {
            val px = (ox + tx) * 16f - camX; val py = (oy + ty) * 16f - camY
            if (m.inBounds(tx, ty)) r.draw(r.assets.tile(m.tileAt(tx, ty)).frame(t), px, py)
            else if (border && borderTile != null && !hasConnectionAt(m, tx, ty)) r.draw(borderTile.frame(t), px, py)
        }
    }

    private fun hasConnectionAt(m: MapData, tx: Int, ty: Int): Boolean = m.connections.any { c ->
        when (c.direction) { "north" -> ty < 0; "south" -> ty >= m.height; "west" -> tx < 0; else -> tx >= m.width }
    }

    private fun drawActor(r: Renderer, a: Actor, camX: Float, camY: Float) {
        if (a.sprite.startsWith("@mon:")) { drawEventMon(r, a, camX, camY); return }
        val frames = r.assets.character(a.sprite)
        val dirRow = when (a.facing) { Facing.DOWN -> 0; Facing.UP -> 1; Facing.LEFT -> 2; Facing.RIGHT -> 3 }
        val px = a.pixelX() - camX
        val py = a.pixelY() - camY - 4
        if (a.jumping) r.rect(px + 3, (a.fromY + (a.y - a.fromY) * a.progress) * 16f - camY + 12, 10f, 3f, Color(0f, 0f, 0f, 0.35f))
        r.draw(frames[dirRow][a.frame()], px, py)
        a.emote?.let {
            r.frame(px.toInt() - 1, py.toInt() - 18, 18, 16)
            r.text(it, px.toInt() + 5, py.toInt() - 14)
        }
    }

    /** A scheduled event Pokémon: its icon sprite, gently bobbing, sparkling in when it appears. */
    private fun drawEventMon(r: Renderer, a: Actor, camX: Float, camY: Float) {
        val species = a.sprite.removePrefix("@mon:")
        val tex = r.assets.pokemon(species, "icon")
        val appear = appearing[a.id] ?: 1f
        val bob = if (game.options.reduceMotion) 0f else kotlin.math.sin(game.timeMs / 300.0).toFloat() * 1.5f
        val w = 24f * appear; val h = 24f * appear
        val px = a.x * 16f - camX + 8 - w / 2
        val py = a.y * 16f - camY + 14 - h + bob
        r.rect(a.x * 16f - camX + 3, a.y * 16f - camY + 12, 10f, 3f, Color(0f, 0f, 0f, 0.3f))
        r.draw(tex, px, py, w, h)
        if (appear < 1f || (game.timeMs / 500) % 4 == 0L) {
            val t = (game.timeMs % 1000) / 1000f
            for (i in 0 until 3) r.rect(px + (i * 9 + t * 6) % w, py + (i * 7) % h, 2f, 2f, Palette.HIGHLIGHT)
        }
    }

    /** Multiplicative lighting per phase: keeps pixel art crisp and readable (no blur, no washed-out overlay). */
    private fun lightTint(p: LightPhase): Color = when (p) {
        LightPhase.MORNING -> Color(1f, 0.96f, 0.88f, 1f)
        LightPhase.DAY -> Color.WHITE
        LightPhase.EVENING -> Color(1f, 0.84f, 0.72f, 1f)
        LightPhase.NIGHT -> Color(0.58f, 0.62f, 0.86f, 1f)
    }

    private val warmLight = Color(1f, 0.86f, 0.45f, 0.9f)

    /** At night, building windows glow (drawn after the tint, so they stay bright). */
    private fun drawWindowLights(r: Renderer, camX: Float, camY: Float) {
        val x0 = (camX / 16).toInt() - 1; val y0 = (camY / 16).toInt() - 1
        for (ty in y0..y0 + Renderer.H / 16 + 2) for (tx in x0..x0 + Renderer.W / 16 + 2) {
            if (!map.inBounds(tx, ty)) continue
            val t = map.tileAt(tx, ty)
            if (t == "wall_window" || t == "gym_window") r.rect(tx * 16f - camX + 4, ty * 16f - camY + 4, 8f, 6f, warmLight)
        }
    }

    private fun drawAtmosphere(r: Renderer) {
        val outdoor = !baseMap.indoor && baseMap.environment != "cave"
        if (outdoor) {
            when (baseMap.weather) {
                "rain" -> {
                    val t = game.timeMs / 16
                    for (i in 0 until 60) {
                        val x = ((i * 53 + t * 3) % (Renderer.W + 40)).toFloat() - 20
                        val y = ((i * 97 + t * 9) % (Renderer.H + 20)).toFloat() - 10
                        r.rect(x, y, 1f, 6f, Color(0.7f, 0.8f, 1f, 0.6f))
                    }
                    r.rect(0f, 0f, Renderer.W.toFloat(), Renderer.H.toFloat(), Color(0.1f, 0.15f, 0.3f, 0.15f))
                }
                "snow" -> {
                    val t = game.timeMs / 30
                    for (i in 0 until 50) {
                        val x = ((i * 61 + t + (i % 7) * kotlin.math.sin((t + i * 10) / 20.0) * 4).toInt() % Renderer.W).toFloat()
                        val y = ((i * 37 + t * 2) % Renderer.H).toFloat()
                        r.rect(x, y, 2f, 2f, Color(1f, 1f, 1f, 0.85f))
                    }
                }
                "sand" -> r.rect(0f, 0f, Renderer.W.toFloat(), Renderer.H.toFloat(), Color(0.8f, 0.65f, 0.35f, 0.25f))
                "fog" -> r.rect(0f, 0f, Renderer.W.toFloat(), Renderer.H.toFloat(), Color(0.9f, 0.9f, 0.95f, 0.35f))
            }
        }
        if (baseMap.dark && !flashUsed) {
            val cx = player.pixelX() + 8 - (player.pixelX() + 8 - Renderer.W / 2)
            val cy = Renderer.H / 2f
            val rad = 28f
            val c = Color(0f, 0f, 0f, 0.92f)
            r.rect(0f, 0f, Renderer.W.toFloat(), cy - rad, c)
            r.rect(0f, cy + rad, Renderer.W.toFloat(), Renderer.H - cy - rad, c)
            r.rect(0f, cy - rad, cx - rad, rad * 2, c)
            r.rect(cx + rad, cy - rad, Renderer.W - cx - rad, rad * 2, c)
        }
    }
}
