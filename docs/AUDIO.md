# Audio

All music and sound effects in Pokémon Crystalline are **original** and are **synthesised at runtime**.
The APK ships only small text files (`assets/audio/music/*.mml`, about 50 KB in total) and
no recorded audio. Everything is CC0-1.0 (see `assets/ATTRIBUTION.json`).

Code lives in `core/src/main/kotlin/pkmn/game/audio/`:

| Class | Role |
|---|---|
| `ChipSynth` | Pure-Kotlin 4-channel GBC-style synthesizer (no LibGDX types). Renders 16-bit PCM, mono or stereo, at any rate from 8 to 96 kHz (22050 and 32000 Hz are the intended rates). |
| `MmlParser` → `Song` | Parses the song format below. Errors carry `id:line:col`. |
| `SongPlayer` | Sequencer: drives a `ChipSynth` from a `Song`. Looping songs wrap without a gap. |
| `Sfx`, `SoundEffects` | Enum of effect ids, and their procedural definitions (small MML songs embedded in Kotlin). |
| `AudioEngine` | LibGDX playback on a daemon thread: music with fades, jingles, and 2 SFX slots. |

## Synth

* **Pulse 1/2** (MML channels `A`, `B`): duty 12.5/25/50/75 %, band-limited with PolyBLEP.
* **Wave** (`C`): 32-sample, 4-bit wavetables. Built-in tables: 0 triangle, 1 saw, 2 square, 3 sine,
  4 organ, 5 thin pulse, 6 rounded square (punchy bass), 7 bright bass. A song can define more with `#wave`.
* **Noise** (`D`): Game Boy 15-bit or 7-bit LFSR. Drums are short programs of steps (see `#drum`).
* Each channel has a GB volume envelope (0–15, one step every n/64 s), an optional release envelope,
  vibrato (delay, depth in cents, rate in Hz), pitch slides and sweeps, panning, and a gain. A master
  volume and a 20 Hz DC-blocking high-pass (like the GB output capacitor) are applied after mixing. A
  soft knee above 0.9 full scale keeps the output from wrapping.
* Envelopes, vibrato and slides update at 256 Hz. Rendering allocates nothing per sample, and the output
  does not depend on the render block size.
* Throughput on the desktop JVM (`ChipSynthTest`) is about 21 M stereo frames/s, roughly 960× real
  time at 22050 Hz with all four channels active. That is about 0.1 % of one desktop core.

## Engine API

```kotlin
val audio = AudioEngine(loader = { id -> Gdx.files.internal("audio/music/$id.mml").readString() })
audio.playMusic("route_early")          // fade = 300 ms; no-op if this track is already playing
audio.playMusic("battle_wild", fadeMs = 0)
audio.playJingle("jingle_item")         // pauses the music, plays once, then fades the music back in
audio.playSfx(Sfx.MENU_SELECT)          // up to 2 at once; the same effect restarts, else the oldest is replaced
audio.stopMusic(500)
audio.setMusicVolume(0.8f); audio.setSfxVolume(1f); audio.muted = true
audio.pause(); audio.resume()           // Android onPause/onResume, audio focus
audio.dispose()
```

Every call only queues a command, so none of them blocks the render thread. The audio thread renders
512-frame blocks and makes blocking writes to `Gdx.audio.newAudioDevice`. A song that is missing or
fails to parse is logged and plays as silence. If no audio device can be created, the engine runs
silently. For tests, pass a custom `PcmOutput` and `startThread = false`, then call `renderBlock()`.
Music plays at `MUSIC_BUS_GAIN` (0.7) relative to SFX.

## Song format (`.mml`)

A song is a text file. `;` starts a comment that runs to the end of the line.

### Directives

| Directive | Meaning |
|---|---|
| `#title Name` | Display title |
| `#tempo 132` | BPM (quarter notes), 20–400 |
| `#time 4/4` | Time signature, used for bar checks (6/8 → 144 ticks per bar) |
| `#key D major` | Declared key(s), comma-separated. Modes: major, minor, dorian, phrygian, lydian, mixolydian, aeolian. The tests check the notes against them. |
| `#inst n key=val…` | Instrument `n` (0–63). Keys: `duty=0..3`, `wave=0..15`, `env=vol,step` (step −7..7; negative = decay, 0 = hold), `rel=-7..0` (envelope after note-off; 0 = cut), `vib=delayFrames,depthCents,rateHz`, `pan=l\|c\|r`, `slide=semitones/s` (portamento speed), `gain=x` |
| `#wave n HEX32` | Wavetable `n` from 32 hex digits |
| `#drum X steps` | Drum `X` (an uppercase letter). Steps are separated by `\|` and use `f=` LFSR clock in Hz, `v=` volume, `e=` envelope step, `w=1` for 7-bit noise, `n=` frames (1/64 s), `sw=` sweep in semitones per frame. Built-in kit: K kick, S snare, H closed hat, O open hat, C crash, T/M/U toms, R rim, P clap, X ghost snare, N rising sweep, Z falling sweep. |
| `#def name mml…` | Macro, used as `{name}` inside channel text |
| `#barcheck on\|off` | Turns `\|` bar checks on or off |
| `#gain x` | Per-song output gain |

### Channel lines

A channel line starts with `A`, `B`, `C` or `D` followed by a space. Lines for the same channel are
joined in order.

| Token | Meaning |
|---|---|
| `c d e f g a b` | Note. Add `+`/`#` for sharp or `-` for flat, then an optional length: `4`, `8.`, `16`, `%5` (ticks), `c4&16` (tie), `c4&d4` (slur: no retrigger) |
| `r` | Rest, with the same length syntax |
| `K S H …` | Drums. Channel `D` only, using the same length syntax. |
| `o4` `>` `<` | Set the octave (C4 = MIDI 60), or move up or down one octave |
| `l8` | Default length |
| `@n` | Select instrument (resets `v`/`w`/`p` overrides) |
| `v12` | Volume 0–15 (on `D` it scales the drum volumes) |
| `w2` | Duty on `A`/`B`, wavetable on `C` |
| `pl pc pr` | Pan |
| `q6` | Gate in eighths of the note length (8 = legato) |
| `k-3` | Transpose in semitones. Used with macros to reuse one arpeggio cell for every chord. |
| `j12` | Pitch sweep over each following note, in semitones (`j0` = off). Used in SFX. |
| `/c4` | Portamento from the previous note |
| `t150` | Tempo change |
| `[ … : … ]3` | Repeat 3 times. On the last pass, the part after `:` is skipped. Repeats can be nested. |
| `$` | Loop point |
| `\|` | Bar check. Fails with `line:col` if not on a bar boundary. |
| `{name}` | Expand a macro |

A tick is 1/48 of a quarter note, so a whole note is 192 ticks. Every channel that has notes must
have the same total length, and if the song loops, `$` must fall on the same tick in every channel.
The parser reports a violation with each channel's length. A song without `$` is a one-shot (all
jingles and SFX). Octave, transpose and length state are resolved when the song is parsed, so a loop
always replays exactly what played the first time.

```mml
#tempo 120
#inst 1 duty=2 env=11,-4 rel=-2 vib=16,12,5.5
#def arp o4 c8 g8 e8 g8 c8 g8 e8 g8
A @1 o5 $ e4 g4 a4. g8 | f2 e2 |
B $ k0{arp} | k5{arp} | k0
C o2 $ c2 g2 | f2 c2 |
D $ [K8 H8 S8 H8]4
```

## Soundtrack

42 looping tracks and 6 jingles, about 26.5 minutes of loop material in total. Each track has its
own key, tempo and groove. `battle_rival` reuses the opening phrase of `rival_theme`, and
`ending_credits` brings back the title melody.

| id | key | BPM | loop (s) |
|---|---|---|---|
| title | D major | 132 | 43.6 (+3.6 intro) |
| intro_professor | F major | 100 | 38.4 |
| hometown | G major, 3/4 | 92 | 47.0 |
| town_coastal | A major | 108 | 35.6 |
| town_mountain | D dorian, 6/8 | 132 | 32.7 |
| city_big | Bb major | 124 | 46.5 |
| city_night | C minor shuffle | 88 | 43.6 |
| route_early | C major | 144 | 40.0 |
| route_mid | Eb major gallop | 136 | 42.4 |
| route_late | B minor | 150 | 38.4 |
| forest | E minor | 100 | 38.4 |
| cave | C# minor (echo) | 80 | 48.0 |
| ocean_surf | F major, 6/8 | 120 | 36.0 |
| bike | G major | 160 | 36.0 |
| pokecenter | Eb major | 104 | 36.9 |
| mart | F major | 120 | 32.0 |
| gym | A minor march | 128 | 45.0 |
| gym_sprout | G major / lydian | 112 | 34.3 |
| gym_tide | E major, 6/8 | 104 | 34.6 |
| gym_spark | E dorian | 164 | 35.1 |
| gym_stone | F minor | 96 | 40.0 |
| gym_mist | A lydian (echo) | 76 | 37.9 |
| gym_ember | G phrygian | 152 | 37.9 |
| gym_frost | Ab major | 108 | 35.6 |
| gym_dusk | F# minor | 72 | 40.0 |
| villain_hideout | D minor | 120 | 48.0 |
| rival_theme | E minor | 152 | 37.9 |
| battle_wild | A minor | 168 | 34.3 |
| battle_trainer | C minor | 172 | 33.5 |
| battle_gym_leader | D minor | 176 | 32.7 |
| battle_rival | E minor | 180 | 32.0 |
| battle_villain_boss | F# minor | 170 | 33.9 |
| battle_legendary | B minor | 150 | 38.4 |
| battle_elite_four | G minor | 178 | 32.4 |
| battle_champion | C minor / Eb major | 184 | 41.7 |
| victory_wild | G major | 140 | 13.7 |
| victory_trainer | C major | 136 | 28.2 |
| victory_gym | F major | 128 | 30.0 |
| league_hall | Eb major | 76 | 37.9 |
| ruins | E phrygian | 70 | 41.1 |
| ending_credits | D major | 112 | 68.6 |
| evolution | A minor | 120 | 32.0 |

Jingles (one-shots, 3.0–7.3 s): `jingle_heal`, `jingle_item`, `jingle_badge`, `jingle_levelup`, `jingle_caught`, `jingle_save`.

## Map music inventory

Every map `music` line (in `world/maps/*.map`) names one of the tracks above; `:tools:validateContent` fails with
`music-missing` if a map, trainer or trainer class references an id without `assets/audio/music/<id>.mml`
(`ContentValidator.silentMusicIds` is the explicit allow-list for intentional silence, empty today).

| Map group | Track |
|---|---|
| Gym maps, one theme each | Brindle `gym_sprout`, Tidewater `gym_tide`, Voltara `gym_spark`, Quarrytown `gym_stone`, Seraphine `gym_mist`, Ashfall `gym_ember`, Frostmere `gym_frost`, Dragonspire (1F and 2F) `gym_dusk` |
| Poké Centers | `pokecenter` |
| Poké Marts, Voltara Dept. Store | `mart` |
| Home towns and villages: Brindle (houses, bike shop, florist), Fernwick, Lumen Village, Seraphine, Frostmere houses, Hollis's Lodge, cabins and cottages (Angler, Berry, Ranger, Lineman's Hut) | `hometown` |
| Mining and hill towns: Quarrytown, Ashfall houses/spa/glass workshop, Frostmere Town | `town_mountain` |
| Seaside: Port Tidewater, Tidewater houses, Lighthouse, Sea Cottage | `town_coastal` |
| Big cities: Brindle City, Ashfall City, Voltara City | `city_big` |
| Voltara houses, Ridge Station | `city_night` |
| Ruins and towers: Lumen Ruins, Mural Chamber, Catacombs, Lantern Tower, Ruins of Sol and Temple, Ember Tower | `ruins` |
| Caves: Crystal Cavern, Mt. Prism | `cave` |
| Hostile or industrial interiors: S.S. Driftwood, Power Plant, Harbor Warehouse | `villain_hideout` |
| Route 11 (sea) | `ocean_surf` |
| Route 12 (snow) | `route_late` |

The generic `gym` track is no longer used by a gym map; it stays as the default for any future gym-tagged map. Gym leaders
battle to `battle_gym_leader` (the `leader` trainer class music): there is no per-gym battle track mechanism.

Other maps keep their existing tracks (`route_early`, `route_mid`, `route_late`, `forest`, `ocean_surf`, `league_hall`, `ruins`, `cave`, ...).

### Evolution music

`EvolutionScreen` plays `evolution` through `MusicLease`, which remembers the track that was playing and puts it back on
every exit path (finish, cancel, stack reset). Back-to-back evolutions share one lease so the theme is not restarted, and the
previous track is restored once afterwards (skipped if a script has started other music meanwhile).

## Species cries

The synthesised effects contain no recorded audio, and public builds still play the generic blip (`cry` is an alias of
`Sfx.STATUS_INFLICTED`) for every species. In a **personal build** with locally imported official cries
(`docs/ASSET_IMPORT.md`, "Cries") each species plays its own recording:

```kotlin
game.audio.playCry("charizardmegax")   // GameAudio API; NoAudio and tests keep the default (the generic blip)
```

* `CryResolver` looks for `<speciesId>.ogg` then `.mp3` in `<importedAssetsDir>/audio/cries` (desktop `assets-local/`, Android
  `imported/`, where a personal APK unpacks its bundled cries), then repeats for the base species (form fallback).
  `PkmnGame.attachDexToAssets` wires it up once the Dex has loaded.
* `CryBank` plays one cry at a time, but a new cry cuts the previous one only once it is known to have started (an unloaded
  replacement never silences a playing cry), keeps a small LRU of decoded `Sound`s (never evicting the playing or pending one),
  releases them in `dispose`, and stops retrying a file whose load failed twice. Android decodes sounds asynchronously: battles
  prefetch their Pokémon on entry and scheduled-event Pokémon are prefetched when they appear; any other cry (script `cry`,
  event intro) whose sound is still loading is retried once per frame (`GameAudio.update`) for up to 600 ms before the generic
  blip plays, so the render thread never blocks. `SynthAudio` plays at `sfx volume * 0.6` (recordings are mastered louder than the chiptune effects), nothing
  when muted or when SFX volume is 0, and stops the cry on `pause`.
* A species without a file falls back to the generic blip. `CryBank.missCount` / `missedSpecies` count those, and the first miss
  per species is logged (`no cry file for '<id>'`). The import coverage report does not count such species as covered.
* Call sites: `ScriptRequest.Cry` (`ScriptHost`), the event encounter intro (`OverworldScreen`), and the wild appearance and
  send-out in `BattleScreen`.

## Verification

`./gradlew :core:test` runs every audio test. None of them needs an audio device.

* `ChipSynthTest`: pitch accuracy at 22050 and 32000 Hz, duty ratios, envelope timing, release,
  panning, slides, headroom and throughput.
* `MmlParserTest`: lengths, ties, slurs, repeats with `:`, macros, drums, loop points, bar checks,
  and error positions.
* `SongPlayerTest`: tempo timing, seamless loop wrap, one-shot end, and identical output for any
  block size.
* `AudioEngineTest`: missing or invalid songs play silence, fades, the SFX slot limit, volume and
  mute, jingle interruption, and threaded streaming with pause and resume.
* `SoundtrackTest`: renders every song and SFX to WAV in `core/build/audio-render/` (scratch, not
  committed). For each song it checks:
  * the song parses;
  * the loop length is within bounds (30–90 s; `victory_*` may be shorter; jingles 1–10 s);
  * the loop point is valid and audio continues after the wrap;
  * RMS is above −30 dBFS and less than 0.1 % of samples are near clipping;
  * every note is within the channel's range (pulse G2–E7, wave E1–E5);
  * at least 85 % of note time is in the declared key;
  * a chroma analysis of the rendered WAV puts at least 70 % of its energy in the key, with the
    tonic among the top 5 pitch classes.

## Limitations

* Nobody has listened to the music. Its musical quality rests on how it was built (diatonic
  harmony, 4- and 8-bar phrases, accompaniment built from chord tones) and on the automated
  analysis above.
* Pitch is equal-tempered floating point, not quantised to the GB's 11-bit frequency registers.
  Effects mix over the music rather than taking over its channels as on real hardware.
* The noise channel samples at most 64 LFSR clocks per output sample, so the highest-frequency
  noise is slightly darker than on hardware.
