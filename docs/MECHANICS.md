# Mechanics & exact numbers

Everything below is the behaviour **0.3.0** ships with. Values are read from the code
(`com.worldplayers`), per resident unless stated otherwise. When in doubt, trust the code.

## Vitals

### Stamina
| | |
|---|---|
| Natural decay | −1 % every **4 min** while awake (not in Stand, not while sleeping) |
| Sleep recovery | **0.3 %/s**, wakes at **100 %** |
| Collapse | at **≤ 10 %** the resident goes back to bed |

### Mood
| | |
|---|---|
| Natural decay | −1 % every **30 s** while awake; **halved** while wandering; **none** in Stand or sleep |
| Entertainment area | **+0.5 % / 10 s** |
| Socialising (eye contact) | **+2 %** per partner, **40 s** cooldown |
| Sharing food | **+5 %** (both sides), 5 s cooldown |
| Natural wake | **+28 – 32 %** |
| Movement | three stages (green / yellow / red) |

### Hunger
| | |
|---|---|
| Full bar | **100 % = 20 points** |
| Walking | **0.05625** per block |
| Working | **0.09375** per second |
| Raw meat | refused (vanilla raw meats + `#c:foods/raw_meat` / `#c:raw_meats` / `#worldplayers:raw_meat`) |
| Food tiers | normal food first, crops last; chili blacklisted |

## Labour pacing

- Cooldown between work actions is picked from the **lower** of mood and stamina
  (also limited by hunger):

| mood & stamina | cooldown |
|---|---|
| ≥ 75 % | **20 ticks** |
| ≥ 50 % | **40 ticks** |
| < 50 % | **60 ticks** |

- **Double speed** while mood **> 75 %** and stamina **> 75 %**: stamina cost and tool wear are halved.

## Daily schedule

| Phase | Duration | Notes |
|---|---|---|
| `WORK` | **10 min** | only counts while inside the work area; Follow / Stand / inventory / debug stick freeze all timers; combat and socialising only pause the work timer |
| `FREE` | **10 min** (has a job) / **20 min** (no job) | after **1 min** of wandering and **≥ 6 min** left, walks to the nearest entertainment area within **128 blocks** (commute not counted) |
| Entertainment | **5 min** | counts toward FREE; returning to wandering afterwards |
| `SLEEP` | until stamina = 100 % | walks to the bed; no bed = no rest phase |

- Assigning a job while free switches to `WORK` within **2 s**.
- An entertainment visit that never resolves times out after **3 min**.
- Wandering / entertainment goals yield to work at the same priority when free time is over.

## Work areas

| | |
|---|---|
| Region selection | **64-block** ray, six faces, scroll to resize |
| Employees | **5** per area, **1** for fishery; selecting an area takes employees over from others |
| Work reach | **4.5 blocks** (farm, tree farm) |
| "Inside the area" | block coordinates of the feet or the block below (not a float AABB) |
| Area types | road, mine, fill, farm, tree farm, ranch, fishery, entertainment, forbidden |

### Farm
- Harvests mature crops, tills dirt (tool −1 durability), replants.
- **−0.1 stamina** per action; scan throttle **20 – 40 ticks**.
- Accepts any `CropBlock` / `StemBlock`, including Farmer's Delight crops.

### Tree farm
- Scans the area **plus 40 blocks above**; slots / stray saplings only inside the area.
- Standing margin **3 blocks** outside the area (standing only, no felling there).
- Only `*_log` blocks; **Chebyshev spacing ≥ 2** (2×2 exempt); picks the nearest unclaimed tree from
  **6 candidates**.
- **12 ticks per log** (6 when doubled), **5 ticks per leaf**, leaves cost no stamina.
- Fells the whole tree without walking to it; blacklists unreachable targets for **60 s**.
- 2×2 trees give back **4 saplings**.

### Mine / Fill
- BFS queue, first to claim an entry gets it; stance outside the region.
- Stops and says a line when a tool or a block is missing (**30 s** cooldown).
- On completion the area is deleted, fireworks fire and a message is broadcast.

### Ranch
| | |
|---|---|
| Max animals | **2 – 64** |
| Sheep shearing | once per **60 s**; needs shears, otherwise says a line and skips |
| Breeding | every **6 min**; feeds all non-cooldown adults within **3 blocks** with one swing |
| Slaughter | only when count **>** max; adult animals preferred (in-love first), **3 blocks** |
| Butcher lock | **1 s** per area (prevents two employees killing in the same tick) |
| Reset | **3 s** without any employee inside the area |

### Fishery
| | |
|---|---|
| Area | marks standing spots only (block + block above, collision-free) |
| Water search | any water fluid within **4.5 blocks** of the standing spot, line-of-sight checked |
| Bite time | **100 – 600 ticks**, **−100 per Lure level** |
| Loot | vanilla `minecraft:fishing` loot table, Luck of the Sea applies |
| Dry water | retried after **60 s** |
| Rod search | inventory scanned every **20 ticks**; offhand counts |
| Per cast | **−0.1 stamina**, **−1 durability** |

## Combat

| | |
|---|---|
| Assist radius | **32 blocks** |
| Give up | unreachable enemy after about **8 s** |
| Melee sweep | **half damage** + sound + particles |
| Attack cooldown | vanilla attack speed |
| Shield | blocks **270°** in front, wears out, **5 s** disabled by an axe |
| Mending | **1 XP = 2 durability** |
| Never attacks | players, iron golems, other residents |
| Neutral mobs | only fought back |

- Weapon pick: best armour + highest attack damage, swords preferred.
- Slaughter: `#c:tools/knife` overrides (score 1000), then highest attack damage; 20-tick check.

## Social

- Hunger check every **10 s**; social cooldown **5 s**.
- The giver needs at least **2** portions of food.
- Eye contact **40 ticks**, then both wave.
- **1 food per 8** portions handed over, capped at **16**; both sides **+5 %** mood.
- Never initiates sharing while in an entertainment area (can still be called away).

## Beds, sleep, respawn

| | |
|---|---|
| Sleep | until stamina **100 %** |
| Natural wake | **+28 – 32** mood |
| Interrupted | **30 s** resume window, then gives up and retries after **60 s** |
| Follow / Stand while asleep | sleep is **paused** (gets up, stamina frozen); cancelling returns it to bed |
| Respawn | **5 s** at the bound bed, full health, stamina and mood preserved |
| Bed highlight | **32 blocks** around the player while holding the manual |

## Rendering & misc

| | |
|---|---|
| Nameplate / status card | drawn within **64 blocks** |
| Colony anchor | a bound bed plus **16 blocks** around it (or the boundaries of all areas) |
| Inventory | **41 slots**: main hand, hotbar, backpack, 4 armour, offhand |
| Skins | **9** default built-ins + **55** random built-ins, `customs → randoms → defaults` |
| Chat | **2 s** queue; hunger chat has a 2 s cooldown |

## Debug command

`/worldplayers mood|hunger|stamina <0-100>`, `/worldplayers instructions <text>`, `/worldplayers craft`,
`/worldplayers diag` (prints job / activity / destination / schedule / path state of nearby residents).
