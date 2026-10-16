# PKS scripting

Event scripts live in `world/scripts/*.pks`. Each file holds any number of scripts:
```
script hometown_mom
  faceplayer
  if flag got_starter and not flag mom_gift
    say "MOM: Take this, {player}!"
    giveitem potion 3
    setflag mom_gift
  elif badges >= 4
    say "MOM: Four badges! I'm so proud."
  else
    say "MOM: The Professor wanted to see you."
  end
end
```
Comments start with `#`. Strings use double quotes; `\n` = new line in the same box, `\p` = new text box.
Placeholders: `{player}`, `{rival}`, `{money}`, `{var:name}`, `{item:id}`, `{mon:id}`, `{move:id}`.
Script ids are global; the compiler rejects duplicates and unknown references.

## Control flow
`if <cond>` / `elif <cond>` / `else` / `end`. Conditions combine with `and`, `or`, `not`:

| condition | meaning |
|---|---|
| `flag X` | story flag set |
| `result yes` / `no` / `win` / `lose` / `caught` / `defeated` / `ran` / `<n>` | result of the last ask/choice/battle |
| `badges >= N`, `badge N` | badge count / specific badge |
| `item X` or `item X >= N` | bag contents |
| `money >= N`, `var X op N` | numbers (ops: `>= <= > < == !=`) |
| `party has X`, `hasmon X`, `partysize op N` | party / PC |
| `time morning|day|night` | real-time clock |
| `caught X`, `seen X`, `caughtcount op N` | Pokédex |
| `trainer X` | trainer defeated |
| `gender m|f`, `visited <map>` | player info |
| `field surf` (cut, flash, rock_smash, strength, surf, fly, waterfall) | field move usable |
| `quest X started|done`, `quest X step op N` | quests |
| `gimmick mega|zmove|dynamax|tera` | gimmick unlocked |
| `random N` | 1-in-N chance |

## Commands
| command | effect |
|---|---|
| `say "text"` | dialogue box (blocks) |
| `ask "question?"` | yes/no → `result` |
| `choice "prompt" "A" "B" ...` | menu → `result` = index |
| `faceplayer`, `face <npc|player> <dir>`, `playerface <dir>` | turn |
| `move <npc|player> up2 left right3` | walk a path (blocks) |
| `wait <ms>`, `emote <npc|player> !|?|heart|...` | timing / emote balloon |
| `show <npc>` / `hide <npc>` | toggle NPC visibility (persist via `showif/hideif` flags) |
| `setflag X`, `clearflag X`, `setvar X N`, `addvar X N` | state |
| `giveitem X [n]`, `takeitem X [n]` (result=1 if taken), `tm <move>` | items |
| `givemoney N`, `takemoney N` (result=1 if paid) | money |
| `givemon <species> <level> [shiny] [item=] [ability=0|1|H] [moves=a,b] [nature=] [ivs=N] [gmax] [tera=Type] [nick=]` | gift Pokémon (result 1/0) |
| `giveegg <species> 1 [...]` | simplified egg (hatches immediately) |
| `battle <trainer> [canlose]` | trainer battle → `result win/lose` (without canlose, a loss blacks out) |
| `wildbattle <species> <level> [shiny] [norun] [ivs=N] [moves=] [item=] [music=]` | scripted wild/legendary battle → result caught/defeated/ran/lose |
| `heal`, `healcenter` (also sets respawn point) | heal party |
| `warp <map> <x> <y> [dir]`, `teleport`, `blackout` | movement |
| `music <id>`, `restoremusic`, `sfx <id>`, `cry <species>` | audio |
| `fadeout`, `fadein`, `flash`, `shake`, `weather <id|none>` | screen effects |
| `badge N` | award badge N |
| `shop <id>`, `pc`, `movetutor <id>`, `moverelearner`, `nickname`, `nameinput rival` | UI screens |
| `quest <id> start|next|done|<step>` | quest log |
| `unlock mega|zmove|dynamax|tera` | enable a battle gimmick |
| `setrespawn`, `save`, `fly`, `evolve`, `halloffame`, `credits`, `pokedex` | misc |
| `trade <want> <give> <level> [nick=] [item=] [ot=] [moves=]…` | in-game trade: player picks a party `<want>` (any forme) and receives `<give>` (OT = `ot`, default TRADER); trade evolutions trigger; `result` 1 if traded, 0 if declined / no match |
| `call <script>` / `return` | subroutine |
| `special <name> [args]` | engine hook for one-off features |
