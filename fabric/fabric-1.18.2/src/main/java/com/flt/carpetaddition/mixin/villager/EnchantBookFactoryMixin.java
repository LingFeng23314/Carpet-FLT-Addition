package com.flt.carpetaddition.mixin.villager;

// IF >= fabric-1.19.4
//import com.flt.carpetaddition.FLTAdditionMod;
//import com.flt.carpetaddition.settings.FLTSettings;
//import net.minecraft.enchantment.Enchantment;
//import net.minecraft.registry.RegistryKeys;
//import net.minecraft.registry.tag.TagKey;
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.ModifyArg;
// END IF

/**
 * 村民交易迅捷潜行附魔书（villagerTradeSwiftSneak，1.19.4~1.20.1 精简版）。
 * @ModifyArg 把 EnchantBookFactory.create 内 Registry.getRandomEntry 的 TagKey 参数换成
 * 自定义标签 flt:tradeable_swift（= 原版 tradeable + swift_sneak，数据驱动定义于 data/flt/tags/enchantment/）；
 * 规则关闭时返回原值，零影响。
 * [VERSION] swift_sneak 1.19+；1.21+ 用 EnchantBookFactoryWindMixin（含风爆），不写嵌套条件。
 */
// IF >= fabric-1.19.4
//@Mixin(net.minecraft.village.TradeOffers.EnchantBookFactory.class)
//public class EnchantBookFactoryMixin {
//
//    private static final TagKey<Enchantment> TAG_SWIFT =
//            TagKey.of(RegistryKeys.ENCHANTMENT, FLTAdditionMod.makeId("flt", "tradeable_swift"));
//
//    @ModifyArg(
//            method = "create",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/registry/Registry;getRandomEntry(Lnet/minecraft/registry/tag/TagKey;Lnet/minecraft/util/math/random/Random;)Ljava/util/Optional;"
//            ),
//            index = 0
//    )
//    private TagKey<Enchantment> flt$tradeable(TagKey<Enchantment> original) {
//        boolean swift = FLTSettings.villagerTradeSwiftSneak;
//        if (!swift) {
//            return original;
//        }
//        return TAG_SWIFT;
//    }
//}
// END IF