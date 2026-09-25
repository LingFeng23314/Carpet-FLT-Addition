package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
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
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 假人容器取货动作（组合拳二期服务端核心）。
 *
 * <p>把「假人从容器里把指定物品搬进自己背包」拆成多刻执行，避免单刻做太多引发卡顿：
 * <ol>
 *   <li>tick 1：传送到容器上方（靠近才能通过 {@code ChestMenu.stillValid} 的 8 格校验）</li>
 *   <li>tick 2：{@code openMenu(容器)} → 拿到 {@code AbstractContainerMenu}</li>
 *   <li>tick 3+：每刻搬一个槽位（{@code quickMoveStack} 等价 shift 点击，把容器槽移到假人背包）</li>
 * </ol>
 *
 * <p>为什么走 {@code quickMoveStack} 而不是直接改容器：容器槽在菜单里占全局索引 0..size-1，
 * quickMoveStack 会按原版的转移规则把物品移入玩家背包（含堆叠合并、背包满则不移动），
 * 行为与真人 shift 点击完全一致，服务端记录也可被其它插件视为正常玩家操作。
 *
 * <p>取货终点 = 假人身上（中转站），与"假人身上的材料计入投影材料列表"的设计一致。
 */
public class ContainerWithdrawAction extends AbstractFakePlayerAction {
    private final BlockPos containerPos;
    private final Item targetItem;
    /** 期望取出的数量；<=0 表示尽量取（取光容器里该物品） */
    private final int requestAmount;
    /** true = 整盒取：优先把"含目标物品的单一内容潜影盒"整盒搬走（而非按数量散装取） */
    private final boolean boxMode;
    /** 结束时的结果反馈（可为 null） */
    private final Consumer<String> reporter;

    /** 0=待传送；1=待开箱；2=取货中 */
    private int phase = 0;
    private AbstractContainerMenu menu;
    private int containerSize;
    private int withdrawn;
    private String message;

    public ContainerWithdrawAction(EntityPlayerMPFake fakePlayer, BlockPos containerPos, Item targetItem,
                                   int requestAmount, boolean boxMode, Consumer<String> reporter) {
        super(fakePlayer);
        this.containerPos = containerPos.immutable();
        this.targetItem = targetItem;
        this.requestAmount = requestAmount;
        this.boxMode = boxMode;
        this.reporter = reporter;
    }

    /** 旧签名：非整盒取（兼容批量备货等原有调用，其实例是 boxMode=false） */
    public ContainerWithdrawAction(EntityPlayerMPFake fakePlayer, BlockPos containerPos, Item targetItem,
                                   int requestAmount, Consumer<String> reporter) {
        this(fakePlayer, containerPos, targetItem, requestAmount, false, reporter);
    }

    @Override
    public boolean tick() {
        ServerPlayer bot = getFakePlayer();
        ServerLevel level = level();

        switch (phase) {
            case 0 -> {
                // 传送到容器「顶部上方」({getY()+1.0})：落点与建档 spawnPos 一致，
                // 假人站在箱子顶上一格（同 8 格 stillValid 校验内），脚底不嵌入任何方块 ——
                // 不会像旧公式 {getY()+0.5-eyeHeight}（眼落箱心、脚底埋进箱子下一层方块）那样卡箱。
                // 【2026-09-23 修复】旧公式在箱子下方是实体方块时假人被夹在方块缝里，
                // openMenu / quickMoveStack 反复失败 → 表现为"卡在箱子里 + 取 0 个"。
                bot.teleportTo(level,
                        containerPos.getX() + 0.5D,
                        containerPos.getY() + 1.0D,
                        containerPos.getZ() + 0.5D,
                        Set.<Relative>of(), bot.getYRot(), bot.getXRot(), false);
                phase = 1;
                return true;
            }
            case 1 -> {
                BlockEntity blockEntity = level.getBlockEntity(containerPos);
                if (!(blockEntity instanceof Container container) || !(blockEntity instanceof MenuProvider provider)) {
                    message = "目标方块不是可打开的容器";
                    return false;
                }
                containerSize = container.getContainerSize();
                OptionalInt menuId = bot.openMenu(provider);
                if (menuId.isEmpty() || bot.containerMenu == null) {
                    message = "打开容器失败（菜单未创建）";
                    return false;
                }
                menu = bot.containerMenu;
                phase = 2;
                return true;
            }
            case 2 -> {
                int moved = withdrawOneStep(bot);
                if (moved < 0) {
                    return false;   // 已无该物品可取 / 背包满
                }
                if (requestAmount > 0 && withdrawn >= requestAmount) {
                    message = "已取够 " + withdrawn + " 个";
                    return false;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 取出一个槽位的目标物品（贪心选槽，与 LMS 的"刚好够"策略一致）。
     * @return 本次搬出的数量（>=0）；-1 = 容器里已无目标物品或搬不动（背包满）
     */
    private int withdrawOneStep(ServerPlayer bot) {
        if (menu == null) {
            return -1;
        }
        int remaining = requestAmount > 0 ? requestAmount - withdrawn : Integer.MAX_VALUE;
        int slotIndex = selectSlot(remaining);

        if (slotIndex >= 0) {
            // 统计"搬了多少"：boxMode 时搬的是潜影盒本身（quickMoveStack 把盒移入背包），
            // 所以用"盒本身数量"计数；散装则用目标物品计数。
            Item measuredItem = boxMode ? getShulkerItem(menu.slots.get(slotIndex).getItem()) : targetItem;
            int before = countInInventory(bot, measuredItem);
            menu.quickMoveStack(bot, slotIndex);
            int after = countInInventory(bot, measuredItem);
            int moved = after - before;
            if (moved > 0) {
                withdrawn += moved;
                return moved;
            }
            // 物品没动 → 假人背包塞不下
            message = "假人背包空间不足，已取 " + withdrawn + " 个";
            return -1;
        }

        // 没有散装槽 → 试试从"单一内容潜影盒"里<b>拆盒取散装</b>。
        //
        // [VERSION] 2026-09-24 新增（用户报障修复："假人没拿到物品就下线了"）：
        // 仓库里整盒存放很常见，而库存统计（{@code StockScanner.scanAll}）的口径是
        // "散装 + 盒内展开" —— 客户端因此会显示"有货"。以前散装取货只认散装槽
        // （{@link #selectSlot} 里 {@code !inSlot.is(targetItem)} 就跳过），于是出现
        // "显示有货 → 能选中 → 派单预检通过 → 实际取 0 件 → 假人空手送到身边就下线"，
        // 而且全程没有日志/提示（原因见 {@link #onStop()} 的注释）。
        //
        // boxMode=true 时 {@link #selectSlot} 已优先返回盒槽（整盒搬走），走不到这里，
        // 所以本分支只在散装模式生效。
        int boxSlot = selectUnpackableBoxSlot();
        if (boxSlot >= 0) {
            int got = withdrawInsideBox(boxSlot, remaining);
            if (got > 0) {
                withdrawn += got;
                return got;
            }
        }

        message = "容器内已没有该物品" + (withdrawn > 0 ? "，共取 " + withdrawn + " 个" : "");
        return -1;
    }

    /** 潜影盒槽里实际存放的物品（即该盒的物品类型；用于整盒取时统计搬移量）。散装 return null */
    private static Item getShulkerItem(ItemStack slotStack) {
        if (slotStack.isEmpty()) {
            return null;
        }
        return slotStack.getItem();
    }

    /**
     * 贪心选槽：优先挑"一个槽就能满足剩余需求"里数量最小的那个（避免为几个物品搬走一整槽）；
     * 没有这样的槽时退而选数量最大的槽。参考 LMS {@code GetItemSlotSelector} 的思路并独立实现
     * （LMS 用"按数量分组 + 二分查找"是为跨箱多槽的大列表优化；这里是单容器 ≤54 槽，线性扫描等价且更简单）。
     *
     * <p><b>boxMode 整盒取</b>：优先选"槽里放的是<b>潜影盒本身</b>（不是散装目标物品）且
     * 盒内全是目标物品"的槽，把整盒搬走；找不到盒装槽才退回散装槽兜底。
     *
     * @param remaining 还需取多少（<=0 或 MAX_VALUE 表示尽量取）
     * @return 选中的菜单槽索引；-1 表示没有目标物品
     */
    private int selectSlot(int remaining) {
        int smallestSufficient = -1;   // 数量 >= remaining 的槽中，数量最小的
        int smallestSufficientCount = Integer.MAX_VALUE;
        int largestSlot = -1;          // 数量最大的槽（兜底）
        int largestCount = 0;
        // boxMode 专属：盒装目标物品的槽（潜影盒本身 + 盒内单一目标物品）
        int boxSlot = -1;
        int boxCount = 0;

        for (int i = 0; i < containerSize; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack inSlot = slot.getItem();
            if (inSlot.isEmpty() || !inSlot.is(targetItem)) {
                continue;
            }

            if (boxMode && isSingleShulkerOfTarget(inSlot)) {
                // 整盒取：优先这片"单一内容潜影盒"槽；多个盒时取盒数最多的
                if (inSlot.getCount() > boxCount) {
                    boxSlot = i;
                    boxCount = inSlot.getCount();
                }
                continue;   // boxMode 下盒装槽不走散装逻辑
            }

            int count = inSlot.getCount();
            if (count >= remaining && count < smallestSufficientCount) {
                smallestSufficient = i;
                smallestSufficientCount = count;
            }
            if (count > largestCount) {
                largestSlot = i;
                largestCount = count;
            }
        }
        // 整盒取优先返回盒装槽；找不到盒装槽再退回散装兜底
        if (boxMode && boxSlot >= 0) {
            return boxSlot;
        }
        return smallestSufficient >= 0 ? smallestSufficient : largestSlot;
    }

    /**
     * 判断槽里是否"单一内容潜影盒"且盒内全部是目标物品（供 boxMode 整盒取用）。
     *
     * <p>判断逻辑与 {@code StockScanner} / {@code StackCounter} 一致：
     * 槽 stack 本身就是潜影盒（{@code ItemTags.SHULKER_BOXES}），且 {@code StackCounter.count}
     * 能把它展开成目标物品（展开即表示盒内非混装）。空盒 / 混装盒都不算（整盒取也没意义）。
     */
    private boolean isSingleShulkerOfTarget(ItemStack slotStack) {
        if (slotStack.isEmpty() || !slotStack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
            return false;
        }
        com.flt.carpetaddition.storage.StackCounter.Count count =
                com.flt.carpetaddition.storage.StackCounter.count(slotStack);
        // 空盒：count 返回"盒本身"（itemId=shulker 非目标）；目标物品的单一内容盒 → itemId 匹配
        return count != null
                && count.itemId().equals(BuiltInRegistries.ITEM.getKey(targetItem));
    }

    /**
     * 找"单一内容潜影盒"槽（盒内全是指定物品），用于<b>散装模式下的拆盒取</b>。
     *
     * <p>[VERSION] 2026-09-24 新增。多个可拆的盒时取"盒里目标物品最多"的那个
     * （一次能拿最多，减少来回）。
     *
     * @return 菜单槽索引；-1 = 没有可拆的盒
     */
    private int selectUnpackableBoxSlot() {
        int best = -1;
        int bestInside = 0;

        for (int i = 0; i < containerSize; i++) {
            ItemStack inSlot = menu.slots.get(i).getItem();
            if (!isSingleShulkerOfTarget(inSlot)) {
                continue;
            }
            int inside = com.flt.carpetaddition.storage.StackCounter
                    .countTargetInsideBox(inSlot, BuiltInRegistries.ITEM.getKey(targetItem));
            if (inside > bestInside) {
                best = i;
                bestInside = inside;
            }
        }
        return best;
    }

    /**
     * 从"单一内容潜影盒"里取出目标物品（拆盒取散装），剩余内容写回原槽（取光则留空盒）。
     *
     * <p>实现与 {@code MultiContainerWithdrawAction.withdrawInsideBox} 一致 ——
     * 那套已经在用、踩平了"潜影盒组件读写"的坑，这里做单箱版的等价实现，不另发明一套。
     *
     * <p><b>注意一个容易踩的坑</b>：{@code Inventory.add(ItemStack)} 会<b>原地把入参栈改成
     * "塞不下的剩余"</b>，所以放完后再读 {@code toAdd.getCount()} 就是没塞下的数量。
     *
     * @param slotIndex 盒所在菜单槽
     * @param want      期望取出数量（&le;0 或极大 == 尽量取）
     * @return 实际取出数量；&le;0 = 没取到（盒里没有目标物品 / 假人背包塞不下）
     */
    private int withdrawInsideBox(int slotIndex, int want) {
        ItemStack boxInSlot = menu.slots.get(slotIndex).getItem();
        if (boxInSlot.isEmpty()) {
            return -1;
        }

        final Identifier targetId = BuiltInRegistries.ITEM.getKey(targetItem);
        final int inside = com.flt.carpetaddition.storage.StackCounter
                .countTargetInsideBox(boxInSlot, targetId);
        if (inside <= 0) {
            return -1;
        }

        final int take = Math.max(0, Math.min(inside, want));

        // 目标物品塞进假人背包（add 会把塞不下的剩余写回 toAdd）
        ItemStack toAdd = new ItemStack(targetItem, take);
        getFakePlayer().getInventory().add(toAdd);
        final int accepted = take - toAdd.getCount();
        if (accepted <= 0) {
            return -1;   // 背包塞不下 → 由上层决定继续还是收尾
        }

        // 从盒内容里扣掉 accepted 个，剩余写回原槽
        final ItemContainerContents contents = boxInSlot.get(DataComponents.CONTAINER);
        int toRemove = accepted;
        List<ItemStack> newInners = new ArrayList<>();

        if (contents != null) {
            for (ItemStack inner : contents.nonEmptyItemCopyStream().toList()) {
                if (toRemove > 0 && BuiltInRegistries.ITEM.getKey(inner.getItem()).equals(targetId)) {
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

        // 取光则写回"空盒"（fromItems(空表) 即空内容盒），盒物品本身仍留在原槽
        boxInSlot.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(newInners));
        menu.slots.get(slotIndex).set(boxInSlot);

        FLTAdditionMod.LOGGER.info("[FLT][取货][拆盒] {} 从仓库 {} 槽 {} 的盒里取出 {} 个（盒内剩 {} 种物品）",
                targetItem, containerPos, slotIndex, accepted, newInners.size());
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

    /** 关闭容器菜单，把假人的当前菜单切回物品栏（避免箱子一直显示"被打开"） */
    private void closeMenu() {
        if (menu != null) {
            try {
                getFakePlayer().closeContainer();
            } catch (Throwable ignored) {
                // 假人可能已失效，忽略
            }
            menu = null;
        }
    }

    @Override
    public void onStop() {
        closeMenu();
        if (reporter != null) {
            String reason = message != null ? message
                    : (withdrawn > 0 ? "取出 " + withdrawn + " 个" : "未取到物品");

            // [VERSION] 2026-09-24 新增：无论成败都打一行日志。
            // 以前本类<b>没有任何日志</b>，而取货失败的原因只走 reporter ——
            // 单物品取货那条路径的 reporter（RestockScheduler.dispatch）当时又完全没用 message 参数，
            // 结果失败时"服务端日志零痕迹 + 玩家零提示"，表现为"假人白跑一趟、毫无线索"。
            // 这条日志 + reporter 那边的播报一起补上，保证任何失败都查得到原因。
            FLTAdditionMod.LOGGER.info("[FLT][取货] {} 于 {} 结束：{}（累计 {} 个）",
                    targetItem, containerPos, reason, withdrawn);

            try {
                reporter.accept(reason);
            } catch (Throwable ignored) {
                // 反馈目标（如命令源玩家）可能已失效；onStop 由调度器直接调用，不能让异常冒泡中断 tick
            }
        }
    }
}