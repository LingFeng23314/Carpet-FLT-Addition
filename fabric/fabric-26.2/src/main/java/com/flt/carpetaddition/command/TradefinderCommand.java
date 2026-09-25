package com.flt.carpetaddition.command;

import carpet.patches.EntityPlayerMPFake;
import com.flt.carpetaddition.fakeplayer.FakePlayerActionScheduler;
import com.flt.carpetaddition.fakeplayer.FakePlayerUtils;
import com.flt.carpetaddition.fakeplayer.LibrarianTradeFindAction;
import com.flt.carpetaddition.fakeplayer.LibrarianTradeForceAction;
import com.flt.carpetaddition.settings.FLTSettings;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * /Tradefinder 命令：用假人在讲台上刷图书管理员的附魔书交易。
 * 用法：
 *   /Tradefinder select <bot>                        看着讲台 → 绑定为该假人专用
 *   /Tradefinder <bot> <附魔> <等级> <价格>           模式由规则 villagerTradeRefresh 决定（vanilla/force）
 *   /Tradefinder stop <bot>                          停止任务
 * 等级 1 = 任意；价格 = 附魔书绿宝石成本上限。
 * 每个假人可绑定各自独立的讲台（BOT_LECTERNS 按假人名存储）。
 * 不强制 OP 权限（26.x 移除了 hasPermission(int)；作为服务端工具命令）。
 */
public final class TradefinderCommand {
    /** 假人绑定的讲台（位置 + 所在维度），按假人名存储 */
    private record SelectedLectern(BlockPos pos, ResourceKey<Level> dimension) {
    }

    private static final Map<String, SelectedLectern> BOT_LECTERNS = new HashMap<>();

    private static final SimpleCommandExceptionType ERROR_NOT_LECTERN = new SimpleCommandExceptionType(
            Component.literal("请先把准星对准讲台，再执行 /Tradefinder select <bot>"));
    private static final SimpleCommandExceptionType ERROR_NOT_SELECTED = new SimpleCommandExceptionType(
            Component.literal("该假人还没有绑定讲台，请先看着讲台执行 /Tradefinder select <bot>"));
    private static final SimpleCommandExceptionType ERROR_RULE_OFF = new SimpleCommandExceptionType(
            Component.literal("规则 villagerTradeRefresh 未开启（/carpet villagerTradeRefresh vanilla 或 force）"));

    private TradefinderCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("Tradefinder")
                .then(Commands.literal("select")
                        .then(Commands.argument("bot", StringArgumentType.word())
                                .executes(TradefinderCommand::select)))
                .then(Commands.literal("stop")
                        .then(Commands.argument("bot", StringArgumentType.word())
                                .executes(TradefinderCommand::stop)))
                .then(Commands.argument("bot", StringArgumentType.word())
                        .then(Commands.argument("enchantment",
                                        ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 255))
                                        .then(Commands.argument("price", IntegerArgumentType.integer(1, 64))
                                                .executes(TradefinderCommand::start))))));
    }

    /** 看着讲台，把该位置绑定给指定假人 */
    private static int select(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String botName = StringArgumentType.getString(context, "bot");
        HitResult hitResult = player.pick(6.0D, 0.0F, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult)
                || !(player.level().getBlockState(blockHitResult.getBlockPos()).getBlock() instanceof LecternBlock)) {
            throw ERROR_NOT_LECTERN.create();
        }
        BlockPos pos = blockHitResult.getBlockPos().immutable();
        BOT_LECTERNS.put(botName, new SelectedLectern(pos, player.level().dimension()));
        context.getSource().sendSuccess(() -> Component.literal("已为假人 ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(botName).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" 绑定讲台 ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(posText(pos)).withStyle(ChatFormatting.AQUA)), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String botName = StringArgumentType.getString(context, "bot");
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(context.getSource().getServer(), botName);
        if (bot == null) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "找不到名为 " + botName + " 的 carpet 假人（/player " + botName + " spawn 可以先创建）")).create();
        }
        boolean wasRunning = FakePlayerActionScheduler.isRunning(bot);
        FakePlayerActionScheduler.stop(bot);
        context.getSource().sendSuccess(() -> Component.literal(wasRunning
                ? "已停止 " + botName + " 的刷取任务"
                : botName + " 当前没有正在执行的刷取任务").withStyle(ChatFormatting.GREEN), false);
        return wasRunning ? 1 : 0;
    }

    private static int start(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // 模式由规则 villagerTradeRefresh 三态决定：false=关；vanilla=原版；force=直接刷新
        String mode = FLTSettings.villagerTradeRefresh;
        if ("false".equals(mode)) {
            throw ERROR_RULE_OFF.create();
        }
        boolean force = "force".equals(mode);

        String botName = StringArgumentType.getString(context, "bot");
        SelectedLectern selected = BOT_LECTERNS.get(botName);
        if (selected == null) {
            throw ERROR_NOT_SELECTED.create();
        }

        Holder.Reference<Enchantment> enchantment = ResourceArgument.getEnchantment(context, "enchantment");
        int level = IntegerArgumentType.getInteger(context, "level");
        int price = IntegerArgumentType.getInteger(context, "price");

        MinecraftServer server = context.getSource().getServer();
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, botName);
        if (bot == null) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "找不到名为 " + botName + " 的 carpet 假人（/player " + botName + " spawn 可以先创建）")).create();
        }
        if (FakePlayerActionScheduler.isRunning(bot)) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "假人 " + botName + " 已经在执行任务了，先执行 /Tradefinder stop " + botName)).create();
        }
        if (!bot.level().dimension().equals(selected.dimension())) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "假人 " + botName + " 与绑定的讲台不在同一个维度")).create();
        }

        FakePlayerActionScheduler.start(bot, force
                ? new LibrarianTradeForceAction(bot, selected.pos(), enchantment, level, price, context.getSource())
                : new LibrarianTradeFindAction(bot, selected.pos(), enchantment, level, price, context.getSource()));

        context.getSource().sendSuccess(() -> Component.literal("开始刷取（" + (force ? "直接刷新" : "原版拆放") + "）：假人 ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(botName).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" 在讲台 ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(posText(selected.pos())).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" 刷 ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(enchantment.value().description().getString()
                        + "（等级≥" + level + "，价格≤" + price + "）").withStyle(ChatFormatting.YELLOW)), false);
        return 1;
    }

    private static String posText(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
