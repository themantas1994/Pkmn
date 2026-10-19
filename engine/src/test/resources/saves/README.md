# Save fixtures

Hand-authored saves in the exact on-disk form (compact envelope JSON, valid payload checksum) of each historical
save schema. No player data: the trainer, team and progress are invented.

| File | Version | Schema taken from | Differences from the next version |
|------|---------|-------------------|-----------------------------------|
| `v1.json` | 1 | `059df5b^` (`GameState.kt`, `Pokemon.kt`) | no `formsSeen`/`formsCaught`/`shiniesCaught`/`pendingNicknames`, no `mintNature`; uids are 0 |
| `v2.json` | 2 | `8020bf5^` | no `outsider`; bag holds a pre-fix shop TM key `tmthunderbolt` |
| `v3.json` | 3 | `8020bf5` onwards | current schema |

Optional nulls are omitted and defaults are written, as the game's `Json { encodeDefaults = true; explicitNulls = false }`
does. The checksum is SHA-256 of the compact payload. Never regenerate these from the current model: they exist to
test migrations against independent data. `SaveFixturesTest` covers them.
