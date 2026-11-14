package pkmn.engine.save

/** What happens to an unfinished link trade ([GameState.pendingTrade]) when the trade session that wrote it is gone. */
object TradeJournal {
    /**
     * Resolves the journal when a save is loaded, or before a new trade starts: the session that wrote it no longer
     * exists, so it can never finish. A PREPARED journal is cancelled (the party was never changed; the player keeps
     * their Pokémon) and the returned sentence tells the player. A COMMITTED journal is cleared silently: the swap is
     * already in the save. Returns null when there is nothing to tell.
     */
    fun resolveOnLoad(state: GameState): String? {
        val j = state.pendingTrade ?: return null
        state.pendingTrade = null
        return when (j.state) {
            TradeJournalState.COMMITTED -> null
            TradeJournalState.PREPARED -> {
                val kept = j.givenName.ifEmpty { j.givenSpecies.uppercase() }
                "The link trade with ${j.partnerName} was not finished. You kept $kept."
            }
        }
    }
}
