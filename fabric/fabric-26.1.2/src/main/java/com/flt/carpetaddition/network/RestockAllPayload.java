package com.flt.carpetaddition.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：客户端投影「假人备货」按钮 → 请求服务端为玩家执行「一键备齐 + 装箱送回」。
 *
 * <p>与单物品取货 {@link ItemFetchPayload} 的区别：本包<b>不带物品与数量</b>，是<b>空载荷</b>。
 * 服务端收到后根据该玩家已上报的材料需求（{@code DemandRegistry}）整体备齐并装箱送回。
 *
 * <p><b>【编码（与客户端 flt-tools 严格一致，勿改）】空载荷：body 无任何字段。</b>
 * 只是借用 payload 通道通知服务端"该玩家点了假人备货"。
 *
 * <p>[VERSION] 26.x：CustomPacketPayload.Type(Identifier) / StreamCodec.unit（空载荷用）。
 */
public record RestockAllPayload() implements CustomPacketPayload {

    /** 通道 ID：carpet-flt-addition:restock_all（客户端 flt-tools 需注册同名通道） */
    public static final Type<RestockAllPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("carpet-flt-addition", "restock_all"));

    public static final StreamCodec<FriendlyByteBuf, RestockAllPayload> CODEC =
            StreamCodec.unit(new RestockAllPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}