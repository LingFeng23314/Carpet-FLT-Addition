package com.flt.carpetaddition.fakeplayer;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** 在村民报价里找"目标附魔 + 等级达标 + 价格可接受"的那一条。无状态工具，规则 A/B 共用。 */
final class TradeOfferMatcher {
    private TradeOfferMatcher() {
    }

    /** 命中结果：报价索引 + 实际等级 + 价格（绿宝石数） */
    record Match(int index, int level, int price) {
    }

    /** 找不到可接受的报价时返回 null */
    static Match find(MerchantOffers offers, Enchantment target, int requiredLevel, int maxPrice) {
        for (int index = 0; index < offers.size(); index++) {
            MerchantOffer offer = offers.get(index);
            int level = matchLevel(offer.getResult(), target, requiredLevel);
            if (level < 0) {
                continue;
            }
            int price = offer.getBaseCostA().getCount();
            if (price > maxPrice) {
                continue;
            }
            return new Match(index, level, price);
        }
        return null;
    }

    /** @return 等级（≥ requiredLevel）；不是目标附魔或等级不够 → -1 */
    private static int matchLevel(ItemStack result, Enchantment target, int requiredLevel) {
        ItemEnchantments enchantments = result.get(DataComponents.STORED_ENCHANTMENTS);
        if (enchantments == null) {
            return -1;
        }
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            if (entry.getKey().value() == target) {
                int level = entry.getIntValue();
                return level >= requiredLevel ? level : -1;
            }
        }
        return -1;
    }
}