package pkmn.engine.link

import pkmn.engine.TestSupport.dex
import pkmn.engine.TestSupport.factory
import pkmn.engine.battle.BattleAI
import pkmn.engine.battle.BattleOutcome
import pkmn.engine.battle.BattleRules
import pkmn.engine.battle.Choice
import pkmn.engine.battle.Request
import pkmn.engine.data.FormKind
import pkmn.engine.data.ItemCategory
import pkmn.engine.mon.Pokemon
import pkmn.engine.util.Rng

/** Shared pieces of the link tests: random teams, an AI player that never touches the battle's RNG, and a two-device runner. */
object LinkTestSupport {
    const val VERSION = "test-1"
    private val pool by lazy { dex.species.values.filter { !it.isBattleOnly && it.formKind != FormKind.TOTEM && it.isNonstandard != "Future" || it.formKind == FormKind.BASE }.sortedBy { it.id } }
    private val items by lazy { dex.items.values.filter { it.hasBattleData && it.category != ItemCategory.Z_CRYSTAL || it.category == ItemCategory.Z_CRYSTAL }.map { it.id }.sorted() }
    private val allMoves by lazy { dex.moves.values.filter { it.isZ == null && it.isMax == null && it.id != "struggle" }.map { it.id }.sorted() }
    val dataHash by lazy { LinkFingerprint.dataHash(dex) }

    fun randomMon(rng: Rng, level: Int): Pokemon {
        val sp = pool[rng.nextInt(pool.size)]
        val ls = dex.learnset(sp.id)
        val candidates = (ls?.levelUp?.map { it.move }.orEmpty() + ls?.machine.orEmpty()).distinct().ifEmpty { allMoves }
        val moves = (0 until 4).map { if (rng.nextInt(10) == 0) allMoves[rng.nextInt(allMoves.size)] else candidates[rng.nextInt(candidates.size)] }.distinct()
        val m = factory.create(sp.id, level, rng, moves = moves, item = if (rng.nextInt(3) > 0) items[rng.nextInt(items.size)] else null,
            abilitySlot = sp.abilities.keys.toList()[rng.nextInt(sp.abilities.size)])
        m.gmaxFactor = rng.nextInt(4) == 0
        m.friendship = rng.nextInt(256)
        return m
    }

    fun teams(i: Int, maxSize: Int = 6): Pair<List<Pokemon>, List<Pokemon>> {
        val rng = Rng(21000L + i)
        val level = 5 + rng.nextInt(96)
        return List(1 + rng.nextInt(maxSize)) { randomMon(rng, level) } to List(1 + rng.nextInt(maxSize)) { randomMon(rng, level) }
    }

    fun rules(oneGimmick: Boolean = true) = LinkRules.of(BattleRules.link(allowedGimmicks = BattleRules.ALL_GIMMICKS, oneGimmickPerBattle = oneGimmick))
    fun config(rules: LinkRules = rules(), version: String = VERSION, hash: String = dataHash, timeouts: LinkTimeouts = LinkTimeouts()) = LinkConfig(version, rules, timeouts, hash)

    /** Plays the local side with the game's AI, drawing from its own generator so the shared battle's random numbers stay untouched. */
    class AiPlayer(private val lb: LockstepBattle, seed: Long, private val forfeitAtTurn: Int? = null) {
        private val ai = BattleAI(Rng(seed))
        fun act() {
            val b = lb.battle ?: return
            for (d in lb.pendingLocal()) {
                val side = b.sides[if (lb.role == LinkRole.HOST) 0 else 1]
                val before = b.stateHash(); val rngBefore = b.rng.state; val snap = b.sides.flatMap { sd -> sd.mons.map { m -> "${m.species.id} hp=${m.hp} lm=${m.lastMove} ld=${m.lastDamage} ko=${m.koCredit?.species?.id} ldb=${m.lastDamagedBy} ta=${m.timesAttacked} ict=${m.itemConsumedTurn} item=${m.item} used=${m.usedItemThisBattle} st=${m.isStarted} hurt=${m.hurtThisTurn} lhl=${m.lastHpLossEffect} vol=${m.volatiles.keys}" } }
                val c: Choice = when (val r = d.request) {
                    is Request.SwitchIn -> Choice.Switch(ai.chooseReplacement(b, side))
                    is Request.Move -> if (forfeitAtTurn != null && b.turn >= forfeitAtTurn) Choice.Forfeit else ai.chooseAction(b, side, d.slot)
                    Request.Wait -> continue
                }
                // Choosing must be a pure look: if the AI changed the shared battle on this device only, the devices drift apart.
                check(b.stateHash() == before) { "the AI changed the battle state while choosing (turn ${b.turn}, ${d.request}) rng ${rngBefore}->${b.rng.state}\n" + snap.zip(b.sides.flatMap { sd -> sd.mons.map { m -> "${m.species.id} hp=${m.hp} lm=${m.lastMove} ld=${m.lastDamage} ko=${m.koCredit?.species?.id} ldb=${m.lastDamagedBy} ta=${m.timesAttacked} ict=${m.itemConsumedTurn} item=${m.item} used=${m.usedItemThisBattle} st=${m.isStarted} hurt=${m.hurtThisTurn} lhl=${m.lastHpLossEffect} vol=${m.volatiles.keys}" } }).filter { it.first != it.second }.joinToString("\n") }
                if (lb.submit(d.slot, c) != null && lb.submit(d.slot, Choice.UseMove(0)) != null) lb.submit(d.slot, Choice.Pass)
            }
        }
    }

    /** Two devices, a link between them and a simulated clock. */
    class Session(
        i: Int, faults: LinkFaults = LinkFaults(), cfgHost: LinkConfig = config(), cfgGuest: LinkConfig = config(),
        maxTeam: Int = 6, wrapHost: (LinkTransport) -> LinkTransport = { it }, wrapGuest: (LinkTransport) -> LinkTransport = { it },
        hostForfeitAt: Int? = null, guestForfeitAt: Int? = null,
    ) {
        var now = 0L
        val link = LoopbackLink({ now }, faults)
        val host: LockstepBattle
        val guest: LockstepBattle
        private val hostAi: AiPlayer
        private val guestAi: AiPlayer
        init {
            val (t1, t2) = teams(i, maxTeam)
            host = LockstepBattle(LinkRole.HOST, wrapHost(link.a), dex, cfgHost, t1, "Host", Rng(31000L + i)::nextLong)
            guest = LockstepBattle(LinkRole.GUEST, wrapGuest(link.b), dex, cfgGuest, t2, "Guest", Rng(41000L + i)::nextLong)
            hostAi = AiPlayer(host, 51000L + i, hostForfeitAt)
            guestAi = AiPlayer(guest, 61000L + i, guestForfeitAt)
        }

        /** Advances the clock by [stepMs] and lets both devices read, decide and send, until both end or [maxTicks] pass. */
        fun run(stepMs: Long = 100, maxTicks: Int = 20000): Session {
            var t = 0
            while ((!host.isOver || !guest.isOver) && t++ < maxTicks) {
                now += stepMs
                host.pump(now); hostAi.act(); guest.pump(now); guestAi.act()
                host.pump(now); guest.pump(now)
            }
            return this
        }

        fun outcomes(): Pair<LinkResult?, LinkResult?> = host.result to guest.result
    }
}
