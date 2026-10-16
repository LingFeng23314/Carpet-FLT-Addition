package com.flt.carpetaddition.command;

import com.flt.carpetaddition.storage.ContainerGroup;
import com.flt.carpetaddition.storage.StockPersistence;
import com.flt.carpetaddition.storage.StockScanner;
import com.flt.carpetaddition.storage.StockSource;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * /Itemfetcher stock 命令（组合拳二期）：管理"自动备货的库存源"（哪些位置可以让假人去取货）。
 *
 * <p>用法（add/remove 既可准星定位、也可直接给坐标）：
 * <pre>
 * /Itemfetcher stock add                       把准星指向的位置加入库存源
 * /Itemfetcher stock add &lt;pos&gt;                 把指定坐标加入库存源（可以是"当前不是容器"的位置）
 * /Itemfetcher stock remove                    从库存源移除准星指向的位置
 * /Itemfetcher stock remove &lt;pos&gt;              从库存源移除指定坐标
 * /Itemfetcher stock addarea &lt;pos1&gt; &lt;pos2&gt;    区域扫描：把该范围内已加载区块里的容器批量登记
 * /Itemfetcher stock list                      查看库存源数量 + 扫描出的物品统计（前若干项）
 * /Itemfetcher stock clear                     清空库存源
 * </pre>
 *
 * <p><b>可以登记"当前不是容器"的位置</b>（2026-10-16）：全物品等机器的取货口是"有货才摆出潜影盒"
 * 的 —— 空盒/无货时那一格是空气，但它位置固定、以后会有盒子。这类位置必须也能登记，否则以后
 * 有货了假人也不知道去取（用户报障："仓库明明有该物品却取不到"）。这类登记会被自动「钉住」
 * （不参与失效自检清除），取货时现场校验：那格当下没容器就跳过。
 *
 * <p>自动备货只会从这些已登记的位置里取货，避免假人到处乱翻箱子。
 * 库存源会<b>持久化</b>到 {@code <游戏目录>/config/carpet-flt-addition/<世界名>/carpet-flt-stock.json}
 * （刻意放在 world 目录之外，避免被"整体回档 world"的备份插件连带覆盖），服务器重启自动恢复；
 * 旧版保存在世界根 / serverconfig 的文件会自动迁移过来。
 */
public final class StockCommand {
    private static final SimpleCommandExceptionType ERROR_NO_BLOCK = new SimpleCommandExceptionType(
            Component.literal("准星没有指向任何方块；请对准目标，或改用坐标形式：/Itemfetcher stock add <x> <y> <z>"));
    private static final SimpleCommandExceptionType ERROR_AREA_TOO_LARGE = new SimpleCommandExceptionType(
            Component.literal("选区太大（超过 " + StockScanner.MAX_AREA_CHUNKS + " 个区块），请缩小范围分几次登记"));

    private StockCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("Itemfetcher")
                .then(Commands.literal("stock")
                        .then(Commands.literal("add")
                                .executes(StockCommand::add)                                  // 准星定位
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(StockCommand::addAt)))                      // 指定坐标
                        .then(Commands.literal("remove")
                                .executes(StockCommand::remove)
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(StockCommand::removeAt)))
                        .then(Commands.literal("list").executes(StockCommand::list))
                        .then(Commands.literal("clear").executes(StockCommand::clear))
                        .then(Commands.literal("addarea")
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                .executes(StockCommand::addArea))))));
    }

    /** {@code /Itemfetcher stock add} —— 登记准星指向的位置（容器 / 当前为空的位置均可）。 */
    private static int add(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        return register(level, aimedPos(player), context);
    }

    /** {@code /Itemfetcher stock add <pos>} —— 登记指定坐标（不要求该处是容器）。 */
    private static int addAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        return register(level, BlockPosArgument.getBlockPos(context, "pos").immutable(), context);
    }

    /**
     * 把一个位置登记进库存源。
     *
     * <p>两种情况：
     * <ul>
     *   <li><b>该位置当前是容器</b> → 按容器登记（双格大容器把整组 half 都登记）；若方块是潜影盒，
     *       一并「钉住」（不随方块消失自动清除）。</li>
     *   <li><b>该位置当前不是容器</b>（全物品「有货才摆出来」的展示位）→ <b>仍然登记</b>并「钉住」——
     *       否则下一次失效自检会立刻把它清掉（空气不是容器）。盒子摆出来后假人即可取货。</li>
     * </ul>
     */
    private static int register(ServerLevel level, BlockPos pos, CommandContext<CommandSourceStack> context) {
        boolean isContainer = level.getBlockEntity(pos) instanceof Container;
        boolean pinned = false;
        int added = 0;
        int groupSize = 1;

        if (isContainer) {
            // 双格大容器（双箱/陷阱大箱/TIS 大木桶）：把整组 two half 都登记，避免只登记一半导致统计缺一半。
            // 潜影盒属"可搬运容器"：登记时就记下身份（见 StockSource.PINNED），之后不参与自动清除 ——
            // 因为方块一旦被挖掉就认不出来了，必须提前记。
            List<BlockPos> group = ContainerGroup.groupOf(level, pos);
            groupSize = group.size();
            pinned = ContainerGroup.isPortableContainer(level.getBlockState(pos));
            for (BlockPos p : group) {
                if (StockSource.add(level.dimension(), p)) {
                    added++;
                }
                if (pinned) {
                    StockSource.setPinned(level.dimension(), p, true);
                }
            }
        } else {
            // 非容器 → 作为「预登记展示位」登记。必须「钉住」：空气会被失效自检当成"失效登记"清掉。
            if (StockSource.add(level.dimension(), pos)) {
                added++;
            }
            StockSource.setPinned(level.dimension(), pos, true);
            pinned = true;
        }

        if (added > 0 || pinned) {
            StockPersistence.save(context.getSource().getServer());
        }
        // lambda 里只能读 effectively final：先把可变值快照成 final
        final int addedFinal = added;
        final int groupSizeFinal = groupSize;
        final boolean pinnedFinal = pinned;
        final boolean containerFinal = isContainer;
        final int total = StockSource.size();
        context.getSource().sendSuccess(() -> Component.literal("[FLT] ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(addedFinal > 0 ? "已加入库存源 " : "该位置已在库存源中 ")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(posText(pos)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(groupSizeFinal > 1 ? "（双格大容器，" + groupSizeFinal + " half）" : "")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(!containerFinal
                                ? "（当前不是容器，已按「展示位」登记；盒子出现后即可取货）"
                                : (pinnedFinal ? "（潜影盒，登记不随方块消失自动清除）" : ""))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("（当前共 " + total + " 个位置）").withStyle(ChatFormatting.GREEN)),
                false);
        return added > 0 ? 1 : 0;
    }

    /** {@code /Itemfetcher stock remove} —— 移除准星指向的位置。 */
    private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        return unregister(level, aimedPos(player), context);
    }

    /** {@code /Itemfetcher stock remove <pos>} —— 移除指定坐标。 */
    private static int removeAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        return unregister(level, BlockPosArgument.getBlockPos(context, "pos").immutable(), context);
    }

    private static int unregister(ServerLevel level, BlockPos pos, CommandContext<CommandSourceStack> context) {
        boolean removed = StockSource.remove(level.dimension(), pos);
        if (removed) {
            StockPersistence.save(context.getSource().getServer());
        }
        context.getSource().sendSuccess(() -> Component.literal("[FLT] ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(removed ? "已从库存源移除 " : "该位置不在库存源中 ")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(posText(pos)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("（当前共 " + StockSource.size() + " 个位置）").withStyle(ChatFormatting.GREEN)),
                false);
        return removed ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        int count = StockSource.size();
        Map<Identifier, Integer> scanned = StockScanner.scanAll(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("[FLT] 库存源共 ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(count + " 个位置，扫描到 " + scanned.size() + " 种物品")
                        .withStyle(ChatFormatting.AQUA)), false);

        // 只展示数量最多的前 12 项，避免刷屏
        List<Map.Entry<Identifier, Integer>> top = new ArrayList<>(scanned.entrySet());
        top.sort(Comparator.comparingInt((Map.Entry<Identifier, Integer> e) -> e.getValue()).reversed());
        for (int i = 0; i < Math.min(12, top.size()); i++) {
            Map.Entry<Identifier, Integer> entry = top.get(i);
            context.getSource().sendSuccess(() -> Component.literal("  - ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(entry.getKey().toString()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" × " + entry.getValue()).withStyle(ChatFormatting.GRAY)), false);
        }
        return count;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        int before = StockSource.size();
        StockSource.clear();
        StockPersistence.save(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("[FLT] 已清空库存源（原 " + before + " 个位置）")
                .withStyle(ChatFormatting.GREEN), false);
        return before;
    }

    /**
     * 区域扫描登记：把两点范围内<b>已加载区块</b>里的容器批量加入库存源。
     * 选区超限（区块数 &gt; {@link StockScanner#MAX_AREA_CHUNKS}）时拒绝，避免一次扫太大卡服。
     */
    private static int addArea(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        // getLoadedBlockPos 要求坐标所在区块已加载，正好符合"不强制加载"的意图
        BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "pos1");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "pos2");

        int chunks = StockScanner.areaChunkCount(from, to);
        if (chunks > StockScanner.MAX_AREA_CHUNKS) {
            throw ERROR_AREA_TOO_LARGE.create();
        }

        int added = StockScanner.addAreaToStock(level, from, to);
        if (added > 0) {
            StockPersistence.save(context.getSource().getServer());
        }
        int total = StockSource.size();
        context.getSource().sendSuccess(() -> Component.literal("[FLT] 区域扫描完成：新登记 ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(String.valueOf(added)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" 个容器（跨 " + chunks + " 区块，未加载区块已跳过）")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal("，当前共 " + total + " 个").withStyle(ChatFormatting.GREEN)), false);
        return added;
    }

    /**
     * 准星定位"要操作的位置"。
     *
     * <p>规则：
     * <ul>
     *   <li>准星命中<b>容器</b> → 返回该容器坐标；</li>
     *   <li>准星命中<b>非容器方块</b> → 返回<b>命中面外侧那一格</b>
     *       （{@code hitPos.relative(hitResult.getDirection())}，即"你正对着的那格空气"）。
     *       这正是全物品"有货才摆出来的展示位"的典型位置 —— 展示格通常紧贴某个方块（墙 / 架子 /
     *       地板 / 机器结构），所以"你正对着的那格"就是展示格本身，从而做到"当下没盒子也能登记"。</li>
     * </ul>
     *
     * <p>准星完全没指到方块（射程内无方块）时无法可靠定位 → 抛错，提示改用坐标形式。
     */
    private static BlockPos aimedPos(ServerPlayer player) throws CommandSyntaxException {
        HitResult hitResult = player.pick(6.0D, 0.0F, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult)
                || blockHitResult.getType() != HitResult.Type.BLOCK) {
            throw ERROR_NO_BLOCK.create();
        }
        BlockPos hitPos = blockHitResult.getBlockPos();
        if (player.level().getBlockEntity(hitPos) instanceof Container) {
            return hitPos.immutable();
        }
        // 命中非容器 → 取"命中面外侧那格"（你正对着的那格），让"对着空展示位"也能登记
        return hitPos.relative(blockHitResult.getDirection()).immutable();
    }

    private static String posText(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
