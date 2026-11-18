# World Players

**Villagers that look, gear up and fight like players.**
A Minecraft **1.21.1** mod for **NeoForge** that adds residents built on the player model, with a player-style
inventory, gear, experience repair, navigation and a full combat system.

> Status: **v0.2.0-alpha** (2026-11-18). Early but playable; expect rough edges.

---

## Requirements

| | |
|---|---|
| Minecraft | **1.21.1** |
| Loader | **NeoForge 21.1.266** (min. 21.1.233) |
| Java | 21 |

Optional integrations (all soft dependencies):

- [3D Skin Layers](https://modrinth.com/mod/3dskinlayers)
- [Chat Heads](https://modrinth.com/mod/chat-heads)
- [Jade](https://modrinth.com/mod/jade)
- Modular Golems — compatibility planned (golems and residents will never fight each other)

## What it does

### Residents
- Player model with **4 forms** (wide / slim / kid-wide / kid-slim; kids fit through 1-block gaps)
- **10 % left-handed**, built-in skin pool + custom skins + **Mojang ID skin download**
- Player-style **41-slot inventory** (main hand, hotbar, backpack, 4 armor slots, offhand) with its own GUI
- Armor wear, **Mending** (1 XP = 2 durability), totem of undying, XP absorption
- Dies and reports its **death message** in chat

### The resident manual
- Right-click to open the manual page (paper notebook style, editable sidebar order)
- Toggle whether the **status card** (name / mood face / current job) is rendered
- Left-click a resident to cycle its mode: **Follow / Stand / Free**

### Work modes
- **Follow** — dog-style following; safely teleports next to you if left behind
- **Stand** — rooted in place, immune to knockback, but still fights and shoots
- **Free** — "leave them to their own schedule". This is the container for the upcoming
  day-schedule system (wake up, commute, work, rest, socialize, go home, sleep).
  "Idle wandering" is only a temporary placeholder until that system exists.

### Combat
- Attacks nearby hostiles **only when it can actually see them**; assists any resident or player under attack within 32 blocks
- **Nearest target first**, gives up on unreachable enemies after ~8 s
- Melee: prefers **swords**, half-damage sweep with sound + particles, real `ATTACK_SPEED` cooldown
- Ranged: **bow > crossbow**, gravity-compensated aiming, keeps a firing distance,
  **finds a shooting position when line of sight is blocked**, auto-reloads crossbows when idle
- **Shield**: raises it in combat, blocks 270° in front, wears out like a player's, disabled for 5 s by an axe
- Keeps distance **only while its attack is on cooldown** — it always prioritizes killing the enemy
- Never attacks players, iron golems or other residents; neutral mobs are only fought back
- Weapons / armor / shields lose durability; enchantments apply

### Movement & safety
- Never walks into dangerous blocks (lava, fire, magma, cactus, sweet berries, cobwebs, powder snow),
  never deliberately drops into fall-damage heights, relocates after environmental damage

### Language
- Chinese (Simplified) and English

## Build

```bash
# Windows, Java 21
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.10
gradlew.bat build
```

The jar is written to `build/libs/`.

> On Windows, building from a path containing non-ASCII characters can break `gradlew.bat`
> (cmd decodes the classpath with a legacy code page). Map the project to an ASCII drive first:
> `subst W: "<project dir>"` → build in `W:\WorldPlayers` → `subst W: /D`.

## Credits

- Inspired by and partially informed by **[Folkways](https://github.com/IzakyL/folkways)** (MIT, © 2026 IzakyL).
- Everything in this repository is an independent single-module implementation.

## License

All Rights Reserved. The source is published for learning and reference; redistribution requires permission.
