package com.flt.carpetaddition.mixin.villager;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 光灵箭强制补货（forceRestock）。
 * 注入 AbstractArrow.onHitEntity HEAD：仅光灵箭命中村民时遍历 offers 调 resetUses()，
 * 无视原版"每日 2 次"上限。
 */
@Mixin(AbstractArrow.class)
public class SpectralArrowRestockMixin {

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void flt$forceRestock(EntityHitResult hitResult, CallbackInfo ci) {
        if (!FLTSettings.forceRestock) {
            return;
        }
        if (!((Object) this instanceof SpectralArrow)) {
            return;
        }
        if (hitResult.getEntity() instanceof Villager villager) {
            for (MerchantOffer offer : villager.getOffers()) {
                offer.resetUses();
            }
        }
    }
}