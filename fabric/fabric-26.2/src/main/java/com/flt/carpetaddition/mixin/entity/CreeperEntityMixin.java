package com.flt.carpetaddition.mixin.entity;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 苦力怕爆炸不破坏方块（noCreeperGrief）。
 * @ModifyArg 把 explodeCreeper 内 ServerLevel.explode 的 Level$ExplosionInteraction
 * 参数（index=5）换成 NONE（不破坏方块，保留伤害）。
 */
@Mixin(Creeper.class)
public abstract class CreeperEntityMixin {

    @ModifyArg(
            method = "explodeCreeper",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;explode(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)V"
            ),
            index = 5
    )
    private Level.ExplosionInteraction flt_noCreeperGrief(Level.ExplosionInteraction interaction) {
        return FLTSettings.noCreeperGrief ? Level.ExplosionInteraction.NONE : interaction;
    }
}