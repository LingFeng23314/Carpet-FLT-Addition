package com.flt.carpetaddition.mixin.spawn;

//# [VERSION] PatrolSpawner 类 1.18.2+ 才存在（1.16.5/1.17.1 无 PatrolSpawner）→ 整个 Mixin 裁剪到 >= fabric-1.18.2
// IF >= fabric-1.18.2
import net.minecraft.world.spawner.PatrolSpawner;
// END IF
import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
//# [VERSION] Random 类型分界 fabric-1.19.4：1.19.4+ net.minecraft.util.math.random.Random；1.18.2- java.util.Random
// IF >= fabric-1.19.4
import net.minecraft.util.math.random.Random;
// ELSE IF <= fabric-1.18.2
//import java.util.Random;
// ELSE
//#    （理论不可达）
// END IF
import net.minecraft.world.SpawnHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 限制巡逻队生成（limitPillagerPatrolSpawn）。
 * 注入 PatrolSpawner.spawnPillager HEAD：读 SpawnHelper.Info（原版每 tick 算好的刷怪统计）
 * 检查怪物类别上限，setReturnValue(false) 阻止生成。参考 ORG 的 limitPhantomSpawn。
 */
// IF >= fabric-1.18.2
@Mixin(PatrolSpawner.class)
public class PatrolSpawnerMixin {

    @Inject(method = "spawnPillager", at = @At("HEAD"), cancellable = true)
    private void flt$limitPillagerPatrolSpawn(ServerWorld world, BlockPos pos, Random random, boolean leader,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!FLTSettings.limitPillagerPatrolSpawn) {
            return;
        }
        SpawnHelper.Info info = world.getChunkManager().getSpawnInfo();
        if (info == null) {
            return;  // 刷怪统计尚未计算（世界刚启动），放行
        }
        SpawnHelperInfoAccessor accessor = (SpawnHelperInfoAccessor) info;
//        // [VERSION] 1.21.2+ 分 isBelowCap + canSpawn 两方法；1.21~1.18.2 只有 isBelowCap 单方法
 // IF >= fabric-1.21.2
        boolean belowCap = accessor.flt$invokeIsBelowCap(SpawnGroup.MONSTER);
        boolean canSpawn = accessor.flt$invokeCanSpawn(SpawnGroup.MONSTER, new ChunkPos(pos));
        if (!belowCap || !canSpawn) {
            cir.setReturnValue(false);
        }
 // ELSE IF <= fabric-1.21.1
//        boolean belowCap = accessor.flt$invokeIsBelowCap(SpawnGroup.MONSTER, new ChunkPos(pos));
//        if (!belowCap) {
//            cir.setReturnValue(false);
//        }
 // ELSE
 //            （理论不可达）
 // END IF
    }
}
// END IF