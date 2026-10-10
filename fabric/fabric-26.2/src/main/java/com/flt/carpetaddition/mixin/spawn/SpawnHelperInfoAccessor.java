package com.flt.carpetaddition.mixin.spawn;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** NaturalSpawner$SpawnState 的访问器：暴露 canSpawnForCategoryGlobal/Local（原版包私有），
 *  给 PatrolSpawnerMixin 检查刷怪上限用。 */
@Mixin(NaturalSpawner.SpawnState.class)
public interface SpawnHelperInfoAccessor {

    /** 指定刷怪类别是否未达刷怪上限（全局） */
    @Invoker("canSpawnForCategoryGlobal")
    boolean flt$invokeCanSpawnForCategoryGlobal(MobCategory category);

    /** 指定类别在给定区块是否还能生成（局部） */
    @Invoker("canSpawnForCategoryLocal")
    boolean flt$invokeCanSpawnForCategoryLocal(MobCategory category, ChunkPos pos);
}