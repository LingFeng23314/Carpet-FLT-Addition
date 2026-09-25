package com.flt.carpetaddition.mixin.menu;

// IF >= fabric-1.21
import com.flt.carpetaddition.settings.FLTSettings;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GrindstoneScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
// END IF

/**
 * 砂轮附魔复制（grindstoneEnchantmentDuplication，1.21+）。
 * 复刻 24w10a~24w11a 快照 bug：下槽附魔直接复制进上槽输入栈。
 * 原版 combineItems 内 transferEnchantments 第一参传上槽<b>副本</b>（copyWithCount），输入栈不被改动；
 * bug 版传上槽输入原栈 → 下槽附魔就地写进上槽。规则开启时人为调用
 * {@code transferEnchantments(item1, item2)} 复现（item1=上槽、item2=下槽），随后照常走原逻辑。
 * [VERSION] 砂轮附魔数据化体系仅 1.21+ 存在（1.20.1- 无 transferEnchantments）。
 */
// IF >= fabric-1.21
@Mixin(GrindstoneScreenHandler.class)
public abstract class GrindstoneEnchantmentDuplicationMixin {

    @Invoker("transferEnchantments")
    protected abstract void flt$invokeTransferEnchantments(ItemStack target, ItemStack source);

    @WrapMethod(method = "combineItems")
    private ItemStack flt$grindstoneEnchantmentDuplication(ItemStack item1, ItemStack item2,
                                                           Operation<ItemStack> original) {
        if (FLTSettings.grindstoneEnchantmentDuplication) {
            flt$invokeTransferEnchantments(item1, item2);
        }
        return original.call(item1, item2);
    }
}
// END IF
