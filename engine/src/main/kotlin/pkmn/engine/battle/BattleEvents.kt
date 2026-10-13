package pkmn.engine.battle

/**
 * Output of the battle engine. The UI plays these in order (text, HP bar animation, sprite changes).
 * Events reference Pokémon by side index and team index so they stay valid after the battle ends.
 */
sealed class BattleEvent {
    /** Text to display in the dialogue box. */
    data class Message(val text: String) : BattleEvent()
    data class SwitchIn(val side: Int, val teamIndex: Int, val speciesId: String, val hp: Int, val maxHp: Int, val text: String) : BattleEvent()
    data class SwitchOut(val side: Int, val teamIndex: Int, val text: String) : BattleEvent()
    data class MoveUsed(val side: Int, val moveId: String, val moveType: String, val category: String, val text: String, val targetSide: Int) : BattleEvent()
    data class HpChange(val side: Int, val teamIndex: Int, val hp: Int, val maxHp: Int, val delta: Int, val cause: String) : BattleEvent()
    data class Faint(val side: Int, val teamIndex: Int, val text: String) : BattleEvent()
    data class StatusChange(val side: Int, val teamIndex: Int, val status: String?, val text: String) : BattleEvent()
    data class StatChange(val side: Int, val stat: String, val amount: Int, val text: String) : BattleEvent()
    data class FormeChange(val side: Int, val teamIndex: Int, val speciesId: String, val kind: String, val text: String) : BattleEvent()
    data class FieldChange(val kind: String, val id: String?, val text: String) : BattleEvent()
    data class Effectiveness(val multiplier: Double) : BattleEvent()
    data class Animation(val side: Int, val id: String) : BattleEvent()
    data class CatchAttempt(val ball: String, val shakes: Int, val caught: Boolean, val critical: Boolean) : BattleEvent()
    data class ExpGain(val teamIndex: Int, val amount: Int, val text: String) : BattleEvent()
    data class LevelUp(val teamIndex: Int, val level: Int, val text: String) : BattleEvent()
    data class LearnMovePrompt(val teamIndex: Int, val moveId: String) : BattleEvent()
    data class TurnStart(val turn: Int) : BattleEvent()
    data class End(val outcome: BattleOutcome) : BattleEvent()
}
