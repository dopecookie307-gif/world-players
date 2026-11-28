# Changelog

All notable changes to World Players. Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

## [0.3.1] — 2026-11-28

The formal release that collects every `0.3.1-alpha.*` build below.

### Added

- **Path network.** A hand-drawn route network that residents actually walk.
  - New item `worldplayers:path_brush`: left click cycles a spot through **path point → anchor → delete**,
    right click selects a point and then links it to another, **Alt + scroll** changes that point's action.
    Points are placed one block outside the clicked face; while holding the brush, points and segments
    within 32 blocks render through blocks (anchors purple, path points yellow-green, blocked or floating red).
  - Each point stores its dimension, anchor flag, action (walk / jump / climb ladder / teleport) and width;
    segments default to two-way.
  - Residents really use it: `PathRoute` picks the nearest same-level entry / exit anchor and runs Dijkstra
    over segments, `ResidentPathNetGoal` walks point by point, executes the actions and hands the last few
    blocks back to vanilla pathfinding. Entry anchors are validated with `canReach` (up to 4 candidates).
  - Segments are **shared passages** with capacity `min(width of both ends)`. A resident reserves the next
    segment before walking it, queues at the node otherwise with the **“Waiting for passage”** status, and a
    5 s deadlock fallback teleports it across the blocked segment. Strolling avoids segments and nodes, so
    nobody loiters in stairwells.
  - Teaching HUD, a dedicated creative tab in front of the vanilla one, and crafting recipes: path brush =
    stick + iron nugget, debug wand = 2 sticks.
- **Custom work areas** (`custom`, paper icon): blank-slate areas with no fixed work. Employees commute to
  them by area name, wander inside, optionally stand on the single standable spot, and display a
  **“Work status:”** line above their head that is saved on every edit.

### Changed

- **One travel-speed rule.** Path-network walking, work-area commuting, combat, wand / bed commuting and
  returning from stand duty all go through `ResidentEntity#applyTravelSpeed()` (the lower mood / stamina
  tier, walking multiplier 1.0, combat 1.15). The base speed returns once a resident is inside an area, so
  the travel boost never leaks into in-area work.
- **Follow / Stand pause sleep instead of cancelling it**: the resident gets up, stamina stays where it was,
  no continuation window and no failed-nap penalty; leaving the mode resumes the previous activity,
  including the rest of the sleep.
- **Tree farm** now scans the area plus **40 blocks above** and fells whole trees **without walking to them**
  (no more leaf-blocked line of sight, no canopy head-stuck teleports), picks the nearest free tree
  systematically and ages unreachable targets out after 60 s.
- **Ranch**: per-area slaughter lock, automatic weapon switching (`#c:tools/knife` first), the max-count
  comparison fixed to `<=`, breeding cooldown restored (`setInLoveTime(600)`), and work animals excluded
  from combat targets, assistance and melee sweeps.
- **Fishery**: an off-hand fishing rod counts as having one and is moved to the main hand.

### Fixed

- Residents stuck inside open door frames and endlessly opening / closing doors; fence gates now close
  behind them.
- Wrong-floor anchors (3D-nearest → same-level scoring), stairway stalls (movement detection, skipping a
  passed node, larger arrival radius for buried nodes) and anchors that could not be reached at all.
- Switching Follow / Stand left a stale status line; non-IDLE switches now clear the activity / destination.
- The path goal fighting the work and sleep goals: the commute goal yields through `handlesTrip()`, and a
  tired resident with a bed is only taken back to bed (no more room ↔ tree-farm ping-pong).
- Fishing-catch, hunger-chat, torches and the rest of the `0.3.0` polish listed in the alpha entries below.

### Notes

- The `0.3.1-alpha.1` … `0.3.1-alpha.12` entries below are kept for history; this release contains all of them.

## [0.3.1-alpha.12] — 2026-11-25

### Added
- **Custom Areas** (`workzone type "custom"`, paper icon): a blank-slate work area you can assign employees to,
  with no fixed work.
  - Residents commute to it like any other work area (the commute destination is the area name), then simply
    wander inside the area.
  - While working they lose **0.1 stamina every 6 seconds**.
  - If the area contains exactly **one** standable spot, the employee stands in that single spot instead of
    wandering (rescan every 5 s; areas larger than 32 768 blocks are treated as multi-spot).
  - The area detail page has a **“Work status:”** text field right below the employee slots. Whatever is typed
    is shown as the resident's job line above their head while they work (empty = “Working”).
  - The text is **saved on every change**, so closing the page in any way (✕, ESC, back button, switching
    screens) keeps the content; it is stored on the zone and synced with the zone list.
  - The custom text is shown as-is (no translation lookup), limited to 40 characters.

## [0.3.1-alpha.11] — 2026-11-24

### Added
- **Path-network traffic, round three (queuing / no lingering / head-on avoidance).**
  Every path segment is now a shared passage resource with capacity `min(width of both endpoints)` — default `1`,
  i.e. one resident at a time. A resident reserves the next segment before walking it, releases it on arrival,
  and queues at the node otherwise, showing the new **“Waiting for passage”** status instead of a generic commute.
  Because a one-lane segment is exclusive both ways, two residents going up and down the same stairs can never
  meet head-on; whoever got there first goes through and the other waits.
- Segment occupancy lives in a runtime-only table (`resident/path/PathTraffic`) keyed by level. It is refreshed
  every 10 ticks and expiring entries are dropped after 10 s, so unloaded or dead residents can never lock a path.
- Queue deadlock fallback: after 5 s of waiting the resident is teleported across the blocked segment (if the
  target node is a safe stance), guaranteeing that everyone eventually gets through.
- While queuing the resident steps aside to a safe neighbouring block so it does not block the junction itself.

### Changed
- **Strolling no longer happens on the path network.** `ResidentStrollGoal` refuses to start while the resident
  is standing on a segment (`PathNetworkData#isTravelLane`, 3-block clearance, stops within 2.5 blocks of a node
  count as landings), and it rejects walk targets that fall inside a segment. Residents therefore stop loitering
  in narrow stairwells / corridors and block the people who are actually travelling.

### Notes
- `PathNode#bidirectional` is still not exposed in the UI; every segment is treated as two-way, which is exactly
  why the exclusive-lane rule resolves head-on conflicts without any retreat logic.

## [0.3.1-alpha.10] — 2026-11-24

### Changed
- **One movement rule for everything that travels**: path-network walking, work-area commuting and combat now all
  use the mood / stamina tier speed — the lower of the two tiers, capped by low hunger and scaled while eating —
  through the single helper `ResidentEntity#applyTravelSpeed()`. Walk multipliers are 1.0, combat keeps its
  1.15 chase multiplier.
- Commuting into a work area now also uses that tier speed, and the resident returns to the base walking speed
  once inside the area, so the travel boost never leaks into in-area work.
- Removed the alpha.9 `WALK_GOAL_SPEED` / `applyWalkSpeed()` pair: that constant speed (0.252) silently
  disabled the mood / stamina tiers, because the base value 0.28 is already below the lowest tier (0.30).

## [0.3.1-alpha.9] — 2026-11-24

> Superseded by 0.3.1-alpha.10: the constant travel speed introduced here disabled the mood / stamina tiers.

### Changed
- **The path network now walks at exactly the same speed as a normal work-area commute.** Both use the shared
  `ResidentEntity.WALK_GOAL_SPEED` (`0.9`) with the normal walking speed attribute (`0.28`), so the resident no
  longer speeds up on the network and slows down on the ground — one speed, one place to change it.
  The work-area commute also re-applies the normal walking speed explicitly, so a leftover sprint from a
  previous goal cannot leak into it.

### Notes
- Bed / debug-wand commuting (`ResidentCommuteGoal`) keeps its old mood-based speed; it was not part of this request.

## [0.3.1-alpha.8] — 2026-11-24

### Fixed
- **Walking speed while following the path network was far too slow** (well under the normal pace even at full
  mood / stamina). The network trip now uses the existing movement system
  (`moodMovementSpeed` + the same multiplier as the commute goal, refreshed every second) instead of the
  slow strolling pace introduced in alpha.5.
- **Residents stopped dead once they reached the last anchor.** After finishing the network route the goal
  now keeps walking toward the actual destination for up to ten seconds (and stops early when it arrives,
  when the destination is unreachable, or when it stops making progress); only then does the original goal
  take over again. Points that vanilla pathfinding cannot reach are also skipped automatically.

## [0.3.1-alpha.7] — 2026-11-24

Third round of in-game fixes for the path network.

### Fixed
- **Residents ping-ponging between their room and the work area**: when exhausted (stamina ≤ 10 %), the
  work goal refused to work but the path-network goal still routed them to the work area, while the
  bed-return timer kept asking them to go home every 30 seconds. The network goal now only handles the
  **bed trip** while a resident with a bed is exhausted; work / entertainment trips fall back to vanilla
  pathfinding (or not at all) until they rest.
- **Residents that only walked once the player dragged them onto the stairs**: the entry anchor used to be
  chosen by distance alone, even when it could not be reached (wall / floor in the way). The planner now
  tries up to four same-floor entry candidates in order and picks the first one vanilla pathfinding can
  actually reach.

## [0.3.1-alpha.6] — 2026-11-24

Second round of in-game fixes for the path network.

### Fixed
- **Residents walking to the room directly below their own destination**: the exit anchor was chosen by plain
  3D distance, so an anchor on the floor below was "closer" than one on the same floor. Anchors on the same
  level are now preferred (same floor and within 12 blocks horizontally → same floor → anything, scored as
  horizontal + 4 × vertical distance). New points also auto-connect to same-floor points first, so placing a
  room anchor no longer creates a vertical "shortcut" segment.
- **Residents getting stuck on the stairs** (`navDone=false` while not moving): the goal now watches actual
  movement — after 2 seconds without moving it jumps and re-paths, after 6 seconds it gives up for this trip
  (vanilla pathfinding takes over for 30 seconds). It also advances past a point that is already behind it,
  and treats a point that is buried inside a block as reached from 2.5 blocks away.
- **Work status stuck on "Commuting" after switching to Follow / Stand**: switching modes now clears the
  transient activity and destination immediately, and restores the commute destination when switching back
  to free activity.

## [0.3.1-alpha.5] — 2026-11-24

Fixes from the first in-game test of the path network round 2 (residents going home / commuting).

### Fixed
- **Residents ignored the drawn path when going back to bed.** The path-network goal and the commute goal
  shared priority 2, and Minecraft never lets a goal replace one with the same priority — so once the
  commute goal held the MOVE flag, the network goal could never run. The two goals now share one decision
  (`ResidentPathNetGoal#handlesTrip()`); the commute goal yields whenever the network can handle the trip,
  and takes the last few blocks back when the trip is done or impossible.
- **Residents could not be switched to Follow / Stand while walking to bed.** The commute goal now only
  runs in free activity (`IDLE`), so the player's Follow / Stand orders always win; switching back resumes
  the commute.
- **Residents stuck standing still next to the bed** (`navDone=true`, path ending at their own position):
  vanilla pathfinding often refuses to path onto the bed block itself. The commute goal now falls back to
  a standable, reachable spot beside the target.

### Changed
- **No more "On a path trip" status**: residents walking the network show the existing **Commuting**
  status with the destination name.
- **No more speed boost while walking the network**: the resident walks at its normal pace (base movement
  speed, strolling multiplier) instead of the old fast commute speed.

## [0.3.1-alpha.4] — 2026-11-24

Path brush linking, two crafting recipes, and **path network round 2: residents actually walk the network**.

### Added
- **Two shapeless recipes**: Resident Wand = 2 sticks (`resident_wand.json`), Path Brush = 1 stick +
  1 iron nugget (`path_brush.json`).
- **Residents follow the path network** (`PathRoute` + `ResidentPathNetGoal`): for trips longer than
  12 blocks (debug-wand commute, going home to bed, commuting to a work area, heading to an
  entertainment area) the resident walks "entry point → each point in order → exit point" over the
  drawn network, then hands the last few blocks back to vanilla pathfinding. Point actions are honoured
  (jump / teleport); a point that cannot be reached within 3 seconds ends the trip and falls back to
  old behaviour. Status shows **On a path trip** with the destination name.

### Changed
- **Right click on a path point/anchor is now the link mode**: the first right click selects the point
  (drawn white, shown in the tutorial panel), the second right click on another point connects the two
  and leaves link mode. **Shift + right click anywhere** leaves link mode. Right clicking the same
  point again also cancels.
- Action editing moved off the right click: **Alt + scroll** changes the selected point's action when
  something is selected, otherwise it still changes the next point's action.

### Notes
- Bed / villager claiming work is dropped per the owner's request (never implemented).

## [0.3.1-alpha.3] — 2026-11-24

### Changed
- **Shift + right click on the path brush now does nothing** — deleting is already the third left click;
  the combination is freed up for a future feature.
- A new point links to the previous point, or else to the **nearest node within 32 blocks** (preferring
  nodes that have no segment yet). Editing or deleting other points no longer breaks the next connection.

### Fixed
- Residents getting stuck in an **open wooden door frame**, toggling the door and playing the sound forever:
  - the door goal no longer starts on a door that is already open;
  - it no longer closes the door while the resident is still within 1.6 blocks — the door stays open and
    is closed later by the door tracker once the resident is through;
  - delayed closing now handles wooden doors (both halves) as well as fence gates.

## [0.3.1-alpha.2] — 2026-11-24

Path brush fixes from the first in-game test.

### Fixed
- **Left click is now one edit per press.** Holding the button (or a quick human click) no longer runs the
  point through path point → anchor → delete; repeated attack events while the button is held are swallowed.
- **Points, anchors and segments are drawn through blocks** (no depth test), so a point that ended up inside
  a block is still visible.
- **Points always get a connecting line**: a new point links to the previous point, and when there is none,
  to the nearest unlinked node within 32 blocks.

## [0.3.1-alpha.1] — 2026-11-24

Path network, round 1: draw it and see it. Residents do not follow the network yet (that is round 2).

### Added
- **Path Brush** (`worldplayers:path_brush`, stick model for now):
  - left click cycles a spot through **path point → anchor → delete**;
  - right click cycles that point's action (walk / jump / climb / teleport);
  - Shift + right click deletes after a confirmation screen;
  - Alt + scroll changes the action used by the next point placed.
  - Points are placed on the **outer face of the clicked block**, like a torch; editing reaches 64 blocks.
- **Path network rendering** while holding the brush: anchors (purple), path points (yellow-green) and segments
  within **32 blocks**; a point that is occupied or unsupported is drawn red.
- **Left-side tutorial panel** while holding the brush (collapsible, saved to the client config).
- **World Players creative tab**, ordered before vanilla's first tab; the manual, wand, spawn egg and path brush
  now live there instead of the vanilla tabs.

### Changed
- The manual now opens on the **top-most sidebar page** (following the player's edited order) instead of always
  opening "Area selection".

## [0.3.0] — 2026-11-23

The "colony" update: work areas, a daily schedule, and a life of their own.

### Added
- **Work areas** planned with the manual (64-block ray, six faces, colours, list/detail pages, scrolling):
  road, mine, fill, farm, tree farm, ranch, fishery, entertainment, forbidden.
- **Employees** — up to 5 per area (fishery 1); selecting an area takes employees over from others.
- **Farm** — harvest, till, replant; **Farmer's Delight compatible: every crop, rice included**.
- **Tree farm** — scans the area + 40 blocks above, fells whole trees without walking into the canopy.
- **Mine / Fill** — queued BFS projects with tool / block checks, stance outside the area, finish fireworks.
- **Ranch** — animal type, max count 2–64, optional shearing; shearing → breeding → slaughtering loop,
  automatic best-weapon selection.
- **Fishery** — water search, casting, bite timing, vanilla fishing loot, Lure / Luck of the Sea.
- **Entertainment** areas and a **forbidden** area type.
- **Daily schedule** — work 10 min → free time → wandering → entertainment → bed.
- **Mood, stamina and hunger** with decay, rest, eating and food sharing.
- **Beds** — binding, sleeping until stamina is full, 5 s respawn at the bed.
- Optional **Yes Steve Model** support (geometry, animations, controllers, hand items).
- Optional **Just Enough Characters** pinyin search, **Modular Golems** peace, resident **Jade** panel.
- `/worldplayers diag` for debugging AI goals and pathfinding.

### Changed
- Manual — scrollable render options and area-type list, marquee names, tooltips, 500-char area descriptions.
- Sleep is now **paused** by Follow / Stand instead of being interrupted.
- Work mode display, chat messages (2 s queue) and status card refresh logic.

### Fixed
- Constant "commuting" / standing still when the work area contains crops or one-block-tall regions.
- Tree farm getting stuck on leaves or only walking around the outside of a full forest.
- Ranch animals being treated as enemies, whole herds wiped out by sweep attacks.
- Breeding never cooling down, slaughter off-by-one, cross-area employee theft.
- Thousands of "skin not found" log lines per session.

## [0.2.0] — 2026-11-18

- **Resident manual** page: right-click to open, left-click a resident to cycle Follow / Stand / Free;
  toggle the status card, editable sidebar order.
- **4 humanoid models** (wide / slim / kid-wide / kid-slim); **10 %** of residents are left-handed.
- **Death messages** in chat; **Chat Heads** support.
- Open the custom skin folder from the inventory; download skins by **Mojang ID**.
- **English** language support (alongside Simplified Chinese).
- **Danger avoidance** — never walks into lava, fire, magma, cactus, sweet berries, cobwebs or powder
  snow; relocates after environmental damage.
- **Full combat system**
  - hostile mobs attack residents like they attack players; neutral mobs are only fought back;
  - never attacks players, iron golems or other residents;
  - picks the best armour and highest-damage weapon (prefers swords) and switches melee / ranged on its own;
  - keeps a safe distance, archers / crossbowmen seek their own shots, idle crossbows are reloaded;
  - assists residents or players under attack within 32 blocks; gives up on unreachable enemies;
  - shield blocks 270° in front; weapon, armour and shield durability and enchantments apply;
  - absorbs XP, **Mending** repairs gear, dropped XP is not kept on death.

## [0.1.0] — 2026-11-17

The baseline.

- **Resident spawn egg** summons a resident.
- **Resident manual** — while held, shows a compact status overlay (name / mood, mode, health, hunger, stamina).
- Player-style **inventory** (36 slots + 4 armour + offhand) with normal durability use.
- **Totem of undying** in any inventory slot blocks one lethal hit.
- Rename residents and change their skins.
- **3D Skin Layers** compatibility.
