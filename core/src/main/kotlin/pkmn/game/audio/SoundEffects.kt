package pkmn.game.audio

/** Every sound effect in the game. [id] is a stable snake_case identifier. */
enum class Sfx(val id: String) {
    MENU_MOVE("menu_move"),
    MENU_SELECT("menu_select"),
    MENU_CANCEL("menu_cancel"),
    BUMP("bump"),
    DOOR_ENTER("door_enter"),
    WARP("warp"),
    LEDGE_JUMP("ledge_jump"),
    ITEM_GET("item_get"),
    HEAL("heal"),
    SAVE("save"),
    LEVEL_UP("level_up"),
    BATTLE_START("battle_start"),
    HIT_NORMAL("hit_normal"),
    HIT_SUPER_EFFECTIVE("hit_super_effective"),
    HIT_NOT_VERY_EFFECTIVE("hit_not_very_effective"),
    FAINT("faint"),
    STAT_UP("stat_up"),
    STAT_DOWN("stat_down"),
    STATUS_INFLICTED("status_inflicted"),
    BALL_THROW("ball_throw"),
    BALL_SHAKE("ball_shake"),
    CATCH_SUCCESS("catch_success"),
    RUN_AWAY("run_away"),
    EVOLUTION_START("evolution_start"),
    EVOLUTION_FINISH("evolution_finish"),
    ERROR("error"),
    TEXT_BLIP("text_blip"),
    PC_ON("pc_on"),
    PC_OFF("pc_off"),
    TRAINER_SPOTTED("trainer_spotted");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): Sfx? = byId[id]
    }
}

/**
 * Procedural sound-effect definitions. Each effect is a tiny song in the same MML format as the
 * music (see docs/AUDIO.md), played by its own [ChipSynth] so effects mix over the music.
 * Most effects use `#tempo 125` so one tick (`%1`) is exactly 10 ms.
 */
object SoundEffects {
    private const val H = "#tempo 125\n"

    /** MML source of every effect. */
    val sources: Map<Sfx, String> = mapOf(
        Sfx.MENU_MOVE to H + """
            #inst 1 duty=2 env=9,-1
            A @1 o6 d%4
        """,
        Sfx.MENU_SELECT to H + """
            #inst 1 duty=2 env=11,-1
            A @1 o5 a%3 >e%9
        """,
        Sfx.MENU_CANCEL to H + """
            #inst 1 duty=1 env=11,-1
            A @1 o6 d%3 <g%9
        """,
        Sfx.BUMP to H + """
            #inst 1 duty=3 env=13,-1
            #drum Q f=2500 v=9 e=-1 sw=-1.5
            A @1 o2 j-5 a%14
            D Q%14
        """,
        Sfx.DOOR_ENTER to H + """
            #drum Q f=26000 v=5 e=1 n=7 | f=26000 v=12 e=-1 sw=-0.35
            #inst 1 duty=1 env=6,-1
            A @1 o3 j-7 c%10 r%30
            D Q%40
        """,
        Sfx.WARP to H + """
            #inst 1 duty=2 env=11,-1
            #inst 2 duty=0 env=7,-1
            A @1 o4 j7 [c%4 g%4 >]3 c%14 r%4
            B @2 r%4 o4 j7 [c%4 g%4 >]3 c%14
        """,
        Sfx.LEDGE_JUMP to H + """
            #inst 1 duty=2 env=12,-1
            A @1 o4 j9 d%9 j-14 b%15
        """,
        Sfx.ITEM_GET to """
            #tempo 150
            #inst 1 duty=2 env=12,-1
            #inst 2 duty=1 env=8,-1
            A @1 o5 l16 d f+ a >d4
            B @2 o4 l16 a >d f+ a4
            C @0 o3 l16 d r r d4
        """,
        Sfx.HEAL to """
            #tempo 132
            #inst 1 duty=2 env=11,-2 vib=12,10,6
            #inst 2 duty=1 env=7,-2
            A @1 o5 l16 e g+ b >e <b >e8.
            B @2 o4 l16 b >e g+ b g+ b8.
        """,
        Sfx.SAVE to """
            #tempo 140
            #inst 1 duty=2 env=10,-1
            #inst 2 duty=1 env=7,-1
            A @1 o5 l32 c g >c <g >e c g e >c8.
            B @2 o4 l32 r g >c <g >c <g >e c g8.
        """,
        Sfx.LEVEL_UP to """
            #tempo 160
            #inst 1 duty=2 env=12,-1
            #inst 2 duty=1 env=8,-1
            A @1 o5 l16 f a >c f8 c16 f4
            B @2 o4 l16 a >c f a8 f16 a4
            C @0 o3 l16 f r f r8 c16 f4
        """,
        Sfx.BATTLE_START to H + """
            #inst 1 duty=2 env=12,0
            #inst 2 duty=0 env=9,0
            A @1 o6 j-24 c%25 j24 <c%25 j-36 >g%40
            B @2 o5 j-24 g%25 j24 <g%25 j-36 >d%40
            D N%50 Z%40
        """,
        Sfx.HIT_NORMAL to H + """
            #drum Q f=70000 v=15 e=-1 sw=-0.7
            #inst 1 duty=3 env=12,-2
            A @1 o3 j-12 c%8 r%14
            D Q%22
        """,
        Sfx.HIT_SUPER_EFFECTIVE to H + """
            #drum Q f=90000 v=15 n=3 | f=40000 v=15 e=-2 sw=-0.45
            #inst 1 duty=3 env=14,-2
            #inst 2 duty=2 env=11,-2
            A @1 o3 j-18 g%22 r%20
            B @2 o2 j-12 c%22 r%20
            D Q%42
        """,
        Sfx.HIT_NOT_VERY_EFFECTIVE to H + """
            #drum Q f=150000 v=9 e=-1 sw=-0.4
            #inst 1 duty=1 env=7,-1
            A @1 o4 j-5 c%6 r%6
            D Q%12
        """,
        Sfx.FAINT to H + """
            #inst 1 duty=2 env=12,-4
            #inst 2 duty=1 env=9,-4
            A @1 o5 j-30 c%80
            B @2 o4 j-30 g%80
        """,
        Sfx.STAT_UP to H + """
            #inst 1 duty=1 env=11,-1
            #inst 2 duty=2 env=6,-1
            A @1 o4 j12 c%7 e%7 g%7 >c%7 e%7 g%7 r%4
            B @2 r%4 o4 j12 c%7 e%7 g%7 >c%7 e%7 g%7
        """,
        Sfx.STAT_DOWN to H + """
            #inst 1 duty=1 env=11,-1
            #inst 2 duty=2 env=6,-1
            A @1 o6 j-12 c%7 <a%7 f%7 d%7 <b-%7 g%7 r%4
            B @2 r%4 o6 j-12 c%7 <a%7 f%7 d%7 <b-%7 g%7
        """,
        Sfx.STATUS_INFLICTED to H + """
            #inst 1 duty=1 env=11,0 rel=-2
            A @1 o4 [c%3 f+%3]6 r%8
        """,
        Sfx.BALL_THROW to H + """
            #inst 1 duty=2 env=9,-1
            #drum Q f=20000 v=4 e=1 n=6 | v=9 e=-1 sw=1
            A @1 o4 j24 c%22 r%6
            D Q%28
        """,
        Sfx.BALL_SHAKE to H + """
            #inst 1 duty=3 env=10,-2
            A @1 o3 j-4 a%5 r%6 j4 a%5 r%9
            D R%11 R%14
        """,
        Sfx.CATCH_SUCCESS to """
            #tempo 150
            #inst 1 duty=2 env=12,-1
            #inst 2 duty=1 env=8,-1
            A @1 o5 l16 g >c e g8 e16 g4
            B @2 o4 l16 e g >c e8 c16 e4
            C @0 o3 l16 c r c r8 g16 >c4
        """,
        Sfx.RUN_AWAY to H + """
            #drum Q f=60000 v=11 e=-1 sw=-1.2
            #inst 1 duty=1 env=8,-1
            A @1 o5 j-7 c%6 <a%6 f%6 d%12
            D Q%6 Q%6 Q%6 Q%12
        """,
        Sfx.EVOLUTION_START to H + """
            #inst 1 duty=2 env=4,1 vib=0,30,9
            #inst 2 duty=0 env=3,1
            A @1 o4 j12 c%30 g%30 >c%40
            B @2 o5 j12 c%30 g%30 >c%40
            D N%100
        """,
        Sfx.EVOLUTION_FINISH to """
            #tempo 140
            #inst 1 duty=2 env=12,-1
            #inst 2 duty=1 env=9,-1
            A @1 o5 l16 c e g >c e g >c4
            B @2 o4 l16 g >c e g >c e g4
            C @0 o3 l16 c r c r c r c4
            D C4 r8 C4
        """,
        Sfx.ERROR to H + """
            #inst 1 duty=3 env=11,0
            #inst 2 duty=3 env=11,0
            A @1 o3 c%12 r%4 c%16
            B @2 o3 c+%12 r%4 c+%16
        """,
        Sfx.TEXT_BLIP to H + """
            #inst 1 duty=1 env=5,-1
            A @1 o6 g%2
        """,
        Sfx.PC_ON to H + """
            #inst 1 duty=2 env=9,-1
            A @1 o5 c%5 e%5 g%5 >c%10
        """,
        Sfx.PC_OFF to H + """
            #inst 1 duty=2 env=9,-1
            A @1 o6 c%5 <g%5 e%5 c%10
        """,
        Sfx.TRAINER_SPOTTED to H + """
            #inst 1 duty=0 env=13,-1
            #inst 2 duty=2 env=9,-1
            A @1 o5 b%4 >e%4 b%26
            B @2 o4 b%4 >e%4 g+%26
        """,
    ).mapValues { it.value.trimIndent() }

    private val cache = HashMap<Sfx, Song>()

    /** The parsed song for [sfx] (parsed once, thread-safe). */
    fun song(sfx: Sfx): Song = synchronized(cache) {
        cache.getOrPut(sfx) { MmlParser.parse(sources.getValue(sfx), "sfx_${sfx.id}") }
    }
}
