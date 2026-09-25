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
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 备货假人调度器（组合拳二期核心）。
 *
 * <p>两条派单入口，共用<b>同一个</b>全服备货假人（命名走 {@link FakePlayerNaming#botNameFor(String)}，
 * 名字 = FLT 自己的 carpet 规则 {@code fltFakePlayerPrefix} 规则值 + "carry"，不含玩家名，总长 ≤ 16）：
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

    /** 假人名 → 该假人的待派单请求（先到先做） */
    private static final Map<String, ArrayDeque<PendingFetch>> PENDING_FETCH = new HashMap<>();

    /** 单个假人最多允许排多少件（防连点把队列刷爆） */
    private static final int MAX_PENDING_PER_BOT = 8;

    /**
     * 排队多久还没派出去就放弃并告知玩家（毫秒）。
     *
     * <p>为什么要有超时：假人可能永远建不起来（createFake 被拒、库存源被清空、服务端卡住），
     * 没超时的话请求会永远挂在队列里，玩家点了界面却什么反馈都没有。
     */
    private static final long PENDING_TIMEOUT_MS = 15_000L;

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

    /** 假人背包（含快捷栏）是否已空 */
    private static boolean isInventoryEmpty(EntityPlayerMPFake bot) {
        var inventory = bot.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                return false;
            }
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

        for (int i = 0; i < botInv.getContainerSize(); i++) {
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
        EntityPlayerMPFake bot = botFor(server, FakePlayerNaming.botNameFor(DemandRegistry.nameOf(playerId)));
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

        // 需求里没有"还缺且有货"的材料 → 回推一次当前库存，让客户端刷新 available
        pushStock(server, playerId, bot);
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
        String botName = FakePlayerNaming.botNameFor(playerName);
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, botName);

        // ① 假人已就绪且空闲 → 直接派单（常规路径）
        if (bot != null && !FakePlayerActionScheduler.isRunning(bot)) {
            return boxMode
                    ? dispatchPack(server, playerId, playerName, bot, itemId, want)
                    : dispatch(server, playerId, bot, itemId, want, false, true);
        }

        // ② 假人不在 → 先确认"有库存源"才值得建档，避免把永远建不出来的人排进队列
        if (bot == null) {
            if (firstStockSpot(server) == null) {
                return false;   // 没登记库存源 → 无从建档，如实告诉客户端失败
            }
            botFor(server, botName);   // 触发异步建档；建档完成后由 tickPending 轮询补派
        }

        // ③ 建档中 / 正忙 → 入队
        return enqueueFetch(botName, new PendingFetch(playerId, itemId, want, boxMode,
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
            notify(server, playerId, ChatFormatting.YELLOW,
                    "[FLT] 备货失败：你还没有上报投影材料需求（先打开投影材料列表）");
            return false;
        }
        String botName = FakePlayerNaming.botNameFor(playerName);
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, botName);

        // 假人不在 → 尝试建档；建档是异步分步的，注册「建档完成 → 自动启动备货动作」的回调，
        // 一次点击即可在假人生成后自动取货装箱，无需玩家再点一次（2026-09-23 修复）。
        if (bot == null) {
            if (firstStockSpot(server) == null) {
                notify(server, playerId, ChatFormatting.RED,
                        "[FLT] 备货失败：还没登记任何库存源仓库（用 /flt stock add 添加）");
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

        startRestockAll(server, playerId, playerName, demand);
        return true;
    }

    /** 把「一键备齐+装箱送回」动作挂到指定假人上（假人必须已就绪且空闲） */
    private static void startRestockAll(MinecraftServer server, UUID playerId, String playerName,
                                        Map<Identifier, Integer> demand) {
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, FakePlayerNaming.botNameFor(playerName));
        if (bot == null || FakePlayerActionScheduler.isRunning(bot)) {
            return;
        }
        FakePlayerActionScheduler.start(bot, new BulkFetchRepackAction(
                bot, playerId, playerName, demand, reason ->
                        notify(server, playerId, ChatFormatting.GREEN, "[FLT] " + reason)));
        FLTAdditionMod.LOGGER.info("[FLT] 玩家 {} 触发了「一键备齐+装箱送回」，需求 {} 种",
                playerName, demand.size());
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
            if (deliverToPlayer && !flushOne(server, bot))
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
            pushStock(server, playerId, bot);
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

        bot.teleportTo(level, x, player.getY(), z,
                Set.<Relative>of(), player.getYRot(), player.getXRot(), false);

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
    // 待派单队列的实现（见上面 PENDING_FETCH 的说明）
    // =========================================================

    /** 把一个取货请求放进队列；队列满则拒绝（返回 false → 客户端提示发送失败） */
    private static boolean enqueueFetch(String botName, PendingFetch req) {
        ArrayDeque<PendingFetch> queue = PENDING_FETCH.computeIfAbsent(botName, k -> new ArrayDeque<>());
        if (queue.size() >= MAX_PENDING_PER_BOT) {
            FLTAdditionMod.LOGGER.warn("[FLT] 假人 {} 待派单队列已满（{} 件），拒绝 {} x{}",
                    botName, queue.size(), req.itemId(), req.count());
            return false;
        }
        queue.addLast(req);
        FLTAdditionMod.LOGGER.info("[FLT] 假人 {} 尚未就绪或正忙，已排队取货 {} x{}（队列 {} 件）",
                botName, req.itemId(), req.count(), queue.size());
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
        Iterator<Map.Entry<String, ArrayDeque<PendingFetch>>> it = PENDING_FETCH.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ArrayDeque<PendingFetch>> entry = it.next();
            String botName = entry.getKey();
            ArrayDeque<PendingFetch> queue = entry.getValue();

            // 超时的先剔除并告知玩家（否则永远挂着，玩家以为单已经派了）
            dropExpired(server, queue);
            if (queue.isEmpty()) {
                it.remove();
                continue;
            }

            EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, botName);
            if (bot == null) {
                botFor(server, botName);   // 还没建好（或刚才建档失败）→ 再催一次建档
                continue;
            }
            if (FakePlayerActionScheduler.isRunning(bot)) {
                continue;                  // 正忙 → 等手里这件做完
            }

            flushOne(server, bot);
            if (queue.isEmpty()) {
                it.remove();               // ⚠️ 只能通过迭代器删，直接改 map 会 ConcurrentModificationException
            }
        }
    }

    /**
     * 从该假人的队列里取一件派出去。
     *
     * <p>注意：本方法只动队列（deque），<b>不动 PENDING_FETCH 这张 map</b> ——
     * 它会被 {@link #tickPending} 的迭代器调用，直接改 map 会抛并发修改异常。
     * map 的清理统一交给 tickPending 的 {@code it.remove()}。
     *
     * @return true = 成功派出去一件；false = 队列为空，或派单失败（无货 / 假人又忙了）
     */
    private static boolean flushOne(MinecraftServer server, EntityPlayerMPFake bot) {
        String botName = bot.getGameProfile().name();
        ArrayDeque<PendingFetch> queue = PENDING_FETCH.get(botName);
        if (queue == null || queue.isEmpty()) {
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

    /** 丢弃队列里已超时的请求并告知玩家 */
    private static void dropExpired(MinecraftServer server, ArrayDeque<PendingFetch> queue) {
        long now = System.currentTimeMillis();
        while (!queue.isEmpty() && now - queue.peekFirst().queuedAtMs() > PENDING_TIMEOUT_MS) {
            PendingFetch req = queue.pollFirst();
            notify(server, req.playerId(), ChatFormatting.RED,
                    "[FLT] 取货请求超时（假人未能就绪），已取消：" + req.itemId() + " x" + req.count());
        }
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
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server,
                FakePlayerNaming.botNameFor(DemandRegistry.nameOf(playerId)));
        if (bot != null) {
            pushStock(server, playerId, bot);
        }
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

    /** 服务器关闭时重置节拍 */
    public static void clear() {
        tickCounter = 0;
    }
}
