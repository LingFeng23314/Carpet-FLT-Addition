package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import com.flt.carpetaddition.network.FakePlayerStockPayload;
import com.flt.carpetaddition.settings.FLTSettings;
import com.flt.carpetaddition.storage.DemandRegistry;
import com.flt.carpetaddition.storage.InventoryCounter;
import com.flt.carpetaddition.storage.StockScanner;
import com.flt.carpetaddition.storage.StockSource;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 备货假人调度器（组合拳二期核心）。
 *
 * <p>两条派单入口，<b>每个玩家一个专属备货假人</b>（命名走 {@link FakePlayerNaming#botNameFor(java.util.UUID)}，
 * 名字 = FLT 自己的 carpet 规则 {@code itemFetcherPrefix} + "fetch" + 该玩家序号，
 * 如 {@code flt_fetch1} / {@code flt_fetch2}，总长 ≤ 16）。改为一号一人后，多人同时备货互不干扰：
 * <p>[VERSION] 2026-09-29 起支持<b>一份清单多假人并行</b>：规则 {@link FLTSettings#itemFetcherParallel}
 * 大于 1 时，需求表会先按库存实际可提取量裁一遍、再按数量切给至多 4 个假人同时取货
 * （见 {@link #startParallelRestock}）。默认 1 = 完全走原来的单假人路径。
 * <ul>
 *   <li><b>定时批量</b> {@link #tick}：按 {@link DemandRegistry} 的需求算缺口，每个周期只派一件</li>
 *   <li><b>即时单件</b> {@link #fetchSingle}：客户端投影中键取货，直接指定物品与数量</li>
 * </ul>
 *
 * <p>限速思路：假人正忙（{@code isRunning}）或仍在建档就跳过本次，不排队 —— 天然限速，
 * 避免一 tick 派爆任务，也不用额外维护"忙"状态。取货终点是假人背包（中转身），
 * 取完由 {@link #pushStock} 把假人身上计数 S2C 回推，客户端材料列表即可显示"已备多少"。
 */
public final class RestockScheduler {
    /** 调度节拍计数（只在主线程读写） */
    private static int tickCounter = 0;

    // =========================================================
    // 待派单队列（修「要点两次取货才生效」）
    // =========================================================

    /**
     * 排队中的取货请求。
     *
     * <p><b>为什么要有这个队列</b>：假人是"按需建档"的，而 Carpet 的建档是
     * <b>异步分步</b>的（见 {@link FakePlayerFactory}：createFake 立即返回，真正的实体要过几刻
     * 才进玩家列表）。所以玩家第一次点「确认取货」时假人还不存在 → 老实现直接
     * {@code return false} 不派单，玩家必须再点一次（这时假人刚建好）——
     * 表现就是"要点两次才拿到货"。现在改成：派不出去就排队，等假人可用后自动补派。
     */
    private record PendingFetch(UUID playerId, Identifier itemId, int count, boolean boxMode,
                                long queuedAtMs) {
    }

    /**
     * <b>取货缓存池</b>：玩家 UUID → 该玩家的待派单取货请求（先到先做）。
     *
     * <p>[VERSION] 2026-09-29 从"每假人一条队列"改为"每玩家一条"（用户需求："加一个假人取货缓存，
     * 多人同时取货时按缓存空间顺序分配"）。原因：开启并行后同一玩家有多个假人，请求按玩家排队、
     * 派单时再挑"空闲且缓存空间最充裕"的那一个（见 {@link #pickIdleBot}）——
     * 队列与具体假人<b>解耦</b>后，某个假人满载（背包腾不出格、末影箱也满）时请求就留在队首<b>等</b>，
     * 等玩家取走货、空间腾出来再自动继续；既不丢请求，也不会"少取却报完成"。
     */
    private static final Map<UUID, ArrayDeque<PendingFetch>> PENDING_FETCH = new HashMap<>();

    /** 单个玩家最多允许排多少件（防连点把队列刷爆；该玩家的多个假人共享这一条队列） */
    private static final int MAX_PENDING_PER_PLAYER = 8;

    /**
     * 排队多久还没派出去就放弃并告知玩家（毫秒）。
     *
     * <p>为什么要有超时：假人可能永远建不起来（createFake 被拒、库存源被清空、服务端卡住），
     * 没超时的话请求会永远挂在队列里，玩家点了界面却什么反馈都没有。
     */
    private static final long PENDING_TIMEOUT_MS = 15_000L;

    /**
     * 假人<b>已就绪</b>（只是忙 / 没空间）时，请求在取货缓存池里最多等多久（毫秒）。
     *
     * <p>[VERSION] 2026-09-29 自查修复：与 {@link #PENDING_TIMEOUT_MS} 分开，是为了让"排队等空间"
     * 这件事<b>真正可行</b>。玩家从收到"请先取货"的提示、走过去、取完货、再让假人腾出格，
     * 通常远超 15 秒 —— 若两种情况共用一个 15 秒阈值，请求会在玩家动手之前先消失，
     * 那"缓存等待"就形同虚设（这正是本轮引入的第二个不一致）。
     *
     * <p>取 10 分钟，与分批续备 {@code PENDING_RESTOCK_TIMEOUT_MS} 同量级，保持一致。
     */
    private static final long PENDING_SPACE_TIMEOUT_MS = 600_000L;   // 10 分钟

    /** 实际允许输入的最大数量（与客户端数量框上限一致） */
    private static final int MAX_COUNT = 99_999;

    // =========================================================
    // 假人回收（按需派单 → 用完下线）
    // =========================================================

    /**
     * 待回收的假人：UUID → 回收状态。
     *
     * <p>为什么需要：假人建出来之后<b>不会自己消失</b>，而它身上背着从箱子里取出的货
     * （既不在箱子、也不在玩家手上）。不回收就会出现"进世界起假人常驻、一直占着物资"的现象。
     */
    private static final Map<UUID, RecycleState> PENDING_RECYCLE = new HashMap<>();

    /** 交付后等玩家自己取货的时长（毫秒）；超时后开始尝试把货自动转交玩家 */
    private static final long RECYCLE_IDLE_MS = 120_000L;

    /** 自动转交的重试间隔（毫秒）：玩家背包没空位时，过这么久再试一次（静默） */
    private static final long RECYCLE_RETRY_MS = 30_000L;

    // =========================================================
    // 分批备货（末影箱满导致的中断 → 玩家取走后自动续备）
    // =========================================================

    /**
     * 待续备货：UUID → 剩余需求。
     *
     * <p>为什么需要：备货容量 = 假人背包 + 末影箱。两者都满时只能备到容量上限为止，
     * 若直接收工，超出容量的需求会被静默丢掉（玩家看到"备货完成"，其实只备了一部分）。
     * 登记在这里 → 每刻检查假人背包是否已被取空 → 取空就自动启动下一批，直到备齐。
     *
     * <p>为什么不用 {@link #PENDING_RECYCLE} 顺带处理：批量备货的交付路径并不登记回收
     * （只有单件取货登记），假人交付后会留在玩家身边，所以需要一个独立的每刻钩子。
     */
    private static final Map<UUID, PendingRestock> PENDING_RESTOCK = new HashMap<>();

    /**
     * 待续备货的有效期（毫秒）。超时即放弃并告知玩家 —— 否则玩家一直不取货时，
     * 这条记录会永远挂着（占内存、且下次点备货时产生意外的自动续备）。
     */
    private static final long PENDING_RESTOCK_TIMEOUT_MS = 600_000L;   // 10 分钟

    // =========================================================
    // 多假人并行备货（[VERSION] 2026-09-29 用户需求："要取的货很多，能不能多假人并行备货"）
    //
    // 单假人路径（本类原有逻辑）一行没改：itemFetcherParallel = 1 时行为与改造前完全一致。
    // 并行是新增能力，不能让不开它的人承担回归风险。
    // =========================================================

    /**
     * 并行备货假人的<b>硬上限</b>（{@link FLTSettings#itemFetcherParallel} 会被 clamp 到此值）。
     *
     * <p>为什么规则里已经写了"建议 1~4"还要在代码里再兜一层：规则是 int 类型、能填任意值，
     * 填 100 就会在同一批里催建 100 个假人 —— 每个都是完整玩家实体（寻路 + 区块加载），
     * 服务器会直接被拖垮。文档拦不住手快的操作，所以必须代码兜底，并且把"已被按上限执行"
     * 明确告诉玩家（见 {@link #startParallelRestock}），不做静默截断。
     */
    private static final int MAX_PARALLEL_BOTS = 4;

    /**
     * 一轮并行任务的总时长上限（毫秒）：等假人就绪 + 等全部跑完，超时即放弃。
     *
     * <p>为什么必须有：假人可能建不起来（createFake 被拒 / 多 Carpet 附加导致 NPE 后进冷却），
     * 也可能某个假人被别的东西占住一直"正忙"。没有超时，这条 job 会永远挂在表里，
     * 玩家再点备货只会被"已在进行中"挡住 —— 表现为功能彻底失灵且没有任何提示。
     */
    private static final long PARALLEL_TIMEOUT_MS = 180_000L;   // 3 分钟

    /** 玩家 UUID → 正在推进的并行备货任务（同一玩家同时只允许一批） */
    private static final Map<UUID, ParallelJob> PARALLEL_JOBS = new HashMap<>();

    /** 待续备货状态（可变字段不必，故用 record 即可） */
    private record PendingRestock(String playerName, Map<Identifier, Integer> remaining, long since) {
    }

    /**
     * 登记一批「等玩家取走后续备」的剩余需求（由 {@code BulkFetchRepackAction} 在因末影箱满
     * 中止装箱时调用）。背包空、假人在线且空闲时，{@link #tickPendingRestock} 会自动续备。
     */
    public static void schedulePendingRestock(UUID playerId, String playerName,
                                              Map<Identifier, Integer> remaining) {
        if (playerId == null || remaining == null || remaining.isEmpty()) {
            return;
        }
        // [VERSION] 2026-09-29 并行备货：每个假人各登记自己那一片的缺口 → 必须<b>累加</b>。
        // 原来直接 put 会让后登记的人覆盖前面的人，总缺口偏小 → 玩家以为备齐了、
        // 实际上还差一大截（这正是"按数量切分"最容易踩的错）。
        PendingRestock previous = PENDING_RESTOCK.get(playerId);
        Map<Identifier, Integer> merged = new LinkedHashMap<>();
        if (previous != null) {
            merged.putAll(previous.remaining());
        }
        for (Map.Entry<Identifier, Integer> entry : remaining.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        PENDING_RESTOCK.put(playerId, new PendingRestock(playerName, merged, System.currentTimeMillis()));
        FLTAdditionMod.LOGGER.info("[FLT] 玩家 {} 登记待续备货 {} 项（合并后 {} 项）",
                playerName, remaining.size(), merged.size());
    }

    /**
     * 每刻推进「待续备货」：假人背包已被取空 → 自动启动下一批。
     *
     * <p>为什么以「背包已空」为触发条件：只有空背包才能保证本次取到的货不会和上一批混在一起
     * （这正是"遗留污染"问题的根源）。玩家可以用手取、也可以等回收逻辑自动转交，
     * 两条路最终都会让背包变空，所以这里不必区分。
     */
    private static void tickPendingRestock(MinecraftServer server) {
        if (PENDING_RESTOCK.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingRestock>> it = PENDING_RESTOCK.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, PendingRestock> entry = it.next();
            UUID playerId = entry.getKey();
            PendingRestock p = entry.getValue();

            if (now - p.since() > PENDING_RESTOCK_TIMEOUT_MS) {
                it.remove();
                notify(server, playerId, ChatFormatting.YELLOW,
                        "[FLT] 分批备货已超时放弃（还剩 " + p.remaining().size()
                                + " 项未备），需要时请重新点「假人备货」");
                continue;
            }

            if (server.getPlayerList().getPlayer(playerId) == null) {
                it.remove();   // 玩家离线 → 不折腾，等他回来重新点
                continue;
            }

            EntityPlayerMPFake bot =
                    FakePlayerUtils.findFakePlayer(server, FakePlayerNaming.botNameFor(playerId));

            if (bot == null) {
                it.remove();   // 假人已被手动清掉 → 重新点是唯一可行路径
                notify(server, playerId, ChatFormatting.YELLOW,
                        "[FLT] 待续备货的假人已不在，分批备货中止；请重新点「假人备货」");
                continue;
            }

            if (FakePlayerActionScheduler.isRunning(bot)) {
                continue;   // 正忙（理论上不该，但别打断）
            }

            // [VERSION] 2026-09-29 并行备货：货分散在多个假人身上，只判主假人背包会误判
            // "已取空"（主假人的取走了、别的假人身上还全是货）→ 续备会提前启动、和前一批混在一起
            if (!allBotsEmpty(server, playerId)) {
                continue;   // 玩家还没取走上批 → 继续等
            }

            it.remove();
            notify(server, playerId, ChatFormatting.GREEN,
                    "[FLT] 上一批已取走，自动继续备货剩余 " + p.remaining().size() + " 项");
            // 走分流：并行开关开着时，续备同样用多假人（缺口大时单假人一轮要很久）
            if (!startRestockFor(server, playerId, p.playerName(), p.remaining())) {
                notify(server, playerId, ChatFormatting.YELLOW,
                        "[FLT] 续备未能启动（假人忙或建档失败），需要时请重新点「假人备货」");
            }
        }
    }

    /** 回收状态（可变字段，故用普通类而不是 record） */
    private static final class RecycleState {
        final long deliveredAtMs;
        long lastHandoverMs;

        RecycleState(long now) {
            this.deliveredAtMs = now;
            this.lastHandoverMs = now;
        }
    }

    /** 登记一个"交付完成、等待回收"的假人（玩家把货取空后会自动下线） */
    /**
     * 新备货任务开始前，把假人身上「上一次任务的遗留」挪走，避免污染本次交付结果。
     *
     * <p><b>为什么需要（2026-09-28 用户报障）</b>：假人背包是<b>持久</b>的。甲原理图备完货、
     * 假人把货送到玩家面前后，如果玩家还没取走就直接切到乙原理图再点备货 ——
     * 乙的 PACK 阶段扫的是「背包里所有散装」，会把甲的遗留一并装箱，交付时玩家看到的就是
     * 「乙的备货里混着甲的货」，严重时以为整批都是甲的结果。此外 FETCH 阶段算缺口时
     * （{@code countItemOnBot}）也会把甲的同类遗留算作「已有」，导致乙少取。
     *
     * <p><b>处理</b>：把遗留<b>整格</b>转入假人末影箱（玩家可自行开启取走，见 GCA 规则
     * {@code openFakePlayerEnderChest}），背包清空后本次任务只装自己的货。
     * 末影箱也放不下时<b>拒绝启动</b> —— 宁可让玩家先取货，也不能让交付结果被污染。
     *
     * <p>只在「会交付给玩家」的入口调用：批量备齐、打包取货、单件取货（带交付）。
     * 后台自动备货（{@code deliverToPlayer=false}）<b>不</b>清理 —— 那正是「持续攒货到假人身上」的语义。
     *
     * @return true = 背包已干净、可以开始；false = 遗留无处安放，无法开始
     */
    private static boolean prepareBotForNewTask(MinecraftServer server, UUID playerId, EntityPlayerMPFake bot) {
        var inv = bot.getInventory();
        int stashed = 0;
        int stuck = 0;
        int keptBoxes = 0;
        // [VERSION] 2026-09-30 口径修正：只扫主背包 + 快捷栏（36 格），不碰装备槽 ——
        // 用 getContainerSize()（＝41）会把假人身上穿的盔甲当成"上次任务的遗留"转进末影箱。
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            // ⚠️ 空潜影盒<b>不是"上次任务的遗留"</b>，留在背包当装箱材料（[VERSION] 2026-09-29 闭环审计修复）：
            //    它是"工具"而不是"货"。原实现一律转走 → ① 下次装箱白跑一趟仓库取盒；
            //    ② 空盒多到末影箱放不下时 stuck>0 → 新任务被<b>直接拒绝启动</b>（玩家莫名其妙）。
            //    装箱流程会优先用背包里现成的空盒（见 BulkFetchRepackAction.hasEmptyShulkerInInventory）。
            if (ShulkerBoxUtil.isEmptyShulker(stack)) {
                keptBoxes++;
                continue;
            }
            if (EnderChestUtil.tryStashSlot(bot, i)) {
                stashed++;
            } else {
                stuck++;
            }
        }
        if (keptBoxes > 0) {
            FLTAdditionMod.LOGGER.info("[FLT] 新任务前清理：假人 {} 的 {} 格空潜影盒留作装箱材料",
                    bot.getGameProfile().name(), keptBoxes);
        }
        if (stashed == 0 && stuck == 0) {
            return true;   // 背包本来就没有遗留物资（可能只有空盒）
        }
        if (stashed > 0) {
            FLTAdditionMod.LOGGER.info("[FLT] 新任务前清理假人 {} 的遗留：{} 格转入其末影箱",
                    bot.getGameProfile().name(), stashed);
            notify(server, playerId, ChatFormatting.YELLOW,
                    "[FLT] 假人身上上次遗留的 " + stashed + " 格物资已转入它的末影箱（记得取走）");
        }
        if (stuck > 0) {
            notify(server, playerId, ChatFormatting.RED,
                    "[FLT] 假人身上还有 " + stuck + " 格上次的物资无处安放（末影箱可能已满），请先取走再备货");
            return false;
        }
        return true;
    }

    public static void markForRecycle(EntityPlayerMPFake bot) {
        if (bot != null) {
            PENDING_RECYCLE.put(bot.getUUID(), new RecycleState(System.currentTimeMillis()));
        }
    }

    /**
     * 每刻推进假人回收（由 FLTAdditionServer.onTick 调用，**不受 autoRestock 开关影响**）。
     *
     * <p>回收条件：
     * <ol>
     *   <li>假人背包已空（玩家把货取走了）→ 立即下线</li>
     *   <li>超过 {@link #RECYCLE_IDLE_MS} 还没取走 → 尝试把货直接塞进玩家背包；
     *       塞完变空就下线，玩家背包没位置则每 {@link #RECYCLE_RETRY_MS} 静默重试</li>
     *   <li>玩家离线 / 假人已不在线 → 停止追踪（不折腾）</li>
     * </ol>
     *
     * ⚠️ 只在背包为空时才真正下线：这样无论 Carpet 的 kill 是掉落还是存盘，都不会丢玩家的物资。
     */
    public static void tickRecycle(MinecraftServer server) {
        if (PENDING_RECYCLE.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, RecycleState>> it = PENDING_RECYCLE.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, RecycleState> entry = it.next();
            ServerPlayer raw = server.getPlayerList().getPlayer(entry.getKey());

            if (!(raw instanceof EntityPlayerMPFake bot)) {
                it.remove();   // 假人已经不在了（被手动 kill / 掉线）
                continue;
            }

            // [VERSION] 2026-09-28 修复：假人正在执行任务时<b>绝不能</b>回收它。
            // 以前只判"背包为空"就 kill —— 而假人刚接手一个新动作时背包本来就是空的，
            // 于是出现：「甲交付完 → 登记回收 → 乙立刻点备货 → 假人接下任务 →
            // 下一 tick 回收逻辑看到背包空就把它下线」→ 乙的任务被顶掉
            // （用户报障："多人同时开启备货会有冲突，会顶掉任务"）。
            // 假人一旦接了新任务，旧的回收意图即作废 —— 直接从登记表撤销。
            // （撤销是安全的：回收登记只发生在「单件取货 + 交付成功」那条路径上，而它每次交付都会
            //   重新登记；批量备货与常驻复用的假人本就不登记回收，不靠这里补。）
            if (FakePlayerActionScheduler.isRunning(bot)) {
                it.remove();
                continue;
            }

            if (isInventoryEmpty(bot)) {
                recycle(bot);
                it.remove();
                continue;
            }

            RecycleState state = entry.getValue();
            if (now - state.deliveredAtMs >= RECYCLE_IDLE_MS && now - state.lastHandoverMs >= RECYCLE_RETRY_MS) {
                state.lastHandoverMs = now;
                handOverToPlayer(server, entry.getKey(), bot);

                if (isInventoryEmpty(bot)) {
                    recycle(bot);
                    it.remove();
                }
            }
        }
    }

    /**
     * 假人背包（含快捷栏）是否已"无货可取"。
     *
     * <p>⚠️ 判定<b>忽略空潜影盒</b>（[VERSION] 2026-09-29 自查修复）：装箱会在假人身上留下
     * 拆用剩余的空盒，而玩家取货时通常只取走成品盒与散装、把空盒留给假人继续用 ——
     * 若把空盒也算作"还没取空"，分批续备就<b>永远不会触发</b>（一直等一个不会出现的"全空"）。
     *
     * @return true = 背包里已没有需要交付的物品（空盒不算）
     */
    private static boolean isInventoryEmpty(EntityPlayerMPFake bot) {
        var inventory = bot.getInventory();

        // [VERSION] 2026-09-30 口径修正：只扫主背包 + 快捷栏（36 格）。用 getContainerSize()（＝41）
        // 时，假人身上穿的盔甲会让"背包永远不空" → 分批续备不触发、假人也永远不被回收。
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || ShulkerBoxUtil.isEmptyShulker(stack)) {
                continue;   // 空槽 / 拆用剩余的空盒 → 不算"还有货"
            }
            return false;
        }
        return true;
    }

    /**
     * 把假人身上的货转交给玩家（能装多少装多少，装不下的留在假人身上）。
     *
     * <p>用 {@code Inventory.add} 而不是"丢在地上"：一来不会产生满地掉落物，
     * 二来 {@code drop(...)} 的签名在 26.1.2 与 26.3 之间不一致（26.3 改成了 Prediction），
     * 而本文件要三版同步，踩这个坑不值得。
     */
    private static void handOverToPlayer(MinecraftServer server, UUID playerId, EntityPlayerMPFake bot) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);

        if (player == null) {
            return;   // 玩家不在线，保持原样
        }

        var botInv = bot.getInventory();
        var playerInv = player.getInventory();
        int moved = 0;

        // [VERSION] 2026-09-30 口径修正：只转交主背包 + 快捷栏（36 格）里的货。
        // ⚠️ 用 getContainerSize()（＝41）时会把假人身上穿的<b>盔甲</b>也搬给玩家（含清空该槽），
        // 属于实打实的物资错位 —— 这是本次口径统一里最该修的一处。
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = botInv.getItem(i);

            if (stack.isEmpty()) {
                continue;
            }

            // add 可能只装下一部分 → 用副本判断，剩余量写回假人，避免凭空蒸发
            ItemStack moving = stack.copy();

            if (playerInv.add(moving)) {
                botInv.setItem(i, ItemStack.EMPTY);
                moved++;
            } else if (moving.getCount() != stack.getCount()) {
                botInv.setItem(i, moving);
                moved++;
            }
        }

        if (moved > 0) {
            player.sendSystemMessage(Component.literal(
                            "[FLT] 假人身上的 " + moved + " 组物资已自动转交给你")
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /** 让假人下线（调用前必须确保它背包是空的，见 tickRecycle 的说明） */
    private static void recycle(EntityPlayerMPFake bot) {
        String name = bot.getGameProfile().name();
        bot.kill((ServerLevel) bot.level());
        FLTAdditionMod.LOGGER.info("[FLT] 假人 {} 已完成任务并下线", name);
    }

    private RestockScheduler() {
    }

    /** 每刻由 FLTAdditionServer.onTick 调用；内部按 restockIntervalTicks 限频 */
    public static void tick(MinecraftServer server) {
        // ⚠️ 必须放在 autoRestock 开关【之前】：手动取货的排队与"自动备货"是两件事，
        //    关掉自动备货后排队仍必须继续推进，否则关了开关就再也取不到货。
        tickPending(server);
        tickPendingRestock(server);   // 分批备货：玩家取走上批后自动续备下一批
        tickParallelJobs(server);     // 并行备货：等 N 个假人就绪 → 逐个派单 → 收尾汇总

        if (!FLTSettings.autoRestock) {
            return;
        }
        int interval = Math.max(1, FLTSettings.restockIntervalTicks);
        if (++tickCounter < interval) {
            return;
        }
        tickCounter = 0;
        if (DemandRegistry.players().isEmpty()) {
            return;
        }
        for (UUID playerId : DemandRegistry.players()) {
            try {
                restockPlayer(server, playerId);
            } catch (Throwable t) {
                FLTAdditionMod.LOGGER.error("[FLT] 为玩家 {} 自动备货时出错", playerId, t);
            }
        }
    }

    /** 为单个玩家推进一件备货（已备齐则回推一次当前假人库存） */
    private static void restockPlayer(MinecraftServer server, UUID playerId) {
        Map<Identifier, Integer> demand = DemandRegistry.get(playerId);
        if (demand.isEmpty()) {
            return;
        }
        EntityPlayerMPFake bot = botFor(server, FakePlayerNaming.botNameFor(playerId));
        if (bot == null || FakePlayerActionScheduler.isRunning(bot)) {
            return;   // 假人还没建档 / 正忙，下个周期再说
        }

        Map<Identifier, Integer> onBot = InventoryCounter.count(bot);
        for (Map.Entry<Identifier, Integer> entry : demand.entrySet()) {
            Identifier itemId = entry.getKey();
            int remaining = entry.getValue() - onBot.getOrDefault(itemId, 0);
            if (remaining > 0 && dispatch(server, playerId, bot, itemId, remaining)) {
                return;   // 本周期只派一件
            }
        }

        // 需求里没有"还缺且有货"的材料 → 回推一次当前库存，让客户端刷新 available。
        // [VERSION] 2026-09-29 自查修复：改用<b>汇总版</b> —— 开了并行后备货假人有多个，
        // 只推主假人会让客户端材料列表少显示其他假人身上的货。
        pushStockTotal(server, playerId);
    }

    /**
     * 单物品立即取货（客户端投影「组合键 + 中键」发出的 C2S {@code item_fetch} 请求）。
     *
     * <p>与批量备货的区别：不查 {@link DemandRegistry} 需求表，由客户端直接指定物品与数量。
     *
     * <p>三种走向：
     * <ol>
     *   <li>假人已就绪且空闲 → 立即派单</li>
     *   <li>假人不在（首次取货 / 已被回收）→ <b>触发异步建档并入队</b>，建档完成后由
     *       {@link #tickPending} 自动补派 —— 这是"要点两次取货"的修复点</li>
     *   <li>假人正在忙 → 也入队，等手里这件做完接着干</li>
     * </ol>
     * 只有"数量/物品非法"与"没登记任何库存源"才返回 false（这两种情况排队也没意义）。
     *
     * @param boxMode 打包模式（[VERSION] 2026-09-24 语义变更）：true = 按 {@code count} 个物品
     *                取货后用空潜影盒打包再交付（数量是个数，不再是盒数）；
     *                false = 原样散装取货（数量是个数）
     * @return true = 已派单或已受理排队；false = 无法受理（数量非法 / 没登记库存源 / 队列已满）
     */
    public static boolean fetchSingle(MinecraftServer server, UUID playerId, String playerName,
                                      Identifier itemId, int count, boolean boxMode) {
        if (itemId == null || count <= 0) {
            return false;
        }
        int want = Math.min(count, MAX_COUNT);

        // [VERSION] 2026-09-29 取货缓存：从该玩家<b>全部</b>备货假人里挑一个"空闲且缓存空间最充裕"的
        // （见 pickIdleBot）。开启并行后每人可有多个假人，满载的那个自然被跳过；
        // 并行关闭时只有一个假人（槽位 0），行为与改造前完全一致。
        EntityPlayerMPFake bot = pickIdleBot(server, playerId);

        // ① 有可用假人 → 直接派单（常规路径）
        if (bot != null) {
            return boxMode
                    ? dispatchPack(server, playerId, playerName, bot, itemId, want)
                    : dispatch(server, playerId, bot, itemId, want, false, true);
        }

        // ② 一个都不在 → 先确认"有库存源"才值得建档，避免把永远建不出来的人排进队列
        String primaryBot = FakePlayerNaming.botNameFor(playerId);
        if (FakePlayerUtils.findFakePlayer(server, primaryBot) == null) {
            if (firstStockSpot(server) == null) {
                return false;   // 没登记库存源 → 无从建档，如实告诉客户端失败
            }
            botFor(server, primaryBot);   // 触发异步建档；建档完成后由 tickPending 轮询补派
        }

        // ③ 全都在忙 / 还在建档 → 进取货缓存池，等有假人空出来再派
        return enqueueFetch(playerId, new PendingFetch(playerId, itemId, want, boxMode,
                System.currentTimeMillis()));
    }

    /** 旧签名：不带 boxMode（批量备货等场景走散装） */
    public static boolean fetchSingle(MinecraftServer server, UUID playerId, String playerName,
                                      Identifier itemId, int count) {
        return fetchSingle(server, playerId, playerName, itemId, count, false);
    }

    /**
     * 【投影「假人备货」按钮】一键备齐 + 装箱送回。
     *
     * <p>客户端从投影材料列表点「假人备货」→ 发 {@code restock_all} 空载荷 →
     * 本方法为玩家启动 {@link BulkFetchRepackAction}：逐物品算需求缺口跨箱取齐，
     * 从仓库找空盒打包，最后把假人送到玩家面前。
     *
     * <p>假人尚未就绪 / 正忙时：不排队，直接返回 false，由客户端提示稍后再试。
     * 原因：PENDING_FETCH 队列的条目语义是"<b>单物品 + 数量</b>"（见 {@link PendingFetch}），
     * 而备货是"<b>整张需求表</b>"的批量动作，塞不进单件队列 —— 硬塞只能取到其中一件。
     * 假人不在时会先触发异步建档，并注册"建档完成 → 自动启动备货"回调，
     * 所以正常情况下玩家点一次即可，无需重试。
     *
     * @return true = 已启动（假人就绪）；false = 假人不可用（需稍等重试）
     */
    public static boolean restockAllBoxed(MinecraftServer server, UUID playerId, String playerName) {
        Map<Identifier, Integer> demand = DemandRegistry.get(playerId);
        if (demand.isEmpty()) {
            // [VERSION] 2026-09-30 措辞修正：客户端改成"只报缺口（投影总数 − 身上已有）"之后，
            //   空需求不再只意味着"没上报过"，也可能是"投影材料都已满足" ——
            //   别再让玩家去翻投影材料列表找原因。
            notify(server, playerId, ChatFormatting.YELLOW,
                    "[FLT] 暂无待备材料：投影未选中，或材料列表里已经没有缺口"
                            + "（打开投影材料列表可刷新需求）");
            return false;
        }
        // 玩家手动重新点备货 → 作废上一轮登记的「待续备货」，避免旧需求和新需求各跑一套
        PENDING_RESTOCK.remove(playerId);
        // 并行开关打开时走并行路径（在下面单假人分支之前分流）。
        // 为什么不做成"复用单假人分支"：单假人是"这一刻判假人在不在，不在就返回失败"，
        // 而并行要等 N 个假人异步建档（Carpet createFake 立即返回、实体要过几刻才进玩家列表），
        // 只能登记任务后每刻推进（见 tickParallelJobs）—— 与单假人路径的 tickPending 同一思路。
        if (configuredParallel() > 1) {
            return startParallelRestock(server, playerId, playerName, demand, configuredParallel());
        }
        String botName = FakePlayerNaming.botNameFor(playerId);
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, botName);

        // 假人不在 → 尝试建档；建档是异步分步的，注册「建档完成 → 自动启动备货动作」的回调，
        // 一次点击即可在假人生成后自动取货装箱，无需玩家再点一次（2026-09-23 修复）。
        if (bot == null) {
            if (firstStockSpot(server) == null) {
                notify(server, playerId, ChatFormatting.RED,
                        "[FLT] 备货失败：还没登记任何库存源仓库（用 /Itemfetcher stock add 添加）");
                return false;
            }
            Consumer<EntityPlayerMPFake> onReady = fake -> startRestockAll(server, playerId, playerName, demand);
            if (ensureBotFor(server, botName, onReady)) {
                // 已建档（或建档中）→ 本次返回 false，动作会在假人就绪后被回调自动拉起
                notify(server, playerId, ChatFormatting.YELLOW,
                        "[FLT] 备货假人正在生成，完成后会自动开始备货，请稍候");
            } else {
                notify(server, playerId, ChatFormatting.RED,
                        "[FLT] 备货假人建档失败（可能在冷却中），稍后再点");
            }
            return false;
        }

        // 假人正忙（正在做别的事）→ 提示稍后再试
        if (FakePlayerActionScheduler.isRunning(bot)) {
            notify(server, playerId, ChatFormatting.YELLOW,
                    "[FLT] 备货假人正忙，请稍候再试");
            return false;
        }

        return startRestockAll(server, playerId, playerName, demand);
    }

    /**
     * 把「一键备齐+装箱送回」动作挂到指定假人上（假人必须已就绪且空闲）。
     *
     * @return true = 已启动；false = 假人不可用，或身上还残留着上次任务的货取不走（见
     *         {@link #prepareBotForNewTask}）
     */
    private static boolean startRestockAll(MinecraftServer server, UUID playerId, String playerName,
                                           Map<Identifier, Integer> demand) {
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, FakePlayerNaming.botNameFor(playerId));
        if (bot == null || FakePlayerActionScheduler.isRunning(bot)) {
            return false;
        }
        if (!prepareBotForNewTask(server, playerId, bot)) {
            // "降级必须可见"：不能只返回 false 让客户端笼统说"稍后再试" —— 要让玩家知道
            // 是哪一步卡住（旧货取不走 / 末影箱也满），否则他只会反复点、每次都被拒。
            notify(server, playerId, ChatFormatting.YELLOW,
                    "[FLT] 备货未能启动：假人 " + bot.getGameProfile().name()
                            + " 身上还有上一批的货没取走（末影箱也满了），请先取走它身上的货再点备货");
            return false;
        }
        FakePlayerActionScheduler.start(bot, new BulkFetchRepackAction(
                bot, playerId, playerName, demand, reason ->
                        notify(server, playerId, ChatFormatting.GREEN, "[FLT] " + reason)));
        FLTAdditionMod.LOGGER.info("[FLT] 玩家 {} 触发了「一键备齐+装箱送回」，需求 {} 种",
                playerName, demand.size());
        return true;
    }

    /**
     * 派假人从库存源取指定物品（实际取货量 = {@code min(want, 箱内可提取量)}）。
     * 批量备货与单件取货共用，保证两条链路行为一致。
     *
     * @param deliverToPlayer 取完后是否把假人送到玩家身边（手动取货 true；后台批量备货 false，
     *                        否则假人会在箱子与玩家之间来回跑）
     * @return true = 已派单；false = 未派单（物品未注册 / 库存源里没这种货）
     */
    private static boolean dispatch(MinecraftServer server, UUID playerId, EntityPlayerMPFake bot,
                                    Identifier itemId, int want, boolean boxMode, boolean deliverToPlayer) {
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item == null || item == Items.AIR) {
            return false;
        }

        // 先按多箱思路收集目标槽；若完全没货直接失败。
        List<MultiContainerWithdrawAction.TargetSlot> targets =
                MultiContainerWithdrawAction.collectTargets(server, itemId, item, boxMode, want);
        if (targets.isEmpty()) {
            return false;
        }

        // 取货缓存闸门（[VERSION] 2026-09-29）：出门前先确认装得下 —— 不够就把成品盒腾进末影箱。
        // 位置放在 collectTargets 之后（而不是最前面）：先确认仓库真有货，否则会为"没货"白腾一通，
        // 还把提示说成"空间不足"，误导玩家去清一个本来就不紧张的背包。
        int needSlots = RestockCapacity.neededSlots(itemId, want) + RestockCapacity.SAFETY_SLOTS;
        if (!ensureCacheSpace(server, playerId, bot, needSlots)) {
            return false;   // 腾不出来 → 不派单（走队列的调用方会把请求留在队首等，见 flushOne）
        }

        Consumer<String> reporter = message ->
        {
            // [VERSION] 2026-09-24 新增：把取货结果播报给玩家。
            //
            // ⚠️ 以前这里<b>完全没用 message 参数</b>（只做"送货 + 回推库存"），于是取货失败时
            // 玩家只看到"假人上线又马上下线"，完全不知道原因 ——
            // "容器内已没有该物品" / "打开容器失败（菜单未创建）" / "目标方块不是可打开的容器" /
            // "假人背包空间不足" 这些信息全被丢掉（2026-09-24 用户报障："假人没拿到物品就下线了"）。
            // 这里对齐 {@link #dispatchPack} 的 reporter 做法，把原因播报出来。
            //
            // 颜色用中性黄而不是按文案猜成败：message 是纯文本，没有成功/失败标志，
            // 靠 contains("没有") 之类猜会误判；文案本身已经写清了结果。
            if (message != null && !message.isBlank())
            {
                notify(server, playerId, ChatFormatting.YELLOW, "[FLT] 取货：" + message);
            }

            // 取货动作结束时：先把假人送到玩家身边，再回推库存
            if (deliverToPlayer && !flushOne(server, playerId, bot))
            {
                // 队列里已经没有该假人的活了 → 才送回玩家身边。
                // ⚠️ 顺序很重要：如果队列还有活却先送回玩家，下一次取货会把它再拉回箱子，
                //    假人在"箱子 ↔ 玩家"之间来回跑，玩家看到的就是反复横跳。
                if (deliverToPlayer(server, playerId, bot))
                {
                    // 交付成功 → 登记回收：玩家把货取空后假人自动下线（避免常驻占用）
                    markForRecycle(bot);
                }
            }
            // [VERSION] 2026-09-29 自查修复：同上，用汇总版（并行的货可能分散在多个假人身上）
            pushStockTotal(server, playerId);
        };

        // 只有一个目标槽时，复用已验证的单箱动作（风险低、行为稳定）。
        AbstractFakePlayerAction action;
        if (targets.size() == 1) {
            MultiContainerWithdrawAction.TargetSlot t = targets.get(0);
            int requestAmount = boxMode ? want : Math.min(want, t.count());
            action = new ContainerWithdrawAction(bot, t.pos(), item, requestAmount, boxMode, reporter);
        } else {
            action = new MultiContainerWithdrawAction(bot, targets, item, itemId.toString(), want, boxMode, reporter);
        }

        // 只有「会交付给玩家」的派单才清理；后台自动备货（deliverToPlayer=false）要保留假人身上的货
        if (deliverToPlayer && !prepareBotForNewTask(server, playerId, bot)) {
            return false;
        }
        FakePlayerActionScheduler.start(bot, action);
        return true;
    }

    /**
     * 【打包取货】[VERSION] 2026-09-24 新增。
     *
     * <p>「打包」模式下，单物品取货不再走"整盒搬取"，而是<b>复用批量备货那套已验证的
     * {@link BulkFetchRepackAction}</b>：把"这一个物品 + 数量"包成只有一项的需求表 →
     * 该动作依次执行 FETCH（跨箱取散装）→ PACK（找空盒装盒）→ DELIVER（送到玩家面前）。
     *
     * <p>为什么复用而不是新写：装盒（含堆叠空盒 split(1) 防复制、双箱槽位对齐、空盒不足降级）
     * 这些坑都已在 BulkFetchRepackAction 里踩平，重写一遍只会引入新 bug。
     * 语义上也正好吻合："按数量取 N 个 → 装盒 → 送来"。
     *
     * <p>与批量备货的唯一区别：需求表只有一项，且交付后同样走"送到玩家身边 + 登记回收"。
     *
     * @return true = 已启动；false = 仓库里没有该物品（无从取货）
     */
    private static boolean dispatchPack(MinecraftServer server, UUID playerId, String playerName,
                                        EntityPlayerMPFake bot, Identifier itemId, int want) {
        // 先确认仓库里确实有货（BulkFetchRepackAction 对"无货"只是跳过，不会报错，
        // 这里提前判断一下，好让客户端得到明确的"未派单"反馈）
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item == null || item == Items.AIR) {
            return false;
        }
        List<MultiContainerWithdrawAction.TargetSlot> targets =
                MultiContainerWithdrawAction.collectTargets(server, itemId, item, false, want);
        if (targets.isEmpty()) {
            return false;
        }

        Map<Identifier, Integer> demand = new LinkedHashMap<>();
        demand.put(itemId, want);

        // 打包取货同样是「把这次的货送来」的语义 → 先清掉上次遗留，避免混装交付
        if (!prepareBotForNewTask(server, playerId, bot)) {
            return false;
        }
        Consumer<String> reporter = reason -> notify(server, playerId, ChatFormatting.GREEN, "[FLT] " + reason);

        FakePlayerActionScheduler.start(bot, new BulkFetchRepackAction(bot, playerId, playerName, demand, reporter));
        FLTAdditionMod.LOGGER.info("[FLT] 玩家 {} 打包取货 {} x{}（复用 BulkFetchRepackAction）",
                playerName, itemId, want);
        return true;
    }

    /**
     * 把假人送到玩家身边（组合拳二期·取货交付）。
     *
     * <p>为什么是"传送"而不是"下线再上线"：假人身上正背着刚取到的货，而 Carpet 假人被杀掉时
     * 会像真玩家一样把背包掉一地 —— 那等于把货撒在箱子旁边，还得回去捡。所以这里直接把它
     * 传送到玩家面前（同维度），既达到"货到你眼前"的效果，又不会丢东西。
     *
     * <p>落点取玩家朝向前方 1.2 格（不在脚下重叠），朝向沿用玩家朝向。
     *
     * @return true = 已送达；false = 玩家已离线（假人留在原地，不报错）
     */
    public static boolean deliverToPlayer(MinecraftServer server, UUID playerId, EntityPlayerMPFake bot) {
        if (server == null || bot == null) {
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return false;   // 玩家离线：不折腾假人
        }

        ServerLevel level = player.level();
        double yawRad = Math.toRadians(player.getYRot());
        double x = player.getX() - Math.sin(yawRad) * 1.2D;   // MC 中 yaw=0 指向 +Z，前方 = (-sin, +cos)
        double z = player.getZ() + Math.cos(yawRad) * 1.2D;

        // 玩家可能站在悬崖边 / 岩浆上方 → 就近找安全落点
        //（半径 2 格：玩家右键打开假人背包走实体交互距离 3.0 格，不能离太远）
        SafeSpotFinder.teleportToSafeSpot(bot, level, x, player.getY(), z,
                SafeSpotFinder.DELIVER_RANGE, player.getYRot(), player.getXRot(), "送货到玩家身边");

        // [VERSION] 26.x：给玩家发聊天消息用 ServerPlayer.sendSystemMessage(Component)
        //（yarn 1.21.8 的 displayClientMessage 在 26.x 官方名里已改名，javap 实证）
        //
        // 提示里附带"怎么拿货"：GCA（Gugle's Carpet Addition）提供 carpet 规则 openFakePlayerInventory
        //（中文名「假人背包」，作用：允许玩家打开假人背包）。装了 GCA 才提示，免得对着没装的人瞎指路。
        String hint = FabricLoader.getInstance().isModLoaded("gca")
                ? "（右键它即可打开背包取货；需已开启 GCA 规则「假人背包」/openFakePlayerInventory）"
                : "";
        player.sendSystemMessage(Component.literal(
                        "[FLT] 假人 " + bot.getGameProfile().name() + " 已把货送到你身边" + hint)
                .withStyle(ChatFormatting.GREEN));
        FLTAdditionMod.LOGGER.info("[FLT] 假人 {} 已送达到玩家 {} 身边（{} {} {}）",
                bot.getGameProfile().name(), player.getName().getString(),
                String.format("%.1f", x), String.format("%.1f", player.getY()), String.format("%.1f", z));
        return true;
    }

    /**
     * 散装取货（boxMode=false）：批量备货 {@link #restockPlayer} 的调用入口。
     * 不配送（后台备货时假人继续留在仓库工作）。
     */
    private static boolean dispatch(MinecraftServer server, UUID playerId, EntityPlayerMPFake bot,
                                    Identifier itemId, int want) {
        return dispatch(server, playerId, bot, itemId, want, false, false);
    }

    /**
     * 取玩家专属备货假人；不存在则先在第一个库存源容器旁建档（分步、不卡服）。
     *
     * @return 可用的假人；假人尚未建档完成时返回 null（调用方放弃本次，等待 {@link #tickPending} 续派）
     */
    private static EntityPlayerMPFake botFor(MinecraftServer server, String botName) {
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, botName);
        if (bot != null) {
            return bot;
        }
        StockSpot spot = firstStockSpot(server);
        if (spot != null) {
            // 只建档，不挂回调 —— 待派单队列 {@link #tickPending} 会轮询到假人上线后再派活
            FakePlayerFactory.ensureOrCreate(server, botName, spot.level(), spot.spawnPos(), ready -> {
            });
        }
        return null;
    }

    /**
     * 同 {@link #botFor(MinecraftServer, String)}，但假人不在时以指定回调登记建档 ——
     * 建档完成后会执行 {@code onReady}（用于"点一次备货，生成完自动开干"，2026-09-23）。
     *
     * <p>区别：{@code botFor} 是同步轮询模型（调用方循环查）；本方法把"就绪后的活"交给
     * {@link FakePlayerFactory} 的建档回调，适合"点一下、档建好就自动拉活"的一次性动作。
     *
     * @return true = 假人已就绪，或已开始建档（异步回调会在就绪后触发 {@code onReady}）；
     *         false = 假人不在且建档未开始（无库存源 / 处于建档冷却中 / 建档被拒）
     */
    private static boolean ensureBotFor(MinecraftServer server, String botName,
                                        Consumer<EntityPlayerMPFake> onReady) {
        if (FakePlayerUtils.findFakePlayer(server, botName) != null) {
            return true;
        }
        StockSpot spot = firstStockSpot(server);
        if (spot == null) {
            return false;
        }
        // ensureOrCreate 返回 true 仅当假人"已就绪且回调控执行"（此时上面 find 应已命中）；
        // 返回 false 时要么开始建档（PENDING 有该名）、要么被冷却/拒绝（PENDING 无该名）。
        FakePlayerFactory.ensureOrCreate(server, botName, spot.level(), spot.spawnPos(), onReady);
        return FakePlayerFactory.isPending(botName);
    }

    // =========================================================

    // =========================================================
    // 多假人并行备货：切分 / 派单 / 汇总
    // =========================================================

    /** 读规则并 clamp 到 [1, {@link #MAX_PARALLEL_BOTS}] */
    private static int configuredParallel() {
        int raw = FLTSettings.itemFetcherParallel;
        if (raw < 1) {
            return 1;
        }
        return Math.min(raw, MAX_PARALLEL_BOTS);
    }

    /**
     * 备货总入口（单假人与并行共用的分流点）：按 {@link FLTSettings#itemFetcherParallel} 决定走哪条。
     *
     * @return true = 已受理（动作已启动，或并行任务已登记并开始等假人）
     */
    private static boolean startRestockFor(MinecraftServer server, UUID playerId, String playerName,
                                           Map<Identifier, Integer> demand) {
        int parts = configuredParallel();
        if (parts <= 1) {
            return startRestockAll(server, playerId, playerName, demand);
        }
        return startParallelRestock(server, playerId, playerName, demand, parts);
    }

    /**
     * 启动一轮多假人并行备货：勘察库存 → 按数量切 N 份 → 登记 {@link ParallelJob} 由
     * {@link #tickParallelJobs} 每刻推进（等假人就绪 → 逐个派单 → 全部结束后汇总）。
     */
    private static boolean startParallelRestock(MinecraftServer server, UUID playerId, String playerName,
                                                Map<Identifier, Integer> demand, int parts) {
        if (firstStockSpot(server) == null) {
            notify(server, playerId, ChatFormatting.RED,
                    "[FLT] 备货失败：还没登记任何库存源仓库（用 /Itemfetcher stock add 添加）");
            return false;
        }
        if (PARALLEL_JOBS.containsKey(playerId)) {
            notify(server, playerId, ChatFormatting.YELLOW,
                    "[FLT] 并行备货已在进行中，请等本批结束");
            return false;
        }
        // 勘察 + 切分；仓库里一点货都没有时不派单（否则 N 个假人白跑一趟仓库）
        List<Map<Identifier, Integer>> slices = splitByCount(server, demand, parts);
        if (slices.isEmpty()) {
            notify(server, playerId, ChatFormatting.YELLOW,
                    "[FLT] 备货失败：库存源的箱子里没有需要的材料（需求 " + demand.size() + " 种都取不到）");
            return false;
        }
        int effective = slices.size();
        long total = 0;
        for (Map<Identifier, Integer> slice : slices) {
            for (int count : slice.values()) {
                total += count;
            }
        }
        List<String> botNames = FakePlayerNaming.botNamesFor(playerId, effective);
        PARALLEL_JOBS.put(playerId, new ParallelJob(playerId, playerName, slices, botNames));
        String limited = FLTSettings.itemFetcherParallel > MAX_PARALLEL_BOTS
                ? "（规则值 " + FLTSettings.itemFetcherParallel + " 超过上限，已按 " + MAX_PARALLEL_BOTS + " 执行）"
                : "";
        notify(server, playerId, ChatFormatting.YELLOW,
                "[FLT] 已启动 " + effective + " 个假人并行备货：需求 " + demand.size() + " 种 / "
                        + total + " 个材料，正在生成假人…" + limited);
        FLTAdditionMod.LOGGER.info("[FLT] 玩家 {} 启动并行备货：{} 个假人（{}），目标 {} 个材料",
                playerName, effective, String.join("/", botNames), total);
        return true;
    }

    /**
     * 勘察库存 → 把需求裁到"确实取得出这么多" → 按<b>数量</b>切成至多 {@code parts} 份非空子表。
     *
     * <p><b>为什么必须先勘察</b>：按数量切分如果直接拿"需求数量"切，而仓库存货远少于需求
     * （例如要 10000 个、箱子只有 500），每个假人都会拿着几千的缺口去扫箱子、谁也取不到，
     * 最后各自登记一笔巨大的"待续备货" → 玩家取货后反复续备、反复落空。先裁到实际有货的量，
     * 切出来的子任务才都是"真能完成"的。
     *
     * <p><b>为什么"物品总数 ≥ 份数"才切</b>：1 个钻石镐切成 4 份就是每人 0 个（整数除法），
     * 切了等于没切、还多派 3 个假人白等。所以小件物品整份给当前负载最轻的那个假人 ——
     * 大宗材料（真正耗时的那部分）均匀并行，小件不浪费假人。
     *
     * @return 非空子表列表（空片不占假人）；仓库无货时返回空列表
     */
    private static List<Map<Identifier, Integer>> splitByCount(MinecraftServer server,
                                                               Map<Identifier, Integer> demand, int parts) {
        Map<Identifier, Integer> effective = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Integer> entry : demand.entrySet()) {
            Identifier itemId = entry.getKey();
            Integer wanted = entry.getValue();
            if (itemId == null || wanted == null || wanted <= 0) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(itemId);
            if (item == null || item == Items.AIR) {
                continue;
            }
            int want = Math.min(wanted, surveyAvailable(server, itemId, item));
            if (want > 0) {
                effective.put(itemId, want);
            }
        }
        if (effective.isEmpty()) {
            return List.of();
        }

        List<Map<Identifier, Integer>> slices = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            slices.add(new LinkedHashMap<>());
        }
        long[] load = new long[parts];   // 各片当前"格数"负载（小件归位用）

        for (Map.Entry<Identifier, Integer> entry : effective.entrySet()) {
            Identifier itemId = entry.getKey();
            int total = entry.getValue();
            Item item = BuiltInRegistries.ITEM.getValue(itemId);
            // 只用于负载估算的近似堆叠上限（不参与取货正确性）—— 默认上限即可
            int stackSize = item == null ? 64 : Math.max(1, item.getDefaultMaxStackSize());
            if (total >= parts) {
                int base = total / parts;
                int extra = total % parts;
                for (int i = 0; i < parts; i++) {
                    int share = base + (i < extra ? 1 : 0);
                    if (share <= 0) {
                        continue;
                    }
                    slices.get(i).put(itemId, share);
                    load[i] += slotsOf(share, stackSize);
                }
            } else {
                int lightest = 0;
                for (int i = 1; i < parts; i++) {
                    if (load[i] < load[lightest]) {
                        lightest = i;
                    }
                }
                slices.get(lightest).put(itemId, total);
                load[lightest] += slotsOf(total, stackSize);
            }
        }

        List<Map<Identifier, Integer>> result = new ArrayList<>(parts);
        for (Map<Identifier, Integer> slice : slices) {
            if (!slice.isEmpty()) {
                result.add(slice);   // 空片（物品种类比份数少时）不占假人
            }
        }
        return result;
    }

    /** 数量换算成背包格数（向上取整），用于切分的负载均衡 */
    private static long slotsOf(int count, int stackSize) {
        return (count + stackSize - 1L) / stackSize;
    }

    /**
     * 勘察某物品在全部已登记库存源里的<b>可提取总量</b>。
     *
     * <p>用 {@code collectTargets(..., needCount = 0)}：它的实现以 {@code if (needCount > 0)} 为界，
     * 0 = "不限量、把候选槽全部返回"，所以这个调用不像真实取货那样按需截断。
     * 其余口径（双箱归并、盒装源展开）与取货链路<b>共用同一段代码</b> ——
     * 另写一套扫描很容易与取货判定跑偏（比如漏掉杂盒里的物品），所以直接复用取货用的收集器。
     */
    private static int surveyAvailable(MinecraftServer server, Identifier itemId, Item item) {
        long total = 0;
        for (MultiContainerWithdrawAction.TargetSlot slot :
                MultiContainerWithdrawAction.collectTargets(server, itemId, item, false, 0)) {
            total += slot.count();
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * 每刻推进并行任务（由 {@link #tick} 调用，<b>不受 autoRestock 开关影响</b> ——
     * 并行备货是玩家手动点出来的，关了自动备货也得能跑完）。
     */
    private static void tickParallelJobs(MinecraftServer server) {
        if (PARALLEL_JOBS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, ParallelJob>> it = PARALLEL_JOBS.entrySet().iterator();
        while (it.hasNext()) {
            ParallelJob job = it.next().getValue();

            if (job.abortReason != null) {
                it.remove();
                notify(server, job.playerId, ChatFormatting.YELLOW,
                        "[FLT] 并行备货已中止：" + job.abortReason);
                continue;
            }
            if (server.getPlayerList().getPlayer(job.playerId) == null) {
                it.remove();   // 玩家离线 → 不折腾（假人留在原地，他回来重新点即可）
                continue;
            }
            if (System.currentTimeMillis() - job.sinceMs > PARALLEL_TIMEOUT_MS) {
                it.remove();
                notify(server, job.playerId, ChatFormatting.YELLOW,
                        "[FLT] 并行备货超时中止（有假人未能就绪或一直被占用），需要时请重试");
                continue;
            }
            if (!job.dispatched) {
                job.tryDispatch(server);
            } else if (job.allFinished(server)) {
                it.remove();
                pushStockTotal(server, job.playerId);
                notify(server, job.playerId, ChatFormatting.GREEN,
                        "[FLT] 并行备货全部结束（" + job.botNames.size() + " 个假人："
                                + String.join(" / ", job.botNames) + "）");
            }
        }
    }

    /** 一轮并行备货的运行状态（可变字段，故用普通类而不是 record） */
    private static final class ParallelJob {
        final UUID playerId;
        final String playerName;
        /** 各假人的子需求表，与 {@link #botNames} 一一对应 */
        final List<Map<Identifier, Integer>> slices;
        final List<String> botNames;
        final long sinceMs = System.currentTimeMillis();
        /** 是否已经派过单 */
        boolean dispatched = false;
        /** 非 null = 本批已放弃，下一刻从表里摘除并告知玩家 */
        String abortReason = null;

        ParallelJob(UUID playerId, String playerName,
                    List<Map<Identifier, Integer>> slices, List<String> botNames) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.slices = slices;
            this.botNames = botNames;
        }

        /** 催建档 → 全部就绪且空闲 → 清理遗留 → 逐个派单 */
        void tryDispatch(MinecraftServer server) {
            for (String name : botNames) {
                if (FakePlayerUtils.findFakePlayer(server, name) == null) {
                    // 每刻只催队首那个还没就绪的假人：createFake 建档本身要 ~1.5~2 秒
                    // （Carpet 会先查 Mojang 档），同一刻催 4 个会叠成一次明显卡顿，
                    // 也更容易触发多 Carpet 附加下的 NPE 重试。
                    ensureBotFor(server, name, ready -> {
                    });
                    return;
                }
            }
            // 全部就绪 → 要求全部空闲（任一个在忙就等下一刻）
            for (String name : botNames) {
                EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, name);
                if (bot == null || FakePlayerActionScheduler.isRunning(bot)) {
                    return;
                }
            }
            // 先统一清遗留：假人背包是<b>持久</b>的，上批没取走的货会混进本批交付结果
            //（见 prepareBotForNewTask 的注释，这正是"背出上一个原理图的货"的来源）
            for (String name : botNames) {
                EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, name);
                if (bot == null) {
                    return;
                }
                if (!prepareBotForNewTask(server, playerId, bot)) {
                    abortReason = "假人 " + name + " 身上还有上一批的货没取走（末影箱也满了），"
                            + "请先取走它身上的货再重试";
                    return;
                }
            }
            // 派单
            for (int i = 0; i < botNames.size(); i++) {
                String name = botNames.get(i);
                EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, name);
                if (bot == null) {
                    // 前面刚校验过全部就绪，这里却查不到了（极罕见：同一 tick 内被别的机制清掉）——
                    // 不能 skip 了事，那一份需求会被<b>静默丢掉</b>。中止整批，并把这一份登记成续备。
                    // （已经 start 的其他假人继续跑完并交付，不影响它们自己的份额。）
                    schedulePendingRestock(playerId, playerName, slices.get(i));
                    abortReason = "假人 " + name + " 在派单瞬间消失，本批已中止"
                            + "（它那一份需求已登记，取走现有货后会自动续备）";
                    return;
                }
                BulkFetchRepackAction action = new BulkFetchRepackAction(bot, playerId, playerName,
                        slices.get(i),
                        reason -> RestockScheduler.notify(server, playerId, ChatFormatting.GREEN,
                                "[FLT][" + name + "] " + reason));
                // 并行时每个假人只拿一片：因<b>竞态</b>少取（货被另一个假人先取走）不会触发任何
                // abort 标志（见 advanceFetch 里 targets.isEmpty() 直接跳过）→ 必须让动作无条件
                // 登记缺口，由 schedulePendingRestock 合并累加后补齐
                action.setRegisterLeftoverAlways(true);
                FakePlayerActionScheduler.start(bot, action);
            }
            dispatched = true;
            FLTAdditionMod.LOGGER.info("[FLT] 并行备货已派单：{}", String.join("/", botNames));
        }

        /** 本批所有假人是否都已不再执行动作（被手动清掉的假人算已结束） */
        boolean allFinished(MinecraftServer server) {
            for (String name : botNames) {
                EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, name);
                if (bot != null && FakePlayerActionScheduler.isRunning(bot)) {
                    return false;
                }
            }
            return true;
        }
    }

    // =========================================================
    // 取货缓存空间（[VERSION] 2026-09-29）：派单前的容量闸门 + 按缓存挑假人
    // =========================================================

    /** "缓存不够"提示的限频表（假人 UUID → 上次提示时间戳） */
    private static final Map<UUID, Long> CAPACITY_NOTICE_AT = new HashMap<>();

    /**
     * 同一个假人的"缓存不够"提示最短间隔（毫秒）。
     *
     * <p>为什么必须限频：请求留在队首后会<b>每刻</b>重试（见 {@link #tickPending}），
     * 不限频就会每 50 毫秒往聊天栏刷一条 —— 玩家还没看清就刷满了。
     */
    private static final long CAPACITY_NOTICE_INTERVAL_MS = 30_000L;

    /**
     * 从该玩家的备货假人里挑一个"现在就能干活"的：存在、空闲、缓存空间最充裕。
     *
     * <p>为什么按缓存空间挑而不是固定用主假人：单件取货可能连着点很多次，假人身上越攒越满；
     * 此时另一个槽位的假人可能还是空的，挑最闲的能少一次"装不下 → 排队"。
     * 并行关闭时该玩家只有一个假人（槽位 0），行为与改造前完全一致。
     *
     * @return 可用的假人；全部在忙 / 都还没建好时返回 null（调用方把它排进缓存池等）
     */
    private static EntityPlayerMPFake pickIdleBot(MinecraftServer server, UUID playerId) {
        int count = Math.max(1, FakePlayerNaming.slotCount(playerId));
        EntityPlayerMPFake best = null;
        int bestCache = -1;
        for (String name : FakePlayerNaming.botNamesFor(playerId, count)) {
            EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, name);
            if (bot == null || FakePlayerActionScheduler.isRunning(bot)) {
                continue;
            }
            int cache = RestockCapacity.cacheSlots(bot);
            if (cache > bestCache) {
                bestCache = cache;
                best = bot;
            }
        }
        return best;
    }

    /**
     * 派单前的取货缓存闸门：保证假人背包有 {@code needSlots} 个空格，不够就把成品盒腾进末影箱。
     *
     * <p>为什么不能只靠取货动作内部的容量闸门（{@code MultiContainerWithdrawAction.stopFreeSlots}）：
     * 那个是"取到一半发现装不下 → 暂停"，此刻假人已经跑到仓库、开了箱，白跑一趟；而且<b>单件取货</b>
     * 那条链路的上层没有"装箱后再回来继续取"的机制，暂停就等于少取。这里是"出门前先看行李装不装得下"。
     *
     * @return true = 空间已够（可能刚腾过）；false = 腾不出来，调用方应把请求留在队列里等
     */
    private static boolean ensureCacheSpace(MinecraftServer server, UUID playerId,
                                            EntityPlayerMPFake bot, int needSlots) {
        int free = RestockCapacity.freeInventorySlots(bot);
        if (needSlots <= 0 || free >= needSlots) {
            return true;
        }
        int after = RestockCapacity.makeRoom(bot, needSlots);
        if (after > free) {
            FLTAdditionMod.LOGGER.info("[FLT] 假人 {} 取货前腾挪成品盒：背包空位 {} → {}（本次需要 {} 格）",
                    bot.getGameProfile().name(), free, after, needSlots);
        }
        if (after >= needSlots) {
            return true;
        }
        if (after > 0) {
            // [VERSION] 2026-09-29 自查修复（本轮引入的回归）：<b>需求本身就超过假人总容量</b>时
            // （例如一次取 5000 个石头 = 79 格，而背包 36 + 末影箱 27 封顶），腾到极限也装不下。
            // 此时<b>必须照常派单</b>：取货动作会尽力装（装多少算多少），缺口由后续取货/续备补
            // —— 这正是改造前的行为。若在这里拦住，请求会永远派不出去（每 PENDING_TIMEOUT_MS
            // 被超时丢弃一次），玩家看到的是"点了没反应"，比"少取但可见"更糟。
            notifyCapacity(server, playerId, bot, after, needSlots, true);
            return true;
        }
        // 一格空位都没有（成品盒挪不动、末影箱也满）→ 派了必然立刻失败，留在队列里等玩家取货。
        // 这才是"取货缓存等待"真正该拦的情形。
        notifyCapacity(server, playerId, bot, after, needSlots, false);
        return false;
    }

    /**
     * 告知玩家假人空间状况（同一假人 30 秒内只提示一次，避免每刻刷屏）。
     *
     * <p>两种语气分开说清，否则玩家没法判断该怎么办：
     * <ul>
     *   <li>{@code partial=true}：装得下一部分 → "会先把能装的取回来"，不需要玩家做任何事；</li>
     *   <li>{@code partial=false}：一格都腾不出来 → 必须玩家来取货，否则队列会一直等（最终超时）。</li>
     * </ul>
     */
    private static void notifyCapacity(MinecraftServer server, UUID playerId,
                                       EntityPlayerMPFake bot, int free, int need, boolean partial) {
        long now = System.currentTimeMillis();
        Long last = CAPACITY_NOTICE_AT.get(bot.getUUID());
        if (last != null && now - last < CAPACITY_NOTICE_INTERVAL_MS) {
            return;
        }
        CAPACITY_NOTICE_AT.put(bot.getUUID(), now);
        String name = bot.getGameProfile().name();
        String text = partial
                ? "[FLT] 假人 " + name + " 的空间只够装一部分（背包剩 " + free + " 格，本次需要 "
                        + need + " 格）：会先把装得下的取回来，剩下的需要你取走这批后再取一次"
                : "[FLT] 假人 " + name + " 的取货缓存不够：背包剩 " + free + " 格（末影箱剩 "
                        + RestockCapacity.freeEnderSlots(bot) + " 格），本次需要 " + need
                        + " 格。请先取走它身上的成品盒，空间腾出后排队中的取货会自动继续";
        notify(server, playerId, ChatFormatting.YELLOW, text);
    }

    // 待派单队列的实现（见上面 PENDING_FETCH 的说明）
    // =========================================================

    /** 把一个取货请求放进该玩家的取货缓存池；队列满则拒绝（返回 false → 客户端提示发送失败） */
    private static boolean enqueueFetch(UUID playerId, PendingFetch req) {
        ArrayDeque<PendingFetch> queue = PENDING_FETCH.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        if (queue.size() >= MAX_PENDING_PER_PLAYER) {
            FLTAdditionMod.LOGGER.warn("[FLT] 玩家 {} 的取货缓存已满（{} 件），拒绝 {} x{}",
                    playerId, queue.size(), req.itemId(), req.count());
            return false;
        }
        queue.addLast(req);
        FLTAdditionMod.LOGGER.info("[FLT] 玩家 {} 的取货请求进入缓存池 {} x{}（队列 {} 件）",
                playerId, req.itemId(), req.count(), queue.size());
        return true;
    }

    /**
     * 每刻推进待派单队列（由 {@link #tick} 调用，<b>不受 autoRestock 开关影响</b>）。
     *
     * <p>为什么用"轮询"而不是"挂在建档回调上"：建档可能失败（createFake 被拒 / 实体始终没进
     * 玩家列表），回调一旦不触发，挂在里面的请求就永远发不出去。轮询 + 超时丢弃能兜住所有异常路径。
     */
    private static void tickPending(MinecraftServer server) {
        if (PENDING_FETCH.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, ArrayDeque<PendingFetch>>> it = PENDING_FETCH.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ArrayDeque<PendingFetch>> entry = it.next();
            UUID playerId = entry.getKey();
            ArrayDeque<PendingFetch> queue = entry.getValue();

            // 超时的先剔除并告知玩家（否则永远挂着，玩家以为单已经派了）
            dropExpired(server, playerId, queue);
            if (queue.isEmpty()) {
                it.remove();
                continue;
            }

            // [VERSION] 2026-09-29 闭环审计修复：该玩家已离线 → 这条队列没人收货了，丢弃。
            // 否则会为一个已离线的玩家凭空建档，生成一个谁也拿不到货的假人。
            if (server.getPlayerList().getPlayer(playerId) == null) {
                it.remove();
                continue;
            }

            // 挑一个空闲且缓存最充裕的假人（并行时是在多个槽位里选）
            EntityPlayerMPFake bot = pickIdleBot(server, playerId);
            if (bot == null) {
                // 全都在忙 / 还没建好 → 只对主假人催一次建档（同一刻 createFake 多个会叠成一次卡顿）。
                // "空间不够"不在这里处理：{@link #flushOne} 的闸门会把请求留在队首等玩家取货。
                String primaryBot = FakePlayerNaming.botNameFor(playerId);
                if (FakePlayerUtils.findFakePlayer(server, primaryBot) == null) {
                    botFor(server, primaryBot);
                }
                continue;
            }

            flushOne(server, playerId, bot);
            if (queue.isEmpty()) {
                it.remove();               // ⚠️ 只能通过迭代器删，直接改 map 会 ConcurrentModificationException
            }
        }
    }

    /**
     * 从该玩家的取货缓存池里取一件派出去。
     *
     * <p>注意：本方法只动队列（deque），<b>不动 PENDING_FETCH 这张 map</b> ——
     * 它会被 {@link #tickPending} 的迭代器调用，直接改 map 会抛并发修改异常。
     * map 的清理统一交给 tickPending 的 {@code it.remove()}。
     *
     * @return true = 成功派出去一件；false = 队列空 / 空间不够（请求留在队首等）/ 派单失败
     */
    private static boolean flushOne(MinecraftServer server, UUID playerId, EntityPlayerMPFake bot) {
        ArrayDeque<PendingFetch> queue = PENDING_FETCH.get(playerId);
        if (queue == null || queue.isEmpty()) {
            return false;
        }
        // 空间闸门（用队首请求估算需求）：不够就先腾成品盒；腾不动就 <b>不 poll</b> ——
        // 请求留在队首等玩家取货腾空间，等他把这批货取走后自动继续。
        // 这正是"取货缓存"的核心语义：宁可在队列里等，也不丢请求、更不静默少取。
        PendingFetch head = queue.peekFirst();
        if (head == null) {
            return false;
        }
        int needSlots = RestockCapacity.neededSlots(head.itemId(), head.count())
                + RestockCapacity.SAFETY_SLOTS;
        if (!ensureCacheSpace(server, playerId, bot, needSlots)) {
            return false;
        }

        PendingFetch req = queue.pollFirst();
        // [VERSION] 2026-09-24：boxMode（打包）走 dispatchPack，与 fetchSingle 的即时派单保持一致
        boolean dispatched = req.boxMode()
                ? dispatchPack(server, req.playerId(), playerNameOf(server, req.playerId()), bot, req.itemId(), req.count())
                : dispatch(server, req.playerId(), bot, req.itemId(), req.count(), false, true);
        if (!dispatched) {
            // 派不出去就别让它堵住后面的请求：剔除并明确告知玩家原因
            notify(server, req.playerId(), ChatFormatting.RED,
                    "[FLT] 排队中的取货未能派单（仓库已无该物品或假人正忙），请重试：" + req.itemId());
        }
        return dispatched;
    }

    /**
     * 丢弃队列里已超时的请求并告知玩家。
     *
     * <p><b>为什么按"假人是否已就绪"分两档超时</b>（[VERSION] 2026-09-29 自查修复）：
     * 这个队列现在有两种等待原因，玩家能做的事完全不同 ——
     * <ul>
     *   <li><b>假人还没建好</b>：建档失败或极慢，玩家干等没有意义 → {@link #PENDING_TIMEOUT_MS}（15 秒）放弃；</li>
     *   <li><b>假人已就绪，只是忙或没空间</b>：这是"取货缓存"的正常等待，玩家可能正在走过来取货
     *       → 给 {@link #PENDING_SPACE_TIMEOUT_MS}（10 分钟）。</li>
     * </ul>
     */
    private static void dropExpired(MinecraftServer server, UUID playerId,
                                    ArrayDeque<PendingFetch> queue) {
        if (queue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long timeout = anyBotReady(server, playerId) ? PENDING_SPACE_TIMEOUT_MS : PENDING_TIMEOUT_MS;
        while (!queue.isEmpty() && now - queue.peekFirst().queuedAtMs() > timeout) {
            PendingFetch req = queue.pollFirst();
            notify(server, req.playerId(), ChatFormatting.RED,
                    "[FLT] 取货请求等待超时已取消（假人一直没能就绪，或一直没腾出空间）："
                            + req.itemId() + " x" + req.count());
        }
    }

    /**
     * 该玩家是否至少有一个备货假人在线。
     *
     * <p>用途：区分"假人还在建档"（15 秒短超时）与"假人已就绪但在忙/没空间"（10 分钟长超时）。
     */
    private static boolean anyBotReady(MinecraftServer server, UUID playerId) {
        int count = Math.max(1, FakePlayerNaming.slotCount(playerId));
        for (String name : FakePlayerNaming.botNamesFor(playerId, count)) {
            if (FakePlayerUtils.findFakePlayer(server, name) != null) {
                return true;
            }
        }
        return false;
    }

    /** 给玩家发一条聊天消息（玩家已离线则静默丢弃） */
    private static void notify(MinecraftServer server, UUID playerId, ChatFormatting color, String text) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(Component.literal(text).withStyle(color));
        }
    }

    /**
     * 按 UUID 取玩家名（{@link PendingFetch} 只存了 UUID，而
     * {@link BulkFetchRepackAction} 构造需要 playerName）。
     * 玩家已离线时返回空串 —— 该字段在 Action 里只作存档/日志用，不影响行为。
     */
    private static String playerNameOf(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null ? player.getName().getString() : "";
    }

    /** 服务器关闭时清空待派单队列（跨存档不留残留） */
    public static void clearPending() {
        PENDING_FETCH.clear();
    }

    /**
     * 该玩家<b>全部</b>备货假人是否背包都空（并行备货时货分散在多人身上，见 {@link #tickPendingRestock}）。
     *
     * <p>假人被手动清掉 / 不存在时视为"空" —— 否则那个槽位会永远挡住续备。
     */
    private static boolean allBotsEmpty(MinecraftServer server, UUID playerId) {
        int count = Math.max(1, FakePlayerNaming.slotCount(playerId));
        for (String name : FakePlayerNaming.botNamesFor(playerId, count)) {
            EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, name);
            if (bot != null && !isInventoryEmpty(bot)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把该玩家<b>全部</b>备货假人身上的物品计数汇总后推给客户端。
     *
     * <p>[VERSION] 2026-09-29 并行备货：货分散在多个假人身上，只读主假人会让客户端材料列表
     * 一直显示"已备 0"。合并口径与单假人时一致（都走 {@link InventoryCounter}：只算背包与盒内明细，
     * 不含末影箱格数 —— 末影箱那部分靠交付提示告知玩家）。
     *
     * <p>一个假人都不在线时<b>不发包</b>（与改造前 pushCurrentStock 的行为一致：
     * 没假人就不刷新，而不是推一个空计数把客户端的"已备"清成 0）。
     */
    public static void pushStockTotal(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return;
        }
        int count = Math.max(1, FakePlayerNaming.slotCount(playerId));
        Map<Identifier, Integer> total = new HashMap<>();
        boolean anyOnline = false;
        for (String name : FakePlayerNaming.botNamesFor(playerId, count)) {
            EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, name);
            if (bot == null) {
                continue;
            }
            anyOnline = true;
            for (Map.Entry<Identifier, Integer> entry : InventoryCounter.count(bot).entrySet()) {
                total.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        if (!anyOnline) {
            return;
        }
        ServerPlayNetworking.send(player, new FakePlayerStockPayload(total));
    }

    /** 把假人身上物品计数推给该玩家客户端（客户端没装 flt-tools 时 fabric 会静默跳过） */
    public static void pushStock(MinecraftServer server, UUID playerId, EntityPlayerMPFake bot) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || bot == null) {
            return;
        }
        ServerPlayNetworking.send(player, new FakePlayerStockPayload(InventoryCounter.count(bot)));
    }

    /** 按玩家查出其备货假人并立刻推一次当前库存（假人不存在 / 玩家离线则什么都不做，不建档） */
    public static void pushCurrentStock(MinecraftServer server, UUID playerId) {
        if (server == null) {
            return;
        }
        // [VERSION] 2026-09-29：改成汇总全部假人（并行备货时单个假人的计数只是其中一片）
        pushStockTotal(server, playerId);
    }

    /** 库存源里第一个可用容器（跨维度），用于给备货假人选址 */
    private static StockSpot firstStockSpot(MinecraftServer server) {
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> entry : StockSource.all().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            BlockPos pos = entry.getValue().iterator().next();
            return new StockSpot(level, new Vec3(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D));
        }
        return null;
    }

    /** 假人建档落点 */
    private record StockSpot(ServerLevel level, Vec3 spawnPos) {
    }

    /**
     * 服务器关闭时清空所有"跨存档会残留"的状态（[VERSION] 2026-09-29 闭环审计修复）。
     *
     * <p>⚠️ 为什么必须清干净：单人游戏"退到标题再进另一个世界"时 <b>JVM 不会重启</b>，
     * 静态字段会原样带进新存档。而 {@link #PENDING_FETCH} 里排队的请求一旦残留，
     * {@code tickPending} 会对旧存档的假人名字反复 {@code botFor} → <b>在新世界凭空创建假人</b>。
     * （原实现只清了 tickCounter 与命名表，三个 PENDING_* 全漏了。）
     * <p>保存调用顺序见 {@code FLTAdditionServer}：必须在
     * {@link BotIndexPersistence#save} 之后、{@link StockSource#clear} 之前。
     */
    public static void clear() {
        tickCounter = 0;
        PENDING_FETCH.clear();
        PENDING_RECYCLE.clear();
        PENDING_RESTOCK.clear();
        PARALLEL_JOBS.clear();   // [VERSION] 2026-09-29：并行任务同样是跨存档残留源，必须一起清
        CAPACITY_NOTICE_AT.clear();
        FakePlayerNaming.clear();
    }
}
