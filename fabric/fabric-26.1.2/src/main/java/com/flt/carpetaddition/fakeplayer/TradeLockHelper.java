package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 命中后「交易一次把村民报价锁死」的原版交互：右键村民开界面 → 选中目标项 → 点输出槽成交。
 *
 * <p>材料不足或异常时返回 {@code false}，不影响刷取结果本身。
 */
final class TradeLockHelper {
    private TradeLockHelper() {
    }

    static boolean tryLock(EntityPlayerMPFake fakePlayer, Villager villager, int offerIndex) {
        try {
            villager.mobInteract(fakePlayer, InteractionHand.MAIN_HAND);
            if (!(fakePlayer.containerMenu instanceof MerchantMenu menu)) {
                return false;
            }
            // 选中目标交易项，并把付款物品从背包移进付款槽
            menu.setSelectionHint(offerIndex);
            menu.tryMoveItems(offerIndex);
            // 输出槽有东西 = 付款槽已备齐 → 点一下完成交易
            if (menu.getSlot(2).getItem().isEmpty()) {
                return false;
            }
            menu.clicked(2, 0, ContainerInput.PICKUP, fakePlayer);
            // 换来的物品塞回背包，避免留在光标上丢不掉
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty()) {
                if (!fakePlayer.getInventory().add(carried)) {
                    fakePlayer.drop(carried, false, true);
                }
                menu.setCarried(ItemStack.EMPTY);
            }
            return true;
        } catch (Throwable throwable) {
            FLTAdditionMod.LOGGER.warn("[Tradefinder] 自动锁定交易失败（不影响刷取结果）", throwable);
            return false;
        } finally {
            fakePlayer.closeContainer();
        }
    }
}
