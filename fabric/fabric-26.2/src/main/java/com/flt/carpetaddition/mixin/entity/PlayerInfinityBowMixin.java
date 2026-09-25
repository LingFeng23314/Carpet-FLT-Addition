package com.flt.carpetaddition.mixin.entity;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 无限弓无箭（infinityBowNoArrows）。
 * 注入 Player.getProjectile RETURN：原返回空且武器是带 Infinity 的弓时，兜底返回 1 根普通箭。
 * 射出的箭因 Infinity 的 AMMO_USE SetValue(0) 效果不消耗。仅对 BowItem + Infinity 生效。
 */
@Mixin(Player.class)
public abstract class PlayerInfinityBowMixin {

    @Inject(method = "getProjectile", at = @At("RETURN"), cancellable = true)
    private void flt$infinityBowNoArrows(ItemStack heldWeapon, CallbackInfoReturnable<ItemStack> cir) {
        if (!FLTSettings.infinityBowNoArrows) {
            return;
        }
        if (!cir.getReturnValue().isEmpty()) {
            return;
        }
        if (heldWeapon.getItem() instanceof BowItem && hasInfinity(heldWeapon)) {
            cir.setReturnValue(new ItemStack(Items.ARROW));
        }
    }

    private static boolean hasInfinity(ItemStack bow) {
        ItemEnchantments enchantments = bow.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var holder : enchantments.keySet()) {
            if (holder.is(Enchantments.INFINITY)) {
                return true;
            }
        }
        return false;
    }
}