package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 讲台放置（规则 A 的"放"部分，与 BlockExcavator 的"挖"对称）。走 gameMode.useItemOn，不直接 setBlock。
 * 要求主手拿斧头（挖讲台快）、副手放讲台（用 OFF_HAND 右键），与真人刷讲台一致。
 */
final class LecternPlacer {
    private final EntityPlayerMPFake fakePlayer;
    private final BlockPos lecternPos;

    LecternPlacer(EntityPlayerMPFake fakePlayer, BlockPos lecternPos) {
        this.fakePlayer = fakePlayer;
        this.lecternPos = lecternPos;
    }

    /**
     * 确保主手拿斧头、副手有讲台。
     * 1) 主手非斧头 → 从背包/快捷栏/副手找斧头换到主手（原主手物品移到该槽）；都没有斧头 → 报错
     *    若原主手物品恰是讲台且斧头在副手，则二者直接互换，一步到位
     * 2) 副手是讲台 → 就绪；否则从背包找讲台移到副手
     * @return null = 就绪；否则 = 错误原因（找不到斧头 / 找不到讲台）
     */
    String readyOffhandLectern() {
        Inventory inventory = this.fakePlayer.getInventory();
        int selected = inventory.getSelectedSlot();
        // 主手已经拿斧头 → 跳过
        if (!(inventory.getSelectedItem().getItem() instanceof AxeItem)) {
            int axeSlot = findItem(inventory, AxeItem.class);
            if (axeSlot < 0) {
                return "假人背包/副手里没有斧头，请先给假人一把斧头";
            }
            // 主手原物品（可能是讲台/别的）与斧头互换
            ItemStack onHand = inventory.getSelectedItem();
            inventory.setItem(selected, inventory.getItem(axeSlot));
            inventory.setItem(axeSlot, onHand);
        }

        int offhandSlot = Inventory.SLOT_OFFHAND;
        if (inventory.getItem(offhandSlot).is(Items.LECTERN)) {
            return null;
        }
        // 找讲台所在槽（跳过副手槽避免自引用）
        for (int slot = 0; slot <= offhandSlot; slot++) {
            if (slot != offhandSlot && inventory.getItem(slot).is(Items.LECTERN)) {
                inventory.setItem(offhandSlot, inventory.getItem(slot));
                inventory.setItem(slot, ItemStack.EMPTY);
                return null;
            }
        }
        return "假人背包/快捷栏里没有讲台，请先给假人一个讲台";
    }

    /** 在背包/快捷栏/副手里找第一个指定类型的物品槽位；找不到返回 -1 */
    private static int findItem(Inventory inventory, Class<?> itemClass) {
        for (int slot = 0; slot <= Inventory.SLOT_OFFHAND; slot++) {
            if (itemClass.isInstance(inventory.getItem(slot).getItem())) {
                return slot;
            }
        }
        return -1;
    }

    /** 用副手朝目标位置放一次讲台。调用前需先 readyOffhandLectern()。 */
    void place() {
        FakePlayerUtils.lookAt(this.fakePlayer, Vec3.atCenterOf(this.lecternPos));
        // 点 lecternPos.below() 的顶面 → 讲台落在 lecternPos 上
        BlockHitResult hitResult = new BlockHitResult(
                Vec3.atBottomCenterOf(this.lecternPos), Direction.UP, this.lecternPos.below(), false);
        FakePlayerUtils.useItemOn(this.fakePlayer, InteractionHand.OFF_HAND, hitResult);
        FakePlayerUtils.swing(this.fakePlayer);
    }
}