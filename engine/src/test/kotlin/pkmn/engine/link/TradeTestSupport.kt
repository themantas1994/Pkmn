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
        var ctx = GameContext(dex, content, GameContext.newGame(content, name, "m", "RIVAL", seed))
            private set
        /** Number of save attempts that fail before saves work again (to test a failed save). */
        var failSaves = 0
        /** When positive, counts saves down and fails the one that reaches 0 (2 = the next save works, the one after fails). */
        var failOnSave = 0
        /** When set, the next save fails at this step of SaveManager.save ("tmp-written", "main-moved"), after writing files. */
        var failInsideSave: String? = null
        private fun newStore() = GameTradeStore(ctx) { s ->
            if (failSaves > 0) { failSaves--; throw java.io.IOException("disk full") }
            if (failOnSave > 0 && --failOnSave == 0) throw java.io.IOException("disk full")
            val sm = SaveManager(dir, dex)
            failInsideSave?.let { step -> failInsideSave = null; sm.testFault = { at -> if (at == step) throw java.io.IOException("failed at $at") } }
            sm.save(s)
        }
        var store = newStore()
            private set

        /** The game is closed and started again: the running state is replaced by what the save file holds. */
        fun restart(): LoadResult.Ok {
            val l = load()
            ctx = GameContext(dex, content, l.state)
            store = newStore()
            return l
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
        hostTransport: LinkTransport = link.a, guestTransport: LinkTransport = link.b, nonces: Pair<String, String> = "a1" to "b2",
    ): Run {
        val toOffer = mapOf(host to host.ctx.state.party.getOrNull(hostOffer), guest to guest.ctx.state.party.getOrNull(guestOffer))
        val h = TradeTransaction(LinkRole.HOST, hostTransport, dex, host.store, host.name, guest.name, nonceSource = { nonces.first })
        val g = TradeTransaction(LinkRole.GUEST, guestTransport, dex, guest.store, guest.name, host.name, nonceSource = { nonces.second })
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

    /** The link frame number of the first frame "host:TradePrepared", "guest:TradeCommitted" and so on, in a complete trade. */
    val frameIndex: Map<String, Int> by lazy {
        val a = Device("ARI", 101, listOf("pidgey", "rattata")); val b = Device("MIO", 202, listOf("kadabra", "geodude"))
        val clock = Clock(); val link = LoopbackLink({ clock.t }); val log = ArrayList<Pair<Int, String>>()
        run(link, clock, a, b, hostTransport = Recording(link.a, link, "host", log), guestTransport = Recording(link.b, link, "guest", log))
        log.groupBy({ it.second }, { it.first }).mapValues { it.value.first() }
    }

    /**
     * Plays a trade between [host] and [guest] that is interrupted around PREPARED, so that [committed] ("host",
     * "guest" or "none") is the only device that applied the swap. The guest's PREPARED is sent before the host's, so
     * "host" is a cut at the host's PREPARED (the one-message window), "guest" drops everything the guest sends from its
     * PREPARED on (the host's PREPARED still arrives), and "none" is a cut at the guest's PREPARED.
     */
    fun interrupt(host: Device, guest: Device, committed: String, clock: Clock = Clock()) {
        val hp = frameIndex.getValue("host:TradePrepared"); val gp = frameIndex.getValue("guest:TradePrepared")
        check(gp < hp) { "frame order changed: $frameIndex" }
        val faults = when (committed) {
            "host" -> LinkFaults(cutAt = hp)
            "guest" -> LinkFaults(drop = { n, from -> from == 1 && n >= gp })
            "none" -> LinkFaults(cutAt = gp)
            else -> error(committed)
        }
        run(LoopbackLink({ clock.t }, faults), clock, host, guest)
    }

    /** Every owned Pokémon has a uid no other owned Pokémon shares. */
    fun assertUniqueUids(mons: List<Pokemon>, what: String) {
        val uids = mons.map { it.uid }
        assertTrue(uids.none { it == 0L } && uids.size == uids.toSet().size, "$what: duplicate uid in $uids")
    }
}
