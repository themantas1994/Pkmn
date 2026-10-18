package pkmn.engine.campaign

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import pkmn.engine.battle.*
import pkmn.engine.data.FormKind
import pkmn.engine.data.ContentJson
import pkmn.engine.data.ContentSource
import pkmn.engine.data.Dex
import pkmn.engine.mon.*
import pkmn.engine.save.GameState
import pkmn.engine.save.QuestState
import pkmn.engine.util.Rng
import pkmn.engine.util.toId
import pkmn.engine.world.*

@Serializable
data class BadgeDef(val number: Int, val name: String, val leader: String, val city: String, val type: String, val levelCap: Int)

@Serializable
data class CampaignInfo(
    val title: String,
    val region: String,
    val badges: List<BadgeDef>,
    /** Gimmick unlock flags -> gimmick ids (e.g. "unlock_mega" -> mega). */
    val gimmickFlags: Map<String, String> = mapOf("unlock_mega" to "mega", "unlock_zmove" to "zmove", "unlock_dynamax" to "dynamax", "unlock_tera" to "tera"),
    val startMap: String,
    val startX: Int,
    val startY: Int,
    val startMoney: Int = 3000,
    /** Maps where Dynamax is possible (Power Spots: gyms, league, special dens). */
    val dynamaxTag: String = "powerspot",
    /** Capture badge penalty: highest level each badge count controls (index = badges). Scarlet/Violet's table. */
    val captureBadgeLevels: List<Int> = CaptureRules.SV_BADGE_LEVELS,
)

/** All static campaign content. Maps and scripts are loaded lazily/once from the content source. */
class WorldContent(private val source: ContentSource) {
    private val mapCache = HashMap<String, MapData>()

    val info: CampaignInfo = ContentJson.decodeFromString(CampaignInfo.serializer(), read("world/campaign.json"))
    val trainers: Map<String, TrainerDef> = list("world/trainers.json", TrainerDef.serializer()).associateBy { it.id }
    val trainerClasses: Map<String, TrainerClass> = list("world/trainer_classes.json", TrainerClass.serializer()).associateBy { it.id }
    val encounters: Map<String, EncounterTable> = list("world/encounters.json", EncounterTable.serializer()).associateBy { it.id }
    val shops: Map<String, ShopDef> = list("world/shops.json", ShopDef.serializer()).associateBy { it.id }
    val quests: Map<String, QuestDef> = list("world/quests.json", QuestDef.serializer()).associateBy { it.id }
    val index: WorldIndex = ContentJson.decodeFromString(WorldIndex.serializer(), read("world/index.json"))
    /** Recurring world events (assets/events/events.json). Invalid entries are dropped and listed in [WorldEvents.errors]. */
    val events: WorldEvents by lazy { WorldEvents.parse(source.read("events/events.json"), null, { id -> source.read("maps/$id.json") != null }) }
    val scripts: Map<String, CompiledScript> by lazy {
        val files = source.read("scripts/index.txt")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        files.flatMap { f -> ScriptParser.parseFile(read("scripts/$f"), f) }.associateBy { it.id }
    }

    private fun read(path: String) = source.read(path) ?: error("Missing world content '$path'")
    private fun <T> list(path: String, s: kotlinx.serialization.KSerializer<T>): List<T> =
        source.read(path)?.let { ContentJson.decodeFromString(ListSerializer(s), it) } ?: emptyList()

    fun map(id: String): MapData = synchronized(mapCache) {
        mapCache.getOrPut(id) { ContentJson.decodeFromString(MapData.serializer(), read("maps/$id.json")) }
    }

    fun mapOrNull(id: String): MapData? = try { map(id) } catch (e: Exception) { null }
}

/**
 * Game session: state + content + rules. Shared by the script VM, overworld logic and battle setup.
 * Contains no rendering code.
 */
enum class DexState { UNKNOWN, SEEN, CAUGHT }

class GameContext(val dex: Dex, val content: WorldContent, var state: GameState) {
    val factory = PokemonFactory(dex)
    val progression = Progression(dex)
    val rng = Rng(state.rngState)
    /** The device's local wall clock; tests and automation inject a fixed one. */
    var clock: GameClock = GameClock.system()

    // ------------------------------------------------------------------ text

    fun format(s: String): String {
        var t = s.replace("{player}", state.playerName).replace("{rival}", state.rivalName).replace("{money}", state.money.toString())
        if ("{" in t) {
            t = Regex("\\{(var|item|mon|move):([a-z0-9_]+)\\}").replace(t) { m ->
                val (kind, key) = m.destructured
                when (kind) {
                    "var" -> (state.vars[key] ?: 0).toString()
                    "item" -> itemName(key)
                    "mon" -> dex.speciesOrNull(key)?.name ?: key
                    "move" -> dex.moveOrNull(key)?.name ?: key
                    else -> m.value
                }
            }
        }
        return t
    }

    fun itemName(id: String): String = if (id.startsWith("tm:")) "TM ${dex.moveOrNull(id.removePrefix("tm:"))?.name ?: id}" else dex.itemOrNull(id)?.name ?: id

    fun badgeName(n: Int) = content.info.badges.firstOrNull { it.number == n }?.name?.let { "$it Badge" } ?: "Badge #$n"

    // ------------------------------------------------------------------ time

    /** Current local time from [clock]. */
    fun now(): java.time.ZonedDateTime = clock.now()

    /**
     * Gold/Silver/Crystal periods from the device clock: morning 04:00–09:59, day 10:00–17:59, night 18:00–03:59.
     * The "ALWAYS DAY" accessibility option pins it to day.
     */
    fun period(): TimePeriod = if (!state.options.realTimeClock) TimePeriod.DAY else TimePeriod.of(now().toLocalTime())

    /** "morning", "day" or "night" (the ids used by encounter tables and the `time` script condition). */
    fun timeOfDay(): String = period().id

    fun isNight() = period() == TimePeriod.NIGHT

    /** Lighting phase for the overworld tint (adds a dusk hour; follows the same option). */
    fun lightPhase(): LightPhase = if (!state.options.realTimeClock) LightPhase.DAY else LightPhase.of(now().toLocalTime())

    /** Scheduled world events active on [mapId] right now (re-evaluated on every call; nothing is cached). */
    fun activeEvents(mapId: String): List<WorldEventDef> = content.events.activeOn(mapId, state, now())

    /** Copies the RNG position into the state so a reload continues the same random sequence. */
    fun syncRng() { state.rngState = rng.state }

    // ------------------------------------------------------------------ party / pokedex

    /** Gives the player a Pokémon (gift, starter, egg). Returns "party", "box:<n>" or null; see [acquire]. */
    fun receivePokemon(mon: Pokemon, offerNickname: Boolean = true): String? = acquire(mon, offerNickname).where

    /**
     * The single path by which the player obtains a Pokémon. In one step it stores the Pokémon (party, else the first
     * free box slot), registers its species, form and shininess as caught in the Pokédex, and queues the nickname offer
     * (it is saved with the Pokémon, so an interruption resumes at the prompt). Calling it again for the same
     * individual (same uid) changes nothing, so a repeated battle result can never duplicate a capture.
     */
    fun acquire(mon: Pokemon, offerNickname: Boolean = true): Acquisition {
        state.locationOf(mon.uid)?.let { if (mon.uid != 0L) return Acquisition(state.findByUid(mon.uid)!!, it, false, false, duplicate = true) }
        if (mon.otName.isEmpty()) { mon.otName = state.playerName; mon.otId = state.trainerId }
        if (mon.uid == 0L || state.ownedPokemon().any { it.uid == mon.uid }) mon.uid = factory.nextUid()
        val where = state.addPokemon(mon) ?: return Acquisition(mon, null, false, false)
        val (newSpecies, newForm) = registerCaught(mon)
        if (offerNickname && mon.nickname == null && mon.uid !in state.pendingNicknames) state.pendingNicknames.add(mon.uid)
        return Acquisition(mon, where, newSpecies, newForm)
    }

    /** Records a Pokémon as seen (species and exact form). */
    fun markSeen(speciesId: String) {
        val sp = dex.speciesOrNull(speciesId)
        state.seen.add(sp?.baseSpecies ?: speciesId)
        state.formsSeen.add(sp?.id ?: speciesId)
    }

    /** Registers an obtained Pokémon in the Pokédex. Returns (new species, new form). */
    fun registerCaught(mon: Pokemon): Pair<Boolean, Boolean> {
        val sp = mon.species(dex)
        markSeen(sp.id)
        val newSpecies = state.caught.add(sp.baseSpecies)
        val newForm = state.formsCaught.add(sp.id)
        if (mon.shiny) state.shiniesCaught.add(sp.baseSpecies)
        return newSpecies to newForm
    }

    /** Pokédex state of a species (by id; forms count towards their base species). */
    fun dexState(speciesId: String): DexState {
        val base = dex.speciesOrNull(speciesId)?.baseSpecies ?: speciesId
        return when (base) { in state.caught -> DexState.CAUGHT; in state.seen -> DexState.SEEN; else -> DexState.UNKNOWN }
    }

    /** The next obtained Pokémon still waiting for its nickname offer; stale entries (released, traded) are dropped. */
    fun nextPendingNickname(): Pokemon? {
        state.pendingNicknames.removeAll { state.findByUid(it) == null }
        return state.pendingNicknames.firstOrNull()?.let { state.findByUid(it) }
    }

    /**
     * Applies the player's nickname choice and closes the offer. A null [name] keeps the species name. Returns an error
     * message (and changes nothing) when the name is invalid.
     */
    fun resolveNickname(uid: Long, name: String?): String? {
        val mon = state.findByUid(uid) ?: run { state.pendingNicknames.remove(uid); return null }
        if (name != null) when (val c = Nicknames.check(name)) {
            is Nicknames.Check.Invalid -> return "That name can't be used: ${c.reason}."
            is Nicknames.Check.Ok -> mon.nickname = c.name.takeIf { it != mon.species(dex).name }
        }
        state.pendingNicknames.remove(uid)
        return null
    }

    /** Renames a Pokémon the player owns (null restores the species name). Returns an error message or null. */
    fun rename(mon: Pokemon, name: String?): String? {
        if (name == null) { mon.nickname = null; return null }
        return when (val c = Nicknames.check(name)) {
            is Nicknames.Check.Invalid -> "That name can't be used: ${c.reason}."
            is Nicknames.Check.Ok -> { mon.nickname = c.name.takeIf { it != mon.species(dex).name }; null }
        }
    }

    /**
     * Party indices matching [want] — candidates for an in-game trade. Alternate formes of the species count, but
     * regional variants and fusions are different Pokémon (asking for Exeggutor does not accept Alolan Exeggutor).
     */
    fun tradeCandidates(want: String): List<Int> = state.party.indices.filter { i ->
        val sp = state.party[i].species(dex)
        sp.id == want || (sp.baseSpecies == want && sp.formKind != FormKind.REGIONAL && sp.formKind != FormKind.FUSION)
    }

    /**
     * Swaps party member [index] for [received] (an NPC trade). Returns the species the received Pokémon evolves into
     * by trading, or null. Returns null without trading if [index] does not match [want].
     */
    fun performTrade(index: Int, want: String, received: Pokemon): String? {
        require(index in tradeCandidates(want)) { "party slot $index is not a $want" }
        state.party[index] = received
        registerCaught(received)
        return evolutionByItem(received, "linkingcord", null)
    }

    fun healParty() { for (m in state.party) m.healFully(dex) }

    fun setRespawnHere() {
        state.respawnMap = state.map; state.respawnX = state.x; state.respawnY = state.y
        state.lastCenterMap = state.map
    }

    fun respawnPoint(): Triple<String, Int, Int> =
        if (state.respawnMap.isNotEmpty()) Triple(state.respawnMap, state.respawnX, state.respawnY)
        else Triple(content.info.startMap, content.info.startX, content.info.startY)

    fun updateQuest(id: String, action: String) {
        val q = state.quests.getOrPut(id) { QuestState(id) }
        when (action) {
            "start" -> {}
            "next", "advance" -> q.step++
            "done", "complete" -> q.completed = true
            else -> action.toIntOrNull()?.let { q.step = it }
        }
    }

    fun questName(id: String) = content.quests[id]?.name ?: id

    fun recordHallOfFame() { state.hallOfFame.add(state.party.map { it.speciesId }); state.setFlag("hall_of_fame") }

    // ------------------------------------------------------------------ field moves

    /** God Bidoof performs every unlocked field move, so no party Pokémon is needed; the badge is the unlock. */
    fun canUseFieldMove(fm: FieldMove): Boolean = state.badgeCount() >= fm.badgeRequired || fm.badgeRequired in state.badges

    fun bidoofLine(fm: FieldMove) = "God Bidoof answered your prayers and used ${fm.name.replace('_', ' ')}!"

    // ------------------------------------------------------------------ encounters

    /**
     * Rolls a wild encounter for a step on a tile using [method] ("grass", "cave", "surf", rods...).
     * Returns null when no encounter happens.
     */
    fun rollEncounter(map: MapData, method: String, forceEncounter: Boolean = false): Pokemon? {
        val table = map.encounters?.let { content.encounters[it] } ?: return null
        if (table.methods[method] == null) return null
        if (!forceEncounter) {
            val rate = table.rates[method] ?: defaultRate(method)
            if (rng.nextInt(256) >= rate) return null
        }
        val slots = encounterSlots(table, method, timeOfDay())
        if (slots.isEmpty()) return null
        val total = slots.sumOf { it.weight }
        var r = rng.nextInt(total)
        val slot = slots.first { r -= it.weight; r < 0 }
        val level = slot.minLevel + rng.nextInt(slot.maxLevel - slot.minLevel + 1)
        val mon = factory.create(slot.species, level, rng, shinyOdds = if (state.itemCount("shinycharm") > 0) 1365 else 4096, hiddenAbilityChance = 0.05)
        markSeen(mon.speciesId)
        return mon
    }

    /**
     * Slots for [method] at time [period]: the period's own slots plus any "any" slots. If that leaves nothing usable
     * (no slots or no positive weight), falls back to "any", then "day", then the first non-empty period, so a
     * partially authored table never stops encounters. Slots with non-positive weight or unknown species are ignored;
     * the remaining weights are relative (normalised by their sum).
     */
    fun encounterSlots(table: EncounterTable, method: String, period: String): List<EncounterSlot> {
        val byTime = table.methods[method] ?: return emptyList()
        fun valid(l: List<EncounterSlot>?) = l.orEmpty().filter { it.weight > 0 && it.minLevel in 1..100 && it.maxLevel >= it.minLevel && dex.speciesOrNull(it.species) != null }
        val primary = valid(byTime[period]) + if (period != "any") valid(byTime["any"]) else emptyList()
        if (primary.isNotEmpty()) return primary
        return listOf("any", "day", "morning", "night").map { valid(byTime[it]) }.firstOrNull { it.isNotEmpty() }
            ?: byTime.values.map { valid(it) }.firstOrNull { it.isNotEmpty() } ?: emptyList()
    }

    /** Repel blocks wild Pokémon weaker than the lead. */
    fun repelBlocks(wild: Pokemon): Boolean {
        val lead = state.party.firstOrNull { !it.isFainted } ?: return false
        return state.repelSteps > 0 && wild.level < lead.level
    }

    private fun defaultRate(method: String) = when (method) { "grass" -> 25; "cave" -> 12; "surf" -> 10; else -> 255 }

    // ------------------------------------------------------------------ battles

    /** Badge obedience cap for the player's current badge count ([Obedience]); null once every Pokémon obeys. */
    fun obedienceCap(): Int? = Obedience.levelCap(state.badgeCount())

    fun levelCap(): Int {
        if (state.options.difficulty < 2) return 100
        val next = content.info.badges.firstOrNull { it.number !in state.badges } ?: return 100
        return next.levelCap
    }

    fun allowedGimmicks(map: MapData?): Set<Gimmick> {
        val set = HashSet<Gimmick>()
        if (state.hasFlag("unlock_mega")) { set.add(Gimmick.MEGA); set.add(Gimmick.ULTRA_BURST) }
        if (state.hasFlag("unlock_zmove")) set.add(Gimmick.ZMOVE)
        if (state.hasFlag("unlock_tera") && state.itemCount("teraorb") > 0) set.add(Gimmick.TERA)
        if (state.hasFlag("unlock_dynamax") && map?.tags?.contains(content.info.dynamaxTag) == true) set.add(Gimmick.DYNAMAX)
        return set
    }

    fun playerSide(): Side {
        val g = HashSet<Gimmick>()
        if (state.itemCount("keystone") > 0 || state.hasFlag("unlock_mega")) { g.add(Gimmick.MEGA); g.add(Gimmick.ULTRA_BURST) }
        if (state.itemCount("zring") > 0 || state.hasFlag("unlock_zmove")) g.add(Gimmick.ZMOVE)
        if (state.itemCount("dynamaxband") > 0 || state.hasFlag("unlock_dynamax")) g.add(Gimmick.DYNAMAX)
        if (state.itemCount("teraorb") > 0) g.add(Gimmick.TERA)
        return Side(0, state.playerName, state.party, Controller.PLAYER, dex, gimmicks = g)
    }

    fun buildTrainerTeam(t: TrainerDef): List<Pokemon> = t.team.map { tm ->
        val local = Rng(toId(t.id).hashCode().toLong() * 31 + tm.species.hashCode())
        val sp = dex.species(tm.species)
        val slot = tm.ability?.let { ab -> sp.abilities.entries.firstOrNull { it.value == toId(ab) || it.key == ab }?.key }
        val mon = factory.create(sp.id, tm.level, local, shinyOdds = if (tm.shiny) 1 else Int.MAX_VALUE,
            moves = tm.moves.ifEmpty { null }?.map { toId(it) }, abilitySlot = slot, item = tm.item?.let { toId(it) },
            nature = tm.nature?.let { Nature.valueOf(it.uppercase()) })
        val iv = if (tm.ivs >= 0) tm.ivs else (tm.level / 4 + 4).coerceAtMost(31)
        for (s in pkmn.engine.data.Stat.entries) mon.ivs[s] = iv
        if (tm.evs.size == 6) pkmn.engine.data.Stat.entries.forEachIndexed { i, s -> mon.evs[s] = tm.evs[i] }
        mon.teraType = tm.tera ?: sp.types.first()
        mon.gmaxFactor = tm.gmax
        mon.nickname = tm.nickname
        mon.friendship = 255
        mon.hp = mon.maxHp(dex)
        mon
    }

    fun createTrainerBattle(trainerId: String, map: MapData?, seed: Long = rng.nextLong()): Battle {
        val t = content.trainers[trainerId] ?: error("Unknown trainer '$trainerId'")
        val cls = content.trainerClasses[t.trainerClass]
        val g = t.gimmicks.mapNotNull { gimmickOf(it) }.toSet()
        val ai = when (state.options.difficulty) { 0 -> (t.ai - 1).coerceAtLeast(0); 2 -> (t.ai + 1).coerceAtMost(3); else -> t.ai }
        val foe = Side(1, "${cls?.name ?: t.trainerClass} ${t.name}".trim(), buildTrainerTeam(t), Controller.AI, dex,
            trainerClass = t.trainerClass, aiLevel = ai, gimmicks = g)
        val rules = BattleRules(
            allowedGimmicks = allowedGimmicks(map) + g.filter { it != Gimmick.DYNAMAX || map?.tags?.contains(content.info.dynamaxTag) == true },
            canRun = false, canCatch = false, isTrainerBattle = true, expShareAll = state.options.expShare, levelCap = levelCap(),
            setMode = state.options.battleStyle == "set", playerOtId = state.trainerId, obedienceCap = obedienceCap(),
        )
        for (m in foe.mons) markSeen(m.mon.speciesId)
        return Battle(dex, listOf(playerSide(), foe), rules, seed, environment = map?.environment ?: "plain", isNight = isNight())
    }

    /**
     * Capture rules for the player's current progress (docs/CAPTURE.md). Scarlet/Violet's low-level bonus is a Casual
     * difficulty option only: this campaign's first two hours are all below level 13, where the bonus made full-HP
     * throws at common Pokémon 95-100% certain.
     */
    /** True when the party and every PC box are full: Poké Balls cannot be thrown (as in the official games). */
    fun storageFull(): Boolean = state.party.size >= 6 && state.boxes.all { box -> box.none { it == null } }

    fun captureRules(): CaptureRules = CaptureRules(
        badges = state.badgeCount(), badgeLevels = content.info.captureBadgeLevels,
        lowLevelBonus = state.options.difficulty == 0, caughtSpecies = state.caught.toSet(),
        catchingCharm = state.itemCount("catchingcharm") > 0,
    )

    /**
     * A wild battle. [environment] overrides the map's battle environment ("water" while surfing, "fishing" after a
     * bite) so the Dive and Lure Balls work. [canCatch] is false for scripted Pokémon that must not be caught.
     */
    fun createWildBattle(wild: Pokemon, map: MapData?, canRun: Boolean = true, seed: Long = rng.nextLong(),
                         environment: String? = null, canCatch: Boolean = true): Battle {
        val foe = Side(1, "Wild", listOf(wild), Controller.WILD, dex)
        val rules = BattleRules(allowedGimmicks = allowedGimmicks(map), canRun = canRun, canCatch = canCatch, isTrainerBattle = false,
            expShareAll = state.options.expShare, levelCap = levelCap(), playerOtId = state.trainerId, capture = captureRules(),
            obedienceCap = obedienceCap())
        markSeen(wild.speciesId)
        return Battle(dex, listOf(playerSide(), foe), rules, seed, environment = environment ?: map?.environment ?: "plain", isNight = isNight())
    }

    fun prizeMoney(trainerId: String): Int {
        val t = content.trainers[trainerId] ?: return 0
        t.prize?.let { return it }
        val payout = content.trainerClasses[t.trainerClass]?.payout ?: 20
        return payout * (t.team.maxOfOrNull { it.level } ?: 1)
    }

    /**
     * Money lost on a defeat: 4 × (1 + badges) × the party's highest level, so it scales like the trainer prize
     * (class payout × highest level, 12–32 per level) and never exceeds half the wallet (nor the wallet itself).
     */
    fun defeatPenalty(): Int {
        val perLevel = 4 * (1 + state.badgeCount().coerceAtMost(8))
        val level = state.party.maxOfOrNull { it.level } ?: 1
        return (perLevel * level).coerceAtMost(state.money / 2).coerceAtLeast(0)
    }

    /** The battle whose result was last applied; a re-entered callback must not pay or charge twice. */
    private var lastAppliedBattle: Battle? = null

    /**
     * Applies the outcome of a battle to the game state. Returns messages to show. A loss in a [canLose] battle
     * (scripted story fights) costs nothing and does not black out. Applying the same battle twice is a no-op.
     */
    /** The capture recorded by the last [applyBattleResult] (the UI shows its Pokédex entry and nickname prompt). */
    var lastCapture: Acquisition? = null
        private set

    fun applyBattleResult(b: Battle, trainerId: String?, canLose: Boolean = false): List<String> {
        lastCapture = null
        val msgs = ArrayList<String>()
        val reapplied = b === lastAppliedBattle
        if (reapplied && b.outcome != BattleOutcome.CAUGHT) return msgs
        lastAppliedBattle = b
        when (b.outcome) {
            BattleOutcome.WIN -> if (trainerId != null && state.defeatedTrainers.add(trainerId)) {
                val prize = prizeMoney(trainerId) * (if (state.party.any { it.heldItem == "amuletcoin" }) 2 else 1)
                state.money = (state.money + prize).coerceAtMost(9_999_999)
                msgs.add("${state.playerName} got ¥$prize for winning!")
            }
            BattleOutcome.CAUGHT -> b.caughtMon?.let { mon ->
                if (state.locationOf(mon.uid) == null) {
                    mon.otName = state.playerName; mon.otId = state.trainerId
                    mon.metLocation = state.map; mon.metLevel = mon.level; mon.outsider = false
                }
                lastCapture = acquire(mon).also { if (it.where == null) msgs.add("There's no room left for ${mon.species(dex).name}! It was released.") }
            }
            BattleOutcome.LOSE, BattleOutcome.DRAW -> if (!canLose) {
                val lost = defeatPenalty()
                state.money -= lost
                msgs.add("${state.playerName} is out of usable Pokémon!")
                if (lost > 0) msgs.add("${state.playerName} panicked and dropped ¥$lost...")
                msgs.add("${state.playerName} blacked out!")
            }
            else -> {}
        }
        if (!reapplied && b.payDay > 0) { state.money += b.payDay; msgs.add("${state.playerName} picked up ¥${b.payDay}!") }
        return msgs
    }

    /**
     * Party members that should evolve (index -> target species): after battle, or with [spun] after the player spins
     * on the spot, in which case only spin evolutions (Milcery) are considered.
     */
    fun pendingEvolutions(candidates: Collection<Int>, map: MapData?, spun: Boolean = false): List<Pair<Int, String>> {
        val ctx = Progression.EvolutionContext(isNight = isNight(), isDusk = now().hour in 17..18, raining = map?.weather == "rain",
            party = state.party, locationTags = map?.tags?.toSet() ?: emptySet(), coins = state.coins, environment = map?.environment ?: "plain",
            spun = spun)
        return candidates.mapNotNull { i ->
            val m = state.party.getOrNull(i) ?: return@mapNotNull null
            if (m.isFainted) return@mapNotNull null
            val target = progression.evolutionTarget(m, ctx) ?: return@mapNotNull null
            if (spun && m.species(dex).evolutions.none { it.target == target && it.conditionCode?.split('&')?.contains("spin") == true }) return@mapNotNull null
            i to target
        }
    }

    fun evolutionByItem(mon: Pokemon, item: String, map: MapData?): String? =
        progression.evolutionTarget(mon, Progression.EvolutionContext(isNight = isNight(), party = state.party, usedItem = item,
            linkingCord = item == "linkingcord", locationTags = map?.tags?.toSet() ?: emptySet()))

    /** Performs an evolution, learning new moves where there is room. Returns moves that need a replace prompt. */
    fun evolve(mon: Pokemon, target: String): List<String> {
        val oldMax = mon.maxHp(dex)
        val oldSpecies = mon.speciesId
        mon.speciesId = target
        val newMax = mon.maxHp(dex)
        if (!mon.isFainted) mon.hp = (mon.hp + newMax - oldMax).coerceIn(1, newMax)
        registerCaught(mon)
        // Shedinja: Nincada leaves a Shedinja behind if there is a free party slot and a Poké Ball.
        if (oldSpecies == "nincada" && target == "ninjask" && state.party.size < 6 && state.removeItem("pokeball")) {
            val shed = factory.create("shedinja", mon.level, rng, moves = mon.moves.map { it.id })
            // Shedinja keeps Nincada's origin (met level and outsider status), like any evolution.
            shed.metLevel = mon.metLevel; shed.outsider = mon.outsider
            receivePokemon(shed, offerNickname = false)
        }
        val pending = ArrayList<String>()
        for (mv in progression.movesOnEvolution(target, mon.level)) {
            if (mon.moves.any { it.id == mv }) continue
            val md = dex.moveOrNull(mv) ?: continue
            if (mon.moves.size < 4) mon.moves.add(MoveSlot(md.id, md.pp)) else pending.add(mv)
        }
        return pending
    }

    companion object {
        fun gimmickOf(s: String): Gimmick? = when (toId(s)) {
            "mega" -> Gimmick.MEGA; "zmove", "z" -> Gimmick.ZMOVE; "dynamax", "gigantamax" -> Gimmick.DYNAMAX; "tera" -> Gimmick.TERA
            "ultraburst" -> Gimmick.ULTRA_BURST; else -> null
        }

        /** Creates a fresh game state at the campaign start. */
        fun newGame(content: WorldContent, name: String, gender: String, rival: String, seed: Long): GameState = GameState(
            playerName = name, playerGender = gender, rivalName = rival, trainerId = (seed and 0xFFFF).toInt(),
            money = content.info.startMoney, map = content.info.startMap, x = content.info.startX, y = content.info.startY,
            rngState = seed, respawnMap = content.info.startMap, respawnX = content.info.startX, respawnY = content.info.startY,
        )
    }
}
