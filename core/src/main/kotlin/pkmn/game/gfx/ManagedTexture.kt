package pkmn.game.gfx

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.glutils.PixmapTextureData

/**
 * A texture built from a generated [Pixmap] that survives an OpenGL context loss (Android can drop the context when
 * the app is backgrounded). `Texture(Pixmap)` is *unmanaged* and its pixmap is disposed after the upload, so after a
 * context loss it cannot be re-uploaded. A managed texture re-uploads from the pixmap, which must therefore stay
 * alive: it is appended to [retained] and the owner disposes it together with the texture.
 * Only small generated art goes through here (solid colours, 16x16 tiles, placeholder sprites); file textures are
 * already managed by LibGDX.
 */
fun managedTexture(pixmap: Pixmap, retained: MutableList<Pixmap>): Texture {
    retained.add(pixmap)
    return Texture(PixmapTextureData(pixmap, null, false, false, true))
}
