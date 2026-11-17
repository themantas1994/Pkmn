# Playtime and difficulty review

Every figure here comes from `./gradlew :tools:estimatePlaytime`, which writes `reports/playtime.json`. That tool is a **content-derived model, not a measurement of human players**. The model and its constants are described in `tools/.../PlaytimeEstimator.kt`. No human playtest data exists yet.

## What was wrong

`STATUS.md` reported two problems:
- About 24 h to the Hall of Fame with optional content, against a target of about 30 h.
- "Normal is easy late in the game."

Before changing any balance, the estimator was extended with a sensitivity table (`levelSensitivity` in the report). It simulates the party's level on arrival at each boss under different experience rules, without grinding.

Party level minus the boss's ace level, gyms 1 to 8 and then the Champion. "Path + optional" is the one definition of "with optional" used everywhere: the critical path plus every optional trainer and optional wild battle of the leg.

| Rules | Gap per boss |
|---|---|
| Exp. Share **on**, critical path only | +4 +6 +7 +7 +9 +8 +9 +8 **+15** |
| Exp. Share **on**, path + optional | +13 +13 +14 +15 +19 +20 +21 +21 **+26** |
| Exp. Share on, *no* ×1.5 trainer bonus, path + optional | +12 +12 +11 +12 +15 +15 +16 +15 **+19** |
| Exp. Share **off**, path + optional | +10 +9 +8 +7 +8 +7 +6 +4 **+5** |
| Exp. Share **off**, critical path only | +3 +2 +2 +0 +0 −3 −3 −6 **−4** |

### Findings

- **The cause is the party-wide Exp. Share combined with the optional content.** The three expansions added about 357 optional trainers. Under the modern share every party member earns from all of them, so a player who explores arrives 13–26 levels above the late bosses. The model's earlier "+9–11" figure counted critical-path EXP only, so it understated the problem.
- **The trainer-battle ×1.5 bonus is not the driver.** Removing it still leaves +19 at the end. The bonus is also unverified for Gen VII+ (see `docs/FIDELITY.md`), so it is unchanged.
- **Boss levels are not too low.** With Gold/Silver/Crystal-style experience (participants only), exploring players arrive 4–10 levels ahead: a classic margin. Raising boss levels would punish players who skip optional areas, who are already 7 levels behind by the end.

## What changed

1. **Exp. Share defaults to off for new players** (`Options.expShare`).
   - With it off, only Pokémon that took part in the battle earn experience, as in GSC without the held Exp. Share.
   - The option and the bag toggle remain. The Exp. Share key item (Fernwick aide's gift) is a switch for the same
     option, not a held item. On, every healthy party member earns experience: participants in full, the others half
     (`Battle.awardExp`); the estimator's `shareFactor` models exactly that.
   - **Existing players are not affected.** Options files written before this change never stored the old default, and `Options.decodeLegacyAware` treats a missing field as the old "on". Options files now always store every field. Tests: `TimeAndEventsTest > exp share default changes only for new players`.
2. **The estimator models what a player does.**
   - The critical-path estimate grinds a party that only did the critical path.
   - The with-optional estimate uses a party that also earned the optional EXP, so it no longer adds grinding that player would never need.
3. **No trainer levels, teams or encounter rates were changed.**

## Result (model)

| Leg | Critical (min) | With optional (min) | Level, critical path only / with optional, vs ace | Grind min (critical / optional) |
|---|---|---|---|---|
| Start → Gym 1 | 54 | 137 | 17 / 24 vs 14 | 0 / 0 |
| Gym 1 → Gym 2 | 76 | 150 | 23 / 30 vs 21 | 0 / 0 |
| Gym 2 → Gym 3 | 80 | 155 | 30 / 36 vs 28 | 0 / 0 |
| Gym 3 → Gym 4 | 58 | 118 | 34 / 41 vs 34 | 0 / 0 |
| Gym 4 → Gym 5 | 79 | 166 | 40 / 48 vs 40 | 0 / 0 |
| Gym 5 → Gym 6 | 49 | 122 | 44 / 53 vs 46 | 1 / 0 |
| Gym 6 → Gym 7 | 88 | 147 | 50 / 58 vs 52 | 4 / 0 |
| Gym 7 → Gym 8 | 94 | 106 | 56 / 62 vs 58 | 37 / 0 |
| Gym 8 → Hall of Fame | 198 | 287 | 64 / 71 vs 66 | 0 / 0 |

In hours:
- **Critical path:** 14.2 h. It includes the detours the story requires (the map where a flag that opens a gate or guard on the way is set, e.g. the Power Plant, the Keystone and the Z-Ring), which the earlier shortest-route estimate left out. Grinding stays **optional**: exploring the side areas gives the same levels with no grinding.
- **With optional content:** 25.5 h.
- **Post-game:** 4.5 h.

The "with optional" columns sit 4–10 levels above the gym aces. That is a GSC-like margin, not the previous 13–26.

## Why the estimate is still below 30 h

Adding battles or walking to reach 30 h would be padding, and the brief rules it out. The remaining gap is mostly time the model does not count:

- **Puzzles, deaths and retries.** Ice and boulder puzzles, losses and their retries are covered only by a flat 10 % overhead.
- **Hidden items.** The world has 572 items, 363 of them gated by later badges (`reports/items.md`). Searching for them is not counted.
- **The weekly event.** The Thursday-night event and backtracking with new field moves are not counted.
- **Battle pace.** Battles now animate. Measured on desktop with fast text, auto-advance and normal animation speed, one turn that includes a faint and the next send-out takes about **10.5 s** (Gym 1 leader, 4 turns, `option animSpeed 0`). That matches the model's 40 s per trainer Pokémon for a two-to-three-turn fight, so the constants were left as they are.

**Recommendation.** Measure human playtime with a few testers before any further rebalancing. Battle animation speed and Exp. Share are player options, so the model's figures are a band, not a single number.
