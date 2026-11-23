# Changelog

All notable changes to World Players. Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

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
