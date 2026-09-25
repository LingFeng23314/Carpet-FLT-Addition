package com.flt.carpetaddition.mixin.item;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
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
 * 注入 Item.getDestroySpeed HEAD，玻璃用类型判断（StainedGlass/Pane/Tinted）+ 工具用物品标签，
 * 速度取 Tool 组件首个有 speed 的规则（对齐 fabric-carpet 官方 missingTools）。
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

    /** 玻璃方块判定：纯类型判断（比 SoundGroup.GLASS 更精确） */
    private static boolean isGlass(BlockState state) {
        Block block = state.getBlock();
        return block instanceof StainedGlassBlock
                || block instanceof StainedGlassPaneBlock
                || block instanceof TintedGlassBlock;
    }
}