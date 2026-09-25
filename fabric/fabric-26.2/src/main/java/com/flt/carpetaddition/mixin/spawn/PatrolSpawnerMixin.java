package com.flt.carpetaddition.mixin.spawn;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 限制巡逻队生成（limitPillagerPatrolSpawn）。
 * 注入 PatrolSpawner.spawnPatrolMember HEAD：读 NaturalSpawner$SpawnState（原版每 tick
 * 已算好的刷怪统计）判断怪物类别是否满或区块是否可生成，否则 setReturnValue(false)。
 * 参考 ORG 的 limitPhantomSpawn 思路。
 */
@Mixin(PatrolSpawner.class)
public class PatrolSpawnerMixin {

    @Inject(method = "spawnPatrolMember", at = @At("HEAD"), cancellable = true)
    private void flt$limitPillagerPatrolSpawn(ServerLevel world, BlockPos pos, RandomSource random, boolean leader,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!FLTSettings.limitPillagerPatrolSpawn) {
            return;
        }
        NaturalSpawner.SpawnState state = world.getChunkSource().getLastSpawnState();
        if (state == null) {
            return;  // 刷怪统计尚未计算（世界刚启动），放行
        }
        SpawnHelperInfoAccessor accessor = (SpawnHelperInfoAccessor) state;
        boolean belowCap = accessor.flt$invokeCanSpawnForCategoryGlobal(MobCategory.MONSTER);
        boolean canSpawn = accessor.flt$invokeCanSpawnForCategoryLocal(MobCategory.MONSTER, ChunkPos.containing(pos));
        if (!belowCap || !canSpawn) {
            cir.setReturnValue(false);
        }
    }
}