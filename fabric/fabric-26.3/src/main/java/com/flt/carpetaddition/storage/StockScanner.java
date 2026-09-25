package com.flt.carpetaddition.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * 库存扫描器（组合拳二期）：把 {@link StockSource} 登记的容器内容聚合成 {@code 物品ID → 总数}。
 *
 * <p>每个物品堆的计数统一走 {@link StackCounter}：潜影盒里的物品<b>仅当盒内是同一种</b>时才展开计入，
 * <b>混装盒不计入</b>——保证"统计到的 = 假人能整槽取出来的"。
 *
 * <p>用途：自动备货调度器算"箱子还有多少货"，决定缺口能否从库存里取。
 */
public final class StockScanner {
    private StockScanner() {
    }

    /**
     * 扫描全部已登记容器（跨维度），聚合成 {@code 物品ID → 总数}。
     * 维度已卸载（getLevel 返回 null）时跳过，不报错。
     *
     * <p>对大容器（双箱）用 {@link ContainerGroup#canonical} 归一去重、并取合并容器
     * {@link ContainerGroup#fullContainerFor} 只统计一次完整内容——否则两个 half 会重复或缺一半。
     */
    public static Map<Identifier, Integer> scanAll(MinecraftServer server) {
        Map<Identifier, Integer> result = new HashMap<>();
        java.util.Set<BlockPos> seenCanonical = new java.util.HashSet<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            for (BlockPos pos : entry.getValue()) {
                BlockPos canonical = ContainerGroup.canonical(level, pos);
                if (!seenCanonical.add(canonical)) {
                    continue;   // 同组双箱已扫过（两个 half 归一）
                }
                Container container = ContainerGroup.fullContainerFor(level, canonical);
                if (container != null) {
                    scanInto(container, result);
                }
            }
        }
        return result;
    }

    /** 聚合单个容器的内容到 out（潜影盒递归展开一层） */
    public static void scanInto(Container container, Map<Identifier, Integer> out) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            merge(out, container.getItem(i));
        }
    }

    /** 把单个物品堆计入统计（走 StackCounter 统一规则：混装潜影盒不计入） */
    private static void merge(Map<Identifier, Integer> out, ItemStack stack) {
        StackCounter.Count count = StackCounter.count(stack);
        if (count == null) {
            return;
        }
        out.merge(count.itemId(), count.count(), Integer::sum);
    }

    /**
     * 扫描全部已登记容器，统计 {@code 物品ID → 盒数}：该物品在仓库里有<b>几盒</b>单一内容潜影盒。
     *
     * <p>与 {@link #scanAll} 的区别：scanAll 统计的是"总量"（散装 + 盒内展开），本方法只数盒子本身，
     * 供客户端「取整盒」判断该物品到底有没有盒装库存 —— 有盒才值得走整盒取（否则退散装）。
     *
     * <p>判定规则与 {@link StackCounter} 一致：只认"非空 + 盒内单一物品"的潜影盒；
     * 空盒（盒内无物）与混装盒都不计入（整盒取它们没有意义）。
     */
    public static Map<Identifier, Integer> scanAllBoxes(MinecraftServer server) {
        Map<Identifier, Integer> result = new HashMap<>();
        java.util.Set<BlockPos> seenCanonical = new java.util.HashSet<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            for (BlockPos pos : entry.getValue()) {
                BlockPos canonical = ContainerGroup.canonical(level, pos);
                if (!seenCanonical.add(canonical)) {
                    continue;   // 同组双箱只数一次
                }
                Container container = ContainerGroup.fullContainerFor(level, canonical);
                if (container != null) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        mergeBox(result, container.getItem(i));
                    }
                }
            }
        }
        return result;
    }

    /** 单个物品堆若是"单一内容潜影盒"，把盒数计入 out */
    private static void mergeBox(Map<Identifier, Integer> out, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(net.minecraft.tags.ItemTags.SHULKER_BOXES)) {
            return;
        }
        StackCounter.Count count = StackCounter.count(stack);
        if (count == null) {
            return;   // 混装盒
        }
        // 空盒展开后 itemId = 盒本身 → 不属于"装有某物品的盒"，跳过
        Identifier selfId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (count.itemId() == null || count.itemId().equals(selfId)) {
            return;
        }
        out.merge(count.itemId(), stack.getCount(), Integer::sum);
    }

    /** 一个"有货的容器"：所在世界 + 坐标 + 该物品数量（含潜影盒内展开） */
    public record LocatedContainer(ServerLevel level, BlockPos pos, int count) {
    }

    /**
     * 找一个含有指定物品的已登记容器（跨维度顺序遍历，返回第一个）。
     * 自动备货用它决定"该派假人去哪个箱子取货"。
     */
    public static java.util.Optional<LocatedContainer> findContainerWith(MinecraftServer server, Identifier itemId) {
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            for (BlockPos pos : entry.getValue()) {
                if (!(level.getBlockEntity(pos) instanceof Container container)) {
                    continue;
                }
                int count = countOf(container, itemId);
                if (count > 0) {
                    return java.util.Optional.of(new LocatedContainer(level, pos, count));
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** 单个容器里某物品的可提取数量（走 StackCounter 统一规则） */
    private static int countOf(Container container, Identifier itemId) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            StackCounter.Count count = StackCounter.count(container.getItem(i));
            if (count != null && itemId.equals(count.itemId())) {
                total += count.count();
            }
        }
        return total;
    }

    /** 区域扫描允许的最大区块数（超出则拒绝执行，避免一次扫太大卡服） */
    public static final int MAX_AREA_CHUNKS = 256;

    /**
     * 区域扫描：把区域内<b>已加载区块</b>里的容器批量登记进 {@link StockSource}（A+B 方案里的 B）。
     *
     * <p>用 {@code level.getChunk(cx, cz, ChunkStatus.FULL, false)} 只取<b>已加载</b>的区块——
     * 第四参 false 表示不强制加载/生成，避免命令一执行就拖动一大片区块导致卡服；
     * 未加载的区块直接跳过（由调用方提示玩家）。
     *
     * @return 本次<b>新登记</b>的容器数量（已登记过的不会重复计数）
     */
    public static int addAreaToStock(ServerLevel level, BlockPos from, BlockPos to) {
        int minX = Math.min(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX());
        int maxZ = Math.max(from.getZ(), to.getZ());

        int added = 0;
        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk levelChunk)) {
                    continue;   // 未加载 → 跳过，不强制加载
                }
                for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                    if (levelChunk.getBlockEntity(pos) instanceof Container
                            && StockSource.add(level.dimension(), pos)) {
                        added++;
                    }
                }
            }
        }
        return added;
    }

    /** 区域跨越的区块数（用于命令侧做"太大就拒绝"的校验） */
    public static int areaChunkCount(BlockPos from, BlockPos to) {
        int width = (Math.abs(from.getX() - to.getX()) >> 4) + 1;
        int depth = (Math.abs(from.getZ() - to.getZ()) >> 4) + 1;
        return width * depth;
    }
}