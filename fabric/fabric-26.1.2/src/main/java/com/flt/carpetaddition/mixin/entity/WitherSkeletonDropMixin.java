package com.flt.carpetaddition.mixin.entity;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 凋零骷髅不掉石剑（witherSkeletonNoStoneSword）。
 * @ModifyArg 把 dropCustomDeathLoot 内 spawnAtLocation 的 ItemStack 参数换成 EMPTY，
 * 原版因 isEmpty() 短路而不生成物品实体。战利品表（骨头/煤炭/头颅）不受影响。
 * [VERSION] spawnAtLocation 单/双参分界 1.21.2；1.20.4- 装备入口为 dropEquipment。
 */
@Mixin(Mob.class)
public abstract class WitherSkeletonDropMixin {

    @ModifyArg(
            method = "dropCustomDeathLoot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Mob;spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/entity/item/ItemEntity;"
            ),
            index = 1
    )
    private ItemStack flt$witherSkeletonNoStoneSword(ItemStack stack) {
        if (!FLTSettings.witherSkeletonNoStoneSword) {
            return stack;
        }
        if (!((Object) this instanceof WitherSkeleton)) {
            return stack;
        }
        if (stack.is(Items.STONE_SWORD)) {
            return ItemStack.EMPTY;
        }
        return stack;
    }
}