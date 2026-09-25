package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import com.flt.carpetaddition.storage.StackCounter;
import com.flt.carpetaddition.storage.StockSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 假人「跨多箱取货」动作（组合拳二期·多箱子支持，参考 Carpet-LMS-Addition 的 GetItem 思路）。
 *
 * <p>与单体 {@link ContainerWithdrawAction} 的区别：单体只在一个容器里贪心凑数量；
 * 本类在<b>多个已登记容器</b>里凑够 {@code requestAmount} —— 用户一次取大量（如 1728 个）
 * 可能分布在好几个箱子里，LMS 正是这么做的。
 *
 * <p>取货流程（多刻，不阻塞主线程）：
 * <ol>
 *   <li>构造时（主线程）扫描全部已登记容器，收集含目标物品的槽，
 *       贪心选槽凑够 requestAmount，得到「待取槽序列」：每项 = (箱子坐标, 槽索引, 可取数量, 是否盒装)</li>
 *   <li>按序列逐槽执行：同一个箱子连续取时保持菜单开启；换箱时传送 → 开箱 → 取槽</li>
 *   <li>直到凑够 requestAmount 或所有选中的槽取完</li>
 * </ol>
 *
 * <p>boxMode 整盒取：只选「单一内容潜影盒」槽，每槽取走的是潜影盒本身，count 按盒数计。
 * 非整盒取：只选槽内物品就是目标物品的槽，不取潜影盒（避免把盒当散装搬走）。
 */
public class MultiContainerWithdrawAction extends AbstractFakePlayerAction {
    /** 待取槽序列（已排序并裁剪到 requestAmount） */
    private final List<TargetSlot> targets;

    private final Item targetItem;
    /** 目标物品 ID（报告用） */
    private final String itemIdText;
    /** 期望取出的数量；<=0 表示尽量取 */
    private final int requestAmount;
    /** true = 整盒取 */
    private final boolean boxMode;
    /** 结束反馈（可为 null） */
    private final Consumer<String> reporter;

    /** 当前处理到第几个槽 */
    private int currentIndex = 0;
    /** 已实际搬进背包的数量（散装 = 物品数，盒装 = 盒数） */
    private int withdrawn;
    private String message;

    /** 已实际搬进背包的数量（供外层动作在子动作结束后读取汇总） */
    public int withdrawnCount() {
        return withdrawn;
    }

    /** 当前进度快照（供外层看门狗判断是否"在推进"；含 槽索引/已取数/当前箱坐标） */
    public String progressSnapshot() {
        return "idx=" + currentIndex + "/" + targets.size() + ",withdrawn=" + withdrawn
                + ",open=" + openPos;
    }

    /** 当前打开的菜单（同箱连续取时复用） */
    private AbstractContainerMenu menu;
    /** 当前菜单对应的容器坐标 */
    private BlockPos openPos;
    /** 当前容器的槽数（防越界） */
    private int containerSize;

    public MultiContainerWithdrawAction(EntityPlayerMPFake fakePlayer, List<TargetSlot> targets,
                                        Item targetItem, String itemIdText, int requestAmount,
                                        boolean boxMode, Consumer<String> reporter) {
        super(fakePlayer);
        this.targets = targets;
        this.targetItem = targetItem;
        this.itemIdText = itemIdText;
        this.requestAmount = requestAmount;
        this.boxMode = boxMode;
        this.reporter = reporter;
    }

    /**
     * 待取槽记录（主线程生成后不可变）。
     *
     * @param pos        容器坐标
     * @param slotIndex  容器内的槽索引
     * @param count      该槽的可取数量（散装=物品数；盒装=盒数）
     * @param isBoxSlot  是否是整盒取的潜影盒槽
     */
    public record TargetSlot(BlockPos pos, int slotIndex, int count, boolean isBoxSlot) {
    }

    /**
     * 跨全部已登记容器收集含目标物品的槽，按 requestAmount 贪心选槽。
     *
     * @param server    服务器
     * @param itemId    目标物品 ID
     * @param targetItem 目标物品实例
     * @param boxMode   true = 整盒取
     * @param needCount 需要数量（盒装 = 盒数）
     * @return 选中的槽序列；凑不够也返回已选中的
     */
    public static List<TargetSlot> collectTargets(
            MinecraftServer server,
            net.minecraft.resources.Identifier itemId,
            Item targetItem,
            boolean boxMode,
            int needCount) {
        List<Candidate> candidates = new ArrayList<>();
        java.util.Set<BlockPos> seenCanonical = new java.util.HashSet<>();

        for (java.util.Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            for (BlockPos pos : entry.getValue()) {
                // 双箱：归一到规范坐标、只取完整合并容器统计一次（两个 half 不再分别扫，避免或缺一半）
                BlockPos canonical = com.flt.carpetaddition.storage.ContainerGroup.canonical(level, pos);
                if (!seenCanonical.add(canonical)) {
                    continue;   // 同组双箱已经扫过
                }
                net.minecraft.world.Container container =
                        com.flt.carpetaddition.storage.ContainerGroup.fullContainerFor(level, canonical);
                if (container == null) {
                    continue;
                }
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    Candidate c = candidateOf(stack, canonical, i, itemId, targetItem, boxMode);
                    if (c != null) {
                        candidates.add(c);
                    }
                }
            }
        }

        // 排序：盒装槽优先（仅 boxMode）；再按 Y 从高到低（减少垂直移动）；再按数量从多到少
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.isBoxSlot ? 0 : 1)
                .thenComparingInt((Candidate c) -> -c.pos.getY())
                .thenComparingInt((Candidate c) -> -c.count));

        // 贪心凑够 needCount
        List<TargetSlot> result = new ArrayList<>();
        int collected = 0;
        for (Candidate c : candidates) {
            if (needCount > 0 && collected >= needCount) {
                break;
            }
            int take = c.count;
            if (needCount > 0) {
                take = Math.min(c.count, needCount - collected);
            }
            result.add(new TargetSlot(c.pos, c.slotIndex, take, c.isBoxSlot));
            collected += take;
        }
        return result;
    }

    /** 单个槽是否可选，可选则返回候选记录 */
    private static Candidate candidateOf(ItemStack stack, BlockPos pos, int slotIndex,
                                         net.minecraft.resources.Identifier itemId,
                                         Item targetItem, boolean boxMode) {
        if (boxMode) {
            if (!isSingleShulkerOfTarget(stack, targetItem)) {
                return null;
            }
            // 盒数 = 槽内潜影盒堆叠数（通常是 1，某些数据包/模组可能可堆叠）
            return new Candidate(pos, slotIndex, stack.getCount(), true);
        }

        // 非盒装：
        //  1) 槽内物品直接就是目标物品 → 按散装取（可取的数目 = 槽内数量）
        if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
            return new Candidate(pos, slotIndex, stack.getCount(), false);
        }
        //  2) 槽内是潜影盒且盒内含有目标物品（纯盒或杂盒）→ 标记为盒装源，
        //     取货时把盒内目标物品拆出来散装进假人背包（不强求盒内全是一种）。
        //     可取的数目 = 盒内该目标物品的总数。
        if (stack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
            int inside = com.flt.carpetaddition.storage.StackCounter
                    .countTargetInsideBox(stack, itemId);
            if (inside > 0) {
                return new Candidate(pos, slotIndex, inside, true);
            }
        }
        return null;
    }

    /** 槽是否「单一内容潜影盒且盒内全部是目标物品」 */
    private static boolean isSingleShulkerOfTarget(ItemStack slotStack, Item targetItem) {
        if (slotStack.isEmpty() || !slotStack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
            return false;
        }
        StackCounter.Count count = StackCounter.count(slotStack);
        return count != null
                && count.itemId().equals(BuiltInRegistries.ITEM.getKey(targetItem));
    }

    private static final class Candidate {
        final BlockPos pos;
        final int slotIndex;
        final int count;
        final boolean isBoxSlot;

        Candidate(BlockPos pos, int slotIndex, int count, boolean isBoxSlot) {
            this.pos = pos;
            this.slotIndex = slotIndex;
            this.count = count;
            this.isBoxSlot = isBoxSlot;
        }
    }

    @Override
    public boolean tick() {
        ServerPlayer bot = getFakePlayer();
        ServerLevel level = level();

        if (currentIndex >= targets.size()) {
            message = message != null ? message
                    : (withdrawn > 0 ? "跨箱取完，共 " + withdrawn + " 个（" + itemIdText + "）"
                    : "未取到物品（" + itemIdText + "）");
            return false;
        }

        TargetSlot target = targets.get(currentIndex);

        // —— 需要换箱（与当前定位/打开的箱子不同）：本 tick 只传送定位。
        // 注意不能写成 `menu == null || ...`：首次进入时 menu 本来就是 null，
        // 那样条件恒成立 → 每 tick 都重走"传送"、永远进不到下面的开箱分支（实测卡死 600 刻）。
        // 换箱判断只看目标箱坐标是否与当前定位箱(openPos)不同；menu==null 时统一走开箱逻辑。
        // 为什么传送和开箱必须分两个 tick：假人 openMenu 要通过容器菜单的 stillValid
        // 8 格距离校验，而 teleportTo 后假人位置要下一 tick 才生效。若同 tick 传送完立刻
        // openMenu，校验仍按旧位置算 → 大量失败（表现为"只拿到第一个箱子"）。
        if (!target.pos.equals(openPos)) {
            closeMenu();
            openPos = target.pos;
            FLTAdditionMod.LOGGER.info("[FLT][取货] {} 换箱传送至 {}", itemIdText, target.pos);

            bot.teleportTo(level,
                    target.pos().getX() + 0.5D,
                    target.pos().getY() + 1.0D,   // 站箱顶上方一格，避免脚底嵌进下一层方块而卡箱（见 ContainerWithdrawAction 注释）
                    target.pos().getZ() + 0.5D,
                    Set.<Relative>of(), bot.getYRot(), bot.getXRot(), false);
            return true;   // 下一 tick 已到位，再开箱
        }

        // —— 已定位到目标箱但还没开箱：本 tick 开箱。
        if (menu == null) {
            BlockPos boxPos = target.pos();
            // ⚠️ 用方块状态的标准菜单入口（而非裸 blockEntity）：双箱会被合并成 54 格菜单，
            //    与 collectTargets 用 fullContainerFor 记录的槽号一致；否则打开 27 格菜单、
            //    槽 27~52 全越界 → 取 0 个（2026-09-23 双箱 bug 实证）。
            MenuProvider provider =
                    com.flt.carpetaddition.storage.ContainerGroup.menuProviderFor(level, boxPos);
            if (provider == null) {
                message = "目标方块不是可打开容器（已跳过）：" + boxPos;
                FLTAdditionMod.LOGGER.info("[FLT][取货] {} 位置 {} 不可开箱，跳过", itemIdText, boxPos);
                currentIndex++;
                return true;
            }
            // 容器格数用与 collectTargets 完全同源（fullContainerFor），保证槽号对齐。
            Container full =
                    com.flt.carpetaddition.storage.ContainerGroup.fullContainerFor(level, boxPos);
            containerSize = full != null ? full.getContainerSize() : 0;
            OptionalInt menuId = bot.openMenu(provider);
            if (menuId.isEmpty() || bot.containerMenu == null) {
                message = "打开容器失败（已跳过）：" + boxPos;
                FLTAdditionMod.LOGGER.info("[FLT][取货] {} 打开容器 {} 失败（菜单未创建），跳过", itemIdText, boxPos);
                currentIndex++;
                return true;
            }
            menu = bot.containerMenu;
            FLTAdditionMod.LOGGER.info("[FLT][取货] {} 已打开 {} 的容器（{} 槽）", itemIdText, boxPos, containerSize);
            return true;   // 下一 tick 再取槽
        }

        // —— 开好箱：取当前槽
        if (target.slotIndex() < 0 || target.slotIndex() >= containerSize
                || target.slotIndex() >= menu.slots.size()) {
            message = "槽位失效（已跳过）：" + target.pos() + " #" + target.slotIndex();
            FLTAdditionMod.LOGGER.info("[FLT][取货] {} 槽 {} 越界(容器{})，跳过", itemIdText, target.slotIndex(), containerSize);
            currentIndex++;
            return true;
        }

        int moved = withdrawOneStep(bot, target);
        if (moved > 0) {
            withdrawn += moved;
            FLTAdditionMod.LOGGER.info("[FLT][取货] {} 槽 {} 取 {} 个（累计 {}）", itemIdText,
                    target.slotIndex(), moved, withdrawn);
        } else if (moved < 0 && message == null) {
            message = "背包空间不足，已取 " + withdrawn + " 个";
        }

        currentIndex++;

        if (requestAmount > 0 && withdrawn >= requestAmount) {
            message = "已取够 " + withdrawn + " 个（" + itemIdText + "）";
            closeMenu();
            return false;
        }
        return true;
    }

    /**
     * 搬走当前槽的目标物品。
     * @return 实际搬进背包的数量；-1 = 该槽已无可取 / 背包满搬不动
     */
    private int withdrawOneStep(ServerPlayer bot, TargetSlot target) {
        Slot slot = menu.slots.get(target.slotIndex());
        if (slot == null || !slot.hasItem()) {
            return -1;
        }
        ItemStack inSlot = slot.getItem();

        // boxMode 槽里必须是潜影盒；非 boxMode 槽里必须是目标物品
        if (boxMode) {
            if (!isSingleShulkerOfTarget(inSlot, targetItem)) {
                return -1;
            }
        } else if (!inSlot.is(targetItem)) {
            // 非 boxMode：若槽内是潜影盒 → 走「拆盒取物」：把盒内目标物品抠出来散装进背包。
            // （纯盒 / 杂盒都支持；盒可能在收集阶段因 countTargetInsideBox>0 被判为盒装源）
            if (inSlot.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
                return withdrawInsideBox(bot, target, inSlot.copy());
            }
            return -1;
        }

        Item measuredItem = boxMode ? inSlot.getItem() : targetItem;
        int before = countInInventory(bot, measuredItem);
        menu.quickMoveStack(bot, target.slotIndex());
        int after = countInInventory(bot, measuredItem);
        int moved = after - before;
        if (moved > 0) {
            return moved;
        }
        return -1;
    }

    /**
     * 拆盒取物：把一个容器槽里的潜影盒打开，把盒内<b>目标物品</b>全部抠出来（散装）塞进假人背包，
     * 盒（含剩余其它物品 / 可能为空盒）放回仓库原槽。
     *
     * <p>为什么不用 {@code quickMoveStack} 整盒搬走：备货流程要的是<b>散装材料</b>——假人取货后
     * 还要把散装重新装箱（PACK）。整盒搬会导致假人背包多一个"带内容的盒"，既占格又不是散装，
     * 且 `countItemOnBot` 只认散装会把盒内物品算成"未取到"。所以这里<b>直接在菜单槽数据层拆盒</b>，
     * 目标物品散装进背包，剩余（含空盒）留在仓库槽作后续来源。
     *
     * <p>数据层操作通过菜单槽 ItemStack + {@code set(DataComponents.CONTAINER, ...)} 写回，
     * 服务的容器（BlockEntity chest）会自动同步，无需真的打开盒菜单。空盒重写回原槽
     * （不丢弃，供 PACK 装箱复用；也可防止盒凭空消失）。
     *
     * @return 取出的目标物品数量；-1 = 没取到（盒内没目标 / 背包加不进）
     */
    private int withdrawInsideBox(ServerPlayer bot, TargetSlot target, ItemStack boxInSlot) {
        // 取货上限：优先按本槽已被裁剪的 target.count()（collectTargets 已按需求裁剪），
        // 避免把一个盒里的目标物品全掏空导致超取（尤其 RestockScheduler 精确取货场景）。
        // 但 target.count() 理论上 = 盒内目标总数（裁剪 >= 0 的 min）。实际以盒内现有为准。
        int inside = com.flt.carpetaddition.storage.StackCounter
                .countTargetInsideBox(boxInSlot,
                        BuiltInRegistries.ITEM.getKey(targetItem));
        if (inside <= 0) {
            return -1;
        }
        int want = Math.max(0, Math.min(inside, target.count()));

        // 目标物品：塞进假人背包。注意本 MC 版本 Inventory.add(stack) 返回 boolean（是否全放完），
        // 且【原地把参数栈改成"塞不下的剩余"】——即 add 内部 shrink 掉放下的部分、剩余写回入参。
        // 所以放完后再读 toAdd.getCount() 即得"没塞下的数量"，accepted = want - 剩余。
        ItemStack toAdd = new ItemStack(targetItem, want);
        bot.getInventory().add(toAdd);                      // add 后 toAdd = 塞不下的剩余
        int accepted = want - toAdd.getCount();             // 实际塞进背包的数量
        if (accepted <= 0) {
            return -1;   // 背包塞不下一个 → 稍后重试其它槽也一样的 → 返回 -1（状态机会继续）
        }

        // 从盒子里扣掉 accepted 个目标物品，重写盒内容；剩余盒写回仓库原槽。
        net.minecraft.world.item.component.ItemContainerContents contents =
                boxInSlot.get(net.minecraft.core.component.DataComponents.CONTAINER);
        int toRemove = accepted;
        java.util.List<ItemStack> newInners = new java.util.ArrayList<>();
        if (contents != null) {
            for (ItemStack inner : contents.nonEmptyItemCopyStream().toList()) {
                if (toRemove > 0
                        && BuiltInRegistries.ITEM.getKey(inner.getItem())
                                .equals(BuiltInRegistries.ITEM.getKey(targetItem))) {
                    int cut = Math.min(toRemove, inner.getCount());
                    int remain = inner.getCount() - cut;
                    toRemove -= cut;
                    if (remain > 0) {
                        newInners.add(inner.copyWithCount(remain));
                    }
                    continue;
                }
                newInners.add(inner);
            }
        }
        // 处理后的盒内容写回菜单槽（剩余内容 / 目标取光则为空盒存原槽）
        if (newInners.isEmpty()) {
            // 目标物品恰好取光且盒里也没其它东西 → 写回"空盒"
            boxInSlot.set(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of()));
        } else {
            boxInSlot.set(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(newInners));
        }
        // 让菜单 / 底层容器感知到盒子内容变化（setItem 会标记 dirty + 同步）
        menu.slots.get(target.slotIndex()).set(boxInSlot);
        // 原盒可能已空内容，但盒本身(ItemStack)不变 → 原槽仍是这个盒（空盒/半盒）

        FLTAdditionMod.LOGGER.info("[FLT][取货][拆盒] {} 从仓库 {} 槽 {} 的盒里取出 {} 个（盒内剩 {} 种物品）",
                itemIdText, target.pos(), target.slotIndex(), accepted, newInners.size());
        return accepted;
    }

    /** 统计假人背包（含快捷栏）里某物品的总数 */
    private static int countInInventory(ServerPlayer bot, Item item) {
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

    /** 关闭当前菜单 */
    private void closeMenu() {
        if (menu != null) {
            try {
                getFakePlayer().closeContainer();
            } catch (Throwable ignored) {
            }
            menu = null;
            openPos = null;
        }
    }

    @Override
    public void onStop() {
        closeMenu();
        if (reporter != null) {
            String reason = message != null ? message
                    : (withdrawn > 0 ? "取出 " + withdrawn + " 个" : "未取到物品");
            try {
                reporter.accept(reason);
            } catch (Throwable ignored) {
            }
        }
    }
}
