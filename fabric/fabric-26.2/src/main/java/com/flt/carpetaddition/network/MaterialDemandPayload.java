package com.flt.carpetaddition.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C2S：客户端上报"当前投影的材料需求"（组合拳二期）。
 *
 * <p>客户端装了 Litematica 时，材料清单本来就在客户端算好了（不必服务端解析 .litematic）。
 * 客户端加载/切换投影后把 {@code 物品ID → 所需总数} 上报给服务端，服务端据此算缺口、
 * 派假人从箱子取货备齐。
 *
 * <p>编码：VarInt 条目数 + 每项 (Identifier 物品ID, VarInt 数量)。用 Identifier 而非字符串，
 * 与 26.x 的注册表命名保持一致（如 minecraft:stone）。
 *
 * <p>[VERSION] 26.x: CustomPacketPayload.Type(Identifier) / FriendlyByteBuf.writeIdentifier / readIdentifier。
 */
public record MaterialDemandPayload(Map<Identifier, Integer> demands) implements CustomPacketPayload {

    /** 通道 ID：carpet-flt-addition:material_demand（客户端 flt-tools 需注册同名通道） */
    public static final Type<MaterialDemandPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("carpet-flt-addition", "material_demand"));

    public static final StreamCodec<FriendlyByteBuf, MaterialDemandPayload> CODEC =
            StreamCodec.of(MaterialDemandPayload::write, MaterialDemandPayload::read);

    /** 防御性拷贝 + 不可变，避免上报方后续修改影响服务端持有的状态 */
    public MaterialDemandPayload {
        demands = Collections.unmodifiableMap(new LinkedHashMap<>(demands));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    private static void write(FriendlyByteBuf buf, MaterialDemandPayload payload) {
        buf.writeVarInt(payload.demands.size());
        for (Map.Entry<Identifier, Integer> entry : payload.demands.entrySet()) {
            buf.writeIdentifier(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    private static MaterialDemandPayload read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<Identifier, Integer> demands = new LinkedHashMap<>(Math.max(16, size));
        for (int i = 0; i < size; i++) {
            demands.put(buf.readIdentifier(), buf.readVarInt());
        }
        return new MaterialDemandPayload(demands);
    }
}