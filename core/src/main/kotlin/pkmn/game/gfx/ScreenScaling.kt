package pkmn.game.gfx

import com.badlogic.gdx.graphics.glutils.ShaderProgram

/** How the logical framebuffer is scaled to the window. */
enum class ScalingMode {
    /** Whole-number scale, nearest filtering (pixel-perfect; used by the screenshot harness). */
    INTEGER,
    /** Fractional scale that fills the window, nearest texels with a one-screen-pixel blend at texel edges. */
    SHARP;

    companion object {
        /** "auto" (and anything unknown) is SHARP on mobile and INTEGER elsewhere. */
        fun resolve(option: String, mobile: Boolean): ScalingMode = when (option.lowercase()) {
            "integer" -> INTEGER
            "sharp" -> SHARP
            else -> if (mobile) SHARP else INTEGER
        }
    }
}

object ScreenScaling {
    /** Scale factor of the logical screen inside a [width] x [availH] area. */
    fun scale(mode: ScalingMode, width: Int, availH: Int, logicalW: Int, logicalH: Int): Float {
        val fit = minOf(width.toFloat() / logicalW, availH.toFloat() / logicalH)
        return when (mode) {
            ScalingMode.INTEGER -> maxOf(1, minOf(width / logicalW, availH / logicalH)).toFloat()
            ScalingMode.SHARP -> maxOf(1f, fit)
        }
    }

    private const val VERT = """
attribute vec4 a_position;
attribute vec4 a_color;
attribute vec2 a_texCoord0;
uniform mat4 u_projTrans;
varying vec4 v_color;
varying vec2 v_texCoords;
void main() {
    v_color = a_color;
    v_color.a = v_color.a * (255.0 / 254.0);
    v_texCoords = a_texCoord0;
    gl_Position = u_projTrans * a_position;
}
"""

    /**
     * Sharp-bilinear: the sample position is snapped so that each source texel stays flat and the linear filter only
     * blends across a band one screen pixel wide at texel edges (band = 1 / u_scale texels). With an integer scale the
     * band never contains a pixel centre, so the output equals nearest filtering. GLES 2.0 compatible.
     */
    private const val FRAG = """
#ifdef GL_ES
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#endif
varying vec4 v_color;
varying vec2 v_texCoords;
uniform sampler2D u_texture;
uniform vec2 u_texSize;
uniform float u_scale;
void main() {
    vec2 t = v_texCoords * u_texSize - 0.5;
    vec2 i = floor(t);
    vec2 f = clamp((t - i - 0.5) * u_scale + 0.5, 0.0, 1.0);
    gl_FragColor = v_color * texture2D(u_texture, (i + f + 0.5) / u_texSize);
}
"""

    /** Compiles the shader; null when the driver rejects it (caller falls back to INTEGER). */
    fun createShader(): ShaderProgram? {
        ShaderProgram.pedantic = false
        val s = ShaderProgram(VERT, FRAG)
        return if (s.isCompiled) s else { s.dispose(); null }
    }
}
