# World source format

World content is authored as text under `world/` and compiled by `./gradlew :tools:compileMaps`
into `assets/maps/*.json`, `assets/world/*.json` and `assets/scripts/`. The compiler validates every
cross-reference (maps, warps, trainers, scripts, items, species, tiles) and writes `reports/world-compile.json`.
The build fails on errors.

Coordinates are tile coordinates, `x` to the right, `y` downward, origin at the top-left. Tiles are 16×16 px.

## `world/legend.txt`
Default character → tile-name mapping shared by all maps (`<char> <tile>` per line). Maps may override it.
Tile names come from `assets/gfx/tiles/world.json`. The special tile `water@auto` is autotiled into
`water`, `water_n/s/e/w`, outer corners `water_ne/nw/se/sw` and inner corners `water_ine/inw/ise/isw`.

## `world/prefabs.txt`
Available prefabs: `house`, `house_blue`, `house_big`, `lab`, `center` (Pokémon Center, door at 2,2),
`mart` (door at 1,2), `gym` (door at 2,3).

Multi-tile stamps (buildings). Rows list tile names; `_` keeps the underlying tile. `door x y` marks the
door offset used by `building ... -> target`.
```
prefab house
roof_l roof_m roof_m roof_r
wall_l wall_window door wall_r
door 2 1
end
```

## Map files `world/maps/<id>.map`
```
map route1                      # id (defaults to file name)
name "Route 1"                  # display name
location "Route 1"              # region-map location (interiors use their town's name)
music route_early
environment grass               # battle background: plain grass cave water sand snow indoor building
weather none                    # none rain snow sand fog
indoor                          # flag: interior (no weather, no fly)
dark                            # flag: needs Flash for full visibility
region 12 20                    # region map cell (8 px cells on the 256×224 region map)
fly 9 12                        # fly landing tile (towns/cities only)
encounters route1               # encounter table id (or none)
onenter route1_enter            # script run on every entry
tags powerspot fullmoon         # free-form tags (powerspot enables Dynamax; pokecenter; gym; ...)
connect north hometown 0        # seamless edge connection: direction, map, offset (tiles)

legend                          # optional per-map overrides
X rock
end

layout                          # one character per tile, rows of equal length
TTTTT::TTTT
T...,,::..T
end

building house 2 2 -> home_1f 3 7       # stamp prefab at x,y; door warps to map x y [facing]
building center 10 4 -> center_route1 4 7
tile 5 5 sign                           # single-tile override
warp 4 7 -> hometown 6 9 down           # warp tile (door/stairs/mat/cave entrance)
warp 4 7 -> @back                       # return to where the player entered this map from (shared interiors)
npc girl 6 8 sprite=lass face=down move=wander text="Hi!"   # move: static | look | wander | path:udlr
npc mom 3 4 sprite=mom script=home_mom hideif=some_flag showif=other_flag
trainer route1_joey 4 10 sprite=youngster face=right sight=4   # trainer id = npc id unless trainer=<id>
sign 3 4 text="ROUTE 1"                 # or script=<id>
item potion1 8 9 potion 1               # visible item ball (id is local to the map)
hidden ether1 12 3 ether                # hidden item (found by interacting)
trigger 9 0 2 1 script=leave_town unless=got_starter if=other_flag   # step-on trigger area x y w h
obstacle 5 5 cut flag=cut_route1_1      # cut | smash | boulder (removable obstacles)
```
Edge connections must be reciprocal (north of A = south of B with the negated offset).
Movement rules come from tile names (see `TileRules` in `engine/.../world/WorldModel.kt`): trees, walls,
roofs, counters etc. are solid; `tall_grass` triggers encounters; `water*` needs Surf; `ledge_s/e/w`
can be jumped in that direction; `door`, `stairs*`, `rug*`, `cave_entrance`, `cave_ladder` are warp tiles.

## Trainers `world/trainers/<file>.txt`
```
class youngster "Youngster" payout=16 music=battle_trainer portrait=youngster gender=m

trainer route1_joey
  class youngster
  name "Joey"
  ai 1                          # 0 random, 1 basic, 2 smart (switching), 3 boss
  intro "Hey! Let's battle!"
  defeat "Aw, man!"
  after "I'll train harder!"
  gimmicks mega                 # mega zmove dynamax tera ultraburst (dynamax only works on powerspot maps)
  items fullrestore=2 hyperpotion=1
  music battle_gym_leader
  prize 2000                    # optional override (default: class payout × highest level)
  mon rattata 4 moves=tackle,tailwhip item=oranberry ability=H nature=adamant ivs=31 evs=0,252,0,0,4,252 tera=Normal gmax shiny nick=Ratty
  mon pidgey 3
end
```
Omitted moves default to the last four level-up moves. Omitted IVs scale with level.

## Encounters `world/encounters/<file>.txt`
```
encounters route1
  rate grass 25                 # chance per step out of 256 (defaults: grass 25, cave 12, surf 10, rods 255)
  grass morning pidgey 2-4 30   # <method> <time> <species> <min-max> <weight>
  grass day sentret 2-3 30      # time: any | morning (4-10h) | day (10-18h) | night (18-4h)
  grass night hoothoot 2-4 30
  surf any tentacool 15-20 60
  oldrod any magikarp 5-10 100  # methods: grass cave surf oldrod goodrod superrod rocksmash headbutt
end
```

## Shops `world/shops.txt`
`shop <id> pokeball potion antidote superpotion=750 ...` (optional `=price` overrides; `tm:<move>` sells TMs).

## Quests `world/quests.txt`
```
quest main_story "The Crystal Bell" main
  desc "Discover what Team Eclipse wants with the old bell."
  step "Visit the Professor's lab."
  step "Earn the first badge."
end
```

## `world/campaign.json`
Title, region, start position, badge list (number, name, leader, city, type, levelCap) and settings.

## Scripts
See `docs/SCRIPTING.md`.
