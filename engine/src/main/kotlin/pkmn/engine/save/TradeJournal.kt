package pkmn.engine.save

/**
 * The rules for an unfinished link trade ([GameState.pendingTrade]) outside a running trade session.
 *
 * A journal is never resolved by loading the save or by time passing: a restart must not decide the outcome, because
 * this device cannot know whether the partner's device applied the swap. It is resolved only by a link with the same
 * partner (`TradeTransaction`'s SYNC step) or by the player in the LINK menu (the manual way out, with a warning).
 */
object TradeJournal {
    /**
     * What the player is told when a save with an open journal is loaded (the journal stays). Null when there is none.
     */
    fun loadNotice(state: GameState): String? {
        val j = state.pendingTrade ?: return null
        if (j.partnerTrainerId == null) return "A link trade with ${j.partnerName} from an earlier version of the game is unfinished. End it from the LINK menu (UNFINISHED TRADE)."
        return when (j.state) {
            TradeJournalState.PREPARED -> "The link trade with ${j.partnerName} was interrupted. Link with ${j.partnerName} again to finish it."
            TradeJournalState.COMMITTED -> "Your link trade with ${j.partnerName} is done on your side. Link with ${j.partnerName} again so their game can finish it too."
        }
    }

    /**
     * Whether a linked player named [name] with trainer id [trainerId] is the partner of [j]: both must match. A journal
     * written before trainer ids were stored (link play Phase 4, never released) matches nobody, because a name alone
     * could be someone else's: only the manual way out ends it.
     */
    fun isPartner(j: PendingTrade, name: String, trainerId: Int?): Boolean =
        j.partnerName == name && j.partnerTrainerId != null && j.partnerTrainerId == trainerId

    /** The name of the given Pokémon for messages. */
    fun givenName(j: PendingTrade): String = j.givenName.ifEmpty { j.givenSpecies.uppercase() }
}
