package pkmn.game.battle

import pkmn.engine.battle.*

/**
 * The battle screen's state machine: which decision the player faces once the engine's events have been presented.
 * Pure logic (no rendering) so it can be tested headlessly.
 *
 * ```
 * INTRO -> PRESENTING --(events drained, animations idle)--> settle()
 *   settle(): outcome decided            -> ENDING -> DONE (exactly once)
 *             player must replace        -> FORCED_SWITCH (trainer) | NEXT_MON_PROMPT (wild: "Use next Pokémon?")
 *             foe must replace, Shift on -> SHIFT_PROMPT -> (YES) SHIFT_PARTY -> PRESENTING
 *                                                      -> (NO)  PRESENTING (foe sends out)
 *             foe must replace, Set      -> PRESENTING (foe sends out)
 *             move request               -> CHOOSE_ACTION (or auto-submit a locked move)
 * ```
 * The engine already awarded experience in the events being presented, so nothing here can award it twice.
 */
class BattleFlow(val battle: Battle, private val ai: BattleAI = BattleAI()) {
    enum class Phase { INTRO, PRESENTING, CHOOSE_ACTION, FORCED_SWITCH, NEXT_MON_PROMPT, SHIFT_PROMPT, SHIFT_PARTY, ENDING, DONE }

    var phase: Phase = Phase.INTRO
        private set
    /** Index of the next engine event to present. */
    var cursor: Int = 0
        private set
    /** How many times [settle] found nothing to do in a row (guards against a stuck state). */
    private var idleSettles = 0
    private var endReported = false
    /** A failed "No" (run) at the "Use next Pokémon?" prompt: the player must now choose. */
    private var fleeFailed = false

    fun hasPendingEvents() = cursor < battle.events.size
    fun nextEvent(): BattleEvent? = if (cursor < battle.events.size) battle.events[cursor++] else null

    fun startPresenting() { if (phase == Phase.INTRO) phase = Phase.PRESENTING }

    /** The foe's next Pokémon's display name while a Shift prompt is pending. */
    fun upcomingFoeName(): String? = battle.plannedReplacement(battle.opponent, ai)?.let { battle.nameOnSendOut(battle.opponent, it) }

    /**
     * Called when every event has been presented. Decides the next phase; may advance the engine (AI replacements),
     * in which case new events appear and the phase stays [Phase.PRESENTING].
     */
    fun settle(): Phase {
        if (phase != Phase.PRESENTING) return phase
        if (hasPendingEvents()) return phase
        if (battle.outcome != BattleOutcome.ONGOING) { phase = Phase.ENDING; return phase }
        val before = battle.events.size
        val req = battle.request(battle.player)
        phase = when {
            req is Request.SwitchIn -> if (battle.opponent.isWild && battle.rules.canRun && !fleeFailed) Phase.NEXT_MON_PROMPT else Phase.FORCED_SWITCH
            battle.needsReplacement(battle.opponent) && battle.shiftAvailable() -> { battle.plannedReplacement(battle.opponent, ai); Phase.SHIFT_PROMPT }
            req is Request.Move && req.lockedMove != null -> { submit(Choice.UseMove(0)); Phase.PRESENTING }
            req is Request.Move -> Phase.CHOOSE_ACTION
            else -> { battle.advance(ai); Phase.PRESENTING }
        }
        if (phase == Phase.PRESENTING && battle.events.size == before && battle.outcome == BattleOutcome.ONGOING) {
            // Nothing happened: never spin forever waiting for a transition that cannot come.
            if (++idleSettles > 3) error("Battle flow stuck: request=$req pendingFoe=${battle.needsReplacement(battle.opponent)}")
        } else idleSettles = 0
        return phase
    }

    /** A move/item/run/switch decision from the action menus. Returns an error message if rejected. */
    fun submit(c: Choice): String? {
        val err = battle.choose(battle.player, c)
        if (err != null) return err
        battle.advance(ai)
        phase = Phase.PRESENTING
        return null
    }

    /** Back to presenting after a rejected choice message. */
    fun resume() { if (phase == Phase.CHOOSE_ACTION) phase = Phase.PRESENTING }

    // ---------------------------------------------------------------- Shift prompt

    fun answerShift(yes: Boolean) {
        check(phase == Phase.SHIFT_PROMPT) { "not at the shift prompt" }
        if (yes) { phase = Phase.SHIFT_PARTY; return }
        battle.advance(ai)
        phase = Phase.PRESENTING
    }

    /** Party choice after "YES": null (cancel) returns to the prompt. */
    fun shiftPick(teamIndex: Int?): String? {
        check(phase == Phase.SHIFT_PARTY) { "not choosing a shift switch" }
        if (teamIndex == null) { phase = Phase.SHIFT_PROMPT; return null }
        val err = battle.chooseShift(teamIndex)
        if (err != null) return err
        battle.advance(ai)
        phase = Phase.PRESENTING
        return null
    }

    // ---------------------------------------------------------------- forced replacement

    fun forcedPick(teamIndex: Int): String? {
        check(phase == Phase.FORCED_SWITCH) { "no forced switch pending" }
        val err = battle.choose(battle.player, Choice.Switch(teamIndex))
        if (err != null) return err
        fleeFailed = false
        battle.advance(ai)
        phase = Phase.PRESENTING
        return null
    }

    /** Wild battles: "Use next Pokémon?" YES opens the party, NO tries to run. */
    fun answerNextMon(useNext: Boolean) {
        check(phase == Phase.NEXT_MON_PROMPT)
        if (useNext) { phase = Phase.FORCED_SWITCH; return }
        // Escaped: the End event is presented. Failed: "Can't escape!", then the party must be chosen.
        if (!battle.fleeInsteadOfReplacing()) fleeFailed = true
        phase = Phase.PRESENTING
    }

    // ---------------------------------------------------------------- ending

    /** Returns the outcome the first time it is called after the battle ended, and null afterwards. */
    fun takeOutcome(): BattleOutcome? {
        if (phase != Phase.ENDING || endReported) return null
        endReported = true
        phase = Phase.DONE
        return battle.outcome
    }

    /** Fast-forwards: marks every event as presented (used when the screen is skipped or cancelled). */
    fun skipEvents() { cursor = battle.events.size }
}
