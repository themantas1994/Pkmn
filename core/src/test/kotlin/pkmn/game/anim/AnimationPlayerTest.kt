package pkmn.game.anim

import kotlin.test.*

class AnimationPlayerTest {
    private fun recorder(log: MutableList<String>, name: String, d: Float, essential: Boolean = false) =
        Clip(name, d, essential, onStart = { log.add("$name:start") }, onEnd = { log.add("$name:end") })

    @Test
    fun `clips play in order on elapsed time`() {
        val log = ArrayList<String>()
        val p = AnimationPlayer()
        p.enqueue(recorder(log, "a", 0.5f)); p.enqueue(recorder(log, "b", 0.5f))
        p.update(0.25f)
        assertEquals(listOf("a:start"), log)
        assertTrue(p.busy)
        p.update(0.3f) // a ends, b starts with the remaining 0.05 s
        assertEquals(listOf("a:start", "a:end", "b:start"), log)
        p.update(0.5f)
        assertFalse(p.busy)
        assertEquals(2, p.completed)
    }

    @Test
    fun `frame rate does not change the result`() {
        fun run(step: Float): Float {
            var v = 0f
            val p = AnimationPlayer()
            p.enqueue(Clip("x", 1f, onUpdate = { v = it }))
            var t = 0f
            while (t < 0.5f - 1e-4f) { p.update(step); t += step }
            return v
        }
        assertEquals(run(1 / 30f), run(1 / 144f), 0.02f)
    }

    @Test
    fun `skipping applies every end state`() {
        var hp = 100f
        val p = AnimationPlayer()
        p.enqueue(Clip("drain", 2f, essential = true, onUpdate = { hp = 100 - 60 * it }, onEnd = { hp = 40f }))
        var visible = true
        p.enqueue(Clip("faint", 1f, onEnd = { visible = false }))
        p.update(0.1f)
        p.skipAll()
        assertEquals(40f, hp)
        assertFalse(visible)
        assertFalse(p.busy)
    }

    @Test
    fun `cosmetic clips are instant when animations are off but essential ones still play`() {
        val log = ArrayList<String>()
        val p = AnimationPlayer().apply { cosmetic = false }
        p.enqueue(recorder(log, "lunge", 0.2f))
        p.enqueue(recorder(log, "hp", 1f, essential = true))
        p.update(0.001f)
        assertEquals(listOf("lunge:start", "lunge:end", "hp:start"), log)
        assertTrue(p.busy)
        p.update(0.5f) // essential clips run at half length when animations are off
        assertFalse(p.busy)
    }

    @Test
    fun `speed setting shortens clips`() {
        val p = AnimationPlayer().apply { speed = 2f }
        p.enqueue(Clip("x", 1f))
        p.update(0.51f)
        assertFalse(p.busy)
    }
}
