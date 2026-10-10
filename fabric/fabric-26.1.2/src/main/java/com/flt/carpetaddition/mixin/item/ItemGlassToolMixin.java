package com.flt.carpetaddition.mixin.item;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TintedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 缺失工具修复增强（missingToolsPlus）：指定工具类型挖玻璃获得正确速度。
 * 注入 Item.getDestroySpeed HEAD，玻璃用「身份+类型」判断（普通玻璃/板按单例 ==，染色/遮光按类名）
 * + 工具用物品标签，速度取 Tool 组件首个有 speed 的规则（对齐 fabric-carpet 官方 missingTools）。
 */
@Mixin(Item.class)
public class ItemGlassToolMixin {

    @Inject(method = "getDestroySpeed", at = @At("HEAD"), cancellable = true)
    private void flt$missingToolsPlus(ItemStack stack, BlockState state, CallbackInfoReturnable<Float> cir) {
        if (FLTSettings.missingToolsPlus.equals("#none")) {
            return;
        }
        if (!isGlass(state)) {
            return;
        }
        if (!isMatchingTool(stack)) {
            return;
        }
        Tool tool = stack.get(DataComponents.TOOL);
        if (tool != null) {
            float speed = tool.defaultMiningSpeed();
            for (Tool.Rule rule : tool.rules()) {
                if (rule.speed().isPresent()) {
                    speed = rule.speed().get();
                    break;
                }
            }
            cir.setReturnValue(speed);
        }
    }

    private static boolean isMatchingTool(ItemStack stack) {
        return switch (FLTSettings.missingToolsPlus) {
            case "pickaxe" -> stack.is(itemHolder -> itemHolder.is(ItemTags.PICKAXES));
            case "axe" -> stack.is(itemHolder -> itemHolder.is(ItemTags.AXES));
            case "shovel" -> stack.is(itemHolder -> itemHolder.is(ItemTags.SHOVELS));
            case "hoe" -> stack.is(itemHolder -> itemHolder.is(ItemTags.HOES));
            default -> false;
        };
    }

    /**
     * 玻璃方块判定。
     * ⚠️ 26.1.2 起不能只按类名判断：普通玻璃的 {@code GlassBlock} / 普通玻璃板的
     * {@code GlassPaneBlock} 两个类已被 Mojang 删除，改用通用类实例化
     * （普通玻璃 = {@code TransparentBlock}，普通玻璃板 = {@code IronBarsBlock}），
     * 故按类名判断会漏掉最常见的普通玻璃/玻璃板 → 规则失效。
     * 这里用「方块身份精确匹配」：普通玻璃/玻璃板用 {@code ==} 比对单例，
     * 染色/遮光玻璃沿用类判断。注意 {@code IronBarsBlock} 同时被铁栏杆使用，
     * 故普通玻璃板**只能**用 {@code == Blocks.GLASS_PANE}，不能 instanceof。
     */
    private static boolean isGlass(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.GLASS
                || block == Blocks.GLASS_PANE
                || block instanceof StainedGlassBlock
                || block instanceof StainedGlassPaneBlock
                || block instanceof TintedGlassBlock;
    }
}