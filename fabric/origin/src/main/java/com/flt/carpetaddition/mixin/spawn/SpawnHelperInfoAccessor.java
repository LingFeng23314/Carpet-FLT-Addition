package com.flt.carpetaddition.mixin.spawn;

import net.minecraft.entity.SpawnGroup;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.SpawnHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SpawnHelper.Info 的访问器：暴露 isBelowCap / canSpawn（原版包私有），
 * 给 PatrolSpawnerMixin 检查刷怪上限用。
 * [VERSION] 1.21.2+ 分 isBelowCap + canSpawn 两方法；1.21~1.18.2 只有 isBelowCap 单方法；
 * 1.16.5/1.17.1 无 isBelowCap（空接口，mixins.json5 不注册本类）。
 */
@Mixin(SpawnHelper.Info.class)
public interface SpawnHelperInfoAccessor {

// IF >= fabric-1.21.2
//#    /** 1.21.2+：指定刷怪类别是否未达刷怪上限 */
//    @Invoker("isBelowCap")
//    boolean flt$invokeIsBelowCap(SpawnGroup group);
//
//#    /** 1.21.2+：指定类别在给定区块是否还能生成 */
//    @Invoker("canSpawn")
//    boolean flt$invokeCanSpawn(SpawnGroup group, ChunkPos pos);
// ELSE IF >= fabric-1.18.2
//#    /** 1.18.2~1.21.1：单方法签名，同时判断类别上限与区块可生成性 */
//    @Invoker("isBelowCap")
//    boolean flt$invokeIsBelowCap(SpawnGroup group, ChunkPos pos);
// ELSE
//#    （1.16.5/1.17.1：空接口）
// END IF
}