# Mechanics checklist

Every core mechanic, its status, the ruleset it follows and the evidence. Machine-readable copy: [reports/mechanics-checklist.json](../reports/mechanics-checklist.json).

Status: **tested** = implemented with the named automated tests; **implemented** = in code, only indirectly tested; **simplified** = deliberate simplification; **excluded** = out of scope; **open** = known gap.

Totals: excluded 4, implemented 12, open 1, simplified 4, tested 43.

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
| Multi-hit moves, recoil, drain, healing, stat changes, secondary effects | tested | Gen IX | MoveExecutionTest (per-move audit), BattleCoreTest | See reports/moves-audit.md |
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
| Obedience | excluded | — | — | All Pokémon obey. The capture badge penalty still uses Scarlet/Violet's obedience table |

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

## Saves

| Mechanic | Status | Ruleset | Evidence | Note |
|---|---|---|---|---|
| Atomic, checksummed, versioned saves with backup recovery | tested | — | WorldTest save round trip, corruption and tamper tests |  |
| v1 -> v2 migration (Pokédex forms, nickname offers, unique ids); no rerolls on load | tested | — | AcquisitionTest migration |  |
| Interrupted capture resumes at the nickname prompt | tested | — | AcquisitionTest interruption |  |
