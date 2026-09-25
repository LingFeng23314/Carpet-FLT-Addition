package com.flt.carpetaddition.mixin.item;

import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.block.StainedGlassPaneBlock;
//# [VERSION] TintedGlassBlock 1.17.1+ 才有
// IF >= fabric-1.17.1
import net.minecraft.block.TintedGlassBlock;
// END IF
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
//# [VERSION] 1.19.4+ 用 ItemTags 工具标签；1.18.2- 用工具类 instanceof
// IF <= fabric-1.18.2
//import net.minecraft.item.AxeItem;
//import net.minecraft.item.HoeItem;
//import net.minecraft.item.PickaxeItem;
//import net.minecraft.item.ShovelItem;
// END IF
//# [VERSION] ItemTags 包路径分界 fabric-1.19.4：1.19.4+ net.minecraft.registry.tag；1.18.2- net.minecraft.tag
// IF >= fabric-1.19.4
import net.minecraft.registry.tag.ItemTags;
// ELSE IF <= fabric-1.18.2
//import net.minecraft.tag.ItemTags;
// ELSE
//#    （理论不可达）
// END IF
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
// IF >= fabric-1.20.5
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ToolComponent;
// END IF

/**
 * 缺失工具修复增强（missingToolsPlus）：指定工具类型挖玻璃获得正确速度。
 * 注入 Item.getMiningSpeed（1.20.5+ 组件）/ getMiningSpeedMultiplier（1.20.4-），
 * 玻璃用类型判断 + 工具用 ItemTags/工具类，速度取 Tool 组件首个有 speed 的规则
 * （对齐 fabric-carpet 官方 missingTools）。
 * [VERSION] 1.20.5 引入组件系统：1.20.5+ 用 ToolComponent 读速度；1.20.4- 用 probe 方块。
 */
@Mixin(Item.class)
public class ItemGlassToolMixin {

// IF >= fabric-1.20.5
//#    /** 1.20.5+ 组件系统：返回 ToolComponent 中的正确速度 */
    @Inject(method = "getMiningSpeed", at = @At("HEAD"), cancellable = true)
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
        ToolComponent tool = stack.get(DataComponentTypes.TOOL);
        if (tool != null) {
            float speed = tool.defaultMiningSpeed();
            for (ToolComponent.Rule rule : tool.rules()) {
                if (rule.speed().isPresent()) {
                    speed = rule.speed().get();
                    break;
                }
            }
            cir.setReturnValue(speed);
        }
    }
// ELSE IF <= fabric-1.20.4
///**
//* 1.20.4- 无组件系统：用 probe 方块（镐→石头/斧→原木/锹→泥土/锄→干草块，各工具 mineable tag 内）
//* 调原版方法拿"工具对可挖掘方块"的速度。probe 不是玻璃→递归调用原版方法不进本注入，安全。
//*/
//@Inject(method = "getMiningSpeedMultiplier", at = @At("HEAD"), cancellable = true)
//private void flt$missingToolsPlus(ItemStack stack, BlockState state, CallbackInfoReturnable<Float> cir) {
//if (FLTSettings.missingToolsPlus.equals("#none")) {
//return;
//}
//if (!isGlass(state)) {
//return;
//}
//if (!isMatchingTool(stack)) {
//return;
//}
//BlockState probe = switch (FLTSettings.missingToolsPlus) {
//case "pickaxe" -> Blocks.STONE.getDefaultState();
//case "axe" -> Blocks.OAK_LOG.getDefaultState();
//case "shovel" -> Blocks.DIRT.getDefaultState();
//case "hoe" -> Blocks.HAY_BLOCK.getDefaultState();
//default -> null;
//};
//if (probe != null) {
//cir.setReturnValue(((Item) (Object) this).getMiningSpeedMultiplier(stack, probe));
//}
//}
// ELSE
//#    （理论不可达）
// END IF

    /** 工具类型判定：[VERSION] 1.19.4+ 用 ItemTags；1.18.2- 用工具类 instanceof */
    private static boolean isMatchingTool(ItemStack stack) {
        return switch (FLTSettings.missingToolsPlus) {
// IF >= fabric-1.19.4
            case "pickaxe" -> stack.isIn(ItemTags.PICKAXES);
            case "axe" -> stack.isIn(ItemTags.AXES);
            case "shovel" -> stack.isIn(ItemTags.SHOVELS);
            case "hoe" -> stack.isIn(ItemTags.HOES);
// ELSE IF <= fabric-1.18.2
//case "pickaxe" -> stack.getItem() instanceof PickaxeItem;
//case "axe" -> stack.getItem() instanceof AxeItem;
//case "shovel" -> stack.getItem() instanceof ShovelItem;
//case "hoe" -> stack.getItem() instanceof HoeItem;
// ELSE
//#    （理论不可达）
// END IF
            default -> false;
        };
    }

    /** 玻璃方块判定：纯类型判断（比 SoundGroup.GLASS 更精确） */
    private static boolean isGlass(BlockState state) {
        Block block = state.getBlock();
        return block instanceof StainedGlassBlock
                || block instanceof StainedGlassPaneBlock
// IF >= fabric-1.17.1
//#                || block instanceof TintedGlassBlock
// END IF
        ;
    }
}