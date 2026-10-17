package pkmn.game

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
                if (keycode == Input.Keys.BACK) { stack.lastOrNull()?.onBack(); return true }
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
    }

    // ------------------------------------------------------------------ screen stack

    fun push(s: Screen) { pending.add { stack.add(s); s.onEnter() } }
    fun pop(s: Screen) { pending.add { if (stack.remove(s)) s.onExit() } }
    fun replace(old: Screen, new: Screen) { pending.add { val i = stack.indexOf(old); if (i >= 0) { stack[i] = new; old.onExit(); new.onEnter() } else { stack.add(new); new.onEnter() } } }
    fun resetTo(s: Screen) { pending.add { stack.toList().forEach { it.onExit() }; stack.clear(); stack.add(s); s.onEnter() } }
    fun top(): Screen? = stack.lastOrNull()
    fun screens(): List<Screen> = stack

    private fun flushPending() { while (pending.isNotEmpty()) { val p = pending.toList(); pending.clear(); p.forEach { it() } } }

    // ------------------------------------------------------------------ loop

    /** CPU time (ns) of the last [FRAME_SAMPLES] frames' update + draw submission, for the perf overlay/automation. */
    val frameNanos = LongArray(FRAME_SAMPLES)

    override fun render() {
        val frameStart = System.nanoTime()
        val dt = minOf(Gdx.graphics.deltaTime, 0.05f)
        timeMs += (dt * 1000).toLong()
        session?.state?.let { it.playTimeMs += (dt * 1000).toLong() }
        flushPending()
        frameHook?.invoke(this)
        input.update(dt)
        stack.lastOrNull()?.update(dt, input)
        flushPending()

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

    private fun drawTouchControls() {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapes.projectionMatrix = screenCam.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        val base = Color(1f, 1f, 1f, 0.16f)
        val active = Color(1f, 1f, 1f, 0.38f)
        fun col(b: Button) = if (input.pressed(b)) active else base
        val d = touch.dpad
        val t = d.width / 3
        shapes.color = col(Button.UP); shapes.rect(d.x + t, d.y + 2 * t, t, t)
        shapes.color = col(Button.DOWN); shapes.rect(d.x + t, d.y, t, t)
        shapes.color = col(Button.LEFT); shapes.rect(d.x, d.y + t, t, t)
        shapes.color = col(Button.RIGHT); shapes.rect(d.x + 2 * t, d.y + t, t, t)
        shapes.color = base; shapes.rect(d.x + t, d.y + t, t, t)
        shapes.color = col(Button.A); shapes.circle(touch.a.x + touch.a.width / 2, touch.a.y + touch.a.height / 2, touch.a.width / 2)
        shapes.color = col(Button.B); shapes.circle(touch.b.x + touch.b.width / 2, touch.b.y + touch.b.height / 2, touch.b.width / 2)
        shapes.color = col(Button.START); shapes.rect(touch.start.x, touch.start.y, touch.start.width, touch.start.height)
        shapes.color = col(Button.SELECT); shapes.rect(touch.select.x, touch.select.y, touch.select.width, touch.select.height)
        shapes.end()
        // Labels
        batch.begin()
        val f = assets.font8
        val s = (touch.a.width / 24f).coerceAtLeast(1f)
        fun label(txt: String, rect: Rectangle) {
            val m = batch.transformMatrix.cpy()
            // Long labels (START/SELECT) shrink to fit their button.
            val ls = minOf(s, rect.width * 0.85f / f.width(txt))
            batch.transformMatrix = m.cpy().translate(rect.x + rect.width / 2 - f.width(txt) * ls / 2, rect.y + rect.height / 2 + 4 * ls, 0f).scale(ls, ls, 1f)
            // draw with y-up: reuse glyph regions directly
            var cx = 0f
            for (ch in txt) { glyphOf(ch)?.let { batch.setColor(1f, 1f, 1f, 0.7f); batch.draw(it, cx, -8f) }; cx += 8f }
            batch.setColor(Color.WHITE)
            batch.transformMatrix = m
        }
        label("A", touch.a); label("B", touch.b); label("START", touch.start); label("SELECT", touch.select)
        batch.end()
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
        // Android may kill the process while backgrounded: autosave when it is safe to do so.
        stack.lastOrNull()?.let { (it as? AutosaveAware)?.autosave() }
        saveOptions()
    }

    override fun resume() { audio.resume() }

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
        if (optionsFile.exists()) kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Options.serializer(), optionsFile.readText()) else Options()
    } catch (e: Exception) { Options() }

    fun saveOptions() {
        try {
            platform.saveDir.mkdirs()
            val tmp = java.io.File(platform.saveDir, "options.json.tmp")
            tmp.writeText(kotlinx.serialization.json.Json.encodeToString(Options.serializer(), options))
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
