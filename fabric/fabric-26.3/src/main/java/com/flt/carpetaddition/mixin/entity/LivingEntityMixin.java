package com.flt.carpetaddition.mixin.entity;

import com.flt.carpetaddition.settings.FLTSettings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 岩浆探索者（lavaStrider）：深海探索者在岩浆中同样生效。
 * @WrapOperation 包住 travelInFluid 对 travelInLava 的调用：带附魔的玩家改走 travelInWater。
 * 必须带附魔门槛（无附魔按水推+岩浆阻力反而更快）；用 @WrapOperation 不用 @Redirect（更兼容）。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Invoker("travelInWater")
    protected abstract void flt$invokeTravelInWater(Vec3 movementInput, double x, boolean z, double w);

    @WrapOperation(
            method = "travelInFluid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;travelInLava(Lnet/minecraft/world/phys/Vec3;DZD)V"
            )
    )
    private void flt$lavaStrider(LivingEntity entity, Vec3 movementInput, double x, boolean z, double w,
                                 Operation<Void> original) {
        if (FLTSettings.lavaStrider && entity instanceof Player && hasDepthStrider(entity)) {
            flt$invokeTravelInWater(movementInput, x, z, w);
        } else {
            original.call(entity, movementInput, x, z, w);
        }
    }

    /** 深海探索者作用于 WATER_MOVEMENT_EFFICIENCY 属性（+0.333/级） */
    private static boolean hasDepthStrider(LivingEntity entity) {
        return entity.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY) > 0.0;
    }
}