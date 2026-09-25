package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

/**
 * 命中后「交易一次把村民报价锁死」的原版交互：右键村民开界面 → 选中目标项 → 点输出槽成交。
 *
 * <p>材料不足或异常时返回 {@code false}，不影响刷取结果本身。
 * //# [VERSION] 1.21.5+ 交易项重构为 TradedItem，本类选择/成交入口按版本分支。
 */
final class TradeLockHelper {
    private TradeLockHelper() {
    }

    static boolean tryLock(EntityPlayerMPFake fakePlayer, VillagerEntity villager, int offerIndex) {
        try {
            villager.interactMob(fakePlayer, Hand.MAIN_HAND);
            if (!(fakePlayer.currentScreenHandler instanceof MerchantScreenHandler menu)) {
                return false;
            }
            // 选中目标交易项，并把付款物品从背包移进付款槽
            menu.setRecipeIndex(offerIndex);
            menu.switchTo(offerIndex);
            // 输出槽有东西 = 付款槽已备齐 → 点一下完成交易
            if (menu.getSlot(2).getStack().isEmpty()) {
                return false;
            }
// IF >= fabric-1.17.1
            menu.onSlotClick(2, 0, SlotActionType.PICKUP, fakePlayer);
//            // 换来的物品塞回背包，避免留在光标上丢不掉
            ItemStack carried = menu.getCursorStack();
            if (!carried.isEmpty()) {
                if (!FakePlayerUtils.invOf(fakePlayer).insertStack(carried)) {
                    fakePlayer.dropItem(carried, false);
                }
                menu.setCursorStack(ItemStack.EMPTY);
            }
// ELSE
            // 1.16.5：ScreenHandler 无公开光标栈接口，改用 shift-点击把成交物直接移进背包
//            menu.onSlotClick(2, 0, SlotActionType.QUICK_MOVE, fakePlayer);
// END IF
            return true;
        } catch (Throwable throwable) {
            FLTAdditionMod.LOGGER.warn("[Tradefinder] 自动锁定交易失败（不影响刷取结果）", throwable);
            return false;
        } finally {
            fakePlayer.closeHandledScreen();
        }
    }
}
