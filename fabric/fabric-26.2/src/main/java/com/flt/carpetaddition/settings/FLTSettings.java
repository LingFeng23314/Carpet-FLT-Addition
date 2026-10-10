package com.flt.carpetaddition.settings;

import carpet.api.settings.Rule;
import static carpet.api.settings.RuleCategory.FEATURE;
import static carpet.api.settings.RuleCategory.SURVIVAL;

/** FLT 规则声明（@Rule 字段）。字段名=规则名（统一 camelCase，对齐 Carpet/ORG/LMS 惯例）；类型=规则类型；初值=默认值。 */
public class FLTSettings {
    /** 本模组的自定义分类（/carpet list FLT 可过滤本模组规则） */
    public static final String FLT = "FLT";

    /** 苦力怕爆炸不破坏方块（保留伤害，默认 false） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean noCreeperGrief = false;

    /** 无限弓无箭：附 Infinity 的弓无箭时也能射击（恢复 1.21.11 及以前的旧行为） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean infinityBowNoArrows = false;

    /** 岩浆探索者：深海探索者在岩浆中也生效（复用水的移动逻辑） */
    @Rule(categories = {FLT, FEATURE})
    public static boolean lavaStrider = false;

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

    /**
     * 末地折跃门配对出口的半径：0 = 原版（径向 1024 格起、沿射线找最近的一座空岛，落点随地形浮动）；
     * &gt;0 = 让配对门<b>精确落在距原点该半径处</b>（如 768）—— 那里已有岛就落在岛上，是虚空则就地生成一座小岛。
     * 改后可用 /Gatewayfixer 让已存在的门重新成对。
     */
    @Rule(categories = {FLT, FEATURE})
    public static int endGatewayExitSearchDistance = 0;

    /**
     * 末地折跃门造岛高度：仅当出口处<b>没有现成的岛</b>、需要当场生成一座时生效。
     * 原版把生成基准写死 Y=75（{@code EndIslandFeature} 以该点为最高层向下逐层收缩，
     * 所以基准高度 ≈ 岛顶高度；门随后落在此高度 +10，见 {@code getPortalPosition} 的 above(10)）。
     * 0 = 原版（Y=75）；&gt;0 = 自定义（如 64）。⚠️ 已有现成岛的位置不受影响。
     */
    @Rule(categories = {FLT, FEATURE})
    public static int endGatewayIslandHeight = 0;

    /** 自动备货总开关：开启后按客户端上报/命令设置的投影材料需求，派假人从库存源箱子自动取货 */
    @Rule(categories = {FLT, FEATURE})
    public static boolean autoRestock = false;

    /** 自动备货调度间隔（游戏刻）：每隔这么多刻扫一次缺口并派单，默认 20（1 秒） */
    @Rule(categories = {FLT}, strict = false)
    public static int restockIntervalTicks = 20;

    /**
     * 备货假人名字前缀：备货/取货假人的名字 = 此前缀 + "fetch" + 玩家序号
     * （默认值 flt_ → 假人名 flt_fetch1、flt_fetch2、flt_fetch3…），总长 ≤ 16。
     *
     * <p>2026-09-28 起<b>每个玩家一个专属假人</b>（以前是全服共用一个 flt_fetch，
     * 多人同时备货会互相抢、还会被回收逻辑顶掉任务）。序号按玩家分配、会话内稳定、互不干扰。
     *
     * <p>⚠️ 只影响 FLT 的备货假人，不影响你们改 Carpet 全局 {@code fakePlayerNamePrefix} 的其它假人。
     * 这是 FLT 自己注册的 carpet 规则（@Rule），可在游戏里 <carpet> 命令 / CarpCat 里改。
     * 命名统一走 {@code FakePlayerNaming.botNameFor(UUID)}，前缀超长会<b>先截前缀</b>（保序号）
     * 到 16 字符内（原版玩家名上限，否则假人进服广播 player_info_update 会 "String too big" 踢人）。
     */
    @Rule(categories = {FLT}, strict = false)
    public static String itemFetcherPrefix = "flt_";

    /**
     * 并行备货假人上限：一份需求清单最多拆给几个假人同时取货（1 = 关闭并行，保持原行为）。
     *
     * <p>并行怎么分：按<b>数量</b>切分。派单前先勘察需求里每种物品在库存源里的实际可提取量
     * （口径与取货链路一致），把「需求量」裁到确实有货的量，再按数量均分给 N 个假人 ——
     * 于是 N 个子任务量之和 = 实际能取到的总量上限，不会出现"某人分到一片、结果箱里已经没货"。
     *
     * <p>⚠️ 为什么必须配合"勘察 + 补差"：取货目标槽是<b>派单那一刻</b>扫出来的快照
     * （见 {@code MultiContainerWithdrawAction.collectTargets}），两个假人盯同一箱货时会各自
     * 以为"有这么多"，先到的取走、后到的取空 → 少取。所以并行必须带补差兜底，不能只切分了事：
     * 每个假人取不到的那部分会自动登记成「待续备货」，玩家取走这批后继续补。
     *
     * <p>名字怎么编：第 k 个假人的序号由 {@code FakePlayerNaming.indexFor(playerId, k)} 分配，
     * 槽位 0 就是原来的那个人 —— 所以设回 1 时行为与改造前完全一致，已暂存在主假人末影箱里的
     * 物资也不会错位。
     *
     * <p>建议范围 1~4（实体与寻路开销随数量线性增长，交付时它们会一起站到玩家身边）。
     * 超出范围会被<b>按上限 4 执行</b>并在聊天栏告知，不是静默忽略。
     */
    @Rule(categories = {FLT, FEATURE})
    public static int itemFetcherParallel = 1;

    /**
     * 命令反馈强制开启：把原版游戏规则 {@code send_command_feedback} 锁死为 true。
     *
     * <p>开启后，任何试图关闭命令反馈的操作都不会生效 —— 包括
     * {@code /gamerule send_command_feedback false}、JSON-RPC 修改、以及存档载入时读到的旧 false 值；
     * {@code /gamerule send_command_feedback} 查询也会恒为 true。
     *
     * <p>为什么需要它：命令反馈一旦被关掉，{@code /setblock}、{@code /execute} 等命令就不再回显结果，
     * 本方块的假人备货 / 交易刷新等流程依赖的命令结果反馈会集体"哑掉"，排查起来毫无线索。
     * 用本规则上锁，可防止有人（或某些插件/存档）误关。
     *
     * <p>实现见 {@code mixin/world/CommandFeedbackMixin}（26.x 的 GameRules 已重写为泛型
     * {@code get(GameRule<T>)} / {@code set(GameRule<T>,T,MinecraftServer)}，所有读写都收敛在这两点）。
     */
    @Rule(categories = {FLT, FEATURE})
    public static boolean forceCommandFeedback = false;
}