package com.flt.carpetaddition.storage;

import com.flt.carpetaddition.FLTAdditionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存扫描器（组合拳二期）：把 {@link StockSource} 登记的容器内容聚合成 {@code 物品ID → 总数}。
 *
 * <p>每个物品堆的计数统一走 {@link StackCounter}：潜影盒里的物品<b>仅当盒内是同一种</b>时才展开计入，
 * <b>混装盒不计入</b>——保证"统计到的 = 假人能整槽取出来的"。
 *
 * <p>用途：自动备货调度器算"箱子还有多少货"，决定缺口能否从库存里取。
 *
 * <p>⚠️ <b>只统计"已加载区块"里的容器</b>（2026-10-12）：遍历前先用 {@link #isChunkLoaded} 判区块是否
 * 已加载，未加载的一律跳过——既符合"箱子只有被加载时才显示容量"的预期，也避免扫描把远端区块
 * <b>强制加载</b>出来（{@link ContainerGroup#canonical} 读方块状态最终走
 * {@code Level.getChunk(x,z,FULL,true)}，第 4 参 {@code true} 就是强制加载）。
 */
public final class StockScanner {
    private StockScanner() {
    }

    /**
     * 容器所在区块是否<b>已加载</b>（非强制加载探测）。
     *
     * <p>为什么必须先判它（2026-10-12 实证）：{@link ContainerGroup#canonical} /
     * {@link ContainerGroup#fullContainerFor} 都要读方块状态、取 BlockEntity，最终走到
     * {@code Level.getChunkAt} → {@code Level.getChunk(x,z,FULL,true)} —— 第 4 参 {@code true}
     * 表示<b>强制加载</b>（javap 逐层验证）。若不先判已加载，一次仓库查询就会把所有登记箱子
     * （含其它维度、含从未去过的区域）所在区块全部强制加载出来 —— 既卡顿，也不符合
     * "箱子被加载时才显示容量"的预期。
     *
     * <p>{@link net.minecraft.world.level.Level#isLoaded(net.minecraft.core.BlockPos)} 内部就是
     * {@code chunkSource.hasChunk(cx, cz)}（纯查询：不加载、不生成、不报错）。
     */
    public static boolean isChunkLoaded(net.minecraft.world.level.Level level, BlockPos pos) {
        return level.isLoaded(pos);
    }

    /**
     * 扫描全部已登记容器（跨维度），聚合成 {@code 物品ID → 总数}。
     * 维度已卸载（getLevel 返回 null）或容器所在区块未加载时跳过，不报错、不强制加载。
     *
     * <p>对大容器（双箱）用 {@link ContainerGroup#canonical} 归一去重、并取合并容器
     * {@link ContainerGroup#fullContainerFor} 只统计一次完整内容——否则两个 half 会重复或缺一半。
     *
     * <p>顺带做"失效登记自检"：某坐标所在区块已加载、却读不到任何容器（被挖掉 / 被爆炸 / 被活塞推走 /
     * 被 {@code /setblock} 换掉）→ 视为失效登记，扫描结束后统一清除并落盘（见
     * {@link #pruneStale}）。这样即使破坏事件没覆盖到（例如非玩家破坏），登记表也不会长期残留。
     */
    public static Map<Identifier, Integer> scanAll(MinecraftServer server) {
        Map<Identifier, Integer> result = new HashMap<>();
        java.util.Set<BlockPos> seenCanonical = new java.util.HashSet<>();
        // 失效登记按维度归类；收集后统一清（边遍历 StockSource 边改集合会 ConcurrentModificationException）
        Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> stale = new HashMap<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            for (BlockPos pos : entry.getValue()) {
                if (!isChunkLoaded(level, pos)) {
                    continue;   // 未加载 → 不统计（也不强制加载）；无法判定是否仍是容器，故不清登记
                }
                BlockPos canonical = ContainerGroup.canonical(level, pos);
                if (!seenCanonical.add(canonical)) {
                    continue;   // 同组双箱已扫过（两个 half 归一）
                }
                Container container = ContainerGroup.fullContainerFor(level, canonical);
                if (container != null) {
                    scanInto(container, result);
                } else if (!ContainerGroup.isContainerBlock(level, canonical)
                        && !StockSource.isPinned(entry.getKey(), pos)) {
                    // 读不到 + 方块实体也不是容器 + 不是"钉住的登记"→ 才算真的失效，待清。
                    // ⚠️ 三个条件缺一不可：
                    //   ① 只判 fullContainerFor==null 会把"箱子上方被遮挡"误判为失效（2026-10-13 误删 249 个）；
                    //   ② 潜影盒（可挖起再放回）/ 预登记展示位（有货才摆出来）的登记必须保留
                    //      （2026-10-16 用户要求）。
                    stale.computeIfAbsent(entry.getKey(), k -> new java.util.HashSet<>())
                            .add(pos.immutable());
                }
                // else：方块仍是容器（只是读不到，如被遮挡）或属可搬运容器 → 保留登记，本次不统计
            }
        }
        pruneStale(server, stale);
        return result;
    }

    /**
     * 只做"失效登记自检"、<b>不统计内容</b> —— 供上层低频 tick 调用。
     *
     * <p><b>为什么要它</b>（2026-10-16 用户报障）：玩家手动挖掉容器会被 {@code StockBreakListener}
     * 立即清掉，但 <b>活塞推走 / 爆炸 / {@code /setblock}</b> 等非玩家破坏<b>不触发</b>
     * {@code PlayerBlockBreakEvents}，只能靠"扫描自检"。而自检原先只在<b>打开取货界面 / 执行
     * stock list</b> 时跑 —— 用户可能长时间都不触发一次，失效登记就长期残留（表现为"箱子/潜影盒
     * 都没了，库存源里还留着"）。本入口让上层用低频 tick 定期跑一遍，把这类残留压到"最多几秒"。
     *
     * <p>判据与 {@link #scanAll} 完全一致：<b>区块已加载</b>且<b>方块实体已不是容器</b>才清
     * （避免把"箱子上方被遮挡"误判成失效）。
     *
     * @return 清除数量
     */
    public static int pruneInvalid(MinecraftServer server) {
        Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> stale = new HashMap<>();
        java.util.Set<BlockPos> seenCanonical = new java.util.HashSet<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> entry : StockSource.all().entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            for (BlockPos pos : entry.getValue()) {
                if (!isChunkLoaded(level, pos)) {
                    continue;   // 未加载 → 无法判定，保留登记（不清）
                }
                BlockPos canonical = ContainerGroup.canonical(level, pos);
                if (!seenCanonical.add(canonical)) {
                    continue;
                }
                if (!ContainerGroup.isContainerBlock(level, canonical)
                        && !StockSource.isPinned(entry.getKey(), pos)) {
                    // 与 scanAll 同判据：非容器 + 非"钉住的登记"才清
                    stale.computeIfAbsent(entry.getKey(), k -> new java.util.HashSet<>())
                            .add(pos.immutable());
                }
            }
        }
        return pruneStale(server, stale);
    }

    /**
     * 清除一批"已加载但已不是容器"的失效登记并落盘。
     *
     * <p>必须与遍历分开调用：{@link StockSource#all()} 是活 Map 的不可变视图，边遍历边 {@code remove}
     * 会抛 {@code ConcurrentModificationException}。
     *
     * <p>⚠️ <b>必须服务端主线程调用</b>（护栏，非优化）：{@code Level.getBlockEntity} 在非服务端主线程
     * 调用时直接返回 {@code null}（javap 实证：{@code currentThread != serverThread → return null}）。
     * 本方法是"删数据"操作，若在异步线程误判"读不到容器 = 失效"，会把全部登记清空 —— 后果不可逆。
     * 因此这里显式校验线程：非主线程一律<b>不清、只告警</b>（负反馈不静默）。
     *
     * @return 实际清除的登记数
     */
    private static int pruneStale(MinecraftServer server,
                                  Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                                          java.util.Set<BlockPos>> stale) {
        if (!server.isSameThread()) {
            FLTAdditionMod.LOGGER.warn("[FLT] 扫描自检跳过：检测到非服务端主线程调用（{}），"
                    + "为避免误清库存源登记，本次不执行清理", Thread.currentThread().getName());
            return 0;
        }
        int removed = 0;
        List<String> samples = new ArrayList<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                java.util.Set<BlockPos>> entry : stale.entrySet()) {
            for (BlockPos pos : entry.getValue()) {
                if (StockSource.remove(entry.getKey(), pos)) {
                    removed++;
                    if (samples.size() < 3) {
                        samples.add(entry.getKey().identifier() + "@(" + pos.getX() + ","
                                + pos.getY() + "," + pos.getZ() + ")");
                    }
                }
            }
        }
        if (removed > 0) {
            // 负反馈不静默：清除登记是"数据被改动"，必须留可查记录
            FLTAdditionMod.LOGGER.info("[FLT] 扫描自检：清除 {} 个已失效的容器登记（坐标已不是容器）：{}",
                    removed, String.join(", ", samples));
            StockPersistence.save(server);
        }
        return removed;
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
     *
     * <p>与 {@link #scanAll} 一致：只统计<b>已加载区块</b>内的容器，不强制加载（见 {@link #isChunkLoaded}）。
     * 本方法不清"失效登记"（那是 {@link #scanAll} 的职责，二者通常成对调用，重复清没有意义）。
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
                if (!isChunkLoaded(level, pos)) {
                    continue;   // 未加载 → 不统计（也不强制加载）
                }
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
                    if (!(levelChunk.getBlockEntity(pos) instanceof Container)) {
                        continue;
                    }
                    if (StockSource.add(level.dimension(), pos)) {
                        added++;
                    }
                    // 潜影盒属"钉住的登记"：区域登记时也要打标记（与 /Itemfetcher stock add 口径一致），
                    // 否则它们会在下次自检时被当成"失效登记"清掉。
                    if (ContainerGroup.isPortableContainer(level.getBlockState(pos))) {
                        StockSource.setPinned(level.dimension(), pos, true);
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