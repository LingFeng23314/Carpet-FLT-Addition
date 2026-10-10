package com.flt.carpetaddition.fakeplayer;

import com.flt.carpetaddition.storage.StackCounter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * 潜影盒工具集：「盒内取物」相关逻辑的<b>唯一实现</b>。
 *
 * <p>本类解决的问题（2026-09-24 重构）：过去 {@code ContainerWithdrawAction}（单容器取货）与
 * {@code MultiContainerWithdrawAction}（跨容器取货）各自抄了一份
 * 「判断单一内容盒」+「拆盒把目标物品抠成散装」的实现，后者的注释还自认
 * "实现与 MultiContainerWithdrawAction.withdrawInsideBox 一致" —— 两份代码必须同步修改，
 * 否则行为会悄悄漂移。本类把它们收敛到一处。
 *
 * <p>⚠️ <b>本类不能合并进 {@link FakePlayerUtils}</b>：那是 26.3 的版本专属适配文件
 * （{@code swing} 签名差异），三版同步脚本 {@code sync_26.py} 的 EXCLUDE 表会跳过它 →
 * 公共工具放那里会漏同步。本类不在排除表，26.1.2 → 26.2 / 26.3 正常镜像。
 */
public final class ShulkerBoxUtil {
    private ShulkerBoxUtil() {
    }

    /**
     * 该槽是否「单一内容潜影盒，且盒内<b>全部</b>是目标物品」（供整盒取用 / 拆盒取用判断）。
     *
     * <p>判断口径与 {@code StockScanner} / {@link StackCounter} 保持一致：
     * 槽 stack 本身就是潜影盒（{@link ItemTags#SHULKER_BOXES}），且 {@link StackCounter#count}
     * 能把它展开成目标物品（"能展开"即表示盒内非混装）。空盒 / 混装盒都不算。
     *
     * @param slotStack  待判断的槽内物品堆
     * @param targetItem 目标物品
     * @return true = 是"装着目标物品的单一内容盒"
     */
    public static boolean isSingleShulkerOfTarget(ItemStack slotStack, Item targetItem) {
        if (slotStack == null || slotStack.isEmpty() || !slotStack.is(ItemTags.SHULKER_BOXES)) {
            return false;
        }
        StackCounter.Count count = StackCounter.count(slotStack);
        // 空盒：count 返回"盒本身"（itemId = shulker，≠ 目标）；装目标物品的单一内容盒 → itemId 匹配
        return count != null
                && count.itemId().equals(BuiltInRegistries.ITEM.getKey(targetItem));
    }

    /**
     * 该物品堆是否为「空潜影盒」（盒内没有任何内容）。
     *
     * <p>口径与 {@link #isSingleShulkerOfTarget} 同源：{@link StackCounter#count} 把"空盒"
     * 展开成<b>盒本身</b>（itemId = 该潜影盒自己的 ID），所以"展开结果等于它自己的物品 ID"
     * 即空盒。
     *
     * <p>本类是潜影盒判定的<b>唯一实现</b>（见类注释）：原先 {@code BulkFetchRepackAction}
     * 自带一份、{@code RestockScheduler} 则根本没有 —— 后者因此把"玩家已取走货、只剩空盒"
     * 误判成"背包还没取空"，分批续备永远不触发（2026-09-29 自查修复，收敛到此处共用）。
     *
     * @param stack 待判断的物品堆
     * @return true = 是空潜影盒
     */
    public static boolean isEmptyShulker(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(ItemTags.SHULKER_BOXES)) {
            return false;
        }
        StackCounter.Count count = StackCounter.count(stack);
        if (count == null) {
            return false;
        }
        return count.itemId().equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** 拆盒取物结果 */
    public record TakeOut(int moved, int remainingKinds) {
        /** 没取到（盒内无目标物品 / 假人背包塞不下） */
        public static final TakeOut NONE = new TakeOut(-1, 0);

        public boolean accepted() {
            return this.moved > 0;
        }
    }

    /**
     * 槽内物品能否当作 {@code targetItem} 的「可直接搬走」来源（散装源 / 目标物品本身）。
     *
     * <p>[VERSION] 2026-09-30 新增（用户报障：「投影要砂岩，它却给我一盒纯沙子」）：
     * <b>目标物品本身就是潜影盒（当建材用）时，只接受盒内为空的盒。</b>
     *
     * <p><b>为什么</b>：取货的候选判定原先只比<b>物品 ID</b>（{@code inSlot.is(targetItem)}），
     * 而"装了东西的潜影盒"的物品 ID 与"空潜影盒"完全相同 —— 于是投影要"潜影盒"时，
     * 仓库里任何一只**装着东西**的盒都会被当成"目标物品"整盒搬走（内容物一起送来）。
     * 日志实证（2026-09-30 20:29）：{@code minecraft:shulker_box 需求 1 → 槽 28 取 1 个}，
     * 玩家因此收到一盒纯沙子 —— 他要的是砂岩，沙子是那只盒里的内容物。
     *
     * <p>装了东西的盒<b>不是取不到</b>：调用方随后会走"盒装源 → 拆盒取物"分支
     * （{@code StackCounter.countTargetInsideBox} 能识别盒内是否真有目标物品）。
     *
     * @param slotStack  槽内物品堆
     * @param targetItem 本次要取的目标物品
     * @return true = 可以直接搬（普通物品 / 空潜影盒 / 盒内没有内容物）
     */
    public static boolean isDirectSourceFor(ItemStack slotStack, Item targetItem) {
        if (slotStack == null || slotStack.isEmpty() || !slotStack.is(targetItem)) {
            return false;
        }
        if (!slotStack.is(ItemTags.SHULKER_BOXES)) {
            return true;   // 普通物品：照旧按散装取
        }
        return isEmptyShulker(slotStack);   // 潜影盒当建材：必须是空盒（见上文"为什么"）
    }

    /**
     * 从潜影盒里抠出 {@code targetItem} 共 {@code want} 个，以<b>散装</b>塞进假人背包，
     * 并<b>原地重写传入 {@code boxInSlot} 的 {@code CONTAINER} 组件</b>（扣掉已取走的部分，
     * 取光则为"空内容盒"）。盒子物品本身留在原处，不丢弃。
     *
     * <p>调用方负责把 {@code boxInSlot} 写回菜单槽（{@code menu.slots.get(i).set(boxInSlot)}，
     * 触发 {@code setChanged} + 同步）并打自己的日志。传入的 {@code boxInSlot} 既可以是
     * 槽内引用，也可以是其 {@code copy()} —— 两种调用方式等价。
     *
     * <p><b>为什么不用 {@code quickMoveStack} 整盒搬走</b>：备货流程要的是<b>散装材料</b>
     * （假人取货后还要 PACK 重新装箱）。整盒搬会让背包多出一个"带内容的盒"——既占格又不是散装，
     * 而且 {@code InventoryCounter.countLooseItem} 只认散装，会把盒内物品算成"没取到"。
     *
     * <p><b>⚠️ 一个容易踩的坑</b>：{@code Inventory.add(ItemStack)} 会<b>原地把入参栈改成
     * "塞不下的剩余"</b>（返回的 boolean 只表示是否全放下），所以放完后再读 {@code toAdd.getCount()}
     * 就是"没塞下的数量"，{@code accepted = take - 剩余}。
     *
     * @param bot        取货的假人
     * @param boxInSlot  潜影盒 ItemStack（<b>会被原地修改</b>其 CONTAINER 组件）
     * @param targetItem 要抠出来的物品
     * @param want       期望取出数量（&le;0 表示尽量取）
     * @return 取出结果；{@link TakeOut#NONE} = 没取到
     */
    public static TakeOut takeOutInside(ServerPlayer bot, ItemStack boxInSlot, Item targetItem, int want) {
        if (boxInSlot == null || boxInSlot.isEmpty()) {
            return TakeOut.NONE;
        }
        final Identifier targetId = BuiltInRegistries.ITEM.getKey(targetItem);
        final int inside = StackCounter.countTargetInsideBox(boxInSlot, targetId);
        if (inside <= 0) {
            return TakeOut.NONE;
        }
        final int take = Math.max(0, Math.min(inside, want));

        // 目标物品塞进假人背包（add 会把塞不下的剩余写回 toAdd）
        ItemStack toAdd = new ItemStack(targetItem, take);
        bot.getInventory().add(toAdd);
        final int accepted = take - toAdd.getCount();
        if (accepted <= 0) {
            return TakeOut.NONE;   // 背包塞不下 → 由上层决定收尾还是重试别的槽
        }

        // 从盒内容里扣掉 accepted 个，其余原样保留
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
        // 剩余内容写回盒组件；取光时 newInners 为空 → fromItems(空表) 即"空内容盒"，等价于旧代码里
        // 单写一个 if (newInners.isEmpty()) 分支所做的事（两条路径传入的都是空列表，结果必然相同）。
        boxInSlot.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(newInners));
        return new TakeOut(accepted, newInners.size());
    }
}
