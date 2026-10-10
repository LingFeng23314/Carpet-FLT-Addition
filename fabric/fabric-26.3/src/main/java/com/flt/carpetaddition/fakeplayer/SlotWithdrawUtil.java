package com.flt.carpetaddition.fakeplayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 「从容器槽里按需取 N 个」的唯一实现（[VERSION] 2026-09-30 新增）。
 *
 * <p><b>它解决什么问题</b>：原版 {@code AbstractContainerMenu.quickMoveStack} 是
 * "<b>整槽搬运</b>"语义（等价玩家对容器槽 shift 点击），没法只取一部分。取货动作原先一律用它，
 * 于是"取 3 个"会把该槽全部 57 个搬回来 —— 这正是用户报障
 * 「填了同样的数字，取回来却不一样」的根因。
 * 2026-09-29 实测日志：请求 {@code x3} → 「已取够 57 个」；{@code x6} / {@code x10} → 「已取够 64 个」。
 *
 * <p><b>做法</b>：{@code split(want)} 拆出要取的那一份 → {@code Inventory.add} 放入背包
 * （{@code add} 会把没放下的剩余<b>原地</b>写回入参）→ 没放下的部分原样还回原槽，
 * 既不丢物也不复制。与 {@link ShulkerBoxUtil#takeOutInside} 是同一套
 * "先拆、按实际放入量扣减"的纪律。
 *
 * <p>⚠️ <b>为什么两个调用方必须共用这一份</b>：{@link ContainerWithdrawAction}（单箱取货）与
 * {@link MultiContainerWithdrawAction}（跨箱取货）如果各写一份，必然出现"改了一处漏一处"的行为漂移 ——
 * 这正是 {@link ShulkerBoxUtil} 类注释里点名批评过的老问题。故收敛到这里，只留一个实现。
 *
 * <p>⚠️ 无论是否发生还回，最后都要 {@code slot.set(...)} 一次：既把"少掉的 take 个"同步出去
 * （{@code setChanged} → 客户端能看到箱子内容变化），也覆盖"某些容器 {@code getItem()}
 * 返回副本而非引用"的实现差异。
 *
 * <p>⚠️ <b>不要合并进 {@link FakePlayerUtils}</b>：那是 26.3 的版本专属适配文件，三版同步脚本
 * 会跳过它 → 公共工具放那里会漏同步。本类不在排除表，26.1.2 → 26.2 / 26.3 正常镜像。
 */
public final class SlotWithdrawUtil {
    private SlotWithdrawUtil() {
    }

    /**
     * 从 {@code menu} 的第 {@code slotIndex} 个槽里取出 {@code want} 个物品放进假人背包，
     * <b>多余的留在原槽</b>。
     *
     * @param menu      已打开的容器菜单
     * @param bot       取货的假人
     * @param slotIndex 菜单内的槽索引（容器槽）
     * @param want      期望取出的数量（&gt; 0）
     * @return 实际放进背包的数量；0 = 一个也没放进去（空槽 / 槽位非法 / 背包塞不下）
     */
    public static int takePartialFromSlot(AbstractContainerMenu menu, ServerPlayer bot,
                                          int slotIndex, int want) {
        if (menu == null || bot == null || want <= 0
                || slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return 0;
        }
        Slot slot = menu.slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return 0;
        }
        ItemStack inSlot = slot.getItem();
        if (inSlot.isEmpty() || inSlot.getCount() <= 0) {
            return 0;
        }

        int take = Math.min(want, inSlot.getCount());
        ItemStack toAdd = inSlot.split(take);   // 原槽立刻少掉 take 个（没放下的部分下面还回）
        bot.getInventory().add(toAdd);          // add 会把塞不下的剩余原地写回 toAdd
        int accepted = take - toAdd.getCount();

        if (accepted < take) {
            // 有没放下的 → 还回原槽，保证"既不丢物也不复制"
            int backCount = take - accepted;
            if (inSlot.isEmpty()) {
                slot.set(toAdd.copyWithCount(backCount));
            } else {
                inSlot.grow(backCount);
                slot.set(inSlot);
            }
        } else {
            // 全部放下 → 也要同步"少掉 take 个"
            slot.set(inSlot);
        }
        return accepted;
    }
}
