package pkmn.engine

import pkmn.engine.battle.*
import pkmn.engine.data.Dex
import pkmn.engine.mon.*
import pkmn.engine.util.Rng

/** Shared fixtures for engine tests. The Dex is loaded once per JVM. */
object TestSupport {
    val dex: Dex by lazy { Dex.loadDefault() }
    val factory by lazy { PokemonFactory(dex) }

    fun mon(
        species: String,
        level: Int = 50,
        moves: List<String> = listOf("tackle"),
        ability: String? = null,
        item: String? = null,
        nature: Nature = Nature.HARDY,
        evs: StatSpread = StatSpread(),
        ivs: StatSpread = StatSpread(31, 31, 31, 31, 31, 31),
        tera: String? = null,
    ): Pokemon {
        val sp = dex.species(species)
        val slot = ability?.let { a -> sp.abilities.entries.firstOrNull { it.value == a }?.key } ?: "0"
        val p = Pokemon(
            speciesId = sp.id, level = level, nature = nature, ivs = ivs, evs = evs, abilitySlot = slot,
            heldItem = item, uid = factory.nextUid(), teraType = tera ?: sp.types.first(),
            exp = sp.growthRate.expForLevel(level),
        )
        for (m in moves) p.moves.add(MoveSlot(dex.move(m).id, dex.move(m).pp))
        p.hp = p.maxHp(dex)
        return p
    }

    fun battle(
        player: List<Pokemon>,
        foe: List<Pokemon>,
        seed: Long = 1,
        rules: BattleRules = BattleRules(allowedGimmicks = BattleRules.ALL_GIMMICKS, isTrainerBattle = true),
        wild: Boolean = false,
        foeAi: Int = 1,
    ): Battle {
        val s0 = Side(0, "Player", player, Controller.PLAYER, dex, gimmicks = BattleRules.ALL_GIMMICKS)
        val s1 = Side(1, if (wild) "Wild" else "Rival", foe, if (wild) Controller.WILD else Controller.AI, dex, aiLevel = foeAi, gimmicks = BattleRules.ALL_GIMMICKS)
        val b = Battle(dex, listOf(s0, s1), rules, seed)
        b.start()
        return b
    }

    /** Both sides use the given move indices this turn. */
    fun turn(b: Battle, p: Choice, o: Choice) {
        check(b.choose(b.player, p) == null) { "player choice rejected: ${b.choose(b.player, p)}" }
        check(b.choose(b.opponent, o) == null) { "foe choice rejected" }
        b.advance()
    }

    fun rng(seed: Long = 7) = Rng(seed)
}
