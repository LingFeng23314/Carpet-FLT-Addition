# Carpet FLT Addition

**English | [简体中文](README.zh-CN.md)**

> This project is developed with deep AI assistance.

An extension mod for [Carpet](https://github.com/gnembon/fabric-carpet) (fabric-carpet),
providing practical rules and fake-player automation.

Works with Minecraft **1.16.5 ~ 26.3**.

---

## Index

- [Features](#features)
- [Dependencies](#dependencies)
- [Installation](#installation)
- [Commands](#commands)
- [Rules](#rules)
- [Version Support](#version-support)
- [License](#license)

---

## Features

The mod consists of three parts:

| Part | Description |
|---|---|
| **Carpet rules** | 18 rules, toggled via `/carpet` or Carpet's config GUI |
| **Fake-player restocking** | Automatically dispatches fake players to fetch materials from your storage based on the schematic's material demand; manual fetching is also supported |
| **Client integration** | Works together with the **FLT Tools** client mod (fetch GUI, automatic material reporting) |

### How fake-player restocking works

1. Register storage containers with `/flt stock add` (aim at a container) or `/flt stock addarea <pos1> <pos2>`
2. Enable `/carpet autoRestock true`, and install **FLT Tools** on the client so the Litematica
   material demand is reported automatically (or set demand manually with `/flt demand set <item> <count>`)
3. Fake players fetch the materials from your storage and deliver them next to you — just take them from the bot's inventory

> The FLT Tools client mod provides a full **fetch GUI**: open with a hotkey → pick an item
> (showing live storage counts) → set the amount → confirm → a bot delivers it to you.

---

## Dependencies

| Name | Type | Notes |
|---|---|---|
| [Carpet](https://modrinth.com/mod/carpet) | **Required** | Use the latest version for your game version |
| [Fabric API](https://modrinth.com/mod/fabric-api) | **Required** | Latest recommended |
| [MixinExtras](https://modrinth.com/mod/mixinextras) | Bundled | Already included via `include(...)`; no separate install needed |
| **FLT Tools** | Optional (client) | Companion client mod: fetch GUI + automatic material reporting |

---

## Installation

1. Install **Carpet** + **Fabric API** on the server (or in `.minecraft/mods/` for single-player)
2. Drop `carpet-flt-addition-<version>+<mcversion>.jar` into `mods/`
3. Restart the server / game

> Rules are toggled with `/carpet <rule> <value>` or in Carpet's config file.
> Fake-player features (`autoRestock`, etc.) require an integrated or dedicated server.

---

## Commands

All commands are rooted at `/flt` (except `/Tradefinder`).

### `/flt stock` — storage source management

| Command | Description |
|---|---|
| `/flt stock add` | Register the container you are **aiming at** as a storage source |
| `/flt stock remove` | Remove the container you are aiming at |
| `/flt stock list` | List all registered storage sources |
| `/flt stock clear` | Clear all registrations |
| `/flt stock addarea <pos1> <pos2>` | Bulk-register an area (warns if the selection is too large) |

### `/flt demand` — material demand

| Command | Description |
|---|---|
| `/flt demand set <item> <count>` | Set the required amount of an item |
| `/flt demand show` | Show the current demand list |
| `/flt demand clear` | Clear the demand list |

### `/flt fetch` — manual fetching

| Command | Description |
|---|---|
| `/flt fetch <bot> <item> [count]` | Make the given bot fetch from storage (no count = as many as possible) |

### `/flt endgate` — end gateway

| Command | Description |
|---|---|
| `/flt endgate reset` | Reset the pairing of the gateway you are aiming at; the next traversal regenerates the exit according to `endGatewayExitSearchDistance` |

### `/Tradefinder` — librarian trade search

| Command | Description |
|---|---|
| `/Tradefinder select <bot>` | Aim at a lectern to bind it; the bot then repeatedly breaks/replaces the lectern to reroll trades |
| `/Tradefinder stop <bot>` | Stop rerolling |
| `/Tradefinder <bot> <enchantment> <level> <price>` | Search for a trade matching the enchantment / level / max price |

> Requires `/carpet villagerTradeRefresh vanilla` or `force` to be enabled first.

---

## Rules

### General

| Rule | Type | Default | Description |
|---|---|---|---|
| `NoCreeperGrief` | boolean | `false` | Creeper explosions no longer destroy terrain |
| `infinityBowNoArrows` | boolean | `false` | Bows with Infinity can shoot non-consuming arrows even with an empty inventory |
| `LavaStrider` | boolean | `false` | Depth Strider also works in lava |
| `missingToolsPlus` | String | `#none` | More tools count as effective tools for mining glass (`pickaxe` / `axe` / `shovel` / `hoe`) |
| `leashableMinecarts` | boolean | `false` | Minecarts can be leashed and pulled like boats (install this mod client-side too for smooth interaction) |
| `limitPillagerPatrolSpawn` | boolean | `false` | Pillager patrol spawning is affected by the mob cap |
| `grindstoneEnchantmentDuplication` | boolean | `false` | Grindstone enchantment duplication (replicates the 24w10a~24w11a snapshot behavior, no XP needed) |
| `witherSkeletonNoStoneSword` | boolean | `false` | Wither skeletons no longer drop stone swords (bones/coal/skulls drop as usual) |

### Villagers & trading

| Rule | Type | Default | Description |
|---|---|---|---|
| `villagerTradeSwiftSneak` | boolean | `false` | Librarians can sell Swift Sneak enchanted books |
| `villagerTradeWindBurst` | boolean | `false` | Librarians can sell Wind Burst enchanted books |
| `villagerTradeRefresh` | String | `false` | Reroll librarian trades with a fake player: `vanilla` = break/replace lectern; `force` = reroll directly server-side (much faster) |
| `forceRestock` | boolean | `false` | Shoot a villager with a spectral arrow to force it to restock |
| `villagersAttractedByEmeraldBlock` | boolean | `false` | Holding an emerald block attracts nearby villagers |

### Fake-player restocking

| Rule | Type | Default | Description |
|---|---|---|---|
| `autoRestock` | boolean | `false` | Automatically dispatch fake players to fetch materials based on demand (register containers with `/flt stock add` first) |
| `restockIntervalTicks` | int | `20` | How often (in ticks) to scan for shortages and dispatch |
| `fltFakePlayerPrefix` | String | — | Name prefix of restocking bots (prefix + player name is truncated to 16 chars; longer names will disconnect clients) |

### Misc

| Rule | Type | Default | Description |
|---|---|---|---|
| `xaeroMapName` | String | `#none` | Requires Xaero's World Map on the client; solves map data mixing on proxy networks (BungeeCord / Velocity) |
| `endGatewayExitSearchDistance` | int | `0` | Search distance for the paired end gateway exit; `0` = vanilla 1024, `>0` = custom (use `/flt endgate reset` to re-pair existing gateways) |

---

## Version Support

| Game Version | Status |
|---|---|
| 1.16.5 | Maintained |
| 1.17.1 | Maintained |
| 1.18.2 | Maintained |
| 1.19.4 | Maintained |
| 1.20.1 | Maintained |
| 1.21 ~ 1.21.1 | Maintained |
| 1.21.2 ~ 1.21.3 | Maintained |
| 1.21.4 | Maintained |
| 1.21.5 | Maintained |
| 1.21.6 | Maintained |
| 1.21.7 ~ 1.21.8 | Maintained |
| 1.21.9 | Maintained |
| 1.21.10 | Maintained |
| 1.21.11 | Maintained |
| 26.1.2 | Maintained |
| 26.2 | Maintained |
| 26.3 | Maintained |

> Use the Carpet build matching your game version; prefer the latest.

---

## License

Released under the **GNU LGPL-3.0** license (see [LICENSE](LICENSE)).

Several third-party projects were referenced or ported during development
(Carpet-Org-Addition, Carpet-LMS-Addition, etc.).
See **[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)** for the full list of sources, authors and licenses.
