package com.flt.carpetaddition.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：客户端请求一次「仓库库存内容」（组合拳二期·仓储可见性）。
 *
 * <p>为什么需要它：{@link FakePlayerStockPayload} 推的是<b>假人身上</b>已备齐的计数，
 * 客户端仍然不知道<b>仓库里到底有什么货</b>。于是物品选择器只能从全部注册物品里挑，
 * 玩家可能挑到仓库里没有的物品 → 服务端回「未派单(无货)」。
 * 有了仓库内容（{@link StockContentsPayload}），客户端就能在界面上标注"仓库现有 N"。
 *
 * <p><b>为什么用「客户端主动查询」而不是「服务端事件推送」</b>：
 * 仓库内容由 {@code StockScanner.scanAll(server)} 实时遍历容器得出，而<b>箱内物品会被玩家
 * 随时手动搬动</b>——服务端<b>没有</b>任何容器内容变更监听（{@code StockSource} 只在
 * add/remove/addarea/clear 时变更登记表）。所以"进服时推送 / 登记表变更时推送"拿到的都是
 * <b>过期快照</b>，玩家看着界面上的数量却取不到货，体验比不显示更差。
 * 查询-响应模型拿到的永远是"这一刻"的真实内容。
 *
 * <p>本 payload 是<b>空的</b>：不需要携带任何参数（查询目标 = 该玩家所在服务器的全部已登记容器）。
 * 空载荷用 {@code StreamCodec.unit(实例)} 编解码（javap 实证：26.x 的
 * {@code net.minecraft.network.codec.StreamCodec} 有 {@code public static <B,V> StreamCodec<B,V> unit(V)}）。
 *
 * <p>[VERSION] 26.x: CustomPacketPayload.Type(Identifier) / StreamCodec.unit。
 */
public record StockQueryPayload() implements CustomPacketPayload {

    /** 通道 ID：carpet-flt-addition:stock_query（客户端 flt-tools 需注册同名通道） */
    public static final Type<StockQueryPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("carpet-flt-addition", "stock_query"));

    /** 空载荷编解码器：读写都不消耗字节，永远返回同一个不可变实例 */
    public static final StreamCodec<FriendlyByteBuf, StockQueryPayload> CODEC =
            StreamCodec.unit(new StockQueryPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
