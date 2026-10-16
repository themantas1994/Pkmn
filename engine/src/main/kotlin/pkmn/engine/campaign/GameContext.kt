package pkmn.engine.campaign

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import pkmn.engine.battle.*
import pkmn.engine.data.ContentJson
import pkmn.engine.data.ContentSource
import pkmn.engine.data.Dex
import pkmn.engine.mon.*
import pkmn.engine.save.GameState
import pkmn.engine.save.QuestState
import pkmn.engine.util.Rng
import pkmn.engine.util.toId
import pkmn.engine.world.*
import java.time.LocalTime

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
class GameContext(val dex: Dex, val content: WorldContent, var state: GameState) {
    val factory = PokemonFactory(dex)
    val progression = Progression(dex)
    val rng = Rng(state.rngState)
    /** Overrides the clock (tests, accessibility "always day" option). */
    var clock: () -> LocalTime = { LocalTime.now() }

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

    /** "morning" (4-10), "day" (10-18), "night" (18-4), following the GSC real-time clock. */
    fun timeOfDay(): String {
        if (!state.options.realTimeClock) return "day"
        val h = clock().hour
        return when (h) { in 4..9 -> "morning"; in 10..17 -> "day"; else -> "night" }
    }

    fun isNight() = timeOfDay() == "night"

    /** Copies the RNG position into the state so a reload continues the same random sequence. */
    fun syncRng() { state.rngState = rng.state }

    // ------------------------------------------------------------------ party / pokedex

    fun receivePokemon(mon: Pokemon): String? {
        if (mon.otName.isEmpty()) { mon.otName = state.playerName; mon.otId = state.trainerId }
        val base = mon.species(dex).baseSpecies
        state.seen.add(base); state.caught.add(base)
        return state.addPokemon(mon)
    }

    /** Party indices whose species (any forme) matches [want] — candidates for an in-game trade. */
    fun tradeCandidates(want: String): List<Int> = state.party.indices.filter { i ->
        val sp = state.party[i].species(dex)
        sp.id == want || sp.baseSpecies == want
    }

    /**
     * Swaps party member [index] for [received] (an NPC trade). Returns the species the received Pokémon evolves into
     * by trading, or null. Returns null without trading if [index] does not match [want].
     */
    fun performTrade(index: Int, want: String, received: Pokemon): String? {
        require(index in tradeCandidates(want)) { "party slot $index is not a $want" }
        state.party[index] = received
        val base = received.species(dex).baseSpecies
        state.seen.add(base); state.caught.add(base)
        return evolutionByItem(received, "linkingcord", null)
    }

    fun markSeen(speciesId: String) { state.seen.add(dex.speciesOrNull(speciesId)?.baseSpecies ?: speciesId) }

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

    fun canUseFieldMove(fm: FieldMove): Boolean {
        if (state.badgeCount() < fm.badgeRequired && fm.badgeRequired !in state.badges) return false
        return fieldMoveUser(fm) != null
    }

    /** The first party Pokémon able to learn (or knowing) the field move, used for the field animation/message. */
    fun fieldMoveUser(fm: FieldMove): Pokemon? = state.party.firstOrNull { m ->
        !m.isFainted && (m.moves.any { it.id == fm.moveId } || progression.canLearnMachine(m, fm.moveId))
    }

    // ------------------------------------------------------------------ encounters

    /**
     * Rolls a wild encounter for a step on a tile using [method] ("grass", "cave", "surf", rods...).
     * Returns null when no encounter happens.
     */
    fun rollEncounter(map: MapData, method: String, forceEncounter: Boolean = false): Pokemon? {
        val table = map.encounters?.let { content.encounters[it] } ?: return null
        val byTime = table.methods[method] ?: return null
        if (!forceEncounter) {
            val rate = table.rates[method] ?: defaultRate(method)
            if (rng.nextInt(256) >= rate) return null
        }
        val slots = byTime[timeOfDay()] ?: byTime["any"] ?: byTime.values.firstOrNull() ?: return null
        if (slots.isEmpty()) return null
        val total = slots.sumOf { it.weight }
        var r = rng.nextInt(total)
        val slot = slots.first { r -= it.weight; r < 0 }
        val level = slot.minLevel + rng.nextInt(slot.maxLevel - slot.minLevel + 1)
        val mon = factory.create(slot.species, level, rng, shinyOdds = if (state.itemCount("shinycharm") > 0) 1365 else 4096, hiddenAbilityChance = 0.05)
        markSeen(mon.speciesId)
        return mon
    }

    /** Repel blocks wild Pokémon weaker than the lead. */
    fun repelBlocks(wild: Pokemon): Boolean {
        val lead = state.party.firstOrNull { !it.isFainted } ?: return false
        return state.repelSteps > 0 && wild.level < lead.level
    }

    private fun defaultRate(method: String) = when (method) { "grass" -> 25; "cave" -> 12; "surf" -> 10; else -> 255 }

    // ------------------------------------------------------------------ battles

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
            setMode = state.options.battleStyle == "set",
        )
        for (m in foe.mons) markSeen(m.mon.speciesId)
        return Battle(dex, listOf(playerSide(), foe), rules, seed, environment = map?.environment ?: "plain", isNight = isNight())
    }

    fun createWildBattle(wild: Pokemon, map: MapData?, canRun: Boolean = true, seed: Long = rng.nextLong()): Battle {
        val foe = Side(1, "Wild", listOf(wild), Controller.WILD, dex)
        val rules = BattleRules(allowedGimmicks = allowedGimmicks(map), canRun = canRun, isTrainerBattle = false,
            expShareAll = state.options.expShare, levelCap = levelCap())
        CatchCalc.repeatCheck = { base -> base in state.caught }
        CatchCalc.caughtCount = { state.caught.size }
        markSeen(wild.speciesId)
        return Battle(dex, listOf(playerSide(), foe), rules, seed, environment = map?.environment ?: "plain", isNight = isNight())
    }

    fun prizeMoney(trainerId: String): Int {
        val t = content.trainers[trainerId] ?: return 0
        t.prize?.let { return it }
        val payout = content.trainerClasses[t.trainerClass]?.payout ?: 20
        return payout * (t.team.maxOfOrNull { it.level } ?: 1)
    }

    /** Applies the outcome of a battle to the game state. Returns messages to show. */
    fun applyBattleResult(b: Battle, trainerId: String?): List<String> {
        val msgs = ArrayList<String>()
        when (b.outcome) {
            BattleOutcome.WIN -> if (trainerId != null) {
                state.defeatedTrainers.add(trainerId)
                val prize = prizeMoney(trainerId) * (if (state.party.any { it.heldItem == "amuletcoin" }) 2 else 1)
                state.money = (state.money + prize).coerceAtMost(9_999_999)
                msgs.add("${state.playerName} got ¥$prize for winning!")
            }
            BattleOutcome.CAUGHT -> b.caughtMon?.let { mon ->
                mon.otName = state.playerName; mon.otId = state.trainerId
                mon.metLocation = state.map; mon.metLevel = mon.level
                val where = receivePokemon(mon)
                if (where != null && where != "party") msgs.add("${mon.species(dex).name} was sent to the PC.")
            }
            BattleOutcome.LOSE, BattleOutcome.DRAW -> {
                val lost = (state.money / 2).coerceAtMost(state.money)
                state.money -= lost
                msgs.add("${state.playerName} is out of usable Pokémon!")
                if (lost > 0) msgs.add("${state.playerName} panicked and dropped ¥$lost...")
                msgs.add("${state.playerName} blacked out!")
            }
            else -> {}
        }
        if (b.payDay > 0) { state.money += b.payDay; msgs.add("${state.playerName} picked up ¥${b.payDay}!") }
        return msgs
    }

    /** Party members that should evolve after battle (index -> target species). */
    fun pendingEvolutions(candidates: Collection<Int>, map: MapData?): List<Pair<Int, String>> {
        val ctx = Progression.EvolutionContext(isNight = isNight(), isDusk = clock().hour in 17..18, raining = map?.weather == "rain",
            party = state.party, locationTags = map?.tags?.toSet() ?: emptySet(), coins = state.coins)
        return candidates.mapNotNull { i ->
            val m = state.party.getOrNull(i) ?: return@mapNotNull null
            if (m.isFainted) return@mapNotNull null
            progression.evolutionTarget(m, ctx)?.let { i to it }
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
        state.seen.add(dex.species(target).baseSpecies); state.caught.add(dex.species(target).baseSpecies)
        // Shedinja: Nincada leaves a Shedinja behind if there is a free party slot and a Poké Ball.
        if (oldSpecies == "nincada" && target == "ninjask" && state.party.size < 6 && state.removeItem("pokeball")) {
            val shed = factory.create("shedinja", mon.level, rng, moves = mon.moves.map { it.id })
            receivePokemon(shed)
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
