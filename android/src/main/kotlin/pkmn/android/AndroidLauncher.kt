package pkmn.android

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import pkmn.game.PkmnGame
import pkmn.game.Platform
import pkmn.game.SynthAudio
import java.io.File

class AndroidLauncher : AndroidApplication() {
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
            override val importedAssetsDir: File? = File(filesDir, "imported").takeIf { it.isDirectory }
            override fun vibrate(ms: Int) {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(ms.toLong())
            }
            override fun isUpsideDown(): Boolean = com.badlogic.gdx.Gdx.input.accelerometerY < -7f
            override fun log(msg: String) { android.util.Log.i("PkmnCrystalline", msg) }
            override fun exit() { moveTaskToBack(true) }
        }
        initialize(PkmnGame(platform) { SynthAudio() }, config)
    }
}
