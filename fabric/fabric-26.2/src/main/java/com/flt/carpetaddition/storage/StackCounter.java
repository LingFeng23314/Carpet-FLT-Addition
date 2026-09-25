package com.flt.carpetaddition.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

/**
 * 单个物品堆的"可提取内容"统计规则（组合拳二期）。
 *
 * <p>规则（照 Carpet LMS Addition 的 {@code StorageSlotCounter} 思路<b>独立实现</b>）：
 * <ul>
 *   <li>普通物品 → (自身, 数量)</li>
 *   <li>空潜影盒 → (潜影盒本身, 数量)</li>
 *   <li>潜影盒且盒内<b>全是同一种</b>物品 → (该物品, 盒内数量 × 盒数)</li>
 *   <li>潜影盒<b>混装</b> → <b>不计入</b>（返回 null）</li>
 * </ul>
 *
 * <p>为什么混装盒不计入：假人取货是"整槽搬运"（{@code quickMoveStack}），混装盒里目标物品
 * 与别的东西混在一起，无法一次干净取出。若把混装盒也统计进去，就会出现
 * "统计说库存够、实际取不出来"的偏差。**宁可少算，保证「统计到的 = 能取到的」**。
 *
 * <p>[VERSION] 26.x：潜影盒判定用 {@code ItemTags.SHULKER_BOXES}（覆盖全部 17 种颜色盒）；
 * 盒内容读取用 {@code ItemContainerContents.nonEmptyItemCopyStream()} → 直接是 ItemStack。
 */
public final class StackCounter {
    private StackCounter() {
    }

    /** 一个物品堆的内容：归一化物品 ID + 可提取数量 */
    public record Count(Identifier itemId, int count) {
    }

    /**
     * 统计物品堆的"可提取内容"。
     * @return 可提取内容；空堆或混装潜影盒返回 {@code null}（表示不计入统计）
     */
    public static Count count(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (!stack.is(ItemTags.SHULKER_BOXES)) {
            return new Count(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount());
        }

        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return shulkerItself(stack);   // 空盒：算作潜影盒本身
        }

        List<ItemStack> inners = contents.nonEmptyItemCopyStream().toList();
        Identifier innerItem = null;
        int innerCount = 0;
        for (ItemStack inner : inners) {
            if (inner.isEmpty()) {
                continue;
            }
            Identifier innerId = BuiltInRegistries.ITEM.getKey(inner.getItem());
            if (innerId == null) {
                continue;
            }
            if (innerItem == null) {
                innerItem = innerId;
            } else if (!innerItem.equals(innerId)) {
                return null;   // 混装 → 不计入
            }
            innerCount += inner.getCount();
        }

        if (innerItem == null) {
            return shulkerItself(stack);   // 盒内实际为空
        }
        return new Count(innerItem, innerCount * stack.getCount());
    }

    private static Count shulkerItself(ItemStack stack) {
        return new Count(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount());
    }

    /**
     * 统计一个潜影盒里<b>指定物品</b>的数量（<b>兼容混装盒</b>）。
     *
     * <p>与 {@link #count} 的区别：{@code count} 对混装盒（盒内 >1 种物品）返回 {@code null}
     * 不计入库存统计（"宁可少算"）；而本方法<b>专门挑出盒内某一种目标物品的数</b>，
     * 用于假人取货时识别"这个盒里有多少目标物品可以拿走"，纯盒/杂盒都能算。
     *
     * @param box     一个潜影盒 ItemStack（空堆/非盒返回 {@code null} 语义：返回 0）
     * @param targetItemId 想数的是哪个物品（指定目标物 id）
     * @return 盒内该物品的总数（不含盒子本身）；若 box 非潜影盒 / 盒内没有该物品则返回 0；
     *         若 box 为 null / 空则返回 0。
     */
    public static int countTargetInsideBox(ItemStack box, Identifier targetItemId) {
        if (box == null || box.isEmpty() || !box.is(ItemTags.SHULKER_BOXES) || targetItemId == null) {
            return 0;
        }
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) {
            return 0;   // 空盒：盒内没有目标物品
        }
        int n = 0;
        List<ItemStack> inners = contents.nonEmptyItemCopyStream().toList();
        for (ItemStack inner : inners) {
            if (inner.isEmpty()) {
                continue;
            }
            Identifier innerId = BuiltInRegistries.ITEM.getKey(inner.getItem());
            if (targetItemId.equals(innerId)) {
                n += inner.getCount();
            }
        }
        return n;   // 注意：纯盒返回的也是盒内总数（未乘堆叠数），与调用方约定一致
    }
}