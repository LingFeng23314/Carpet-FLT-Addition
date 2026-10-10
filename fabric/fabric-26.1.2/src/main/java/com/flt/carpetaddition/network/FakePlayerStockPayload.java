package com.flt.carpetaddition.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S2C：服务端把"假人身上已备齐的物品计数"推给客户端（组合拳二期）。
 *
 * <p>原因：原版服务器<b>不同步</b>容器/实体背包 NBT，客户端也读不到假人背包，
 * 所以 Litematica 材料列表的"已有数量"默认只算玩家自己背包。服务端把假人身上的
 * {@code 物品ID → 数量} 推给客户端后，客户端 mixin 在
 * {@code MaterialListUtils.updateAvailableCounts} 的 TAIL 把这份计数合并进材料列表，
 * 玩家就能在投影里看到"假人已经帮你备了多少"。
 *
 * <p>编码同 {@link MaterialDemandPayload}：VarInt 条目数 + 每项 (Identifier, VarInt)。
 *
 * <p>[VERSION] 26.x: CustomPacketPayload.Type(Identifier) / FriendlyByteBuf.writeIdentifier。
 */
public record FakePlayerStockPayload(Map<Identifier, Integer> stock) implements CustomPacketPayload {

    /** 通道 ID：carpet-flt-addition:fakeplayer_stock（客户端 flt-tools 需注册同名通道） */
    public static final Type<FakePlayerStockPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("carpet-flt-addition", "fakeplayer_stock"));

    public static final StreamCodec<FriendlyByteBuf, FakePlayerStockPayload> CODEC =
            StreamCodec.of(FakePlayerStockPayload::write, FakePlayerStockPayload::read);

    public FakePlayerStockPayload {
        stock = Collections.unmodifiableMap(new LinkedHashMap<>(stock));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    private static void write(FriendlyByteBuf buf, FakePlayerStockPayload payload) {
        buf.writeVarInt(payload.stock.size());
        for (Map.Entry<Identifier, Integer> entry : payload.stock.entrySet()) {
            buf.writeIdentifier(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    private static FakePlayerStockPayload read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<Identifier, Integer> stock = new LinkedHashMap<>(Math.max(16, size));
        for (int i = 0; i < size; i++) {
            stock.put(buf.readIdentifier(), buf.readVarInt());
        }
        return new FakePlayerStockPayload(stock);
    }
}