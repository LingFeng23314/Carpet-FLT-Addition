package com.flt.carpetaddition.settings;

//# [VERSION] carpet API 包路径分界 fabric-1.19.4（carpet 1.4.69 jar 类表实证）：
// 1.19.4+（carpet 1.4.101+）= carpet.api.settings；1.18.2-（carpet 1.4.69-）= carpet.settings
// IF >= fabric-1.19.4
//import carpet.api.settings.Rule;
//import static carpet.api.settings.RuleCategory.FEATURE;
//import static carpet.api.settings.RuleCategory.SURVIVAL;
// ELSE IF <= fabric-1.18.2
import carpet.settings.Rule;
import static carpet.settings.RuleCategory.FEATURE;
import static carpet.settings.RuleCategory.SURVIVAL;
// ELSE
//#    （理论不可达：版本号必在 >= fabric-1.19.4 或 <= fabric-1.18.2 之一）
// END IF

/**
 * FLT 规则声明（@Rule 字段）。
 * 字段名=规则名（统一 camelCase，对齐 Carpet/ORG/LMS 惯例）；类型=规则类型；初值=默认值。
 * [VERSION] @Rule 注解成员分界 fabric-1.19.4：1.19.4+ 用 categories()，1.18.2- 用 category() + 必需 desc()。
 */
public class FLTSettings {
    /** 本模组的自定义分类（/carpet list FLT 可过滤本模组规则） */
    public static final String FLT = "FLT";

    /** 苦力怕爆炸不破坏方块（保留伤害，默认 false） */
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT, FEATURE})
// ELSE IF <= fabric-1.18.2
@Rule(category = {FLT, FEATURE}, desc = "Creepers do not destroy blocks")
// ELSE
//#    （理论不可达）
// END IF
    public static boolean noCreeperGrief = false;

    /** 凋零骷髅不掉石剑（骨头/煤炭/头颅照常掉落） */
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT, FEATURE})
// ELSE IF <= fabric-1.18.2
@Rule(category = {FLT, FEATURE}, desc = "Wither skeletons no longer drop their stone sword")
// ELSE
//#    （理论不可达）
// END IF
    public static boolean witherSkeletonNoStoneSword = false;

    /** 缺失工具修复增强：指定工具类型挖玻璃获得正确速度。对齐 fabric-carpet 官方 missingTools */
// IF >= fabric-1.19.4
//    @Rule(
//            categories = {FLT, SURVIVAL},
//            options = {"#none", "pickaxe", "axe", "shovel", "hoe"}
//    )
// ELSE IF <= fabric-1.18.2
@Rule(
category = {FLT, SURVIVAL},
options = {"#none", "pickaxe", "axe", "shovel", "hoe"},
desc = "Set tool type for proper glass mining speed"
)
// ELSE
//#    （理论不可达）
// END IF
    public static String missingToolsPlus = "#none";

    /** 岩浆探索者：深海探索者在岩浆中也生效（复用水的移动逻辑） */
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT, FEATURE})
// ELSE IF <= fabric-1.18.2
@Rule(category = {FLT, FEATURE}, desc = "Depth Strider works in lava")
// ELSE
//#    （理论不可达）
// END IF
    public static boolean lavaStrider = false;

    /** Xaero 地图世界名：发 worldId = CRC32(xaeroMapName) 给 Xaero 客户端，实现多世界地图分离 */
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT}, strict = false)
// ELSE IF <= fabric-1.18.2
@Rule(category = {FLT}, strict = false, desc = "World name for Xaero map separation")
// ELSE
//#    （理论不可达）
// END IF
    public static String xaeroMapName = "#none";

// IF >= fabric-1.19.4
//#    /** 图书管理员出售迅捷潜行书（替换 EnchantBookFactory 选书标签为 flt:tradeable_swift） */
//    @Rule(categories = {FLT, FEATURE})
//    public static boolean villagerTradeSwiftSneak = false;
// END IF
//
// IF >= fabric-1.21
//#    /** 图书管理员出售风爆书（标签 flt:tradeable_wind） */
//    @Rule(categories = {FLT, FEATURE})
//    public static boolean villagerTradeWindBurst = false;
// END IF

    /** 光灵箭强制补货：射中村民立即重置全部交易次数（无视每日 2 次上限） */
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT, FEATURE})
// ELSE IF <= fabric-1.18.2
@Rule(category = {FLT, FEATURE}, desc = "Spectral arrow forces villager restock")
// ELSE
//#    （理论不可达）
// END IF
    public static boolean forceRestock = false;

    /** 村民吸引：玩家手持绿宝石块时，周围村民被吸引走过来 */
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT, FEATURE})
// ELSE IF <= fabric-1.18.2
@Rule(category = {FLT, FEATURE}, desc = "Villagers attracted by emerald block")
// ELSE
//#    （理论不可达）
// END IF
    public static boolean villagersAttractedByEmeraldBlock = false;

    /** 限制巡逻队生成：巡逻队生成受刷怪上限影响（默认 false，原版无视上限） */
//# [VERSION] PatrolSpawner 类 1.18.2+ 才有；1.16.5/1.17.1 字段保留但无 @Rule（规则不生成）
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT, FEATURE})
// ELSE IF >= fabric-1.18.2
//    @Rule(category = {FLT, FEATURE}, desc = "Pillager patrols count to mob cap")
// ELSE
//#    （1.16.5/1.17.1：无 @Rule——规则不生成）
// END IF
    public static boolean limitPillagerPatrolSpawn = false;

    /**
     * 无限弓无箭：附 Infinity 的弓无箭时也能拉弓射击（兜底返回 1 根不消耗的普通箭）。
     * 注入玩家找箭方法 RETURN；全版本原版找箭兜底只看创造模式，本规则恢复旧行为。
     */
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT, FEATURE})
// ELSE IF <= fabric-1.18.2
    @Rule(category = {FLT, FEATURE}, desc = "Bows with Infinity can shoot without arrows")
// ELSE
//#    （理论不可达）
// END IF
    public static boolean infinityBowNoArrows = false;

    /**
     * 拴绳拴矿车：矿车实现 Leashable 接口后可被拴绳拴住/拖着走。
     * [VERSION] Leashable 1.20.5+ 才有；矿车拴绳规则仅 1.21+ 实现。
     */
//# [VERSION] 拴绳规则仅 1.21+ 生成 @Rule
// IF >= fabric-1.21
//    @Rule(categories = {FLT, FEATURE})
// END IF
    public static boolean leashableMinecarts = false;

    /**
     * 砂轮附魔复制：复刻 24w10a~24w11a 快照 bug，下槽附魔直接复制到上槽（上槽输入被就地修改）。
     * [VERSION] 砂轮附魔数据化（transferEnchantments）仅 1.21+ 才有；1.20.1- 无此方法，规则不生成。
     */
//# [VERSION] 砂轮附魔复制规则仅 1.21+ 生成 @Rule
// IF >= fabric-1.21
//    @Rule(categories = {FLT, FEATURE})
// END IF
    public static boolean grindstoneEnchantmentDuplication = false;

    /**
     * 村民交易刷新：false=关闭；vanilla=原版（假人拆/放讲台）；force=原版+直接刷新（服务端直掷）。
     * 均保留 villagerXp==0 门槛（已交易过的村民不再刷新，严格对齐原版限制）。
     * [VERSION] 假人刷交易整套仅 1.21+ 实现（依赖 1.20.5+ 物品组件 + 1.21+ 交易/命令 API）。
     */
// IF >= fabric-1.19.4
//    @Rule(categories = {FLT, FEATURE}, options = {"false", "vanilla", "force"})
// ELSE
@Rule(category = {FLT, FEATURE}, options = {"false", "vanilla", "force"},
        desc = "Fake players reroll librarian trades at their bound lectern")
// END IF
    public static String villagerTradeRefresh = "false";
}