package com.flt.carpetaddition.mixin.entity;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
// IF <= fabric-1.18.2
//import net.minecraft.world.explosion.Explosion;
// END IF

/**
 * 苦力怕爆炸不破坏方块（noCreeperGrief）。
 * @ModifyArg 把 explode 内 ServerWorld.createExplosion 的"破坏方式"参数替换为 NONE
 * （不破坏方块，保留伤害）。
 * [VERSION] createExplosion 签名三档：
 *   1.21+：6 参，爆炸源类型 index 5
 *   1.19.4~1.20.4：9 参，爆炸源类型 index 8
 *   1.18.2-：9 参，类型是 Explosion$DestructionType，index 8
 */
@Mixin(CreeperEntity.class)
public abstract class CreeperEntityMixin {

// IF >= fabric-1.21
    @ModifyArg(
            method = "explode",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;createExplosion(Lnet/minecraft/entity/Entity;DDDFLnet/minecraft/world/World$ExplosionSourceType;)V"
            ),
            index = 5
    )
    private World.ExplosionSourceType flt_noCreeperGrief(World.ExplosionSourceType sourceType) {
        return FLTSettings.noCreeperGrief ? World.ExplosionSourceType.NONE : sourceType;
    }
// ELSE IF >= fabric-1.19.4
//    @ModifyArg(
//            method = "explode",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/world/ServerWorld;createExplosion(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;DDDFZLnet/minecraft/world/World$ExplosionSourceType;)Lnet/minecraft/world/explosion/Explosion;"
//            ),
//            index = 8
//    )
//    private World.ExplosionSourceType flt_noCreeperGrief(World.ExplosionSourceType sourceType) {
//        return FLTSettings.noCreeperGrief ? World.ExplosionSourceType.NONE : sourceType;
//    }
// ELSE
//    @ModifyArg(
//            method = "explode",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/world/ServerWorld;createExplosion(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;DDDFZLnet/minecraft/world/explosion/Explosion$DestructionType;)Lnet/minecraft/world/explosion/Explosion;"
//            ),
//            index = 8
//    )
//    private Explosion.DestructionType flt_noCreeperGrief(Explosion.DestructionType destructionType) {
//        return FLTSettings.noCreeperGrief ? Explosion.DestructionType.NONE : destructionType;
//    }
// END IF
}