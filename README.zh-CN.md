# Carpet FLT Addition

**[English](README.md) | 简体中文**

> 本项目在开发中大量使用 AI 辅助。

[Fabric Carpet](https://github.com/gnembon/fabric-carpet) 的扩展模组，提供一些实用的功能性规则与假人自动化能力。

支持 Minecraft **1.16.5 ~ 26.3**。

---

## 目录

- [功能总览](#功能总览)
- [依赖](#依赖)
- [安装](#安装)
- [命令](#命令)
- [规则列表](#规则列表)
- [版本支持](#版本支持)
- [许可证](#许可证)

---

## 功能总览

本模组包含三个部分：

| 部分 | 说明 |
|---|---|
| **Carpet 规则** | 18 条规则，通过 `/carpet` 命令或 carpet 配置界面开关 |
| **假人备货系统** | 按投影材料需求自动派假人从仓库取货备齐；也支持手动向假人取货 |
| **客户端联动** | 配合客户端模组 **FLT Tools** 使用（取货界面、材料自动上报等） |

### 假人备货系统怎么用

1. 用 `/flt stock add`（瞄准箱子）或 `/flt stock addarea <pos1> <pos2>` 登记库存源箱子
2. 开启 `/carpet autoRestock true`，并在客户端安装 **FLT Tools** 以自动上报 Litematica 投影的材料需求
   （也可以手动 `/flt demand set <物品> <数量>` 指定需求）
3. 假人会自动从库存箱取货备齐；取好后送到你身边，你从它背包取走即可

> 客户端 FLT Tools 提供完整的**取货界面**：热键打开 → 选物品（显示仓库实时库存）→ 设数量 → 确认 → 假人送达。

---

## 依赖

| 名称 | 类型 | 说明 |
|---|---|---|
| [Carpet](https://modrinth.com/mod/carpet) | **必需** | 建议使用同一游戏版本的最新版 |
| [Fabric API](https://modrinth.com/mod/fabric-api) | **必需** | 建议使用最新版 |
| [MixinExtras](https://modrinth.com/mod/mixinextras) | 内置 | 已通过 `include(...)` 打包，无需单独安装 |
| **FLT Tools** | 可选（客户端） | 客户端配套模组，提供取货 GUI 与材料自动上报 |

---

## 安装

1. 服务端（或单人世界的 `.minecraft/mods/`）安装 **Carpet** + **Fabric API**
2. 把本模组的 `carpet-flt-addition-<版本>+<MC版本>.jar` 放入 `mods/`
3. 重启服务器 / 游戏

> 规则通过 `/carpet <规则名> <值>` 或在 Carpet 的配置文件里开关。
> 假人相关功能（`autoRestock` 等）需要服务端为**集成服务器或专用服务器**。

---

## 命令

所有命令以 `/flt` 为根（`/Tradefinder` 独立）。

### `/flt stock` —— 库存源管理

| 命令 | 说明 |
|---|---|
| `/flt stock add` | 把**准星对准的容器**登记为库存源 |
| `/flt stock remove` | 移除准星对准的容器 |
| `/flt stock list` | 列出已登记的库存源 |
| `/flt stock clear` | 清空全部登记 |
| `/flt stock addarea <pos1> <pos2>` | 按区域批量登记（选区过大时会提示缩小范围） |

### `/flt demand` —— 材料需求管理

| 命令 | 说明 |
|---|---|
| `/flt demand set <物品> <数量>` | 设置某物品的需求量 |
| `/flt demand show` | 显示当前需求清单 |
| `/flt demand clear` | 清空需求 |

### `/flt fetch` —— 手动派假人取货

| 命令 | 说明 |
|---|---|
| `/flt fetch <假人名> <物品> [数量]` | 让指定假人从库存源取货（不填数量 = 尽可能多取） |

### `/flt endgate` —— 末地折跃门

| 命令 | 说明 |
|---|---|
| `/flt endgate reset` | 重置准星对准的折跃门配对，下次穿越时按 `endGatewayExitSearchDistance` 重新生成配对门 |

### `/Tradefinder` —— 图书管理员交易查找

| 命令 | 说明 |
|---|---|
| `/Tradefinder select <假人名>` | 瞄讲台绑定：让该假人反复拆放讲台刷新交易 |
| `/Tradefinder stop <假人名>` | 停止刷新 |
| `/Tradefinder <假人名> <附魔> <等级> <价格>` | 查找符合条件（附魔 / 等级 / 价格上限）的交易 |

> 需先开启规则 `/carpet villagerTradeRefresh vanilla` 或 `force`。

---

## 规则列表

### 通用

| 规则 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `NoCreeperGrief` | boolean | `false` | 苦力怕爆炸不再破坏地形 |
| `infinityBowNoArrows` | boolean | `false` | 无限附魔的弓在背包没箭时也能射出（箭不消耗） |
| `LavaStrider` | boolean | `false` | 深海探索者也能在岩浆里使用 |
| `missingToolsPlus` | String | `#none` | 让更多工具可作为玻璃的有效采集工具（`pickaxe` / `axe` / `shovel` / `hoe`） |
| `leashableMinecarts` | boolean | `false` | 矿车可以像船一样被拴绳牵走（需客户端也安装本模组以获得顺滑体验） |
| `limitPillagerPatrolSpawn` | boolean | `false` | 灾厄巡逻队生成受刷怪上限影响 |
| `grindstoneEnchantmentDuplication` | boolean | `false` | 砂轮附魔复制（复刻 24w10a~24w11a 快照特性，不需经验） |
| `witherSkeletonNoStoneSword` | boolean | `false` | 凋零骷髅不再掉落石剑（骨头/煤炭/头颅照常） |

### 村民与交易

| 规则 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `villagerTradeSwiftSneak` | boolean | `false` | 图书管理员可售卖迅捷潜行附魔书 |
| `villagerTradeWindBurst` | boolean | `false` | 图书管理员可售卖风爆附魔书 |
| `villagerTradeRefresh` | String | `false` | 假人刷新图书管理员交易：`vanilla`=拆放讲台；`force`=服务端直接重掷（快得多） |
| `forceRestock` | boolean | `false` | 用光灵箭射村民可强制其补货 |
| `villagersAttractedByEmeraldBlock` | boolean | `false` | 手持绿宝石块可吸引附近村民 |

### 假人备货

| 规则 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `autoRestock` | boolean | `false` | 按材料需求自动派假人取货备齐（需先用 `/flt stock add` 登记箱子） |
| `restockIntervalTicks` | int | `20` | 每隔多少游戏刻扫一次缺口并派单 |
| `fltFakePlayerPrefix` | String | — | 备货假人名字前缀（前缀+玩家名会被截到 16 字符，过长会导致客户端被踢） |

### 其它

| 规则 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `xaeroMapName` | String | `#none` | 需客户端安装 Xaero 世界地图；解决群组服（BungeeCord / Velocity）地图数据混乱 |
| `endGatewayExitSearchDistance` | int | `0` | 末地折跃门配对出口的搜索距离；`0`=原版 1024 格，`>0` 自定义（改后可用 `/flt endgate reset` 让旧门重新成对） |

---

## 版本支持

| 游戏版本 | 状态 |
|---|---|
| 1.16.5 | 维护中 |
| 1.17.1 | 维护中 |
| 1.18.2 | 维护中 |
| 1.19.4 | 维护中 |
| 1.20.1 | 维护中 |
| 1.21 ~ 1.21.1 | 维护中 |
| 1.21.2 ~ 1.21.3 | 维护中 |
| 1.21.4 | 维护中 |
| 1.21.5 | 维护中 |
| 1.21.6 | 维护中 |
| 1.21.7 ~ 1.21.8 | 维护中 |
| 1.21.9 | 维护中 |
| 1.21.10 | 维护中 |
| 1.21.11 | 维护中 |
| 26.1.2 | 维护中 |
| 26.2 | 维护中 |
| 26.3 | 维护中 |

> 请使用与游戏版本对应的 Carpet；优先使用最新版。

---

## 许可证

本项目以 **GNU LGPL-3.0** 发布，详见 [LICENSE](LICENSE)。

开发中参考或移植了若干第三方项目（Carpet-Org-Addition、Carpet-LMS-Addition 等），
完整的来源、作者与许可条款见 **[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)**。
