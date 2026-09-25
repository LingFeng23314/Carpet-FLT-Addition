package com.flt.carpetaddition.fakeplayer;

// IF >= fabric-1.20.5
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.registry.entry.RegistryEntry;
// END IF
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

import java.util.Map;

/** 在村民报价里找"目标附魔 + 等级达标 + 价格可接受"的那一条。无状态工具，规则 A/B 共用。 */
final class TradeOfferMatcher {
    private TradeOfferMatcher() {
    }

    /** 命中结果：报价索引 + 实际等级 + 价格（绿宝石数） */
    record Match(int index, int level, int price) {
    }

    /** 找不到可接受的报价时返回 null */
    static Match find(TradeOfferList offers, Enchantment target, int requiredLevel, int maxPrice) {
        for (int index = 0; index < offers.size(); index++) {
            TradeOffer offer = offers.get(index);
            int level = matchLevel(offer.getSellItem(), target, requiredLevel);
            if (level < 0) {
                continue;
            }
            int price = offer.getOriginalFirstBuyItem().getCount();
            if (price > maxPrice) {
                continue;
            }
            return new Match(index, level, price);
        }
        return null;
    }

    /** @return 等级（≥ requiredLevel）；不是目标附魔或等级不够 → -1 */
    private static int matchLevel(ItemStack result, Enchantment target, int requiredLevel) {
// IF >= fabric-1.20.5
        ItemEnchantmentsComponent enchantments = result.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (enchantments == null) {
            return -1;
        }
        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
            if (entry.value() == target) {
                int level = enchantments.getLevel(entry);
                return level >= requiredLevel ? level : -1;
            }
        }
        return -1;
// ELSE
        // 1.20.4- ：附魔存于 NBT
//        Map<Enchantment, Integer> enchantments = EnchantmentHelper.get(result);
//        Integer level = enchantments.get(target);
//        if (level == null) {
//            return -1;
//        }
//        return level >= requiredLevel ? level : -1;
// END IF
    }
}
