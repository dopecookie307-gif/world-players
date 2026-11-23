# World Players

**Villagers that look, gear up and fight like players.**

A Minecraft **1.21.1** mod for **NeoForge**: residents built on the player model, with a player-style
inventory, gear, experience repair, navigation and a full combat system. Since 0.3.0 they also
mark out work areas, keep a daily schedule, and have mood, stamina and hunger.

> **Status: v0.3.0** (2026-11-23). Playable, still evolving.
> Exact numbers live in [docs/MECHANICS.md](docs/MECHANICS.md); version history in [CHANGELOG.md](CHANGELOG.md).
> 中文说明见 [README.zh-CN.md](README.zh-CN.md)。

---

## Requirements

| | |
|---|---|
| Minecraft | **1.21.1** |
| Loader | **NeoForge 21.1.266** (min. 21.1.233) |
| Java | 21 |

## What it does

### Residents
- Player model in **4 forms** (wide / slim / kid-wide / kid-slim); kids fit through 1-block gaps.
- **10 % left-handed**; built-in skin pool, custom skins, **Mojang ID skin download**, renaming.
- Player-style **41-slot inventory** (main hand, hotbar, backpack, 4 armor slots, offhand) with its own GUI.
- Armor wear, **Mending** (XP repairs gear), totem of undying, XP absorption.
- Summoned with a **spawn egg**; dies and reports a **death message** in chat;
  with a bound bed, comes back at the bed after 5 s.

### The manual
- Right-click opens the notebook-style manual page; left-click a resident cycles **Follow / Stand / Free**.
- Toggle the **status card** (name / mood face / current job) and editable sidebar order.

### Modes
- **Follow** — dog-style following, teleports next to you when left behind; can place torches (no crafting).
- **Stand** — rooted in place, immune to knockback, still fights.
- **Free** — "leave them to their own schedule". This is the container for the day-schedule system.

### Work areas (0.3.0)
- Plan a region with the manual (64-block ray, six faces, scroll to resize): **road, mine, fill, farm,
  tree farm, ranch, fishery, entertainment, forbidden** — with colour, list/detail pages and scrolling.
- Up to **5 employees** per area (fishery: **1**); selecting an area **takes its employees over** from others.
- **Farm** — harvests mature crops, tills dirt, replants. **Farmer's Delight compatible: plants and
  harvests every crop, rice included.**
- **Tree farm** — scans the area plus **40 blocks above**, fells whole trees **without walking into the
  canopy**, replants saplings, supports 2×2 trees.
- **Mine / Fill** — queued BFS work, stance outside the area, tool/block checks, finish fireworks.
- **Ranch** — animal type (cow / sheep / pig / chicken), max count 2–64, sheep-shearing toggle;
  shearing → breeding → slaughtering loop, drops into the backpack, weapons auto-selected.
- **Fishery** — the area marks standing spots only; finds water within 4.5 blocks, casts, waits for a
  bite, rolls vanilla fishing loot (lure / luck of the sea apply), re-checks water every cast.
- **Entertainment** — 500-char description; residents visit during free time to recover mood.
- **Forbidden** — residents never enter voluntarily; one already inside walks out.

### Daily life (0.3.0)
- **Schedule**: work (10 min) → free time (10 min with a job, 20 without) → wandering →
  nearest entertainment area → bed. Follow / Stand / inventory freeze all timers.
- **Mood & stamina**: decay over time, affect work pacing and movement; work is doubled while both are >75 %.
- **Hunger**: eats from the inventory, shares food with hungry neighbours, refuses raw meat.
- **Beds**: bind a bed, **sleep until stamina is full** and wake with a mood bonus; residents without a
  bed are shown as "（流浪）" (homeless).

### Combat
- Attacks nearby hostiles **only when it can see them**; assists any resident or player under attack
  within **32 blocks**; gives up on unreachable enemies.
- **Picks the best armour and the highest-damage weapon (prefers swords)**; **switches between melee and
  ranged automatically**; archers and crossbowmen find their own shooting positions; reloads crossbows
  when idle.
- **Shield**: raised in combat, blocks **270° in front**, wears out like a player's, disabled for 5 s by an axe.
- Weapon / armour / shield durability and enchantments apply; XP repairs Mending gear.
- Never attacks players, iron golems or other residents; neutral mobs are only fought back.
- Melee sweeps at half damage with sound and particles; real attack-speed cooldown.

### Movement & safety
- Never walks into dangerous blocks (lava, fire, magma, cactus, sweet berries, cobwebs, powder snow),
  never deliberately drops into fall-damage heights, relocates after environmental damage.
- Pathfinding understands doors and fence gates (only the area's own employees open its gates), roads
  cost less, forbidden areas are blocked, and stand spots are always reachable by collision.

### Languages
- Chinese (Simplified) and English.

## Compatibility

Optional integrations are soft dependencies — nothing loads when the mod is absent.

| Mod | What you get |
|---|---|
| [Jade](https://modrinth.com/mod/jade) | resident info panel |
| [3D Skin Layers](https://modrinth.com/mod/3dskinlayers) | 3D skin layers on residents |
| [Chat Heads](https://modrinth.com/mod/chat-heads) | chat heads in resident messages |
| [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) | residents wear non-encrypted YSM models |
| [Just Enough Characters](https://modrinth.com/mod/just-enough-characters) | pinyin search in residents' inventory |
| Modular Golems | golems and residents never fight each other |

## Build

```bash
# Windows, Java 21
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.10
set GRADLE_USER_HOME=C:\wp-gradle-home
gradlew.bat build
```

The jar is written to `build/libs/`.

> On Windows, building from a path containing non-ASCII characters can break `gradlew.bat`
> (cmd decodes the classpath with a legacy code page). Map the project to an ASCII drive first:
> `subst W: "<project dir>"` → build in `W:\WorldPlayers` → `subst W: /D`.
>
> `libs/` holds the compile-only API jars (Jade, 3D Skin Layers, Chat Heads, JECharacters, PinIn) and is
> not tracked by git. Put them there before building, or remove the matching `compileOnly` lines.

## Credits

- Inspired by and partially informed by **[Folkways](https://github.com/IzakyL/folkways)** (MIT, © 2026 IzakyL).
- Everything in this repository is an independent single-module implementation.

## License

All Rights Reserved — see [LICENSE](LICENSE). The source is published for learning and reference;
redistribution requires permission.
