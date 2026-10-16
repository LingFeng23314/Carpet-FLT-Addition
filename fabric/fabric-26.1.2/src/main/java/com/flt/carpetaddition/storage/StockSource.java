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
 * <p>玩家用 {@code /Itemfetcher stock add} 把位置加入库存源（按维度分组）。
 * 自动备货调度器只从这些位置取货，避免假人到处乱翻箱子。
 *
 * <p><b>「钉住的登记」{@link #PINNED}</b> —— 这批登记<b>不参与“失效自动清除”</b>。两类来源：
 * <ul>
 *   <li><b>可搬运容器（潜影盒）</b>：可被挖起、掉落成物品、再放回原处。挖掉那一刻若清了登记，
 *       玩家放回去就得重新登记一遍。</li>
 *   <li><b>预登记展示位</b>：全物品等机器的取货口 —— 潜影盒<b>有货才摆出来</b>（空盒/无货时那一格
 *       是空气），但位置是固定的。这类位置必须能在“当下没盒子”时也登记进去，否则以后有货了假人
 *       也不知道去取（2026-10-16 用户报障：“仓库明明有该物品却取不到”）。</li>
 * </ul>
 * 两者共性都是「<b>位置固定、但方块会时有时无</b>」—— 所以统一钉住、不参与自检清除；取货时另有
 * 现场校验：那格当下真没容器就跳过（不报错、不白跑）。
 *
 * <p>线程安全：只在服务端主线程读写。
 */
public final class StockSource {
    /** 维度 → 容器坐标集合 */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> CONTAINERS = new HashMap<>();

    /**
     * 维度 → “钉住的登记”坐标集合（{@link #CONTAINERS} 的子集）。
     * 这批登记<b>不参与自动清除</b>（潜影盒可挖起再放回；展示位有货才有盒子）。见类注释。
     */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> PINNED = new HashMap<>();

    private StockSource() {
    }

    /** 登记一个位置（重复添加幂等）。返回 true = 本次新增 */
    public static boolean add(ResourceKey<Level> dimension, BlockPos pos) {
        return CONTAINERS.computeIfAbsent(dimension, key -> new HashSet<>()).add(pos.immutable());
    }

    /** 把某个已登记坐标标记为 / 取消「钉住」（不参与自动清除）。仅对已登记坐标有意义。 */
    public static void setPinned(ResourceKey<Level> dimension, BlockPos pos, boolean pinned) {
        Set<BlockPos> set = PINNED.computeIfAbsent(dimension, key -> new HashSet<>());
        if (pinned) {
            set.add(pos.immutable());
        } else {
            set.remove(pos);
        }
    }

    /**
     * 该登记是否被「钉住」（不参与自动清除）—— 是则所有失效自检都必须跳过它。
     * 两类来源：可搬运容器（潜影盒）/ 预登记展示位。
     */
    public static boolean isPinned(ResourceKey<Level> dimension, BlockPos pos) {
        Set<BlockPos> set = PINNED.get(dimension);
        return set != null && set.contains(pos);
    }

    /** 移除一个位置。返回 true = 之前存在并已移除 */
    public static boolean remove(ResourceKey<Level> dimension, BlockPos pos) {
        Set<BlockPos> pinned = PINNED.get(dimension);
        if (pinned != null) {
            pinned.remove(pos);   // 摘登记时一并摘掉“钉住”标记，避免标记孤儿化
        }
        Set<BlockPos> set = CONTAINERS.get(dimension);
        return set != null && set.remove(pos);
    }

    /** 某维度已登记的位置（不可变视图） */
    public static Set<BlockPos> get(ResourceKey<Level> dimension) {
        return Collections.unmodifiableSet(CONTAINERS.getOrDefault(dimension, Set.of()));
    }

    /** 全部登记（维度 → 位置集合） */
    public static Map<ResourceKey<Level>, Set<BlockPos>> all() {
        return Collections.unmodifiableMap(CONTAINERS);
    }

    /** 全部「钉住」标记（维度 → 位置集合），供持久化 */
    public static Map<ResourceKey<Level>, Set<BlockPos>> pinnedAll() {
        return Collections.unmodifiableMap(PINNED);
    }

    /** 已登记的位置总数（跨维度） */
    public static int size() {
        int total = 0;
        for (Set<BlockPos> set : CONTAINERS.values()) {
            total += set.size();
        }
        return total;
    }

    public static void clear() {
        CONTAINERS.clear();
        PINNED.clear();
    }
}
