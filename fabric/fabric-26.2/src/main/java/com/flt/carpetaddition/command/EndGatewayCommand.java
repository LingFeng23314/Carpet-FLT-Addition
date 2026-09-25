package com.flt.carpetaddition.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * /Gatewayfixer 命令：断开玩家准星指向的末地折跃门的配对出口。
 * 折跃门成对后 exitPortal 指向配对回程门。本命令把该门出口清空（setExitPosition(null)），
 * 玩家下次穿越时原版会自动沿当前 endGatewayExitSearchDistance 规则的距离重新寻找空岛并重建配对。
 * 典型用途：调整了 endGatewayExitSearchDistance 后，让旧门按新距离重新成对。
 * 不强制 OP 权限（作为服务端工具命令，对齐现有命令风格）。
 */
public final class EndGatewayCommand {
    private static final SimpleCommandExceptionType ERROR_NOT_GATEWAY = new SimpleCommandExceptionType(
            Component.literal("请把准星对准一个末地折跃门（End Gateway），再执行 /Gatewayfixer"));
    private static final SimpleCommandExceptionType ERROR_NOT_END = new SimpleCommandExceptionType(
            Component.literal("该折跃门不在末地维度，只有末地的折跃门才需要重生成配对"));
    private static final SimpleCommandExceptionType ERROR_NO_BLOCK_ENTITY = new SimpleCommandExceptionType(
            Component.literal("该折跃门方块缺少方块实体，无法重置（数据异常）"));

    private EndGatewayCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("Gatewayfixer")
                .executes(EndGatewayCommand::reset));
    }

    /** 对玩家准星指向的末地折跃门执行重置配对 */
    private static int reset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        HitResult hitResult = player.pick(6.0D, 0.0F, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            throw ERROR_NOT_GATEWAY.create();
        }
        Level level = player.level();
        BlockPos pos = blockHitResult.getBlockPos().immutable();
        if (!(level.getBlockState(pos).getBlock() instanceof EndGatewayBlock)) {
            throw ERROR_NOT_GATEWAY.create();
        }
        if (!level.dimension().equals(Level.END)) {
            throw ERROR_NOT_END.create();
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TheEndGatewayBlockEntity gateway)) {
            throw ERROR_NO_BLOCK_ENTITY.create();
        }
        // 清空出口：exactTeleport=false + exitPortal=null → 下次 getPortalPosition 会沿新半径重建配对门
        gateway.setExitPosition(null, false);
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        int radius = com.flt.carpetaddition.settings.FLTSettings.endGatewayExitSearchDistance;
        context.getSource().sendSuccess(() -> Component.literal("已断开该末地折跃门的配对出口，")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal("下次穿越时将按")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" endGatewayExitSearchDistance="
                                + radius + (radius > 0 ? "" : "（0=原版1024）")).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" 的距离重新生成配对门").withStyle(ChatFormatting.GREEN)), false);
        return 1;
    }
}