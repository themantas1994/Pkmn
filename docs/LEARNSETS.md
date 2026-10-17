# Learnset legality and move availability

Pokémon Crystalline deliberately supports the whole National Dex (1025 species, 354 forms) and every move of the data set,
across generations, like the project's gimmick-inclusive "Crystalline" battle ruleset. This page defines which moves a
species may learn and how that is audited. The datasets are **community-maintained** (Pokémon Showdown data, MIT; PokeAPI
CSV data, BSD-3-Clause), not official game data; see `assets/data/manifest.json` and `data-src/SHA256SUMS` for the exact
sources, retrieval date and hashes (they are copied into every audit report).

## Crystalline move-availability policy

| Source | Rule |
|---|---|
| Level-up | The most recent generation's level-up list that exists for the species/form: Gen IX when the species is in Scarlet/Violet, otherwise its latest game. Level 0 = learned on evolution, level 1 = starting moves. Older generations' level-up moves are **not** merged in. |
| TM / TR / tutor | A move is machine-legal if the species **or a pre-evolution** can learn it by TM, TR, HM or tutor in **any** generation (`Progression.canLearnMachine`, also used by TM items and move tutors). Pairs that are legal only in older generations are counted separately in the report so the decision stays visible. |
| Egg moves | Recorded (`LearnsetData.egg`), but breeding is not implemented: out of scope. A world assignment that relies on one is a conflict. |
| Event-only (`S`), LGPE / Virtual Console (`V`), Dream World (`D`) | Not learnable in-game. |
| Form-required (`R`) | Showdown marks a form's own signature move (Rotom-Wash's Hydro Pump, Zacian-Crowned's Behemoth Blade, Necrozma fusions, Pikachu cosplay moves) with `R`. The importer **does not import these**, so they are unlearnable and are reported as `form-signature-not-imported`. |
| Forms | A form uses its own learnset when it has one **with a level-up list**; otherwise `Dex.learnset` falls back to `changesFrom`, then to the base species. Own machine/egg data of a form with an empty level-up list is discarded (reported as `own-data-discarded`). Forms that inherit are listed in the report. |
| Non-standard moves | Moves Showdown flags `Past`, `Unobtainable`, `LGPE`, `CAP`, `Custom`, `Gmax` or `Future` stay in the game; every species/move pair and every world assignment that depends on one is reported. |

World assignments (trainer teams, `givemon`/`wildbattle`/`trade`/`giveegg`, scheduled events) are legal when the move is in the
level-up list at or below the level, in a pre-evolution's level-up list, machine-legal, or learned on evolution (level 0).

## Running the audits

```
./gradlew :tools:auditLearnsets                        # reports/learnsets.json + reports/learnsets.md
./gradlew :engine:test --tests '*MoveExecutionTest*'   # writes engine/build/reports/move-execution.json
./gradlew :tools:auditMoves                            # merges it with BattleCoverage -> reports/moves-audit.{json,md}
./gradlew :tools:test --tests '*LearnsetAuditTest*'    # audit logic on synthetic data
```

`auditLearnsets` exits non-zero when there are error-severity findings (like `validateContent`); the reports are written either way.

### What `auditLearnsets` checks

* every species and form: own / inherited / missing learnset, empty level-up lists, duplicate or invalid move ids, levels outside 0..100, unsorted level-up lists (`PokemonFactory.defaultMoves` depends on the order);
* an independent, regex-based recomputation of `DataImporter.importLearnsets` from `data-src/showdown/learnsets.json`, compared with `assets/data/learnsets.json` (any difference is an `importer-discrepancy`), plus a count of the source codes the importer ignores;
* `PokemonFactory.defaultMoves` at levels 1, 5, 25, 50 and 100: at most four moves, valid ids, no duplicates, all legal;
* signature moves (learnable by exactly one evolutionary family), egg-only signature moves, forms that require a move, and moves nobody learns (broken down into Z/Max, event-only, form-signature and nonstandard);
* world legality of every trainer move, `givemon`/`wildbattle`/`trade`/`giveegg`/event `moves=`, with file and line;
* TMs in the world (map pickups, `tm <move>` scripts, shops) against the machine-legal moves of **obtainable** species (species in encounters, gifts, scripted battles, events and their evolutions), cross-checked against the game's own `Progression.canLearnMachine`;
* tutor lists, machine generations (Gen IX vs older-only), moves that depend on non-standard flags, and the dataset provenance.

Each unresolved conflict is recorded as `{species, move, issue, selectedValue, justification, source}` under `conflicts`
in `reports/learnsets.json`. Issue codes: `illegal-move`, `level-too-high`, `older-gen-level-up-only`, `egg-only`,
`event-only`, `form-signature-not-imported`, `tm-unlearnable`, `tutor-unlearnable`, `required-move-unlearnable`,
`importer-discrepancy:<field>`, `unknown-move`.

## Known gaps (state of the first audit)

See `reports/learnsets.md` for the current numbers. Highlights worth a decision:

* **Shop TMs are not TMs.** `MapCompilerTool.compileShops` runs `toId()` over every shop item, so `tm:watergun` becomes `tmwatergun`; the game only treats bag keys starting with `tm:` as TMs (`FieldItems.useOn`, `ShopScreen.price`, `BagScreen`). All 32 shop TM entries are affected (`shop-tm-prefix-lost`). Fix in the compiler: keep the `tm:` prefix.
* **`R` sources are dropped** by the importer: Rotom appliance forms, Pikachu cosplay forms, Necrozma fusions, Zacian/Zamazenta-Crowned and Shedinja lose their signature moves. Four Rotom-Wash trainer moves depend on this.
* **Level-too-high and older-generation-only trainer moves** (17 and 6): content to retune (raise the level, or change the move) or to bless by adding the move to the level-up data.
* **Egg-only trainer moves** (32): legal in the games but unobtainable here because breeding is out of scope.
* **Older-generation machine moves**: about 31.9k of the 71.6k machine-legal species/move pairs have no Gen IX TM/TR/tutor source; they are legal only under the "any generation" rule above.
* **Older-generation level-up moves**: about 1.3k species/move pairs are learnable by level-up only in older generations and are dropped by the "latest generation only" rule.
* Forms that inherit a learnset although their types differ from the base (Rotom forms, Arceus/Silvally types, Oricorio, Hoopa-Unbound, Necrozma fusions, ...) rely on the base species' movepool.
