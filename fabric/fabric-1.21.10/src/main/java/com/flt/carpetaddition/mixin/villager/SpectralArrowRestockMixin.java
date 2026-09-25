package com.flt.carpetaddition.mixin.villager;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 光灵箭强制补货（forceRestock）。
 * 注入 PersistentProjectileEntity.onEntityHit HEAD：仅光灵箭命中村民时遍历 offers 调 resetUses()，
 * 无视原版"每日 2 次"上限。
 */
@Mixin(PersistentProjectileEntity.class)
public class SpectralArrowRestockMixin {

    @Inject(method = "onEntityHit", at = @At("HEAD"))
    private void flt$forceRestock(EntityHitResult hitResult, CallbackInfo ci) {
        if (!FLTSettings.forceRestock) {
            return;
        }
        if (!((Object) this instanceof SpectralArrowEntity)) {
            return;
        }
        if (hitResult.getEntity() instanceof VillagerEntity villager) {
            for (TradeOffer offer : villager.getOffers()) {
                offer.resetUses();
            }
        }
    }
}