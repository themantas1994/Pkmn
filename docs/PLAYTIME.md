# Playtime and difficulty review

Every figure here comes from `./gradlew :tools:estimatePlaytime`, which writes `reports/playtime.json`. That tool is a **content-derived model, not a measurement of human players**. The model and its constants are described in `tools/.../PlaytimeEstimator.kt`. No human playtest data exists yet.

## What was wrong

`STATUS.md` reported two problems:
- About 24 h to the Hall of Fame with optional content, against a target of about 30 h.
- "Normal is easy late in the game."

Before changing any balance, the estimator was extended with a sensitivity table (`levelSensitivity` in the report). It simulates the party's level on arrival at each boss under different experience rules, without grinding.

Party level minus the boss's ace level, gyms 1 to 8 and then the Champion:

| Rules | Gap per boss |
|---|---|
| Exp. Share **on**, critical path only | +4 +5 +5 +6 +7 +6 +8 +8 **+11** |
| Exp. Share **on**, path + optional trainers | +8 +10 +11 +13 +16 +17 +19 +22 **+23** |
| Exp. Share on, *no* ×1.5 trainer bonus, path + optional | +6 +8 +8 +9 +11 +12 +13 +16 +16 |
| Exp. Share **off**, path + optional trainers | +6 +6 +6 +5 +6 +5 +4 +5 **+3** |
| Exp. Share **off**, critical path only | +3 +2 +0 −1 −2 −4 −4 −7 **−7** |

### Findings

- **The cause is the party-wide Exp. Share combined with the optional content.** The three expansions added about 357 optional trainers. Under the modern share every party member earns from all of them, so a player who explores arrives 16–23 levels above the late bosses. The model's earlier "+9–11" figure counted critical-path EXP only, so it understated the problem.
- **The trainer-battle ×1.5 bonus is not the driver.** Removing it still leaves +16 at the end. The bonus is also unverified for Gen VII+ (see `docs/FIDELITY.md`), so it is unchanged.
- **Boss levels are not too low.** With Gold/Silver/Crystal-style experience (participants only), exploring players arrive 3–6 levels ahead: a classic margin. Raising boss levels would punish players who skip optional areas, who are already 7 levels behind by the end.

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

| Leg | Critical (min) | With optional (min) | Level, critical path only / with optional, vs ace | Grind (critical / optional) |
|---|---|---|---|---|
| Start → Gym 1 | 50 | 135 | 17 / 24 vs 14 | 0 / 0 |
| Gym 1 → Gym 2 | 65 | 147 | 23 / 30 vs 21 | 0 / 0 |
| Gym 2 → Gym 3 | 61 | 153 | 28 / 36 vs 28 | 0 / 0 |
| Gym 3 → Gym 4 | 55 | 136 | 33 / 42 vs 34 | 0 / 0 |
| Gym 4 → Gym 5 | 61 | 136 | 38 / 48 vs 40 | 0 / 0 |
| Gym 5 → Gym 6 | 56 | 121 | 44 / 53 vs 46 | 8 / 0 |
| Gym 6 → Gym 7 | 87 | 146 | 50 / 58 vs 52 | 4 / 0 |
| Gym 7 → Gym 8 | 94 | 179 | 56 / 64 vs 58 | 37 / 0 |
| Gym 8 → Hall of Fame | 162 | 178 | 64 / 70 vs 66 | 39 / 0 |

In hours:
- **Critical path:** 12.7 h (was 11.1 h). The extra time is the grinding a player needs if they skip all side content in the last legs. Grinding stays **optional**: exploring the side areas gives the same levels with no grinding.
- **With optional content:** 24.4 h (unchanged).
- **Post-game:** 4.5 h.

The "with optional" columns still sit 4–9 levels above the gym aces. That is a GSC-like margin, not the previous 16–23.

## Why the estimate is still below 30 h

Adding battles or walking to reach 30 h would be padding, and the brief rules it out. The remaining gap is mostly time the model does not count:

- **Puzzles, deaths and retries.** Ice and boulder puzzles, losses and their retries are covered only by a flat 10 % overhead.
- **Hidden items.** The world has 572 items, 363 of them gated by later badges (`reports/items.md`). Searching for them is not counted.
- **The weekly event.** The Thursday-night event and backtracking with new field moves are not counted.
- **Battle pace.** Battles now animate. Measured on desktop with fast text, auto-advance and normal animation speed, one turn that includes a faint and the next send-out takes about **10.5 s** (Gym 1 leader, 4 turns, `option animSpeed 0`). That matches the model's 40 s per trainer Pokémon for a two-to-three-turn fight, so the constants were left as they are.

**Recommendation.** Measure human playtime with a few testers before any further rebalancing. Battle animation speed and Exp. Share are player options, so the model's figures are a band, not a single number.
