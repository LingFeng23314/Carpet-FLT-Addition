package com.flt.carpetaddition.command;

import com.flt.carpetaddition.storage.DemandRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * /flt demand 命令（组合拳二期）：设置/查看"本玩家的投影材料需求"。
 *
 * <p>正常流程下需求由客户端（flt-tools + Litematica）上报 {@code MaterialDemandPayload}；
 * 本命令作为<b>手动入口 / 测试兜底</b>，不依赖客户端也能验证自动备货链路。
 *
 * <pre>
 * /flt demand set &lt;物品&gt; &lt;数量&gt;   设置该材料的所需总数（覆盖同物品旧值，保留其它物品）
 * /flt demand show                 查看当前需求
 * /flt demand clear                清空需求
 * </pre>
 */
public final class DemandCommand {
    private DemandCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("flt")
                .then(Commands.literal("demand")
                        .then(Commands.literal("set")
                                .then(Commands.argument("item", ResourceArgument.resource(buildContext, Registries.ITEM))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 1_000_000))
                                                .executes(DemandCommand::set))))
                        .then(Commands.literal("show").executes(DemandCommand::show))
                        .then(Commands.literal("clear").executes(DemandCommand::clear))));
    }

    private static int set(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Holder.Reference<Item> itemHolder = ResourceArgument.getResource(context, "item", Registries.ITEM);
        int count = IntegerArgumentType.getInteger(context, "count");
        Identifier itemId = BuiltInRegistries.ITEM.getKey(itemHolder.value());

        Map<Identifier, Integer> demand = new HashMap<>(DemandRegistry.get(player.getUUID()));
        demand.put(itemId, count);
        DemandRegistry.set(player.getUUID(), player.getName().getString(), demand);

        context.getSource().sendSuccess(() -> Component.literal("[FLT] 已把 ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(itemId.toString()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" 的需求设为 " + count + "（当前共 " + demand.size() + " 种材料）")
                        .withStyle(ChatFormatting.GREEN)), false);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Map<Identifier, Integer> demand = DemandRegistry.get(player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal("[FLT] 当前材料需求（" + demand.size() + " 种）：")
                .withStyle(ChatFormatting.GREEN), false);
        for (Map.Entry<Identifier, Integer> entry : demand.entrySet()) {
            context.getSource().sendSuccess(() -> Component.literal("  - ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(entry.getKey().toString()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" × " + entry.getValue()).withStyle(ChatFormatting.GRAY)), false);
        }
        return demand.size();
    }

    private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int before = DemandRegistry.get(player.getUUID()).size();
        DemandRegistry.clear(player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal("[FLT] 已清空你的材料需求（原 " + before + " 种）")
                .withStyle(ChatFormatting.GREEN), false);
        return before;
    }
}