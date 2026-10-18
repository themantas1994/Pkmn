# Mechanics checklist

Every core mechanic, its status, the ruleset it follows and the evidence. Machine-readable copy: [reports/mechanics-checklist.json](../reports/mechanics-checklist.json).

Status: **tested** = implemented with the named automated tests; **implemented** = in code, only indirectly tested; **simplified** = deliberate simplification; **excluded** = out of scope; **open** = known gap.

Totals: excluded 3, implemented 12, open 1, simplified 4, tested 50.

## Identity and creation

| Mechanic | Status | Ruleset | Evidence | Note |
|---|---|---|---|---|
| Six base stats, types, abilities, gender ratio, height/weight, growth rate, EV yield, base friendship | tested | Gen IX data (Showdown + PokeAPI) | ContentValidatorTest; :tools:validateContent (0 errors); EV yields 1-3 per base species checked | Community datasets, not official |
| Catch rate data | tested | Gen IX (PokeAPI capture_rate) | ContentValidator cross-check vs PokeAPI (0 mismatches over 1025 base species) |  |
| Species/form identity by id (Showdown-style ids), forms tracked separately | tested | — | AcquisitionTest forms; SaveValidator repair |  |
| Individual: level, EXP, nature, IVs, EVs, moves, ability slot, held item, nickname, OT, ball, met data, unique id | tested | Gen III+ | StatFormulaTest, AcquisitionTest save/load + migration |  |
| Natures (25, +10%/-10%, HP never affected, neutral natures) | tested | Gen III+ | StatFormulaTest: all 25 natures |  |
| Mints (stat nature separate from original nature) | tested | Gen VIII+ | StatFormulaTest mint test |  |
| IVs 0-31, random on creation; 3 perfect IVs for scripted legendaries/static encounters; fixed trainer IVs | tested | Gen III+ / Gen VI+ static | StatFormulaTest generation test |  |
| EVs: 252 per stat, 510 total, floor(EV/4) in stats | tested | Gen VI+ | StatFormulaTest |  |
| Shiny odds 1/4096 (Shiny Charm 3 rolls ≈ 1/1365); scripted shinies | implemented | Gen VI+ | GameContext.rollEncounter, ScriptVM buildMon | Shiny tracking in Pokédex tested in AcquisitionTest |
| Hidden abilities in the wild (5%) | simplified | Design | GameContext.rollEncounter | Scarlet/Violet normally give hidden abilities only through special encounters; 5% is a design choice |
| Wild held items | excluded | — | — | Wild Pokémon never hold items (no wild held-item dataset imported) |
| Markings, ribbons, Pokérus, contests | excluded | — | — | Out of scope for this campaign |

## Stats and battle calculations

| Mechanic | Status | Ruleset | Evidence | Note |
|---|---|---|---|---|
| Stat formula and integer rounding | tested | Gen III+ | StatFormulaTest (Bulbapedia Garchomp example + hand fixtures) |  |
| Damage formula, STAB 1.5/2.0, Tera STAB, type effectiveness, immunities, random 85-100% | tested | Gen IX | BattleCoreTest (hand-computed damage), BattleEdgeCaseTest |  |
| Critical hits (stages 1/24, 1/8, 1/2, always; x1.5; ignore stat drops) | tested | Gen VII+ | BattleEdgeCaseTest crits vs boosts |  |
| Priority, Trick Room, speed order | tested | Gen IX | BattleEdgeCaseTest priority/Trick Room |  |
| Accuracy/evasion stages, speed ties | implemented | Gen IX | BattleFuzzTest invariants only | No dedicated accuracy-stage test |
| Every move executed: 413 scripted + 524 data-driven moves tested, 14 with no singles effect, 0 unsupported (power, type, category, PP, accuracy, priority, damage, secondary effects, recoil, drain, healing, stat changes, multi-hit, Z/Max paths) | tested | Gen IX | MoveExecutionTest; reports/moves-audit.md | Scripted moves: data-derived checks plus ~60 move-specific checks; not every script detail is asserted |
| Move bugs found by the audit: Moonlight/Morning Sun/Synthesis/Shore Up healed nothing; duplicate script registrations (Spit Up, Knock Off, Wake-Up Slap, Smelling Salts, Fury Cutter, Burn Up, Double Shock, Steel Roller); Diamond Storm +4 Def; Plasma Fists | tested | Gen IX | MoveExecutionTest | Fixed |
| Major status: burn (1/16, halved physical damage), paralysis (25%, half Speed), sleep, poison, toxic | tested | Gen VII+ | BattleCoreTest burn/paralysis/sleep/toxic tests |  |
| Freeze (20% thaw per turn, thawed by Fire moves) | implemented | Gen VII+ | Conditions.kt frz | No dedicated test |
| Substitute, Perish Song, Sky Drop, Snatch, Illusion, Disguise | tested | Gen IX | BattleEdgeCaseTest |  |
| Confusion (1/3 self-hit, 2-5 turns), flinch, trapping | implemented | Gen VII+ | Conditions.kt; MoveExecutionTest runs the inflicting moves | Self-hit chance and duration not asserted by a dedicated test |
| Sandstorm, Stealth Rock, Gravity | tested | Gen IX | BattleEdgeCaseTest, BattleCoreTest |  |
| Other weather, terrains, screens, Spikes | implemented | Gen IX | BattleCoverage classification; MoveExecutionTest runs the moves |  |
| Abilities (318: 299 scripted, 19 no battle effect) | implemented | Gen IX | BattleCoverageTest classification; edge-case tests for many | Classification proves a handler exists, not that every interaction is tested |
| Held items in battle (371 implemented, 468 no battle effect) | implemented | Gen IX | BattleCoverageTest; edge-case tests (Focus Sash, Life Orb, Choice, Assault Vest...) |  |
| Switching, forced switching, replacement after faint, Shift/Set | tested | Gen IX / GSC style | engine+core BattleFlowTest |  |
| PP use, Struggle, PP Up/Max, Ethers/Elixirs | tested | Gen IX | BattleEdgeCaseTest Struggle; FieldItems |  |
| Mega Evolution, Z-Moves, Dynamax/Gigantamax, Terastallization, Primal, Ultra Burst; one gimmick per battle | tested | Crystalline unified ruleset | GimmickTest |  |
| Singles | tested | Gen IX | all battle tests |  |
| Double/triple battles | excluded | — | — | Singles only |
| Learnsets: 1379 species/forms (1135 own, 244 inherited, 0 missing); importer cross-checked against raw Showdown (0 discrepancies); world movesets legal (0 errors) | tested | Crystalline policy (docs/LEARNSETS.md) | LearnsetAuditTest; :tools:auditLearnsets; reports/learnsets.md | 32 trainer egg moves are documented conflicts (breeding out of scope) |
| Shop TMs usable after purchase | tested | — | AcquisitionTest shop TMs | Bug fixed: shop TMs lost their tm: key |

## Experience and growth

| Mechanic | Status | Ruleset | Evidence | Note |
|---|---|---|---|---|
| EXP formula (scaled, Gen V+), Lucky Egg, traded bonus | tested | Gen VII+ | BattleFlowTest exactly one EXP award per faint |  |
| Trainer-battle x1.5 EXP | open | Gen VII+ unverified | — | Kept from Gen VI; no primary source reachable (see docs/FIDELITY.md) |
| EXP and EVs for catching | tested | Gen VI+ | CaptureTest 12 |  |
| EV awards once per defeated Pokémon; Macho Brace, Power items; level-100 Pokémon still gain EVs | tested | Gen VII+ | StatFormulaTest EV tests; BattleFlowTest |  |
| Party-wide Exp. Share (option, off by default) | tested | Gen VI+ (option) | TimeAndEventsTest/options; PLAYTIME.md |  |
| Level-up stat recalculation keeping damage; move learning; four-move replacement prompt | tested | Gen IX | StatFormulaTest level-up; MoveLearnFlow (UI) |  |
| Level caps on Challenge difficulty | implemented | Design | GameContext.levelCap |  |
| Friendship: level-up, walking, vitamins, berries, fainting; friendship evolution at 160 | implemented | Gen VIII+ | Progression.evolutionTarget; EvolutionTest (Sylveon branch) |  |
| Vitamins (+10 to 252), feathers (+1), EV berries (-10) | tested | Gen VIII+ | StatFormulaTest |  |
| Badge obedience: Paldea level caps 20 + 5 per badge (0-7 badges), all obey with 8; reference level = met level (caught) or received level (gifts, trades, starters, fossils); Gen III/IV disobedience check (other move, nap, confusion self-hit, nothing); player Pokémon in wild and trainer battles only | tested | Gen IX caps and reference level; Gen III/IV check (Bulbapedia, Obedience) | ObedienceTest (caps for 0-8 badges at cap-1/cap/cap+1, formula, every outcome, Snore/Sleep Talk, charge/Bide skip, Vital Spirit/Insomnia/uproar/status, Encore/Rage, no-softlock fuzz); ObedienceCampaignTest (starter, gift, egg, trade, capture, evolution, Shedinja, badges mid-run); core BattleFlowTest | Gen V+ disobedience formulas are undocumented, so the documented Gen III/IV one is used. Separate from the capture badge penalty table (docs/CAPTURE.md). Details: [Obedience](#obedience) |

## Evolution

| Mechanic | Status | Ruleset | Evidence | Note |
|---|---|---|---|---|
| Level, item, friendship, time, move, held item, gender, party, location, stat-comparison and special evolutions | tested | Gen IX | EvolutionTest; :tools:validateContent evolution coverage (reports/coverage-evolutions.json) |  |
| Trade evolutions via Linking Cord (offline substitute) | tested | Legends: Arceus item | EvolutionTest Linking Cord |  |
| Branching (Wurmple, Toxel by original nature, Burmy cloak...) | tested | Gen IX | EvolutionTest branch selection |  |
| Cancel with B, Everstone | implemented | Gen IX | EvolutionScreen; EvolutionTest Everstone |  |
| Evolution keeps nickname, nature, IVs, EVs; registers the new species and shows its Pokédex entry | tested | Gen IX | AcquisitionTest evolution |  |
| Unreachable evolutions (12 regional, Vivillon Fancy, Kubfu) | simplified | Design | validateContent warnings (documented in STATUS.md) |  |

## Capture and acquisition

| Mechanic | Status | Ruleset | Evidence | Note |
|---|---|---|---|---|
| Capture formula (fixed-point), balls, status, badge penalty, critical captures | tested | Gen IX (reverse-engineered) | CaptureTest vs CaptureReference; reports/capture.md |  |
| Low-level capture bonus | simplified | Gen IX, Casual only | CaptureTest 10 | Deliberate: off on Normal/Challenge (docs/CAPTURE.md) |
| Trainer battles block balls; uncatchable scripted encounters; full storage blocks balls | tested | Gen IX | CaptureTest 10; AcquisitionTest storage |  |
| Pokédex seen/caught by species id, forms, shinies; entry on first registration | tested | Gen IX | AcquisitionTest |  |
| Nickname offer: starter, captures (incl. duplicates/legendaries), gifts; validation; rename/reset | tested | Gen VI+ (12 chars) | AcquisitionTest; scenes capture_flow/starter_nickname |  |
| Party of 6, PC with 16x30 boxes | tested | Gen IX | AcquisitionTest full party |  |
| In-game trades (OT is the NPC, no nickname offer) | tested | Gen IX | WorldTest trades |  |
| Eggs/breeding | simplified | Design | ScriptVM giveegg | Gift eggs hatch immediately at level 1; no Day Care or breeding |

## Items, progression, world

| Mechanic | Status | Ruleset | Evidence | Note |
|---|---|---|---|---|
| Healing and status items in and out of battle | implemented | Gen IX | FieldItems, BagItemEffects | EV items and Mints are tested (StatFormulaTest); Potions etc. only in play-throughs |
| Held item give/take; consumed items stay consumed; stolen items | implemented | Gen IX | Battle.cleanupAfterBattle |  |
| Duplicate item/reward prevention (flags), one-time gifts | tested | — | WorldTest every script yes/no path; ItemAuditTest |  |
| Badges, gym order, field moves gated by badges | tested | Design | checkProgression; StrictReachTest |  |
| Day/night, weekly events, time-dependent encounters and evolutions | tested | GSC-style clock | TimeAndEventsTest |  |
| Healing at Pokémon Centers, blackout | tested | Gen IX | WorldTest (every script run on its yes/no paths) |  |
| Trainer prize = class payout × highest trainer level (or `prize` override); defeat penalty = 4 × (1 + badges, max 8) × highest party level, capped at half the wallet; applied once per battle; none for scripted can-lose battles; wild wins pay nothing | tested | Crystalline policy (GSC halved the whole wallet, which dwarfed the 12–32/level prizes) | EconomyTest | Rewards and penalty scale with level; the badge factor is the only progression term |
| Trainers spot the player at any distance while the trainer is on screen (camera rectangle) with an unobstructed, walkable straight line in its facing direction; `sight 0` never challenges | tested | Design | TrainerSightTest (viewport edge, walls, obstacles, terrain) | On-screen walking/battle start is not covered by unit tests (needs the LibGDX screen) |

## Saves

| Mechanic | Status | Ruleset | Evidence | Note |
|---|---|---|---|---|
| Atomic, checksummed, versioned saves with backup recovery | tested | — | WorldTest save round trip, corruption and tamper tests |  |
| v1 -> v2 migration (Pokédex forms, nickname offers, unique ids); no rerolls on load | tested | — | AcquisitionTest migration |  |
| v2 -> v3 migration (obedience): outsider flag added as false; saved met levels kept; a missing met level becomes the current level; a met level above the current level is repaired | tested | — | ObedienceCampaignTest migration and repair tests |  |
| Interrupted capture resumes at the nickname prompt | tested | — | AcquisitionTest interruption |  |

## Obedience

Source: Bulbapedia, [Obedience](https://bulbapedia.bulbagarden.net/wiki/Obedience) (wikitext read 2026-10-18).
Code: `engine/src/main/kotlin/pkmn/engine/battle/Obedience.kt`; the check runs in `Battle.runMoveAction`.

**Reference level.** Since Generation IX the game compares the Pokémon's *met level* if the player caught it, or the
level at which it was *received* if it is an outsider (gift, in-game trade, event), never its current level. This game
creates every received Pokémon when the player receives it, so `Pokemon.metLevel` stores the right level in both cases;
`Pokemon.outsider` records which case applies.

| How the Pokémon was obtained | `outsider` | Reference level (`metLevel`) |
|---|---|---|
| Starter (received from the professor as a gift) | true | received level (5): always obeys |
| `givemon` gift, fossil revival | true | received level |
| In-game trade (`trade`) | true | received level |
| Wild capture, including scripted and event encounters | false | level when caught |
| Gift egg (`giveegg`, hatches immediately) | false | 1 |
| Evolution, including Shedinja's split | unchanged | unchanged |

**Level cap by badge count** (Paldea table; a Pokémon whose reference level is at or below the cap always obeys):

| Badges | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|---|
| Highest obedient level | 20 | 25 | 30 | 35 | 40 | 45 | 50 | 55 | all obey |

This is not the capture badge-penalty table (25-60 and 100, docs/CAPTURE.md), which comes from a different
Scarlet/Violet rule.

**Where it applies.** The player's Pokémon in battles that award experience: every wild and trainer battle. The game has
no link or facility battles. With 8 badges (the end of the main story) the check is off.

**Disobedience check** (Generation III/IV formula; Generation V+ formulas are not documented). L is the reference level,
C the cap, and each R is a random integer 0-255 from the battle's seeded RNG. Integer division throughout.

1. (L + C) × R1 / 256 < C: obeys.
2. Otherwise it disobeys. If it is asleep and was told to use Snore or Sleep Talk: "X ignored orders while asleep!"
3. (L + C) × R2 / 256 < C: "X ignored orders!" and it uses another move it can select (PP left, not disabled). PP is
   spent on the move it uses. With no such move it does nothing.
4. R3 < L − C: "X began to nap!" (sleep). Not if it already has a major status, an uproar is active, or its ability is
   Vital Spirit or Insomnia; a blocked nap falls through to the next step, as in the Generation III code.
5. R3 < 2 × (L − C): "X won't obey!" / "It hurt itself in its confusion!" (40-power typeless physical self-hit).
6. Otherwise it does nothing: one of "X is loafing around!", "X won't obey!", "X turned away!",
   "X pretended not to notice!".

The check runs after the usual can-it-move checks (sleep, freeze, paralysis, flinch, confusion) and before PP is spent.
It is skipped while the Pokémon is locked into a move: the second turn of a charging move, Bide, Outrage-style
rampages, Rollout, Uproar and recharging. Disobeying ends Encore and Rage. A Z-Move that is not used is not spent.

