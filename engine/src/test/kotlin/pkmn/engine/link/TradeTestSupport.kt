package pkmn.engine.link

import pkmn.engine.TestSupport
import pkmn.engine.campaign.GameContext
import pkmn.engine.campaign.GameTradeStore
import pkmn.engine.data.DirectoryContentSource
import pkmn.engine.mon.Pokemon
import pkmn.engine.save.LoadResult
import pkmn.engine.save.SaveManager
import pkmn.engine.save.SaveResult
import pkmn.engine.campaign.WorldContent
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Two players with real saves on disk, and a runner that plays a trade between them over a [LoopbackLink]. */
object TradeTestSupport {
    val dex get() = TestSupport.dex
    val content by lazy { WorldContent(DirectoryContentSource(File(System.getProperty("pkmn.assets")))) }

    /** One player: a new game with [species] in the party (level 20, registered), saved to its own directory. */
    class Device(val name: String, seed: Long, species: List<String>, level: Int = 20, val dir: File = Files.createTempDirectory("trade-$name").toFile()) {
        val ctx = GameContext(dex, content, GameContext.newGame(content, name, "m", "RIVAL", seed))
        /** Number of save attempts that fail before saves work again (to test a failed save). */
        var failSaves = 0
        /** When positive, counts saves down and fails the one that reaches 0 (2 = the next save works, the one after fails). */
        var failOnSave = 0
        val store = GameTradeStore(ctx) { s ->
            if (failSaves > 0) { failSaves--; throw java.io.IOException("disk full") }
            if (failOnSave > 0 && --failOnSave == 0) throw java.io.IOException("disk full")
            SaveManager(dir, dex).save(s)
        }

        init {
            for (sp in species) ctx.acquire(ctx.factory.create(sp, level, ctx.rng), offerNickname = false)
            assertEquals(SaveResult.Saved, SaveManager(dir, dex).save(ctx.state))
        }

        val partyUids get() = ctx.state.party.map { it.uid }
        fun load(): LoadResult.Ok = assertIs<LoadResult.Ok>(SaveManager(dir, dex).load())
    }

    class Clock { var t = 0L }

    /** What a runner did, per device. */
    class Run(val host: TradeTransaction, val guest: TradeTransaction)

    /**
     * Plays a trade: each device offers its party member [hostOffer] / [guestOffer] and answers [hostAccept] /
     * [guestAccept]. With [offer] false the devices only sync (the reconnect check) and then back out. Time advances
     * one second per round, so a silent link times out after [TradeTimeouts.voidMs].
     */
    fun run(
        link: LoopbackLink, clock: Clock, host: Device, guest: Device, hostOffer: Int = 0, guestOffer: Int = 0,
        hostAccept: Boolean = true, guestAccept: Boolean = true, offer: Boolean = true, rounds: Int = 200,
        hostTransport: LinkTransport = link.a, guestTransport: LinkTransport = link.b,
    ): Run {
        val toOffer = mapOf(host to host.ctx.state.party.getOrNull(hostOffer), guest to guest.ctx.state.party.getOrNull(guestOffer))
        val h = TradeTransaction(LinkRole.HOST, hostTransport, dex, host.store, host.name, guest.name, nonceSource = { "a1" })
        val g = TradeTransaction(LinkRole.GUEST, guestTransport, dex, guest.store, guest.name, host.name, nonceSource = { "b2" })
        repeat(rounds) {
            for ((tx, dev, accept) in listOf(Triple(h, host, hostAccept), Triple(g, guest, guestAccept))) {
                tx.pump(clock.t)
                if (tx.isOver) continue
                if (tx.stage == TradeTransaction.Stage.OFFER && tx.localOffer == null) {
                    if (offer) assertEquals(null, tx.offer(toOffer[dev]!!)) else tx.cancel()
                }
                if (tx.stage == TradeTransaction.Stage.CONFIRM) tx.confirm(accept)
            }
            if (h.isOver && g.isOver) return Run(h, g)
            clock.t += 1000
        }
        return Run(h, g)
    }

    /** A transport that records the type of every frame it sends, with the link's frame number. */
    class Recording(private val inner: LinkTransport, private val link: LoopbackLink, private val from: String, private val log: MutableList<Pair<Int, String>>) : LinkTransport by inner {
        override fun send(frame: ByteArray) {
            log.add(link.framesSent to "$from:${TradeCodec.decode(frame)::class.simpleName}")
            inner.send(frame)
        }
    }

    /** Every owned Pokémon has a uid no other owned Pokémon shares. */
    fun assertUniqueUids(mons: List<Pokemon>, what: String) {
        val uids = mons.map { it.uid }
        assertTrue(uids.none { it == 0L } && uids.size == uids.toSet().size, "$what: duplicate uid in $uids")
    }
}
