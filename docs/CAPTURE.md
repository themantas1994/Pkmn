# Capture, Pokédex registration and nicknames

## The bug players reported

Wild Pokémon in the opening routes were caught by almost every Poké Ball, even at full HP.

**Root cause.** The species data was correct: the catch rates of all 1,025 base species match PokeAPI's `capture_rate`,
re-checked for this fix (0 mismatches). The formula itself was close to Scarlet/Violet's. The cause was a design
setting: Scarlet/Violet's low-level bonus, `L = (36 − 2 × level) / 10` for wild Pokémon below level 13. Scarlet/Violet
only meet such Pokémon in their first area. This campaign's whole first two hours (Route 1, Route 2, Whisperwood,
Brook Trail and so on) are level 2–8 and full of catch-rate-255 species. At level 2–4 the bonus alone pushes the final
rate to 255, which is a guaranteed capture. `./gradlew :tools:auditCapture` measures it for every encounter slot
([reports/capture.md](../reports/capture.md)). For example, a full-HP level-2 Pidgey went from 100% to 44% per Poké
Ball.

**Implementation errors found and fixed on the way:**

| Defect | Effect |
|---|---|
| No badge penalty | Over-levelled Pokémon were as easy to catch as any other (Scarlet/Violet multiply by 0.8 per missing badge) |
| Fractional arithmetic | The games use 1/4096 fixed-point steps; results could differ slightly |
| Timer Ball | Gave ×1.3 on the first turn (should be ×1.0, +1229/4096 per later turn) |
| Nest Ball | Gave ×1.1 at level 30 (should be ×1 from level 30) |
| Level Ball | Compared `user ≥ target × 4` instead of `floor(user / 4) ≥ target` |
| Dream Ball | Ignored Comatose |
| Dive/Lure Ball | Never worked while surfing or after a fishing bite (the battle used the map's environment) |
| Repeat Ball, critical captures | Read the Pokédex through a global mutable hook shared by every battle |
| Capture EXP | Catching gave no EXP or EVs (Generation VI+ awards both) |
| Full PC | A capture with every box full was silently lost |

## The rules (engine: `CatchCalc`, `CaptureRules`)

Scarlet/Violet (Generation IX):

```
X = (((3M − 2H) × G × C × B × BP) / 3M) × L × S × D        rounded to 1/4096 after each step, capped at 255
Y = floor(65536 / (255 / X)^(3/16))
```

- `X ≥ 255` always catches. Otherwise, up to four shake checks pass when a random number in 0..65535 is below `Y`.
  A critical capture makes one check.
- A critical capture happens when a random number in 0..255 is below `floor(min(255, X) × P × Ch / 6)`.
  `P` is 0 / 0.5 / 1 / 1.5 / 2 / 2.5 for more than 0 / 30 / 150 / 300 / 450 / 600 species caught; `Ch` is 2 with the
  Catching Charm.
- `G`: the dark-grass modifier, always 1 since Generation VI.
- `C`: the species catch rate. The Heavy Ball adds +30 / +20 / 0 / −20 by weight, with a minimum of 1.
- `S`: sleep or freeze 2.5; poison, paralysis or burn 1.5.
- `D`: out-of-battle bonuses (sandwiches, off-guard starts). These don't exist in this game, so D = 1.
- `BP`: ×0.8 for every badge missing to control a Pokémon of that level. Scarlet/Violet's table is 25, 30, 35, 40, 45,
  50, 55, 60 and 100 for 0–8 badges (`CampaignInfo.captureBadgeLevels`).
- `B` (balls):

| Ball | Multiplier |
|---|---|
| Great | 1.5 |
| Ultra | 2 |
| Net | 3.5 vs Water/Bug |
| Nest | (41 − level)/10 below level 30 |
| Dive | 3.5 while surfing |
| Lure | 4 while surfing or after a fishing bite |
| Repeat | 3.5 if already caught |
| Timer | 1 + 1229/4096 per turn after the first, maximum 4 |
| Quick | 5 on turn 1 |
| Dusk | 3 at night or in caves |
| Fast | 4 if base Speed ≥ 100 |
| Level | 8 / 4 / 2 |
| Love | 8 |
| Moon | 4 for Moon Stone evolvers |
| Dream | 4 if asleep or Comatose |
| Beast | 5 for Ultra Beasts, 410/4096 otherwise |
| Every other ball against an Ultra Beast | 410/4096 |
| Master | Skips the formula |

- `L`, the low-level bonus. **This is the one deliberate difference.** Scarlet/Violet apply it below level 13. Here
  it applies only on **Casual** difficulty (`GameContext.captureRules()`); Normal and Challenge leave it out.
  Everything else follows Scarlet/Violet on every difficulty.

**Other rules:**
- Trainer battles block every ball.
- Scripted encounters can be made uncatchable (`BattleRules.canCatch = false`).
- Balls can't be thrown when the party and all 16 boxes are full.
- Legendary encounters use their species catch rate (3 for most) and the same formula. Nothing makes them easier or
  harder.

**Sources and confidence:**

| Detail | Source | Confidence |
|---|---|---|
| Formula, fixed-point rounding, badge penalty, low-level bonus, critical captures, ball values | Cave of Dragonflies, [Gen IX capture mechanics](https://www.dragonflycave.com/mechanics/gen-ix-capturing/) and its calculator worker (retrieved 2026-10-17), from Anubis's reverse engineering of Scarlet/Violet | Community reverse engineering, not official. The most detailed public analysis |
| Formula shape, ball list | [Bulbapedia, Catch rate](https://bulbapedia.bulbagarden.net/wiki/Catch_rate) | Community reference. It agrees, but its page couldn't be fetched from the build machine (HTTP 403) |
| Catch rates | PokeAPI `pokemon_species.csv` (`data-src/`), cross-checked with Showdown species data | Community datasets |

No official Nintendo/The Pokémon Company document publishes the capture formula.

**Tests.** `CaptureTest` compares the engine with `CaptureReference`, an independent transcription of the published
calculator. The comparison covers:
- Exact `X` and success probability over 4,000+ combinations of species, levels, HP, balls, badges and the bonus
  setting.
- The twelve required scenarios: full, half and 1 HP; Great and Ultra Balls; every status; common, uncommon and
  legendary species; the Master Ball; the level bonus, badges and blocked throws; fail then retry; EXP exactly once.
- Monte Carlo runs of real throws, 5,000–6,000 each, within 4.5σ of the reference.

## After a successful capture

The engine resolves the throw, finishes the battle and records the caught Pokémon (`Battle.caughtMon`). The foe acts
no more. `GameContext.applyBattleResult` then calls `acquire`, the single path for every Pokémon the player obtains.
In one step, `acquire`:
1. Stores the Pokémon: the party if it has fewer than 6, otherwise the first free box slot.
2. Registers the species as caught, plus its exact form and whether it is shiny.
3. Queues a nickname offer. The offer is stored in the save with the Pokémon (`GameState.pendingNicknames`).

Calling it twice for the same individual (same `uid`) changes nothing.

The game saves immediately, then shows:
- **A new species:** "X's data was newly registered in the POKéDEX!", then the Pokédex entry: number, name, category,
  types, sprite, height, weight and description. A, B or Back closes it.
- **A repeat:** a one-line summary, which mentions a newly recorded form. The full entry stays available from the
  Pokédex menu.
- **The nickname offer** (below).
- **"X was sent to BOX n on the PC."** when the party was full.

It saves again when the sequence ends. If the app is killed anywhere in this sequence, the Pokémon and its Pokédex
entry are already saved, and the nickname offer is shown again on the next load (`OverworldScreen`, first update).

**Pokédex states** (`DexState`):
- `UNKNOWN`: never encountered.
- `SEEN`: encountered in a wild or trainer battle.
- `CAUGHT`: obtained by capture, gift, trade or evolution.

The game keeps `seen`/`caught` by base species id, plus `formsSeen`, `formsCaught` and `shiniesCaught` by exact
species/form id. Evolution registers the new species.

**Descriptions** come from PokeAPI's English Pokédex text: the Scarlet/Violet entry when there is one, otherwise the
most recent game's (`assets/data/dextext.json`, built by `:tools:importData`).

## Nicknames

Every Pokémon the player obtains gets the offer "Would you like to give X a nickname?": the starter, every capture
(duplicates and legendaries included) and gifts. The script option `givemon … nonick` skips it. NPC trades and
Shedinja never get it.

- **YES** opens name entry. B on an empty name returns to the question.
- **NO** keeps the species name.
- **Validation** (`Nicknames.check`):
  - At most 12 characters, the Generation VI+ limit.
  - Latin letters (including accented ones), digits, spaces and `. , - ' ! ? & ♂ ♀`.
  - NFC normalisation; surrounding and repeated spaces are trimmed.
  - Empty or invalid names are refused without changing anything.
  - A name equal to the species name is stored as no nickname.
- The nickname is separate from the species. The summary shows both, and battle, party and PC use the nickname.
  Evolution keeps it.
- The party menu's **NICKNAME** option renames or resets the name, only for Pokémon whose original trainer is the
  player.

## Saves

Save version 2. The new fields (`formsSeen`, `formsCaught`, `shiniesCaught`, `pendingNicknames`, `Pokemon.mintNature`)
default to empty or null.

On every load, `SaveValidator` also:
- gives every owned Pokémon a unique non-zero `uid`;
- registers every owned species and form as caught and seen;
- drops offers for Pokémon no longer owned.

It only adds records, never removes them. Nicknames, natures, IVs, EVs and moves are read as stored and never
regenerated. `AcquisitionTest` covers:
- the starter, YES/NO and invalid names;
- first and duplicate captures, legendaries, forms and shinies;
- a full party and full storage;
- evolution;
- save/load, an interrupted capture and the version-1 migration.
