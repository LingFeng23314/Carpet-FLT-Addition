# Carpet FLT Addition

**[简体中文](README.zh-CN.md) | English**

> This project is developed with deep AI assistance.

An extension mod for [Carpet](https://github.com/gnembon/fabric-carpet) (fabric-carpet), providing practical rules and fake-player automation (material restocking, librarian trade rerolling, ...).

Works with Minecraft 1.16.5 ~ 26.3.

| Game Version | Development Status |
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

Use it together with the Carpet mod of the same Minecraft version; prefer the latest Carpet.

## Dependencies

- [Carpet](https://modrinth.com/mod/carpet) (required; use the latest version whenever possible)
- [Fabric API](https://modrinth.com/mod/fabric-api) (required; use the latest version whenever possible)
- [MixinExtras](https://modrinth.com/mod/mixinextras) (bundled via `include(...)`; no separate install needed)
- **FLT Tools** (optional, client) — companion client mod: fetch GUI + automatic material reporting

## Index

**Rules**

- [No Creeper Grief](#no-creeper-grief-nocreepergrief)
- [Wither Skeleton No Stone Sword](#wither-skeleton-no-stone-sword-witherskeletonnostonesword)
- [Infinity Bow No Arrows](#infinity-bow-no-arrows-infinitybownoarrows)
- [Lava Strider](#lava-strider-lavastrider)
- [Missing Tools Plus](#missing-tools-plus-missingtoolsplus)
- [Leashable Minecarts](#leashable-minecarts-leashableminecarts-mc121)
- [Limit Pillager Patrol Spawn](#limit-pillager-patrol-spawn-limitpillagerpatrolspawn-mc1182)
- [Grindstone Enchantment Duplication](#grindstone-enchantment-duplication-grindstoneenchantmentduplication-mc121)
- [Villager Trade Swift Sneak](#villager-trade-swift-sneak-villagertradeswiftsneak-mc1194)
- [Villager Trade Wind Burst](#villager-trade-wind-burst-villagertradewindburst-mc121)
- [Villager Trade Refresh](#villager-trade-refresh-villagertraderefresh)
- [Force Restock](#force-restock-forcerestock)
- [Emerald Block Attracts Villagers](#emerald-block-attracts-villagers-villagersattractedbyemeraldblock)
- [Xaero Map World Name](#xaero-map-world-name-xaeromapname)
- [End Gateway Exit Search Distance](#end-gateway-exit-search-distance-endgatewayexitsearchdistance-mc2612)
- [Auto Restock](#auto-restock-autorestock-mc2612)
- [Restock Interval Ticks](#restock-interval-ticks-restockintervalticks-mc2612)
- [Restock Fake Player Prefix](#restock-fake-player-prefix-itemfetcherprefix-mc2612)

**Commands**

- [`/Tradefinder`](#tradefinder) — librarian trade search (all versions)
- [`/Itemfetcher`](#itemfetcher) — fake-player material fetching: storage sources / demand / fetch (**26.x only**)
- [`/Gatewayfixer`](#gatewayfixer) — end gateway re-pairing (**26.x only**)

## Rules List

### No Creeper Grief (noCreeperGrief)

Creeper explosions no longer destroy your terrain.

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Wither Skeleton No Stone Sword (witherSkeletonNoStoneSword)

Wither skeletons no longer drop their stone sword (bones, coal and skulls drop as usual).

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Infinity Bow No Arrows (infinityBowNoArrows)

Bows enchanted with Infinity can shoot non-consuming arrows even with an empty inventory.

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Lava Strider (lavaStrider)

Depth Strider also works in lava (swim fast! :D)

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Missing Tools Plus (missingToolsPlus)

More tools can serve as effective tools for mining glass.

- Type: `String`
- Default: `#none`
- Options: `#none`, `pickaxe`, `axe`, `shovel`, `hoe`
- Categories: `FLT`, `survival`

### Leashable Minecarts (leashableMinecarts) `MC>=1.21`

Minecarts can be leashed and pulled around like boats.

> The **client** must also install this mod for smooth leash interaction (otherwise right-click may be predicted as "mount").

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Limit Pillager Patrol Spawn (limitPillagerPatrolSpawn) `MC>=1.18.2`

Pillager patrol spawning is affected by the mob cap.

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Grindstone Enchantment Duplication (grindstoneEnchantmentDuplication) `MC>=1.21`

Replicates the 24w10a ~ 24w11a snapshot behavior: enchantments in the bottom slot are copied onto the top slot item.

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Villager Trade Swift Sneak (villagerTradeSwiftSneak) `MC>=1.19.4`

Allows librarian villagers to sell Swift Sneak enchanted books.

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Villager Trade Wind Burst (villagerTradeWindBurst) `MC>=1.21`

Allows librarian villagers to sell Wind Burst enchanted books.

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Villager Trade Refresh (villagerTradeRefresh)

Reroll librarian trades with a fake player: `vanilla` = break/replace the lectern; `force` = reroll directly server-side (much faster).

> Used together with the `/Tradefinder` command. Only villagers that have never been traded with can be rerolled (vanilla `villagerXp == 0` gate).

- Type: `String`
- Default: `false`
- Options: `false`, `vanilla`, `force`
- Categories: `FLT`, `feature`

### Force Restock (forceRestock)

Shoot a villager with a spectral arrow to force it to restock (ignores the vanilla 2-times-per-day limit).

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Emerald Block Attracts Villagers (villagersAttractedByEmeraldBlock)

Holding an emerald block attracts nearby villagers.

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Xaero Map World Name (xaeroMapName)

Requires **Xaero's World Map** on the client. Separates map data per world on proxy networks (BungeeCord / Velocity).

- Type: `String`
- Default: `#none`
- Categories: `FLT`

### End Gateway Exit Search Distance (endGatewayExitSearchDistance) `MC>=26.1.2`

Search distance for the paired end-gateway exit: `0` = vanilla (1024 blocks along the radius), `>0` = custom distance.

> Use `/Gatewayfixer` to re-pair existing gateways.

- Type: `int`
- Default: `0`
- Categories: `FLT`, `feature`

### Auto Restock (autoRestock) `MC>=26.1.2`

Automatically dispatches fake players to fetch materials from your storage based on the material demand.

> Register containers with `/Itemfetcher stock add` first. Requires the **FLT Tools** client mod to report Litematica material demand automatically.

- Type: `boolean`
- Default: `false`
- Options: `true`, `false`
- Categories: `FLT`, `feature`

### Restock Interval Ticks (restockIntervalTicks) `MC>=26.1.2`

How often (in ticks) to scan for material shortages and dispatch fake players. `20` = 1 second.

- Type: `int`
- Default: `20`
- Categories: `FLT`

### Restock Fake Player Prefix (itemFetcherPrefix) `MC>=26.1.2`

Name prefix of the restocking fake players (the bot name is prefix + `fetch` (the default `flt_` gives `flt_fetch`), truncated to 16 characters).

> Only affects FLT restocking bots, not other fake players using Carpet's global `fakePlayerNamePrefix`.

- Type: `String`
- Default: `flt_`
- Categories: `FLT`

## Commands

### /Tradefinder

Librarian trade search (**available on all versions**). Requires `/carpet villagerTradeRefresh vanilla` or `force` to be enabled first.

| Command | Description |
|---|---|
| `/Tradefinder select <bot>` | Aim at a lectern to bind it to the bot; the bot then repeatedly breaks/replaces the lectern to reroll trades |
| `/Tradefinder stop <bot>` | Stop rerolling |
| `/Tradefinder <bot> <enchantment> <level> <price>` | Search for a trade matching the enchantment / level / max price |

### /Itemfetcher

Fake-player material fetching (**26.x only**): register storage sources, set the material demand, and make bots fetch it.

| Command | Description |
|---|---|
| `/Itemfetcher stock add` | Register the container you are aiming at as a storage source |
| `/Itemfetcher stock addarea <pos1> <pos2>` | Bulk-register an area as storage sources |
| `/Itemfetcher stock remove` | Remove the container you are aiming at |
| `/Itemfetcher stock list` | List all registered storage sources |
| `/Itemfetcher stock clear` | Clear all registrations |
| `/Itemfetcher demand set <item> <count>` | Set the required amount of an item |
| `/Itemfetcher demand show` | Show the current demand list |
| `/Itemfetcher demand clear` | Clear the demand list |
| `/Itemfetcher fetch <bot> <item> [count]` | Make the given bot fetch from storage (no count = as many as possible) |

### /Gatewayfixer

End gateway re-pairing (**26.x only**).

| Command | Description |
|---|---|
| `/Gatewayfixer` | Reset the pairing of the gateway you are aiming at; the next traversal regenerates the exit according to `endGatewayExitSearchDistance` |

## License

This project is released under the **GNU LGPL-3.0** license (see [LICENSE](LICENSE)).
