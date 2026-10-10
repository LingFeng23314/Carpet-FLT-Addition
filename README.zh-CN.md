# Carpet FLT Addition

**简体中文 | [English](README.md)**

这是一个基于 [Carpet](https://github.com/gnembon/fabric-carpet) (fabric-carpet) 的扩展模组，提供实用的规则与假人自动化能力，例如备货取货和村民交易刷新。

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
- [末地折跃门出口半径](#末地折跃门出口半径-endgatewayexitsearchdistance-mc2612)
- [末地折跃门造岛高度](#末地折跃门造岛高度-endgatewayislandheight-mc2612)
- [自动备货](#自动备货-autorestock-mc2612)
- [备货调度间隔](#备货调度间隔-restockintervalticks-mc2612)
- [备货假人名字前缀](#备货假人名字前缀-itemfetcherprefix-mc2612)
- [并行备货假人](#并行备货假人-itemfetcherparallel-mc2612)
- [强制命令反馈](#强制命令反馈-forcecommandfeedback-mc2612)

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

### 末地折跃门出口半径 (endGatewayExitSearchDistance) `MC>=26.1.2`

末地折跃门配对出口的半径：`0` = 原版（从径向 1024 格起、沿射线找最近的一座空岛，最终位置随地形浮动）；`>0` = 让出口**精确落在该半径上**（如 `768`）——那里没有岛会就地生成一座小岛。

> 原版的 `1024` 只是那趟搜索的**起点**，落点由地形决定。想要可预期的距离就设一个正值。
> 用 `/Gatewayfixer` 重新配对已存在的折跃门。

- 类型: `int`
- 默认值: `0`
- 分类: `FLT`, `feature`

### 末地折跃门造岛高度 (endGatewayIslandHeight) `MC>=26.1.2`

出口处没有现成岛时、就地在虚空里生成的那座小岛的基准高度：`0` = 原版（`Y=75`），`>0` = 自定义（如 `64`）。折跃门本身随后落在该高度上方 10 格。

> 只影响新造的岛。若出口处已经有现成的岛，它的高度不会被改动。

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

备货假人的名字前缀（假人名 = 前缀 + `fetch` + 玩家序号，如默认值 `flt_` → `flt_fetch1`、`flt_fetch2`；每个玩家一个专属假人，多人同时备货互不干扰；总长截断到 16 字符）。

> 只影响 FLT 的备货假人，不影响使用 Carpet 全局 `fakePlayerNamePrefix` 的其它假人。

- 类型: `String`
- 默认值: `flt_`
- 分类: `FLT`

### 并行备货假人 (itemFetcherParallel) `MC>=26.1.2`

一份备货清单最多拆给几个假人同时取货。`1` = 关闭并行（完全走原来的单假人路径）。开启后按
**数量**切分：先勘察已登记库存箱里每种材料实际有多少、把需求裁到有货的量，再均分给各个假人。
取不到的份额（例如箱子已被另一个假人先取空）会自动排队，等你把这批取走后继续补。

> 每多一个假人就是多一个完整玩家实体，交付时它们会一起走到你身边，所以建议 `1`~`4`。
> 超过 `4` 会被按 `4` 执行并在聊天栏提示，不会静默忽略。

- 类型: `int`
- 默认值: `1`
- 分类: `FLT`, `feature`

### 强制命令反馈 (forceCommandFeedback) `MC>=26.1.2`

把游戏规则 `send_command_feedback` 锁死为 `true`：`/gamerule`、客户端设置包、JSON-RPC、存档载入等
任何关闭命令反馈的操作都不会生效，查询恒为 `true`。

> 需要它的原因：命令反馈一旦被关掉，`/setblock`、`/execute` 等命令就不再回显结果，
> 备货 / 交易刷新等流程的诊断信息会集体"哑掉"，排查起来毫无线索。

- 类型: `boolean`
- 默认值: `false`
- 可选值: `true`、`false`
- 分类: `FLT`, `feature`

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
| `/Gatewayfixer` | 重设准星所对准折跃门的配对；下次穿越时按 `endGatewayExitSearchDistance` 重新生成出口（`>0` 精确落在该半径，`0` 走原版找岛） |

## 许可证

本项目以 **GNU LGPL-3.0** 发布（见 [LICENSE](LICENSE)）。
