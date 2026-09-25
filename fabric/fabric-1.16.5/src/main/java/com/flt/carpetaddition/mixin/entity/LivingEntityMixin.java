package com.flt.carpetaddition.mixin.entity;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// IF >= fabric-1.21.11
//import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
// END IF

/**
 * 岩浆探索者（LavaStrider）：深海探索者在岩浆中也生效。
 * 必须带附魔门槛（无附魔按水推+岩浆阻力反而更快）。
 * 用 @WrapOperation 不用 @Redirect（更兼容）。
 * [VERSION] 旅行机制四档分界：
 *   1.21.11+：travelInFluid 内 travelInLava 调用，@WrapOperation
 *   1.21.4~1.21.10：travelInFluid HEAD 仿写水分支，getBaseWaterMovementSpeedMultiplier
 *   1.21~1.21.3：travel HEAD 仿写，getBaseMovementSpeedMultiplier
 *   1.20.4-：travel HEAD 仿写 + getDepthStrider 等级（int/3.0 归一化）+ 重力 0.08
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

// IF >= fabric-1.21.11
//#    /** 1.21.11+：原版受保护方法 travelInWater（岩浆改用水中逻辑时直接调用） */
//    @Invoker("travelInWater")
//    protected abstract void flt$invokeTravelInWater(Vec3d movementInput, double x, boolean z, double w);
//
//#    /** 1.21.11+ 新结构：包住 travelInFluid 里的 travelInLava 调用，有深海探索者的玩家改走 travelInWater */
//    @WrapOperation(
//            method = "travelInFluid",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/entity/LivingEntity;travelInLava(Lnet/minecraft/util/math/Vec3d;DZD)V"
//            )
//    )
//    private void flt$lavaStrider(LivingEntity entity, Vec3d movementInput, double x, boolean z, double w,
//                                 Operation<Void> original) {
//        if (FLTSettings.LavaStrider && entity instanceof PlayerEntity && hasDepthStrider(entity)) {
//            flt$invokeTravelInWater(movementInput, x, z, w);
//        } else {
//            original.call(entity, movementInput, x, z, w);
//        }
//    }
// ELSE IF >= fabric-1.21.4
//#    /** 1.21.4~1.21.10：仿写 1.21.10 travelInFluid 水分支（getBaseWaterMovementSpeedMultiplier 改名） */
//    @Shadow
//    protected abstract float getBaseWaterMovementSpeedMultiplier();
//
//    @Inject(method = "travelInFluid", at = @At("HEAD"), cancellable = true)
//    private void flt$lavaStrider1214(Vec3d movementInput, CallbackInfo ci) {
//        LivingEntity self = (LivingEntity) (Object) this;
//        if (!FLTSettings.LavaStrider || !(self instanceof PlayerEntity) || !hasDepthStrider(self) || !self.isInLava()) {
//            return;
//        }
//        double gravity = flt$gravity(this);
//        boolean slowFalling = self.getVelocity().y <= 0.0 && self.hasStatusEffect(StatusEffects.SLOW_FALLING);
//        if (slowFalling) {
//            gravity = Math.min(gravity, 0.01);
//        }
//        double startY = self.getY();
//        float movementSpeed = self.isSprinting() ? 0.9f : flt$baseMoveSpeed(this);
//        float accel = 0.02f;
//        float eff = getWaterEfficiency(self);
//        if (self.isOnGround()) {
//            eff *= 0.5f;
//        }
//        if (eff > 0.0f) {
//            movementSpeed = 0.546f + (0.546f - movementSpeed) * eff;
//            accel = 0.02f + (self.getMovementSpeed() - 0.02f) * eff;
//        }
//        if (self.hasStatusEffect(StatusEffects.DOLPHINS_GRACE)) {
//            movementSpeed = 0.96f;
//        }
//        self.updateVelocity(accel, movementInput);
//        self.move(MovementType.SELF, self.getVelocity());
//        Vec3d vel = self.getVelocity();
//        if (self.horizontalCollision && self.isClimbing()) {
//            vel = new Vec3d(vel.x, 0.2, vel.z);
//        }
//        self.setVelocity(vel.multiply(movementSpeed, 0.8, movementSpeed));
//        self.setVelocity(self.applyFluidMovingSpeed(gravity, slowFalling, self.getVelocity()));
//        if (self.horizontalCollision) {
//            Vec3d v = self.getVelocity();
//            if (self.doesNotCollide(v.x, v.y + 0.6 - self.getY() + startY, v.z)) {
//                self.setVelocity(v.x, v.y + 0.6 - self.getY() + startY, v.z);
//            }
//        }
//        ci.cancel();
//    }
// ELSE IF >= fabric-1.21
//#    /** 1.21~1.21.3：travel() 单方法入口；基础速度仍是 getBaseMovementSpeedMultiplier */
//    @Shadow
//    protected abstract float getBaseMovementSpeedMultiplier();
//
//    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
//    private void flt$lavaStriderOld21(Vec3d movementInput, CallbackInfo ci) {
//        LivingEntity self = (LivingEntity) (Object) this;
//        if (!FLTSettings.LavaStrider || !(self instanceof PlayerEntity) || !hasDepthStrider(self) || !self.isInLava()) {
//            return;
//        }
//        double gravity = flt$gravity(this);
//        boolean slowFalling = self.getVelocity().y <= 0.0 && self.hasStatusEffect(StatusEffects.SLOW_FALLING);
//        if (slowFalling) {
//            gravity = Math.min(gravity, 0.01);
//        }
//        double startY = self.getY();
//        float movementSpeed = self.isSprinting() ? 0.9f : flt$baseMoveSpeed(this);
//        float accel = 0.02f;
//        float eff = getWaterEfficiency(self);
//        if (self.isOnGround()) {
//            eff *= 0.5f;
//        }
//        if (eff > 0.0f) {
//            movementSpeed = 0.546f + (0.546f - movementSpeed) * eff;
//            accel = 0.02f + (self.getMovementSpeed() - 0.02f) * eff;
//        }
//        if (self.hasStatusEffect(StatusEffects.DOLPHINS_GRACE)) {
//            movementSpeed = 0.96f;
//        }
//        self.updateVelocity(accel, movementInput);
//        self.move(MovementType.SELF, self.getVelocity());
//        Vec3d vel = self.getVelocity();
//        if (self.horizontalCollision && self.isClimbing()) {
//            vel = new Vec3d(vel.x, 0.2, vel.z);
//        }
//        self.setVelocity(vel.multiply(movementSpeed, 0.8, movementSpeed));
//        self.setVelocity(self.applyFluidMovingSpeed(gravity, slowFalling, self.getVelocity()));
//        if (self.horizontalCollision) {
//            Vec3d v = self.getVelocity();
//            if (self.doesNotCollide(v.x, v.y + 0.6 - self.getY() + startY, v.z)) {
//                self.setVelocity(v.x, v.y + 0.6 - self.getY() + startY, v.z);
//            }
//        }
//        ci.cancel();
//    }
// ELSE
//#    /** 1.20.4-：travel() 单方法 + 重力常量 0.08 + 附魔等级制（EnchantmentHelper） */
    @Shadow
    protected abstract float getBaseMovementSpeedMultiplier();

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void flt$lavaStriderOld120(Vec3d movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!FLTSettings.LavaStrider || !(self instanceof PlayerEntity) || !hasDepthStrider(self) || !self.isInLava()) {
            return;
        }
        double gravity = flt$gravity(this);
        boolean slowFalling = self.getVelocity().y <= 0.0 && self.hasStatusEffect(StatusEffects.SLOW_FALLING);
        if (slowFalling) {
            gravity = 0.01;
        }
        double startY = self.getY();
        float movementSpeed = self.isSprinting() ? 0.9f : flt$baseMoveSpeed(this);
        float accel = 0.02f;
        float eff = getWaterEfficiency(self);
        if (eff > 3.0f) {
            eff = 3.0f;
        }
        if (self.isOnGround()) {
            eff *= 0.5f;
        }
        if (eff > 0.0f) {
            movementSpeed = 0.546f + (0.546f - movementSpeed) * eff / 3.0f;
            accel = 0.02f + (self.getMovementSpeed() - 0.02f) * eff / 3.0f;
        }
        if (self.hasStatusEffect(StatusEffects.DOLPHINS_GRACE)) {
            movementSpeed = 0.96f;
        }
        self.updateVelocity(accel, movementInput);
        self.move(MovementType.SELF, self.getVelocity());
        Vec3d vel = self.getVelocity();
        if (self.horizontalCollision && self.isClimbing()) {
            vel = new Vec3d(vel.x, 0.2, vel.z);
        }
        self.setVelocity(vel.multiply(movementSpeed, 0.8, movementSpeed));
        self.setVelocity(flt$fluidMovingSpeed(self, gravity, slowFalling, self.getVelocity()));
        if (self.horizontalCollision) {
            Vec3d v = self.getVelocity();
            if (self.doesNotCollide(v.x, v.y + 0.6 - self.getY() + startY, v.z)) {
                self.setVelocity(v.x, v.y + 0.6 - self.getY() + startY, v.z);
            }
        }
        ci.cancel();
    }
// END IF

// IF >= fabric-1.21
//#    /** 1.21 系列原版 protected 方法 getGravity()（1.20.4- 硬编码 0.08） */
//    @Shadow
//    protected abstract double getGravity();
// END IF

    /** 基础移动速度倍率（版本分档） */
    private static float flt$baseMoveSpeed(LivingEntityMixin mixin) {
// IF >= fabric-1.21.11
//        return 0;   // 1.21.11 占位防引用未声明 @Shadow
// ELSE IF >= fabric-1.21.4
//        return mixin.getBaseWaterMovementSpeedMultiplier();
// ELSE
        return mixin.getBaseMovementSpeedMultiplier();
// END IF
    }

    /** 重力（1.21+ getGravity()；1.20.4- 硬编码 0.08） */
    private static double flt$gravity(LivingEntityMixin mixin) {
// IF >= fabric-1.21
//        return mixin.getGravity();
// ELSE
        return 0.08;
// END IF
    }

    /** 1.20.4- 内联 applyFluidMovingSpeed 字节码（1.19.4+ yarn 可直调） */
    private static Vec3d flt$fluidMovingSpeed(LivingEntity self, double gravity, boolean slowFalling, Vec3d velocity) {
        if (!self.hasNoGravity() && !self.isSprinting()) {
            double y;
            if (slowFalling && Math.abs(velocity.y - 0.005) < 0.003
                    && Math.abs(velocity.y - gravity / 16.0) >= 0.003) {
                y = -0.003;
            } else {
                y = velocity.y - gravity / 16.0;
            }
            return new Vec3d(velocity.x, y, velocity.z);
        }
        return velocity;
    }

    /** 是否有深海探索者附魔（水中移动效率 > 0） */
    private static boolean hasDepthStrider(LivingEntity entity) {
        return getWaterEfficiency(entity) > 0.0;
    }

    /** 水中移动效率（1.21.2+ 属性无前缀 / 1.21~1.21.1 带前缀 / 1.20.4- 附魔等级 int） */
    private static float getWaterEfficiency(LivingEntity entity) {
// IF >= fabric-1.21.2
//        return (float) entity.getAttributeValue(EntityAttributes.WATER_MOVEMENT_EFFICIENCY);
// ELSE IF >= fabric-1.21
//        return (float) entity.getAttributeValue(EntityAttributes.GENERIC_WATER_MOVEMENT_EFFICIENCY);
// ELSE
        return EnchantmentHelper.getDepthStrider(entity);
// END IF
    }
}