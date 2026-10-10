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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * /Itemfetcher stock 命令（组合拳二期）：管理"自动备货的库存源"（哪些箱子可以让假人去取货）。
 *
 * <p>用法（add/remove 以准星指向为准；addarea 用两点坐标）：
 * <pre>
 * /Itemfetcher stock add                       把准星指向的容器加入库存源
 * /Itemfetcher stock remove                    从库存源移除准星指向的容器
 * /Itemfetcher stock addarea &lt;pos1&gt; &lt;pos2&gt;    区域扫描：把该范围内已加载区块里的容器批量登记
 * /Itemfetcher stock list                      查看库存源数量 + 扫描出的物品统计（前若干项）
 * /Itemfetcher stock clear                     清空库存源
 * </pre>
 * 自动备货只会从这些已登记的容器里取货，避免假人到处乱翻箱子。
 * 库存源会<b>持久化</b>到 {@code <world>/carpet-flt-stock.json}，服务器重启自动恢复。
 */
public final class StockCommand {
    private static final SimpleCommandExceptionType ERROR_NOT_CONTAINER = new SimpleCommandExceptionType(
            Component.literal("请把准星对准一个容器（箱子/木桶/潜影盒等）再执行该命令"));
    private static final SimpleCommandExceptionType ERROR_AREA_TOO_LARGE = new SimpleCommandExceptionType(
            Component.literal("选区太大（超过 " + StockScanner.MAX_AREA_CHUNKS + " 个区块），请缩小范围分几次登记"));

    private StockCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("Itemfetcher")
                .then(Commands.literal("stock")
                        .then(Commands.literal("add").executes(StockCommand::add))
                        .then(Commands.literal("remove").executes(StockCommand::remove))
                        .then(Commands.literal("list").executes(StockCommand::list))
                        .then(Commands.literal("clear").executes(StockCommand::clear))
                        .then(Commands.literal("addarea")
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                .executes(StockCommand::addArea))))));
    }

    private static int add(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos pos = targetContainer(player);
        ServerLevel level = (ServerLevel) player.level();

        // 双格大容器（双箱/陷阱大箱）：把整组两个 half 都登记，避免只登记一半导致统计缺一半。
        int added = 0;
        List<BlockPos> group = ContainerGroup.groupOf(level, pos);
        for (BlockPos p : group) {
            if (StockSource.add(level.dimension(), p)) {
                added++;
            }
        }
        if (added > 0) {
            StockPersistence.save(context.getSource().getServer());
        }
        // lambda 里只能读 effectively final：先把可变值快照成 final
        final int addedFinal = added;
        final int groupSize = group.size();
        final int total = StockSource.size();
        context.getSource().sendSuccess(() -> Component.literal("[FLT] ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(addedFinal > 0 ? "已加入库存源 " : "该容器已在库存源中 ")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(posText(pos)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(groupSize > 1 ? "（双格大容器，" + groupSize + " half）" : "")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("（当前共 " + total + " 个容器）").withStyle(ChatFormatting.GREEN)),
                false);
        return added > 0 ? 1 : 0;
    }

    private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos pos = targetContainer(player);
        ServerLevel level = (ServerLevel) player.level();
        boolean removed = StockSource.remove(level.dimension(), pos);
        if (removed) {
            StockPersistence.save(context.getSource().getServer());
        }
        context.getSource().sendSuccess(() -> Component.literal("[FLT] ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(removed ? "已从库存源移除 " : "该容器不在库存源中 ")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(posText(pos)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("（当前共 " + StockSource.size() + " 个容器）").withStyle(ChatFormatting.GREEN)),
                false);
        return removed ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        int count = StockSource.size();
        Map<Identifier, Integer> scanned = StockScanner.scanAll(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("[FLT] 库存源共 ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(count + " 个容器，扫描到 " + scanned.size() + " 种物品")
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
        context.getSource().sendSuccess(() -> Component.literal("[FLT] 已清空库存源（原 " + before + " 个容器）")
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

    /** 取准星指向的容器坐标，不是容器则报错 */
    private static BlockPos targetContainer(ServerPlayer player) throws CommandSyntaxException {
        HitResult hitResult = player.pick(6.0D, 0.0F, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            throw ERROR_NOT_CONTAINER.create();
        }
        BlockPos pos = blockHitResult.getBlockPos();
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof Container)) {
            throw ERROR_NOT_CONTAINER.create();
        }
        return pos.immutable();
    }

    private static String posText(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}