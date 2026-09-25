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
// IF >= fabric-1.21
import net.minecraft.command.argument.RegistryEntryReferenceArgumentType;
// ELSE IF >= fabric-1.19.4
//import net.minecraft.command.argument.RegistryEntryArgumentType;
// ELSE
//import net.minecraft.command.argument.EnchantmentArgumentType;
// END IF
// IF >= fabric-1.19.4
import net.minecraft.command.CommandRegistryAccess;
// END IF
import net.minecraft.enchantment.Enchantment;
// IF >= fabric-1.19.4
import net.minecraft.registry.RegistryKeys;
// END IF
// IF >= fabric-1.19.4
import net.minecraft.registry.entry.RegistryEntry;
// END IF
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
// IF >= fabric-1.19.4
import net.minecraft.registry.RegistryKey;
// ELSE
//import net.minecraft.util.registry.RegistryKey;
// END IF
import net.minecraft.world.World;
import net.minecraft.block.LecternBlock;

import java.util.HashMap;
import java.util.Map;

/**
 * /Tradefinder 命令：用假人在讲台上刷图书管理员的附魔书交易。
 * 用法：
 *   /Tradefinder select &lt;bot&gt;                        看着讲台 → 绑定为该假人专用
 *   /Tradefinder &lt;bot&gt; &lt;附魔&gt; &lt;等级&gt; &lt;价格&gt;           模式由规则 villagerTradeRefresh 决定（vanilla/force）
 *   /Tradefinder stop &lt;bot&gt;                          停止任务
 * 等级 1 = 任意；价格 = 附魔书绿宝石成本上限。
 * 每个假人可绑定各自独立的讲台（BOT_LECTERNS 按假人名存储）。
 * 不强制 OP 权限（作为服务端工具命令）。
 */
public final class TradefinderCommand {
    /** 假人绑定的讲台（位置 + 所在维度），按假人名存储 */
    private record SelectedLectern(BlockPos pos, RegistryKey<World> dimension) {
    }

    private static final Map<String, SelectedLectern> BOT_LECTERNS = new HashMap<>();

    private static final SimpleCommandExceptionType ERROR_NOT_LECTERN = new SimpleCommandExceptionType(
            FakePlayerUtils.literal("请先把准星对准讲台，再执行 /Tradefinder select <bot>"));
    private static final SimpleCommandExceptionType ERROR_NOT_SELECTED = new SimpleCommandExceptionType(
            FakePlayerUtils.literal("该假人还没有绑定讲台，请先看着讲台执行 /Tradefinder select <bot>"));
    private static final SimpleCommandExceptionType ERROR_RULE_OFF = new SimpleCommandExceptionType(
            FakePlayerUtils.literal("规则 villagerTradeRefresh 未开启（/carpet villagerTradeRefresh vanilla 或 force）"));

    private TradefinderCommand() {
    }

// IF >= fabric-1.19.4
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess buildContext) {
// ELSE
//    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
// END IF
        dispatcher.register(CommandManager.literal("Tradefinder")
                .then(CommandManager.literal("select")
                        .then(CommandManager.argument("bot", StringArgumentType.word())
                                .executes(TradefinderCommand::select)))
                .then(CommandManager.literal("stop")
                        .then(CommandManager.argument("bot", StringArgumentType.word())
                                .executes(TradefinderCommand::stop)))
                .then(CommandManager.argument("bot", StringArgumentType.word())
                        .then(CommandManager.argument("enchantment",
// IF >= fabric-1.21
                                        RegistryEntryReferenceArgumentType.registryEntry(buildContext, RegistryKeys.ENCHANTMENT)
// ELSE IF >= fabric-1.19.4
//                                        RegistryEntryArgumentType.registryEntry(buildContext, RegistryKeys.ENCHANTMENT)
// ELSE
//                                        EnchantmentArgumentType.enchantment()
// END IF
                                )
                                .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 255))
                                        .then(CommandManager.argument("price", IntegerArgumentType.integer(1, 64))
                                                .executes(TradefinderCommand::start))))));
    }

    /** 看着讲台，把该位置绑定给指定假人 */
    private static int select(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
// IF >= fabric-1.19.4
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
// ELSE
//        ServerPlayerEntity player = context.getSource().getPlayer();
// END IF
        String botName = StringArgumentType.getString(context, "bot");
        HitResult hitResult = player.raycast(6.0D, 0.0F, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult)
                || !(FakePlayerUtils.worldOf(player).getBlockState(blockHitResult.getBlockPos()).getBlock() instanceof LecternBlock)) {
            throw ERROR_NOT_LECTERN.create();
        }
        BlockPos pos = blockHitResult.getBlockPos().toImmutable();
        BOT_LECTERNS.put(botName, new SelectedLectern(pos, FakePlayerUtils.worldOf(player).getRegistryKey()));
        feedback(context.getSource(), FakePlayerUtils.literal("已为假人 ")
                .formatted(Formatting.GREEN)
                .append(FakePlayerUtils.literal(botName).formatted(Formatting.AQUA))
                .append(FakePlayerUtils.literal(" 绑定讲台 ").formatted(Formatting.GREEN))
                .append(FakePlayerUtils.literal(posText(pos)).formatted(Formatting.AQUA)), false);
        return 1;
    }

    private static int stop(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String botName = StringArgumentType.getString(context, "bot");
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(serverOf(context.getSource()), botName);
        if (bot == null) {
            throw new SimpleCommandExceptionType(FakePlayerUtils.literal(
                    "找不到名为 " + botName + " 的 carpet 假人（/player " + botName + " spawn 可以先创建）")).create();
        }
        boolean wasRunning = FakePlayerActionScheduler.isRunning(bot);
        FakePlayerActionScheduler.stop(bot);
        feedback(context.getSource(), FakePlayerUtils.literal(wasRunning
                ? "已停止 " + botName + " 的刷取任务"
                : botName + " 当前没有正在执行的刷取任务").formatted(Formatting.GREEN), false);
        return wasRunning ? 1 : 0;
    }

    private static int start(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
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

// IF >= fabric-1.21
        Enchantment enchantment = RegistryEntryReferenceArgumentType
                .getRegistryEntry(context, "enchantment", RegistryKeys.ENCHANTMENT).value();
// ELSE IF >= fabric-1.19.4
//        Enchantment enchantment = RegistryEntryArgumentType
//                .getRegistryEntry(context, "enchantment", RegistryKeys.ENCHANTMENT).value();
// ELSE
//        Enchantment enchantment = EnchantmentArgumentType.getEnchantment(context, "enchantment");
// END IF
        int level = IntegerArgumentType.getInteger(context, "level");
        int price = IntegerArgumentType.getInteger(context, "price");

        MinecraftServer server = serverOf(context.getSource());
        EntityPlayerMPFake bot = FakePlayerUtils.findFakePlayer(server, botName);
        if (bot == null) {
            throw new SimpleCommandExceptionType(FakePlayerUtils.literal(
                    "找不到名为 " + botName + " 的 carpet 假人（/player " + botName + " spawn 可以先创建）")).create();
        }
        if (FakePlayerActionScheduler.isRunning(bot)) {
            throw new SimpleCommandExceptionType(FakePlayerUtils.literal(
                    "假人 " + botName + " 已经在执行任务了，先执行 /Tradefinder stop " + botName)).create();
        }
        if (!FakePlayerUtils.worldOf(bot).getRegistryKey().equals(selected.dimension())) {
            throw new SimpleCommandExceptionType(FakePlayerUtils.literal(
                    "假人 " + botName + " 与绑定的讲台不在同一个维度")).create();
        }

        FakePlayerActionScheduler.start(bot, force
                ? new LibrarianTradeForceAction(bot, selected.pos(), enchantment, level, price, context.getSource())
                : new LibrarianTradeFindAction(bot, selected.pos(), enchantment, level, price, context.getSource()));

        feedback(context.getSource(), FakePlayerUtils.literal("开始刷取（" + (force ? "直接刷新" : "原版拆放") + "）：假人 ")
                .formatted(Formatting.GREEN)
                .append(FakePlayerUtils.literal(botName).formatted(Formatting.AQUA))
                .append(FakePlayerUtils.literal(" 在讲台 ").formatted(Formatting.GREEN))
                .append(FakePlayerUtils.literal(posText(selected.pos())).formatted(Formatting.AQUA))
                .append(FakePlayerUtils.literal(" 刷 ").formatted(Formatting.GREEN))
                .append(FakePlayerUtils.literal(enchantmentName(enchantment)
                        + "（等级≥" + level + "，价格≤" + price + "）").formatted(Formatting.YELLOW)), false);
        return 1;
    }

    /** 反馈给命令执行者。[VERSION] ≤1.19.4 为 sendFeedback(Text,boolean)；≥1.20.1 为 Supplier */
    private static void feedback(ServerCommandSource source, Text message, boolean broadcastToOps) {
// IF >= fabric-1.20.1
        source.sendFeedback(() -> message, broadcastToOps);
// ELSE
//        source.sendFeedback(message, broadcastToOps);
// END IF
    }

    /** 命令源的服务器。[VERSION] 1.16.5 为 getMinecraftServer()；≥1.17.1 为 getServer() */
    private static MinecraftServer serverOf(ServerCommandSource source) {
// IF >= fabric-1.17.1
        return source.getServer();
// ELSE
//        return source.getMinecraftServer();
// END IF
    }

    /** 附魔显示名。[VERSION] 1.21+ 用 description()；≤1.20.x 为实例方法 getName(level) */
    private static String enchantmentName(Enchantment enchantment) {
// IF >= fabric-1.21
        return enchantment.description().getString();
// ELSE
//        return enchantment.getName(1).getString();
// END IF
    }

    private static String posText(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
