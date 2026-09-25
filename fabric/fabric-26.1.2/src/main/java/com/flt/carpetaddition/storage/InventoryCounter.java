package com.flt.carpetaddition.storage;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家（含假人）背包内容统计：{@code 物品ID → 总数}（走 {@link StackCounter} 统一规则，混装潜影盒不计入）。
 *
 * <p>用途：自动备货算"假人身上已备了多少"（缺口 = 需求 − 已有）；也是 S2C 回推给客户端的数据源。
 *
 * <p>独立成类（而非塞进 {@code FakePlayerUtils}）：26.3 的 {@code FakePlayerUtils} 属"版本专属适配文件"
 * （工具类层级/签名差异），sync 镜像时会跳过它 → 公共工具方法放在那里会漏同步。本类不在排除表，三版自动同步。
 */
public final class InventoryCounter {
    private InventoryCounter() {
    }

    /** 统计玩家（含假人）背包里 物品ID → 总数 */
    public static Map<Identifier, Integer> count(ServerPlayer player) {
        Map<Identifier, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            merge(counts, inventory.getItem(i));
        }
        return counts;
    }

    private static void merge(Map<Identifier, Integer> counts, ItemStack stack) {
        StackCounter.Count count = StackCounter.count(stack);
        if (count == null) {
            return;
        }
        counts.merge(count.itemId(), count.count(), Integer::sum);
    }
}