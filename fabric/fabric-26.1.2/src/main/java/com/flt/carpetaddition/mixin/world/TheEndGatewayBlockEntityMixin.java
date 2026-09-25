package com.flt.carpetaddition.mixin.world;

import com.flt.carpetaddition.settings.FLTSettings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 末地折跃门寻出口距离（endGatewayExitSearchDistance）。
 *
 * <p>折跃门首次穿越时会在末地外圈找一个空岛作为配对回程门，原版沿径向向外硬编码 1024 格。
 * {@code findExitPortalXZPosTentative} 内用 {@code dir.scale(1024.0d)} 定位候选点，
 * 本 Mixin 包住这次 scale：规则 &gt;0 时把距离换成自定义值（如 768），规则=0 保持原版行为。
 * 只改半径向量，不影响方法内搜空岛的 ±16 格步进与 findTallestBlock 落地逻辑。
 *
 * <p><b>⚠️ 回调必须是 static，不能改</b>：目标方法 {@code findExitPortalXZPosTentative}
 * 在 26.x 是 {@code private static}（javap 实证）。MixinExtras 的 {@code @WrapOperation}
 * 若用实例回调去注入 static 方法，会在 bootstrap 阶段直接崩：
 * {@code InvalidInjectionException: non-static callback method ... targets a static method}。
 * 也正因为目标方法是 static，签名里没有"外围 this"，只有外围方法的参数 (ServerLevel, BlockPos)。
 *
 * <p>方法内共 3 处 {@code Vec3.scale(D)}：1024（原版半径）+ 搜岛步进的 ±16。
 * 本回调三处都会被调，靠 {@code distance <= 100.0} 把 ±16 放行给原版。
 */
@Mixin(TheEndGatewayBlockEntity.class)
public abstract class TheEndGatewayBlockEntityMixin {

    @WrapOperation(
            method = "findExitPortalXZPosTentative",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private static Vec3 flt$customizedExitSearchDistance(Vec3 direction, double distance,
                                                         Operation<Vec3> original,
                                                         ServerLevel level, BlockPos pos) {
        int custom = FLTSettings.endGatewayExitSearchDistance;
        if (custom <= 0 || distance <= 100.0) {
            // 规则关(默认0 → 原版1024)；distance<=100 是搜岛的 ±16 格步进，必须原样放行
            return original.call(direction, distance);
        }
        // 走到这里 distance=1024（原版寻出口半径）→ 按自定义距离重算方向向量
        return direction.scale(custom);
    }
}
