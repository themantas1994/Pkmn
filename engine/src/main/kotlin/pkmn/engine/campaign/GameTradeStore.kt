package pkmn.engine.campaign

import pkmn.engine.link.TradeStore
import pkmn.engine.mon.Pokemon
import pkmn.engine.save.GameState
import pkmn.engine.save.PendingTrade
import pkmn.engine.save.SaveResult
import pkmn.engine.save.TradeJournal
import pkmn.engine.save.TradeJournalState

/**
 * The [TradeStore] of the running game: the journal lives in [GameContext.state] and every change is written with
 * [save] (normally `SaveManager.save`) before the trade goes on. A failed save puts the in-memory state back as it was,
 * so memory never runs ahead of the save file. [lastTrade] is the swap applied by the last [commit].
 */
class GameTradeStore(private val ctx: GameContext, private val save: (GameState) -> SaveResult) : TradeStore {
    var lastTrade: LinkTradeResult? = null
        private set

    override val journal: PendingTrade? get() = ctx.state.pendingTrade

    override val party: List<Pokemon> get() = ctx.state.party

    override val trainerId: Int get() = ctx.state.trainerId

    override fun refusal(uid: Long): String? {
        val slot = ctx.state.party.indexOfFirst { it.uid == uid }
        return if (slot < 0) "That POKéMON is not in your party." else ctx.linkTradeRefusal(slot)
    }

    override fun prepare(journal: PendingTrade) {
        val slot = ctx.state.party.indexOfFirst { it.uid == journal.givenUid }
        transact { ctx.state.pendingTrade = journal.copy(state = TradeJournalState.PREPARED, givenSlot = slot) }
    }

    override fun commit(journal: PendingTrade, resume: Boolean) {
        transact {
            lastTrade = ctx.performLinkTrade(journal, requireAble = !resume)
            ctx.state.pendingTrade = journal.copy(state = TradeJournalState.COMMITTED, givenSlot = lastTrade!!.slot)
        }
    }

    override fun clear() { transact { ctx.state.pendingTrade = null } }

    /**
     * The manual way out (LINK menu): removes the open journal and saves, after the player saw
     * [GameContext.linkTradeAbandonWarning]. The party is left as it is. Returns what happened, or null when no journal
     * was open. Throws when the save fails (the journal is then still open, in memory and on disk).
     */
    fun abandon(): String? {
        val j = ctx.state.pendingTrade ?: return null
        clear()
        return when (j.state) {
            TradeJournalState.PREPARED -> "The trade with ${j.partnerName} was cancelled. You kept ${TradeJournal.givenName(j)}."
            TradeJournalState.COMMITTED -> "The trade with ${j.partnerName} was ended. You keep ${j.received.displayName(ctx.dex)}."
        }
    }

    /**
     * Runs [change] and saves; on any failure restores what [change] may touch and rethrows. A save that fails after
     * its temporary copy was complete can still be picked up by the next load (SaveManager's crash recovery); the file
     * then holds the whole new state while memory holds the old one. Both are complete states (trade applied or not),
     * and the next save from memory makes them agree again.
     */
    private fun transact(change: () -> Unit) {
        val s = ctx.state
        // performLinkTrade only moves Pokémon in and out of the party list and changes its own copy of the received one.
        val party = s.party.toList()
        val sets = listOf(s.seen, s.caught, s.formsSeen, s.formsCaught, s.shiniesCaught).map { it.toSet() }
        val nicknames = s.pendingNicknames.toList()
        val journal = s.pendingTrade
        val trades = s.linkRecord.trades
        val trade = lastTrade
        try {
            change()
            when (val r = save(s)) {
                SaveResult.Saved -> {}
                is SaveResult.Refused -> throw IllegalStateException(r.reason)
            }
        } catch (e: Exception) {
            s.party.clear(); s.party.addAll(party)
            listOf(s.seen, s.caught, s.formsSeen, s.formsCaught, s.shiniesCaught).zip(sets).forEach { (cur, old) -> cur.clear(); cur.addAll(old) }
            s.pendingNicknames.clear(); s.pendingNicknames.addAll(nicknames)
            s.pendingTrade = journal
            s.linkRecord.trades = trades
            lastTrade = trade
            throw e
        }
    }
}
