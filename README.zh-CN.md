# Carpet FLT Addition

**简体中文 | [English](README.md)**

> 本项目由 AI 深度参与开发。

这是一个基于 [Carpet](https://github.com/gnembon/fabric-carpet) (fabric-carpet) 的扩展模组，提供一些实用的规则与假人自动化能力（备货取货、村民交易刷新等）。

适用于 Minecraft 1.16.5 ~ 26.3。

| 游戏版本 | 开发状态 |
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

与同 Minecraft 版本的 Carpet 模组一起使用即可，尽可能使用较新的 Carpet。

## 依赖

- [Carpet（fabric-carpet）](https://modrinth.com/mod/carpet)（必需，尽可能使用较新的版本）
- [Fabric API](https://modrinth.com/mod/fabric-api)（必需，尽可能使用较新的版本）
- [MixinExtras](https://modrinth.com/mod/mixinextras)（已通过 `include(...)` 内置，无需单独安装）
- **FLT Tools**（可选，客户端）——配套客户端模组：取货 GUI + 自动上报材料需求

## 索引

**规则**

- [苦力怕防炸](#苦力怕防炸-nocreepergrief)
- [凋零骷髅不掉石剑](#凋零骷髅不掉石剑-witherskeletonnostonesword)
- [无限弓无箭](#无限弓无箭-infinitybownoarrows)
- [岩浆探索者](#岩浆探索者-lavastrider)
- [缺失工具修复增强](#缺失工具修复增强-missingtoolsplus)
- [拴绳拴矿车](#拴绳拴矿车-leashableminecarts-mc121)
- [限制灾厄巡逻队生成](#限制灾厄巡逻队生成-limitpillagerpatrolspawn-mc1182)
- [砂轮附魔复制](#砂轮附魔复制-grindstoneenchantmentduplication-mc121)
- [村民交易迅捷潜行书](#村民交易迅捷潜行书-villagertradeswiftsneak-mc1194)
- [村民交易风爆书](#村民交易风爆书-villagertradewindburst-mc121)
- [村民交易刷新](#村民交易刷新-villagertraderefresh)
- [光灵箭强制补货](#光灵箭强制补货-forcerestock)
- [绿宝石块吸引村民](#绿宝石块吸引村民-villagersattractedbyemeraldblock)
- [Xaero地图世界名](#xaero地图世界名-xaeromapname)
- [末地折跃门寻出口距离](#末地折跃门寻出口距离-endgatewayexitsearchdistance-mc2612)
- [自动备货](#自动备货-autorestock-mc2612)
- [备货调度间隔](#备货调度间隔-restockintervalticks-mc2612)
- [备货假人名字前缀](#备货假人名字前缀-itemfetcherprefix-mc2612)

**命令**

- [`/Tradefinder`](#tradefinder) —— 图书管理员交易搜索（全版本可用）
- [`/Itemfetcher`](#itemfetcher) —— 假人取货：库存源 / 材料需求 / 取货（**仅 26.x**）
- [`/Gatewayfixer`](#gatewayfixer) —— 末地折跃门重配对（**仅 26.x**）

## 规则列表

### 苦力怕防炸 (noCreeperGrief)

苦力怕爆炸不再破坏地形。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 凋零骷髅不掉石剑 (witherSkeletonNoStoneSword)

凋零骷髅不再掉落石剑（骨头、煤炭、头颅照常掉落）。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 无限弓无箭 (infinityBowNoArrows)

附有无限附魔的弓在背包没有箭时也能射出箭（箭不消耗）。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 岩浆探索者 (lavaStrider)

深海探索者也能在岩浆里使用（游得可快了：D）

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 缺失工具修复增强 (missingToolsPlus)

支持更多工具作为玻璃的有效采集工具。

- 类型: `String`
- 默认值: `#none`
- 参考选项: `#none`, `pickaxe`, `axe`, `shovel`, `hoe`
- 分类: `FLT`, `survival`

### 拴绳拴矿车 (leashableMinecarts) `MC>=1.21`

矿车可以像船一样被拴绳拴住并拖着走。

> 需要**客户端**也安装本模组，才能顺畅地右键拴绳（否则客户端可能把右键预测为“上车”）。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 限制灾厄巡逻队生成 (limitPillagerPatrolSpawn) `MC>=1.18.2`

灾厄巡逻队的生成会受刷怪上限影响。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 砂轮附魔复制 (grindstoneEnchantmentDuplication) `MC>=1.21`

复刻 24w10a ~ 24w11a 快照特性：下槽附魔直接复制到上槽物品上。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 村民交易迅捷潜行书 (villagerTradeSwiftSneak) `MC>=1.19.4`

允许图书管理员售卖迅捷潜行附魔书。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 村民交易风爆书 (villagerTradeWindBurst) `MC>=1.21`

允许图书管理员售卖风爆附魔书。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 村民交易刷新 (villagerTradeRefresh)

用假人刷新图书管理员的交易：`vanilla` = 拆/放讲台；`force` = 服务端直接重掷（快得多）。

> 配合 `/Tradefinder` 命令使用。只有从未与玩家交易过的村民才能刷新（保留原版 `villagerXp == 0` 门槛）。

- 类型: `String`
- 默认值: `false`
- 参考选项: `false`, `vanilla`, `force`
- 分类: `FLT`, `feature`

### 光灵箭强制补货 (forceRestock)

向村民射击光灵箭可强制其补货（无视原版每日 2 次上限）。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 绿宝石块吸引村民 (villagersAttractedByEmeraldBlock)

手持绿宝石块可以吸引附近的村民。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### Xaero地图世界名 (xaeroMapName)

需要**客户端**安装 Xaero 世界地图。解决群组服（BungeeCord / Velocity）地图数据混乱问题。

- 类型: `String`
- 默认值: `#none`
- 分类: `FLT`

### 末地折跃门寻出口距离 (endGatewayExitSearchDistance) `MC>=26.1.2`

末地折跃门配对出口的搜索距离：`0` = 原版（沿半径向外 1024 格），`>0` = 自定义距离。

> 用 `/Gatewayfixer` 重新配对已存在的折跃门。

- 类型: `int`
- 默认值: `0`
- 分类: `FLT`, `feature`

### 自动备货 (autoRestock) `MC>=26.1.2`

按材料需求自动派假人从仓库取货。

> 需先用 `/Itemfetcher stock add` 登记容器。配合 **FLT Tools** 客户端模组可自动上报投影材料需求。

- 类型: `boolean`
- 默认值: `false`
- 参考选项: `true`, `false`
- 分类: `FLT`, `feature`

### 备货调度间隔 (restockIntervalTicks) `MC>=26.1.2`

每隔多少游戏刻扫一次材料缺口并派单。`20` = 1 秒。

- 类型: `int`
- 默认值: `20`
- 分类: `FLT`

### 备货假人名字前缀 (itemFetcherPrefix) `MC>=26.1.2`

备货假人的名字前缀（假人名 = 前缀 + `fetch`（默认值 `flt_` → `flt_fetch`），总长截断到 16 字符）。

> 只影响 FLT 的备货假人，不影响使用 Carpet 全局 `fakePlayerNamePrefix` 的其它假人。

- 类型: `String`
- 默认值: `flt_`
- 分类: `FLT`

## 命令

### /Tradefinder

图书管理员交易搜索（**全部版本可用**）。需先开启 `/carpet villagerTradeRefresh vanilla` 或 `force`。

| 命令 | 说明 |
|---|---|
| `/Tradefinder select <bot>` | 看着讲台执行，把讲台绑定给该假人；随后假人会反复拆/放讲台刷新交易 |
| `/Tradefinder stop <bot>` | 停止刷新 |
| `/Tradefinder <bot> <enchantment> <level> <price>` | 搜索符合附魔 / 等级 / 最高价格的交易 |

### /Itemfetcher

假人取货（**仅 26.x 可用**）：登记库存源、设置材料需求、派假人取货。

| 命令 | 说明 |
|---|---|
| `/Itemfetcher stock add` | 把准星对准的容器登记为库存源 |
| `/Itemfetcher stock addarea <pos1> <pos2>` | 批量登记一个区域为库存源 |
| `/Itemfetcher stock remove` | 移除准星对准的库存源 |
| `/Itemfetcher stock list` | 列出所有已登记库存源 |
| `/Itemfetcher stock clear` | 清空全部登记 |
| `/Itemfetcher demand set <item> <count>` | 设置某物品的需求数量 |
| `/Itemfetcher demand show` | 查看当前需求列表 |
| `/Itemfetcher demand clear` | 清空需求列表 |
| `/Itemfetcher fetch <bot> <item> [count]` | 让指定假人从库存源取货（不填数量 = 尽可能多） |

### /Gatewayfixer

末地折跃门重配对（**仅 26.x 可用**）。

| 命令 | 说明 |
|---|---|
| `/Gatewayfixer` | 重设准星所对准折跃门的配对；下次穿越时按 `endGatewayExitSearchDistance` 重新生成出口 |

## 许可证

本项目以 **GNU LGPL-3.0** 发布（见 [LICENSE](LICENSE)）。
