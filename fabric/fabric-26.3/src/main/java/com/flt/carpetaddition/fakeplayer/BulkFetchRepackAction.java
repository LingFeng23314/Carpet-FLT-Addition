package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import com.flt.carpetaddition.storage.ContainerGroup;
import com.flt.carpetaddition.storage.StackCounter;
import com.flt.carpetaddition.storage.StockSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 假人「一键备齐 + 装箱送回」动作（组合拳·投影「假人备货」按钮的服务端实现）。
 *
 * <p>用户从投影材料列表点「假人备货」后，服务端为这位玩家启动本动作，为假人执行完整流程：
 * <ol>
 *   <li><b>取齐（FETCH）</b>：逐物品算「需求 − 假人背包已有」缺口，跨箱取货到假人背包。
 *       复用 {@link MultiContainerWithdrawAction#collectTargets} 收集目标槽，再按
 *       传送 → 开箱 → 逐槽 {@code quickMoveStack} 的多刻状态机搬货。</li>
 *   <li><b>装箱（PACK）</b>：仓库找空潜影盒 → 让假人从仓库拿一个盒 → 打开盒 → 把假人背包的
 *       散装物品尽量填满盒，产出一个成品盒（每盒最多 27 格 × 64 = 1728 个）。</li>
 *   <li><b>送回（DELIVER）</b>：假人背着成品盒（若有）与散装余量送到玩家面前。</li>
 * </ol>
 *
 * <p><b>为什么装箱</b>：大量散装物品（如 1728 石砖）占满假人 36 格背包；打包成一盒只占一格，
 * 一次就能把大量材料送到玩家面前（玩家右键假人背包取走成品盒，比逐格捡散装高效得多）。
 *
 * <p><b>空盒来源</b>：从已登记仓库里找「空潜影盒」（{@code StackCounter.count} 判定为"盒本身"
 * 即空盒）。仓库没有空盒 → 不装箱、散装送回（不额外造盒，绝不变出物品）。
 *
 * <p><b>状态机</b>（每 tick 由 {@link FakePlayerActionScheduler} 驱动，同一时刻只执行一个子阶段）：
 * <pre>
 *   FETCH:  for each 需求物品 in 排序后的缺口列表:
 *              传送(1t) → 开箱(1t) → 逐槽取货(直到该物品够需求或箱空)
 *   PACK:  找空盒(1t) → 去空盒箱取空盒(传送/开箱/取盒) → 打开盒(1t) → 填物(每t填若干格) → 完成
 *          所有散装填完 或 仓库没有空盒 → 结束装箱
 *   DELIVER: 传送假人到玩家面前，report，return false 结束
 * </pre>
 *
 * <p>【尽力而为】某物品仓库取光也不够需求 → 取到多少算多少；没空盒 → 散装送回。
 * 结束时理 {@code reporter} 汇报汇总。
 */
public class BulkFetchRepackAction extends AbstractFakePlayerAction {
    /** 玩家 UUID（送回目标） */
    private final UUID playerId;
    /** 玩家名（仅日志/命名用） */
    private final String playerName;

    /** 需求表（物品ID → 需要总数），键已排序 */
    private final List<Map.Entry<Identifier, Integer>> demands;
    /** 当前处理到第几个需求物品 */
    private int demandIndex = 0;

    /** 结束反馈 */
    private final Consumer<String> reporter;

    // ============ 汇总统计 ============
    private int fetchedTotalItems = 0;   // 累计取到的散装物品数（所有物品）
    private int packedBoxes = 0;         // 产出的成品盒数
    private String message;

    // ============ 阶段码 ============
    private static final int PHASE_FETCH = 0;
    private static final int PHASE_PACK = 1;
    private static final int PHASE_DELIVER = 2;
    private int phase = PHASE_FETCH;

    // ---- 取货（FETCH）子状态：逐物品委托 MultiContainerWithdrawAction（跨箱取货做法参考 LMS，本实现独立编写） ---- //
    /** 当前正在执行「单个物品跨箱取货」的子动作；null 表示待对下一个需求物品新建 */
    private MultiContainerWithdrawAction currentFetcher;

    // ---- 装箱（PACK）子状态 ----
    private BlockPos emptyBoxSrcPos;   // 找到空盒的仓库容器坐标
    private int emptyBoxSrcSlot;       // 该容器里空盒的槽索引
    private AbstractContainerMenu boxSrcMenu;  // 去取空盒打开的箱菜单
    private BlockPos boxSrcOpenPos;
    private int boxSrcSize;
    private int packSub = 0;           // PACK 子阶段：0=判断还需装箱/1=找空盒/2=取空盒/3=装一盒

    // ---- 卡死看门狗 ----
    /** 连续多少刻没有任何实质进展（取到物/换箱/换阶段等）就强制降级，避免动作永挂 */
    private static final int MAX_STALL_TICKS = 600;   // 30 秒无进展即视为卡死
    private int stallTicks = 0;
    /** 上次推进时的"无进展判定依据"，用于日志对比 */
    private String stallMark = "";

    public BulkFetchRepackAction(EntityPlayerMPFake fakePlayer,
                                 UUID playerId, String playerName,
                                 Map<Identifier, Integer> demands,
                                 Consumer<String> reporter) {
        super(fakePlayer);
        this.playerId = playerId;
        this.playerName = playerName;
        this.demands = new ArrayList<>(demands.entrySet());
        this.demands.sort(Comparator.comparing(e -> e.getKey().toString()));
        this.reporter = reporter;
    }

    @Override
    public boolean tick() {
        try {
            boolean keep;
            String mark = progressMark();
            switch (phase) {
                case PHASE_FETCH -> { keep = tickFetch(); }
                case PHASE_PACK -> { keep = tickPack(); }
                default -> { return tickDeliver(); }
            }
            // 卡死看门狗：若本 tick 前后状态毫无变化（没取到物/没换槽/没换阶段），累计刻度，
            // 连续 MAX_STALL_TICKS 刻无进展则强制降级到交付（宁可送货上门也不永挂）。
            if (!mark.equals(stallMark)) {
                stallMark = mark;
                stallTicks = 0;
            } else if (++stallTicks >= MAX_STALL_TICKS) {
                FLTAdditionMod.LOGGER.warn("[FLT] 备货动作卡死（状态 {} 连续 {} 刻无进展），强制降级到交付",
                        mark, stallTicks);
                currentFetcher = null;
                if (boxSrcMenu != null) {
                    try { getFakePlayer().closeContainer(); } catch (Throwable ignored) {}
                    boxSrcMenu = null;
                }
                phase = PHASE_DELIVER;
                return true;
            }
            return keep;
        } catch (Throwable t) {
            FLTAdditionMod.LOGGER.error("[FLT] 备货打包动作异常，终止", t);
            message = "备货过程出错：" + t.getClass().getSimpleName();
            return false;
        }
    }

    /**
     * 生成当前"进展指纹"：阶段 + 当前物品 + 目标槽 + 装箱子阶段 + 背包散装总串。
     * 用作卡死看门狗判断「有无实质进展」的依据（同指纹连续多刻即视为卡住）。
     */
    private String progressMark() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("p=").append(phase)
                .append(";demand=").append(demandIndex).append('/').append(demands.size());
        if (currentFetcher != null) {
            // 印出子动作的内部进度（idx/已取数/当前箱），这样即使取同一个物品，
            // 只要它在推进（传送/开箱/取槽），指纹就会变化 —— 看门狗不会误判卡死
            sb.append(";item=").append(currentFetchItemId)
                    .append('[').append(currentFetcher.progressSnapshot()).append(']');
        }
        sb.append(";packSub=").append(packSub);
        if (emptyBoxSrcPos != null) {
            sb.append(";box=").append(emptyBoxSrcPos);
        }
        sb.append(";loose=").append(looseTotalOnBot());
        return sb.toString();
    }

    /** 假人背包里散装（非潜影盒）物品的总数，供进展指纹对比 */
    private int looseTotalOnBot() {
        var inv = getFakePlayer().getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && !st.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                n++;
            }
        }
        return n;
    }

    // =========================================================
    // 阶段1：FETCH —— 逐物品跨箱取齐
    // =========================================================

    private boolean tickFetch() {
        // 还有子动作在跑 → 继续驱动它
        if (currentFetcher != null) {
            boolean more = currentFetcher.tick();
            if (more) {
                return true;
            }
            // 该物品取完（或无可取）：累计实际取到的数量，然后推进下一个需求物品
            int got = currentFetcher.withdrawnCount();
            fetchedTotalItems += got;
            FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 物品 {} 取 {} 个（累计 {}）",
                    currentFetchItemId, got, fetchedTotalItems);
            currentFetcher = null;
            currentFetchItemId = null;
        }
        return advanceFetch();
    }

    /** 当前正在取的目标物品 ID（日志用） */
    private String currentFetchItemId;

    private boolean advanceFetch() {
        for (;;) {
            if (demandIndex >= demands.size()) {
                FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 完成，进入 PACK（共取 {} 个材料）", fetchedTotalItems);
                phase = PHASE_PACK;
                return true;   // 进入装箱
            }
            Map.Entry<Identifier, Integer> de = demands.get(demandIndex);
            Identifier itemId = de.getKey();
            int need = de.getValue();
            int have = countItemOnBot(itemId);
            int remaining = need - have;
            demandIndex++;
            if (remaining <= 0) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(itemId);
            if (item == null) {
                continue;
            }
            // 复用已验证的跨箱取货动作（做法参考 LMS 的 getItemFromSlot 思路：逐目标槽、同箱连收、换箱分tick）——
            // 不再手写精简版状态机，避免"传送后位置未生效 / 菜单残留"等取 0 个的 bug（2026-09-23）。
            List<MultiContainerWithdrawAction.TargetSlot> targets =
                    MultiContainerWithdrawAction.collectTargets(server(), itemId, item, false, remaining);
            if (targets.isEmpty()) {
                FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 仓库无 {}，跳过", itemId);
                continue;   // 仓库没货 → 跳过该物品
            }
            FLTAdditionMod.LOGGER.info("[FLT] 备货 FETCH 委托取货 {} 需求 {}，目标槽 {} 处",
                    itemId, remaining, targets.size());
            currentFetcher = new MultiContainerWithdrawAction(
                    getFakePlayer(), targets, item, itemId.toString(), remaining, false, null);
            currentFetchItemId = itemId.toString();
            return true;
        }
    }

    // =========================================================
    // 阶段2：PACK —— 反复取空盒装箱，直到散装清空或空盒用完
    // =========================================================

    private boolean tickPack() {
        switch (packSub) {
            case 0 -> {
                // 有可装箱的散装才继续；否则直接交付
                if (!hasPackableLoose()) {
                    FLTAdditionMod.LOGGER.debug("[FLT] 备货 PACK 无散装可装箱，直接交付");
                    phase = PHASE_DELIVER;
                } else {
                    packSub = 1;
                }
                return true;
            }
            case 1 -> {
                // 找空盒；没有 → 散装带回，直接交付
                if (!locateEmptyBox()) {
                    FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 仓库无空盒，散装携带交付");
                    phase = PHASE_DELIVER;
                } else {
                    FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 找到空盒 {}/{}，开始取盒", emptyBoxSrcPos, emptyBoxSrcSlot);
                    packSub = 2;
                }
                return true;
            }
            case 2 -> {
                // 取空盒（多 tick）。完成后进入装盒
                if (!takeEmptyBoxOneTick()) {
                    packSub = 3;
                }
                return true;
            }
            default -> {
                // 3 = 用背包里第一个空盒装一盒，然后回到 0 判断是否还有散装
                packSub = 0;
                int before = looseTotalOnBot();
                fillFirstEmptyBoxFromInventory();
                FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 装完一盒（散装 {}→{}，累计 {} 盒）",
                        before, looseTotalOnBot(), packedBoxes);
                return true;
            }
        }
    }

    /** 假人背包是否还有"可装箱的散装物品"（非盒类的松散物品） */
    private boolean hasPackableLoose() {
        var inv = getFakePlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && !st.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                return true;
            }
        }
        return false;
    }

    /** 扫假人背包第一个空潜影盒 → 用它装一盒散装；装完该盒留在背包（成品）。 */
    private void fillFirstEmptyBoxFromInventory() {
        var inv = getFakePlayer().getInventory();
        // 找第一个空盒槽
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack box = inv.getItem(i);
            if (!isEmptyShulker(box)) {
                continue;
            }
            int filled;
            if (box.getCount() > 1) {
                // ⚠️ 堆叠空盒（ORG「潜影盒可堆叠」让空盒 count>1）：绝不能把整组建一个 CONTAINER 填充，
                //    否则 64 个盒子一次性全带上内容外壳、而物品只按单盒扣 → 材料"复制成堆叠盒"（实测 bug）。
                //    用 split(1) 只拆出【单个】盒来装，剩余 count-1 个空盒留在背包槽，下次还能继续拆用。
                ItemStack single = box.split(1);
                filled = fillBoxFromInventory(single);
                // split(1) 后 box=原组剩余(count-1)、single=单个盒(可能已装料/可能空)
                if (single.isEmpty()) {
                    // 拆出来的盒没能装进物品（理论不该）：若原组也空了就清槽，否则放回一个空盒保持占位
                    if (box.isEmpty()) {
                        inv.setItem(i, ItemStack.EMPTY);
                    } else {
                        inv.setItem(i, box);   // 剩余空盒留槽
                    }
                } else {
                    // single 已带内容=成品盒，放回本槽；剩余空盒 box 若仍有则挤到别处
                    inv.setItem(i, single);
                    if (!box.isEmpty()) {
                        inv.add(box);   // 剩余空盒放背包空位（通常还有空位）
                    }
                }
                if (filled > 0) {
                    packedBoxes++;
                }
                return;
            }
            // 本来就单个空盒：直接装
            filled = fillBoxFromInventory(box);
            if (filled > 0) {
                packedBoxes++;
            }
            return;
        }
    }

    /**
     * 扫描全部已登记容器，找第一个可拿的"空潜影盒"。
     * 找到后记录 {@code emptyBoxSrcPos / emptyBoxSrcSlot}。
     * @return true = 找到空盒来源；false = 仓库没有空盒
     */
    private boolean locateEmptyBox() {
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel level = server().getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            Set<BlockPos> seen = new HashSet<>();
            for (BlockPos pos : entry.getValue()) {
                BlockPos canonical = ContainerGroup.canonical(level, pos);
                if (!seen.add(canonical)) {
                    continue;
                }
                Container c = ContainerGroup.fullContainerFor(level, canonical);
                if (c == null) {
                    continue;
                }
                // 诊断：统计本容器里「空盒/潜影盒但非空/其它」的槽
                boolean anyBoxInThisContainer = false;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack stack = c.getItem(i);
                    if (stack.isEmpty() || !stack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                        continue;
                    }
                    anyBoxInThisContainer = true;
                    if (!isEmptyShulker(stack)) {
                        FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 诊断：{} 处槽 {} 是潜影盒但未认成空盒 -> {}", canonical, i, shulkerDiag(stack));
                    }
                }
                if (anyBoxInThisContainer) {
                    FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 诊断：容器 {} 内发现了潜影盒（含未识别为空的）", canonical);
                }
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack stack = c.getItem(i);
                    if (isEmptyShulker(stack)) {
                        emptyBoxSrcPos = canonical.immutable();
                        emptyBoxSrcSlot = i;
                        return true;
                    }
                }
            }
        }
        // 无空盒时，把「仓库里到底有些什么盒」也汇总一下，便于一次定位
        FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 未找到任何空盒，仓库内潜影盒盘点如下");
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel dl = server().getLevel(entry.getKey());
            if (dl == null) {
                continue;
            }
            Set<BlockPos> dseen = new HashSet<>();
            for (BlockPos pos : entry.getValue()) {
                BlockPos canonical = ContainerGroup.canonical(dl, pos);
                if (!dseen.add(canonical)) {
                    continue;
                }
                Container dc = ContainerGroup.fullContainerFor(dl, canonical);
                if (dc == null) {
                    continue;
                }
                for (int i = 0; i < dc.getContainerSize(); i++) {
                    ItemStack stack = dc.getItem(i);
                    if (!stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                        FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 盘点：{} 槽 {} = {}", canonical, i, shulkerDiag(stack));
                    }
                }
            }
        }
        return false;
    }

    /** 判断是否为"空潜影盒"（{@code StackCounter.count} 返回"盒本身"即空盒） */
    private static boolean isEmptyShulker(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
            return false;
        }
        StackCounter.Count count = StackCounter.count(stack);
        if (count == null) {
            return false;
        }
        Identifier self = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return count.itemId().equals(self);   // 空盒展开 = 盒本身
    }

    /**
     * 诊断用：为什么某些潜影盒没被认成"空盒"。
     * 逐盒打印：物品 id、数量、CONTAINER 组件是否存在及其非空内容数量。
     * （排查 ORG「潜影盒可堆叠」开启后空盒识别失败的根因。）
     */
    @SuppressWarnings("unused")
    private static String shulkerDiag(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
            return "非潜影盒";
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String self;
        try {
            self = id.toString();
        } catch (Throwable t) {
            self = "?";
        }
        var contents = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        String contentsDesc = "null";
        int innerCount = 0;
        if (contents != null) {
            innerCount = contents.nonEmptyItemCopyStream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            contentsDesc = "非空内容=" + innerCount;
        }
        return self + " ×" + stack.getCount() + " CONTAINER=" + contentsDesc;
    }

    /**
     * 去空盒所在箱取空盒（传送 → 开箱 → quickMoveStack 取那格）。
     * @return true = 还在进行；false = 已取到空盒（可能失败，失败只需继续走后续）
     */
    private boolean takeEmptyBoxOneTick() {
        ServerLevel level = level();
        ServerPlayer bot = getFakePlayer();

        // 换箱判断只靠坐标：不能写成 `boxSrcMenu == null || ...`——首次进入 menu 本就是 null，
        // 那样条件恒成立 → 每 tick 都重走"传送"、永远进不到下面 `if (boxSrcMenu == null)` 的开箱分支
        // （实测表现"找到空盒了却永远拿不到盒不装箱"，与 MultiContainerWithdrawAction 那个卡死同根）。
        if (!emptyBoxSrcPos.equals(boxSrcOpenPos)) {
            if (boxSrcMenu != null) {
                try {
                    bot.closeContainer();
                } catch (Throwable ignored) {
                }
                boxSrcMenu = null;
            }
            boxSrcOpenPos = emptyBoxSrcPos.immutable();
            bot.teleportTo(level,
                    emptyBoxSrcPos.getX() + 0.5D,
                    emptyBoxSrcPos.getY() + 1.0D,   // 站箱顶上方一格，避免卡箱（见 ContainerWithdrawAction 注释）
                    emptyBoxSrcPos.getZ() + 0.5D,
                    Set.<Relative>of(), bot.getYRot(), bot.getXRot(), false);
            return true;
        }
        if (boxSrcMenu == null) {
            // ⚠️ 用方块状态的标准菜单入口（双箱合并成54格菜单），与 locateEmptyBox 用
            //    fullContainerFor 扫到的槽号一致；否则双箱后半(槽27~52)的空盒越界取不到。
            net.minecraft.world.MenuProvider provider =
                    ContainerGroup.menuProviderFor(level, emptyBoxSrcPos);
            if (provider == null) {
                return false;   // 取不到，放弃这盒（回到 0 重来可能找到别处）
            }
            OptionalInt id = bot.openMenu(provider);
            if (id.isEmpty() || bot.containerMenu == null) {
                return false;
            }
            boxSrcMenu = bot.containerMenu;
            Container full = ContainerGroup.fullContainerFor(level, emptyBoxSrcPos);
            boxSrcSize = full != null ? full.getContainerSize() : 0;
            return true;
        }
        // 取空盒那一格
        int idx = emptyBoxSrcSlot;
        if (idx < 0 || idx >= boxSrcSize || idx >= boxSrcMenu.slots.size()) {
            return false;
        }
        Slot slot = boxSrcMenu.slots.get(idx);
        if (slot == null || !slot.hasItem() || !isEmptyShulker(slot.getItem())) {
            return false;   // 已被别人拿走等等
        }
        // 诊断：取盒前该槽物品（用于追踪"空盒没变少"=问题出在 quickMoveStack 复制）
        String beforeDesc = shulkerDiagSlot(slot.getItem());
        int beforeCount = slot.getItem().getCount();
        if (slot.getItem().getCount() > 1) {
            // ⚠️ 堆叠空盒（ORG「潜影盒可堆叠」让空盒 count>1）：一次只取【1 个】，剩余 count-1 留在仓库槽。
            //    这样仓库不会"整组被搬空"，且每次装箱只消耗 1 个盒；配合 fillFirstEmptyBoxFromInventory 拆单盒填装。
            ItemStack single = slot.getItem().split(1);
            boolean ok = bot.getInventory().add(single);   // 塞进假人背包
            slot.setChanged();   // 让底层的容器记录变更，剩余盒留在仓库
            if (!ok && !single.isEmpty()) {
                // 背包满塞不进：把这个单盒放回仓库槽（避免丢失）
                ItemStack remaining = slot.getItem();
                if (remaining.isEmpty()) {
                    slot.set(single);
                } else {
                    remaining.grow(1);
                }
            }
        } else {
            // 本来就单个空盒：quickMoveStack 直接搬进背包
            boxSrcMenu.quickMoveStack(bot, idx);
        }
        String afterDesc = shulkerDiagSlot(slot.getItem());
        FLTAdditionMod.LOGGER.info("[FLT] 备货 PACK 取盒诊断：{} 槽{} 前=[{}] 后=[{}]",
                emptyBoxSrcPos, idx, beforeDesc, afterDesc);
        try {
            bot.closeContainer();
        } catch (Throwable ignored) {
        }
        boxSrcMenu = null;
        return false;   // 完成取盒
    }

    /** 诊断用：空槽返回 "(空)"，否则调 shulkerDiag */
    @SuppressWarnings("unused")
    private static String shulkerDiagSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "(空)";
        }
        return shulkerDiag(stack);
    }

    /**
     * 把一个空盒<b>尽量装满同一类物品</b>（每格 64，最多 27 格 = 最多 1728 个同一物品）。
     *
     * <p>装箱策略：优先凑满"一个整数堆"（如 64 或 64×27）的同类物品装一盒，
     * 这样成品盒内容单一、好用。装不满一整盒也能装（只要某类物品总数 ≥1）。
     * 从假人背包逐格抠这些物品写入盒内（{@code ItemContainerContents.fromItems}），
     * 被抠光的槽置空；装完的盒（已带内容）留在假人背包作为成品。
     *
     * @param emptyBox 假人背包里的一个空潜影盒（会被填充内容）
     * @return 装进盒的物品个数（0 = 没装成，理论上不该发生）
     */
    private int fillBoxFromInventory(ItemStack emptyBox) {
        if (emptyBox == null || emptyBox.isEmpty() || !isEmptyShulker(emptyBox)) {
            return 0;
        }
        // ⚠️ 防御：只能给【单个】空盒填内容。若误传堆叠盒(count>1)，整组一起改 CONTAINER = 复制 bug，
        //    直接拒绝（调用方 fillFirstEmptyBoxFromInventory 已保证传 single count==1 或本来就是单盒）。
        if (emptyBox.getCount() != 1) {
            return 0;
        }
        var inv = getFakePlayer().getInventory();
        // 找出背包里所有散装物品（按 物品ID → 各槽累计数量），选一种最多的来装一盒。
        // 装箱优先"同类满 27 格"：找到总数能凑满 64×27 的物品优先；否则选总数最大的物品。
        // 收集形式：Map<Identifier, Integer> 只用于决策，真正取值时逐槽抠。
        java.util.Map<Identifier, Integer> looseTotals = new java.util.LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            looseTotals.merge(id, st.getCount(), Integer::sum);
        }

        // 决策：选"能凑满整盒(1728)"的物品里最好的；否则选总数最大的
        Identifier pickId = null;
        int pickMax = 0;
        for (Map.Entry<Identifier, Integer> en : looseTotals.entrySet()) {
            int total = en.getValue();
            if ((pickId == null) || total > pickMax) {
                pickId = en.getKey();
                pickMax = total;
            }
        }
        if (pickId == null || pickMax <= 0) {
            return 0;   // 没有可装箱的散装
        }

        // 从背包逐格抠 pickId 的物品，尽量凑满 27 格（每格 ≤64）
        Item pickItem = BuiltInRegistries.ITEM.getValue(pickId);
        if (pickItem == null) {
            return 0;
        }
        int want = Math.min(pickMax, 27 * 64);
        List<ItemStack> filled = new ArrayList<>();   // 盒内 27 格
        int got = 0;
        for (int i = 0; i < inv.getContainerSize() && got < want; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || !st.is(pickItem)) {
                continue;
            }
            int take = Math.min(st.getCount(), want - got);
            // 一个盒槽：从该格分出一个 ≤64 的小堆（同类物品分多次也行，但一格只放一种）
            while (take > 0 && filled.size() < 27) {
                int chunk = Math.min(take, 64);
                filled.add(st.copyWithCount(chunk));
                st.shrink(chunk);
                take -= chunk;
                got += chunk;
            }
            if (st.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            // filled 已满或 got 已够 → 结束
        }

        if (filled.isEmpty()) {
            return 0;
        }
        // 写入盒内容
        emptyBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(filled));
        return got;
    }

    // =========================================================
    // 阶段3：DELIVER —— 送回玩家
    // =========================================================

    private boolean tickDeliver() {
        boolean delivered = RestockScheduler.deliverToPlayer(server(), playerId, getFakePlayer());
        String msg;
        if (packedBoxes > 0) {
            msg = "备货完成：已打包 " + packedBoxes + " 盒，共取 " + fetchedTotalItems + " 个材料"
                    + (delivered ? "，假人已送到你面前" : "（玩家离线，货在假人身上）");
        } else {
            msg = "备货完成：共取 " + fetchedTotalItems + " 个材料"
                    + (delivered ? "，假人已送到你面前" : "（玩家离线，货在假人身上）")
                    + "（仓库没有空盒，未装箱）";
        }
        message = msg;
        return false;
    }

    // ============ ============ ============

    /** 统计假人背包（含快捷栏）里某物品的总数 */
    private static int countItemOnBot(ServerPlayer bot, Item item) {
        var inventory = bot.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countItemOnBot(Item item) {
        return countItemOnBot(getFakePlayer(), item);
    }

    private int countItemOnBot(Identifier itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        return item == null ? 0 : countItemOnBot(item);
    }

    @Override
    public void onStop() {
        currentFetcher = null;
        if (getFakePlayer().containerMenu != null) {
            try {
                getFakePlayer().closeContainer();
            } catch (Throwable ignored) {
            }
        }
        if (reporter != null) {
            String reason = message != null ? message
                    : "备货已停止（" + (packedBoxes > 0 ? "装 " + packedBoxes + " 盒" : "未装箱")
                    + "，取 " + fetchedTotalItems + " 个）";
            try {
                reporter.accept(reason);
            } catch (Throwable ignored) {
            }
        }
    }
}