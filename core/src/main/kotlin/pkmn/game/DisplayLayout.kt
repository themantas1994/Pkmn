package pkmn.game

import pkmn.game.gfx.Renderer
import kotlin.math.ceil

/** Where the 256x224 game image lands on screen. [prescale] > 0 means FILL: nearest-neighbour by that factor, then bilinear to [w] x [h]. */
class DisplayLayout(val x: Float, val y: Float, val w: Float, val h: Float, val prescale: Int) {
    companion object {
        const val AUTO_FILL_BELOW = 0.85f
        const val TOUCH_HEIGHT_SHARE = 0.58f

        fun compute(width: Int, height: Int, showTouch: Boolean, mode: String, mobile: Boolean): DisplayLayout {
            val portrait = height > width
            val stacked = portrait && showTouch
            val availH = if (stacked) (height * TOUCH_HEIGHT_SHARE).toInt() else height
            val intScale = maxOf(1, minOf(width / Renderer.W, availH / Renderer.H))
            val fill = when (mode) {
                "FILL" -> true
                "CRISP" -> false
                else -> mobile && Renderer.W * intScale < AUTO_FILL_BELOW * width
            }
            val fit = minOf(width.toFloat() / Renderer.W, availH.toFloat() / Renderer.H)
            val scale = if (fill && fit > 1f) fit else intScale.toFloat()
            val gw = Renderer.W * scale; val gh = Renderer.H * scale
            val gx = (width - gw) / 2f
            val gy = if (stacked) height - gh - height * 0.03f else (height - gh) / 2f
            val pre = if (fill && scale != intScale.toFloat()) ceil(scale).toInt().coerceAtLeast(1) else 0
            return DisplayLayout(gx, gy, gw, gh, pre)
        }
    }
}
