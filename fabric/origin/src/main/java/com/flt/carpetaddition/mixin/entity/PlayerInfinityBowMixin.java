package com.flt.carpetaddition.mixin.entity;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
// IF >= fabric-1.21
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.registry.entry.RegistryEntry;
// END IF
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 无限弓无箭（infinityBowNoArrows）。
 * 注入玩家找箭方法 RETURN：原返回空且武器是带 Infinity 的弓时，兜底返回 1 根普通箭。
 * 射出的箭因 Infinity 附魔不消耗。仅对 BowItem + Infinity 生效。
 * [VERSION] 找箭方法名分界 1.18.2-1.19.4；Infinity API 分界 1.20.5（旧 EnchantmentHelper / 新组件）。
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerInfinityBowMixin {

// IF >= fabric-1.19.4
//    @Inject(method = "getProjectileType", at = @At("RETURN"), cancellable = true)
// ELSE IF <= fabric-1.18.2
//    @Inject(method = "getArrowType", at = @At("RETURN"), cancellable = true)
// ELSE
//#    （理论不可达）
// END IF
    private void flt$infinityBowNoArrows(ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        if (!FLTSettings.infinityBowNoArrows) {
            return;
        }
        if (!cir.getReturnValue().isEmpty()) {
            return;
        }
        if (stack.getItem() instanceof BowItem && hasInfinity(stack)) {
            cir.setReturnValue(new ItemStack(Items.ARROW));
        }
    }

    private static boolean hasInfinity(ItemStack bow) {
// IF >= fabric-1.21
//        ItemEnchantmentsComponent enchantments =
//                bow.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
//        for (RegistryEntry<net.minecraft.enchantment.Enchantment> entry : enchantments.getEnchantments()) {
//            if (entry.matchesKey(Enchantments.INFINITY)) {
//                return true;
//            }
//        }
//        return false;
// ELSE IF <= fabric-1.20.1
//        return EnchantmentHelper.getLevel(Enchantments.INFINITY, bow) > 0;
// ELSE
//#    （理论不可达：无中间版本）
// END IF
    }
}