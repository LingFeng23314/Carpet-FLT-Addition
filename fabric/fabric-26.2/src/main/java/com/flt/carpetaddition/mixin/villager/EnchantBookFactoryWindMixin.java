package com.flt.carpetaddition.mixin.villager;

import com.flt.carpetaddition.FLTAdditionMod;
import com.flt.carpetaddition.settings.FLTSettings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Optional;

/**
 * 村民附魔书交易扩池（villagerTradeSwiftSneak / villagerTradeWindBurst）。
 *
 * <p>26.x 交易数据驱动：附魔书由 {@code enchant_randomly} 战利品函数生成，候选池取自 options 标签。
 * 本 Mixin 包住 {@code run} 内的 {@code Util.getRandomSafe} 调用，规则开启时把候选池换成
 * flt:tradeable_{swift,wind,both}；原版调用照常执行（空值与警告分支保留）。
 *
 * <p>场景限定：仅当 options 是 {@code #minecraft:tradeable} 或 {@code #minecraft:trades/*}（村庄交易专用）
 * 才替换，避免影响宝箱、钓鱼等同样使用 enchant_randomly 的战利品。
 */
@Mixin(EnchantRandomlyFunction.class)
public class EnchantBookFactoryWindMixin {

    /** 原版字段：options 标签经 codec 解析后为 HolderSet。 */
    @Shadow
    @Final
    private Optional<HolderSet<Enchantment>> options;

    private static final TagKey<Enchantment> TAG_SWIFT = tag("tradeable_swift");
    private static final TagKey<Enchantment> TAG_WIND = tag("tradeable_wind");
    private static final TagKey<Enchantment> TAG_BOTH = tag("tradeable_both");

    private static TagKey<Enchantment> tag(String path) {
        return TagKey.create(Registries.ENCHANTMENT, FLTAdditionMod.makeId("flt", path));
    }

    @WrapOperation(
            method = "run",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Util;getRandomSafe(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/util/Optional;"
            )
    )
    private Optional<Holder<Enchantment>> flt$tradeablePool(List<Holder<Enchantment>> candidates, RandomSource random,
                                                            Operation<Optional<Holder<Enchantment>>> original,
                                                            ItemStack stack, LootContext context) {
        boolean swift = FLTSettings.villagerTradeSwiftSneak;
        boolean wind = FLTSettings.villagerTradeWindBurst;
        if (!swift && !wind || !isVillagerTradeTag()) {
            return original.call(candidates, random);
        }
        try {
            TagKey<Enchantment> tag = swift && wind ? TAG_BOTH : (swift ? TAG_SWIFT : TAG_WIND);
            List<Holder<Enchantment>> pool = context.getResolver().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(tag).stream().toList();
            return original.call(pool, random);
        } catch (Exception e) {
            return original.call(candidates, random);   // 数据文件未加载 → 回退原版
        }
    }

    /** options 是否为村庄交易专用标签（#minecraft:tradeable 或 #minecraft:trades/*）。 */
    private boolean isVillagerTradeTag() {
        return options.flatMap(HolderSet::unwrapKey)
                .filter(key -> key.location().getNamespace().equals("minecraft"))
                .map(key -> {
                    String path = key.location().getPath();
                    return path.equals("tradeable") || path.startsWith("trades/");
                })
                .orElse(false);
    }
}
