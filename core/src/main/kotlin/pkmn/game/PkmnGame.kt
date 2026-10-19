package pkmn.game

import java.io.File
import pkmn.engine.util.toId
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.InputAdapter
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.WorldContent
import pkmn.engine.data.ContentSource
import pkmn.engine.data.Dex
import pkmn.engine.save.GameState
import pkmn.engine.save.Options
import pkmn.engine.save.SaveManager
import pkmn.game.gfx.Assets
import pkmn.game.gfx.Renderer
import pkmn.game.input.Button
import pkmn.game.input.InputState
import pkmn.game.input.TouchControls
import pkmn.game.screens.LoadingScreen
import pkmn.game.screens.Screen
import kotlin.concurrent.thread

/** Frames kept for frame-time statistics. */
const val FRAME_SAMPLES = 600

/**
 * Application root: owns the screen stack, the fixed-resolution framebuffer, input and shared services.
 */
class PkmnGame(val platform: Platform, private val audioFactory: (() -> GameAudio)? = null) : ApplicationAdapter() {
    lateinit var batch: SpriteBatch
    lateinit var assets: Assets
    lateinit var r: Renderer
    var audio: GameAudio = NoAudio
        private set
    val input = InputState()
    val touch = TouchControls()
    private lateinit var fbo: FrameBuffer
    private lateinit var fboRegion: TextureRegion
    private lateinit var screenCam: OrthographicCamera
    private lateinit var gameCam: OrthographicCamera
    private lateinit var shapes: ShapeRenderer
    private val gameRect = Rectangle()
    private val stack = ArrayList<Screen>()
    private val pending = ArrayList<() -> Unit>()

    // Shared services (populated by LoadingScreen).
    @Volatile var dex: Dex? = null
    @Volatile var content: WorldContent? = null
    var session: GameContext? = null
    val saves: SaveManager by lazy { SaveManager(platform.saveDir, dex) }
    var options = Options()
    val contentSource = ContentSource { path -> Gdx.files.internal(path).takeIf { it.exists() }?.readString("UTF-8") }
    var timeMs = 0L
        private set
    /** Frames rendered (for tests and the screenshot harness). */
    var frameCount = 0L
        private set
    /** Debug: player name for skipping the title and intro (desktop --quickstart). */
    var quickstart: String? = null
    /** Automation: advance exactly this many seconds per frame so screenshot runs are reproducible (null = real time). */
    var fixedStep: Float? = null
    /** Optional per-frame hook (automation). */
    var frameHook: ((PkmnGame) -> Unit)? = null

    override fun create() {
        batch = SpriteBatch()
        shapes = ShapeRenderer()
        fbo = FrameBuffer(Pixmap.Format.RGBA8888, Renderer.W, Renderer.H, false)
        fbo.colorBufferTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        fboRegion = TextureRegion(fbo.colorBufferTexture).also { it.flip(false, true) }
        gameCam = OrthographicCamera().also { it.setToOrtho(false, Renderer.W.toFloat(), Renderer.H.toFloat()) }
        screenCam = OrthographicCamera()
        input.touch = touch
        touch.hapticHook = { if (options.haptics) platform.vibrate(12) }
        Gdx.input.inputProcessor = InputMultiplexer(touch, object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (keycode == Input.Keys.BACK) { handleBack(); return true }
                return false
            }
        })
        @Suppress("DEPRECATION")
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        // Start loading the content on a background thread; assets need the GL thread.
        thread(name = "content-loader", isDaemon = true) {
            try {
                val d = Dex.load(contentSource)
                dex = d
                d.learnsets // parse eagerly off the render thread
                content = WorldContent(contentSource)
                content!!.scripts
            } catch (e: Throwable) {
                loadError = e
                platform.log("Content loading failed: $e")
            }
        }
        assets = Assets(platform.importedAssetsDir, null)
        r = Renderer(batch, assets)
        audio = try { audioFactory?.invoke() ?: NoAudio } catch (e: Throwable) { platform.log("Audio unavailable: $e"); NoAudio }
        options = loadOptions()
        applyOptions()
        push(LoadingScreen(this))
        resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    @Volatile var loadError: Throwable? = null

    /** Re-creates the asset manager once the Dex is available (placeholder sprites need species data). */
    fun attachDexToAssets() {
        val old = assets
        assets = Assets(platform.importedAssetsDir, dex)
        r = Renderer(batch, assets)
        old.dispose()
        configureCries()
    }

    /** Cry files exist only in the local override directory (assets-local/audio/cries, Android imported/audio/cries). */
    private fun configureCries() {
        val d = dex ?: return
        val dirs = listOfNotNull(platform.importedAssetsDir?.let { File(it, "audio/cries") })
        audio.configureCries(dirs) { id -> d.speciesOrNull(id)?.baseSpecies?.let { toId(it) }?.takeIf { it != id } }
    }

    // ------------------------------------------------------------------ screen stack

    fun push(s: Screen) { pending.add { stack.add(s); s.onEnter() } }
    fun pop(s: Screen) { pending.add { if (stack.remove(s)) s.onExit() } }
    fun replace(old: Screen, new: Screen) { pending.add { val i = stack.indexOf(old); if (i >= 0) { stack[i] = new; old.onExit(); new.onEnter() } else { stack.add(new); new.onEnter() } } }
    fun resetTo(s: Screen) { pending.add { stack.toList().forEach { it.onExit() }; stack.clear(); stack.add(s); s.onEnter() } }
    /** Runs [r] after everything currently queued (next pending batch). */
    fun later(r: () -> Unit) { pending.add(r) }
    /** Shared lease for the evolution theme, so consecutive evolutions keep it and the map music comes back once. */
    val evolutionMusic = pkmn.game.audio.MusicLease("evolution")
    fun top(): Screen? = stack.lastOrNull()
    fun screens(): List<Screen> = stack

    internal fun flushPending() { while (pending.isNotEmpty()) { val p = pending.toList(); pending.clear(); p.forEach { it() } } }

    // ------------------------------------------------------------------ loop

    /** CPU time (ns) of the last [FRAME_SAMPLES] frames' update + draw submission, for the perf overlay/automation. */
    val frameNanos = LongArray(FRAME_SAMPLES)

    override fun render() {
        val frameStart = System.nanoTime()
        val dt = fixedStep ?: minOf(Gdx.graphics.deltaTime, 0.05f)
        timeMs += (dt * 1000).toLong()
        session?.state?.let { it.playTimeMs += (dt * 1000).toLong() }
        flushPending()
        frameHook?.invoke(this)
        input.update(dt)
        stack.lastOrNull()?.update(dt, input)
        flushPending()
        audio.update()

        fbo.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        batch.projectionMatrix = gameCam.combined
        batch.begin()
        val firstOpaque = stack.indexOfLast { it.opaque }.coerceAtLeast(0)
        for (i in firstOpaque until stack.size) stack[i].draw(r)
        batch.end()
        fbo.end()

        Gdx.gl.glClearColor(0.07f, 0.07f, 0.09f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        batch.projectionMatrix = screenCam.combined
        batch.begin()
        batch.draw(fboRegion, gameRect.x, gameRect.y, gameRect.width, gameRect.height)
        batch.end()
        if (touch.enabled) drawTouchControls()
        frameNanos[(frameCount % FRAME_SAMPLES).toInt()] = System.nanoTime() - frameStart
        frameCount++
    }

    // Reused every frame: the touch overlay is drawn each frame on phones and must not allocate.
    private val touchBase = Color(1f, 1f, 1f, 0.16f)
    private val touchActive = Color(1f, 1f, 1f, 0.38f)
    private val savedTransform = com.badlogic.gdx.math.Matrix4()
    private val labelTransform = com.badlogic.gdx.math.Matrix4()

    private fun touchColor(b: Button) = if (input.pressed(b)) touchActive else touchBase

    private fun drawTouchControls() {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapes.projectionMatrix = screenCam.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        val d = touch.dpad
        val t = d.width / 3
        shapes.color = touchColor(Button.UP); shapes.rect(d.x + t, d.y + 2 * t, t, t)
        shapes.color = touchColor(Button.DOWN); shapes.rect(d.x + t, d.y, t, t)
        shapes.color = touchColor(Button.LEFT); shapes.rect(d.x, d.y + t, t, t)
        shapes.color = touchColor(Button.RIGHT); shapes.rect(d.x + 2 * t, d.y + t, t, t)
        shapes.color = touchBase; shapes.rect(d.x + t, d.y + t, t, t)
        shapes.color = touchColor(Button.A); shapes.circle(touch.a.x + touch.a.width / 2, touch.a.y + touch.a.height / 2, touch.a.width / 2)
        shapes.color = touchColor(Button.B); shapes.circle(touch.b.x + touch.b.width / 2, touch.b.y + touch.b.height / 2, touch.b.width / 2)
        shapes.color = touchColor(Button.START); shapes.rect(touch.start.x, touch.start.y, touch.start.width, touch.start.height)
        shapes.color = touchColor(Button.SELECT); shapes.rect(touch.select.x, touch.select.y, touch.select.width, touch.select.height)
        shapes.end()
        // Labels
        batch.begin()
        val s = (touch.a.width / 24f).coerceAtLeast(1f)
        touchLabel("A", touch.a, s); touchLabel("B", touch.b, s); touchLabel("START", touch.start, s); touchLabel("SELECT", touch.select, s)
        batch.end()
    }

    private fun touchLabel(txt: String, rect: Rectangle, s: Float) {
        val f = assets.font8
        savedTransform.set(batch.transformMatrix)
        // Long labels (START/SELECT) shrink to fit their button.
        val ls = minOf(s, rect.width * 0.85f / f.width(txt))
        labelTransform.set(savedTransform).translate(rect.x + rect.width / 2 - f.width(txt) * ls / 2, rect.y + rect.height / 2 + 4 * ls, 0f).scale(ls, ls, 1f)
        batch.transformMatrix = labelTransform // the setter flushes what was drawn with the old matrix, then copies this one
        // draw with y-up: reuse glyph regions directly
        var cx = 0f
        batch.setColor(1f, 1f, 1f, 0.7f)
        for (ch in txt) { glyphOf(ch)?.let { batch.draw(it, cx, -8f) }; cx += 8f }
        batch.setColor(Color.WHITE)
        batch.transformMatrix = savedTransform
    }

    private fun glyphOf(ch: Char): TextureRegion? = assets.font8.glyph(ch)

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        screenCam.setToOrtho(false, width.toFloat(), height.toFloat())
        val portrait = height > width
        val showTouch = options.showTouchControls &&
            (platform.isMobile || platform.forceTouchControls || Gdx.input.isPeripheralAvailable(com.badlogic.gdx.Input.Peripheral.MultitouchScreen))
        touch.enabled = showTouch
        // Integer scale that fits; in portrait leave room for controls below.
        val availH = if (portrait && showTouch) (height * 0.58f).toInt() else height
        val scale = maxOf(1, minOf(width / Renderer.W, availH / Renderer.H))
        val gw = Renderer.W * scale; val gh = Renderer.H * scale
        val gx = (width - gw) / 2f
        val gy = if (portrait && showTouch) height - gh - (height * 0.03f) else (height - gh) / 2f
        gameRect.set(gx, gy, gw.toFloat(), gh.toFloat())
        touch.layout(width, height, gameRect, options.controlScale)
    }

    override fun pause() {
        audio.pause()
        // A finger that is down when the app is backgrounded never delivers its touch-up: drop it or the button sticks.
        touch.releaseAll()
        input.clearAll()
        // Android may kill the process while backgrounded: autosave when it is safe to do so.
        autosaveTarget(stack)?.autosave()
        saveOptions()
    }

    /**
     * One Android BACK press = one action. The top screen's [Screen.onBack] decides; only when it does not handle the
     * key does it count as a single B press (a tap lasting one frame). Keys.BACK is not polled by [InputState], so the
     * press cannot also reach the screen underneath after this one pops itself.
     */
    internal fun handleBack() {
        val top = stack.lastOrNull() ?: return
        if (!top.onBack()) input.tap(Button.B)
    }

    override fun resume() {
        audio.resume()
        // The device clock or time zone may have changed while suspended: re-evaluate time of day and events now.
        refreshWorldTime()
    }

    /** Re-reads the wall clock for the overworld (lighting, scheduled events). Safe to call at any time. */
    fun refreshWorldTime() {
        stack.filterIsInstance<pkmn.game.overworld.OverworldScreen>().forEach { it.refreshWorldTime(force = true) }
    }

    override fun dispose() {
        audio.dispose()
        assets.dispose()
        batch.dispose()
        shapes.dispose()
        fbo.dispose()
    }

    // ------------------------------------------------------------------ options

    private val optionsFile get() = java.io.File(platform.saveDir, "options.json")

    fun loadOptions(): Options = try {
        if (optionsFile.exists()) Options.decodeLegacyAware(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, optionsFile.readText()) else Options()
    } catch (e: Exception) { Options() }

    fun saveOptions() {
        try {
            platform.saveDir.mkdirs()
            val tmp = java.io.File(platform.saveDir, "options.json.tmp")
            // Every field is written so a later change of a default never reinterprets this file (see Options.decodeLegacyAware).
            tmp.writeText(kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Options.serializer(), options))
            if (!tmp.renameTo(optionsFile)) { tmp.copyTo(optionsFile, true); tmp.delete() }
        } catch (e: Exception) { platform.log("Could not save options: $e") }
    }

    fun applyOptions() {
        input.swapAB = options.leftHanded
        audio.setVolumes(options.musicVolume, options.sfxVolume)
        session?.state?.options = options
        if (::screenCam.isInitialized) resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    /** Starts a session for a loaded or new game state. */
    fun startSession(state: GameState): GameContext {
        state.options = options
        val ctx = GameContext(dex!!, content!!, state)
        session = ctx
        return ctx
    }
}

/** Screens that can safely write an autosave when the app is backgrounded. */
interface AutosaveAware { fun autosave() }

/**
 * Which screen, if any, should autosave when the app is paused. The overworld is usually *under* a menu, dialog or
 * party screen when the player switches apps, so the search walks down from the top to the first [AutosaveAware]
 * screen instead of looking only at the top. It returns null (no save) when a screen above it is in the middle of a
 * transaction ([Screen.blocksAutosave]: a battle in progress, an evolution). The chosen screen still applies its own
 * rules (the overworld defers while a script runs, see AutosaveGate), so a half-applied script outcome is never saved.
 */
fun autosaveTarget(stack: List<Screen>): AutosaveAware? {
    for (i in stack.indices.reversed()) {
        val s = stack[i]
        if (s is AutosaveAware) return s
        if (s.blocksAutosave) return null
    }
    return null
}
