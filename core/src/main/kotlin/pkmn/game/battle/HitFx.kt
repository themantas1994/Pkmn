package pkmn.game.battle

import pkmn.engine.battle.BattleEvent

/** Presentation helpers for hit feedback (damage flash, damage number, crit flash). Reads events only; no rules. */
object HitFx {
    /**
     * The engine emits the HP change first and "A critical hit!" right after it, so the presentation looks ahead from
     * [from] (the next undelivered event) until the next HP change, move or turn boundary.
     */
    fun critFollows(events: List<BattleEvent>, from: Int): Boolean {
        for (i in from until events.size) {
            when (val e = events[i]) {
                is BattleEvent.Message -> if (e.text == "A critical hit!") return true
                is BattleEvent.Effectiveness, is BattleEvent.Animation -> {}
                else -> return false
            }
        }
        return false
    }

    /** Peak brightness of the damage flash: heavier hits flash harder, crits always at full strength. */
    fun flashStrength(damageFraction: Float, crit: Boolean) =
        if (crit) 1f else (0.45f + damageFraction * 1.5f).coerceIn(0.45f, 0.9f)
}
