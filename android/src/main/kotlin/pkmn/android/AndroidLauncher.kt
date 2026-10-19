package pkmn.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import pkmn.engine.util.SpriteArchive
import pkmn.game.BundledAssetSource
import pkmn.game.BundledAssets
import pkmn.game.PkmnGame
import pkmn.game.Platform
import pkmn.game.SynthAudio
import java.io.File

class AndroidLauncher : AndroidApplication() {
    private var importDone: ((String) -> Unit)? = null
    /** Set when the background unpack of bundled sprites has finished (successfully or not); the game waits for it. */
    @Volatile private var assetsReady = false
    @Volatile private var destroyed = false
    private val importedDir get() = File(filesDir, "imported")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useAccelerometer = true
            useCompass = false
            useImmersiveMode = true
            numSamples = 0
        }
        val platform = object : Platform {
            override val name = "android"
            override val isMobile = true
            override val saveDir: File = File(filesDir, "saves").apply { mkdirs() }
            /** Official sprites imported by the player (see docs/ASSET_IMPORT.md) live in app storage. */
            override val importedAssetsDir: File? get() = importedDir.takeIf { it.isDirectory }
            override val assetsReady: Boolean get() = this@AndroidLauncher.assetsReady
            override val canImportAssets = true
            override fun importAssets(done: (message: String) -> Unit) {
                importDone = done
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed"))
                }
                runOnUiThread { startActivityForResult(intent, REQUEST_IMPORT) }
            }
            override fun vibrate(ms: Int) {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(ms.toLong())
            }
            override fun isUpsideDown(): Boolean = Gdx.input.accelerometerY < -7f
            override fun log(msg: String) { android.util.Log.i("PkmnCrystalline", msg) }
            override fun exit() { moveTaskToBack(true) }
        }
        initialize(PkmnGame(platform) { SynthAudio() }, config)
        // The copy can take seconds for a personal build: never on the UI thread. The game is already running but its
        // loading screen waits for assetsReady, so nothing reads the sprite directory before it is complete.
        unpackBundledSprites()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    @Deprecated("AndroidApplication is not a ComponentActivity, so the activity-result API is unavailable")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT) return
        val done = importDone ?: return
        importDone = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        Thread {
            val msg = try {
                val r = contentResolver.openInputStream(uri)?.use { SpriteArchive.extract(it, importedDir) }
                when {
                    r == null -> "The file could not be opened."
                    r.extracted == 0 -> "No sprites were found. The archive needs a sprites/ folder (see the import guide)."
                    else -> "Imported ${r.extracted} sprite files."
                }
            } catch (e: Exception) {
                log("PkmnCrystalline", "sprite import failed", e)
                "The import failed: ${e.message?.take(40)}"
            }
            Gdx.app.postRunnable { done(msg) }
        }.start()
    }

    /**
     * Personal builds made with -Ppkmn.bundleLocalAssets=true carry `assets/imported/`; copy it to app storage once
     * per app version on a background thread (see [BundledAssets] for the failure and interruption rules).
     * A failure is logged and the game starts with what is already on disk (placeholders at worst); the next start retries.
     */
    private fun unpackBundledSprites() {
        Thread({
            try {
                // Keyed on the install time too: a rebuilt personal APK keeps versionCode but must refresh the unpacked files.
                val info = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION") val version = "${info.versionCode}-${info.lastUpdateTime}"
                val source = object : BundledAssetSource {
                    override fun list(path: String) = (assets.list(path) ?: emptyArray()).toList()
                    override fun open(path: String) = assets.open(path)
                }
                val outcome = BundledAssets.unpack(source, filesDir, version, cancelled = { destroyed },
                    onRetry = { n, e -> android.util.Log.w("PkmnCrystalline", "unpacking bundled sprites failed (attempt $n), retrying: $e") })
                if (outcome is BundledAssets.Outcome.Failed)
                    android.util.Log.e("PkmnCrystalline", "could not unpack bundled sprites after ${outcome.attempts} attempts; will retry on the next start", outcome.error)
            } catch (e: Throwable) {
                android.util.Log.e("PkmnCrystalline", "unpacking bundled sprites crashed", e)
            } finally {
                assetsReady = true
            }
        }, "unpack-bundled-assets").apply { isDaemon = true }.start()
    }

    private companion object { const val REQUEST_IMPORT = 4201 }
}
