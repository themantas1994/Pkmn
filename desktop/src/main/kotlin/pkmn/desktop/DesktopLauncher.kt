package pkmn.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import pkmn.game.GameAudio
import pkmn.game.NoAudio
import pkmn.game.PkmnGame
import pkmn.game.Platform
import pkmn.game.SynthAudio
import pkmn.game.input.Button
import java.io.File

class DesktopPlatform(override val saveDir: File, override val forceTouchControls: Boolean = false) : Platform {
    override val name = "desktop"
    override val isMobile = false
    override val importedAssetsDir: File? = listOf(File("../assets-local"), File("assets-local")).firstOrNull { it.isDirectory }
    override fun exit() { Gdx.app.exit() }
    override fun localAddresses() = pkmn.game.link.LocalAddresses.ipv4()
}

/**
 * Desktop launcher. Options:
 *   --saves=<dir>        save directory (default ~/.pkmn-crystalline)
 *   --script=<file>      automation script, one command per line: wait N, press BUTTON [frames], mash BUTTON N,
 *                        goto X Y (walk on the current map), log, perf, shot NAME, quit,
 *                        party SPECIES:LEVEL..., trainer ID, wild SPECIES LEVEL, warp MAP X Y [FACING],
 *                        option NAME VALUE (battleStyle, battleAnimations, animSpeed, textSpeed, realTimeClock),
 *                        clock YYYY-MM-DDTHH:MM [ZONE] (fixed test clock), clock real, waittop SCREEN [frames], item ID [QTY],
 *                        waitstate TEXT [frames] (the debug state of the top screen (battle, link lobby, link trade) contains TEXT), save,
 *                        ot (the party's Pokémon with no original trainer get the player as OT, as a catch would),
 *                        link host [PORT] [flat] [trade] (host a link battle with default rules, or a trade; start when the guest is ready),
 *                        link join CODE|ADDRESS[:PORT] (join a host, ready as soon as it has rules; follows the host's battle or trade),
 *                        linkplay fight | linkplay forfeit TURN (drive the link battle's menus until its result screen shows)
 *   --shots=<dir>        where screenshots go
 *   --mute               no audio
 *   --scale=N            window scale (default 3)
 *   --portrait           phone-like portrait window with touch controls (540x1080, or --size=WxH)
 *   --quickstart[=NAME]  skip title/intro and start a new game (debugging)
 *   --fixedstep          every frame advances 1/60 s regardless of real time (reproducible screenshots)
 *   --selftest=determinism  play 400 seeded AI battles, print one combined hash and exit (no window). Content is read from
 *                        --assets=<dir>, else the working directory if it holds data/, else ./assets. Add --verbose for per-battle hashes.
 */
fun main(args: Array<String>) {
    val opts = args.associate { a -> a.removePrefix("--").substringBefore('=') to a.substringAfter('=', "true") }
    if (opts["selftest"] == "determinism") runDeterminismSelfTest(opts)
    val saves = File(opts["saves"] ?: (System.getProperty("user.home") + "/.pkmn-crystalline"))
    val platform = DesktopPlatform(saves, forceTouchControls = opts["portrait"] == "true")
    val audio: (() -> GameAudio)? = if (opts["mute"] == "true") ({ NoAudio }) else ({ SynthAudio() })
    val game = PkmnGame(platform, audio)
    opts["quickstart"]?.let { game.quickstart = if (it == "true") "ARI" else it }
    if (opts["fixedstep"] == "true") game.fixedStep = 1f / 60f
    opts["script"]?.let { Automation(File(it).readLines(), File(opts["shots"] ?: "shots")).attach(game) }
    val scale = opts["scale"]?.toIntOrNull() ?: 3
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Pokémon Crystalline")
        if (opts["portrait"] == "true") {
            val (pw, ph) = (opts["size"] ?: "540x1080").split('x').map { it.toInt() }
            setWindowedMode(pw, ph)
        } else setWindowedMode(256 * scale, 224 * scale)
        setForegroundFPS(60)
        useVsync(true)
        if (opts.containsKey("script")) setIdleFPS(60)
    }
    Lwjgl3Application(game, config)
}

/** Phase 0 of link play: the same combined hash must come out on the desktop JVM and on Android (log tag PkmnDeterminism). */
private fun runDeterminismSelfTest(opts: Map<String, String>): Nothing {
    val root = opts["assets"]?.let { File(it) } ?: listOf(File("."), File("assets")).first { File(it, "data/species.json").isFile }
    val dex = pkmn.engine.data.Dex.load(pkmn.engine.data.DirectoryContentSource(root))
    val verbose = opts["verbose"] == "true"
    val r = pkmn.engine.battle.DeterminismSelfTest.run(dex) { i, h -> if (verbose) println("battle $i $h") }
    println(pkmn.engine.battle.DeterminismSelfTest.summary(r))
    kotlin.system.exitProcess(0)
}

/** Feeds scripted input and captures screenshots (used for headless verification under Xvfb). */
class Automation(script: List<String>, private val shots: File) {
    private val lines = script.toMutableList()
    private var pc = 0
    private var wait = 0
    private var hold: Button? = null
    private var pilot: pkmn.game.overworld.Autopilot? = null
    /** `waittop NAME [frames]`: pause until the top screen's class is NAME (or the timeout passes). */
    private var waitTop: String? = null
    private var waitTopFrames = 0
    /** `linkplay`: "fight", or "forfeit:TURN"; null when off. */
    private var linkPlay: String? = null
    private var linkPlaySawBattle = false
    private val linkSteps = ArrayDeque<Pair<Button?, Int>>()

    fun attach(game: PkmnGame) {
        shots.mkdirs()
        game.frameHook = { g -> tick(g) }
    }

    private fun tick(g: PkmnGame) {
        val p = pilot ?: pkmn.game.overworld.Autopilot(g).also { pilot = it }
        if (p.active) { p.tick(); return }
        waitTop?.let { name ->
            val match = if (name.startsWith("state:")) debugState(g.top())?.contains(name.removePrefix("state:")) == true
                else g.top()?.javaClass?.simpleName == name
            if (match || --waitTopFrames <= 0) { if (waitTopFrames <= 0) println("AUTOMATION waittop $name timed out (top=${g.top()?.javaClass?.simpleName})"); waitTop = null } else return
        }
        if (wait > 0) { wait--; if (wait == 0) hold?.let { g.input.inject(it, false); hold = null }; return }
        if (linkPlay != null) { driveLinkBattle(g); if (linkPlay != null) return }
        while (pc < lines.size) {
            val parts = lines[pc++].trim().split(Regex("\\s+"))
            when (parts[0]) {
                "", "#" -> continue
                "wait" -> { wait = parts[1].toInt(); return }
                "press" -> {
                    val b = Button.valueOf(parts[1].uppercase())
                    val frames = parts.getOrNull(2)?.toInt() ?: 2
                    g.input.inject(b, true); hold = b; wait = frames; return
                }
                "mash" -> {
                    // mash BUTTON N: press N times, 12 frames apart.
                    val b = parts[1].uppercase(); val n = parts[2].toInt()
                    val expanded = (0 until n).flatMap { listOf("press $b", "wait 10") }
                    lines.addAll(pc, expanded); continue
                }
                "log" -> {
                    val ow = g.screens().filterIsInstance<pkmn.game.overworld.OverworldScreen>().lastOrNull()
                    println("AUTOMATION frame=${g.frameCount} stack=${g.screens().joinToString(">") { it.javaClass.simpleName }} top=${g.top()?.javaClass?.simpleName} ${debugState(g.top()) ?: ""}${(g.top() as? pkmn.game.link.LinkResultScreen)?.summary()?.let { " $it" } ?: ""} map=${ow?.baseMap?.id} pos=${ow?.player?.x},${ow?.player?.y} moving=${ow?.player?.moving}")
                    continue
                }
                "perf" -> {
                    // CPU time per frame (update + draw submission) over the last FRAME_SAMPLES frames, plus FPS.
                    val n = minOf(g.frameCount, pkmn.game.FRAME_SAMPLES.toLong()).toInt()
                    val v = g.frameNanos.take(n).sorted()
                    if (v.isNotEmpty()) println("PERF frames=$n meanMs=%.3f p95Ms=%.3f maxMs=%.3f fps=%d heapMb=%d".format(
                        v.average() / 1e6, v[(v.size - 1) * 95 / 100] / 1e6, v.last() / 1e6, Gdx.graphics.framesPerSecond,
                        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576))
                    continue
                }
                "goto" -> { p.goto(parts[1].toInt(), parts[2].toInt()); return }
                "shot" -> { screenshot(File(shots, parts[1] + ".png")); continue }
                "waittop" -> { waitTop = parts[1]; waitTopFrames = parts.getOrNull(2)?.toInt() ?: 1200; return }
                "waitstate" -> { waitTop = "state:" + parts[1]; waitTopFrames = parts.getOrNull(2)?.toInt() ?: 1200; return }
                "party" -> {
                    val ctx = g.session ?: continue
                    ctx.state.party.clear()
                    for (spec in parts.drop(1)) {
                        val f = spec.split(':')
                        val moves = f.getOrNull(2)?.split('/')
                        ctx.state.party.add(ctx.factory.create(f[0], f.getOrNull(1)?.toInt() ?: 50, ctx.rng, moves = moves)
                            .also { m -> f.getOrNull(3)?.let { id -> m.status = pkmn.engine.mon.MajorStatus.byId(id) ?: error("unknown status '$id' in party spec") } })
                    }
                    continue
                }
                // Level-100 scene parties only obey with enough badges (Obedience), so scenes grant them explicitly.
                "badges" -> { g.session?.state?.let { st -> st.badges.clear(); st.badges.addAll(1..parts[1].toInt()) }; continue }
                "ot" -> {
                    val ctx = g.session ?: continue
                    ctx.state.party.filter { it.otName.isEmpty() }.forEach { it.otName = ctx.state.playerName; it.otId = ctx.state.trainerId }
                    continue
                }
                "flag" -> { g.session?.state?.setFlag(parts[1]); continue }
                "item" -> { g.session?.state?.addItem(parts[1], parts.getOrNull(2)?.toInt() ?: 1); continue }
                "trainer" -> { overworld(g)?.startTrainerBattle(parts[1], canLose = true) {}; return }
                "wild" -> {
                    val ctx = g.session ?: continue
                    overworld(g)?.startWildBattle(ctx.factory.create(parts[1], parts[2].toInt(), ctx.rng), true, null) {}; return
                }
                "warp" -> {
                    overworld(g)?.warpTo(parts[1], parts[2].toInt(), parts[3].toInt(), parts.getOrNull(4)?.let { pkmn.engine.world.Facing.parse(it) }) {}
                    wait = 40; return
                }
                "option" -> {
                    val o = g.options
                    when (parts[1]) {
                        "battleStyle" -> o.battleStyle = parts[2]
                        "battleAnimations" -> o.battleAnimations = parts[2].toBoolean()
                        "animSpeed" -> o.animSpeed = parts[2].toInt()
                        "textSpeed" -> o.textSpeed = parts[2].toInt()
                        "realTimeClock" -> o.realTimeClock = parts[2].toBoolean()
                    }
                    g.applyOptions(); continue
                }
                "clock" -> {
                    val ctx = g.session ?: continue
                    if (parts[1] == "real") ctx.clock = pkmn.engine.campaign.GameClock.system()
                    else ctx.clock = pkmn.engine.campaign.GameClock.fixed(java.time.LocalDateTime.parse(parts[1]), java.time.ZoneId.of(parts.getOrNull(2) ?: "UTC"))
                    g.refreshWorldTime()
                    continue
                }
                "linkplay" -> { linkPlay = if (parts[1] == "forfeit") "forfeit:${parts.getOrNull(2) ?: "1"}" else "fight"; continue }
                "save" -> { println("AUTOMATION save ${overworld(g)?.save()}"); continue }
                "link" -> {
                    val ctx = g.session ?: continue
                    when (parts[1]) {
                        "host" -> g.push(pkmn.game.link.LinkLobbyScreen(g, ctx, pkmn.game.link.AutoLink(host = true,
                            port = parts.drop(2).firstNotNullOfOrNull { it.toIntOrNull() } ?: pkmn.engine.link.LinkAddress.DEFAULT_PORT, flat = "flat" in parts, trade = "trade" in parts)))
                        "join" -> g.push(pkmn.game.link.LinkLobbyScreen(g, ctx, pkmn.game.link.AutoLink(host = false,
                            endpoint = pkmn.engine.link.LinkAddress.parse(parts[2]) ?: error("bad link address '${parts[2]}'"))))
                        else -> error("unknown link command '${parts[1]}'")
                    }
                    return
                }
                "quit" -> { Gdx.app.exit(); return }
                else -> if (parts[0].startsWith("#")) continue
            }
        }
    }

    /**
     * `linkplay`: at the battle's action menu, FIGHT with the first move (or, once the battle has reached the turn, FORFEIT
     * and confirm); while text is showing, A advances it. Blocks the script until the battle screen is gone, so it goes on
     * at the result screen.
     */
    private fun driveLinkBattle(g: PkmnGame) {
        val top = g.top()
        // The battle's own prompts (YES/NO, text boxes) sit on top of it while queued input is still being played.
        if (top is pkmn.game.link.LinkResultScreen || (linkPlaySawBattle && top is pkmn.game.overworld.OverworldScreen)) { linkPlay = null; linkPlaySawBattle = false; linkSteps.clear(); return }
        if (top is pkmn.game.battle.BattleScreen) linkPlaySawBattle = true
        val step = linkSteps.removeFirstOrNull()
        if (step != null) {
            val (b, frames) = step
            if (b == null) wait = frames else { g.input.inject(b, true); hold = b; wait = frames }
            return
        }
        if (top !is pkmn.game.battle.BattleScreen) return
        val st = top.debugState()
        val forfeitTurn = linkPlay!!.takeIf { it.startsWith("forfeit:") }?.removePrefix("forfeit:")?.toIntOrNull()
        val turn = Regex("turn=(\\d+)").find(st)?.groupValues?.get(1)?.toInt() ?: 0
        val A = Button.A
        when {
            "menu=ACTION" in st && forfeitTurn != null && turn >= forfeitTurn -> linkSteps.addAll(listOf(Button.DOWN to 3, null to 10, A to 3, null to 15, A to 3, null to 30))
            "menu=ACTION" in st -> linkSteps.addAll(listOf(A to 3, null to 10, A to 3, null to 30))
            "menu=NONE" in st && "phase=CHOOSE_ACTION" !in st -> linkSteps.addAll(listOf(A to 3, null to 20))
        }
    }

    /** What `log` and `waitstate` see of a screen: the battle's or a link screen's one-line state. */
    private fun debugState(s: pkmn.game.screens.Screen?): String? = (s as? pkmn.game.battle.BattleScreen)?.debugState() ?: (s as? pkmn.game.link.LinkDebug)?.debugState()

    private fun overworld(g: PkmnGame) = g.screens().filterIsInstance<pkmn.game.overworld.OverworldScreen>().lastOrNull()

    private fun screenshot(f: File) {
        val w = Gdx.graphics.backBufferWidth; val h = Gdx.graphics.backBufferHeight
        val pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true)
        for (i in 3 until pixels.size step 4) pixels[i] = 255.toByte()
        val pm = Pixmap(w, h, Pixmap.Format.RGBA8888)
        BufferUtils.copy(pixels, 0, pm.pixels, pixels.size)
        PixmapIO.writePNG(Gdx.files.absolute(f.absolutePath), pm)
        pm.dispose()
    }
}
