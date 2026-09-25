package com.flt.carpetaddition.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 库存源：登记"哪些容器属于可用的取货来源"（组合拳二期）。
 *
 * <p>玩家用 {@code /flt stock add} 把准星指向的容器加入库存源（按维度分组）。
 * 自动备货调度器只从这些容器里取货，避免假人到处乱翻箱子。
 *
 * <p>线程安全：只在服务端主线程读写。
 */
public final class StockSource {
    /** 维度 → 容器坐标集合 */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> CONTAINERS = new HashMap<>();

    private StockSource() {
    }

    /** 登记一个容器（重复添加幂等）。返回 true = 本次新增 */
    public static boolean add(ResourceKey<Level> dimension, BlockPos pos) {
        return CONTAINERS.computeIfAbsent(dimension, key -> new HashSet<>()).add(pos.immutable());
    }

    /** 移除一个容器。返回 true = 之前存在并已移除 */
    public static boolean remove(ResourceKey<Level> dimension, BlockPos pos) {
        Set<BlockPos> set = CONTAINERS.get(dimension);
        return set != null && set.remove(pos);
    }

    /** 某维度已登记的容器（不可变视图） */
    public static Set<BlockPos> get(ResourceKey<Level> dimension) {
        return Collections.unmodifiableSet(CONTAINERS.getOrDefault(dimension, Set.of()));
    }

    /** 全部登记（维度 → 容器集合） */
    public static Map<ResourceKey<Level>, Set<BlockPos>> all() {
        return Collections.unmodifiableMap(CONTAINERS);
    }

    /** 已登记的容器总数（跨维度） */
    public static int size() {
        int total = 0;
        for (Set<BlockPos> set : CONTAINERS.values()) {
            total += set.size();
        }
        return total;
    }

    public static void clear() {
        CONTAINERS.clear();
    }
}