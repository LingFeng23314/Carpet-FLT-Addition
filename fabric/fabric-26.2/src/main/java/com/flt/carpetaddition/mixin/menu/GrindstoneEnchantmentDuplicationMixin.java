package com.flt.carpetaddition.mixin.menu;

import com.flt.carpetaddition.settings.FLTSettings;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 砂轮附魔复制（grindstoneEnchantmentDuplication）。
 *
 * <p>复刻 24w10a~24w11a 的快照 bug：下槽物品的附魔直接复制进上槽物品。
 * 原版 26.1.2 的 mergeItems 内，mergeEnchantsFrom 第一参传的是上槽<b>副本</b>，输入栈不被改动；
 * bug 版把它传成上槽输入原栈，导致下槽附魔就地写进上槽。这里规则开启时人为调用
 * {@code mergeEnchantsFrom(item1, item2)} 复现（item1=上槽、item2=下槽），随后照常交给原逻辑。
 */
@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneEnchantmentDuplicationMixin {

    @Invoker("mergeEnchantsFrom")
    protected abstract void flt$invokeMergeEnchantsFrom(ItemStack target, ItemStack source);

    @WrapMethod(method = "mergeItems")
    private ItemStack flt$grindstoneEnchantmentDuplication(ItemStack item1, ItemStack item2,
                                                           Operation<ItemStack> original) {
        if (FLTSettings.grindstoneEnchantmentDuplication) {
            flt$invokeMergeEnchantsFrom(item1, item2);
        }
        return original.call(item1, item2);
    }
}