package com.flt.carpetaddition.fakeplayer;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
// IF <= fabric-1.20.4
//import net.minecraft.item.AxeItem;
// END IF
// IF >= fabric-1.19.4
import net.minecraft.registry.tag.ItemTags;
// ELSE IF >= fabric-1.18.2
//import net.minecraft.tag.ItemTags;
// ELSE
//import net.minecraft.tag.ItemTags;
// END IF
import net.minecraft.item.Item;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 讲台放置（规则 A 的"放"部分，与 BlockExcavator 的"挖"对称）。走 interactionManager.interactBlock，不直接 setBlock。
 * 要求主手拿斧头（挖讲台快）、副手放讲台（用 OFF_HAND 右键），与真人刷讲台一致。
 */
final class LecternPlacer {
    /** 副手槽下标。[VERSION] ≥1.17.1 用常量；1.16.5 无该常量，取原版固定值 40 */
// IF >= fabric-1.17.1
    private static final int OFF_HAND_SLOT = PlayerInventory.OFF_HAND_SLOT;
// ELSE
//    private static final int OFF_HAND_SLOT = 40;
// END IF

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
        PlayerInventory inventory = FakePlayerUtils.invOf(this.fakePlayer);
// IF >= fabric-1.21.5
        int selected = inventory.getSelectedSlot();
// ELSE
//        int selected = inventory.selectedSlot;
// END IF
        // 主手已经拿斧头 → 跳过（1.20.5+ 工具为数据驱动，用 #minecraft:axes 标签判定）
// IF >= fabric-1.21
        if (!inventory.getStack(selected).isIn(ItemTags.AXES)) {
// ELSE
//        if (!(inventory.getStack(selected).getItem() instanceof AxeItem)) {
// END IF
            int axeSlot = findAxe(inventory);
            if (axeSlot < 0) {
                return "假人背包/副手里没有斧头，请先给假人一把斧头";
            }
            // 主手原物品（可能是讲台/别的）与斧头互换
            ItemStack onHand = inventory.getStack(selected);
            inventory.setStack(selected, inventory.getStack(axeSlot));
            inventory.setStack(axeSlot, onHand);
        }

        int offhandSlot = OFF_HAND_SLOT;
        if (FakePlayerUtils.isItem(inventory.getStack(offhandSlot), Items.LECTERN)) {
            return null;
        }
        // 找讲台所在槽（跳过副手槽避免自引用）
        for (int slot = 0; slot <= offhandSlot; slot++) {
            if (slot != offhandSlot && FakePlayerUtils.isItem(inventory.getStack(slot), Items.LECTERN)) {
                inventory.setStack(offhandSlot, inventory.getStack(slot));
                inventory.setStack(slot, ItemStack.EMPTY);
                return null;
            }
        }
        return "假人背包/快捷栏里没有讲台，请先给假人一个讲台";
    }

    /** 在背包/快捷栏/副手里找第一个斧头；找不到返回 -1。
     *  [VERSION] ≥1.21 工具数据化，用 #minecraft:axes 标签；≤1.20.4 用 AxeItem 类型判定。 */
    private static int findAxe(PlayerInventory inventory) {
        for (int slot = 0; slot <= OFF_HAND_SLOT; slot++) {
// IF >= fabric-1.21
            if (inventory.getStack(slot).isIn(ItemTags.AXES)) {
// ELSE
//            if (inventory.getStack(slot).getItem() instanceof AxeItem) {
// END IF
                return slot;
            }
        }
        return -1;
    }

    /** 用副手朝目标位置放一次讲台。调用前需先 readyOffhandLectern()。 */
    void place() {
        FakePlayerUtils.lookAt(this.fakePlayer, Vec3d.ofCenter(this.lecternPos));
        // 点 lecternPos.down() 的顶面 → 讲台落在 lecternPos 上
        BlockHitResult hitResult = new BlockHitResult(
                Vec3d.ofBottomCenter(this.lecternPos), Direction.UP, this.lecternPos.down(), false);
        FakePlayerUtils.useItemOn(this.fakePlayer, Hand.OFF_HAND, hitResult);
        FakePlayerUtils.swing(this.fakePlayer);
    }
}
