package com.flt.carpetaddition.mixin.entity;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 凋零骷髅不掉石剑（witherSkeletonNoStoneSword，全版本）。
 * @ModifyArg 把 dropEquipment 内 dropStack 的 ItemStack 参数换成 EMPTY，原版因 isEmpty() 短路而不生成物品实体。
 * 战利品表（骨头/煤炭/头颅）不受影响。
 * [VERSION] dropStack 单/双参分界 1.21.2：<=1.21 单参 index=0，>=1.21.2 双参 (ServerWorld, ItemStack) index=1。
 * 石剑判断用 getItem()==STONE_SWORD（isOf 1.20.1+ 才有，getItem 全版本通用）。
 */
@Mixin(MobEntity.class)
public abstract class WitherSkeletonDropMixin {

// IF <= fabric-1.21
//    @ModifyArg(
//            method = "dropEquipment",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/entity/Entity;dropStack(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/entity/ItemEntity;"
//            ),
//            index = 0
//    )
//    private ItemStack flt$witherSkeletonNoStoneSword(ItemStack stack) {
//        if (!FLTSettings.witherSkeletonNoStoneSword || !((Object) this instanceof WitherSkeletonEntity)) {
//            return stack;
//        }
//        return stack.getItem() == Items.STONE_SWORD ? ItemStack.EMPTY : stack;
//    }
// ELSE
    @ModifyArg(
            method = "dropEquipment",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;dropStack(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/item/ItemStack;)Lnet/minecraft/entity/ItemEntity;"
            ),
            index = 1
    )
    private ItemStack flt$witherSkeletonNoStoneSword(ItemStack stack) {
        if (!FLTSettings.witherSkeletonNoStoneSword || !((Object) this instanceof WitherSkeletonEntity)) {
            return stack;
        }
        return stack.getItem() == Items.STONE_SWORD ? ItemStack.EMPTY : stack;
    }
// END IF
}
