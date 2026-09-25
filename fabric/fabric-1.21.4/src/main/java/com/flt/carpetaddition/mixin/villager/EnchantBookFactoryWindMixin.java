package com.flt.carpetaddition.mixin.villager;

// IF >= fabric-1.21
import com.flt.carpetaddition.FLTAdditionMod;
import com.flt.carpetaddition.settings.FLTSettings;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
// END IF

/**
 * 村民交易附魔书增强（villagerTradeSwiftSneak + villagerTradeWindBurst，1.21+ 完整版）。
 * @ModifyArg 把 EnchantBookFactory.create 内 Registry.getRandomEntry 的 TagKey 参数换成自定义标签
 * （flt:tradeable_swift / tradeable_wind / tradeable_both = 原版 tradeable + 对应附魔，
 * 数据驱动定义于 data/flt/tags/enchantment/）；规则全关时返回原值，零影响。
 * [VERSION] 1.21+ 含风爆；1.19.4/1.20.1 用 EnchantBookFactoryMixin（精简版仅迅捷），
 * 不写嵌套条件——ModMultiVersion 不处理嵌套块，由 mixins.json5 按版本选择。
 */
// IF >= fabric-1.21
@Mixin(net.minecraft.village.TradeOffers.EnchantBookFactory.class)
public class EnchantBookFactoryWindMixin {

    private static final TagKey<Enchantment> TAG_SWIFT =
            TagKey.of(RegistryKeys.ENCHANTMENT, FLTAdditionMod.makeId("flt", "tradeable_swift"));
    private static final TagKey<Enchantment> TAG_WIND =
            TagKey.of(RegistryKeys.ENCHANTMENT, FLTAdditionMod.makeId("flt", "tradeable_wind"));
    private static final TagKey<Enchantment> TAG_BOTH =
            TagKey.of(RegistryKeys.ENCHANTMENT, FLTAdditionMod.makeId("flt", "tradeable_both"));

    /** 按规则状态替换选书标签（规则全关时返回原版） */
    @ModifyArg(
            method = "create",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/registry/Registry;getRandomEntry(Lnet/minecraft/registry/tag/TagKey;Lnet/minecraft/util/math/random/Random;)Ljava/util/Optional;"
            ),
            index = 0
    )
    private TagKey<Enchantment> flt$tradeable(TagKey<Enchantment> original) {
        boolean swift = FLTSettings.villagerTradeSwiftSneak;
        boolean wind = FLTSettings.villagerTradeWindBurst;
        if (swift && wind) {
            return TAG_BOTH;
        }
        if (!swift && !wind) {
            return original;
        }
        return swift ? TAG_SWIFT : TAG_WIND;
    }
}
// END IF