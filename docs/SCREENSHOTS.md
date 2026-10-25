# Visual regression scenes

`tools/visual/run_scenes.sh` captures the key scenes in the **real desktop game** under Xvfb, then compares them with the baselines in this folder.
- **Capture:** each scene is a plain automation script in `desktop/automation/scenes/`. Runs use `--fixedstep`, so every frame advances exactly 1/60 s. Each scene also sets a fixed clock, so captures are reproducible: two consecutive runs matched 26/26 pixel-for-pixel.
- **Compare:** `tools screenshots` (`ScreenshotCompare.kt`) fails a scene when more than 1 % of its pixels change by more than 24 per channel.

```sh
tools/visual/run_scenes.sh            # capture to build/screenshots and compare; non-zero exit on a change
tools/visual/run_scenes.sh --update   # capture and replace the baselines after an intended change
```

This needs `xvfb-run`. It is not part of CI, because the GitHub runner has no Xvfb set up by this workflow; run it before changing the presentation.

| Scene script | Screenshots |
|---|---|
| `battle_six.txt` | `battle_intro_trainer`, `battle_ui_six`, `battle_shift_lead`, `battle_shift_prompt`, `battle_party_select`, `battle_next_foe` |
| `battle_one.txt` | `battle_ui_one`, `battle_last_foe_fainted`, `battle_victory` |
| `battle_status_{psn,brn,par,slp,frz}.txt` | `battle_status_{psn,brn,par,slp,frz}` (persistent status effect on the player's battler) |
| `battle_wild.txt` | `wild_ui`, `wild_catch_shake` |
| `capture_flow.txt` | `capture_registered`, `capture_dex_entry`, `capture_nickname_ask`, `capture_name_entry` |
| `starter_nickname.txt` | `starter_received`, `starter_nickname_ask` |
| `daynight.txt` | `town_morning`, `town_day`, `town_evening`, `town_night` |
| `event.txt` | `event_wednesday_night`, `event_thursday_night`, `event_intro`, `event_battle` |
| `entrances.txt` | `entrance_forest`, `entrance_mine_cave`, `entrance_ruins`, `entrance_tower`, `entrance_house`, `item_before`, `item_after` |

Automation commands are documented in `desktop/src/main/kotlin/pkmn/desktop/DesktopLauncher.kt`:
- `wait`, `press`, `mash`, `goto`, `log`, `perf`, `shot`, `quit`
- `party`, `trainer`, `wild`, `warp`, `option`, `clock`, `item`
- `waittop SCREEN`, `waitstate TEXT`: wait for a battle-screen state, such as `menu=ACTION` or `phase=DONE`.
