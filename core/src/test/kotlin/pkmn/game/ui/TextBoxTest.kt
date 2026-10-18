package pkmn.game.ui

import kotlin.test.*

class TextBoxTest {
    /**
     * Callers write `TextBox(game, "...") { next() }`; Kotlin binds that trailing lambda to the LAST constructor
     * parameter. It must be the `() -> Unit` onDone (run once when dismissed), never the per-frame `decor` drawer,
     * which would run the callback on every draw (the broken intro/gender menu).
     */
    @Test
    fun `trailing lambda binds to onDone, not decor`() {
        val ctor = TextBox::class.java.constructors.single { c -> c.parameterTypes.none { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" } }
        assertEquals(kotlin.jvm.functions.Function0::class.java, ctor.parameterTypes.last())
    }
}
