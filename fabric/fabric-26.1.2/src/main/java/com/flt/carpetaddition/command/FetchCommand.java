package com.flt.carpetaddition.command;

import com.flt.carpetaddition.fakeplayer.ContainerWithdrawAction;
import com.flt.carpetaddition.fakeplayer.FakePlayerActionScheduler;
import com.flt.carpetaddition.fakeplayer.FakePlayerFactory;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * /flt fetch 命令（组合拳二期：假人取货的指令兜底 / 手动入口）。
 *
 * <p>用法：准星对准一个容器，执行
 * <pre>/flt fetch &lt;假人名&gt; &lt;物品&gt; [数量]</pre>
 * 数量省略 = 尽量取（取光容器里该物品）。命令会确保假人存在（不存在则分步建档），
 * 然后让假人传送到容器旁 → 打开容器 → 把该物品搬进自己背包。
 *
 * <p>不强制 OP 权限（对齐现有服务端工具命令风格）。
 */
public final class FetchCommand {
    private static final SimpleCommandExceptionType ERROR_NOT_CONTAINER = new SimpleCommandExceptionType(
            Component.literal("请把准星对准一个容器（箱子/木桶/潜影盒等），再执行 /flt fetch"));

    private FetchCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("flt")
                .then(Commands.literal("fetch")
                        .then(Commands.argument("bot", StringArgumentType.word())
                                .then(Commands.argument("item", ResourceArgument.resource(buildContext, Registries.ITEM))
                                        .executes(context -> fetch(context, -1))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 2304))
                                                .executes(context -> fetch(context,
                                                        IntegerArgumentType.getInteger(context, "count"))))))));
    }

    private static int fetch(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String botName = StringArgumentType.getString(context, "bot");
        Holder.Reference<Item> itemHolder = ResourceArgument.getResource(context, "item", Registries.ITEM);
        Item item = itemHolder.value();

        HitResult hitResult = player.pick(6.0D, 0.0F, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            throw ERROR_NOT_CONTAINER.create();
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos containerPos = blockHitResult.getBlockPos().immutable();
        BlockEntity blockEntity = level.getBlockEntity(containerPos);
        if (!(blockEntity instanceof Container)) {
            throw ERROR_NOT_CONTAINER.create();
        }

        MinecraftServer server = context.getSource().getServer();
        CommandSourceStack source = context.getSource();
        Vec3 spawnPos = new Vec3(containerPos.getX() + 0.5D, containerPos.getY() + 1.0D, containerPos.getZ() + 0.5D);
        String itemName = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
        String countText = count > 0 ? String.valueOf(count) : "尽量多";

        boolean ready = FakePlayerFactory.ensureOrCreate(server, botName, level, spawnPos, bot -> {
            FakePlayerActionScheduler.start(bot, new ContainerWithdrawAction(bot, containerPos, item, count,
                    message -> source.sendSuccess(() -> Component.literal("[FLT] 假人 ")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(botName).withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(" 取货结束：" + message).withStyle(ChatFormatting.GREEN)), false)));
            source.sendSuccess(() -> Component.literal("[FLT] 假人 ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(botName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" 开始取 " + itemName + "（" + countText + "）").withStyle(ChatFormatting.GREEN)), false);
        });

        if (!ready) {
            source.sendSuccess(() -> Component.literal("[FLT] 假人 ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(botName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" 正在建档，完成后会自动开始取货").withStyle(ChatFormatting.YELLOW)), false);
        }
        return 1;
    }
}