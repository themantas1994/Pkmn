package pkmn.game.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MmlParserTest {
    private fun notes(song: Song, ch: Int) = song.tracks[ch].events.filter { it.type == Event.NOTE }

    @Test
    fun lengthsDotsTiesAndOctaves() {
        val s = MmlParser.parse("A o4 l8 c d4 e4. f%5 g4&16 > c < b+ c-1&2")
        val n = notes(s, 0)
        assertEquals(listOf(24, 48, 72, 5, 60, 24, 24, 288), n.map { it.ticks })
        assertEquals(listOf(60, 62, 64, 65, 67, 72, 72, 59), n.map { it.pitch })
    }

    @Test
    fun repeatsWithBreakAndNesting() {
        val s = MmlParser.parse("A l4 [c [d e]2 : f]3")
        val pitches = notes(s, 0).map { it.pitch }
        // c d e d e f | c d e d e f | c d e d e
        assertEquals(17, pitches.size)
        assertEquals(listOf(60, 62, 64, 62, 64, 65), pitches.take(6))
        assertEquals(64, pitches.last())
    }

    @Test
    fun loopPointAndLengths() {
        val s = MmlParser.parse(
            """
            #tempo 120
            A c4 $ d4 e4 f4
            C o3 c4 $ c2.
            D r4 $ K4 S4 K4
            """.trimIndent(), "t"
        )
        assertEquals(48, s.loopTick)
        assertEquals(192, s.lengthTicks)
        assertTrue(s.loops)
        assertEquals(2.0, s.totalSeconds, 1e-9)
        assertEquals(1.5, s.loopSeconds, 1e-9)
    }

    @Test
    fun tempoChangesAffectDuration() {
        val s = MmlParser.parse("#tempo 60\nA c4 t120 c4 c4")
        assertEquals(2.0, s.totalSeconds, 1e-9)
    }

    @Test
    fun instrumentsAndOverrides() {
        val s = MmlParser.parse(
            """
            #inst 1 duty=1 env=11,-3 rel=-2 vib=16,12,6 pan=l
            #wave 6 FFEEDDCCBBAA99887766554433221100
            A @1 c4 v7 w3 pr c4
            C @0 w6 o3 c2
            """.trimIndent()
        )
        val a = notes(s, 0)
        assertEquals(1, a[0].patch!!.duty); assertEquals(11, a[0].patch!!.volume); assertEquals(-3, a[0].patch!!.envStep)
        assertEquals(-1f, a[0].patch!!.pan); assertEquals(0.25, a[0].patch!!.vibDelay)
        assertEquals(3, a[1].patch!!.duty); assertEquals(7, a[1].patch!!.volume); assertEquals(1f, a[1].patch!!.pan)
        assertEquals(6, notes(s, 2)[0].patch!!.wave)
        assertEquals(0xF, s.waves[6][0].toInt())
    }

    @Test
    fun quantizeSlurAndPortamento() {
        val s = MmlParser.parse("A q4 c4 d4&e4 /g4")
        val n = notes(s, 0)
        assertEquals(24, n[0].gate)
        assertEquals(48, n[1].gate, "a note slurred into the next keeps its gate open")
        assertTrue(n[2].legato)
        assertEquals(48, n[2].gate, "followed by a portamento note")
        assertTrue(n[3].portamento)
    }

    @Test
    fun macrosAndDrums() {
        val s = MmlParser.parse(
            """
            #drum Q f=1000 v=10 e=-1
            #def beat K8 H8 S8 Q8
            D {beat} [{beat}]2
            """.trimIndent()
        )
        val d = notes(s, 3)
        assertEquals(12, d.size)
        assertEquals("KHSQKHSQKHSQ", d.joinToString("") { it.drum!!.name.toString() })
    }

    @Test
    fun barChecks() {
        MmlParser.parse("A c4 d4 e4 f4 | g1 |")
        val e = assertFailsWith<MmlException> { MmlParser.parse("A c4 d4 e4 |\nA f4") }
        assertEquals(1, e.line); assertEquals(12, e.col)
        assertTrue("144 ticks into bar 1" in e.detail, e.detail)
        MmlParser.parse("#time 3/4\nA c4 d4 e4 | f2. |")
    }

    @Test
    fun errorsCarryLineAndColumn() {
        fun fails(src: String, line: Int, col: Int, text: String) {
            val e = assertFailsWith<MmlException> { MmlParser.parse(src, "x") }
            assertEquals(line to col, e.line to e.col, e.message)
            assertTrue(text in e.detail, "'${e.detail}' should mention '$text'")
            assertTrue(e.message!!.startsWith("x:$line:$col:"))
        }
        fails("A c4 c7", 1, 7, "does not divide")
        fails("#tempo 120\nA c4\nA c4 x4", 3, 6, "unexpected character")
        fails("A [c4 d4", 1, 9, "unclosed '['")
        fails("A c4]", 1, 5, "without matching")
        fails("A @3 c4", 1, 3, "undefined instrument")
        fails("D c4", 1, 3, "not allowed on the noise channel")
        fails("A K4", 1, 3, "only valid on channel D")
        fails("D Y4", 1, 3, "unknown drum")
        fails("#inst 1 duty=7", 1, 7, "duty must be 0..3")
        fails("#bogus 1", 1, 1, "unknown directive")
        fails("A c4\nB c4 c4", 1, 1, "different lengths")
        fails("A $ c4\nB c4", 1, 1, "loop points")
        fails("A [$ c4]2", 1, 4, "inside a repeat")
        fails("A {nope}", 1, 3, "undefined macro")
        fails("Q c4", 1, 1, "channel line")
        fails("A o9", 1, 3, "octave")
        fails("#key H major", 1, 6, "unknown key")
    }

    @Test
    fun keysParse() {
        assertEquals(setOf(6, 8, 9, 11, 1, 2, 4, 5), ScaleUtil.parseKey("F# minor")!!.second)
        assertEquals(setOf(10, 0, 2, 3, 5, 7, 9), ScaleUtil.parseKey("Bb major")!!.second)
    }
}
