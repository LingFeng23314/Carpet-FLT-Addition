package com.flt.carpetaddition.settings;

import carpet.api.settings.Rule;
import static carpet.api.settings.RuleCategory.FEATURE;
import static carpet.api.settings.RuleCategory.SURVIVAL;

/** FLT 规则声明（@Rule 字段）。字段名=命令名（首字母大写是用户指定，不要改）；类型=规则类型；初值=默认值。 */
public class FLTSettings {
    /** 本模组的自定义分类（/carpet list FLT 可过滤本模组规则） */
    public static final String FLT = "FLT";

    /** 苦力怕爆炸不破坏方块（保留伤害，默认 false） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean NoCreeperGrief = false;

    /** 凋零骷髅不掉石剑（骨头/煤炭/头颅不受影响）。spawnAtLocation 单/双参分界 1.21.2 */
    @Rule(categories = {FLT, FEATURE})
    public static boolean witherSkeletonNoStoneSword = false;

    /** 无限弓无箭：附 Infinity 的弓无箭时也能射击（恢复 1.21.11 及以前的旧行为） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean infinityBowNoArrows = false;

    /** 岩浆探索者：深海探索者在岩浆中也生效（复用水的移动逻辑） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean LavaStrider = false;

    /** 缺失工具修复增强：指定工具类型挖玻璃获得正确速度（对齐 fabric-carpet 官方 missingTools） */
    @Rule(categories = {FLT, SURVIVAL}, options = {"#none", "pickaxe", "axe", "shovel", "hoe"})
    public static String missingToolsPlus = "#none";

    /** 拴绳拴矿车：矿车实现 Leashable 接口后可被拴绳拴住/拖着走 */
    @Rule(categories = {FLT, FEATURE})
    public static boolean leashableMinecarts = false;

    /** 村民吸引：玩家手持绿宝石块时，周围村民被吸引走过来 */
    @Rule(categories = {FLT, FEATURE})
    public static boolean villagersAttractedByEmeraldBlock = false;

    /** 光灵箭强制补货：射中村民立即重置全部交易次数（无视每日 2 次上限） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean forceRestock = false;

    /** 限制巡逻队生成：巡逻队生成受刷怪上限影响（默认 false，原版无视上限） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean limitPillagerPatrolSpawn = false;

    /** 图书管理员出售迅捷潜行书。1.18.2- 不生成此规则（swift_sneak 1.19+） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean villagerTradeSwiftSneak = false;

    /** 图书管理员出售风爆书。1.20.1- 不生成此规则（wind_burst 1.21+） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean villagerTradeWindBurst = false;

    /** 村民交易刷新：false=关闭；vanilla=原版（假人拆/放讲台）；force=原版+直接刷新（服务端直掷）。均保留 villagerXp==0 门槛 */
    @Rule(categories = {FLT, FEATURE}, options = {"false", "vanilla", "force"})
    public static String villagerTradeRefresh = "false";

    /** 砂轮附魔复制：复刻 24w10a~24w11a 快照特性，下槽附魔直接复制到上槽（上槽输入被就地修改） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean grindstoneEnchantmentDuplication = false;

    /** Xaero 地图世界名：发 worldId = CRC32(xaeroMapName) 给客户端，实现多世界地图分离 */
    @Rule(categories = {FLT}, strict = false)
    public static String xaeroMapName = "#none";

    /** 末地折跃门寻出口距离：0=原版（沿径向向外 1024 格找空岛）；>0 = 自定义距离（如 768）会让回程门生成在更近/更远的岛屿上 */
    @Rule(categories = {FLT, FEATURE})
    public static int endGatewayExitSearchDistance = 0;

    /** 自动备货总开关：开启后按客户端上报/命令设置的投影材料需求，派假人从库存源箱子自动取货 */
    @Rule(categories = {FLT, FEATURE})
    public static boolean autoRestock = false;

    /** 自动备货调度间隔（游戏刻）：每隔这么多刻扫一次缺口并派单，默认 20（1 秒） */
    @Rule(categories = {FLT}, strict = false)
    public static int restockIntervalTicks = 20;

    /**
     * 备货假人名字前缀：备货/取货假人的名字 = 此前缀 + "carry"（如 Fltcarry），总长 ≤ 16。
     *
     * <p>⚠️ 只影响 FLT 的备货假人，不影响你们改 Carpet 全局 {@code fakePlayerNamePrefix} 的其它假人。
     * 这是 FLT 自己注册的 carpet 规则（@Rule），可在游戏里 <carpet> 命令 / CarpCat 里改。
     * 命名统一走 {@code FakePlayerNaming.botNameFor()}，前缀超长会硬截到 16 字符内
     * （原版玩家名上限，否则假人进服广播 player_info_update 会 "String too big" 踢人）。
     */
    @Rule(categories = {FLT}, strict = false)
    public static String fltFakePlayerPrefix = "FLT";
}