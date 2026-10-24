package pkmn.game.battle

import com.badlogic.gdx.graphics.Color
import pkmn.game.gfx.Palette
import java.io.File
import kotlin.test.*

class MoveFxTest {
    private fun hex(c: Color) = "%02X%02X%02X".format((c.r * 255 + 0.5f).toInt(), (c.g * 255 + 0.5f).toInt(), (c.b * 255 + 0.5f).toInt())

    @Test
    fun `ramp matches the style sheet values for fire and water`() {
        val fire = TypeRamp.of(Color.valueOf("f08030"))
        assertEquals(listOf("F8D8C8", "F0B080", "F08030", "784018"), listOf(fire.highlight, fire.light, fire.main, fire.dark).map(::hex))
        val water = TypeRamp.of(Color.valueOf("6890f0"))
        assertEquals(listOf("D0E0F8", "A0B8F0", "6890F0", "304878"), listOf(water.highlight, water.light, water.main, water.dark).map(::hex))
    }

    @Test
    fun `every type ramps from light to dark and keeps its own main colour`() {
        for ((type, main) in Palette.TYPE) {
            val r = MoveFx("impact", main, 0f, 0f, 0f, 0f).ramp
            fun lum(c: Color) = 0.3f * c.r + 0.59f * c.g + 0.11f * c.b
            assertTrue(lum(r.highlight) > lum(r.light) && lum(r.light) >= lum(r.main) && lum(r.main) > lum(r.dark), "ramp order for $type")
            assertSame(main, r.main)
        }
    }

    @Test
    fun `shipped battle json maps every type and category to a known style`() {
        val data = BattleAnimData.parse(File("../assets/anim/battle.json").readText())
        for (type in Palette.TYPE.keys) for (cat in listOf("Physical", "Special", "Status")) {
            assertContains(BattleAnimData.STYLES, data.styleFor("nomove", type, cat, false), "$type/$cat")
        }
    }
}
