package com.flt.carpetaddition.storage;

import com.flt.carpetaddition.FLTAdditionMod;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 容器破坏监听（2026-10-12）：玩家挖掉已登记的箱子时，自动从 {@link StockSource} 清除登记。
 *
 * <p><b>为什么需要</b>：{@link StockSource} 只存坐标、不存内容，一旦箱子被挖掉，坐标就成为
 * "指向空气的失效登记"——不清理的话，{@code /Itemfetcher stock list} 会一直显示它、假人取货还会
 * 白跑一趟。本监听让登记表跟随世界实际状态自动收敛。
 *
 * <p><b>为什么用 Fabric API 事件而不是 mixin</b>：{@code PlayerBlockBreakEvents.AFTER} 是 fabric-api
 * 官方事件（fabric-events-interaction 模块，26.1.2 / 26.2 / 26.3 三个版本都已实证存在）。
 * 零 mixin = 零注入点风险，符合本项目"能不用 mixin 就不用"的取向。
 *
 * <p><b>覆盖边界（诚实说明）</b>：本监听只覆盖<b>玩家手动破坏</b>。爆炸 / 活塞 / {@code /setblock}
 * 等非玩家破坏不会触发——这类残留由 {@link StockScanner#scanAll} / {@link StockScanner#pruneInvalid}
 * 的"失效登记自检"兜底清除（扫描/低频 tick 时发现该坐标已不是容器就清）。
 *
 * <p><b>「钉住的登记」豁免</b>（2026-10-16 用户要求）：潜影盒（可挖起 → 掉落成物品 → 可放回原处）
 * 与预登记展示位（全物品机器“有货才摆出盒子”）都是“位置固定、方块会时有时无”的登记 ——
 * 挖掉/方块消失的瞬间<b>不清登记</b>，取货时会现场校验那格有没有容器（没有就跳过）。
 * 登记时它的身份已记入 {@link StockSource#setPinned}，所有自检也一并跳过它。
 *
 * <p><b>双箱处理</b>：按用户决策，只清除<b>被挖的那一个坐标</b>（不移除整组）。若双箱只挖一半，
 * 另一半仍是有效容器、保留登记；被挖的那半由本监听清掉，剩余 half 在 {@link ContainerGroup#canonical}
 * 归一时会因合并失败退化为单箱，不影响功能。
 */
public final class StockBreakListener {
    private StockBreakListener() {
    }

    /** 注册方块破坏监听。在 {@code FLTAdditionMod.onInitialize()} 里调用一次。 */
    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            // 事件在所有维度/所有玩家（含假人）破坏方块后触发；只关心服务端 + 已登记的容器坐标
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            BlockPos immutable = pos.immutable();
            // ⚠️「钉住的登记」豁免（2026-10-16 用户要求）：潜影盒可以被挖起、掉落成物品、再放回原处；
            //    预登记展示位（全物品“有货才摆出盒子”）也是位置固定、方块会时有时无 ——
            //    挖掉那一刻若清了登记，玩家放回盒子/盒子回来就得重新 /stock add 一遍。
            //    所以这里不清，取货时再现场校验那格有没有容器（没有就跳过，登记照旧保留）。
            //    state 是"破坏前"的方块状态，正好能认出潜影盒；isPinned 兜底兼容登记时已标记的坐标。
            if (ContainerGroup.isPortableContainer(state)
                    || StockSource.isPinned(serverLevel.dimension(), immutable)) {
                return;
            }
            if (StockSource.remove(serverLevel.dimension(), immutable)) {
                // 立即落盘：即使服务器随后被强杀，清除结果也不会回滚成"复活一个已挖掉的箱子"
                StockPersistence.save(serverLevel.getServer());
                FLTAdditionMod.LOGGER.info("[FLT] 容器被破坏，已从库存源移除 {} @({},{},{})（剩余 {} 个容器）",
                        serverLevel.dimension().identifier(),
                        immutable.getX(), immutable.getY(), immutable.getZ(),
                        StockSource.size());
            }
        });
    }
}
